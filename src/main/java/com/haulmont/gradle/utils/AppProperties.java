/*
 * Copyright (c) 2008-2020 Haulmont.
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

package com.haulmont.gradle.utils;

import org.apache.commons.lang3.StringUtils;

import java.util.Locale;
import java.util.Properties;

public class AppProperties {
    protected final Properties properties;

    public AppProperties(Properties properties) {
        this.properties = properties;
    }

    public String getProperty(String key) {
        String value = System.getProperty(key);
        if (StringUtils.isEmpty(value)) {
            value = System.getProperty(key.toLowerCase(Locale.ROOT));
        }
        if (StringUtils.isEmpty(value)) {
            value = System.getProperty(key.toUpperCase(Locale.ROOT));
        }
        String envKey = key.replace('.', '_');
        if (StringUtils.isEmpty(value)) {
            value = System.getenv(envKey.toLowerCase(Locale.ROOT));
        }
        if (StringUtils.isEmpty(value)) {
            value = System.getenv(envKey.toUpperCase(Locale.ROOT));
        }
        if (StringUtils.isEmpty(value)) {
            value = properties.getProperty(key);
        }
        return value;
    }
}
