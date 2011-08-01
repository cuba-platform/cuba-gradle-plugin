/*
 * Copyright (c) 2011 Haulmont Technology Ltd. All Rights Reserved.
 * Haulmont Technology proprietary and confidential.
 * Use is subject to license terms.
 */

import org.gradle.api.*
import org.gradle.api.plugins.*
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.file.FileTree

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

        org.apache.ivy.util.url.CredentialsStore.INSTANCE.addCredentials(
            'Sonatype Nexus Repository Manager',
            'repository.haulmont.com',
            project.repositoryUser,
            project.repositoryPassword
        )

        project.repositories {
            mavenLocal()
            mavenRepo(urls: "$project.repositoryUrl/groups/work")
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

        project.task([description: 'Sets up Tomcat instance'], 'setupTomcat') << {
            project.configurations.tomcat.files.each { dep ->
                project.copy {
                    from project.zipTree(dep.absolutePath)
                    into project.tomcatDir
                }
            }
            ant.chmod(osfamily: 'unix', perm: 'a+x') {
                fileset(dir: "${project.tomcatDir}/bin", includes: '*.sh')
            }
        }

        project.task([description: 'Starts local Tomcat'], 'start') << {
            ant.exec(osfamily: 'windows', dir: "${project.tomcatDir}/bin", executable: 'cmd.exe', spawn: true) {
                env(key: 'NOPAUSE', value: true)
                arg(line: '/c start callAndExit.bat debug.bat')
            }
            ant.exec(osfamily: 'unix', dir: "${project.tomcatDir}/bin", executable: '/bin/sh') {
                arg(line: 'debug.sh')
            }
        }

        project.task([description: 'Stops local Tomcat'], 'stop') << {
            ant.exec(osfamily: 'windows', dir: "${project.tomcatDir}/bin", executable: 'cmd.exe', spawn: true) {
                env(key: 'NOPAUSE', value: true)
                arg(line: '/c start callAndExit.bat shutdown.bat')
            }
            ant.exec(osfamily: 'unix', dir: "${project.tomcatDir}/bin", executable: '/bin/sh') {
                arg(line: 'shutdown.sh')
            }
        }
        
        project.task([description: 'Close tomcat after tests'], 'closeTomcat') << {
            ant.exec(osfamily: 'windows', dir: "${project.tomcatDir}/bin", executable: 'cmd.exe', spawn: true) {
                env(key: 'NOPAUSE', value: true)
                arg(line: '/c start callAndExit.bat shutdown.bat')
            }
            ant.exec(osfamily: 'unix', dir: "${project.tomcatDir}/bin", executable: '/bin/sh') {
                arg(line: 'shutdown.sh')
            }
        }
        
        project.task([description: 'Delete Tomcat instance'], 'dropTomcat') << {     
            File dir = new File(project.tomcatDir)
            if (dir.exists()) {
                project.tasks['stop'].execute()
                ant.waitfor(maxwait: 6, maxwaitunit: 'second', checkevery: 2, checkeveryunit: 'second') {
                    not {
                        socket(server: 'localhost', port: '8787')
                    }
                }
                project.delete(dir)
            }
        }

        if (project.convention.plugins.idea) {
            project.ideaProject.withXml { provider ->
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
        
        project.uploadArchives.configure {
            repositories.mavenDeployer {
                name = 'httpDeployer'
                configuration = project.configurations.deployerJars
                repository(url: "$project.repositoryUrl/repositories/" + (project.isSnapshot ? 'snapshots' : 'releases')) {
                    authentication(userName: project.repositoryUser, password: project.repositoryPassword)
                }
            }
        }
        
        if (project.name.endsWith('-core')) {
            project.task('assembleDbScripts') << {
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
                        def lastName = Arrays.asList(dir.list()).sort().last()
                        def num = lastName.substring(0,2).toInteger()
                        project.copy {
                            project.logger.info ">>> copy db from: $srcDbDir.absolutePath" 
                            from srcDbDir
                            into "${project.buildDir}/db/${num + 10}-${project.rootProject.name}"
                        }
                    }
                }
            }
            
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
        
        if (project.convention.plugins.idea) {
            project.ideaModule.scopes += [PROVIDED: [plus: [project.configurations.provided, project.configurations.jdbc], minus: []]]
            // Uncomment this and remove withXml hook after upgrade to milestone-4
            // project.ideaModule.inheritOutputDirs = false
            // project.ideaModule.outputDir = new File(project.buildDir, 'classes/main')
            // project.ideaModule.testOutputDir = new File(project.buildDir, 'classes/test')
            project.ideaModule.withXml { provider ->
                def node = provider.node.component.find { it.@name == 'NewModuleRootManager' }
                node.@'inherit-compiler-output' = 'false'
                node.appendNode('output', [url: "file://${project.buildDir}/classes/main"])
                node.appendNode('output-test', [url: "file://${project.buildDir}/classes/test"])
            }    
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
                    prop = properties.appendNode('property', [name: 'openjpa.DetachState', value: 'loaded(DetachedStateField=true, DetachedStateManager=true)'])
                
                File tmpDir = new File(project.buildDir, 'tmp')
                tmpDir.mkdirs()
                File tmpFile = new File(tmpDir, 'persistence.xml')
                new XmlNodePrinter(new PrintWriter(new FileWriter(tmpFile))).print(persistence)
                
                project.javaexec {
                    main = 'org.apache.openjpa.enhance.PCEnhancer'
                    classpath(project.sourceSets.main.compileClasspath, project.sourceSets.main.classesDir)
                    args('-properties', tmpFile)
                }
            }
        }
        if (metadataXml) {
            File f = new File(persistenceXml)
            if (f.exists()) {
                project.javaexec {
                    main = 'com.haulmont.cuba.core.sys.CubaTransientEnhancer'
                    classpath(project.sourceSets.main.compileClasspath, project.sourceSets.main.classesDir)
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

    CubaDeployment() {
        setDescription('Deploys applications for local usage')
    }

    @TaskAction
    def deploy() {
        project.logger.info(">>> copying from configurations.jdbc to ${project.tomcatDir}/lib")
        project.copy {
            from project.configurations.jdbc
            into "${project.tomcatDir}/lib"
        }
        project.logger.info(">>> copying shared libs from configurations.runtime")
        project.copy {
            from project.configurations.runtime
            into "${project.tomcatDir}/shared/lib"
            include { details ->
                def name = details.file.name
                return !(name.endsWith('-sources.jar')) && (jarNames.find { name.startsWith(it) } == null)
            }
        }
        project.logger.info(">>> copying app libs from configurations.runtime")
        project.copy {
            from project.configurations.runtime
            from project.libsDir
            into "${project.tomcatDir}/webapps/$appName/WEB-INF/lib"
            include { details ->
                def name = details.file.name
                return !(name.endsWith('.zip')) && !(name.endsWith('-tests.jar')) && !(name.endsWith('-sources.jar')) && 
                    (jarNames.find { name.startsWith(it) } != null)
            }
        }
        
        if (project.configurations.getAsMap().dbscripts) {
            project.logger.info(">>> copying dbscripts from ${project.buildDir}/db to ${project.tomcatDir}/webapps/$appName/WEB-INF/db")
            project.copy {
                from "${project.buildDir}/db"
                into "${project.tomcatDir}/webapps/$appName/WEB-INF/db"
            }
        }
        
        if (project.configurations.getAsMap().webcontent) {
            project.configurations.webcontent.files.each { dep ->
                project.logger.info(">>> copying webcontent from $dep.absolutePath to ${project.tomcatDir}/webapps/$appName")
                project.copy {
                    from project.zipTree(dep.absolutePath)
                    into "${project.tomcatDir}/webapps/$appName"
                    exclude '**/web.xml'
                }
            }
            project.logger.info(">>> copying webcontent from ${project.buildDir}/web to ${project.tomcatDir}/webapps/$appName")
            project.copy {
                from "${project.buildDir}/web"
                into "${project.tomcatDir}/webapps/$appName"
            }
        }
        
        project.logger.info(">>> copying from web to ${project.tomcatDir}/webapps/$appName")
        project.copy {
            from 'web'
            into "${project.tomcatDir}/webapps/$appName"
        }

        if (doAfter) {
            project.logger.info(">>> calling doAfter")
            doAfter.call()
        }
            
        project.logger.info(">>> touch ${project.tomcatDir}/webapps/$appName/WEB-INF/web.xml")
        File webXml = new File("${project.tomcatDir}/webapps/$appName/WEB-INF/web.xml")
        webXml.setLastModified(new Date().getTime())
    }

    def appJars(Object... names) {
        jarNames = names
    }
}

class CubaDbCreation extends DefaultTask {
    
    def dbms
    def delimiter
    def host = 'localhost'
    def dbName
    def dbUser
    def dbPassword
    def driverClasspath 
    def createDbSql
    
    @TaskAction
    def createDb() {
        def driver
        def dbUrl
        def masterUrl
        
        if (!driverClasspath)
            driverClasspath = project.configurations.jdbc.fileCollection{ true }.asPath
            
        if (dbms == 'postgres') {
            driver = 'org.postgresql.Driver'
            dbUrl = "jdbc:postgresql://$host/$dbName"
            masterUrl = "jdbc:postgresql://$host/postgres"
            if (!delimiter) 
                delimiter = '^'
            if (!createDbSql) 
                createDbSql = "drop database if exists $dbName; create database $dbName with template=template0 encoding='UTF8';"
        } else 
            throw new UnsupportedOperationException("DBMS $dbms not supported")
        
        project.logger.info(">>> executing SQL: $createDbSql")
        project.ant.sql(
            classpath: driverClasspath,
            driver: driver,
            url: masterUrl,
            userid: dbUser, password: dbPassword,
            autocommit: true,
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
                    autocommit: true
            )
        }
    }
    
    private List<File> getInitScripts() {
        List<File> files = []
        File dbDir = new File(project.buildDir, 'db')
        if (dbDir.exists()) {
            String[] moduleDirs = dbDir.list()
            Arrays.sort(moduleDirs)
            for (String moduleDirName : moduleDirs) {
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
