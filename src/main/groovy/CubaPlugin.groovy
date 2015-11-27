/*
 * Copyright (c) 2008-2013 Haulmont. All rights reserved.
 * Use is subject to license terms, see http://www.cuba-platform.com/license for details.
 */
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.compile.JavaCompile

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.text.SimpleDateFormat

/**
 * @author krivopustov
 * @version $Id$
 */
class CubaPlugin implements Plugin<Project> {

    public static final String VERSION_RESOURCE = "cuba-plugin.version"

    @Override
    void apply(Project project) {
        project.logger.info("[CubaPlugin] applying to project $project.name")

        project.repositories {
            project.rootProject.buildscript.repositories.each {
                project.logger.info("[CubaPlugin] using repository $it.name" + (it.hasProperty('url') ? " at $it.url" : ""))
                project.repositories.add(it)
            }
        }

        if (project == project.rootProject) {
            project.extensions.create("cuba", CubaPluginExtension, project)
            applyToRootProject(project)
        } else {
            applyToModuleProject(project)
        }

        project.afterEvaluate {
            doAfterEvaluateForAnyProject(project)
            if (project == project.rootProject) {
                doAfterEvaluateForRootProject(project)
            }
        }
    }

    private void doAfterEvaluateForAnyProject(Project project) {
        project.group = project.cuba.artifact.group
        project.version = project.cuba.artifact.version + (project.cuba.artifact.isSnapshot ? '-SNAPSHOT' : '')

        if (project.hasProperty('install')) { // Check if the Maven plugin has been applied
            project.configurations {
                deployerJars
            }
            project.dependencies {
                deployerJars(group: 'org.apache.maven.wagon', name: 'wagon-http', version: '1.0-beta-2')
            }

            def uploadUrl = project.cuba.uploadRepository.url ? project.cuba.uploadRepository.url
                    : "http://repository.haulmont.com:8587/nexus/content/repositories/${project.cuba.artifact.isSnapshot ? 'snapshots' : 'releases'}"
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

    private void applyToRootProject(Project project) {
        project.configurations {
            tomcat
            tomcatInit
        }

        project.dependencies {
            tomcat(group: 'org.apache.tomcat', name: 'tomcat', version: '8.0.26', ext: 'zip')
            tomcatInit(group: 'com.haulmont.appservers', name: 'tomcat-init', version: '4.0.4', ext: 'zip')
        }

        project.task([type: CubaSetupTomcat], 'setupTomcat')
        project.task([type: CubaStartTomcat], 'start')
        project.task([type: Exec], 'tomcat')
        project.task([type: CubaStopTomcat], 'stop')
        project.task([type: CubaDropTomcat], 'dropTomcat')
    }

    private void doAfterEvaluateForRootProject(Project project) {
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
            project.logger.info "[CubaPlugin] configuring IDEA project"
            project.idea.project.ipr {
                withXml { provider ->
                    def node = provider.node.component.find { it.@name == 'ProjectRootManager' }
                    node.@languageLevel = 'JDK_1_8'
                    node.@'project-jdk-name' = '1.8'

                    node = provider.node.component.find { it.@name == 'CopyrightManager' }
                    node.@default = 'cuba'
                    node = node.appendNode('copyright')
                    node.appendNode('option', [name: 'notice', value: project.cuba.ide.copyright])

                    node.appendNode('option', [name: 'keyword', value: 'Copyright'])
                    node.appendNode('option', [name: 'allowReplaceKeyword', value: ''])
                    node.appendNode('option', [name: 'myName', value: 'cuba'])
                    node.appendNode('option', [name: 'myLocal', value: 'true'])

                    if (project.cuba.ide.vcs)
                        provider.node.component.find { it.@name == 'VcsDirectoryMappings' }.mapping.@vcs = project.cuba.ide.vcs //'svn'

                    provider.node.component.find { it.@name == 'Encoding' }.@defaultCharsetForPropertiesFiles = 'UTF-8'
                }
            }
            project.idea.workspace.iws.withXml { provider ->
                def runManagerNode = provider.asNode().component.find { it.@name == 'RunManager' }
                def listNode = runManagerNode.list.find { it }
                if (listNode.@size == '0') {
                    project.logger.info("[CubaPlugin] Creating remote configuration ")
                    def confNode = runManagerNode.appendNode('configuration', [name: 'localhost:8787', type: 'Remote', factoryName: 'Remote'])
                    confNode.appendNode('option', [name: 'USE_SOCKET_TRANSPORT', value: 'true'])
                    confNode.appendNode('option', [name: 'SERVER_MODE', value: 'false'])
                    confNode.appendNode('option', [name: 'SHMEM_ADDRESS', value: 'javadebug'])
                    confNode.appendNode('option', [name: 'HOST', value: 'localhost'])
                    confNode.appendNode('option', [name: 'PORT', value: '8787'])
                    confNode.appendNode('method')
                    listNode.appendNode('item', [index: '0', class: 'java.lang.String', itemvalue: 'Remote.localhost:8787'])
                    listNode.@size = 1;
                    runManagerNode.@selected = 'Remote.localhost:8787'
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
            }
        }

        if (project.hasProperty('eclipse')) {
            project.logger.info "[CubaPlugin] configuring Eclipse project"
            project.eclipse.project.file.withXml { provider ->
                def projectDescription = provider.asNode()

                def filteredResources = projectDescription.children().find { it.name() == 'filteredResources'}
                if (filteredResources != null) {
                    filteredResources.children().clear()
                } else {
                    filteredResources = projectDescription.appendNode('filteredResources')
                }
                filteredResources.append(nestedProjectsFilter())
            }
            project.eclipse.classpath.file.withXml{ provider ->
                def classpath = provider.asNode();
                for (String projectName : project.childProjects.keySet()) {
                    Node entry = classpath.appendNode('classpathentry')
                    entry.@kind = 'src'
                    if (projectName.startsWith("app")) {
                        projectName = projectName.replace("app", project.name);
                    }
                    entry.@path = '/' + projectName;
                    entry.@exported = 'true'

                    classpath.children().remove(entry);
                    classpath.children().add(0, entry)
                }
            }
        }

        defineTasksExecutionOrder(project)
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

    /**
     * Finds tasks by type in the given project and in its subprojects
     */
    private Collection<Task> findAllTasksByType(Project project, Class type) {
        def result = []
        project.getAllTasks(true).each {subProject, tasks ->
            result.addAll(tasks.findAll {type.isAssignableFrom(it.class)})
        }
        return result
    }

    private static Node nestedProjectsFilter() {
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
        }

        project.assemble.doFirst { acceptLicense(project) }

        project.jar {
            // Ensure there will be no duplicates in jars
            exclude { details -> !details.isDirectory() && isEnhanced(details.file, project.buildDir) }
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

        if (project.name.endsWith('-core')) {
            File dbDir = new File(project.projectDir, "db");
            project.task([type: CubaDbScriptsAssembling], 'assembleDbScripts')
            project.assemble.dependsOn(project.assembleDbScripts)

            if (dbDir.exists() && dbDir.isDirectory() && dbDir.list().length > 0) {
                project.task([type: Zip, dependsOn: 'assembleDbScripts'], 'dbScriptsArchive') {
                    from "${project.buildDir}/db"
                    exclude '**/*.bat'
                    exclude '**/*.sh'
                    classifier = 'db'
                }

                project.artifacts {
                    archives project.dbScriptsArchive
                }
            }
        }

        // set module language level to 1.6 if it is -toolkit module
        if (project.name.endsWith('-toolkit')) {
            project.sourceCompatibility = '1.6'
            project.targetCompatibility = '1.6'
        }

        // add web resources version for correct caching
        if (project.name.endsWith('-web')) {
            project.configurations {
                themes
            }

            def resourceBuildTimeStamp = new SimpleDateFormat('yyyy_MM_dd_HH_mm').format(new Date())
            project.logger.info("[CubaPlugin] set web resources timestamp for project " + project.name)

            project.ext.set('webResourcesTs', resourceBuildTimeStamp)

            project.task('bindVaadinThemes') << {
                def vaadinLib = project.configurations.compile.resolvedConfiguration.resolvedArtifacts.find {
                    it.name.equals('vaadin-server')
                }
                // add default vaadin-themes dependency
                if (vaadinLib) {
                    def dependency = vaadinLib.moduleVersion.id
                    project.logger.info("[CubaPlugin] add default themes dependency on com.vaadin:vaadin-themes:${dependency.version}")

                    project.dependencies {
                        themes(group: dependency.group, name: 'vaadin-themes', version: dependency.version)
                    }
                }
            }

            if (project.tasks.findByName('idea')) {
                project.tasks.idea.dependsOn(project.tasks.bindVaadinThemes)
            }
        }

        if (project.hasProperty('idea') && project.hasProperty('ideaModule')) {
            project.ideaModule.doFirst { acceptLicense(project) }
            project.logger.info "[CubaPlugin] configuring IDEA module $project.name"

            List<Configuration> providedConfs = new ArrayList<>()
            providedConfs.add(project.configurations.provided)
            providedConfs.add(project.configurations.jdbc)

            if (project.configurations.findByName('themes')) {
                providedConfs.add(project.configurations.themes)
            }

            project.idea.module.scopes += [PROVIDED: [plus: providedConfs, minus: []]]

            project.idea.module.inheritOutputDirs = true

            // Enhanced classes library entry must go before source folder
            project.idea.module.iml.withXml { provider ->
                Node rootNode = provider.node.component.find { it.@name == 'NewModuleRootManager' }

                Node enhNode = (Node) rootNode.children().find {
                    it instanceof Node && it.name() == 'orderEntry' && it.@type == 'module-library' &&
                        it.library.CLASSES.root.@url.contains('file://$MODULE_DIR$/build/enhanced-classes/main') // it.library.CLASSES.root.@url is a List here
                }

                // set module language level to 1.6 if it is -toolkit module
                if (project.name.endsWith('-toolkit')) {
                    rootNode.@LANGUAGE_LEVEL = 'JDK_1_6'
                }

                int srcIdx = rootNode.children().findIndexOf {
                    it instanceof Node && it.name() == 'orderEntry' && it.@type == 'sourceFolder'
                }
                if (!enhNode && project.name.endsWith('-global')) {
                    enhNode = rootNode.appendNode('orderEntry', [type: 'module-library', exported: '', scope: 'RUNTIME'])
                    Node libNode = enhNode.appendNode('library')
                    libNode.appendNode('CLASSES').appendNode('root', [url: 'file://$MODULE_DIR$/build/enhanced-classes/main'])
                    libNode.appendNode('JAVADOC')
                    libNode.appendNode('SOURCES')
                }
                rootNode.children().remove(enhNode)
                rootNode.children().add(srcIdx, enhNode)
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

    private static void acceptLicense(Project project) {
        if (!project.rootProject.hasProperty('licenseAgreementAccepted')) {
            boolean saved = false
            Properties props = new Properties()
            File file = new File("${System.getProperty('user.home')}/.haulmont/license.properties")
            if (file.exists()) {
                props.load(file.newDataInputStream())
                saved = Boolean.parseBoolean(props.getProperty("accepted"))
            }
            if (!saved) {
                def license = '''
================================================================
       Do you accept the terms of CUBA license agreement
      published at http://www.cuba-platform.com/license ?
                     (Y - yes, N - no)
================================================================
'''
                project.ant.input(message: license, addproperty: 'licenseAgreement')

                if (project.ant.licenseAgreement.toLowerCase() != 'y' && project.ant.licenseAgreement.toLowerCase() != 'yes') {
                    throw new IllegalStateException("=========== License agreement is not accepted ===========")
                }

                file.parentFile.mkdirs()
                props.setProperty("accepted", "true")
                props.store(file.newDataOutputStream(), "")
            }
            project.rootProject.ext.licenseAgreementAccepted = true
        }
    }

    protected static isEnhanced(File file, File buildDir) {
        Path path = file.toPath()
        Path classesPath = Paths.get(buildDir.toString(), 'classes/main')
        if (!path.startsWith(classesPath))
            return false

        Path enhClassesPath = Paths.get(buildDir.toString(), 'enhanced-classes/main')

        Path relPath = classesPath.relativize(path)
        Path enhPath = enhClassesPath.resolve(relPath)
        return Files.exists(enhPath)
    }

    public static String getArtifactDefinition() {
        return new InputStreamReader(CubaPlugin.class.getResourceAsStream(VERSION_RESOURCE)).text
    }
}