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

package com.haulmont.gradle.utils;

import com.google.common.base.Splitter;
import org.apache.commons.lang3.StringUtils;
import org.gradle.api.Project;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SpringProfileSpecificNameResolver {

    private static final String ACTIVE_PROFILES_PROPERTY_NAME = "spring.profiles.active";
    private static final String UNIX_ENV_ACTIVE_PROFILES_PROPERTY_NAME = "spring_profiles_active";
    private String activeProfiles;

    public SpringProfileSpecificNameResolver(Project project) {
        activeProfiles = WebXmlUtils.getParamValueFromWebXml(project, ACTIVE_PROFILES_PROPERTY_NAME);
        if (StringUtils.isEmpty(activeProfiles)) {
            activeProfiles = System.getProperty(ACTIVE_PROFILES_PROPERTY_NAME);
        }
        if (StringUtils.isEmpty(activeProfiles)) {
            activeProfiles = System.getenv(ACTIVE_PROFILES_PROPERTY_NAME);
        }
        if (StringUtils.isEmpty(activeProfiles)) {
            activeProfiles = System.getenv(UNIX_ENV_ACTIVE_PROFILES_PROPERTY_NAME);
        }
    }

    public SpringProfileSpecificNameResolver(String activeProfiles) {
        this.activeProfiles = activeProfiles;
    }

    /**
     * Returns a list containing the given base name and then one derived name for each active profile,
     * e.g. for {@code app.properties} base name and {@code dev,foo} active profiles the result will be:
     * <pre>
     *     classpath:com/company/demo/app.properties
     *     classpath:com/company/demo/dev-app.properties
     *     classpath:com/company/demo/foo-app.properties
     * </pre>
     */
    public List<String> getDerivedNames(String baseName) {
        if (activeProfiles == null || activeProfiles.isEmpty()) {
            return Collections.singletonList(baseName);
        }

        List<String> list = new ArrayList<>();
        list.add(baseName);
        String normalizedBaseName = baseName.replace('\\', '/');
        for (String activeProfile : Splitter.on(',').omitEmptyStrings().trimResults().split(activeProfiles)) {
            String name;
            int pos = normalizedBaseName.lastIndexOf('/');
            if (pos == -1) {
                pos = normalizedBaseName.lastIndexOf(':');
            }
            if (pos == -1) {
                name = activeProfile + "-" + normalizedBaseName;
            } else {
                StringBuilder sb = new StringBuilder(normalizedBaseName);
                sb.insert(pos + 1, activeProfile + "-");
                name = sb.toString();
            }
            list.add(name);
        }
        return list;
    }
}
