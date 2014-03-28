/*
 * Copyright (c) 2008-2014 Haulmont. All rights reserved.
 * Use is subject to license terms, see http://www.cuba-platform.com/license for details.
 */

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

/**
 *
 * @author krivopustov
 * @version $Id$
 */
class CubaWarDistribution extends DefaultTask {

    def String distrDir = "${project.buildDir}/war"
    def String appHome

    CubaWarDistribution() {
        setDescription('Builds WARs distribution')
        setGroup('Deployment')
    }

    @TaskAction
    def build() {
        if (!appHome)
            throw new IllegalStateException("CubaWarDistribution requires appHome parameter")

        String appHomeName = new File(appHome).name

        taskDependencies.getDependencies(this).each { t ->
            project.copy {
                from t.outputFile
                into distrDir
            }

            if (new File("${t.project.buildDir}/db").exists()) {
                project.copy {
                    from "${t.project.buildDir}/db"
                    into "$distrDir/$appHomeName/db"
                }
            }
        }

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
