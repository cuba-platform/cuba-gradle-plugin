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
 */

/**
 * Enhances entity classes specified in persistence xml
 */
class CubaTestEnhancing extends CubaEnhancingTask {

    CubaTestEnhancing() {
        setDescription('Enhances persistent test classes')
        setGroup('Compile')

        srcRoot = 'test'
        classesRoot = 'test'
        sourceSet = project.sourceSets.test

        // set default task dependsOn
        setDependsOn(project.getTasksByName('compileTestJava', false))
        project.getTasksByName('testClasses', false).each { it.dependsOn(this) }

        // move enhanced classes to the beginning of test classpath
        project.sourceSets.test.runtimeClasspath =
                project.files('build/enhanced-classes/test').plus(project.sourceSets.test.runtimeClasspath)
    }
}