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

import org.gradle.api.GradleException
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
import java.util.jar.Manifest

/**
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

        project.afterEvaluate { p ->
            doAfterEvaluateForAnyProject(p)
            if (p == project.rootProject) {
                doAfterEvaluateForRootProject(p)
            } else {
                doAfterEvaluateForModuleProject(p)
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

            project.logger.info("[CubaPlugin] upload repository: $uploadUrl ($uploadUser:$uploadPassword)")

            if (uploadUrl == null) {
                project.getTasks().getByName('uploadArchives').doFirst {
                    throw new GradleException("Please specify upload repository using cuba.uploadRepository.url property " +
                            "or HAULMONT_REPOSITORY_UPLOAD_URL environment variable!")
                }
            }

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
        }

        project.dependencies {
            tomcat(group: 'org.apache.tomcat', name: 'tomcat', version: '8.0.35', ext: 'zip')
        }

        project.task([type: CubaSetupTomcat], 'setupTomcat')
        project.task([type: CubaStartTomcat], 'start')
        project.task([type: Exec], 'tomcat')
        project.task([type: CubaStopTomcat], 'stop')
        project.task([type: CubaDropTomcat], 'dropTomcat')
        project.task([type: CubaZipProject], 'zipProject')
    }

    private def doAfterEvaluateForModuleProject(Project project) {
        addDependenciesFromProjectInfos(project)
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
                    node.@default = 'default'

                    node = node.appendNode('copyright')
                    node.appendNode('option', [name: 'notice', value: project.cuba.ide.copyright])

                    node.appendNode('option', [name: 'keyword', value: 'Copyright'])
                    node.appendNode('option', [name: 'allowReplaceKeyword', value: ''])
                    node.appendNode('option', [name: 'myName', value: 'default'])
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
            def cleanTask = project.getTasksByName("clean", false).iterator().next()
            cleanTask.delete = ['build/libs', 'build/tmp']
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
        }

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
            def resourceBuildTimeStamp = new SimpleDateFormat('yyyy_MM_dd_HH_mm').format(new Date())
            project.logger.info("[CubaPlugin] set web resources timestamp for project " + project.name)

            project.ext.set('webResourcesTs', resourceBuildTimeStamp)
        }

        if (project.hasProperty('idea') && project.hasProperty('ideaModule')) {
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

    private void addDependenciesFromProjectInfos(Project project) {
        def moduleName = getModuleName(project)

        project.logger.info("[CubaPlugin] Setting up dependencies for module $moduleName")

        def jarNames = new HashSet()
        List<SkippedDep> skippedDeps = []

        Enumeration<URL> manifests = getClass().getClassLoader().getResources("META-INF/MANIFEST.MF")
        while (manifests.hasMoreElements()) {
            Manifest manifest = new Manifest(manifests.nextElement().openStream());

            def compId = manifest.mainAttributes.getValue('App-Component-Id')
            def compVersion = manifest.mainAttributes.getValue('App-Component-Version')

            if (compId && compVersion) {
                def compDescrPath = compId.replace('.', '/') + '/app-component.xml'
                def compGroup = compId

                def url = CubaPlugin.class.getResource(compDescrPath)
                if (url) {
                    project.logger.info("[CubaPlugin] Found app-component info in $url")
                    def xml = new XmlSlurper().parseText(url.openStream().getText('UTF-8'))
                    def module = xml.module.find { it.@name == moduleName }
                    if (module.size() > 0) {
                        module.artifact.each { art ->
                            def dep = "$compGroup:${art.@name}:$compVersion"
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
                                    skippedDeps.add(new SkippedDep(new AppComponent(compId, xml), dep, art.@configuration))
                                }
                            } else {
                                addDependencyToConfiguration(project, dep, art.@configuration)
                            }
                        }

                        addJarNamesFromModule(jarNames, xml, module)
                    }

                    // Adding appJars from modules that work in all blocks. For example, not all components have
                    // portal module, so they don't export appJars for a portal module in the project. But
                    // project's global module depends from components' global modules and hence they should be added
                    // to appJars.
                    def globalModules = xml.module.findAll { it.@blocks.text().contains('*') }
                    globalModules.each {
                        addJarNamesFromModule(jarNames, xml, it)
                    }
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

    private void addDependencyToConfiguration(Project project, String dep, def conf) {
        project.logger.info("[CubaPlugin] Adding dependency '$dep' to configuration '$conf'")
        switch (conf) {
            case 'testCompile':
                project.dependencies {
                    testCompile(dep)
                }
                break;
            case 'dbscripts':
                project.configurations {
                    dbscripts
                }
                project.dependencies {
                    dbscripts(dep)
                }
                break;
            case 'webcontent':
                project.configurations {
                    webcontent
                }
                project.dependencies {
                    webcontent(dep)
                }
                break;
            case 'themes':
                project.configurations {
                    themes
                }
                project.dependencies {
                    themes(dep)
                }
                break;
            case 'provided':
                project.configurations {
                    provided
                }
                project.dependencies {
                    provided(dep)
                }
                break;
            default:
                project.dependencies {
                    compile(dep)
                }
        }
    }

    private Object getModuleName(Project project) {
        def moduleName
        if (project.hasProperty('appModuleType'))
            moduleName = project['appModuleType']
        else
            moduleName = project.projectDir.name
        moduleName
    }

    private def addJarNamesFromModule(Set jarNames, def xml, def module) {
        module.artifact.each { art ->
            if (art.@appJar == "true") {
                jarNames.add(art.@name.text())
            }
            module.@dependsOn.text().tokenize(' ,').each { depName ->
                def depModule = xml.module.find { it.@name == depName }
                addJarNamesFromModule(jarNames, xml, depModule)
            }
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
        def stream = CubaPlugin.class.getResourceAsStream(VERSION_RESOURCE)
        if (!stream) {
            throw new IllegalStateException("Resource $VERSION_RESOURCE not found. If you use Gradle daemon, try to restart it")
        }
        return new InputStreamReader(stream).text
    }

    private static class AppComponent {
        String id
        List<String> dependencies = []

        AppComponent(String id, def xml) {
            this.id = id
            if (xml.@dependsOn) {
                dependencies = xml.@dependsOn.text().tokenize(' ,')
            }
        }

        public boolean dependsOn(AppComponent other) {
            return dependencies.contains(other.id)
        }
    }

    private static class SkippedDep implements Comparable<SkippedDep> {
        AppComponent appComponent
        String dep
        def conf

        SkippedDep(AppComponent appComponent, String dep, def conf) {
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