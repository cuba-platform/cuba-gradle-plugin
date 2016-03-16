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
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 */
class CubaDbScriptsAssembling extends DefaultTask {
    String moduleAlias

    CubaDbScriptsAssembling() {
        setDescription('Gathers database scripts from module and its dependencies')
        setGroup('Database')
    }

    @OutputDirectory
    File getOutputDirectory() {
        return new File("${project.buildDir}/db")
    }

    @InputFiles
    FileCollection getSourceFiles() {
        return project.fileTree(new File(project.projectDir, 'db'), {
            exclude '*.*'
        })
    }

    @TaskAction
    void assemble() {
        Configuration dbscripts = project.configurations.findByName('dbscripts')
        if (dbscripts) {
            project.logger.info '[CubaDbScriptsAssembling] project has dbscripts'
            def dir = new File("${project.buildDir}/db")
            if (dir.exists()) {
                project.logger.info "[CubaDbScriptsAssembling] delete $dir.absolutePath"
                project.delete(dir)
            }
            dir.mkdir()

            dbscripts.resolvedConfiguration.resolvedArtifacts.each { artifact ->
                project.logger.info "[CubaDbScriptsAssembling] copy db from: $artifact.file.absolutePath"
                project.copy {
                    from project.zipTree(artifact.file.absolutePath)
                    into dir
                }
            }

            def srcDbDir = new File(project.projectDir, 'db')
            project.logger.info "[CubaDbScriptsAssembling] srcDbDir: $srcDbDir.absolutePath"
            if (srcDbDir.exists() && dir.exists()) {
                def moduleDirName = moduleAlias
                if (!moduleDirName) {
                    def moduleNames = Arrays.asList(dir.list()).sort()
                    if (!moduleNames.empty) {
                        def lastName = moduleNames.last()
                        def num = lastName.substring(0, 2).toInteger()
                        moduleDirName = "${Math.max(50, num + 10)}-${project.rootProject.name}"
                    }
                }
                if (moduleDirName) {
                    project.copy {
                        project.logger.info "[CubaDbScriptsAssembling] copy db from: $srcDbDir.absolutePath"
                        from srcDbDir
                        into "${project.buildDir}/db/${moduleDirName}"
                    }
                }
            }
        }
    }
}