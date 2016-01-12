import org.apache.commons.lang.StringUtils
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.tasks.TaskAction

/**
 *
 * @author degtyarjov
 * @version $Id$
 */
class CubaWarBuilding extends DefaultTask {
    Project coreProject;
    Project webProject;
    def appHome
    def appName
    def singleWar = true
    def webXml

    def projectAll
    def webcontentExclude = []
    Closure doAfter
    def appProperties
    def coreJarNames;
    def webJarNames;

    def coreTmpWarDir
    def webTmpWarDir

    def String distrDir = "${project.buildDir}/distributions/war"

    void setCoreProject(Project coreProject) {
        this.coreProject = coreProject
        def assembleCore = coreProject.getTasksByName('assemble', false).iterator().next()
        this.dependsOn(assembleCore)
    }

    void setWebProject(Project webProject) {
        this.webProject = webProject
        def assembleWeb = webProject.getTasksByName('assemble', false).iterator().next()
        this.dependsOn(assembleWeb)
    }

    String warDir(Project project) {
        project == coreProject ? coreTmpWarDir : webTmpWarDir
    }

    @TaskAction
    def build() {
        init()

        copyLibs(coreProject)
        copyLibs(webProject)

        def coreProperties = collectProperties(coreProject)
        def webProperties = collectProperties(webProject)

        copyDbScripts(coreProject)

        copyWebContent(coreProject)
        copyWebContent(webProject)
        copySpecificWebContent(webProject)

        processDoAfter()

        touchWebXml(coreProject)
        touchWebXml(webProject)

        if (singleWar) {
            def summaryProperties = coreProperties + webProperties
            writeLocalAppProperties(webProject, summaryProperties)
            writeDependencies(coreProject, 'core', coreJarNames)
            writeDependencies(webProject, 'web', webJarNames)

            packWarFile(webProject, webProject.file("${warDir(webProject)}/${appName}.war"))
            project.copy {
                from webProject.file("${warDir(webProject)}/${appName}.war")
                into distrDir
            }

            generateAppHome()
        } else {
            writeLocalAppProperties(coreProject, coreProperties)
            writeLocalAppProperties(webProject, webProperties)

            packWarFile(webProject, webProject.file("${warDir(webProject)}/${appName}.war"))
            packWarFile(coreProject, coreProject.file("${warDir(coreProject)}/${appName}-core.war"))
            webProject.copy {
                from webProject.file("${warDir(webProject)}/${appName}.war")
                from coreProject.file("${warDir(coreProject)}/${appName}-core.war")
                into distrDir
            }

            generateAppHome()
        }

        project.delete("${project.buildDir}/tmp")
    }

    private void init() {
        project.delete(distrDir)

        CubaDeployment deployCore = coreProject.getTasksByName('deploy', false).iterator().next() as CubaDeployment
        CubaDeployment deployWeb = webProject.getTasksByName('deploy', false).iterator().next() as CubaDeployment

        if (!coreJarNames) {
            coreJarNames = deployCore.jarNames
        }

        if (!webJarNames) {
            webJarNames = deployWeb.jarNames
        }

        if (singleWar) {
            coreTmpWarDir = "${project.buildDir}/tmp/war"
            webTmpWarDir = coreTmpWarDir
        } else {
            coreTmpWarDir = "${project.buildDir}/tmp/core/war"
            webTmpWarDir = "${project.buildDir}/tmp/web/war"
        }
    }

    private Map<String, Object> collectProperties(Project theProject) {
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
                    'cuba.connectionUrlList'        : "http://localhost:8080/${appName}-core",
                    'cuba.useLocalServiceInvocation': singleWar ? "true" : "false"
            ]
        }

        if (appProperties) {
            properties += appProperties
        }
        properties
    }

    private void copyLibs(Project theProject) {
        theProject.logger.info("[CubaWarBuilding] copying libs from configurations.runtime")
        theProject.copy {
            from theProject.configurations.runtime
            from theProject.libsDir
            into "${warDir(theProject)}/WEB-INF/lib"
            include { details ->
                def name = details.file.name
                return !(name.endsWith('-sources.jar'))
            }
        }
    }

    private void writeDependencies(Project theProject, String applicationType, def jarNames) {
        File dependenciesFile = new File("${warDir(theProject)}/WEB-INF/${applicationType}.dependencies")
        dependenciesFile.withWriter('UTF-8') { writer ->
            theProject.configurations.runtime.each { File lib ->
                if (!lib.name.endsWith('-sources.jar')
                        && !lib.name.endsWith('-tests.jar')
                        && jarNames.find { lib.name.startsWith(it) } != null) {
                    writer << lib.name << '\n'
                }
            }

            new File("$theProject.libsDir").listFiles().each { File lib ->
                if (!lib.name.endsWith('-sources.jar')
                        && !lib.name.endsWith('-tests.jar')
                        && jarNames.find { lib.name.startsWith(it) } != null) {
                    writer << lib.name << '\n'
                }
            }
        }
    }

    private void copyDbScripts(Project theProject) {
        theProject.copy {
            from "${theProject.buildDir}/db"
            into "${warDir(theProject)}/WEB-INF/db"
        }
    }

    private void copyWebContent(Project theProject) {
        theProject.logger.info("[CubaWarBuilding] copying from web to ${warDir(theProject)}")

        if (webXml) {
            def webXmlFileName = new File(webXml).name
            theProject.copy {
                from 'web'
                into warDir(theProject)
                exclude '**/context.xml'
                exclude "**/$webXmlFileName"//do not copy webXml file twice
            }

            theProject.logger.info("[CubaWarBuilding] copying web.xml from ${webXml} to ${warDir(theProject)}/WEB-INF/web.xml")
            theProject.copy {
                from webXml
                into "${warDir(theProject)}/WEB-INF/"
                rename { String fileName ->
                    "web.xml"
                }
            }
        } else {
            theProject.copy {
                from 'web'
                into warDir(theProject)
                exclude '**/context.xml'
            }
        }
    }

    private void copySpecificWebContent(Project theProject) {
        if (!projectAll) {
            if (theProject.configurations.getAsMap().webcontent) {
                def excludePatterns = ['**/web.xml', '**/context.xml'] + webcontentExclude
                theProject.configurations.webcontent.files.each { dep ->
                    theProject.logger.info("[CubaWarBuilding] copying webcontent from $dep.absolutePath to ${warDir(theProject)}")
                    theProject.copy {
                        from theProject.zipTree(dep.absolutePath)
                        into warDir(theProject)
                        excludes = excludePatterns
                        includeEmptyDirs = false
                    }
                }
                theProject.logger.info("[CubaWarBuilding] copying webcontent from ${theProject.buildDir}/web to ${warDir(theProject)}")
                theProject.copy {
                    from "${theProject.buildDir}/web"
                    into warDir(theProject)
                    exclude '**/context.xml'
                }
            }
            def webToolkit = theProject.rootProject.subprojects.find { subprj -> subprj.name.endsWith('web-toolkit') }
            if (webToolkit) {
                theProject.logger.info("[CubaWarBuilding] copying webcontent from ${webToolkit.buildDir}/web to ${warDir(theProject)}")
                theProject.copy {
                    from "${webToolkit.buildDir}/web"
                    into warDir(theProject)
                    exclude '**/gwt-unitCache/'
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
                into warDir(theProject)
                exclude '**/web.xml'
                exclude '**/context.xml'
                exclude '**/gwt-unitCache/'
            }
        }
    }

    private void writeLocalAppProperties(Project theProject, def properties) {
        File appPropFile = new File("${warDir(theProject)}/WEB-INF/local.app.properties")
        project.logger.info("[CubaWarBuilding] writing $appPropFile")
        appPropFile.withWriter('UTF-8') { writer ->
            properties.each { key, value ->
                writer << key << ' = ' << value << '\n'
            }
        }
    }

    private void processDoAfter() {
        if (doAfter) {
            project.logger.info("[CubaWarBuilding] calling doAfter")
            doAfter.call()
        }
    }

    private void touchWebXml(Project theProject) {
        def webXml = new File("${warDir(theProject)}/WEB-INF/web.xml")
        if (theProject.ext.has('webResourcesTs')) {
            theProject.logger.info("[CubaWarBuilding] update web resources timestamp")

            // detect version automatically
            def buildTimeStamp = theProject.ext.get('webResourcesTs')

            def webXmlText = webXml.text
            if (StringUtils.contains(webXmlText, '${webResourcesTs}')) {
                webXmlText = webXmlText.replace('${webResourcesTs}', buildTimeStamp)
            }
            webXml.write(webXmlText)
        }
        theProject.logger.info("[CubaWarBuilding] touch ${warDir(theProject)}/WEB-INF/web.xml")
        webXml.setLastModified(new Date().getTime())
    }

    private void packWarFile(Project project, File destFile) {
        ant.jar(destfile: destFile, basedir: warDir(project))
    }

    private Set<File> generateAppHome() {
        String appHomeName = new File(appHome).name

        project.configurations.tomcatInit.files.each { dep ->
            project.copy {
                from project.zipTree(dep.absolutePath).files
                into "$distrDir/$appHomeName"
                include '**/log4j.xml'
                filter { String line ->
                    line.replace('${catalina.home}', appHome)
                }
            }
        }
    }
}