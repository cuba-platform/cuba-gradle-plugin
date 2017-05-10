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

package com.haulmont.gradle.utils

import groovy.transform.CompileStatic
import org.gradle.api.IllegalDependencyNotation
import org.gradle.api.logging.Logger

import java.nio.charset.StandardCharsets

@CompileStatic
class BOMVersions {
    protected Map<String, String> rules = new LinkedHashMap<>()
    protected Set<String> unusedRules = new HashSet<>()

    protected Logger logger

    BOMVersions(Logger logger) {
        this.logger = logger
    }

    String getAt(String versionSpec) {
        return getByVersionSpec(versionSpec)
    }

    String putAt(String moduleSpec, String version) {
        return putBOMRule(moduleSpec, version)
    }

    void define(Map<String, String> map) {
        for (Map.Entry<String, String> entry : map.entrySet()) {
            putBOMRule(entry.key, entry.value)
        }
    }

    void load(File rulesFile) {
        logger.info("[CubaPlugin] Import BOM rules file $rulesFile")

        rulesFile.withInputStream {
            load(it)
        }
    }

    void load(InputStream bomInputStream) {
        def configReader = new InputStreamReader(bomInputStream, StandardCharsets.UTF_8)
        def properties = new Properties()
        properties.load(configReader)

        for (entry in properties.entrySet()) {
            def bomKey = String.valueOf(entry.key).replace("/", ":")
            def bomValue = String.valueOf(entry.value ?: "").trim()

            rules.put(bomKey, bomValue)
        }

        for (entry in properties.entrySet()) {
            def bomValue = String.valueOf(entry.value ?: "").trim()

            if (bomValue.startsWith('${') && bomValue.endsWith('}')) {
                def bomKey = String.valueOf(entry.key).replace("/", ":")
                def bomValueKey = bomValue.substring(2, bomValue.length() - 1).replace("/", ":")
                bomValue = rules.get(bomValueKey)

                rules.put(bomKey, bomValue)
            }
        }

        unusedRules.addAll(properties.keySet() as Set<String>)
    }

    String putBOMRule(String moduleSpec, String version) {
        if (moduleSpec.indexOf(':') < 0) {
            moduleSpec = moduleSpec + ':'
        }
        unusedRules.add(moduleSpec)

        return rules.put(moduleSpec, version)
    }

    String getByVersionSpec(String versionSpec) {
        String version = rules.get(versionSpec)
        if (version == null) {
            int sep = versionSpec.indexOf(':')
            if (sep < 0) {
                return ''
            } else {
                return getByGroupModule(versionSpec.substring(0, sep), versionSpec.substring(sep + 1))
            }
        }
        return versionSpec + ':' + version
    }

    String getByGroupModule(String group, String module) {
        String version = getVersion(group, module);
        if (version == null) {
            throw new IllegalDependencyNotation("There is no rule in BOM for dependency: '$group:$module'")
        }
        return group + ':' + module + ':' + version
    }

    String getVersion(String group, String module) {
        String rule = group + ':' + module
        String version = rules.get(rule)

        if (version != null) {
            unusedRules.remove(rule)
        }
        return version
    }

    Set<String> getUnusedRules() {
        return Collections.unmodifiableSet(unusedRules)
    }
}