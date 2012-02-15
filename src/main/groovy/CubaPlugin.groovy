/*
 * Copyright (c) 2011 Haulmont Technology Ltd. All Rights Reserved.
 * Haulmont Technology proprietary and confidential.
 * Use is subject to license terms.
 */

import com.yahoo.platform.yui.compressor.CssCompressor
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileTree
import org.gradle.api.internal.file.collections.SimpleFileCollection
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.Zip

class CubaPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        project.group = project.artifactGroup
        project.version = project.artifactVersion + (project.isSnapshot ? '-SNAPSHOT' : '')

        if (!project.hasProperty('tomcatDir'))
            project.tomcatDir = project.rootDir.absolutePath + '/../tomcat'

        if (!project.hasProperty('repositoryUrl'))
            project.repositoryUrl = 'http://repository.haulmont.com:8587/nexus/content'
        if (!project.hasProperty('repositoryUser'))
            project.repositoryUser = System.getenv('HAULMONT_REPOSITORY_USER')
        if (!project.hasProperty('repositoryPassword'))
            project.repositoryPassword = System.getenv('HAULMONT_REPOSITORY_PASSWORD')

        project.repositories {
            mavenLocal()
            maven {
                credentials {
                    username project.repositoryUser
                    password project.repositoryPassword
                }
                url "$project.repositoryUrl/groups/work"
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
            tomcat(group: 'com.haulmont.thirdparty', name: 'apache-tomcat', version: '6.0.29', ext: 'zip')
            tomcat(group: 'com.haulmont.appservers', name: 'tomcat-init', version: '3.0-SNAPSHOT', ext: 'zip')
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
                    node.@languageLevel = 'JDK_1_6'
                    node.@'project-jdk-name' = '1.6'

                    node = provider.node.component.find { it.@name == 'CopyrightManager' }
                    node.@default = 'Haulmont'
                    node = node.appendNode('copyright')
                    node.appendNode('option', [name: 'notice', value: 'Copyright (c) $today.year Haulmont Technology Ltd. All Rights Reserved.\nHaulmont Technology proprietary and confidential.\nUse is subject to license terms.'])
                    node.appendNode('option', [name: 'keyword', value: 'Copyright'])
                    node.appendNode('option', [name: 'allowReplaceKeyword', value: ''])
                    node.appendNode('option', [name: 'myName', value: 'Haulmont'])
                    node.appendNode('option', [name: 'myLocal', value: 'true'])

                    provider.node.component.find { it.@name == 'VcsDirectoryMappings' }.mapping.@vcs = 'svn'

                    node = provider.node.component.find { it.@name == 'CompilerConfiguration' }
                    node = node.appendNode('excludeFromCompile')
                    findEntityDirectories(project).each { dir ->
                        node.appendNode('directory', [url: "file://$dir", includeSubdirectories: 'true'])
                    }
                }
            }
        }
    }

    private void applyToModuleProject(Project project) {
        project.sourceCompatibility = '1.6'

        project.configurations {
            provided
            jdbc
            deployerJars
        }

        project.dependencies {
            deployerJars(group: 'org.apache.maven.wagon', name: 'wagon-http', version: '1.0-beta-2')
        }

        project.sourceSets {
            main {
                java {
                    srcDir 'src'
                    compileClasspath = compileClasspath + project.configurations.provided + project.configurations.jdbc
                }
                resources { srcDir 'src' }
            }
            test {
                java {
                    srcDir 'test'
                    compileClasspath = compileClasspath + project.configurations.provided + project.configurations.jdbc
                }
                resources { srcDir 'test' }
            }
        }

        if (project.hasProperty('install')) { // Check if the Maven plugin has been applied
            project.uploadArchives.configure {
                repositories.mavenDeployer {
                    name = 'httpDeployer'
                    configuration = project.configurations.deployerJars
                    repository(url: "$project.repositoryUrl/repositories/" + (project.isSnapshot ? 'snapshots' : 'releases')) {
                        authentication(userName: project.repositoryUser, password: project.repositoryPassword)
                    }
                }
            }
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
            project.idea.module.inheritOutputDirs = false
            project.idea.module.outputDir = new File(project.buildDir, 'classes/main')
            project.idea.module.testOutputDir = new File(project.buildDir, 'classes/test')
        }
    }

    private Collection findEntityDirectories(Project rootProject) {
        Set result = new HashSet()
        rootProject.subprojects.each { proj ->
            if (proj.hasProperty('sourceSets')) {
                FileTree tree = proj.sourceSets.main.java.matching {
                    include('**/entity/**')
                }
                tree.visit { details ->
                    if (!details.file.isDirectory()) {
                        result.add(details.file.parent)
                    }
                }
            }
        }
        return result
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
                    args('-properties', tmpFile)
                }
            }
        }
        if (metadataXml) {
            File f = new File(persistenceXml)
            if (f.exists()) {
                project.javaexec {
                    main = 'CubaTransientEnhancer'
                    classpath(project.sourceSets.main.compileClasspath, project.sourceSets.main.output.classesDir)
                    args(metadataXml)
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

class CubaWebStartCreation extends DefaultTask {

    def jnlpTemplateName = "${project.projectDir}/webstart/template.jnlp"
    def indexFileName
    def baseHost = 'http://localhost:8080/'
    def basePath = "${project.applicationName}-webstart"
    def signJarsAlias = 'signJars'
    def signJarsPassword = 'HaulmontSignJars'
    def signJarsKeystore = "${project.projectDir}/webstart/sign-jars-keystore.jks"

    CubaWebStartCreation() {
        setDescription('Creates web start distribution')
    }

    @TaskAction
    def create() {
        File distDir = new File(project.buildDir, "distributions/${basePath}")
        File libDir = new File(distDir, 'lib')
        libDir.mkdirs()

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

        libDir.listFiles().each {
            ant.signjar(jar: "${it}", alias: signJarsAlias, keystore: signJarsKeystore, storepass: signJarsPassword)
        }

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

class CubaDbCreation extends DefaultTask {

    def dbms
    def delimiter
    def host = 'localhost'
    def dbFolder = 'db'
    def dbName
    def dbUser
    def dbPassword
    def driverClasspath
    def dropDbSql
    def createDbSql

    @TaskAction
    def createDb() {
        def driver
        def dbUrl
        def masterUrl

        if (!driverClasspath)
            driverClasspath = project.configurations.jdbc.fileCollection { true }.asPath

        if (dbms == 'postgres') {
            driver = 'org.postgresql.Driver'
            dbUrl = "jdbc:postgresql://$host/$dbName"
            masterUrl = "jdbc:postgresql://$host/postgres"
            if (!delimiter)
                delimiter = '^'
            if (!dropDbSql)
                dropDbSql = "drop database if exists $dbName;"
            if (!createDbSql)
                createDbSql = "create database $dbName with template=template0 encoding='UTF8';"
        } else if (dbms == 'mssql') {
            driver = 'net.sourceforge.jtds.jdbc.Driver'
            dbUrl = "jdbc:jtds:sqlserver://$host/$dbName"
            masterUrl = "jdbc:jtds:sqlserver://$host/master"
            if (!delimiter)
                delimiter = '^'
            if (!dropDbSql)
                dropDbSql = "drop database $dbName;"
            if (!createDbSql)
                createDbSql = "create database $dbName;"
        } else
            throw new UnsupportedOperationException("DBMS $dbms not supported")

        project.logger.info(">>> executing SQL: $dropDbSql")
        try {
            project.ant.sql(
                    classpath: driverClasspath,
                    driver: driver,
                    url: masterUrl,
                    userid: dbUser, password: dbPassword,
                    autocommit: true,
                    encoding: "UTF-8",
                    dropDbSql
            )
        } catch (Exception e) {
            project.logger.warn(e.getMessage())
        }

        project.logger.info(">>> executing SQL: $createDbSql")
        project.ant.sql(
                classpath: driverClasspath,
                driver: driver,
                url: masterUrl,
                userid: dbUser, password: dbPassword,
                autocommit: true,
                encoding: "UTF-8",
                createDbSql
        )

        getInitScripts().each { File script ->
            project.logger.info(">>> Executing SQL script: ${script.absolutePath}")
            project.ant.sql(
                    classpath: driverClasspath,
                    src: script.absolutePath,
                    delimiter: delimiter,
                    driver: driver,
                    url: dbUrl,
                    userid: dbUser, password: dbPassword,
                    autocommit: true,
                    encoding: "UTF-8"
            )
        }
    }

    private List<File> getInitScripts() {
        List<File> files = []
        File dbDir = new File(project.buildDir, dbFolder)
        if (dbDir.exists()) {
            String[] moduleDirs = dbDir.list()
            Arrays.sort(moduleDirs)
            for (String moduleDirName: moduleDirs) {
                File moduleDir = new File(dbDir, moduleDirName)
                File initDir = new File(moduleDir, "init")
                File scriptDir = new File(initDir, dbms)
                if (scriptDir.exists()) {
                    File[] scriptFiles = scriptDir.listFiles(new FilenameFilter() {
                        public boolean accept(File dir, String name) {
                            return name.endsWith("create-db.sql")
                        }
                    })
                    Arrays.sort(scriptFiles)
                    files.addAll(Arrays.asList(scriptFiles))
                }
            }
        }
        return files
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

    def widgetSetsDir
    def widgetSetModules
    def widgetSetClass
    def compilerArgs

    private def defaultCompilerArgs = [
           '-style' : 'OBF',
           '-localWorkers' : '2',
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

        if (!widgetSetModules || widgetSetModules.isEmpty())
            throw new IllegalStateException('Please specify not empty \'Collection widgetSetModules\' for build GWT')

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
        def gwtBuildingArtifacts = project.configurations.gwtBuilding.resolvedConfiguration.getResolvedArtifacts()

        File validationSrcDir = gwtBuildingArtifacts.find { a -> a.name == 'validation-api' }.file
        List compilerClassPath = [validationSrcDir]

        def moduleClassesDirs = []
        def moduleSrcDirs = []
        for (def module: widgetSetModules) {
            moduleSrcDirs.add(new File((File) module.projectDir, 'src'))
            moduleClassesDirs.add(module.sourceSets.main.output.classesDir)
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
    protected List collectClassPathEntries() {
        def gwtBuildingArtifacts = project.configurations.gwtBuilding.resolvedConfiguration.getResolvedArtifacts()
        def providedArtefacts = project.configurations.provided.resolvedConfiguration.getResolvedArtifacts()

        File validationSrcDir = gwtBuildingArtifacts.find { a -> a.name == 'validation-api' }.file
        List compilerClassPath = [validationSrcDir]

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
                File toolkitJar = providedArtefacts.find { it.name == toolkit.name }.file
                File toolkitClassesDir = new File("${project.buildDir}/tmp/${toolkit.name}-classes")
                project.copy {
                    from project.zipTree(toolkitJar)
                    into toolkitClassesDir
                }
                mainClasspath.add(0, toolkitClassesDir)
            }

            for (InheritedArtifact sourceArtifact: inheritedSources)
                compilerClassPath.add(sourceArtifact.jarFile)
        }

        if (!(widgetSetModules instanceof Collection))
            widgetSetModules = Collections.singletonList(widgetSetModules)

        for (def widgetSetModule: widgetSetModules) {
            compilerClassPath.add(new File(widgetSetModule.projectDir, 'src'))
            compilerClassPath.add(widgetSetModule.sourceSets.main.output.classesDir)
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
            throw new IllegalArgumentException("ThemeDir should be a directory");
        }

        def themeName = themeDir.getName()
        def combinedCss = new StringBuilder()
        combinedCss.append("/* Automatically created css file from subdirectories. */\n")

        final File[] subdir = themeDir.listFiles();

        Arrays.sort(subdir, new Comparator<File>() {
            @Override
            public int compare(File arg0, File arg1) {
                return (arg0).compareTo(arg1);
            }
        });

        for (final File dir: subdir) {
            String name = dir.getName();
            String filename = dir.getPath() + "/" + name + ".css";

            final File cssFile = new File(filename);
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
            themePath += "/";
        }

        if (destFile.indexOf(".") == -1) {
            destFile += ".css";
        }

        def themeFileName = themePath + destFile
        BufferedWriter out = new BufferedWriter(new FileWriter(themeFileName));

        CssCompressor compressor = new CssCompressor(new StringReader(combinedCss.toString()))
        compressor.compress(out, 0)

        out.close();

        project.logger.info(">>> compiled CSS to " + themePath + destFile
                + " (" + combinedCss.toString().length() + " bytes)");
    }
}