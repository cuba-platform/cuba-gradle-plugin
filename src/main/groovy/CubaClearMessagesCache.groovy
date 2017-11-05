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

/**
 *
 */
class CubaClearMessagesCache extends DefaultTask {

    def appName = 'app'

    CubaClearMessagesCache() {
        setDescription('Clears messages cache')
        setGroup('Deployment')
    }

    @TaskAction
    protected void copyTriggerFile() {
        def fileName = "${project.cuba.tomcat.dir}/temp/$appName/triggers/cuba_Messages.clearCache"
        File file = new File(fileName)
        if (!file.exists()) {
            project.logger.info "[CubaClearMessagesCache] creating $fileName"
            file.getParentFile().mkdirs()
            file.createNewFile()
        }
    }
}