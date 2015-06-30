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
class CubaStopTomcat extends DefaultTask {

    def tomcatRootDir = project.tomcatDir

    CubaStopTomcat() {
        setDescription('Stops local Tomcat')
        setGroup('Deployment')
    }

    @TaskAction
    def deploy() {
        def binDir = "${tomcatRootDir}/bin"
        project.logger.info ">>> stopping $tomcatRootDir"
        ant.exec(osfamily: 'windows', dir: "${binDir}", executable: 'cmd.exe', spawn: true) {
            env(key: 'NOPAUSE', value: true)
            if (project.hasProperty('studioJavaHome')) {
                env(key: 'JAVA_HOME', value: project.studioJavaHome)
            }
            arg(line: '/c start callAndExit.bat shutdown.bat')
        }
        ant.exec(osfamily: 'unix', dir: "${binDir}", executable: '/bin/sh') {
            arg(line: 'shutdown.sh')
        }
    }
}