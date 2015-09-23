import org.gradle.api.Project

/**
 *
 * @author gorbunkov
 * @version $Id$
 */
class CubaPluginExtension {

    final String CUBA_COPYRIGHT = '''Copyright (c) 2008-$today.year Haulmont. All rights reserved.
Use is subject to license terms, see http://www.cuba-platform.com/license for details.'''

    Project project

    TomcatConfiguration tomcat = new TomcatConfiguration()

    IdeConfiguration ide = new IdeConfiguration()

    ArtifactConfiguration artifact = new ArtifactConfiguration()

    UploadRepositoryConfiguration uploadRepository = new UploadRepositoryConfiguration()

    CubaPluginExtension(Project project) {
        this.project = project
        tomcat.dir = project.rootDir.absolutePath + '/../tomcat'
        ide.copyright = CUBA_COPYRIGHT
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

    class TomcatConfiguration {
        String dir
        String port
        String debugPort
        String shutdownPort
    }

    class IdeConfiguration {
        String copyright
        String vcs
    }

    class ArtifactConfiguration {
        String group
        String version
        boolean isSnapshot
    }

    class UploadRepositoryConfiguration {
        String url
        String user
        boolean password
    }
}
