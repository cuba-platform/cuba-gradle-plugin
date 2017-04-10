/*
 * Copyright (c) 2008-2017 Haulmont.
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
 */

import com.haulmont.gradle.uberjar.ExcludeResourceTransformer
import com.haulmont.gradle.uberjar.MergeResourceTransformer
import com.haulmont.gradle.uberjar.Unpack
import com.haulmont.gradle.uberjar.UnpackTransformer
import org.apache.commons.lang.StringUtils
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.tasks.TaskAction

class CubaUberJar extends DefaultTask {

    String appName

    Project coreProject
    Project webProject
    Project portalProject
    Project polymerProject

    String coreWebXmlPath
    String webWebXmlPath
    String portalWebXmlPath

    int corePort = 8079
    int webPort = 8080
    int portalPort = 8081

    String coreJettyEnvPath
    List<String> webContentExclude = []
    List<String> excludeResources = []
    List<String> mergeResources = []
    Map<String, Object> appProperties


    protected String distributionDir = "${project.buildDir}/distributions/uberJar"
    protected List<UnpackTransformer> defaultTransformers = new ArrayList<>()

    protected String coreTmpDir
    protected String webTmpDir
    protected String portalTmpDir
    protected String polymerTmpDir

    protected String coreAppName
    protected String portalAppName

    CubaUberJar() {
        project.afterEvaluate {
            def childProjects = project.getChildProjects()

            if (!coreProject) {
                project.logger.info("[CubaUberJar] core project is not set, trying to find it automatically")
                for (Map.Entry<String, Project> entry : childProjects.entrySet()) {
                    if (entry.getKey().endsWith("-core")) {
                        coreProject = entry.getValue()
                        project.logger.info("[CubaUberJar] $coreProject is set as core project")
                        break
                    }
                }
            }

            if (!webProject) {
                project.logger.info("[CubaUberJar] web project is not set, trying to find it automatically")
                for (Map.Entry<String, Project> entry : childProjects.entrySet()) {
                    if (entry.getKey().endsWith("-web")) {
                        webProject = entry.getValue()
                        project.logger.info("[CubaUberJar] $webProject is set as web project")
                        break
                    }
                }
            }

            if (!portalProject) {
                project.logger.info("[CubaUberJar] portal project is not set, trying to find it automatically")
                for (Map.Entry<String, Project> entry : childProjects.entrySet()) {
                    if (entry.getKey().endsWith("-portal")) {
                        portalProject = entry.getValue()
                        project.logger.info("[CubaUberJar] $portalProject is set as portal project")
                        break;
                    }
                }
            }

            if (!polymerProject) {
                project.logger.info("[CubaUberJar] Polymer client project is not set, trying to find it automatically")
                for (Map.Entry<String, Project> entry : childProjects.entrySet()) {
                    if (entry.getKey().endsWith("-polymer-client")) {
                        polymerProject = entry.getValue()
                        project.logger.info("[CubaUberJar] $polymerProject is set as Polymer client project")
                        break;
                    }
                }
            }

            for (Map.Entry<String, Project> entry : childProjects.entrySet()) {
                if (entry.getKey().endsWith("-web-toolkit")) {
                    def webToolkit = entry.getValue()
                    def assembleWebToolkit = webToolkit.getTasksByName("assemble", false).iterator().next()
                    this.dependsOn(assembleWebToolkit)
                    break;
                }
            }
        }
    }

    void setCoreProject(Project coreProject) {
        this.coreProject = coreProject
        def assembleCore = coreProject.getTasksByName('assemble', false).iterator().next()
        this.dependsOn(assembleCore)
    }

    void setWebProject(Project webProject) {
        this.webProject = webProject
        def assembleWeb = webProject.getTasksByName('assemble', false).iterator().next()
        this.dependsOn(assembleWeb)
    }

    void setPortalProject(Project portalProject) {
        this.portalProject = portalProject
        def assemblePortal = portalProject.getTasksByName('assemble', false).iterator().next()
        this.dependsOn(assemblePortal)
    }

    void setPolymerProject(Project polymerProject) {
        this.polymerProject = polymerProject
        def assemblePolymer = polymerProject.getTasksByName('assemble', false).iterator().next()
        this.dependsOn(assemblePolymer)
    }

    @TaskAction
    def createJar() {
        project.delete(distributionDir)

        initVariables()
        initTransformers()

        createUberJarForProject(coreProject, coreAppName)
        createUberJarForProject(webProject, appName)
        if (portalProject) {
            createUberJarForProject(portalProject, portalAppName)
        }

        coreProject.copy {
            from coreProject.file("${tmpDir(coreProject)}/${coreAppName}.jar")
            from webProject.file("${tmpDir(webProject)}/${appName}.jar")
            into distributionDir
        }

        if (portalProject) {
            coreProject.copy {
                from portalProject.file("${tmpDir(portalProject)}/${portalAppName}.jar")
                into distributionDir
            }
        }

        project.delete("${project.buildDir}/tmp")
    }

    protected void initVariables() {
        if (coreJettyEnvPath) {
            coreJettyEnvPath = "$project.rootDir/$coreJettyEnvPath"
        }
        if (coreWebXmlPath) {
            coreWebXmlPath = "$project.rootDir/$coreWebXmlPath"
        }
        if (webWebXmlPath) {
            webWebXmlPath = "$project.rootDir/$webWebXmlPath"
        }
        if (portalWebXmlPath) {
            portalWebXmlPath = "$project.rootDir/$portalWebXmlPath"
        }
        Set coreDeployTasks = coreProject.getTasksByName('deploy', false)
        if (coreDeployTasks.isEmpty()) {
            throw new GradleException("'core' module has no 'deploy' task")
        }
        Set webDeployTasks = webProject.getTasksByName('deploy', false)
        if (webDeployTasks.isEmpty()) {
            throw new GradleException("'web' module has no 'deploy' task")
        }
        def deployWeb = webDeployTasks.first()
        if (portalProject) {
            Set portalDeployTasks = portalProject.getTasksByName('deploy', false)
            if (portalDeployTasks.isEmpty()) {
                throw new GradleException("'portal' module has no 'deploy' task")
            }
        }

        coreTmpDir = "${project.buildDir}/tmp/uberJar/core"
        webTmpDir = "${project.buildDir}/tmp/uberJar/web"
        portalTmpDir = "${project.buildDir}/tmp/uberJar/portal"
        polymerTmpDir = "${project.buildDir}/tmp/uberJar/front"

        if (!appName) {
            appName = deployWeb.appName
        }
        coreAppName = appName + '-core'
        portalAppName = appName + '-portal'

        String platformVersion = resolvePlatformVersion(coreProject)
        project.dependencies {
            uberJar(group: 'com.haulmont.cuba', name: 'cuba-uberjar', version: platformVersion)
        }
    }

    protected boolean initTransformers() {
        defaultTransformers.add(new ExcludeResourceTransformer(excludeResources))
        defaultTransformers.add(new MergeResourceTransformer(mergeResources))
    }

    protected void createUberJarForProject(Project theProject, String jarName) {
        theProject.logger.warn("[CubaUberJar] Resolve dependent libs for ${theProject}")
        theProject.copy {
            from theProject.configurations.runtime
            from theProject.libsDir
            from project.configurations.uberJar
            from theProject.configurations.jdbc
            into dependentLibsDir(theProject)
            include { details ->
                !details.file.name.endsWith('-sources.jar')
            }
        }

        theProject.logger.warn("[CubaUberJar] Unpack dependent libs for ${theProject}")
        new Unpack(theProject.file(dependentLibsDir(theProject)),
                theProject.file(unpackDir(theProject)), defaultTransformers).runAction()

        copyWebInfContent(theProject)

        copySpecificWebContent(theProject)

        project.logger.warn("[CubaUberJar] Writing local app properties for ${theProject}")
        def projectProperties = collectProperties(theProject)
        writeLocalAppProperties(theProject, projectProperties)

        touchWebXml(theProject)

        project.logger.warn("[CubaUberJar] Packing jar for ${theProject}")
        ant.jar(destfile: theProject.file("${tmpDir(theProject)}/${jarName}.jar"), basedir: unpackDir(theProject), zip64Mode: 'as-needed') {
            delegate.manifest {
                attribute(name: 'Main-Class', value: 'com.haulmont.cuba.uberjar.ServerRunner')
            }
        }
    }

    protected void copyWebInfContent(Project theProject) {
        theProject.logger.warn("[CubaUberJar] Copy WEB-INF content for ${theProject}")
        String webXmlPath
        if (coreWebXmlPath && theProject == coreProject)
            webXmlPath = coreWebXmlPath
        if (webWebXmlPath && theProject == webProject)
            webXmlPath = webWebXmlPath
        if (portalWebXmlPath && theProject == portalProject)
            webXmlPath = portalWebXmlPath

        if (webXmlPath) {
            File webXml = new File(webXmlPath)
            if (!webXml.exists()) {
                throw new GradleException("$webXmlPath doesn't exists")
            }
            theProject.copy {
                from 'web'
                into unpackDir(theProject)
                include '**/WEB-INF/**'
                exclude '**/WEB-INF/web.xml'
            }
            theProject.copy {
                from webXmlPath
                into "${unpackDir(theProject)}/WEB-INF/"
                rename { String fileName ->
                    "web.xml"
                }
            }
        } else {
            theProject.copy {
                from 'web'
                into unpackDir(theProject)
                include '**/WEB-INF/**'
            }
        }

        if (theProject == coreProject) {
            theProject.copy {
                from "${theProject.buildDir}/db"
                into "${unpackDir(theProject)}/WEB-INF/db"
            }

            if (coreJettyEnvPath) {
                def coreContextXml = new File(coreJettyEnvPath)
                if (!coreContextXml.exists()) {
                    throw new GradleException("$coreJettyEnvPath doesn't exists")
                }
                theProject.copy {
                    from coreJettyEnvPath
                    into "${unpackDir(theProject)}/WEB-INF/"
                    rename { String fileName ->
                        "jetty-env.xml"
                    }
                }
            }
        }
    }

    protected void copySpecificWebContent(Project theProject) {
        if (theProject == webProject || theProject == portalProject) {
            theProject.logger.info("[CubaUberJar] Copy web content for ${theProject}")
            def excludePatterns = ['**/WEB-INF/**', '**/META-INF/**'] + webContentExclude
            if (theProject == webProject) {
                theProject.copy {
                    from "${unpackDir(theProject)}/VAADIN"
                    into "${unpackDir(theProject)}/ubercontent/VAADIN"
                }
            }
            theProject.delete("${unpackDir(theProject)}/VAADIN")
            if (theProject.configurations.findByName('webcontent')) {
                theProject.configurations.webcontent.files.each { dep ->
                    theProject.logger.info("[CubaUberJar] Copying webcontent from $dep.absolutePath for project ${theProject}")
                    theProject.copy {
                        from theProject.zipTree(dep.absolutePath)
                        into "${unpackDir(theProject)}/ubercontent"
                        excludes = excludePatterns
                        includeEmptyDirs = false
                    }
                }
            }
            theProject.logger.info("[CubaUberJar] Copying webcontent from ${theProject.buildDir}/web for project ${theProject}")
            theProject.copy {
                from "${theProject.buildDir}/web"
                into "${unpackDir(theProject)}/ubercontent"
                excludes = excludePatterns
            }
            project.logger.info("[CubaUberJar] copying from web for project ${theProject}")
            project.copy {
                from theProject.file('web')
                into "${unpackDir(theProject)}/ubercontent"
                excludes = excludePatterns
            }
            if (theProject == webProject) {
                def webToolkit = theProject.rootProject.subprojects.find { it -> it.name.endsWith('web-toolkit') }
                if (webToolkit) {
                    theProject.logger.info("[CubaUberJar] Copying webcontent from ${webToolkit.buildDir}/web} for project ${theProject}")
                    theProject.copy {
                        from "${webToolkit.buildDir}/web"
                        into "${unpackDir(theProject)}/ubercontent"
                        exclude '**/gwt-unitCache/'
                    }
                }
                if (polymerProject != null) {
                    theProject.logger.info("[CubaUberJar] Copy polymer files for ${theProject}")
                    theProject.copy {
                        from polymerProject.file('build/bundled')
                        into "${unpackDir(theProject)}/uberfront"
                    }
                }
            }
        }
    }

    protected Map<String, Object> collectProperties(Project theProject) {
        def properties = [
                'cuba.logDir' : '${app.home}/logs',
                'cuba.confDir': '${app.home}/${cuba.webContextName}/conf',
                'cuba.tempDir': '${app.home}/${cuba.webContextName}/temp',
                'cuba.dataDir': '${app.home}/${cuba.webContextName}/work'
        ]

        if (theProject == coreProject) {
            properties += [
                    'cuba.dataSourceJndiName'  : 'jdbc/CubaDS',
                    'cuba.download.directories': '${cuba.tempDir};${cuba.logDir}',
                    'cuba.dbDir'               : 'web-inf:db',
                    'cuba.uberJar'             : 'true',
                    'cuba.webPort'             : corePort
            ]
        }

        if (theProject == webProject) {
            properties += [
                    'cuba.connectionUrlList'        : "http://localhost:${corePort}/${coreAppName}",
                    'cuba.useLocalServiceInvocation': 'false',
                    'cuba.webPort'                  : webPort
            ]
        }

        if (theProject == portalProject) {
            properties += [
                    'cuba.connectionUrlList'        : "http://localhost:${corePort}/${coreAppName}",
                    'cuba.useLocalServiceInvocation': 'false',
                    'cuba.webPort'                  : portalPort

            ]
        }

        if (appProperties) {
            properties += appProperties
        }
        properties
    }

    protected void writeLocalAppProperties(Project theProject, def properties) {
        File appPropFile = new File("${unpackDir(theProject)}/WEB-INF/local.app.properties")
        appPropFile.withWriter('UTF-8') { writer ->
            properties.each { key, value ->
                writer << key << ' = ' << value << '\n'
            }
        }
    }

    protected String tmpDir(Project theProject) {
        if (theProject == coreProject) {
            return coreTmpDir
        } else if (theProject == webProject) {
            return webTmpDir
        } else if (theProject == portalProject) {
            return portalTmpDir
        } else if (theProject == polymerProject) {
            return polymerTmpDir
        } else {
            return null
        }
    }

    protected String dependentLibsDir(Project theProject) {
        return "${tmpDir(theProject)}/libs"
    }

    protected String unpackDir(Project theProject) {
        return "${tmpDir(theProject)}/unpack"
    }

    protected void touchWebXml(Project theProject) {
        def webXml = new File("${unpackDir(theProject)}/WEB-INF/web.xml")
        if (!webXml.exists()) {
            throw new GradleException("$webXml doesn't exists")
        }

        if (theProject.ext.has('webResourcesTs')) {
            theProject.logger.info("[CubaUberJar] Update web resources timestamp")

            // detect version automatically
            String buildTimeStamp = theProject.ext.get('webResourcesTs').toString()

            def webXmlText = webXml.text
            if (StringUtils.contains(webXmlText, '${webResourcesTs}')) {
                webXmlText = webXmlText.replace('${webResourcesTs}', buildTimeStamp)
            }
            webXml.write(webXmlText)
        }
    }

    protected String resolvePlatformVersion(Project project) {
        Configuration dependencyCompile = project.configurations.findByName('compile')
        if (dependencyCompile) {
            def artifacts = dependencyCompile.resolvedConfiguration.getResolvedArtifacts()
            def cubaGlobalArtifact = artifacts.find { ResolvedArtifact artifact ->
                artifact.name == 'cuba-global'
            }
            if (cubaGlobalArtifact) {
                return cubaGlobalArtifact.moduleVersion.id.version
            }
        }
        throw new GradleException("[CubaUberJar] Platform version is undefined")
    }
}
