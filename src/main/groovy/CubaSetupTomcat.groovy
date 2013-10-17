/*
 * Copyright (c) 2008-2013 Haulmont. All rights reserved.
 * Use is subject to license terms, see http://www.cuba-platform.com/license for details.
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
                into "$tomcatRootDir/tmp"
            }
        }
        new File("$tomcatRootDir/tmp").eachDirMatch(~/apache-tomcat-.*/) { File dir ->
            project.copy {
                from dir
                into tomcatRootDir
                exclude '**/webapps/*'
                exclude '**/work/*'
                exclude '**/temp/*'
            }
        }
        project.delete("$tomcatRootDir/tmp")

        project.configurations.tomcatInit.files.each { dep ->
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