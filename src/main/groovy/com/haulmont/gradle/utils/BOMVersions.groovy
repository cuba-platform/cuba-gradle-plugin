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

import org.gradle.api.IllegalDependencyNotation
import org.gradle.api.logging.Logger

class BOMVersions {
    protected Map<String, String> rules = new HashMap<>()
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

    void rules(Map<String, String> map) {
        for (e in map) {
            putBOMRule(e.key, e.value)
        }
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