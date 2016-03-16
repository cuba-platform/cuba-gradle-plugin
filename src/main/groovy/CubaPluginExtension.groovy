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
 *
 */

import org.gradle.api.Project

/**
 *
 */
class CubaPluginExtension {

    final static String DEFAULT_COPYRIGHT = 'TODO Copyright'

    Project project

    TomcatConfiguration tomcat

    IdeConfiguration ide

    ArtifactConfiguration artifact

    UploadRepositoryConfiguration uploadRepository

    CubaPluginExtension(Project project) {
        this.project = project

        tomcat = new TomcatConfiguration(project)
        ide = new IdeConfiguration(project)
        artifact = new ArtifactConfiguration(project)
        uploadRepository = new UploadRepositoryConfiguration(project)

        tomcat.dir = project.rootDir.absolutePath + '/../tomcat'
        ide.copyright = DEFAULT_COPYRIGHT
        uploadRepository.user = System.getenv('HAULMONT_REPOSITORY_USER')
        uploadRepository.password = System.getenv('HAULMONT_REPOSITORY_PASSWORD')
        artifact.group = 'com.company'
        artifact.version = '0.1'
        artifact.isSnapshot = true
    }

    void tomcat(Closure closure) {
        project.configure(tomcat, closure)
    }

    void ide(Closure closure) {
        project.configure(ide, closure)
    }

    void artifact(Closure closure) {
        project.configure(artifact, closure)
    }

    void uploadRepository(Closure closure) {
        project.configure(uploadRepository, closure)
    }

    @Override
    String toString() {
        def SEP = "^^"
        String res = "cuba.tomcat.dir: " + tomcat.dir + SEP;
        return res
    }

    class TomcatConfiguration {
        Project project
        String dir
        String port
        String debugPort
        String shutdownPort
        String ajpPort

        TomcatConfiguration(Project project) {
            this.project = project
        }
    }

    class IdeConfiguration {
        Project project
        String copyright
        String vcs
        String classComment

        IdeConfiguration(Project project) {
            this.project = project
        }
    }

    class ArtifactConfiguration {
        Project project
        String group
        String version
        boolean isSnapshot

        ArtifactConfiguration(Project project) {
            this.project = project
        }
    }

    class UploadRepositoryConfiguration {
        Project project
        String url
        String user
        String password

        UploadRepositoryConfiguration(Project project) {
            this.project = project
        }
    }
}
