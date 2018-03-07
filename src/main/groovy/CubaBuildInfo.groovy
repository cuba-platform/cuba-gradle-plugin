/*
 * Copyright (c) 2008-2017 Haulmont.
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
 */

import org.gradle.api.DefaultTask
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat

/**
 * Task creating a build info file in the project resources.
 */
class CubaBuildInfo extends DefaultTask {

    @Input
    String buildInfoPath = "${project.buildDir}/resources/main/${project.cuba.artifact.group.replace('.', '/')}/build-info.properties"

    @Input
    String appName = project.rootProject.name

    @Input
    String artifactGroup = project.cuba.artifact.group

    @Input
    String version = project.cuba.artifact.version + (project.cuba.artifact.isSnapshot ? '-SNAPSHOT' : '')

    @Input
    Map<String, String> properties = [:]

    CubaBuildInfo() {
        setDescription('Generates build info files')
        setGroup('Util')

        dependsOn(project.tasks.getByPath(JavaPlugin.PROCESS_RESOURCES_TASK_NAME))
        project.tasks.getByPath(JavaPlugin.CLASSES_TASK_NAME).dependsOn(this)
    }

    @InputDirectory
    def getInputDirectory() {
        return new File("$project.projectDir/src")
    }

    @OutputFile
    File getOutputFile() {
        return new File(buildInfoPath)
    }

    @TaskAction
    def generateBuildInfo() {
        Date releaseDate = new Date()
        String buildDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(releaseDate)

        File buildInfoFile = new File(buildInfoPath)
        buildInfoFile.withWriter(StandardCharsets.UTF_8.name()) { writer ->
            writer.write("appName = $appName\n")
            writer.write("buildDate = $buildDate\n")
            writer.write("version = $version\n")
            writer.write("artifactGroup = $artifactGroup\n")
            if (project.hasProperty('resolvedAppComponents')) {
                try {
                    writer.write("appComponents = ${project.resolvedAppComponents.join(', ')}\n")
                } catch (e) {
                    logger.error("Cannot get information about app components: $e")
                }
            }
            this.properties.each { prop ->
                writer.write("$prop.key = $prop.value\n")
            }
        }
    }
}
