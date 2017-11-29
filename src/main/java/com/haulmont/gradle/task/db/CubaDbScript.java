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

package com.haulmont.gradle.task.db;

import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class CubaDbScript extends CubaDbTask {
    List<Object> scripts = new ArrayList<>();

    @TaskAction
    public void runScripts() {
        init();

        for (Object script : scripts) {
            File scriptFile = getProject().file(script);

            getProject().getLogger().lifecycle("Executing SQL script: {}", scriptFile.getAbsolutePath());

            executeSqlScript(scriptFile);
        }
    }

    void script(Object script) {
        scripts.add(script);
    }
}