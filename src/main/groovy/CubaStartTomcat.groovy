/*
 * Copyright (c) 2008-2013 Haulmont. All rights reserved.
 * Use is subject to license terms, see http://www.cuba-platform.com/license for details.
 */


import org.apache.commons.lang.StringUtils
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

/**
 * @author krivopustov
 * @version $Id$
 */
class CubaStartTomcat extends DefaultTask {

    def tomcatRootDir = project.tomcatDir

    CubaStartTomcat() {
        setDescription('Starts local Tomcat')
        setGroup('Development server')
    }

    @TaskAction
    def deploy() {
        def binDir = "${tomcatRootDir}/bin"
        project.logger.info ">>> starting $tomcatRootDir"

        def tomcatStartScript = System.getenv("CUBA_TOMCAT_START_SCRIPT")
        if (StringUtils.isBlank(tomcatStartScript)) {
            ant.exec(osfamily: 'windows', dir: "${binDir}", executable: 'cmd.exe', spawn: true) {
                env(key: 'NOPAUSE', value: true)
                arg(line: '/c start callAndExit.bat debug.bat')
            }
            ant.exec(osfamily: 'unix', dir: "${binDir}", executable: '/bin/sh') {
                arg(line: 'debug.sh')
            }
        } else {
            println "Execute tomcat start with ${tomcatStartScript}"

            ant.exec(osfamily: 'windows', dir: "${binDir}", executable: tomcatStartScript, spawn: true) {
                arg(line: tomcatRootDir)
            }
            ant.exec(osfamily: 'unix', dir: "${binDir}", executable: tomcatStartScript) {
                arg(line: tomcatRootDir)
            }
        }
    }
}