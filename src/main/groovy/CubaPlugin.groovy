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


import com.haulmont.gradle.polymer.CubaPolymerToolingInfoTask
import com.haulmont.gradle.task.db.CubaHsqlStart
import com.haulmont.gradle.task.db.CubaHsqlStop
import com.haulmont.gradle.utils.BOMVersions
import com.moowork.gradle.node.NodeExtension
import com.moowork.gradle.node.NodePlugin
import groovy.util.slurpersupport.GPathResult
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.language.jvm.tasks.ProcessResources

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.function.Consumer
import java.util.jar.JarFile
import java.util.jar.Manifest

import static org.apache.commons.io.IOUtils.closeQuietly

class CubaPlugin implements Plugin<Project> {

    public static final String APP_COMPONENT_ID_MANIFEST_ATTRIBUTE = 'App-Component-Id'
    public static final String APP_COMPONENT_VERSION_MANIFEST_ATTRIBUTE = 'App-Component-Version'

    @Override
    void apply(Project project) {
        project.logger.info("[CubaPlugin] applying to project $project.name")

        project.repositories {
            project.rootProject.buildscript.repositories.each {
                project.logger.info("[CubaPlugin] using repository $it.name" + (it.hasProperty('url') ? " at $it.url" : ""))
                project.repositories.add(it)
            }
        }

        if (project != project.rootProject && project.name.endsWith('-polymer-client')) {
            applyToPolymerClientProject(project)
            project.afterEvaluate { p ->
                doAfterEvaluateForAnyProject(p)
            }
            return
        }

        exportTaskTypes(project)

        if (project == project.rootProject) {
            def cubaExtension = project.extensions.create("cuba", CubaPluginExtension, project)
            applyToRootProject(project, cubaExtension)
        } else {
            project.extensions.extraProperties.set("appModuleType", null)
            applyToModuleProject(project)
        }

        project.afterEvaluate { p ->
            doAfterEvaluateForAnyProject(p)
            if (p == project.rootProject) {
                doAfterEvaluateForRootProject(p)
            } else {
                doAfterEvaluateForModuleProject(p)
            }
        }
    }

    private void exportTaskTypes(Project project) {
        project.ext.CubaHsqlStop = CubaHsqlStop.class
        project.ext.CubaHsqlStart = CubaHsqlStart.class
    }

    private void doAfterEvaluateForAnyProject(Project project) {
        project.group = project.cuba.artifact.group
        project.version = project.cuba.artifact.version + (project.cuba.artifact.isSnapshot ? '-SNAPSHOT' : '')

        if (project.hasProperty('install')) {
            def uploadUrl = project.cuba.uploadRepository.url
            def haulmontUploadRepo = System.getenv('HAULMONT_REPOSITORY_UPLOAD_URL')
            if (uploadUrl == null && haulmontUploadRepo) {
                if (!haulmontUploadRepo.endsWith('/')) {
                    haulmontUploadRepo += '/'
                }

                uploadUrl = haulmontUploadRepo + "${project.cuba.artifact.isSnapshot ? 'snapshots' : 'releases'}"
            }

            def uploadUser = project.cuba.uploadRepository.user
            def uploadPassword = project.cuba.uploadRepository.password

            // Override upload properties if passed as project parameters, gradle upload -PuploadUrl http://someurl
            if (project.hasProperty('uploadUrl')) {
                uploadUrl = project['uploadUrl']
            }
            if (project.hasProperty('uploadUser')) {
                uploadUser = project['uploadUser']
            }
            if (project.hasProperty('uploadPassword')) {
                uploadPassword = project['uploadPassword']
            }

            if (uploadUrl != null) {
                // Check if the Maven plugin has been applied
                project.configurations {
                    deployerJars
                }
                project.dependencies {
                    deployerJars(group: 'org.apache.maven.wagon', name: 'wagon-http', version: '2.12')
                }

                project.logger.info("[CubaPlugin] upload repository: $uploadUrl ($uploadUser:$uploadPassword)")

                project.uploadArchives.configure {
                    repositories.mavenDeployer {
                        name = 'httpDeployer'
                        configuration = project.configurations.deployerJars
                        repository(url: uploadUrl) {
                            authentication(userName: uploadUser, password: uploadPassword)
                        }
                    }
                }
            }
        }
    }

    private void applyToRootProject(Project project, CubaPluginExtension cubaExtension) {
        project.configurations {
            appComponent
        }

        enableBOMVersionResolver(project, cubaExtension.bom)

        project.task([type: CubaSetupTomcat], 'setupTomcat')
        project.task([type: CubaStartTomcat], 'start')
        project.task([type: Exec], 'tomcat')
        project.task([type: CubaStopTomcat], 'stop')
        project.task([type: CubaDropTomcat], 'dropTomcat')
        project.task([type: CubaZipProject], 'zipProject')

        importBomFromDependencies(project, cubaExtension)
    }

    private void enableBOMVersionResolver(Project project, BOMVersions bomStore) {
        project.ext.bom = bomStore
    }

    private void doAfterEvaluateForModuleProject(Project project) {
        addDependenciesFromAppComponents(project)

        if (project.name.endsWith('-global')) {
            project.buildInfo.setDependsOn(project.getTasksByName('processResources', false))
            project.getTasksByName('classes', false).each { it.dependsOn(project.buildInfo) }
        }
    }

    private void doAfterEvaluateForRootProject(Project project) {
        project.configurations {
            tomcat
            uberJar
            front
        }
        project.dependencies {
            tomcat(group: 'org.apache.tomcat', name: 'tomcat', version: project.cuba.tomcat.version, ext: 'zip')
        }

        CubaSetupTomcat setupTomcat = project.getTasksByName('setupTomcat', false).iterator().next()
        setupTomcat.tomcatRootDir = project.cuba.tomcat.dir

        CubaStartTomcat start = project.getTasksByName('start', false).iterator().next()
        start.tomcatRootDir = project.cuba.tomcat.dir

        Exec tomcat = project.getTasksByName('tomcat', false).iterator().next()
        if (System.getProperty('os.name').contains('Windows')) {
            tomcat.workingDir "${project.cuba.tomcat.dir}/bin"
            tomcat.commandLine 'cmd'
            tomcat.args '/C', 'catalina.bat', 'jpda', 'run'
        } else {
            tomcat.workingDir "${project.cuba.tomcat.dir}/bin"
            tomcat.commandLine './catalina.sh'
            tomcat.args 'jpda', 'run'
        }

        CubaStopTomcat stop = project.getTasksByName('stop', false).iterator().next()
        stop.tomcatRootDir = project.cuba.tomcat.dir

        CubaDropTomcat dropTomcat = project.getTasksByName('dropTomcat', false).iterator().next()
        dropTomcat.tomcatRootDir = project.cuba.tomcat.dir
        dropTomcat.listeningPort = '8787'

        if (project.hasProperty('idea')) {
            applyIdeaConfigRootProject(project)
        }

        if (project.hasProperty('eclipse')) {
            applyEclipseConfigRootProject(project)
        }

        defineTasksExecutionOrder(project)
    }

    private void applyEclipseConfigRootProject(Project project) {
        project.logger.info "[CubaPlugin] configuring Eclipse project"
        project.eclipse.project.file.withXml { provider ->
            def projectDescription = provider.asNode()

            def filteredResources = projectDescription.children().find { it.name() == 'filteredResources' }
            if (filteredResources != null) {
                filteredResources.children().clear()
            } else {
                filteredResources = projectDescription.appendNode('filteredResources')
            }
            filteredResources.append(nestedProjectsFilter())
        }
        project.eclipse.classpath.file.withXml { provider ->
            def classpath = provider.asNode()
            for (String projectName : project.childProjects.keySet()) {
                Node entry = classpath.appendNode('classpathentry')
                entry.@kind = 'src'
                if (projectName.startsWith("app")) {
                    projectName = projectName.replace("app", project.name)
                }
                entry.@path = '/' + projectName
                entry.@exported = 'true'

                classpath.children().remove(entry)
                classpath.children().add(0, entry)
            }
        }
        def cleanTask = project.getTasksByName("clean", false).iterator().next()
        cleanTask.delete = ['build/libs', 'build/tmp']
    }

    private void applyIdeaConfigRootProject(Project project) {
        project.logger.info "[CubaPlugin] configuring IDEA project"
        project.idea.project.ipr {
            withXml { provider ->
                def node = provider.node.component.find { it.@name == 'ProjectRootManager' }
                node.@languageLevel = 'JDK_1_8'
                node.@'project-jdk-name' = '1.8'

                if (project.cuba.ide.copyright) {
                    node = provider.node.component.find { it.@name == 'CopyrightManager' }
                    node.@default = 'default'

                    node = node.appendNode('copyright')
                    node.appendNode('option', [name: 'notice', value: project.cuba.ide.copyright])

                    node.appendNode('option', [name: 'keyword', value: 'Copyright'])
                    node.appendNode('option', [name: 'allowReplaceKeyword', value: ''])
                    node.appendNode('option', [name: 'myName', value: 'default'])
                    node.appendNode('option', [name: 'myLocal', value: 'true'])
                }

                if (project.cuba.ide.vcs)
                    provider.node.component.find {
                        it.@name == 'VcsDirectoryMappings'
                    }.mapping.@vcs = project.cuba.ide.vcs //'svn'

                def encodingNode = provider.node.component.find { it.@name == 'Encoding' }
                encodingNode.@defaultCharsetForPropertiesFiles = 'UTF-8'
                encodingNode.appendNode('file', [url: 'PROJECT', charset: 'UTF-8'])
            }
        }

        project.idea.workspace.iws.withXml { provider ->
            def runManagerNode = provider.asNode().component.find { it.@name == 'RunManager' }

            def listNode = runManagerNode.list.find { it }
            if (listNode) {
                // old IntelliJ Idea
                if (listNode.@size == '0') {
                    createIdeaRunConfigurationNode(project, runManagerNode)

                    listNode.appendNode('item', [index: '0', class: 'java.lang.String', itemvalue: 'Remote.localhost:8787'])
                    listNode.@size = 1
                }
            } else {
                // Project were opened in IntelliJ idea 2017.2+
                def remoteConfNode = runManagerNode.configuration.find {
                    it.@name == 'localhost:8787' && it.@type == 'Remote'
                }
                if (remoteConfNode == null) {
                    createIdeaRunConfigurationNode(project, runManagerNode)
                }
            }

            def changeListManagerNode = provider.asNode().component.find { it.@name == 'ChangeListManager' }
            def ignored = changeListManagerNode.ignored.find { it }
            if (ignored == null) {
                project.logger.info("[CubaPlugin] Configure ignored files")
                changeListManagerNode.appendNode('ignored', [mask: '*.ipr'])
                changeListManagerNode.appendNode('ignored', [mask: '*.iml'])
                changeListManagerNode.appendNode('ignored', [mask: '*.iws'])
            }

            def projectViewNode = provider.asNode().component.find { it.@name == 'ProjectView' }
            if (!projectViewNode) {
                projectViewNode = provider.asNode().appendNode('component', [name: 'ProjectView'])

                def projectViewPanesNode = projectViewNode.appendNode('panes')
                def projectPaneNode = projectViewPanesNode.appendNode('pane', [id: 'ProjectPane'])
                projectPaneNode.appendNode('option', [name: 'show-excluded-files', value: 'false'])
            }

            // Set Highlighting level to Syntax only for files
            List<String> disabledHintsPaths = project.cuba.ide.ideaOptions.disabledHintsPaths
            if (!disabledHintsPaths.isEmpty()) {
                project.logger.info("[CubaPlugin] Configure disabled hints for files")
                Node daemonCodeAnalyzerNode = provider.asNode().component.find { it.@name == 'DaemonCodeAnalyzer' } as Node
                if (daemonCodeAnalyzerNode == null) {
                    daemonCodeAnalyzerNode = provider.asNode().appendNode('component', [name: 'DaemonCodeAnalyzer'])
                }
                Node disableHintsNode = daemonCodeAnalyzerNode.disable_hints[0] as Node
                if (disableHintsNode == null) {
                    disableHintsNode = daemonCodeAnalyzerNode.appendNode('disable_hints')
                }

                Node highlightSettingsPerFileNode = provider.asNode().component.find { it.@name == 'HighlightingSettingsPerFile' } as Node
                if (highlightSettingsPerFileNode == null) {
                    highlightSettingsPerFileNode = provider.asNode().appendNode('component', [name: 'HighlightingSettingsPerFile'])
                }

                for (String disabledHintsFile : disabledHintsPaths) {
                    disableHintsNode.appendNode('file', [url: 'file://$PROJECT_DIR$/' + disabledHintsFile])

                    highlightSettingsPerFileNode.appendNode('setting', [
                            file: 'file://$PROJECT_DIR$/' + disabledHintsFile,
                            root0: 'SKIP_INSPECTION',
                    ])
                }
            }

            // disable Gradle import popup
            Node propertiesComponentNode = provider.asNode().component.find { it.@name == 'PropertiesComponent' } as Node
            if (propertiesComponentNode == null) {
                propertiesComponentNode = provider.asNode().appendNode('component', [name: 'PropertiesComponent'])
            }
            propertiesComponentNode.appendNode('property', [name: 'show.inlinked.gradle.project.popup', value: 'false'])
        }

        project.idea.module.iml.withXml { provider ->
            Node componentNode = provider.node.component.find { it.@name == 'NewModuleRootManager' } as Node
            Node contentNode = componentNode.content.find { it.@url == 'file://$MODULE_DIR$/' } as Node
            if (contentNode)
                contentNode.appendNode('excludeFolder', ['url': 'file://$MODULE_DIR$/deploy'])
        }
    }

    private void createIdeaRunConfigurationNode(Project project, Node runManagerNode) {
        project.logger.info("[CubaPlugin] Creating remote configuration")

        def confNode = runManagerNode.appendNode('configuration', [name: 'localhost:8787', type: 'Remote', factoryName: 'Remote'])
        confNode.appendNode('option', [name: 'USE_SOCKET_TRANSPORT', value: 'true'])
        confNode.appendNode('option', [name: 'SERVER_MODE', value: 'false'])
        confNode.appendNode('option', [name: 'SHMEM_ADDRESS', value: 'javadebug'])
        confNode.appendNode('option', [name: 'HOST', value: 'localhost'])
        confNode.appendNode('option', [name: 'PORT', value: '8787'])
        confNode.appendNode('method')

        runManagerNode.@selected = 'Remote.localhost:8787'
    }

    /**
     * Method defines a tasks execution order, which is necessary for parallel task execution.
     * For example if we run 'deploy' and 'start' tasks in parallel mode we want the 'start' task to
     * be executed only after 'deploy' is completed
     */
    private void defineTasksExecutionOrder(Project project) {
        def deploymentTasks = findAllTasksByType(project, CubaDeployment.class)
        def dbCreationTasks = findAllTasksByType(project, CubaDbCreation.class)
        def dbUpdateTasks = findAllTasksByType(project, CubaDbUpdate.class)
        def startTomcatTasks = project.getTasks().findAll { it instanceof CubaStartTomcat }
        def setupTomcatTasks = project.getTasks().findAll { it instanceof CubaSetupTomcat }
        def dropTomcatTasks = project.getTasks().findAll { it instanceof CubaDropTomcat }
        def hsqlStartTasks = project.getTasks().findAll { it instanceof CubaHsqlStart }
        def deployNameTasks = project.getTasksByName("deploy", true)
        def tomcatNameTasks = project.getTasksByName("tomcat", true)

        startTomcatTasks.addAll(tomcatNameTasks)

        startTomcatTasks.each {
            it.mustRunAfter deploymentTasks
            it.mustRunAfter dbCreationTasks
            it.mustRunAfter dbUpdateTasks
            it.mustRunAfter setupTomcatTasks
            it.mustRunAfter deployNameTasks
        }

        deploymentTasks.each {
            it.mustRunAfter setupTomcatTasks
        }

        setupTomcatTasks.each {
            it.mustRunAfter dropTomcatTasks
        }

        dbCreationTasks.each {
            it.mustRunAfter hsqlStartTasks
        }

        dbUpdateTasks.each {
            it.mustRunAfter hsqlStartTasks
        }
    }

    private void importBomFromDependencies(Project project, CubaPluginExtension cubaExtension) {
        def bomComponentConf = project.rootProject.configurations.findByName('bom')
        if (bomComponentConf == null) {
            return
        }

        project.logger.info("[CubaPlugin] Import BOM from dependencies")

        def resolvedConfiguration = bomComponentConf.resolvedConfiguration

        def dependencies = resolvedConfiguration.firstLevelModuleDependencies
        def addedArtifacts = new HashSet<ResolvedArtifact>()

        walkJarDependencies(dependencies, addedArtifacts, { artifact ->
            def jarFile = new JarFile(artifact.file)
            try {
                def manifest = jarFile.manifest
                if (manifest == null) {
                    return
                }

                def compId = manifest.mainAttributes.getValue(APP_COMPONENT_ID_MANIFEST_ATTRIBUTE)
                def compVersion = manifest.mainAttributes.getValue(APP_COMPONENT_VERSION_MANIFEST_ATTRIBUTE)
                if (compId == null || compVersion == null) {
                    return
                }

                def bomPath = compId.replace('.', '/') + '/bom.properties'
                def bomEntry = jarFile.getEntry(bomPath)
                if (bomEntry != null) {
                    def bomInputStream = jarFile.getInputStream(bomEntry)
                    try {
                        project.logger.info("[CubaPlugin] Found BOM info in ${artifact.file.absolutePath}")

                        cubaExtension.bom.load(bomInputStream)
                    } finally {
                        closeQuietly(bomInputStream)
                    }
                }
            } finally {
                closeQuietly(jarFile)
            }
        })
    }

    /**
     * Finds tasks by type in the given project and in its sub projects
     */
    private Collection<Task> findAllTasksByType(Project project, Class type) {
        def result = []
        project.getAllTasks(true).each {subProject, tasks ->
            result.addAll(tasks.findAll {type.isAssignableFrom(it.class)})
        }
        return result
    }

    private Node nestedProjectsFilter() {
        def filter = new Node(null, 'filter')
        filter.appendNode('id', new Date().getTime())   // Eclipse does the same
        filter.appendNode('name', )
        filter.appendNode('type', 26)                   // EXCLUDE_ALL = 2 | FOLDERS= 8 | INHERITABLE = 16
        def node = filter.appendNode('matcher')

        node.appendNode('id', 'org.eclipse.ui.ide.multiFilter')
        node.appendNode('arguments', '1.0-projectRelativePath-matches-true-false-modules')

        return filter
    }

    private void applyToModuleProject(Project project) {
        project.sourceCompatibility = '1.8'
        project.targetCompatibility = '1.8'

        project.configurations {
            provided
            themes
            jdbc
        }

        project.sourceSets {
            main {
                java {
                    srcDir 'src'
                    compileClasspath = compileClasspath + project.configurations.provided + project.configurations.jdbc
                }
                resources { srcDir 'src' }
                output.dir("$project.buildDir/enhanced-classes/main")
            }
            test {
                java {
                    srcDir 'test'
                    compileClasspath = compileClasspath + project.configurations.provided + project.configurations.jdbc
                }
                resources { srcDir 'test' }
                output.dir("$project.buildDir/enhanced-classes/test")
            }
        }

        project.tasks.withType(JavaCompile) {
            options.compilerArgs << "-Xlint:-options"
            options.encoding = 'UTF-8'
        }

        project.tasks.withType(Javadoc) {
            options.encoding = 'UTF-8'
        }

        project.jar {
            // Ensure there will be no duplicates in jars
            exclude { details -> !details.isDirectory() && isEnhanced(project, details.file, project.buildDir) }
            // add META-INF/beans.xml
            from ("$project.buildDir/tmp") {
                include 'META-INF/beans.xml'
            }
        }

        if (!project.hasProperty("noBeansXml")) {
            // create META-INF/beans.xml
            project.jar.doFirst {
                def file = new File("$project.buildDir/tmp/META-INF/beans.xml")
                file.parentFile.mkdirs()
                file.write(
'''<?xml version="1.0" encoding="UTF-8"?>
<beans xmlns="http://xmlns.jcp.org/xml/ns/javaee"
       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       xsi:schemaLocation="http://xmlns.jcp.org/xml/ns/javaee
                       http://xmlns.jcp.org/xml/ns/javaee/beans_1_1.xsd"
       version="1.1" bean-discovery-mode="none">
</beans>'''
                )
            }
        }

        if (project.name.endsWith('-global')) {
            project.task([type: CubaBuildInfo], 'buildInfo')
        }

        if (project.name.endsWith('-core')) {
            File dbDir = new File(project.projectDir, "db")
            project.task([type: CubaDbScriptsAssembling], 'assembleDbScripts')
            project.assemble.dependsOn(project.assembleDbScripts)

            if (dbDir.exists() && dbDir.isDirectory() && dbDir.list().length > 0) {
                project.task([type: Zip, dependsOn: 'assembleDbScripts'], 'dbScriptsArchive') {
                    from "${project.buildDir}/db"
                    include "*-$project.rootProject.name/**/*"
                    exclude '**/*.bat'
                    exclude '**/*.sh'
                    classifier = 'db'
                }

                project.artifacts {
                    archives project.dbScriptsArchive
                }
            }
        }

        if (project.name.endsWith('-toolkit')) {
            // hibernate validator breaks GWT compilation
            project.configurations {
                compile.exclude group: 'org.hibernate', module: 'hibernate-validator'
            }
        }

        // add web resources version for correct caching
        if (project.name.endsWith('-web')) {
            def resourceBuildTimeStamp = new SimpleDateFormat('yyyy_MM_dd_HH_mm').format(new Date())
            project.logger.info("[CubaPlugin] set web resources timestamp for project ${project.name}")

            project.ext.set('webResourcesTs', resourceBuildTimeStamp)
        }

        if (project.hasProperty('idea') && project.hasProperty('ideaModule')) {
            project.logger.info "[CubaPlugin] configuring IDEA module $project.name"

            def providedConfs = new ArrayList<Configuration>()
            providedConfs.add(project.configurations.compile)
            providedConfs.add(project.configurations.provided)
            providedConfs.add(project.configurations.jdbc)

            if (project.configurations.findByName('themes')) {
                providedConfs.add(project.configurations.themes)
            }

            project.idea.module.scopes += [PROVIDED: [plus: providedConfs, minus: []]]

            project.idea.module.inheritOutputDirs = true

            // Enhanced classes library entry must go before source folder
            project.idea.module.iml.withXml { provider ->
                Node rootNode = (Node) provider.node.component.find { it.@name == 'NewModuleRootManager' }

                int srcIdx = rootNode.children().findIndexOf {
                    it instanceof Node && it.name() == 'orderEntry' && it.@type == 'sourceFolder'
                }

                def moveBeforeSources = { String dir ->
                    Node enhNode = (Node) rootNode.children().find {
                        it instanceof Node && it.name() == 'orderEntry' && it.@type == 'module-library' &&
                                it.library.CLASSES.root.@url.contains('file://$MODULE_DIR$/build/enhanced-classes/' + dir)
                    }
                    if (!enhNode && project.name.endsWith('-global')) {
                        enhNode = new Node(rootNode, 'orderEntry', [type: 'module-library', scope: 'RUNTIME'])
                        Node libraryNode = new Node(enhNode, 'library')
                        Node classesNode = new Node(libraryNode, 'CLASSES')
                        new Node(classesNode, 'root', ['url': 'file://$MODULE_DIR$/build/enhanced-classes/' + dir])
                        new Node(libraryNode, 'JAVADOC')
                        new Node(libraryNode, 'SOURCES')
                    }
                    if (enhNode) {
                        rootNode.children().remove(enhNode)
                        rootNode.children().add(srcIdx, enhNode)
                    }
                }
                moveBeforeSources('main')
                moveBeforeSources('test')
            }
        }

        if (project.hasProperty('eclipse')) {
            project.logger.info "[CubaPlugin] configuring Eclipse module $project.name"

            project.eclipse.classpath {
                plusConfigurations += [project.configurations.provided]
                file.whenMerged { classpath ->
                    classpath.entries.removeAll { entry ->
                        entry.path.contains('build/enhanced-classes')
                    }
                }
            }

            project.eclipse.project.file.withXml { provider ->
                def projectDescription = provider.asNode()
                def projectName = project.name.startsWith("app") ? project.name.replace("app", project.parent.name) : project.name
                def name = projectDescription.children().find { it.name() == 'name' }
                if (name != null) {
                    name.value = projectName
                } else {
                    projectDescription.appendNode('name', projectName)
                }
            }

            project.eclipse.classpath.file.withXml { provider ->
                def root = provider.asNode()

                for (Node classpath : root.children()) {

                    if (classpath.@kind == "src") {
                        def path = classpath.@path
                        if (path.startsWith("/app")) {
                            path = path.replace("/app", "/$project.parent.name")
                            classpath.@path = path
                        }
                    }
                }

                if (project.name.endsWith('-global')) {
                    Node entry = root.appendNode('classpathentry')
                    entry.@kind = 'lib'
                    entry.@path = "$project.buildDir/enhanced-classes"
                    entry.@exported = 'true'

                    root.children().remove(entry)
                    root.children().add(0, entry)
                }
            }
        }
    }

    private void applyToPolymerClientProject(Project project) {
        project.plugins.apply(NodePlugin)
        def nodeExtension = project.extensions.getByType(NodeExtension)
        nodeExtension.version = '8.9.3'
        nodeExtension.download = true

        project.task([type: CubaPolymerToolingInfoTask], CubaPolymerToolingInfoTask.NAME)

        project.getTasks().withType(JavaCompile) {
            enabled = false
        }
        project.getTasks().withType(ProcessResources) {
            enabled = false
        }
    }

    private void addDependenciesFromAppComponents(Project project) {
        def moduleName = getModuleName(project)

        project.logger.info("[CubaPlugin] Setting up dependencies for module $moduleName")

        def appComponentConf = project.rootProject.configurations.appComponent
        if (appComponentConf.dependencies.size() > 0) {
            addDependenciesFromAppComponentsConfiguration(project, moduleName)
        } else {
            addDependenciesFromAppComponentsClassPath(project, moduleName)
        }
    }

    private void addDependenciesFromAppComponentsClassPath(Project project, String moduleName) {
        project.logger.info("[CubaPlugin] Import app-components to ${project.name} from classpath")

        def jarNames = new HashSet<String>()
        def skippedDeps = new ArrayList<SkippedDep>()

        def manifests = getClass().getClassLoader().getResources("META-INF/MANIFEST.MF")
        while (manifests.hasMoreElements()) {
            def manifest = new Manifest(manifests.nextElement().openStream())

            def compId = manifest.mainAttributes.getValue(APP_COMPONENT_ID_MANIFEST_ATTRIBUTE)
            def compVersion = manifest.mainAttributes.getValue(APP_COMPONENT_VERSION_MANIFEST_ATTRIBUTE)

            if (compId && compVersion) {
                def compDescrPath = compId.replace('.', '/') + '/app-component.xml'
                def compGroup = compId

                def url = CubaPlugin.class.getResource(compDescrPath)
                if (url) {
                    project.logger.info("[CubaPlugin] Found app-component info in $url")
                    def xml = new XmlSlurper().parseText(url.openStream().getText('UTF-8'))

                    applyAppComponentXml(xml, moduleName, compGroup, compVersion, project, skippedDeps, compId, jarNames)
                }
            }
        }

        if (!jarNames.isEmpty()) {
            project.logger.info("[CubaPlugin] Inherited app JAR names for deploy task: $jarNames")
            project.ext.inheritedDeployJarNames = jarNames
        }

        if (!skippedDeps.isEmpty()) {
            skippedDeps.sort()
            def last = skippedDeps.last()
            addDependencyToConfiguration(project, last.dep, last.conf)
        }
    }

    private void addDependenciesFromAppComponentsConfiguration(Project project, String moduleName) {
        project.logger.info("[CubaPlugin] Import app-components to ${project.name} from appComponent configuration")

        def addedArtifacts = new HashSet<ResolvedArtifact>()
        def jarNames = new HashSet<String>()
        def skippedDeps = new ArrayList<SkippedDep>()

        def appComponentConf = project.rootProject.configurations.appComponent
        def resolvedConfiguration = appComponentConf.resolvedConfiguration
        def dependencies = resolvedConfiguration.firstLevelModuleDependencies

        project.ext.resolvedAppComponents = []

        walkJarDependencies(dependencies, addedArtifacts, { artifact ->
            def jarFile = new JarFile(artifact.file)
            try {
                def manifest = jarFile.manifest
                if (manifest == null) {
                    return
                }

                def compId = manifest.mainAttributes.getValue(APP_COMPONENT_ID_MANIFEST_ATTRIBUTE)
                def compVersion = manifest.mainAttributes.getValue(APP_COMPONENT_VERSION_MANIFEST_ATTRIBUTE)
                if (compId == null || compVersion == null) {
                    return
                }

                project.logger.info("[CubaPlugin] Inspect app-component dependency ${artifact.name}")

                def compDescriptorPath = compId.replace('.', '/') + '/app-component.xml'
                def compGroup = compId

                def descriptorEntry = jarFile.getEntry(compDescriptorPath)
                if (descriptorEntry != null) {
                    project.ext.resolvedAppComponents.add(compId + ':' + compVersion)

                    def descriptorInputStream = jarFile.getInputStream(descriptorEntry)
                    try {
                        project.logger.info("[CubaPlugin] Found app-component info in ${artifact.file.absolutePath}")
                        def xml = new XmlSlurper().parseText(descriptorInputStream.getText(StandardCharsets.UTF_8.name()))

                        applyAppComponentXml(xml, moduleName, compGroup, compVersion, project, skippedDeps, compId, jarNames)
                    } finally {
                        closeQuietly(descriptorInputStream)
                    }
                }
            } finally {
                closeQuietly(jarFile)
            }
        })

        if (!jarNames.isEmpty()) {
            project.logger.info("[CubaPlugin] Inherited app JAR names for deploy task: $jarNames")
            project.ext.inheritedDeployJarNames = jarNames
        }

        if (!skippedDeps.isEmpty()) {
            skippedDeps.sort()
            def last = skippedDeps.last()
            addDependencyToConfiguration(project, last.dep, last.conf)
        }
    }

    private void walkJarDependencies(Set<ResolvedDependency> dependencies,
                                     Set<ResolvedArtifact> passedArtifacts,
                                     Consumer<ResolvedArtifact> artifactAction) {
        for (dependency in dependencies) {
            walkJarDependencies(dependency.children, passedArtifacts, artifactAction)

            for (artifact in dependency.moduleArtifacts) {
                if (passedArtifacts.contains(artifact)) {
                    continue
                }

                passedArtifacts.add(artifact)

                if (artifact.file != null && artifact.file.name.endsWith('.jar')) {
                    artifactAction.accept(artifact)
                }
            }
        }
    }

    private void applyAppComponentXml(GPathResult xml, String moduleName, String compGroup, String compVersion,
                                      Project project, List<SkippedDep> skippedDeps, String compId, Set<String> jarNames) {
        GPathResult module = (GPathResult) xml.module.find { it.@name == moduleName }
        if (module.size() > 0) {
            module.artifact.each { art ->
                if (Boolean.valueOf(art.@library.toString()))
                    return

                String dep = "$compGroup:${art.@name}:$compVersion"
                if (art.@classifier != "" || art.@ext != "") {
                    dep += ':'
                    if (art.@classifier != "") {
                        dep += art.@classifier
                    }
                    if (art.@ext != "") {
                        dep += "@${art.@ext}"
                    }
                }
                if (art.@skipIfExists != "") {
                    if (!project.rootProject.allprojects.find { art.@skipIfExists == getModuleName(it) }) {
                        skippedDeps.add(new SkippedDep(new AppComponent(compId, xml), dep, art.@configuration.text()))
                    }
                } else {
                    addDependencyToConfiguration(project, dep, art.@configuration.text())
                }
            }

            addJarNamesFromModule(jarNames, xml, module)
        }

        // Adding appJars from modules that work in all blocks. For example, not all components have
        // portal module, so they don't export appJars for a portal module in the project. But
        // project's global module depends from components' global modules and hence they should be added
        // to appJars.
        def globalModules = xml.module.findAll { it.@blocks.text().contains('*') }
        globalModules.each { GPathResult child ->
            addJarNamesFromModule(jarNames, xml, child)
        }
    }

    private void addDependencyToConfiguration(Project project, String dependency, String conf) {
        project.logger.info("[CubaPlugin] Adding dependency '$dependency' to configuration '$conf'")
        switch (conf) {
            case 'testCompile':
                project.dependencies {
                    testCompile(dependency)
                }
                break
            case 'dbscripts':
                project.configurations {
                    dbscripts
                }
                project.dependencies {
                    dbscripts(dependency)
                }
                break
            case 'webcontent':
                project.configurations {
                    webcontent
                }
                project.dependencies {
                    webcontent(dependency)
                }
                break
            case 'themes':
                project.configurations {
                    themes
                }
                project.dependencies {
                    themes(dependency)
                }
                break
            case 'provided':
                project.configurations {
                    provided
                }
                project.dependencies {
                    provided(dependency)
                }
                break
            default:
                project.dependencies {
                    compile(dependency)
                }
        }
    }

    private String getModuleName(Project project) {
        String moduleName
        if (project.hasProperty('appModuleType') && project['appModuleType'] != null)
            moduleName = project['appModuleType']
        else
            moduleName = project.projectDir.name
        return moduleName
    }

    private void addJarNamesFromModule(Set jarNames, GPathResult xml, GPathResult module) {
        module.artifact.each { art ->
            if (art.@appJar == "true") {
                jarNames.add(art.@name.text())
            }
            module.@dependsOn.text().tokenize(' ,').each { depName ->
                GPathResult depModule = (GPathResult) xml.module.find { it.@name == depName }
                addJarNamesFromModule(jarNames, xml, depModule)
            }
        }
    }

    private static boolean isEnhanced(Project project, File file, File buildDir) {
        def path = file.toPath()
        def classesPath = getEntityClassesDir(project).toPath()
        if (!path.startsWith(classesPath))
            return false

        def enhClassesPath = Paths.get(buildDir.toString(), 'enhanced-classes/main')

        def relPath = classesPath.relativize(path)
        def enhPath = enhClassesPath.resolve(relPath)
        return Files.exists(enhPath)
    }

    private static File getEntityClassesDir(Project project) {
        SourceSet mainSourceSet = project.sourceSets.main

        return mainSourceSet.java.outputDir
    }

    private static class AppComponent {
        String id
        List<String> dependencies = []

        AppComponent(String id, GPathResult xml) {
            this.id = id
            if (xml.@dependsOn) {
                dependencies = xml.@dependsOn.text().tokenize(' ,')
            }
        }

        boolean dependsOn(AppComponent other) {
            return dependencies.contains(other.id)
        }
    }

    private static class SkippedDep implements Comparable<SkippedDep> {
        AppComponent appComponent
        String dep
        String conf

        SkippedDep(AppComponent appComponent, String dep, String conf) {
            this.appComponent = appComponent
            this.dep = dep
            this.conf = conf
        }

        @Override
        int compareTo(SkippedDep other) {
            if (this.appComponent.dependsOn(other.appComponent))
                return 1
            if (other.appComponent.dependsOn(this.appComponent))
                return -1
            return 0
        }
    }
}