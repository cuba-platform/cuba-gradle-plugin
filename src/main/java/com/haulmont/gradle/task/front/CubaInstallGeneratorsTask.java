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

package com.haulmont.gradle.task.front;

import com.moowork.gradle.node.npm.NpmTask;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputDirectory;

import java.io.File;
import java.util.Collections;

public class CubaInstallGeneratorsTask extends NpmTask {

    public static final String NAME = "installGenerators";
    public static final String GENERATION_DIR = "generation";

    public CubaInstallGeneratorsTask() {
        setDescription("Install front client generators");
        setGroup("Node");
        setWorkingDir(new File(getProject().getProjectDir(), GENERATION_DIR));
        setArgs(Collections.singletonList("install"));
    }

    @InputFile
    protected File getInputFile() {
        return new File(getProject().getProjectDir(), GENERATION_DIR + "/package.json");
    }

    @OutputDirectory
    protected File getOutputDirectory() {
        return new File(getProject().getProjectDir(), GENERATION_DIR + "/node_modules");
    }


}
