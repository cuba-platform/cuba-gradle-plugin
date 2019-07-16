/*
 * Copyright (c) 2008-2019 Haulmont.
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

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ScriptSplitter {
    protected String delimiter;

    public ScriptSplitter(String delimiter) {
        this.delimiter = delimiter;
    }

    public List<String> split(String script) {
        String qd = Pattern.quote(delimiter);
        String[] commands = script.split("(?<!" + qd + ")" + qd + "(?!" + qd + ")"); // regex for ^: (?<!\^)\^(?!\^)
        return Arrays.stream(commands)
                .map(s -> s.replace(delimiter + delimiter, delimiter)).collect(Collectors.toList());
    }
}
