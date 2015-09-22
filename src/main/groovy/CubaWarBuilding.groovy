/*
 * Copyright (c) 2008-2013 Haulmont. All rights reserved.
 * Use is subject to license terms, see http://www.cuba-platform.com/license for details.
 */


import org.apache.commons.lang.StringUtils
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

/**
 * @author artamonov
 * @version $Id$
 */
class CubaWarBuilding extends DefaultTask {
    def appName
    def connectionUrlList
    def Closure doAfter
    def webcontentExclude = []
    def appProperties
    def appHome
    def boolean projectAll
    def boolean includeDbScripts = true
    def boolean singleWar = true
    def jarNames
    def webXml

    def tmpWarDir

    CubaWarBuilding() {
        setDescription('Builds WAR')
        setGroup('Deployment')

        if (singleWar) {
            tmpWarDir = "${project.rootProject.buildDir}/tmp/war"
        } else {
            tmpWarDir = "${project.buildDir}/tmp/war/"
        }
    }

    def getOutputFile() {
        new File("${project.buildDir}/distributions/${appName}.war")
    }

    @TaskAction
    def build() {
        if (!appHome)
            throw new IllegalStateException("CubaWarBuilding task requires appHome parameter")

        Map<String, GString> properties = collectProperties()

        copyLibs()

        writeDependencies()

        copyDbScripts()

        copyWebContent()

        writeLocalAppProperties(properties)

        processDoAfter()

        touchWebXml()

        packWarFile()

        if (!singleWar || isWebApp()) {
            project.delete(tmpWarDir)
        }
    }

    private void processDoAfter() {
        if (doAfter) {
            project.logger.info(">>> calling doAfter")
            doAfter.call()
        }
    }

    private void packWarFile() {
        if (!singleWar || isWebApp()) {
            ant.jar(destfile: getOutputFile(), basedir: tmpWarDir)
        }
    }

    private void touchWebXml() {
        def webXml = new File("${tmpWarDir}/WEB-INF/web.xml")
        if (project.ext.has('webResourcesTs')) {
            project.logger.info(">>> update web resources timestamp")

            // detect version automatically
            def buildTimeStamp = project.ext.get('webResourcesTs')

            def webXmlText = webXml.text
            if (StringUtils.contains(webXmlText, '${webResourcesTs}')) {
                webXmlText = webXmlText.replace('${webResourcesTs}', buildTimeStamp)
            }
            webXml.write(webXmlText)
        }
        project.logger.info(">>> touch ${tmpWarDir}/WEB-INF/web.xml")
        webXml.setLastModified(new Date().getTime())
    }

    private void writeLocalAppProperties(properties) {
        File appPropFile = new File("${tmpWarDir}/WEB-INF/local.app.properties")
        project.logger.info(">>> writing $appPropFile")
        appPropFile.withWriter('UTF-8') { writer ->
            properties.each { key, value ->
                writer << key << ' = ' << value << '\n'
            }
        }
    }

    private void copyWebContent() {
        if (isWebApp()) {
            if (!projectAll) {
                if (project.configurations.getAsMap().webcontent) {
                    def excludePatterns = ['**/web.xml', '**/context.xml'] + webcontentExclude
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
                        exclude '**/context.xml'
                    }
                }
                def webToolkit = project.rootProject.subprojects.find { subprj -> subprj.name.endsWith('web-toolkit') }
                if (webToolkit) {
                    project.logger.info(">>> copying webcontent from ${webToolkit.buildDir}/web to ${tmpWarDir}")
                    project.copy {
                        from "${webToolkit.buildDir}/web"
                        into tmpWarDir
                        exclude '**/gwt-unitCache/'
                    }
                }
            } else {
                project.logger.info(">>> copying webcontent from project-all directories to ${tmpWarDir}")
                project.copy {
                    from new File(project.project(':cuba-web').projectDir, 'web')
                    from new File(project.project(':charts-web').projectDir, 'web')
                    from new File(project.project(':workflow-web').projectDir, 'web')
                    from new File(project.project(':reports-web').projectDir, 'web')
                    from new File(project.project(':refapp-web').buildDir, 'web')
                    from new File(project.project(':refapp-web-toolkit').buildDir, 'web')
                    into tmpWarDir
                    exclude '**/web.xml'
                    exclude '**/context.xml'
                    exclude '**/gwt-unitCache/'
                }
            }
        }

        if (webXml) {
            project.logger.info(">>> copying from web to ${tmpWarDir}")
            project.copy {
                from 'web'
                into tmpWarDir
                exclude '**/context.xml'
            }

            project.logger.info(">>> copying web.xml from ${webXml} to ${tmpWarDir}/web.xml")
            project.copy {
                from webXml
                into "$tmpWarDir/WEB-INF/"
                rename { String fileName ->
                    "web.xml"
                }
            }
        } else {
            project.logger.info(">>> copying from web to ${tmpWarDir}")
            project.copy {
                from 'web'
                into tmpWarDir
                exclude '**/context.xml'
            }
        }
    }

    private void copyDbScripts() {
        if (includeDbScripts) {
            project.copy {
                from "${project.buildDir}/db"
                into "${tmpWarDir}/WEB-INF/db"
            }
        }
    }

    private void copyLibs() {
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
    }

    private void writeDependencies() {
        if (singleWar && jarNames) {
            File dependenciesFile = new File("${tmpWarDir}/WEB-INF/${isWebApp() ? 'web' : 'core'}.dependencies")
            dependenciesFile.withWriter('UTF-8') { writer ->
                project.configurations.runtime.each { File lib ->
                    if (jarNames.find { lib.name.startsWith(it) } != null) {
                        writer << lib.name << '\n'
                    }
                }

                new File("$project.libsDir").listFiles().each { File lib ->
                    if (jarNames.find { lib.name.startsWith(it) } != null) {
                        writer << lib.name << '\n'
                    }
                }
            }
        } else if (singleWar && !jarNames) {
            throw new RuntimeException("Please specify jarNames property for buildWar tasks to build single WAR file. Otherwise it would not work fine")
        }
    }

    private Map<String, GString> collectProperties() {
        def properties = [
                'cuba.logDir' : "$appHome/logs",
                'cuba.confDir': "$appHome/\${cuba.webContextName}/conf",
                'cuba.tempDir': "$appHome/\${cuba.webContextName}/temp",
                'cuba.dataDir': "$appHome/\${cuba.webContextName}/work"
        ]
        if (singleWar || isCoreApp()) {
            properties += [
                    'cuba.dataSourceJndiName'  : 'jdbc/CubaDS',
                    'cuba.download.directories': "\${cuba.tempDir};\${cuba.logDir}"
            ]

            if (includeDbScripts) {
                properties += ['cuba.dbDir': "web-inf:db",]
            } else {
                properties += ['cuba.dbDir': "$appHome/\${cuba.webContextName}/db",]
            }
        }

        if (singleWar || isWebApp()) {
            def connectionUrlListProperty = connectionUrlList ?: "http://localhost:8080/${appName}-core"
            properties += [
                    'cuba.connectionUrlList'        : connectionUrlListProperty,
                    'cuba.useLocalServiceInvocation': (singleWar ? 'true' : 'false')
            ]
        }

        if (appProperties) {
            properties += appProperties
        }
        properties
    }

    private boolean isCoreApp() {
        project.name.endsWith('-core')
    }

    private boolean isWebApp() {
        project.name.endsWith('-web')
    }
}