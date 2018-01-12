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

package com.haulmont.gradle.dependency;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ProjectDependency;

import java.util.LinkedHashSet;
import java.util.Set;

public class ProjectCollector {

    public static Set<Project> collectProjectDependencies(Project project) {
        Set<Project> result = new LinkedHashSet<>();
        collectProjectDependencies(project, result);
        return result;
    }

    private static void collectProjectDependencies(Project project, Set<Project> explored) {
        Configuration compileConfiguration = project.getConfigurations().findByName("compile");
        if (compileConfiguration != null) {
            for (Dependency dependencyItem : compileConfiguration.getAllDependencies()) {
                if (dependencyItem instanceof ProjectDependency) {
                    Project dependencyProject = ((ProjectDependency) dependencyItem).getDependencyProject();
                    if (!explored.contains(dependencyProject)) {
                        explored.add(dependencyProject);
                        collectProjectDependencies(dependencyProject, explored);
                    }
                }
            }
        }
    }
}
