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

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskAction

/**
 * Enhances entity classes specified in persistence xml.
 *
 * @deprecated enhancing is performed by {@link CubaEnhancingAction} now
 */
@Deprecated
class CubaEnhancingTask extends DefaultTask {

    String persistenceConfig
    String metadataConfig

    @Deprecated
    String metadataXml

    String metadataPackageRegExp

    File customClassesDir

    protected String srcRoot
    protected String classesRoot
    protected SourceSet sourceSet

    CubaEnhancingTask() {
    }

    @TaskAction
    def enhanceClasses() {
        project.logger.info('The \"enhance\" task is deprecated and should be removed from build script')
    }
}