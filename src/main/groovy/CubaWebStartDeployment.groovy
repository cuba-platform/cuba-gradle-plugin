/*
 * Copyright (c) 2008-2013 Haulmont. All rights reserved.
 * Use is subject to license terms, see http://www.cuba-platform.com/license for details.
 */

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

/**
 * @author krivopustov
 * @version $Id$
 */
class CubaWebStartDeployment extends DefaultTask {

    def basePath = "${project.applicationName}-webstart"

    CubaWebStartDeployment() {
        setDescription('Deploys web start distribution into the local Tomcat')
        setGroup('Web Start')
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