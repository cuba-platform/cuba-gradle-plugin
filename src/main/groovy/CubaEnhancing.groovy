import com.haulmont.gradle.api.CubaEnhancingTask

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

/**
 * Enhances entity classes specified in persistence xml
 */
class CubaEnhancing extends CubaEnhancingTask {

    CubaEnhancing() {
        setDescription('Enhances persistent classes')
        setGroup('Compile')

        srcRoot = 'src'
        classesRoot = 'main'
        sourceSet = project.sourceSets.main

        // set default task dependsOn
        setDependsOn(project.getTasksByName('compileJava', false))
        project.getTasksByName('classes', false).each { it.dependsOn(this) }
    }
}