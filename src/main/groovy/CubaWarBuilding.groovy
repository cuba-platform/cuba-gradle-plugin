/*
 * Copyright (c) 2012 Haulmont Technology Ltd. All Rights Reserved.
 * Haulmont Technology proprietary and confidential.
 * Use is subject to license terms.
 */

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

/**
 * @author artamonov
 * @version $Id$
 */
class CubaWarBuilding extends DefaultTask {

    def appName
    def Closure doAfter
    def webcontentExclude = []
    def dbScriptsExcludes = []
    def tmpWarDir

    CubaWarBuilding() {
        setDescription('Builds WAR distribution')
        setGroup('Compile')

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