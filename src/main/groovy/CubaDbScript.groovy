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

import org.gradle.api.tasks.TaskAction

/**
 */
class CubaDbScript extends CubaDbTask {

    def scripts = []

    @TaskAction
    def runScripts() {
        init()

        for (def script in scripts) {
            File scriptFile
            if (script instanceof File) {
                scriptFile = script
            } else if (script instanceof String) {
                scriptFile = project.file(script)
            } else
                throw new IllegalArgumentException("Unable to run script $script")

            project.logger.warn("Executing SQL script: ${scriptFile.absolutePath}")

            executeSqlScript(scriptFile)
        }
    }

    def script(def scriptObject) {
        scripts.add(scriptObject)
    }
}