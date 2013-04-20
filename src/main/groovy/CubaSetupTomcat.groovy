/*
 * Copyright (c) 2011 Haulmont Technology Ltd. All Rights Reserved.
 * Haulmont Technology proprietary and confidential.
 * Use is subject to license terms.
 */

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

/**
 * @author artamonov
 * @version $Id$
 */
class CubaSetupTomcat extends DefaultTask {

    def tomcatRootDir = project.tomcatDir

    CubaSetupTomcat() {
        setDescription('Sets up local Tomcat')
        setGroup('Development server')
    }

    @TaskAction
    def setup() {
        project.configurations.tomcat.files.each { dep ->
            project.copy {
                from project.zipTree(dep.absolutePath)
                into tomcatRootDir
            }
        }
        ant.chmod(osfamily: 'unix', perm: 'a+x') {
            fileset(dir: "${tomcatRootDir}/bin", includes: '*.sh')
        }
    }
}