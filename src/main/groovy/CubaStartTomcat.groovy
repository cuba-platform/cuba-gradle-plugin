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

import org.apache.commons.lang3.StringUtils
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

class CubaStartTomcat extends DefaultTask {

    def tomcatRootDir = project.cuba.tomcat.dir

    CubaStartTomcat() {
        setDescription('Starts local Tomcat')
        setGroup('Deployment')
    }

    @TaskAction
    def deploy() {
        if (!tomcatRootDir) {
            tomcatRootDir = project.cuba.tomcat.dir
        }

        def binDir = "${tomcatRootDir}/bin"
        project.logger.info "[CubaStartTomcat] starting $tomcatRootDir"

        def tomcatStartScript = System.getenv("CUBA_TOMCAT_START_SCRIPT")
        if (StringUtils.isBlank(tomcatStartScript)) {
            ant.exec(osfamily: 'windows', dir: "${binDir}", executable: 'cmd.exe', spawn: true) {
                env(key: 'NOPAUSE', value: true)
                if (project.hasProperty('studioJavaHome')) {
                    env(key: 'JAVA_HOME', value: project.studioJavaHome)
                    env(key: 'JRE_HOME', value: project.studioJavaHome)
                }
                arg(line: '/c start call_and_exit.bat debug.bat')
            }
            ant.exec(osfamily: 'unix', dir: "${binDir}", executable: '/bin/sh') {
                arg(line: 'debug.sh')
            }
        } else {
            println "Executing ${tomcatStartScript}"

            ant.exec(osfamily: 'windows', dir: "${binDir}", executable: tomcatStartScript, spawn: true) {
                if (project.hasProperty('studioJavaHome')) {
                    env(key: 'JAVA_HOME', value: project.studioJavaHome)
                    env(key: 'JRE_HOME', value: project.studioJavaHome)
                }
                arg(line: tomcatRootDir)
            }
            ant.exec(osfamily: 'unix', dir: "${binDir}", executable: tomcatStartScript) {
                arg(line: tomcatRootDir)
            }
        }
    }
}