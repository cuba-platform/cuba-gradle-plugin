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
import org.apache.commons.lang3.StringUtils
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
    def deploy() {
        if (project.hasProperty(INHERITED_JAR_NAMES)) {
            def inheritedJarNames = project[INHERITED_JAR_NAMES]
            project.logger.info("[CubaDeployment] adding inherited JAR names: ${inheritedJarNames}")
            jarNames.addAll(inheritedJarNames)
        }

        if (!tomcatRootDir)
            tomcatRootDir = new File(project.cuba.tomcat.dir).canonicalPath

        project.logger.info("[CubaDeployment] copying from configurations.jdbc to ${tomcatRootDir}/lib")
        project.copy {
            from project.configurations.jdbc {
                exclude {f ->
                    def libsPath = "${tomcatRootDir}/lib".toString()
                    String fileName = f.file.name

                    if (new File(libsPath, fileName).exists()) {
                        return true
                    }

                    f.file.absolutePath.startsWith(project.file("${tomcatRootDir}/lib/").absolutePath)
                }
            }
            into "${tomcatRootDir}/lib"
        }
        project.logger.info("[CubaDeployment] copying shared libs from configurations.runtime")

        File sharedLibDir = new File("${tomcatRootDir}/shared/lib")
        List<String> copiedToSharedLib = []
        project.copy {
            from project.configurations.runtime
            into "${tomcatRootDir}/shared/lib"
            include { details ->
                String name = details.file.name

                if (new File(sharedLibDir, name).exists() && !name.contains("-SNAPSHOT")) {
                    return false
                }

                if (!name.endsWith('.jar')) {
                    return false
                }

                def libraryName = getLibraryDefinition(name).name

                if (!(name.endsWith('-sources.jar')) && !name.endsWith('-tests.jar') && !jarNames.contains(libraryName)) {
                    copiedToSharedLib.add(name)

                    return true
                }

                return false
            }
        }

        File appLibDir = new File("${tomcatRootDir}/webapps/$appName/WEB-INF/lib")
        List<String> copiedToAppLib = []
        project.logger.info("[CubaDeployment] copying app libs from configurations.runtime")
        project.copy {
            from project.configurations.runtime
            from project.libsDir
            into "${tomcatRootDir}/webapps/$appName/WEB-INF/lib"  //
            include { details ->
                String name = details.file.name

                if (new File(appLibDir, name).exists() && !name.contains("-SNAPSHOT")) {
                    return false
                }

                if (!name.endsWith('.jar')) {
                    return false
                }

                def libraryName = getLibraryDefinition(name).name

                if (!(name.endsWith('.zip')) && !(name.endsWith('-tests.jar')) && !(name.endsWith('-sources.jar')) &&
                        jarNames.contains(libraryName)) {
                    copiedToAppLib.add(name)

                    return true
                }

                return false
            }
        }

        if (project.configurations.getAsMap().dbscripts) {
            project.logger.info("[CubaDeployment] copying dbscripts from ${project.buildDir}/db to ${tomcatRootDir}/webapps/$appName/WEB-INF/db")
            project.copy {
                from "${project.buildDir}/db"
                into "${tomcatRootDir}/webapps/$appName/WEB-INF/db"
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
        }

        if (doAfter) {
            project.logger.info("[CubaDeployment] calling doAfter")
            doAfter.call()
        }

        def webXml = new File("${tomcatRootDir}/webapps/$appName/WEB-INF/web.xml")
        if (project.ext.has('webResourcesTs')) {
            project.logger.info("[CubaDeployment] update web resources timestamp")

            // detect version automatically
            def buildTimeStamp = project.ext.get('webResourcesTs')

            def webXmlText = webXml.text
            if (StringUtils.contains(webXmlText, '${webResourcesTs}')) {
                webXmlText = webXmlText.replace('${webResourcesTs}', buildTimeStamp.toString())
            }
            webXml.write(webXmlText)
        }

        project.logger.info("[CubaDeployment] touch ${tomcatRootDir}/webapps/$appName/WEB-INF/web.xml")
        webXml.setLastModified(System.currentTimeMillis())
    }

    def appJars(Object... names) {
        if (names) {
            def namesList = names.collect { String.valueOf(it) }
            jarNames.addAll(namesList)
        }
    }

    def appJar(String name) {
        jarNames.add(name)
    }
}