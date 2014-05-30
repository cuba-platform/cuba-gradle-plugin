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
class CubaDropTomcat extends DefaultTask {

    def tomcatRootDir = project.tomcatDir
    def listeningPort = '8787'

    CubaDropTomcat() {
        setDescription('Deletes local Tomcat')
        setGroup('Deployment')
    }

    @TaskAction
    def deploy() {
        File dir = new File(tomcatRootDir)
        if (dir.exists()) {
            project.logger.info ">>> deleting $dir"
            // stop
            def binDir = "${tomcatRootDir}/bin"

            if (new File(binDir).exists()) {
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
            }
            project.delete(dir)
        }
    }
}