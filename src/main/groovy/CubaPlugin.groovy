/*
 * Copyright (c) 2011 Haulmont Technology Ltd. All Rights Reserved.
 * Haulmont Technology proprietary and confidential.
 * Use is subject to license terms.
 */

import com.yahoo.platform.yui.compressor.CssCompressor
import org.apache.commons.io.FilenameUtils
import org.apache.commons.io.FileUtils
import org.carrot2.labs.smartsprites.SmartSpritesParameters
import org.carrot2.labs.smartsprites.SpriteBuilder
import org.carrot2.labs.smartsprites.message.MessageLog
import org.carrot2.labs.smartsprites.message.PrintStreamMessageSink
import org.gradle.api.internal.project.DefaultAntBuilder
import org.kohsuke.args4j.CmdLineParser

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.internal.file.collections.SimpleFileCollection
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.Zip
import groovyx.gpars.GParsPool

class CubaPlugin implements Plugin<Project> {

    def HAULMONT_COPYRIGHT = '''Copyright (c) $today.year Haulmont Technology Ltd. All Rights Reserved.
Haulmont Technology proprietary and confidential.
Use is subject to license terms.'''

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
            tomcat(group: 'com.haulmont.appservers', name: 'tomcat-init', version: '3.4', ext: 'zip')
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
                    node.@default = 'Haulmont'
                    node = node.appendNode('copyright')
                    if (!project.hasProperty('copyright'))
                        node.appendNode('option', [name: 'notice', value: HAULMONT_COPYRIGHT])
                    else
                        node.appendNode('option', [name: 'notice', value: project.copyright])

                    node.appendNode('option', [name: 'keyword', value: 'Copyright'])
                    node.appendNode('option', [name: 'allowReplaceKeyword', value: ''])
                    node.appendNode('option', [name: 'myName', value: 'Haulmont'])
                    node.appendNode('option', [name: 'myLocal', value: 'true'])

//                    provider.node.component.find { it.@name == 'VcsDirectoryMappings' }.mapping.@vcs = 'svn'

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

    def isEnhanced(File file, File buildDir) {
        Path path = file.toPath()
        Path classesPath = Paths.get(buildDir.toString(), 'classes/main')
        if (!path.startsWith(classesPath))
            return false

        Path enhClassesPath = Paths.get(buildDir.toString(), 'enhanced-classes/main')

        Path relPath = classesPath.relativize(path)
        Path enhPath = enhClassesPath.resolve(relPath)
        return Files.exists(enhPath)
    }
}

class CubaDbScriptsAssembling extends DefaultTask {

    def moduleAlias

    CubaDbScriptsAssembling() {
        setDescription('Gathers database scripts from module and its dependencies')
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

class CubaEnhancing extends DefaultTask {

    def persistenceXml
    def metadataXml

    CubaEnhancing() {
        setDescription('Enhances classes')
    }

    @TaskAction
    def enhance() {
        if (persistenceXml) {
            File f = new File(persistenceXml)
            if (f.exists()) {
                def persistence = new XmlParser().parse(f)
                def pu = persistence.'persistence-unit'[0]
                def properties = pu.properties[0]
                if (!properties)
                    properties = pu.appendNode('properties')
                def prop = properties.find { it.@name == 'openjpa.DetachState' }
                if (!prop)
                    properties.appendNode('property', [name: 'openjpa.DetachState', value: 'loaded(DetachedStateField=true, DetachedStateManager=true)'])

                File tmpDir = new File(project.buildDir, 'tmp')
                tmpDir.mkdirs()
                File tmpFile = new File(tmpDir, 'persistence.xml')
                new XmlNodePrinter(new PrintWriter(new FileWriter(tmpFile))).print(persistence)

                project.javaexec {
                    main = 'org.apache.openjpa.enhance.PCEnhancer'
                    classpath(project.sourceSets.main.compileClasspath, project.sourceSets.main.output.classesDir)
                    args('-properties', tmpFile, '-d', "$project.buildDir/enhanced-classes/main")
                }
            }
        }
        if (metadataXml) {
            File f = new File(persistenceXml)
            if (f.exists()) {
                project.javaexec {
                    main = 'CubaTransientEnhancer'
                    classpath(project.sourceSets.main.compileClasspath, project.sourceSets.main.output.classesDir)
                    args(metadataXml, "$project.buildDir/enhanced-classes/main")
                }
            }
        }
    }
}

class CubaDeployment extends DefaultTask {

    def jarNames
    def appName
    def Closure doAfter
    def tomcatRootDir = project.tomcatDir
    def webcontentExclude = []
    def dbScriptsExcludes = []

    CubaDeployment() {
        setDescription('Deploys applications for local usage')
    }

    @TaskAction
    def deploy() {
        project.logger.info(">>> copying from configurations.jdbc to ${tomcatRootDir}/lib")
        project.copy {
            from project.configurations.jdbc
            into "${tomcatRootDir}/lib"
        }
        project.logger.info(">>> copying shared libs from configurations.runtime")
        project.copy {
            from project.configurations.runtime
            into "${tomcatRootDir}/shared/lib"
            include { details ->
                def name = details.file.name
                return !(name.endsWith('-sources.jar')) && (jarNames.find { name.startsWith(it) } == null)
            }
        }
        project.logger.info(">>> copying app libs from configurations.runtime")
        project.copy {
            from project.configurations.runtime
            from project.libsDir
            into "${tomcatRootDir}/webapps/$appName/WEB-INF/lib"
            include { details ->
                def name = details.file.name
                return !(name.endsWith('.zip')) && !(name.endsWith('-tests.jar')) && !(name.endsWith('-sources.jar')) &&
                        (jarNames.find { name.startsWith(it) } != null)
            }
        }

        if (project.configurations.getAsMap().dbscripts) {
            project.logger.info(">>> copying dbscripts from ${project.buildDir}/db to ${tomcatRootDir}/webapps/$appName/WEB-INF/db")
            project.copy {
                from "${project.buildDir}/db"
                into "${tomcatRootDir}/webapps/$appName/WEB-INF/db"
                excludes = dbScriptsExcludes
            }
        }

        if (project.configurations.getAsMap().webcontent) {
            def excludePatterns = ['**/web.xml'] + webcontentExclude
            project.configurations.webcontent.files.each { dep ->
                project.logger.info(">>> copying webcontent from $dep.absolutePath to ${tomcatRootDir}/webapps/$appName")
                project.copy {
                    from project.zipTree(dep.absolutePath)
                    into "${tomcatRootDir}/webapps/$appName"
                    excludes = excludePatterns
                    includeEmptyDirs = false
                }
            }
            project.logger.info(">>> copying webcontent from ${project.buildDir}/web to ${tomcatRootDir}/webapps/$appName")
            project.copy {
                from "${project.buildDir}/web"
                into "${tomcatRootDir}/webapps/$appName"
            }
        }

        project.logger.info(">>> copying from web to ${tomcatRootDir}/webapps/$appName")
        project.copy {
            from 'web'
            into "${tomcatRootDir}/webapps/$appName"
        }

        if (doAfter) {
            project.logger.info(">>> calling doAfter")
            doAfter.call()
        }

        project.logger.info(">>> touch ${tomcatRootDir}/webapps/$appName/WEB-INF/web.xml")
        File webXml = new File("${tomcatRootDir}/webapps/$appName/WEB-INF/web.xml")
        webXml.setLastModified(new Date().getTime())
    }

    def appJars(Object... names) {
        jarNames = names
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

class CubaWebStartCreation extends DefaultTask {

    def jnlpTemplateName = "${project.projectDir}/webstart/template.jnlp"
    def indexFileName
    def baseHost = 'http://localhost:8080/'
    def basePath = "${project.applicationName}-webstart"
    def signJarsAlias = 'signJars'
    def signJarsPassword = 'HaulmontSignJars'
    def signJarsKeystore = "${project.projectDir}/webstart/sign-jars-keystore.jks"
    def applicationSignJars = []
    def jarSignerThreadCount = 4
    def useSignerCache = true

    def threadLocalAnt = new ThreadLocal<AntBuilder>()

    CubaWebStartCreation() {
        setDescription('Creates web start distribution')
    }

    @TaskAction
    def create() {
        File distDir = new File(project.buildDir, "distributions/${basePath}")
        File libDir = new File(distDir, 'lib')
        libDir.mkdirs()

        File signerCacheDir = new File(project.buildDir, "jar-signer-cache")
        if (!signerCacheDir.exists())
            signerCacheDir.mkdir()

        project.logger.info(">>> copying app libs from configurations.runtime to ${libDir}")

        project.copy {
            from project.configurations.runtime
            from project.libsDir
            into libDir
            include { details ->
                def name = details.file.name
                return !(name.endsWith('.zip')) && !(name.endsWith('-tests.jar')) && !(name.endsWith('-sources.jar'))
            }
        }

        project.logger.info(">>> signing jars in ${libDir}")

		Date startStamp = new Date()

        if (useSignerCache && applicationSignJars.empty) {
            if (project.parent) {
                project.parent.subprojects.each { subProject ->
                    subProject.configurations.archives.allArtifacts.each {
                        if (''.equals(it.classifier))
                            applicationSignJars.add(it.name + '-' + subProject.version)
                    }
                }
            }

            project.logger.info(">>> do not cache jars: ${applicationSignJars}")
        }
		
		GParsPool.withPool(jarSignerThreadCount) {
			libDir.listFiles().eachParallel { File jarFile ->
                try {
                    project.logger.info(">>> started sign jar ${jarFile.name} in thread ${Thread.currentThread().id}")
                    doSignFile(jarFile, signerCacheDir)
                    project.logger.info(">>> finished sign jar ${jarFile.name} in thread ${Thread.currentThread().id}")
                } catch (Exception e) {
                    project.logger.error("failed to sign jar file $jarFile.name", e)
                }
			}
		}
		
		Date endStamp = new Date()
		long processTime = endStamp.getTime() - startStamp.getTime()		
		
		project.logger.info(">>> signing time: ${processTime}")

        project.logger.info(">>> creating JNLP file from ${jnlpTemplateName}")

        File jnlpTemplate = new File(jnlpTemplateName)
        def jnlpNode = new XmlParser().parse(jnlpTemplate)

        if (!baseHost.endsWith('/'))
            baseHost += '/'

        jnlpNode.@codebase = baseHost + basePath
        def jnlpName = jnlpNode.@href

        def resourcesNode = jnlpNode.resources[0]

        libDir.listFiles().each {
            resourcesNode.appendNode('jar', [href: "lib/${it.getName()}", download: 'eager'])
        }

        File jnlpFile = new File(distDir, jnlpName)
        new XmlNodePrinter(new PrintWriter(new FileWriter(jnlpFile))).print(jnlpNode)

        if (indexFileName) {
            project.logger.info(">>> copying indes file from ${indexFileName} to ${distDir}")
            project.copy {
                from indexFileName
                into distDir.getAbsolutePath()
            }
        }
    }

    void doSignFile(File jarFile, File signerCacheDir) {
        def cachedJar = new File(signerCacheDir, jarFile.name)
        def libraryName = FilenameUtils.getBaseName(jarFile.name)

        if (useSignerCache && cachedJar.exists() &&
                !libraryName.endsWith('-SNAPSHOT')
                && !applicationSignJars.contains(libraryName)) {
            project.logger.info(">>> use cached jar: ${jarFile}")
            FileUtils.copyFile(cachedJar, jarFile)
        } else {
            project.logger.info(">>> sign: ${jarFile}")

            def sharedAnt
            if (threadLocalAnt.get())
                sharedAnt = threadLocalAnt.get()
            else {
                sharedAnt = new DefaultAntBuilder(project)
                threadLocalAnt.set(sharedAnt)
            }

            sharedAnt.signjar(jar: "${jarFile}", alias: signJarsAlias, keystore: signJarsKeystore,
                              storepass: signJarsPassword, preservelastmodified: "true")

            if (useSignerCache && !libraryName.endsWith('-SNAPSHOT')
                    && !applicationSignJars.contains(libraryName)) {
                project.logger.info(">>> cache jar: ${jarFile}")
                FileUtils.copyFile(jarFile, cachedJar)
            }
        }
    }
}

class CubaWebStartDeployment extends DefaultTask {

    def basePath = "${project.applicationName}-webstart"

    CubaWebStartDeployment() {
        setDescription('Deploys web start distribution into the local Tomcat')
    }

    @TaskAction
    def deploy() {
        File distDir = new File(project.buildDir, "distributions/${basePath}")

        project.logger.info(">>> copying web start distribution from ${distDir} to ${project.tomcatDir}/webapps/$basePath")

        project.copy {
            from distDir
            into "${project.tomcatDir}/webapps/$basePath"
        }
    }
}

class CubaSetupTomcat extends DefaultTask {

    def tomcatRootDir = project.tomcatDir

    CubaSetupTomcat() {
        setDescription('Sets up local Tomcat')
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

class ProjectAllWebToolkit extends DefaultTask {

    String widgetSetsDir
    List widgetSetModules
    String widgetSetClass
    Map compilerArgs

    private def defaultCompilerArgs = [
           '-style' : 'OBF',
           '-localWorkers' : Runtime.getRuntime().availableProcessors(),
           '-logLevel' : 'INFO'
    ]

    private def compilerJvmArgs = new HashSet([
            '-Xmx512m', '-Xss8m', '-XX:MaxPermSize=256m', '-Djava.awt.headless=true'
    ])

    ProjectAllWebToolkit() {
        setDescription('Builds GWT widgetset in project-all')
    }

    @TaskAction
    def buildGwt() {
        if (!widgetSetsDir)
            throw new IllegalStateException('Please specify \'String widgetSetsDir\' for build GWT')

        if (!widgetSetClass)
            throw new IllegalStateException('Please specify \'String widgetSetClass\' for build GWT')

        checkWidgetSetModules()

        File widgetSetsDirectory = new File(this.widgetSetsDir)
        if (!widgetSetsDirectory.exists()) {
            List compilerClassPath = collectClassPathEntries()
            List gwtCompilerArgs = collectCompilerArgs(widgetSetsDirectory.absolutePath)
            List gwtCompilerJvmArgs = collectCompilerJvmArgs()

            project.javaexec {
                main = 'com.google.gwt.dev.Compiler'
                classpath = new SimpleFileCollection(compilerClassPath)
                args = gwtCompilerArgs
                jvmArgs = gwtCompilerJvmArgs
            }

            new File(widgetSetsDirectory, 'WEB-INF').deleteDir()
        } else {
            println "Widgetsets dir exists, skip build GWT"
        }
    }

    protected void checkWidgetSetModules() {
        if (!widgetSetModules || widgetSetModules.isEmpty())
            throw new IllegalStateException('Please specify not empty \'Collection widgetSetModules\' for build GWT')
    }

    def jvmArgs(String... jvmArgs) {
        compilerJvmArgs.addAll(Arrays.asList(jvmArgs))
    }

    protected List collectCompilerJvmArgs() {
        println('JVM Args:')
        println(compilerJvmArgs)

        return new LinkedList(compilerJvmArgs)
    }

    protected List collectCompilerArgs(warPath) {
        List args = []

        args.add('-war')
        args.add(warPath)

        for (def entry : defaultCompilerArgs.entrySet()) {
            args.add(entry.getKey())
            args.add(getCompilerArg(entry.getKey()))
        }

        args.add(widgetSetClass)

        println('GWT Compiler args: ')
        println(args)

        return args
    }

    protected def getCompilerArg(argName) {
        if (compilerArgs && compilerArgs.containsKey(argName))
            return compilerArgs.get(argName)
        else
            return defaultCompilerArgs.get(argName)
    }

    protected List collectClassPathEntries() {
        def compilerClassPath = []
        if (project.configurations.findByName('gwtBuilding')) {
            def gwtBuildingArtifacts = project.configurations.gwtBuilding.resolvedConfiguration.getResolvedArtifacts()

            def validationApiArtifact = gwtBuildingArtifacts.find { a -> a.name == 'validation-api' }
            if (validationApiArtifact) {
                File validationSrcDir = validationApiArtifact.file
                compilerClassPath.add(validationSrcDir)
            }
        }

        def moduleClassesDirs = []
        def moduleSrcDirs = []
        if (widgetSetModules) {
            for (def module : widgetSetModules) {
                moduleSrcDirs.add(new File((File) module.projectDir, 'src'))
                moduleClassesDirs.add(module.sourceSets.main.output.classesDir)
            }
        }

        compilerClassPath.addAll(moduleSrcDirs)
        compilerClassPath.addAll(moduleClassesDirs)

        compilerClassPath.add(project.sourceSets.main.compileClasspath.getAsPath())
        compilerClassPath.add(project.sourceSets.main.output.classesDir)
        return compilerClassPath
    }
}

class CubaWebToolkit extends ProjectAllWebToolkit {

    def inheritedArtifacts

    private def excludes = [ ]

    CubaWebToolkit() {
        setDescription('Builds GWT widgetset')
    }

    def excludeJars(String... artifacts) {
        excludes.addAll(artifacts)
    }

    private static class InheritedArtifact {
        def name
        def jarFile
    }

    boolean excludedArtifact(String name) {
        for (def artifactName : excludes)
            if (name.contains(artifactName))
                return true
        return false
    }

    @Override
    protected void checkWidgetSetModules() {
        // do nothing
    }

    @Override
    protected List collectClassPathEntries() {
        def gwtBuildingArtifacts = []
        def compilerClassPath = []
        if (project.configurations.findByName('gwtBuilding')) {
            gwtBuildingArtifacts = project.configurations.gwtBuilding.resolvedConfiguration.getResolvedArtifacts()
            def validationApiArtifact = gwtBuildingArtifacts.find { a -> a.name == 'validation-api' }
            if (validationApiArtifact) {
                File validationSrcDir = validationApiArtifact.file
                compilerClassPath.add(validationSrcDir)
            }
        }
        def providedArtefacts = project.configurations.provided.resolvedConfiguration.getResolvedArtifacts()

        def mainClasspath = project.sourceSets.main.compileClasspath.findAll { !excludedArtifact(it.name) }

        if (inheritedArtifacts) {
            def inheritedWidgetSets = []
            def inheritedSources = []
            for (def artifactName: inheritedArtifacts) {
                def artifact = providedArtefacts.find { it.name == artifactName }
                if (artifact)
                    inheritedWidgetSets.add(new InheritedArtifact(name: artifactName, jarFile: artifact.file))
                def artifactSource = gwtBuildingArtifacts.find { it.name == artifactName }
                if (artifactSource)
                    inheritedSources.add(new InheritedArtifact(name: artifactName, jarFile: artifactSource.file))
            }

            // unpack inhertited toolkit (widget sets)
            for (InheritedArtifact toolkit: inheritedWidgetSets) {
                def toolkitArtifact = providedArtefacts.find { it.name == toolkit.name }
                if (toolkitArtifact) {
                    File toolkitJar = toolkitArtifact.file
                    File toolkitClassesDir = new File("${project.buildDir}/tmp/${toolkit.name}-classes")
                    project.copy {
                        from project.zipTree(toolkitJar)
                        into toolkitClassesDir
                    }
                    mainClasspath.add(0, toolkitClassesDir)
                }
            }

            for (InheritedArtifact sourceArtifact: inheritedSources)
                compilerClassPath.add(sourceArtifact.jarFile)
        }

        if (widgetSetModules) {
            if (!(widgetSetModules instanceof Collection))
                widgetSetModules = Collections.singletonList(widgetSetModules)

            for (def widgetSetModule : widgetSetModules) {
                compilerClassPath.add(new File(widgetSetModule.projectDir, 'src'))
                compilerClassPath.add(widgetSetModule.sourceSets.main.output.classesDir)
            }
        }

        compilerClassPath.add(project.sourceSets.main.output.classesDir)

        compilerClassPath.addAll(mainClasspath)

        return compilerClassPath
    }
}

class CubaWebThemeCreation extends DefaultTask {

    def themes
    def cssDir
    def destDir

    CubaWebThemeCreation() {
        setDescription('Builds GWT themes')
    }

    @TaskAction
    def buildThemes() {
        project.logger.info('>>> copying themes to outDir')
        File outDir = new File(destDir)
        outDir.mkdirs()
        themes.each {
            def themeName = it['themeName']
            def themeInclude = themeName + '/**'
            project.copy {
                from cssDir
                into outDir
                include themeInclude
            }
            def destFile = it['destFile'] != null ? it['destFile'] : 'styles-include.css'
            project.logger.info('>>> build theme ' + themeName)
            buildCssTheme(new File(outDir, '/' + themeName), destFile)
        }
    }

    def buildCssTheme(themeDir, destFile) {

        if (!themeDir.isDirectory()) {
            throw new IllegalArgumentException("ThemeDir should be a directory")
        }

        def themeName = themeDir.getName()
        def combinedCss = new StringBuilder()
        combinedCss.append("/* Automatically created css file from subdirectories. */\n")

        final File[] subdir = themeDir.listFiles()

        Arrays.sort(subdir, new Comparator<File>() {
            @Override
            public int compare(File arg0, File arg1) {
                return (arg0).compareTo(arg1)
            }
        })

        for (final File dir: subdir) {
            String name = dir.getName()
            String filename = dir.getPath() + "/" + name + ".css"

            final File cssFile = new File(filename)
            if (cssFile.isFile()) {
                combinedCss.append("\n")
                combinedCss.append("/* >>>>> ").append(cssFile.getName()).append(" <<<<< */")
                combinedCss.append("\n")

                BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(cssFile)))
                String strLine
                while ((strLine = br.readLine()) != null) {
                    String urlPrefix = "../" + themeName + "/"

                    if (strLine.indexOf("url(../") > 0) {
                        strLine = strLine.replaceAll("url\\(../", "url\\(" + urlPrefix)

                    } else {
                        strLine = strLine.replaceAll("url\\(", "url\\(" + urlPrefix + name + "/")

                    }
                    combinedCss.append(strLine)
                    combinedCss.append("\n")
                }
                br.close()
                // delete obsolete css and empty directories
                cssFile.delete()
            }
            if (dir.isDirectory() && ((dir.listFiles() == null) || (dir.listFiles().length == 0)))
                dir.delete()
        }

        def themePath = themeDir.absolutePath

        if (!themePath.endsWith("/")) {
            themePath += "/"
        }

        if (destFile.indexOf(".") == -1) {
            destFile += ".css"
        }

        def themeFileName = themePath + destFile
        BufferedWriter out = new BufferedWriter(new FileWriter(themeFileName))

        CssCompressor compressor = new CssCompressor(new StringReader(combinedCss.toString()))
        compressor.compress(out, 0)

        out.close()

        project.logger.info(">>> compiled CSS to " + themePath + destFile
                + " (" + combinedCss.toString().length() + " bytes)")
    }
}

class CubaWebScssThemeCreation extends DefaultTask {
    def themes = []
    def scssDir = 'themes'
    def destDir = "${project.buildDir}/web/VAADIN/themes"
    def compress = true
    def sprites = false
    def cleanup = true

    def dirFilter = new FileFilter() {
        @Override
        boolean accept(File pathname) {
            return pathname.isDirectory() && !pathname.name.startsWith(".")
        }
    }

    CubaWebScssThemeCreation() {
        setDescription('Compile scss styles in theme')
    }

    @TaskAction
    def buildThemes() {
        if (destDir instanceof String)
            destDir = project.file(destDir)

        if (scssDir instanceof String)
            scssDir = project.file(scssDir)

        if (themes.empty) {
            project.logger.info(">>> scan directory '${scssDir}' for themes")
            for (File themeDir : project.files(scssDir).listFiles(dirFilter))
                themes.add(themeDir)
        }

        themes.each { def themeDir ->
            if (themeDir instanceof String)
                themeDir = new File(scssDir, themeDir)

            def themeDestDir = new File(destDir, themeDir.name)

            project.copy {
                from themeDir
                into themeDestDir
                exclude {
                    it.file.name.startsWith('.') || it.file.name.endsWith('.scss')
                }
            }

            project.logger.info(">>> compile theme '${themeDir.name}'")

            def scssFilePath = project.file("${themeDir}/styles.scss").absolutePath
            def cssFilePath = project.file("${themeDestDir}/styles.css").absolutePath

            project.javaexec {
                main = 'com.vaadin.sass.SassCompiler'
                classpath = project.sourceSets.main.compileClasspath
                args = [scssFilePath, cssFilePath]
                jvmArgs = []
            }

            if (sprites) {
                project.logger.info(">>> compile sprites for theme '${themeDir.name}'")

                def compiledSpritesDir = new File(themeDestDir, 'compiled')
                if (!compiledSpritesDir.exists())
                    compiledSpritesDir.mkdir()

                def processedFile = new File(themeDestDir, 'styles-sprite.css')
                def cssFile = new File(cssFilePath)

                // process
                final SmartSpritesParameters parameters = new SmartSpritesParameters();
                final CmdLineParser parser = new CmdLineParser(parameters);

                parser.parseArgument('--root-dir-path', themeDestDir.absolutePath)

                // Get parameters form system properties
                final MessageLog messageLog = new MessageLog(new PrintStreamMessageSink(System.out, parameters.getLogLevel()));
                new SpriteBuilder(parameters, messageLog).buildSprites();

                def dirsToDelete = []
                // remove sprites directories
                themeDestDir.eachDirRecurse { if ('sprites'.equals(it.name)) dirsToDelete.add(it) }
                dirsToDelete.each { it.deleteDir() }

                // replace file
                if (processedFile.exists()) {
                    cssFile.delete()
                    processedFile.renameTo(cssFile)
                }
            }

            if (compress) {
                project.logger.info(">>> compress theme '${themeDir.name}'")

                def compressedFile = new File(cssFilePath + '.compressed')
                def cssFile = new File(cssFilePath)

                def cssReader = new FileReader(cssFile)
                BufferedWriter out = new BufferedWriter(new FileWriter(compressedFile))

                CssCompressor compressor = new CssCompressor(cssReader)
                compressor.compress(out, 0)

                out.close()
                cssReader.close()

                if (compressedFile.exists()) {
                    cssFile.delete()
                    compressedFile.renameTo(cssFile)
                }
            }

            if (cleanup) {
                // remove empty directories
                recursiveVisitDir(themeDestDir, { File f ->
                    boolean isEmpty = f.list().length == 0
                    if (isEmpty) {
                        project.logger.info(">>> remove empty dir ${themeDestDir.toPath().relativize(f.toPath())}")
                        f.deleteDir()
                    }
                })
            }

            project.logger.info(">>> successfully compiled theme '${themeDir.name}'")
        }
    }

    def recursiveVisitDir(File dir, Closure apply) {
        for (def f : dir.listFiles()) {
            if (f.exists() && f.isDirectory()) {
                recursiveVisitDir(f, apply)
                apply(f)
            }
        }
    }
}

/**
 * Enhance styles in target webContentDir
 */
class CubaEnhanceStyles extends DefaultTask {

    private static final String URL_REGEX = "(?:@import\\s*(?:url\\(\\s*)?|url\\(\\s*).(?:\\.|[^)\"'?])*"

    def webContentDir

    CubaEnhanceStyles() {
        setDescription('Enhance styles in theme')
    }

    @TaskAction
    def enhanceStyles() {
        project.logger.info('>>> enchance styles')

        File contentDir
        if (webContentDir instanceof File)
            contentDir = webContentDir
        else
            contentDir = new File(webContentDir)

        File themesRoot = new File(contentDir, '/VAADIN/themes/')
        if (themesRoot.exists()) {
            themesRoot.eachFile { themeDir ->
                if (themeDir.isDirectory()) {
                    project.logger.info(">>> enhance includes for theme ${themeDir.name}")
                    enhanceTheme(themeDir)
                }
            }
        }
    }

    def enhanceTheme(File themeDir) {
        String releaseTimestamp = Long.toString(new Date().getTime())

        final File[] files = themeDir.listFiles(new FileFilter() {
            @Override
            public boolean accept(File pathname) {
                return pathname.isFile() && pathname.getName().endsWith(".css")
            }
        })

        for (final File file: files) {
            StringBuffer enhanceFile = new StringBuffer()

            BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file)))
            String strLine
            while ((strLine = br.readLine()) != null) {
                strLine = strLine.replaceAll(URL_REGEX, '$0?' + releaseTimestamp)
                enhanceFile.append(strLine)
                enhanceFile.append("\n")
            }
            br.close()
            BufferedWriter out = new BufferedWriter(new FileWriter(file.getPath()))
            out.write(enhanceFile.toString())
            out.close()
            project.logger.info(">>> enhance CSS: " + file.getName())
        }
    }
}