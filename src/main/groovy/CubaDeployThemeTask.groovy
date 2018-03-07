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
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

class CubaDeployThemeTask extends DefaultTask {

    String appName = null

    CubaDeployThemeTask() {
        setDescription('Deploy scss styles to Tomcat')
        setGroup('Web resources')
    }

    @InputDirectory
    File getSourceFiles() {
        return new File(project.buildDir, 'web/VAADIN/themes')
    }

    @OutputDirectory
    File getOutputDirectory() {
        String targetAppName = getTargetAppName()

        return new File("${project.cuba.tomcat.dir}/webapps/$targetAppName/VAADIN/themes")
    }

    @TaskAction
    void deployThemes() {
        String targetAppName = getTargetAppName()

        project.copy {
            from new File(project.buildDir, 'web/VAADIN/themes')
            into "${project.cuba.tomcat.dir}/webapps/$targetAppName/VAADIN/themes"
        }
    }

    protected String getTargetAppName() {
        String targetAppName = appName
        if (targetAppName == null) {
            def deployTask = project.tasks.findByName(CubaPlugin.DEPLOY_TASK_NAME)
            if (deployTask instanceof CubaDeployment) {
                targetAppName = deployTask.appName
            }
        }

        if (targetAppName == null) {
            throw new IllegalStateException('Please specify \'appName\' for deploy themes task')
        }

        targetAppName
    }
}