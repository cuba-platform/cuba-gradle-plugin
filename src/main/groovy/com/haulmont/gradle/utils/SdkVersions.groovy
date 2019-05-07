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

package com.haulmont.gradle.utils

import org.gradle.api.Project

import java.nio.charset.StandardCharsets

class SdkVersions {

    private final Map<String, VersionInfo> versions = new HashMap<>()

    private String tomcatVersion

    SdkVersions(Project project) {
        init(project)
    }

    private void init(Project project) {
        def sdkPomStream = SdkVersions.class.getResourceAsStream('/META-INF/sdk-pom.xml')

        project.logger.debug("Loading SDK dependencies from /META-INF/sdk-pom.xml")

        sdkPomStream.withCloseable {
            def slurper = new XmlSlurper()

            def projectRoot = slurper.parse(new InputStreamReader(sdkPomStream, StandardCharsets.UTF_8))
            def properties = projectRoot.'properties'

            this.tomcatVersion = properties.'tomcat.version'.text()

            project.logger.debug("Loaded tomcat version: $tomcatVersion")

            def dependencies = projectRoot.'dependencies'.'dependency'
            for (d in dependencies) {
                String groupId = d.'groupId'.text()
                String artifactId = d.'artifactId'.text()
                String version = d.'version'.text()

                project.logger.debug("Loaded SDK dependency: $groupId:$artifactId:$version")

                versions.put(groupId + ':' + artifactId, new VersionInfo(groupId, artifactId, version))
            }
        }
    }

    String getTomcatVersion() {
        return tomcatVersion
    }

    VersionInfo getUberJarGav() {
        return versions.get('com.haulmont.uberjar:uberjar')
    }

    VersionInfo getFrontServletGav() {
        return versions.get('com.haulmont.frontservlet:frontservlet')
    }

    VersionInfo getWagonHttpGav() {
        return versions.get('org.apache.maven.wagon:wagon-http')
    }

    static class VersionInfo {
        private final String groupId
        private final String artifactId
        private final String version

        VersionInfo(String groupId, String artifactId, String version) {
            this.groupId = groupId
            this.artifactId = artifactId
            this.version = version
        }

        String getGroupId() {
            return groupId
        }

        String getArtifactId() {
            return artifactId
        }

        String getVersion() {
            return version
        }
    }
}