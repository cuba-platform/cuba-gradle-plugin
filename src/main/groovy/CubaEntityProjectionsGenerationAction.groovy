/*
 * Copyright (c) 2008-2018 Haulmont.
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


import com.haulmont.gradle.enhance.CubaEntityProjectionWrapperCreator
import groovy.io.FileType
import javassist.ClassPool
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.SourceSet

import java.nio.file.Path

/**
 * Gradle action that runs during compilation phase.
 */
class CubaEntityProjectionsGenerationAction implements Action<Task> {

    protected final Project project

    protected final String srcRoot
    protected final String classesRoot
    protected final SourceSet sourceSet
    protected final Boolean enableProjections

    CubaEntityProjectionsGenerationAction(Project project, String sourceSet) {
        this.project = project

        project.logger.debug("Source set: {}", sourceSet)

        def mainSourceSet = sourceSet == 'main'

        srcRoot = mainSourceSet ? 'src' : 'test'
        classesRoot = mainSourceSet ? 'main' : 'test'
        this.sourceSet = mainSourceSet ? project.sourceSets.main : project.sourceSets.test

        this.enableProjections = project.getProperties()
    }

    @Override
    void execute(Task task) {
        project.logger.info("Projections for source set {}: {}", sourceSet, enableProjections?"enabled":"disabled")
        if (enableProjections) {
            performAction()
        }
    }

    protected void performAction() {
        generateEntityProjections()
    }
    /**
     * Generate entity projection implementations using JavaAssist.
     */
    protected void generateEntityProjections() {
        def logger = project.logger
        def classesDir = sourceSet.java.outputDir

        ClassPool pool = ClassPool.getDefault();

        sourceSet.compileClasspath.forEach({File f ->
            logger.debug("Adding compile path entry: {}", f.getAbsolutePath())
            pool.insertClassPath(f.getAbsolutePath())
        })
        logger.debug("Adding source set classes dir: {}", classesDir.getAbsolutePath())
        pool.insertClassPath(classesDir.getAbsolutePath())

        CubaEntityProjectionWrapperCreator wrapperCreator = new CubaEntityProjectionWrapperCreator(pool, classesDir.getAbsolutePath(), logger)

        //There is no good criteria to figure out which class should be processed
        //Should run this action only in GLOBAL project
        classesDir.eachFileRecurse(FileType.FILES) { File file ->
            Path path = classesDir.toPath().relativize(file.toPath())
            String name = path.findAll().join('.')
            name = name.substring(0, name.lastIndexOf('.'))
            wrapperCreator.run(name)
        }
    }
}
