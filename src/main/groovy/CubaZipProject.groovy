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

import org.apache.tools.ant.DirectoryScanner
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

/**
 *
 */
class CubaZipProject extends DefaultTask {

    def excludeFromZip = []
    def includeToZip = []
    def zipDir = "${project.rootDir}"

    @TaskAction
    def zipProject() {

        def tmpDir = "${project.buildDir}/zip"
        def tmpRootDir = "${project.buildDir}/zip/${project.name}"

        def includeToZip = ['.gitignore']
        includeToZip += this.includeToZip

        def excludeFromZip = [
                'build',
                '.iml',
                '.ipr',
                '.iws'
        ]
        excludeFromZip += this.excludeFromZip

        String zipFilePath = "${zipDir}/${project.name}.zip"

        project.logger.info("[CubaZipProject] Deleting old archive")
        // to exclude recursive packing
        project.delete(zipFilePath)

        // Due to GRADLE-1883
        DirectoryScanner.removeDefaultExclude("**/.gitignore")

        project.logger.info("[CubaZipProject] Packing files from: ${project.rootDir}")
        project.copy {
            from '.'
            into tmpRootDir
            exclude { details ->
                String name = details.file.name
                if (isFileMatched(name, includeToZip)) return false
                // eclipse project files, gradle, git, idea (directory based), Mac OS files
                if (name.startsWith(".")) return true
                return isFileMatched(name, excludeFromZip)
            }
        }
        project.copy {
            from 'build/hsqldb'
            into "$tmpRootDir/build/hsqldb"
        }

        ant.zip(destfile: zipFilePath, basedir: tmpDir)

        println("Zip archive has been created at '${project.file(zipFilePath).absolutePath}'")

        DirectoryScanner.resetDefaultExcludes()

        project.delete(tmpDir)
    }

    protected static boolean isFileMatched(String name, def rules) {
        for (String rule : rules) {
            if (rule.startsWith(".")) {     // extension
                if (name.endsWith(rule)) {
                    return true
                }
            } else {                        // file name
                if (name == rule) {
                    return true
                }
            }
        }
        return false
    }
}
