/*
 * Copyright (c) 2011 Haulmont Technology Ltd. All Rights Reserved.
 * Haulmont Technology proprietary and confidential.
 * Use is subject to license terms.
 */

import org.gradle.api.*
import org.gradle.api.plugins.*
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.TaskAction

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

        if (project == project.rootProject) {

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
            }

            project.task([description: 'Starts local Tomcat'], 'start') << {
                ant.exec(dir: "${project.tomcatDir}/bin", executable: 'cmd.exe', spawn: true) {
                    env(key: 'NOPAUSE', value: true)
                    arg(line: '/c start callAndExit.bat debug.bat')
                }
            }

            project.task([description: 'Stops local Tomcat'], 'stop') << {
                ant.exec(dir: "${project.tomcatDir}/bin", executable: 'cmd.exe', spawn: true) {
                    env(key: 'NOPAUSE', value: true)
                    arg(line: '/c start callAndExit.bat shutdown.bat')
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
                }
            }
        } else {
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

            if (project.convention.plugins.idea) {
                project.ideaModule.scopes += [PROVIDED: [plus: [project.configurations.provided], minus: []]]
            }
        }
    }
}

class CubaEnhancing extends JavaExec {

    CubaEnhancing() {
        main = 'org.apache.openjpa.enhance.PCEnhancer'
        classpath(project.sourceSets.main.compileClasspath, project.sourceSets.main.classesDir)
        setDescription('Enhances classes')
    }

    void setPersistenceXml(String persistenceXml) {
        args('-properties', persistenceXml)
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
        project.copy {
            from 'web'
            into "$project.tomcatDir/webapps/$appName"
        }
        project.copy {
            from project.configurations.jdbc
            into "$project.tomcatDir/lib"
        }
        project.copy {
            from project.configurations.runtime
            into "$project.tomcatDir/shared/lib"
            include { details ->
                def name = details.file.name
                return !(name.endsWith('-sources.jar')) && (jarNames.find { name.startsWith(it) } == null)
            }
        }
        project.copy {
            from project.configurations.runtime
            from project.libsDir
            into "$project.tomcatDir/webapps/$appName/WEB-INF/lib"
            include { details ->
                def name = details.file.name
                return !(name.endsWith('-sources.jar')) && (jarNames.find { name.startsWith(it) } != null)
            }
        }
        
        if (doAfter)
            doAfter.call()
        
        File webXml = new File("$project.tomcatDir/webapps/$appName/WEB-INF/web.xml")
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
        
        project.ant.sql(
            classpath: driverClasspath,
            driver: driver,
            url: masterUrl,
            userid: dbUser, password: dbPassword,
            autocommit: true,
            createDbSql
        )
        
        getInitScripts().each { File script ->
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
                    files.addAll(Arrays.asList(scriptFiles))
                }
            }
        }
        return files
    }

}