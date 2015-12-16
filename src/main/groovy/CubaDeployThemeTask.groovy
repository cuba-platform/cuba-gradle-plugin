/*
 * Copyright (c) 2008-2013 Haulmont. All rights reserved.
 * Use is subject to license terms, see http://www.cuba-platform.com/license for details.
 */

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * @author artamonov
 * @version $Id$
 */
class CubaDeployThemeTask extends DefaultTask {

    String appName = null

    CubaDeployThemeTask() {
        setDescription('Deploy scss styles to Tomcat')
        setGroup('Web resources')
    }

    @InputDirectory
    def File getSourceFiles() {
        new File(project.buildDir, 'web/VAADIN/themes')
    }

    @OutputDirectory
    def File getOutputDirectory() {
        String targetAppName = getTargetAppName()

        return new File("${project.cuba.tomcat.dir}/webapps/$targetAppName/VAADIN/themes")
    }

    @TaskAction
    void deployThemes() {
        String targetAppName = getTargetAppName()

        project.copy {
            from new File(project.buildDir, 'web/VAADIN/themes')
            into "${project.cuba.tomcat.dir}/webapps/$targetAppName/VAADIN/themes"
        }
    }

    protected String getTargetAppName() {
        String targetAppName = appName
        if (targetAppName == null) {
            def deployTask = project.tasks.findByName('deploy')
            if (deployTask instanceof CubaDeployment) {
                targetAppName = deployTask.appName
            }
        }

        if (targetAppName == null) {
            throw new IllegalStateException('Please specify \'appName\' for deploy themes task')
        }

        targetAppName
    }
}