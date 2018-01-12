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


import com.haulmont.gradle.dependency.DependencyResolver
import com.haulmont.gradle.dependency.ProjectCollector
import com.haulmont.gradle.uberjar.*
import com.haulmont.gradle.utils.FrontUtils
import org.apache.commons.lang3.StringUtils
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.tasks.*
import org.gradle.jvm.tasks.Jar

class CubaUberJarBuilding extends DefaultTask {

    private static final String LIBS_DIR = "libs"
    private static final String LIBS_SHARED_DIR = "libs_shared"
    private static final String CONTENT_DIR = "content"
    private static final String MAIN_CLASS = "com.haulmont.cuba.uberjar.ServerRunner"

    public static final String CORE_CONTENT_DIR_IN_JAR = "app-core"
    public static final String WEB_CONTENT_DIR_IN_JAR = "app"
    public static final String PORTAL_CONTENT_DIR_IN_JAR = "app-portal"
    public static final String FRONT_CONTENT_DIR_IN_JAR = "app-front"

    @Input
    boolean singleJar

    @Input
    @Optional
    String appName

    Project coreProject
    Project webProject
    Project portalProject
    Project polymerProject
    Project webToolkitProject

    @Input
    @Optional
    String coreWebXmlPath
    @Input
    @Optional
    String webWebXmlPath
    @Input
    @Optional
    String portalWebXmlPath

    @Input
    @Optional
    String coreJettyConfPath
    @Input
    @Optional
    String webJettyConfPath
    @Input
    @Optional
    String portalJettyConfPath

    @Input
    @Optional
    String coreJettyEnvPath

    @Input
    @Optional
    String logbackConfigurationFile

    @Input
    boolean useDefaultLogbackConfiguration = true

    @Input
    int corePort = 8079
    @Input
    int webPort = 8080
    @Input
    int portalPort = 8081

    @Input
    List<String> webContentExclude = []
    @Input
    List<String> excludeResources = []
    @Input
    List<String> mergeResources = []
    @Input
    Map<String, Object> appProperties

    @Input
    String polymerBuildDir = 'es6-unbundled'

    protected String distributionDir = "${project.buildDir}/distributions/uberJar"
    protected List<ResourceTransformer> defaultTransformers = new ArrayList<>()

    protected String rootJarTmpDir

    protected String coreAppName
    protected String portalAppName

    protected Collection<String> coreJarNames
    protected Collection<String> webJarNames
    protected Collection<String> portalJarNames

    CubaUberJarBuilding() {
        setGroup('Deployment')
        setDescription('Task creates JAR files containing the application code and all its dependencies')
        project.afterEvaluate {
            def childProjects = project.getChildProjects()

            if (!coreProject) {
                project.logger.info("[CubaUberJAR] core project is not set, trying to find it automatically")
                for (Map.Entry<String, Project> entry : childProjects.entrySet()) {
                    if (entry.getKey().endsWith("-core")) {
                        setCoreProject(entry.getValue())
                        project.logger.info("[CubaUberJAR] $coreProject is set as core project")
                        break
                    }
                }
            }

            if (!webProject) {
                project.logger.info("[CubaUberJAR] web project is not set, trying to find it automatically")
                for (Map.Entry<String, Project> entry : childProjects.entrySet()) {
                    if (entry.getKey().endsWith("-web")) {
                        setWebProject(entry.getValue())
                        project.logger.info("[CubaUberJAR] $webProject is set as web project")
                        break
                    }
                }
            }

            if (!portalProject) {
                project.logger.info("[CubaUberJAR] portal project is not set, trying to find it automatically")
                for (Map.Entry<String, Project> entry : childProjects.entrySet()) {
                    if (entry.getKey().endsWith("-portal")) {
                        setPortalProject(entry.getValue())
                        project.logger.info("[CubaUberJAR] $portalProject is set as portal project")
                        break
                    }
                }
            }

            if (!polymerProject) {
                project.logger.info("[CubaUberJAR] Polymer client project is not set, trying to find it automatically")
                for (Map.Entry<String, Project> entry : childProjects.entrySet()) {
                    if (entry.getKey().endsWith("-polymer-client")) {
                        setPolymerProject(entry.getValue())
                        project.logger.info("[CubaUberJAR] $polymerProject is set as Polymer client project")
                        break
                    }
                }
            }

            if (!webToolkitProject) {
                for (Map.Entry<String, Project> entry : childProjects.entrySet()) {
                    if (entry.getKey().endsWith("-web-toolkit")) {
                        setWebToolkitProject(entry.getValue())
                        project.logger.info("[CubaUberJAR] $webToolkitProject is set as web-toolkit project")
                        break
                    }
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

    void setWebToolkitProject(Project webToolkitProject) {
        this.webToolkitProject = webToolkitProject
        def assembleWebToolkit = webToolkitProject.getTasksByName('assemble', false).iterator().next()
        this.dependsOn(assembleWebToolkit)
    }

    @OutputDirectory
    File getOutputDirectory() {
        return new File(distributionDir)
    }

    @InputFiles
    List<File> getInputFiles() {
        def dependencyFiles = new ArrayList<File>()
        def projects = Arrays.asList(coreProject, webProject, portalProject,
                polymerProject, webToolkitProject)
        def allProjects = new LinkedHashSet<Project>()

        for (theProject in projects) {
            if (theProject) {
                allProjects.add(theProject)
                allProjects.addAll(ProjectCollector.collectProjectDependencies(theProject))
                File webDir = theProject.file("web")
                if (webDir.exists()) {
                    dependencyFiles.add(webDir)
                }
            }
        }

        for (theProject in allProjects) {
            def jarTask = theProject.getTasks().findByName('jar')
            if (jarTask instanceof Jar) {
                dependencyFiles.addAll(jarTask.outputs.files)
            }
        }

        addToInputFiles(coreWebXmlPath, dependencyFiles)
        addToInputFiles(webWebXmlPath, dependencyFiles)
        addToInputFiles(portalWebXmlPath, dependencyFiles)
        addToInputFiles(webJettyConfPath, dependencyFiles)
        addToInputFiles(coreJettyConfPath, dependencyFiles)
        addToInputFiles(portalJettyConfPath, dependencyFiles)
        addToInputFiles(coreJettyEnvPath, dependencyFiles)
        addToInputFiles(logbackConfigurationFile, dependencyFiles)

        return dependencyFiles
    }

    void addToInputFiles(String filePath, List<File> files) {
        if (filePath) {
            File file = new File(filePath)
            if (file.exists()) {
                files.add(file)
            }
        }
    }

    @TaskAction
    def createJar() {
        project.delete(distributionDir)

        initVariables()
        initTransformers()

        Set<String> serverLibs = new LinkedHashSet<>()
        Set<String> coreLibs = new LinkedHashSet<>()
        Set<String> webLibs = new LinkedHashSet<>()
        Set<String> portalLibs = new LinkedHashSet<>()

        copyServerLibs(serverLibs)
        copyLibsAndContent(coreProject, coreJarNames, coreLibs)
        copyLibsAndContent(webProject, webJarNames, webLibs)
        if (polymerProject) {
            copyFrontLibsAndContent(polymerProject)
        }
        if (portalProject) {
            copyLibsAndContent(portalProject, portalJarNames, portalLibs)
        }

        if (singleJar) {
            resolveSharedLibConflicts(coreLibs, webLibs, portalLibs)
            UberJar jar = createJarTask(appName)
            packServerLibs(jar)
            packLibsAndContent(coreProject, jar, false)
            packLibsAndContent(webProject, jar, true)
            if (polymerProject) {
                packFrontContent(polymerProject, jar)
            }
            if (portalProject) {
                packLibsAndContent(portalProject, jar, false)
            }
            packLogbackConfigurationFile(jar)
            packJettyFile(null, jar)
            jar.createManifest(MAIN_CLASS)

            //copy jar to distribution dir
            project.copy {
                from project.file("$rootJarTmpDir/${appName}.jar")
                into distributionDir
            }
        } else {
            UberJar coreJar = createJarTask(coreAppName)
            packServerLibs(coreJar)
            packLibsAndContent(coreProject, coreJar, true)
            packLogbackConfigurationFile(coreJar)
            packJettyFile(coreProject, coreJar)
            coreJar.createManifest(MAIN_CLASS)

            UberJar webJar = createJarTask(appName)
            packServerLibs(webJar)
            packLibsAndContent(webProject, webJar, true)
            if (polymerProject) {
                packFrontContent(polymerProject, webJar)
            }
            packJettyFile(webProject, webJar)
            packLogbackConfigurationFile(webJar)
            webJar.createManifest(MAIN_CLASS)

            //copy jars to distribution dir
            project.copy {
                from coreProject.file("$rootJarTmpDir/${coreAppName}.jar")
                from webProject.file("$rootJarTmpDir/${appName}.jar")
                into distributionDir
            }

            if (portalProject) {
                UberJar portalJar = createJarTask(portalAppName)
                packServerLibs(portalJar)
                packLibsAndContent(portalProject, portalJar, true)
                packLogbackConfigurationFile(portalJar)
                packJettyFile(portalProject, portalJar)
                portalJar.createManifest(MAIN_CLASS)

                //copy jar to distribution dir
                if (portalProject) {
                    project.copy {
                        from portalProject.file("$rootJarTmpDir/${portalAppName}.jar")
                        into distributionDir
                    }
                }
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
        if (coreJettyConfPath) {
            coreJettyConfPath = "$project.rootDir/$coreJettyConfPath"
        }
        if (webJettyConfPath) {
            webJettyConfPath = "$project.rootDir/$webJettyConfPath"
        }
        if (portalJettyConfPath) {
            portalJettyConfPath = "$project.rootDir/$portalJettyConfPath"
        }
        if (logbackConfigurationFile) {
            logbackConfigurationFile = "$project.rootDir/$logbackConfigurationFile"
        }

        Set coreDeployTasks = coreProject.getTasksByName('deploy', false)
        if (coreDeployTasks.isEmpty()) {
            throw new GradleException("'core' module has no 'deploy' task")
        }
        def deployCore = coreDeployTasks.first()

        Set webDeployTasks = webProject.getTasksByName('deploy', false)
        if (webDeployTasks.isEmpty()) {
            throw new GradleException("'web' module has no 'deploy' task")
        }
        def deployWeb = webDeployTasks.first()

        def deployPortal = null
        if (portalProject) {
            Set portalDeployTasks = portalProject.getTasksByName('deploy', false)
            if (portalDeployTasks.isEmpty()) {
                throw new GradleException("'portal' module has no 'deploy' task")
            }
            deployPortal = portalDeployTasks.first()
        }

        coreJarNames = deployCore.getAllJarNames()
        webJarNames = deployWeb.getAllJarNames()
        if (portalProject) {
            portalJarNames = deployPortal.getAllJarNames()
        }

        rootJarTmpDir = "${project.buildDir}/tmp/uberJar"

        if (!appName) {
            appName = deployWeb.appName
        }
        coreAppName = appName + '-core'
        portalAppName = appName + '-portal'

        String platformVersion = resolvePlatformVersion(coreProject)
        project.dependencies {
            uberJar(group: 'com.haulmont.cuba', name: 'cuba-uberjar', version: platformVersion)
        }
        if (polymerProject) {
            project.dependencies {
                front(group: 'com.haulmont.cuba', name: 'cuba-front', version: platformVersion)
            }
        }
    }

    protected boolean initTransformers() {
        defaultTransformers.add(new ExcludeResourceTransformer(excludeResources))
        defaultTransformers.add(new MergeResourceTransformer(mergeResources))
    }

    protected UberJar createJarTask(String name) {
        return new UberJar(project.logger, project.file(rootJarTmpDir).toPath(), "${name}.jar", defaultTransformers)
    }

    protected void resolveSharedLibConflicts(Set<String> coreLibs, Set<String> webLibs, Set<String> portalLibs) {
        Set<String> allLibs = new LinkedHashSet<>();
        allLibs.addAll(coreLibs)
        allLibs.addAll(webLibs)
        allLibs.addAll(portalLibs)

        def libsDir = project.file(getSharedLibsDir(project))
        def resolver = new DependencyResolver(libsDir, logger)
        resolver.resolveDependencies(libsDir, new ArrayList<String>(allLibs))
    }

    protected void copyLibsAndContent(Project theProject, Collection<String> jarNames, Set<String> resolvedLibs) {
        theProject.logger.warn("[CubaUberJAR] Copy shared libs for ${theProject}")
        copySharedLibs(theProject, jarNames, resolvedLibs)

        theProject.logger.warn("[CubaUberJAR] Copy app libs for ${theProject}")
        copyAppLibs(theProject, jarNames, resolvedLibs)

        copyWebInfContent(theProject)
        copySpecificWebContent(theProject)

        project.logger.info("[CubaUberJAR] Writing local app properties for ${theProject}")
        def projectProperties = collectProperties(theProject)
        writeLocalAppProperties(theProject, projectProperties)
        writeLibsFile(theProject, resolvedLibs)

        touchWebXml(theProject)
    }

    protected void copyFrontLibsAndContent(Project theProject) {
        theProject.copy {
            from project.configurations.front
            into "${getAppLibsDir(theProject)}"
            include { details ->
                !details.file.name.endsWith('-sources.jar') && details.file.name.contains('cuba-front')
            }
        }
        copySpecificWebContent(theProject)
        File webInf = new File("${getContentDir(polymerProject)}/WEB-INF/")
        if (!webInf.exists()) {
            webInf.mkdir()
        }
        File webXml = new File(webInf, "web.xml")
        webXml.write(FrontUtils.getFrontWebXml())
        writeIndexHtmlTemplate()
    }

    protected void packServerLibs(UberJar jar) {
        project.logger.warn("[CubaUberJAR] Pack server libs")
        jar.copyJars(project.file(getServerLibsDir()).toPath(), null)
    }

    protected void packLibsAndContent(Project theProject, UberJar jar, boolean copyShared) {
        theProject.logger.warn("[CubaUberJAR] Pack app libs for ${theProject}")
        jar.copyJars(project.file(getAppLibsDir(theProject)).toPath(), new AllResourceLocator("${getPackDir(theProject)}/WEB-INF/classes"))

        if (copyShared) {
            theProject.logger.warn("[CubaUberJAR] Pack shared libs for ${theProject}")
            jar.copyJars(project.file(getSharedLibsDir(theProject)).toPath(), new SharedResourceLocator("LIB-INF/shared", getPackDir(webProject)))
        }

        theProject.logger.warn("[CubaUberJAR] Pack content for ${theProject}")
        jar.copyFiles(project.file(getContentDir(theProject)).toPath(), new AllResourceLocator(getPackDir(theProject)))
    }

    protected void packFrontContent(Project theProject, UberJar jar) {
        jar.copyJars(project.file(getAppLibsDir(theProject)).toPath(), new AllResourceLocator("${getPackDir(theProject)}/WEB-INF/classes"))
        jar.copyFiles(project.file(getContentDir(theProject)).toPath(), new AllResourceLocator(getPackDir(theProject)))
    }

    protected void packLogbackConfigurationFile(UberJar jar) {
        if (logbackConfigurationFile) {
            if (!new File(logbackConfigurationFile).exists()) {
                throw new GradleException("$logbackConfigurationFile doesn't exists")
            }
            jar.copyFiles(project.file(logbackConfigurationFile).toPath(), new LogbackResourceLocator())
        } else if (useDefaultLogbackConfiguration) {
            InputStream inputStream = getClass().getClassLoader().getResourceAsStream("logback.xml")
            if (inputStream == null) {
                throw new GradleException("Default logback configuration file doesn't exists")
            }
            try {
                jar.copy(inputStream, new LogbackResourceLocator())
            } finally {
                try {
                    inputStream.close()
                } catch (Exception e) {
                    //Do nothing
                }
            }
        }
    }

    protected void packJettyFile(Project theProject, UberJar jar) {
        String jettyPath
        if (webJettyConfPath && singleJar) {
            jettyPath = webJettyConfPath
        } else if (coreJettyConfPath && theProject == coreProject) {
            jettyPath = coreJettyConfPath
        } else if (webJettyConfPath && theProject == webProject) {
            jettyPath = webJettyConfPath
        } else if (portalJettyConfPath && theProject == portalProject) {
            jettyPath = portalJettyConfPath
        }
        if (jettyPath != null) {
            if (!new File(jettyPath).exists()) {
                throw new GradleException("$jettyPath doesn't exists")
            }
            jar.copyFiles(project.file(jettyPath).toPath(), new JettyXmlResourceLocator())
        }
    }

    protected void copyServerLibs(Set<String> resolvedLibs) {
        project.copy {
            from project.configurations.uberJar
            into getServerLibsDir()
            include { details ->
                if (details.file.name.endsWith('-sources.jar')) {
                    return false
                }
                resolvedLibs.add(details.file.name)
                return true
            }
        }
    }

    protected void copySharedLibs(Project theProject, Collection<String> jarNames, Set<String> resolvedLibs) {
        theProject.copy {
            from theProject.configurations.runtime
            from theProject.libsDir
            from theProject.configurations.jdbc
            into "${getSharedLibsDir(theProject)}"
            include { details ->
                if (!details.file.name.endsWith('.jar')) {
                    return false
                }
                if (details.file.name.endsWith('-sources.jar')) {
                    return false
                }
                resolvedLibs.add(details.file.name)
                def libraryName = DependencyResolver.getLibraryDefinition(details.file.name).name
                !jarNames.contains(libraryName)
            }
        }
    }

    protected void copyAppLibs(Project theProject, Collection<String> jarNames, Set<String> resolvedLibs) {
        theProject.copy {
            from theProject.configurations.runtime
            from theProject.libsDir
            from theProject.configurations.jdbc
            into "${getAppLibsDir(theProject)}"
            include { details ->
                if (!details.file.name.endsWith(".jar")) {
                    return false
                }
                if (details.file.name.endsWith('-sources.jar')) {
                    return false
                }
                resolvedLibs.add(details.file.name)
                def libraryName = DependencyResolver.getLibraryDefinition(details.file.name).name
                jarNames.contains(libraryName)
            }
        }
    }

    protected void copyWebInfContent(Project theProject) {
        theProject.logger.info("[CubaUberJAR] Copy WEB-INF content for ${theProject}")
        String webXmlPath
        if (coreWebXmlPath && theProject == coreProject) {
            webXmlPath = coreWebXmlPath
        } else if (webWebXmlPath && theProject == webProject) {
            webXmlPath = webWebXmlPath
        } else if (portalWebXmlPath && theProject == portalProject) {
            webXmlPath = portalWebXmlPath
        }

        if (webXmlPath) {
            File webXml = new File(webXmlPath)
            if (!webXml.exists()) {
                throw new GradleException("$webXmlPath doesn't exists")
            }
            theProject.copy {
                from 'web'
                into getContentDir(theProject)
                include '**/WEB-INF/**'
                exclude '**/WEB-INF/web.xml'
            }
            theProject.copy {
                from webXmlPath
                into "${getContentDir(theProject)}/WEB-INF/"
                rename { String fileName ->
                    "web.xml"
                }
            }
        } else {
            theProject.copy {
                from 'web'
                into getContentDir(theProject)
                include '**/WEB-INF/**'
            }
        }

        if (theProject == coreProject) {
            theProject.copy {
                from "${theProject.buildDir}/db"
                into "${getContentDir(theProject)}/WEB-INF/db"
            }

            if (coreJettyEnvPath) {
                def coreContextXml = new File(coreJettyEnvPath)
                if (!coreContextXml.exists()) {
                    throw new GradleException("$coreJettyEnvPath doesn't exists")
                }
                theProject.copy {
                    from coreJettyEnvPath
                    into "${getContentDir(theProject)}/WEB-INF/"
                    rename { String fileName ->
                        "jetty-env.xml"
                    }
                }
            }
        }
    }

    protected void copySpecificWebContent(Project theProject) {
        if (theProject == webProject || theProject == portalProject) {
            theProject.logger.info("[CubaUberJAR] Copy web content for ${theProject}")
            def excludePatterns = ['**/WEB-INF/**', '**/META-INF/**'] + webContentExclude
            if (theProject.configurations.findByName('webcontent')) {
                theProject.configurations.webcontent.files.each { dep ->
                    theProject.logger.info("[CubaUberJAR] Copying webcontent from $dep.absolutePath for project ${theProject}")
                    theProject.copy {
                        from theProject.zipTree(dep.absolutePath)
                        into "${getContentDir(theProject)}"
                        excludes = excludePatterns
                        includeEmptyDirs = false
                    }
                }
            }
            theProject.logger.info("[CubaUberJAR] Copying webcontent from ${theProject.buildDir}/web for project ${theProject}")
            theProject.copy {
                from "${theProject.buildDir}/web"
                into "${getContentDir(theProject)}"
                excludes = excludePatterns
            }
            project.logger.info("[CubaUberJAR] copying from web for project ${theProject}")
            project.copy {
                from theProject.file('web')
                into "${getContentDir(theProject)}"
                excludes = excludePatterns
            }
            if (theProject == webProject) {
                def webToolkit = theProject.rootProject.subprojects.find { it -> it.name.endsWith('web-toolkit') }
                if (webToolkit) {
                    theProject.logger.info("[CubaUberJAR] Copying webcontent from ${webToolkit.buildDir}/web} for project ${theProject}")
                    theProject.copy {
                        from "${webToolkit.buildDir}/web"
                        into "${getContentDir(theProject)}"
                        exclude '**/gwt-unitCache/'
                    }
                }
            }
        }
        if (theProject == polymerProject) {
            theProject.logger.info("[CubaUberJAR] Copy Polymer files for ${theProject}")
            if (!polymerBuildDir) {
                throw new GradleException("'polymerBuildDir' property should be required for Uber JAR building with Polymer")
            }
            def dir = theProject.file("build/$polymerBuildDir")
            if (!dir.exists()) {
                throw new GradleException("Polymer build directory $dir doesn't exists")
            }
            theProject.copy {
                from theProject.file("build/$polymerBuildDir")
                into "${getContentDir(theProject)}"
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
            ]
            if (!singleJar) {
                properties += [
                        'cuba.webPort': corePort
                ]
            }
        }

        if (theProject == webProject) {
            properties += [
                    'cuba.useLocalServiceInvocation': singleJar ? 'true' : 'false',
                    'cuba.webPort'                  : webPort,
                    'cuba.uberJar'                  : 'true'
            ]
            if (!singleJar) {
                properties += [
                        'cuba.connectionUrlList': "http://localhost:${corePort}/${coreAppName}"
                ]
            }
        }

        if (theProject == portalProject) {
            properties += [
                    'cuba.useLocalServiceInvocation': singleJar ? 'true' : 'false',
            ]
            if (!singleJar) {
                properties += [
                        'cuba.webPort'          : portalPort,
                        'cuba.connectionUrlList': "http://localhost:${corePort}/${coreAppName}"
                ]
            }
        }

        if (appProperties) {
            properties += appProperties
        }
        properties
    }

    protected void writeLocalAppProperties(Project theProject, def properties) {
        File appPropFile = new File("${getContentDir(theProject)}/WEB-INF/local.app.properties")
        appPropFile.withWriter('UTF-8') { writer ->
            properties.each { key, value ->
                writer << key << ' = ' << value << '\n'
            }
        }
    }

    protected String getServerLibsDir() {
        return "$rootJarTmpDir/${LIBS_DIR}_server"
    }

    protected String getSharedLibsDir(Project theProject) {
        if (!singleJar) {
            if (theProject == coreProject) {
                return "$rootJarTmpDir/${LIBS_SHARED_DIR}_core"
            } else if (theProject == webProject) {
                return "$rootJarTmpDir/${LIBS_SHARED_DIR}_web"
            } else if (theProject == portalProject) {
                return "$rootJarTmpDir/${LIBS_SHARED_DIR}_portal"
            }
        }
        return "$rootJarTmpDir/$LIBS_SHARED_DIR"
    }

    protected String getAppLibsDir(Project theProject) {
        if (theProject == coreProject) {
            return "$rootJarTmpDir/${LIBS_DIR}_core"
        } else if (theProject == webProject) {
            return "$rootJarTmpDir/${LIBS_DIR}_web"
        } else if (theProject == portalProject) {
            return "$rootJarTmpDir/${LIBS_DIR}_portal"
        } else if (theProject == polymerProject) {
            return "$rootJarTmpDir/${LIBS_DIR}_front"
        }
        return null
    }

    protected String getContentDir(Project theProject) {
        if (theProject == coreProject) {
            return "$rootJarTmpDir/${CONTENT_DIR}_core"
        } else if (theProject == webProject) {
            return "$rootJarTmpDir/${CONTENT_DIR}_web"
        } else if (theProject == portalProject) {
            return "$rootJarTmpDir/${CONTENT_DIR}_portal"
        } else if (theProject == polymerProject) {
            return "$rootJarTmpDir/${CONTENT_DIR}_front"
        }
        return null
    }

    protected String getPackDir(Project theProject) {
        if (theProject == coreProject) {
            return "LIB-INF/$CORE_CONTENT_DIR_IN_JAR"
        } else if (theProject == webProject) {
            return "LIB-INF/$WEB_CONTENT_DIR_IN_JAR"
        } else if (theProject == portalProject) {
            return "LIB-INF/$PORTAL_CONTENT_DIR_IN_JAR"
        } else if (theProject == polymerProject) {
            return "LIB-INF/$FRONT_CONTENT_DIR_IN_JAR"
        }
        return null
    }

    protected void touchWebXml(Project theProject) {
        def webXml = new File("${getContentDir(theProject)}/WEB-INF/web.xml")
        if (!webXml.exists()) {
            throw new GradleException("$webXml doesn't exists")
        }

        if (theProject.ext.has('webResourcesTs')) {
            theProject.logger.info("[CubaUberJAR] Update web resources timestamp")

            // detect version automatically
            String buildTimeStamp = theProject.ext.get('webResourcesTs').toString()

            def webXmlText = webXml.text
            if (StringUtils.contains(webXmlText, '${webResourcesTs}')) {
                webXmlText = webXmlText.replace('${webResourcesTs}', buildTimeStamp)
            }
            webXml.write(webXmlText)
        }
    }

    protected void writeLibsFile(Project theProject, Set<String> resolvedLibs) {
        File dependenciesFile = new File("${getContentDir(theProject)}/META-INF/cuba-app-libs.txt")
        dependenciesFile.withWriter('UTF-8') { writer ->
            resolvedLibs.each { value ->
                writer << value << '\n'
            }
        }
    }

    protected void writeIndexHtmlTemplate() {
        File templateDir = new File("${getContentDir(polymerProject)}/front")
        templateDir.mkdir()
        File indexTemplate = new File(templateDir, "index.ftl")
        File indexHtml = new File("${getContentDir(polymerProject)}/index.html")
        String text = FrontUtils.rewriteBaseUrl(indexHtml.text, null)
        text = FrontUtils.rewriteApiUrl(text, null)
        indexTemplate.write(text)
        indexHtml.delete()
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
        throw new GradleException("[CubaUberJAR] Platform version is undefined")
    }
}
