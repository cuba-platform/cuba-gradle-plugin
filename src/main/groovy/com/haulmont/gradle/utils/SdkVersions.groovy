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

import java.nio.charset.StandardCharsets

class SdkVersions {

    private final Map<String, VersionInfo> versions = new HashMap<>()

    private String tomcatVersion

    SdkVersions() {
        init()
    }

    private void init() {
        def sdkPomStream = SdkVersions.class.getResourceAsStream('/META-INF/sdk-pom.xml')

        sdkPomStream.withCloseable {
            def slurper = new XmlSlurper()

            def project = slurper.parse(new InputStreamReader(sdkPomStream, StandardCharsets.UTF_8))

            def properties = project.'properties'

            this.tomcatVersion = properties.'tomcat.version'.text()

            def dependencies = project.'dependencies'

            dependencies.each { d ->
                String groupId = d.'groupId'.text()
                String artifactId = d.'artifactId'.text()
                String version = d.'version'.text()

                versions.put(groupId + ':' + artifactId, new VersionInfo(groupId, artifactId, version))
            }
        }
    }

    String getTomcatVersion() {
        return tomcatVersion
    }

    String getUberJarVersion() {
        return versions.get('com.haulmont.uberjar:uberjar').version
    }

    String getFrontServletVersion() {
        return versions.get('com.haulmont.frontservlet:frontservlet').version
    }

    String getWagonHttpVersion() {
        return versions.get('org.apache.maven.wagon:wagon-http').version
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