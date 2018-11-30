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

package com.haulmont.gradle.polymer;

import com.google.common.collect.Lists;
import com.moowork.gradle.node.task.NodeTask;
import org.gradle.api.tasks.OutputFile;

import java.io.File;

import static com.haulmont.gradle.polymer.CubaInstallGeneratorsTask.GENERATION_DIR;

public class CubaListGeneratorsTask extends NodeTask {

    public static final String NAME = "listGenerators";

    public CubaListGeneratorsTask() {
        setDescription("List front-end client generators");
        setGroup("Node");
        setScript(new File(getProject().getProjectDir(), GENERATION_DIR + "/node_modules/@cuba-platform/front-generator/bin/gen-cuba-front"));
        setArgs(Lists.newArrayList("list", "-s", "generation/generators.json"));
        setDependsOn(this.getProject().getTasks().withType(CubaInstallGeneratorsTask.class));
    }


    @OutputFile
    protected File getOutputFile() {
        return new File(getProject().getProjectDir(), GENERATION_DIR + "/generators.json");
    }
}
