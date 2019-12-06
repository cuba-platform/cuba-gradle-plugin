/*
 * Copyright (c) 2008-2016 Haulmont.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

class CubaDropTomcat extends DefaultTask {

    def tomcatRootDir = project.cuba.tomcat.dir
    def listeningPort = '8787'
    def waitTimeout = 20

    CubaDropTomcat() {
        setDescription('Deletes local Tomcat')
        setGroup('Deployment')
    }

    @TaskAction
    def deploy() {
        if (!tomcatRootDir) {
            tomcatRootDir = project.cuba.tomcat.dir
        }
        File dir = new File(tomcatRootDir)
        if (dir.exists()) {
            project.logger.info "[CubaDropTomcat] deleting $dir"
            // stop
            def binDir = "${tomcatRootDir}/bin"

            if (new File(binDir).exists()) {
                ant.exec(osfamily: 'windows', dir: "${binDir}", executable: 'cmd.exe', spawn: true) {
                    env(key: 'NOPAUSE', value: true)
                    arg(line: '/c start call_and_exit.bat shutdown.bat')
                }
                ant.exec(osfamily: 'unix', dir: "${binDir}", executable: '/bin/sh') {
                    arg(line: 'shutdown.sh')
                }
                // wait and delete
                ant.waitfor(maxwait: waitTimeout, maxwaitunit: 'second', checkevery: 2, checkeveryunit: 'second') {
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