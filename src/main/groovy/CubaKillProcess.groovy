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

class CubaKillProcess extends DefaultTask {

    def port

    @TaskAction
    def void kill() {
        if (!port) {
            logger.error("Port value is needed")
            return
        }
        if ('linux'.equalsIgnoreCase(System.getProperty('os.name'))) {
            ant.exec(executable: 'sh', spawn: false) {
                arg(value: "-c")
                arg(value: "kill `lsof -i :$port | tail -n +2 | sed -e 's,[ \\t][ \\t]*, ,g' | cut -f2 -d' '`")
            }
        } else {
            logger.error("Killing by port is not supported on ${System.getProperty('os.name')} operating system")
        }
    }
}