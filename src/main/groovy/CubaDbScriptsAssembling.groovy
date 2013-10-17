/*
 * Copyright (c) 2008-2013 Haulmont. All rights reserved.
 * Use is subject to license terms, see http://www.cuba-platform.com/license for details.
 */

import org.gradle.api.DefaultTask
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction

/**
 * @author krivopustov
 * @version $Id$
 */

class CubaDbScriptsAssembling extends DefaultTask {

    def moduleAlias

    CubaDbScriptsAssembling() {
        setDescription('Gathers database scripts from module and its dependencies')
        setGroup('Database')
    }

    @OutputDirectory
    def File getOutputDirectory() {
        return project.file("${project.buildDir}/db")
    }

    @InputFiles
    def FileCollection getSourceFiles() {
        return project.fileTree(new File(project.projectDir, 'db'), {
            exclude '**/.*'
        })
    }

    @TaskAction
    def assemble() {
        if (project.configurations.getAsMap().dbscripts) {
            project.logger.info '>>> project has dbscripts'
            File dir = new File("${project.buildDir}/db")
            if (dir.exists()) {
                project.logger.info ">>> delete $dir.absolutePath"
                project.delete(dir)
            }
            project.configurations.dbscripts.files.each { dep ->
                project.logger.info ">>> copy db from: $dep.absolutePath"
                project.copy {
                    from project.zipTree(dep.absolutePath)
                    into dir
                }
            }
            File srcDbDir = new File(project.projectDir, 'db')
            project.logger.info ">>> srcDbDir: $srcDbDir.absolutePath"
            if (srcDbDir.exists() && dir.exists()) {
                def moduleDirName = moduleAlias
                if (!moduleDirName) {
                    def lastName = Arrays.asList(dir.list()).sort().last()
                    def num = lastName.substring(0, 2).toInteger()
                    moduleDirName = "${Math.max(50, num + 10)}-${project.rootProject.name}"
                }
                project.copy {
                    project.logger.info ">>> copy db from: $srcDbDir.absolutePath"
                    from srcDbDir
                    into "${project.buildDir}/db/${moduleDirName}"
                }
            }
        }
    }
}