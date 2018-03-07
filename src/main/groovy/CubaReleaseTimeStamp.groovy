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
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFiles
import org.gradle.api.tasks.TaskAction

import java.text.SimpleDateFormat

/**
 * Generates <b>release.timestamp</b> and <b>release.number</b> files by artifact version and optional VCS version
 */
class CubaReleaseTimeStamp extends DefaultTask {

    String releaseTimeStampPath
    String releaseNumberPath

    String artifactVersion = project.cuba.artifact.version
    Boolean isSnapshot = project.cuba.artifact.isSnapshot
    String buildVcsNumber = project.rootProject.hasProperty('buildVcsNumber') ?
        project.rootProject['buildVcsNumber'] : null

    CubaReleaseTimeStamp() {
        setDescription('Generates release timestamp and release number files with optional VCS version')
        setGroup('Util')

        // set default task dependsOn
        dependsOn(project.tasks.getByPath(JavaPlugin.COMPILE_JAVA_TASK_NAME))
        project.tasks.getByPath(JavaPlugin.CLASSES_TASK_NAME).dependsOn(this)
    }

    @InputDirectory
    File getInputDirectory() {
        return new File("$project.projectDir/src")
    }

    @OutputFiles
    List getOutputFiles() {
        return [new File(releaseTimeStampPath), new File(releaseNumberPath)]
    }

    @TaskAction
    void generateReleaseFiles() {
        if (!releaseNumberPath)
            throw new IllegalStateException('Not specified releaseNumberPath param for CubaReleaseTimeStamp');

        if (!releaseTimeStampPath)
            throw new IllegalStateException('Not specified releaseTimeStampPath param for CubaReleaseTimeStamp');

        if (!artifactVersion)
            throw new IllegalStateException('Not specified artifactVersion param for CubaReleaseTimeStamp');

        if (isSnapshot == null)
            throw new IllegalStateException('Not specified isSnapshot flag for CubaReleaseTimeStamp');

        def releaseDate = new Date()
        def timeStamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(releaseDate)

        String releaseNumber = artifactVersion
        if (isSnapshot) {
            if (StringUtils.isNotBlank(buildVcsNumber))
                releaseNumber = releaseNumber + '.' + buildVcsNumber
            releaseNumber = releaseNumber + '-SNAPSHOT'
        }

        def releaseFile = new File(releaseTimeStampPath)
        def releaseNumberFile = new File(releaseNumberPath)

        releaseFile.delete()
        releaseFile.write(timeStamp)

        releaseNumberFile.delete()
        releaseNumberFile.write(releaseNumber)

        project.logger.info("Release timestamp: $timeStamp")
        project.logger.info("Release number: $releaseNumber")
    }
}