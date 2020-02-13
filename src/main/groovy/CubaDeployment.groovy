/*
 * Copyright (c) 2008-2016 Haulmont.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

import com.haulmont.gradle.dependency.DependencyResolver
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

import static com.haulmont.gradle.dependency.DependencyResolver.getLibraryDefinition

class CubaDeployment extends DefaultTask {
    public static final String INHERITED_JAR_NAMES = 'inheritedDeployJarNames'

    def jarNames = new HashSet()
    def appName
    Closure doAfter
    def tomcatRootDir = new File(project.cuba.tomcat.dir).canonicalPath
    def webcontentExclude = []
    def dbScriptsExcludes = []

    def sharedlibResolve = true

    CubaDeployment() {
        setDescription('Deploys applications for local usage')
        setGroup('Deployment')
    }

    Set getAllJarNames() {
        Set res = new HashSet()
        res.addAll(jarNames)
        if (project.hasProperty(INHERITED_JAR_NAMES)) {
            def inheritedJarNames = project[INHERITED_JAR_NAMES]
            res.addAll(inheritedJarNames)
        }
        return res
    }

    @TaskAction
    void deploy() {
        if (project.hasProperty(INHERITED_JAR_NAMES)) {
            def inheritedJarNames = project[INHERITED_JAR_NAMES]
            project.logger.info("[CubaDeployment] adding inherited JAR names: ${inheritedJarNames}")
            jarNames.addAll(inheritedJarNames)
        }

        if (!tomcatRootDir)
            tomcatRootDir = new File(project.cuba.tomcat.dir).canonicalPath

        project.logger.info("[CubaDeployment] copying from configurations.jdbc to ${tomcatRootDir}/lib")

        def tomcatLibsDir = project.file("${tomcatRootDir}/lib")
        def tomcatLibsAbsolutePath = tomcatLibsDir.absolutePath

        project.copy {
            from project.configurations.jdbc {
                include { details ->
                    File file = details.file

                    if (!isDependencyDeploymentRequired(tomcatLibsDir, file)) {
                        return false
                    }

                    return !file.absolutePath.startsWith(tomcatLibsAbsolutePath)
                }
            }
            into tomcatLibsDir
        }

        project.logger.info("[CubaDeployment] copying from configurations.server to ${tomcatRootDir}/lib")

        List<String> copiedToServerLib = []
        if (project.configurations.find { it.name == 'server' }) {
            project.copy {
                from project.configurations.server {
                    include { details ->
                        File file = details.file

                        if (!isDependencyDeploymentRequired(tomcatLibsDir, file) || file.absolutePath.startsWith(tomcatLibsAbsolutePath)) {
                            return false
                        }

                        copiedToServerLib.add(file.name)
                        return true
                    }
                }
                into tomcatLibsDir
            }
        }

        List<String> copiedToSharedLib = []

        project.logger.info("[CubaDeployment] copying shared libs from configurations.runtime")
        def sharedLibDir = new File("${tomcatRootDir}/shared/lib")
        project.copy {
            from project.configurations.runtime
            into sharedLibDir
            include { details ->
                File file = details.file

                if (!isDependencyDeploymentRequired(sharedLibDir, file)) {
                    return false
                }

                def libraryName = getLibraryDefinition(file.name).name
                if (jarNames.contains(libraryName)) {
                    return false
                }

                copiedToSharedLib.add(file.name)

                return true
            }
        }

        List<String> copiedToAppLib = []

        project.logger.info("[CubaDeployment] copying app libs from configurations.runtime")
        def appLibDir = new File("${tomcatRootDir}/webapps/$appName/WEB-INF/lib")
        project.copy {
            from project.configurations.runtime
            from project.libsDir
            into appLibDir
            include { details ->
                File file = details.file

                if (!isDependencyDeploymentRequired(appLibDir, file)) {
                    return false
                }

                def libraryName = getLibraryDefinition(file.name).name

                if (!jarNames.contains(libraryName)) {
                    return false
                }

                copiedToAppLib.add(file.name)

                return true
            }
        }

        if (project.configurations.getAsMap().dbscripts) {
            project.logger.info("[CubaDeployment] copying dbscripts from ${project.buildDir}/db to ${tomcatRootDir}/webapps/$appName/WEB-INF/db")

            // remove scripts that do not exist in project
            def dbScriptsDir = project.file("${tomcatRootDir}/webapps/$appName/WEB-INF/db")
            if (dbScriptsDir.exists()) {
                dbScriptsDir.deleteDir()
            }

            project.copy {
                from "${project.buildDir}/db"
                into dbScriptsDir
                excludes = dbScriptsExcludes
            }
        }

        if (project.configurations.getAsMap().webcontent) {
            def excludePatterns = ['**/web.xml'] + webcontentExclude
            project.configurations.webcontent.files.each { dep ->
                project.logger.info("[CubaDeployment] copying webcontent from $dep.absolutePath to ${tomcatRootDir}/webapps/$appName")
                project.copy {
                    from project.zipTree(dep.absolutePath)
                    into "${tomcatRootDir}/webapps/$appName"
                    excludes = excludePatterns
                    includeEmptyDirs = false
                }
            }
        }

        project.logger.info("[CubaDeployment] copying webcontent from ${project.buildDir}/web to ${tomcatRootDir}/webapps/$appName")
        project.copy {
            from "${project.buildDir}/web"
            into "${tomcatRootDir}/webapps/$appName"
        }

        project.logger.info("[CubaDeployment] copying from web to ${tomcatRootDir}/webapps/$appName")
        project.copy {
            from 'web'
            into "${tomcatRootDir}/webapps/$appName"
        }

        if (sharedlibResolve) {
            def resolver = new DependencyResolver(new File(tomcatRootDir), logger)
            if (!copiedToSharedLib.isEmpty()) {
                resolver.resolveDependencies(sharedLibDir, copiedToSharedLib)
            }
            resolver.resolveDependencies(appLibDir, copiedToAppLib)
            resolver.resolveDependencies(tomcatLibsDir, copiedToServerLib)
        }

        File logbackConfig = new File(project.rootProject.rootDir, 'etc/logback.xml')
        if (logbackConfig.exists()) {
            project.logger.info("[CubaDeployment] copying etc/logback.xml to ${project.cuba.appHome}")
            project.copy {
                from logbackConfig
                into project.cuba.appHome
            }
        }

        if (doAfter) {
            project.logger.info("[CubaDeployment] calling doAfter")
            doAfter.call()
        }

        def webXml = new File("${tomcatRootDir}/webapps/$appName/WEB-INF/web.xml")
        project.logger.info("[CubaDeployment] touch ${tomcatRootDir}/webapps/$appName/WEB-INF/web.xml")
        webXml.setLastModified(System.currentTimeMillis())
    }

    protected boolean isDependencyDeploymentRequired(File targetDir, File libFile) {
        String name = libFile.name

        if (!name.endsWith('.jar')) {
            return false
        }

        if (name.endsWith('-sources.jar') || name.endsWith('-tests.jar') || name.endsWith('-themes.jar')) {
            return false
        }

        def targetFile = new File(targetDir, name)
        if (targetFile.exists()) {
            if (targetFile.lastModified() >= libFile.lastModified()) {
                project.logger.info("[CubaDeployment] skipping library '{}' since Tomcat already contains the newer " +
                        "version of the same file", name)
                return false
            } else {
                project.logger.info("[CubaDeployment] replacing library '{}' with the newer version", name)
            }
        }

        return true
    }

    void appJars(Object... names) {
        if (names) {
            def namesList = names.collect { String.valueOf(it) }
            jarNames.addAll(namesList)
        }
    }

    void appJar(String name) {
        jarNames.add(name)
    }
}