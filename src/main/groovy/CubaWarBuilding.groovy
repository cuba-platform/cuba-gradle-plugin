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

import com.google.common.base.Strings
import com.haulmont.gradle.dependency.DependencyResolver
import com.haulmont.gradle.dependency.ProjectCollector
import com.haulmont.gradle.utils.FrontUtils
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.tasks.*
import org.gradle.jvm.tasks.Jar

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class CubaWarBuilding extends DefaultTask {
    Project coreProject
    Project webProject
    Project portalProject
    Project polymerProject
    Project webToolkitProject

    @Input
    String appHome
    @Input
    @Optional
    String appName
    @Input
    boolean singleWar = true

    @Input
    @Optional
    String webXmlPath

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
    String polymerBuildDir = 'es6-unbundled'

    @Input
    @Optional
    String logbackConfigurationFile

    @Input
    boolean useDefaultLogbackConfiguration = true

    boolean projectAll

    @Input
    List<String> webContentExclude = []
    Closure doAfter

    @Input
    @Optional
    Map<String, Object> appProperties

    def coreJarNames
    def webJarNames
    def portalJarNames

    String coreTmpWarDir
    String webTmpWarDir
    String portalTmpWarDir
    String polymerTmpWarDir

    @Input
    boolean includeJdbcDriver = false
    @Input
    boolean includeContextXml = false

    @Input
    @Optional
    String coreContextXmlPath

    @Input
    boolean hsqlInProcess = false

    String distrDir = "${project.buildDir}/distributions/war"

    @Input
    @Optional
    @Deprecated
    Object webXml
    @Input
    @Optional
    @Deprecated
    String coreContextXml

    private String coreAppName

    CubaWarBuilding() {
        setGroup('Deployment')
        setDescription('Task builds a WAR file from the application code and its dependencies')
        project.afterEvaluate {
            def childProjects = project.getChildProjects()

            if (!coreProject) {
                project.logger.info("[CubaWarBuilding] core project is not set, trying to find it automatically")
                for (Map.Entry<String, Project> entry : childProjects.entrySet()) {
                    if (entry.getKey().endsWith("-core")) {
                        coreProject = entry.getValue()
                        project.logger.info("[CubaWarBuilding] $coreProject is set as core project")
                        break
                    }
                }
            }

            if (!webProject) {
                project.logger.info("[CubaWarBuilding] web project is not set, trying to find it automatically")
                for (Map.Entry<String, Project> entry : childProjects.entrySet()) {
                    if (entry.getKey().endsWith("-web")) {
                        webProject = entry.getValue()
                        project.logger.info("[CubaWarBuilding] $webProject is set as web project")
                        break
                    }
                }
            }

            if (!portalProject && !singleWar) {
                project.logger.info("[CubaWarBuilding] portal project is not set, trying to find it automatically")
                for (Map.Entry<String, Project> entry : childProjects.entrySet()) {
                    if (entry.getKey().endsWith("-portal")) {
                        portalProject = entry.getValue()
                        project.logger.info("[CubaWarBuilding] $portalProject is set as portal project")
                        break
                    }
                }
            }

            if (!polymerProject) {
                project.logger.info("[CubaWarBuilding] Polymer client project is not set, trying to find it automatically")
                for (Map.Entry<String, Project> entry : childProjects.entrySet()) {
                    if (entry.getKey().endsWith("-polymer-client")) {
                        polymerProject = entry.getValue()
                        project.logger.info("[CubaWarBuilding] $polymerProject is set as Polymer client project")
                        break
                    }
                }
            }

            if (!webToolkitProject) {
                for (Map.Entry<String, Project> entry : childProjects.entrySet()) {
                    if (entry.getKey().endsWith("-web-toolkit")) {
                        webToolkitProject = entry.getValue()
                        this.dependsOn(webToolkitProject.tasks.getByPath(BasePlugin.ASSEMBLE_TASK_NAME))
                        break
                    }
                }
            }
        }
    }

    void setCoreProject(Project coreProject) {
        this.coreProject = coreProject
        def assembleCore = coreProject.tasks.getByPath(BasePlugin.ASSEMBLE_TASK_NAME)
        this.dependsOn(assembleCore)
    }

    void setWebProject(Project webProject) {
        this.webProject = webProject
        def assembleWeb = webProject.tasks.getByPath(BasePlugin.ASSEMBLE_TASK_NAME)
        this.dependsOn(assembleWeb)
    }

    void setPortalProject(Project portalProject) {
        this.portalProject = portalProject
        def assembleWeb = portalProject.tasks.getByPath(BasePlugin.ASSEMBLE_TASK_NAME)
        this.dependsOn(assembleWeb)
    }

    void setPolymerProject(Project polymerProject) {
        this.polymerProject = polymerProject
        def assemblePolymer = polymerProject.tasks.getByPath(BasePlugin.ASSEMBLE_TASK_NAME)
        this.dependsOn(assemblePolymer)
    }

    void setWebToolkitProject(Project webToolkitProject) {
        this.webToolkitProject = webToolkitProject
        def assembleWebToolkit = webToolkitProject.tasks.getByPath(BasePlugin.ASSEMBLE_TASK_NAME)
        this.dependsOn(assembleWebToolkit)
    }

    @OutputDirectory
    File getOutputDirectory() {
        return new File(distrDir)
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

        addToInputFiles(webXmlPath, dependencyFiles)
        addToInputFiles(coreWebXmlPath, dependencyFiles)
        addToInputFiles(webWebXmlPath, dependencyFiles)
        addToInputFiles(portalWebXmlPath, dependencyFiles)
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

    String warDir(Project project) {
        if (project == coreProject) return coreTmpWarDir
        else if (project == webProject) return webTmpWarDir
        else if (project == portalProject) return portalTmpWarDir
        else if (project == polymerProject) return polymerTmpWarDir
        else return null
    }

    @TaskAction
    def build() {
        if (!webProject && singleWar) {
            throw new GradleException("Web module should be exist for single WAR building.")
        }

        init()

        def outputDirectory = getOutputDirectory()
        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs()
        }

        def tmpDir = new File(project.buildDir, 'tmp/' + getName())
        tmpDir.deleteDir()

        List<String> copied = copyLibs(coreProject)
        copied.addAll(copyLibs(webProject))
        copyLibs(portalProject)

        if (singleWar) {
            def resolver = new DependencyResolver(new File(coreTmpWarDir), logger)
            resolver.resolveDependencies(new File(coreTmpWarDir, 'WEB-INF/lib'), copied)
        }

        def coreProperties = collectProperties(coreProject)
        def webProperties = webProject ? collectProperties(webProject) : Collections.emptyMap()
        def portalProperties = portalProject ? collectProperties(portalProject) : Collections.emptyMap()

        copyDbScripts(coreProject)

        copyWebContent(coreProject)
        copyWebContent(webProject)
        copyWebContent(portalProject)

        if (includeContextXml) {
            copyWebContext(coreProject)
            if (!singleWar) {
                copyWebContext(webProject)
                copyWebContext(portalProject)
            }
        }

        if (polymerProject) {
            if (!polymerBuildDir) {
                throw new GradleException("'polymerBuildDir' property should be required for WAR building with Polymer")
            }
            def dir = polymerProject.file("build/$polymerBuildDir")
            if (!dir.exists()) {
                throw new GradleException("Polymer build directory $dir doesn't exists")
            }
            polymerProject.copy {
                from polymerProject.file("build/$polymerBuildDir")
                into "${warDir(polymerProject)}"
            }
        }

        copySpecificWebContent(webProject)

        processDoAfter()

        touchWebXml(coreProject)
        if (webProject) touchWebXml(webProject)
        if (portalProject) touchWebXml(portalProject)

        if (singleWar) {
            def summaryProperties = coreProperties + webProperties
            writeLocalAppProperties(webProject, summaryProperties)
            writeDependencies(coreProject, 'core', coreJarNames)
            writeDependencies(webProject, 'web', webJarNames)
            if (webProject && polymerProject) {
                copyFrontLibs()
                writeIndexHtmlTemplate()
            }

            def warFile = webProject.file("${warDir(webProject)}/${appName}.war")

            packWarFile(webProject, warFile)

            moveToDistributions(warFile)
        } else {
            writeLocalAppProperties(coreProject, coreProperties)

            if (webProject) {
                writeLocalAppProperties(webProject, webProperties)
            }

            if (portalProject) {
                writeLocalAppProperties(portalProject, portalProperties)
            }

            def coreWarFile = coreProject.file("${warDir(coreProject)}/${appName}-core.war")
            packWarFile(coreProject, coreWarFile)

            File webWarFile = null
            if (webProject) {
                webWarFile = webProject.file("${warDir(webProject)}/${appName}.war")
                packWarFile(webProject, webWarFile)
            }

            File portalWarFile = null
            if (portalProject) {
                portalWarFile = portalProject.file("${warDir(portalProject)}/${appName}-portal.war")
                packWarFile(portalProject, portalWarFile)
            }

            File polymerWarFile = null
            if (polymerProject) {
                polymerWarFile = polymerProject.file("${warDir(polymerProject)}/${appName}-front.war")
                packWarFile(polymerProject, polymerWarFile)
            }

            moveToDistributions(coreWarFile)

            if (webWarFile) {
                moveToDistributions(webWarFile)
            }

            if (portalWarFile) {
                moveToDistributions(portalWarFile)
            }

            if (polymerWarFile) {
                moveToDistributions(polymerWarFile)
            }
        }

        tmpDir.deleteDir()
    }

    protected void moveToDistributions(File resultFile) {
        def outputDir = getOutputDirectory()

        project.logger.info("[CubaWarBuilding] moving $resultFile into $outputDir")

        if (!resultFile.renameTo(new File(outputDir, resultFile.name))) {
            throw new GradleException("Unable to move WAR file into " + outputDir.absolutePath)
        }
    }

    protected void init() {
        webXmlPath = webXmlPath ? project.file(webXmlPath).absolutePath : webXml
        coreContextXmlPath = coreContextXmlPath ? project.file(coreContextXmlPath).absolutePath : coreContextXml

        if (!singleWar && webXmlPath) {
            throw new GradleException("'webXmlPath' property should be used only in single WAR building. " +
                    "Please set 'singleWar = true' or remove 'webXmlPath' property.")
        }

        if (singleWar) {
            if (!webXmlPath)
                throw new GradleException("To build single WAR, you should set the 'webXmlPath' property")

            def webXmlFile = new File(webXmlPath)
            if (!webXmlFile.exists()) {
                throw new GradleException("File '$webXmlPath' does not exist")
            }

            if (portalProject) {
                throw new GradleException("'portalProject' property is not supported in single WAR building. " +
                        "Please remove the 'portalProject' property.")
            }
        }

        project.delete(distrDir)

        def deployCore = coreProject.tasks.getByPath(CubaPlugin.DEPLOY_TASK_NAME)

        def deployWeb = null
        if (webProject) {
            deployWeb = webProject.tasks.getByPath(CubaPlugin.DEPLOY_TASK_NAME)
        }

        def deployPortal = null
        if (portalProject) {
            deployPortal = portalProject.tasks.getByPath(CubaPlugin.DEPLOY_TASK_NAME)
        }

        if (!coreJarNames) {
            coreJarNames = deployCore.getAllJarNames()
        }

        if (deployWeb && !webJarNames) {
            webJarNames = deployWeb.getAllJarNames()
        }

        if (deployPortal && !portalJarNames) {
            portalJarNames = deployPortal.getAllJarNames()
        }

        if (singleWar) {
            coreTmpWarDir = "${project.buildDir}/tmp/" + getName()
            webTmpWarDir = coreTmpWarDir
            polymerTmpWarDir = "$coreTmpWarDir/front"
        } else {
            coreTmpWarDir = "${project.buildDir}/tmp/${getName()}/core/war"
            webTmpWarDir = "${project.buildDir}/tmp/${getName()}/web/war"
            portalTmpWarDir = "${project.buildDir}/tmp/${getName()}/portal/war"
            polymerTmpWarDir = "${project.buildDir}/tmp/${getName()}/polymer/war"
        }

        if (!appName) {
            if (deployWeb) {
                appName = deployWeb.appName
            } else {
                appName = deployCore.appName.replace('-core', '')
            }
        }

        coreAppName = singleWar ? deployCore.appName : appName + '-core'

        if (logbackConfigurationFile) {
            logbackConfigurationFile = "$project.rootDir/$logbackConfigurationFile"
        }
        String platformVersion = resolvePlatformVersion(coreProject)
        if (polymerProject && singleWar) {
            project.dependencies {
                front(group: 'com.haulmont.cuba', name: 'cuba-front', version: platformVersion)
            }
        }
    }

    protected Map<String, Object> collectProperties(Project theProject) {
        def properties = [
                'cuba.logDir' : "$appHome/logs",
                'cuba.confDir': "$appHome/\${cuba.webContextName}/conf",
                'cuba.tempDir': "$appHome/\${cuba.webContextName}/temp",
                'cuba.dataDir': "$appHome/\${cuba.webContextName}/work"
        ]

        if (theProject == coreProject) {
            properties += [
                    'cuba.dataSourceJndiName'  : "jdbc/CubaDS",
                    'cuba.download.directories': "\${cuba.tempDir};\${cuba.logDir}",
                    'cuba.dbDir'               : "web-inf:db"
            ]
        }

        if (theProject == webProject) {
            properties += [
                    'cuba.connectionUrlList'        : "http://localhost:8080/${coreAppName}",
                    'cuba.useLocalServiceInvocation': singleWar ? "true" : "false"
            ]
        }

        if (theProject == portalProject) {
            properties += [
                    'cuba.connectionUrlList'        : "http://localhost:8080/${coreAppName}",
                    'cuba.useLocalServiceInvocation': "false"
            ]
        }

        if (appProperties) {
            properties += appProperties
        }
        properties
    }

    protected List<String> copyLibs(Project theProject) {
        if (theProject) {
            List<String> copied = []
            theProject.logger.info("[CubaWarBuilding] copying libs from configurations.runtime")

            theProject.copy {
                from theProject.configurations.runtime
                from theProject.libsDir
                into "${warDir(theProject)}/WEB-INF/lib"
                include { details ->
                    String name = details.file.name
                    if (!(name.endsWith('-sources.jar')) && name.endsWith(".jar")) {
                        copied.add(name)
                        return true
                    }
                    return false
                }
            }

            if (includeJdbcDriver) {
                theProject.logger.info("[CubaWarBuilding] copying libs from configurations.jdbc")
                def libsDir = "${warDir(theProject)}/WEB-INF/lib"
                theProject.copy {
                    from theProject.configurations.jdbc {
                        exclude { f ->
                            def file = f.file
                            def fileName = (String) file.name
                            if (new File(libsDir, fileName).exists()) {
                                return true
                            }

                            file.absolutePath.startsWith(project.file(libsDir).absolutePath)
                        }
                    }
                    into libsDir
                }
            }

            return copied
        }
        return Collections.emptyList()
    }

    protected void copyFrontLibs() {
        webProject.copy {
            from project.configurations.front
            into "${warDir(webProject)}/WEB-INF/lib"
            include { details ->
                !details.file.name.endsWith('-sources.jar') && details.file.name.contains('cuba-front')
            }
        }
    }

    protected void writeDependencies(Project theProject, String applicationType, def jarNames) {
        File dependenciesFile = new File("${warDir(theProject)}/WEB-INF/${applicationType}.dependencies")

        dependenciesFile.withWriter(StandardCharsets.UTF_8.name()) { writer ->

            List<String> libNames = new ArrayList<>()
            theProject.configurations.runtime.each { File lib ->
                if (!lib.name.endsWith('.jar')) {
                    return false
                }

                def libraryName = DependencyResolver.getLibraryDefinition(lib.name).name

                if (!lib.name.endsWith('-sources.jar')
                        && !lib.name.endsWith('-tests.jar')
                        && !lib.name.endsWith('-themes.jar')
                        && jarNames.contains(libraryName)) {
                    libNames.add(lib.name)
                }
            }

            new File("$theProject.libsDir").listFiles().each { File lib ->
                def libraryName = DependencyResolver.getLibraryDefinition(lib.name).name

                if (!lib.name.endsWith('-sources.jar')
                        && !lib.name.endsWith('-tests.jar')
                        && !lib.name.endsWith('-themes.jar')
                        && jarNames.contains(libraryName)) {
                    libNames.add(lib.name)
                }
            }

            libNames = DependencyResolver.getResolvedLibsList(libNames)
            libNames.forEach { String libName ->
                writer << libName << '\n'
            }
        }
    }

    protected void copyDbScripts(Project theProject) {
        theProject.copy {
            from "${theProject.buildDir}/db"
            into "${warDir(theProject)}/WEB-INF/db"
        }
    }

    protected void copyWebContent(Project theProject) {
        if (theProject) {
            theProject.logger.info("[CubaWarBuilding] copying from web to ${warDir(theProject)}")

            if (webXmlPath) {
                def webXml = new File(webXmlPath)
                if (!webXml.exists()) {
                    throw new GradleException("$webXmlPath doesn't exists")
                }

                def webXmlFileName = webXml.name
                theProject.copy {
                    from 'web'
                    into warDir(theProject)
                    exclude '**/context.xml'
                    exclude "**/$webXmlFileName"//do not copy webXml file twice
                }

                theProject.logger.info("[CubaWarBuilding] copying web.xml from ${webXmlPath} to ${warDir(theProject)}/WEB-INF/web.xml")
                theProject.copy {
                    from webXmlPath
                    into "${warDir(theProject)}/WEB-INF/"
                    rename { String fileName ->
                        "web.xml"
                    }
                }
            } else {
                String path
                if (coreWebXmlPath && theProject == coreProject)
                    path = coreWebXmlPath
                if (webWebXmlPath && theProject == webProject)
                    path = webWebXmlPath
                if (portalWebXmlPath && theProject == portalProject)
                    path = portalWebXmlPath

                if (path) {
                    File file = new File(path)
                    if (!file.exists()) {
                        file = new File("$project.rootDir/$path")
                        if (!file.exists())
                            throw new GradleException("File $path or $project.rootDir/$path not found")
                    }

                    theProject.copy {
                        from 'web'
                        into warDir(theProject)
                        exclude '**/META-INF/context.xml'
                        exclude { it.file.absolutePath == file.absolutePath }
                    }

                    theProject.copy {
                        from file.absolutePath
                        into "${warDir(theProject)}/WEB-INF/"
                        rename { String fileName ->
                            "web.xml"
                        }
                    }

                } else {
                    theProject.copy {
                        from 'web'
                        into warDir(theProject)
                        exclude '**/META-INF/context.xml'
                    }
                }
            }
            copyLogbackConfigurationFile(theProject)
        }
    }

    protected void copyWebContext(Project theProject) {
        if (theProject) {
            theProject.logger.info("[CubaWarBuilding] copying context.xml to ${warDir(theProject)}")
            if (theProject == coreProject && coreContextXmlPath) {
                def coreContextXml = new File(coreContextXmlPath)
                if (!coreContextXml.exists()) {
                    throw new GradleException("$coreContextXmlPath doesn't exists")
                }

                def coreContextXmlFileName = coreContextXml.name
                theProject.logger.info("[CubaWarBuilding] copying context.xml from ${coreContextXmlPath} to ${warDir(theProject)}/META-INF/context.xml")
                theProject.copy {
                    from coreContextXmlPath
                    into "${warDir(theProject)}/META-INF/"
                    rename { String fileName ->
                        "context.xml"
                    }
                }
                if ('context.xml' != coreContextXmlFileName) {
                    theProject.logger.info("[CubaWarBuilding] removing $coreContextXmlFileName from META-INF")

                    theProject.delete("${warDir(theProject)}/META-INF/${coreContextXmlFileName}")
                }
            } else if (theProject == coreProject && hsqlInProcess) {
                def contextFile = new File("${theProject.projectDir}/web/META-INF/context.xml")
                if (!contextFile.exists()) {
                    throw new GradleException("$contextFile doesn't exists")
                }

                def context
                try {
                    context = new XmlParser().parse(contextFile)
                } catch (Exception ignored) {
                    throw new GradleException("Core context.xml parsing error")
                }

                String url = context.Resource.@url.get(0)
                context.Resource.@url = "jdbc:hsqldb:file:db/hsql" + url.substring(url.lastIndexOf('/'))

                def targetFile = new File("${warDir(theProject)}/META-INF/context.xml")
                def printer = new XmlNodePrinter(new PrintWriter(new FileWriter(targetFile)))
                printer.preserveWhitespace = true
                printer.print(context)
            } else {
                theProject.copy {
                    from 'web/META-INF/context.xml'
                    into "${warDir(theProject)}/META-INF"
                }
            }
        }
    }

    protected void copySpecificWebContent(Project theProject) {
        if (theProject) {
            if (!projectAll) {
                def excludePatterns = ['**/web.xml', '**/context.xml'] + webContentExclude
                if (theProject.configurations.findByName('webcontent')) {
                    theProject.configurations.webcontent.files.each { dep ->
                        theProject.logger.info("[CubaWarBuilding] copying webcontent from $dep.absolutePath to ${warDir(theProject)}")
                        theProject.copy {
                            from theProject.zipTree(dep.absolutePath)
                            into warDir(theProject)
                            excludes = excludePatterns
                            includeEmptyDirs = false
                        }
                    }
                }
                theProject.logger.info("[CubaWarBuilding] copying webcontent from ${theProject.buildDir}/web to ${warDir(theProject)}")
                theProject.copy {
                    from "${theProject.buildDir}/web"
                    into warDir(theProject)
                    excludes = excludePatterns
                }
                project.logger.info("[CubaWarBuilding] copying from web to ${warDir(theProject)}")
                project.copy {
                    from theProject.file('web')
                    into warDir(theProject)
                    excludes = excludePatterns
                }
                def webToolkit = theProject.rootProject.subprojects.find { subprj -> subprj.name.endsWith('web-toolkit') }
                if (webToolkit) {
                    theProject.logger.info("[CubaWarBuilding] copying widgetset JAR from \"webArchive\" task output to ${warDir(theProject)}/WEB-INF/lib")
                    def webArchiveTask = webToolkit.tasks.findByName('webArchive')
                    if (webArchiveTask instanceof Jar) {
                        theProject.copy {
                            from webArchiveTask.outputs.files
                            into "${warDir(theProject)}/WEB-INF/lib"
                        }
                    }
                }
            } else {
                theProject.logger.info("[CubaWarBuilding] copying webcontent from theProject-all directories to ${warDir(theProject)}")
                theProject.copy {
                    from new File(project.project(':cuba-web').projectDir, 'web')
                    from new File(project.project(':charts-web').projectDir, 'web')
                    from new File(project.project(':workflow-web').projectDir, 'web')
                    from new File(project.project(':reports-web').projectDir, 'web')
                    from new File(project.project(':refapp-web').buildDir, 'web')
                    from new File(project.project(':refapp-web-toolkit').buildDir, 'web')
                    from new File(project.project(':refapp-web').projectDir, 'web')
                    into warDir(theProject)
                    exclude '**/web.xml'
                    exclude '**/context.xml'
                    exclude '**/gwt-unitCache/'
                }
            }
        }
    }

    protected void writeLocalAppProperties(Project theProject, def properties) {
        File appPropFile = new File("${warDir(theProject)}/WEB-INF/local.app.properties")

        project.logger.info("[CubaWarBuilding] writing $appPropFile")
        appPropFile.withWriter('UTF-8') { writer ->
            properties.each { key, value ->
                writer << key << ' = ' << value << '\n'
            }
        }
    }

    protected void writeIndexHtmlTemplate() {
        File indexTemplate = new File("${warDir(polymerProject)}/index.ftl")
        File indexHtml = new File("${warDir(polymerProject)}/index.html")
        def text = FrontUtils.rewriteBaseUrl(indexHtml.text, "/$appName/front/")
        text = FrontUtils.rewriteApiUrl(text, null)
        indexTemplate.write(text)
        indexHtml.delete()
    }

    protected void copyLogbackConfigurationFile(Project theProject) {
        if (logbackConfigurationFile) {
            if (!new File(logbackConfigurationFile).exists()) {
                throw new GradleException("$logbackConfigurationFile doesn't exists")
            }
            theProject.copy {
                from new File(logbackConfigurationFile)
                into "${warDir(theProject)}/WEB-INF/classes"
                rename { String fileName ->
                    "logback.xml"
                }
            }
        } else if (useDefaultLogbackConfiguration) {
            InputStream inputStream = getClass().getClassLoader().getResourceAsStream("logback.xml")
            if (inputStream == null) {
                throw new GradleException("Default logback configuration file doesn't exists")
            }
            try {
                Path classesDir = theProject.file("${warDir(theProject)}/WEB-INF/classes").toPath()
                Files.createDirectories(classesDir)
                Files.copy(inputStream, classesDir.resolve("logback.xml"),
                        StandardCopyOption.REPLACE_EXISTING)
            } finally {
                try {
                    inputStream.close()
                } catch (Exception e) {
                    //Do nothing
                }
            }
        }
    }

    protected void processDoAfter() {
        if (doAfter) {
            project.logger.info("[CubaWarBuilding] calling doAfter")
            doAfter.call()
        }
    }

    protected void touchWebXml(Project theProject) {
        def webXml = new File("${warDir(theProject)}/WEB-INF/web.xml")
        if (!webXml.exists()) {
            throw new GradleException("$webXml doesn't exists")
        }

        if (theProject.ext.has('webResourcesTs')) {
            theProject.logger.info("[CubaWarBuilding] update web resources timestamp")

            // detect version automatically
            String buildTimeStamp = theProject.ext.get('webResourcesTs').toString()

            def webXmlText = Strings.nullToEmpty(webXml.text)
            if (webXmlText.contains('${webResourcesTs}')) {
                webXmlText = webXmlText.replace('${webResourcesTs}', buildTimeStamp)
            }
            webXml.write(webXmlText)
        }
        theProject.logger.info("[CubaWarBuilding] touch ${warDir(theProject)}/WEB-INF/web.xml")
        webXml.setLastModified(System.currentTimeMillis())
    }

    protected void packWarFile(Project project, File destFile) {
        ant.jar(destfile: destFile, basedir: warDir(project))
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
        throw new GradleException("[CubaWarBuilding] Platform version is undefined")
    }
}