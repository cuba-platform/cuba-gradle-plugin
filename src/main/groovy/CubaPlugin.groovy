/*
 * Copyright (c) 2008-2013 Haulmont. All rights reserved.
 * Use is subject to license terms, see http://www.cuba-platform.com/license for details.
 */
import org.gradle.api.Plugin
import org.gradle.api.Project
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

    def CUBA_COPYRIGHT = '''Copyright (c) 2008-$today.year Haulmont. All rights reserved.
Use is subject to license terms, see http://www.cuba-platform.com/license for details.'''

    public static final String VERSION_RESOURCE = "cuba-plugin.version"

    @Override
    void apply(Project project) {
        project.logger.info(">>> applying to project $project.name")

        project.group = project.artifactGroup
        project.version = project.artifactVersion + (project.isSnapshot ? '-SNAPSHOT' : '')

        if (!project.hasProperty('tomcatDir')) {
            project.ext.tomcatDir = project.rootDir.absolutePath + '/../tomcat'
        }

        project.repositories {
            project.rootProject.buildscript.repositories.each {
                project.logger.info(">>> using repository $it.name" + (it.hasProperty('url') ? " at $it.url" : ""))
                project.repositories.add(it)
            }
        }

        if (project.hasProperty('install')) { // Check if the Maven plugin has been applied
            project.configurations {
                deployerJars
            }
            project.dependencies {
                deployerJars(group: 'org.apache.maven.wagon', name: 'wagon-http', version: '1.0-beta-2')
            }

            def uploadUrl = project.hasProperty('uploadUrl') ? project.uploadUrl :
                "http://repository.haulmont.com:8587/nexus/content/repositories/${project.isSnapshot ? 'snapshots' : 'releases'}"
            def uploadUser = project.hasProperty('uploadUser') ? project.uploadUser :
                System.getenv('HAULMONT_REPOSITORY_USER')
            def uploadPassword = project.hasProperty('uploadPassword') ? project.uploadPassword :
                System.getenv('HAULMONT_REPOSITORY_PASSWORD')

            project.logger.info(">>> upload repository: $uploadUrl ($uploadUser:$uploadPassword)")

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

        if (project == project.rootProject) {
            applyToRootProject(project)
        } else {
            applyToModuleProject(project)
        }
    }

    private void applyToRootProject(Project project) {
        project.configurations {
            tomcat
            tomcatInit
        }

        project.dependencies {
            tomcat(group: 'org.apache.tomcat', name: 'tomcat', version: '7.0.62', ext: 'zip')
            tomcatInit(group: 'com.haulmont.appservers', name: 'tomcat-init', version: '3.12', ext: 'zip')
        }

        project.task([type: CubaSetupTomcat], 'setupTomcat') {
            tomcatRootDir = project.tomcatDir
        }

        project.task([type: CubaStartTomcat], 'start') {
            tomcatRootDir = project.tomcatDir
        }

        if (System.getProperty('os.name').contains('Windows')) {
            project.task([type: Exec], 'tomcat') {
                workingDir "${project.tomcatDir}/bin"
                commandLine 'cmd'
                args '/C', 'catalina.bat', 'jpda', 'run'
            }
        } else {
            project.task([type: Exec], 'tomcat') {
                workingDir "${project.tomcatDir}/bin"
                commandLine './catalina.sh'
                args 'jpda', 'run'
            }
        }

        project.task([type: CubaStopTomcat], 'stop') {
            tomcatRootDir = project.tomcatDir
        }

        project.task([type: CubaDropTomcat], 'dropTomcat') {
            tomcatRootDir = project.tomcatDir
            listeningPort = '8787'
        }

        if (project.hasProperty('idea')) {
            project.logger.info ">>> configuring IDEA project"
            project.idea.project.ipr {
                withXml { provider ->
                    def node = provider.node.component.find { it.@name == 'ProjectRootManager' }
                    node.@languageLevel = 'JDK_1_8'
                    node.@'project-jdk-name' = '1.8'

                    node = provider.node.component.find { it.@name == 'CopyrightManager' }
                    node.@default = 'cuba'
                    node = node.appendNode('copyright')
                    if (!project.hasProperty('copyright'))
                        node.appendNode('option', [name: 'notice', value: CUBA_COPYRIGHT])
                    else
                        node.appendNode('option', [name: 'notice', value: project.copyright])

                    node.appendNode('option', [name: 'keyword', value: 'Copyright'])
                    node.appendNode('option', [name: 'allowReplaceKeyword', value: ''])
                    node.appendNode('option', [name: 'myName', value: 'cuba'])
                    node.appendNode('option', [name: 'myLocal', value: 'true'])

                    if (project.hasProperty('vcs'))
                        provider.node.component.find { it.@name == 'VcsDirectoryMappings' }.mapping.@vcs = project.vcs //'svn'

                    provider.node.component.find { it.@name == 'Encoding' }.@defaultCharsetForPropertiesFiles = 'UTF-8'
                }
            }
            project.idea.workspace.iws.withXml { provider ->
                def runManagerNode = provider.asNode().component.find { it.@name == 'RunManager' }
                def listNode = runManagerNode.list.find { it }
                if (listNode.@size == '0') {
                    project.logger.info(">>> Creating remote configuration ")
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
                    project.logger.info(">>> Configure ignored files")
                    changeListManagerNode.appendNode('ignored', [mask: '*.ipr'])
                    changeListManagerNode.appendNode('ignored', [mask: '*.iml'])
                    changeListManagerNode.appendNode('ignored', [mask: '*.iws'])
                }
            }
        }

        if (project.hasProperty('eclipse')) {
            project.logger.info ">>> configuring Eclipse project"
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
                    entry.@path = '/' + projectName;
                    entry.@exported = 'true'

                    classpath.children().remove(entry);
                    classpath.children().add(0, entry)
                }
            }
        }
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
            project.logger.info(">>> set web resources timestamp for project " + project.name)

            project.ext.set('webResourcesTs', resourceBuildTimeStamp)

            project.task('bindVaadinThemes') << {
                def vaadinLib = project.configurations.compile.resolvedConfiguration.resolvedArtifacts.find {
                    it.name.equals('vaadin-server')
                }
                // add default vaadin-themes dependency
                if (vaadinLib) {
                    def dependency = vaadinLib.moduleVersion.id
                    project.logger.info(">>> add default themes dependency on com.vaadin:vaadin-themes:${dependency.version}")

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
            project.logger.info ">>> configuring IDEA module $project.name"

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
            project.logger.info ">>> configuring Eclipse module $project.name"

            project.eclipse.classpath {
                plusConfigurations += project.configurations.provided
                file.whenMerged { classpath ->
                    classpath.entries.removeAll { entry ->
                        entry.path.contains('build/enhanced-classes')
                    }
                }
            }

            if (project.name.endsWith('-global')) {
                project.eclipse.classpath.file.withXml { provider ->
                    def root = provider.asNode()

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