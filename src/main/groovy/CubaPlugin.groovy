/*
 * Copyright (c) 2011 Haulmont Technology Ltd. All Rights Reserved.
 * Haulmont Technology proprietary and confidential.
 * Use is subject to license terms.
 */

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.*
import org.gradle.api.tasks.bundling.Zip

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class CubaPlugin implements Plugin<Project> {

    def HAULMONT_COPYRIGHT = '''Copyright (c) $today.year Haulmont Technology Ltd. All Rights Reserved.
Haulmont Technology proprietary and confidential.
Use is subject to license terms.'''

    public static final String VERSION_RESOURCE = "cuba-plugin.version"

    @Override
    void apply(Project project) {
        project.logger.info(">>> applying to project $project.name")

        project.group = project.artifactGroup
        project.version = project.artifactVersion + (project.isSnapshot ? '-SNAPSHOT' : '')

        if (!project.hasProperty('tomcatDir'))
            project.ext.tomcatDir = project.rootDir.absolutePath + '/../tomcat'


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

        if (project == project.rootProject)
            applyToRootProject(project)
        else
            applyToModuleProject(project)
    }

    private void applyToRootProject(Project project) {
        project.configurations {
            tomcat
        }

        project.dependencies {
            tomcat(group: 'com.haulmont.thirdparty', name: 'apache-tomcat', version: '7.0.27', ext: 'zip')
            tomcat(group: 'com.haulmont.appservers', name: 'tomcat-init', version: '3.5', ext: 'zip')
        }

        project.task([type: CubaSetupTomcat], 'setupTomcat') {
            tomcatRootDir = project.tomcatDir
        }

        project.task([type: CubaStartTomcat], 'start') {
            tomcatRootDir = project.tomcatDir
        }

        project.task([type: CubaStopTomcat], 'stop') {
            tomcatRootDir = project.tomcatDir
        }

        project.task([type: CubaDropTomcat], 'dropTomcat') {
            tomcatRootDir = project.tomcatDir
            listeningPort = '8787'
        }

        if (project.idea) {
            project.logger.info ">>> configuring IDEA project"
            project.idea.project.ipr {
                withXml { provider ->
                    def node = provider.node.component.find { it.@name == 'ProjectRootManager' }
                    node.@languageLevel = 'JDK_1_7'
                    node.@'project-jdk-name' = '1.7'

                    node = provider.node.component.find { it.@name == 'CopyrightManager' }
                    node.@default = 'cuba'
                    node = node.appendNode('copyright')
                    if (!project.hasProperty('copyright'))
                        node.appendNode('option', [name: 'notice', value: HAULMONT_COPYRIGHT])
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
        }
    }

    private void applyToModuleProject(Project project) {
        project.sourceCompatibility = '1.7'
        project.targetCompatibility = '1.7'

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

        // Ensure there will be no duplicates in jars
        project.jar {
            exclude { details -> !details.isDirectory() && isEnhanced(details.file, project.buildDir) }
        }

        if (project.name.endsWith('-core')) {
            project.task([type: CubaDbScriptsAssembling], 'assembleDbScripts')

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

        if (project.idea) {
            project.logger.info ">>> configuring IDEA module $project.name"
            project.idea.module.scopes += [PROVIDED: [plus: [project.configurations.provided, project.configurations.jdbc], minus: []]]
            project.idea.module.inheritOutputDirs = true

            // Enhanced classes library entry must go before source folder
            project.idea.module.iml.withXml { provider ->
                Node rootNode = provider.node.component.find { it.@name == 'NewModuleRootManager' }
                Node enhNode = rootNode.children().find {
                    it.name() == 'orderEntry' && it.@type == 'module-library' &&
                        it.library.CLASSES.root.@url.contains('file://$MODULE_DIR$/build/enhanced-classes/main') // it.library.CLASSES.root.@url is a List here
                }
                if (enhNode) {
                    int srcIdx = rootNode.children().findIndexOf { it.name() == 'orderEntry' && it.@type == 'sourceFolder' }
                    rootNode.children().remove(enhNode)
                    rootNode.children().add(srcIdx, enhNode)
                }
            }
        }
    }

    protected isEnhanced(File file, File buildDir) {
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

class CubaDbScriptsAssembling extends DefaultTask {

    def moduleAlias

    CubaDbScriptsAssembling() {
        setDescription('Gathers database scripts from module and its dependencies')
    }

    @OutputDirectory
    def File getOutputDirectory() {
        return project.file("${project.buildDir}/db")
    }

    @InputFiles @SkipWhenEmpty @Optional
    def FileCollection getSourceFiles() {
        return project.fileTree(new File(project.projectDir, 'db'), {
            exclude '**/.*'
        })
    }

    @TaskAction
    def assemble() {
        if (project.configurations.getAsMap().dbscripts) {
            project.logger.info '>>> project has dbscripts'
            File dir = new File("${project.buildDir}/db")
            if (dir.exists()) {
                project.logger.info ">>> delete $dir.absolutePath"
                project.delete(dir)
            }
            project.configurations.dbscripts.files.each { dep ->
                project.logger.info ">>> copy db from: $dep.absolutePath"
                project.copy {
                    from project.zipTree(dep.absolutePath)
                    into dir
                }
            }
            File srcDbDir = new File(project.projectDir, 'db')
            project.logger.info ">>> srcDbDir: $srcDbDir.absolutePath"
            if (srcDbDir.exists() && dir.exists()) {
                def moduleDirName = moduleAlias
                if (!moduleDirName) {
                    def lastName = Arrays.asList(dir.list()).sort().last()
                    def num = lastName.substring(0, 2).toInteger()
                    moduleDirName = "${num + 10}-${project.rootProject.name}"
                }
                project.copy {
                    project.logger.info ">>> copy db from: $srcDbDir.absolutePath"
                    from srcDbDir
                    into "${project.buildDir}/db/${moduleDirName}"
                }
            }
        }
    }
}

class CubaWarBuilding extends DefaultTask {

    def appName
    def Closure doAfter
    def webcontentExclude = []
    def dbScriptsExcludes = []
    def tmpWarDir

    CubaWarBuilding() {
        setDescription('Builds WAR distribution')
        tmpWarDir = "${project.buildDir}/tmp/war"
    }

    @TaskAction
    def deploy() {
        project.logger.info(">>> copying libs from configurations.runtime")
        project.copy {
            from project.configurations.runtime
            from project.libsDir
            into "${tmpWarDir}/WEB-INF/lib"
            include { details ->
                def name = details.file.name
                return !(name.endsWith('-sources.jar'))
            }
        }

        if (project.configurations.getAsMap().dbscripts) {
            project.logger.info(">>> copying dbscripts from ${project.buildDir}/db to ${tmpWarDir}/WEB-INF/db")
            project.copy {
                from "${project.buildDir}/db"
                into "${tmpWarDir}/WEB-INF/db"
                excludes = dbScriptsExcludes
            }
        }

        if (project.configurations.getAsMap().webcontent) {
            def excludePatterns = ['**/web.xml'] + webcontentExclude
            project.configurations.webcontent.files.each { dep ->
                project.logger.info(">>> copying webcontent from $dep.absolutePath to ${tmpWarDir}")
                project.copy {
                    from project.zipTree(dep.absolutePath)
                    into tmpWarDir
                    excludes = excludePatterns
                    includeEmptyDirs = false
                }
            }
            project.logger.info(">>> copying webcontent from ${project.buildDir}/web to ${tmpWarDir}")
            project.copy {
                from "${project.buildDir}/web"
                into tmpWarDir
            }
        }

        project.logger.info(">>> copying from web to ${tmpWarDir}")
        project.copy {
            from 'web'
            into tmpWarDir
        }

        if (doAfter) {
            project.logger.info(">>> calling doAfter")
            doAfter.call()
        }

        project.logger.info(">>> touch ${tmpWarDir}/WEB-INF/web.xml")
        File webXml = new File("${tmpWarDir}/WEB-INF/web.xml")
        webXml.setLastModified(new Date().getTime())

        ant.jar(destfile: "${project.buildDir}/distributions/${appName}.war", basedir: tmpWarDir)

        project.delete(tmpWarDir)
    }
}

class CubaSetupTomcat extends DefaultTask {

    def tomcatRootDir = project.tomcatDir

    CubaSetupTomcat() {
        setDescription('Sets up local Tomcat')
        setGroup('Development server')
    }

    @TaskAction
    def setup() {
        project.configurations.tomcat.files.each { dep ->
            project.copy {
                from project.zipTree(dep.absolutePath)
                into tomcatRootDir
            }
        }
        ant.chmod(osfamily: 'unix', perm: 'a+x') {
            fileset(dir: "${tomcatRootDir}/bin", includes: '*.sh')
        }
    }
}

class CubaStartTomcat extends DefaultTask {

    def tomcatRootDir = project.tomcatDir

    CubaStartTomcat() {
        setDescription('Starts local Tomcat')
        setGroup('Development server')
    }

    @TaskAction
    def deploy() {
        def binDir = "${tomcatRootDir}/bin"
        project.logger.info ">>> starting $tomcatRootDir"
        ant.exec(osfamily: 'windows', dir: "${binDir}", executable: 'cmd.exe', spawn: true) {
            env(key: 'NOPAUSE', value: true)
            arg(line: '/c start callAndExit.bat debug.bat')
        }
        ant.exec(osfamily: 'unix', dir: "${binDir}", executable: '/bin/sh') {
            arg(line: 'debug.sh')
        }
    }
}

class CubaStopTomcat extends DefaultTask {

    def tomcatRootDir = project.tomcatDir

    CubaStopTomcat() {
        setDescription('Stops local Tomcat')
        setGroup('Development server')
    }

    @TaskAction
    def deploy() {
        def binDir = "${tomcatRootDir}/bin"
        project.logger.info ">>> stopping $tomcatRootDir"
        ant.exec(osfamily: 'windows', dir: "${binDir}", executable: 'cmd.exe', spawn: true) {
            env(key: 'NOPAUSE', value: true)
            arg(line: '/c start callAndExit.bat shutdown.bat')
        }
        ant.exec(osfamily: 'unix', dir: "${binDir}", executable: '/bin/sh') {
            arg(line: 'shutdown.sh')
        }
    }
}

class CubaDropTomcat extends DefaultTask {

    def tomcatRootDir = project.tomcatDir
    def listeningPort = '8787'

    CubaDropTomcat() {
        setDescription('Deletes local Tomcat')
        setGroup('Development server')
    }

    @TaskAction
    def deploy() {
        File dir = new File(tomcatRootDir)
        if (dir.exists()) {
            project.logger.info ">>> deleting $dir"
            // stop
            def binDir = "${tomcatRootDir}/bin"
            ant.exec(osfamily: 'windows', dir: "${binDir}", executable: 'cmd.exe', spawn: true) {
                env(key: 'NOPAUSE', value: true)
                arg(line: '/c start callAndExit.bat shutdown.bat')
            }
            ant.exec(osfamily: 'unix', dir: "${binDir}", executable: '/bin/sh') {
                arg(line: 'shutdown.sh')
            }
            // wait and delete
            ant.waitfor(maxwait: 6, maxwaitunit: 'second', checkevery: 2, checkeveryunit: 'second') {
                not {
                    socket(server: 'localhost', port: listeningPort)
                }
            }
            if (listeningPort) {
                // kill to be sure
                ant.exec(osfamily: 'unix', dir: "${binDir}", executable: '/bin/sh') {
                    arg(line: "kill_by_port.sh $listeningPort")
                }
            }
            project.delete(dir)
        }
    }
}
