/*
 * Copyright (c) 2008-2013 Haulmont. All rights reserved.
 * Use is subject to license terms, see http://www.cuba-platform.com/license for details.
 */

import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * @author krivopustov
 * @version $Id$
 */
class CubaDbScriptsAssembling extends DefaultTask {
    String moduleAlias

    CubaDbScriptsAssembling() {
        setDescription('Gathers database scripts from module and its dependencies')
        setGroup('Database')
    }

    @OutputDirectory
    File getOutputDirectory() {
        return new File("${project.buildDir}/db")
    }

    @InputFiles
    FileCollection getSourceFiles() {
        return project.fileTree(new File(project.projectDir, 'db'), {
            exclude '*.*'
        })
    }

    @TaskAction
    void assemble() {
        Configuration dbscripts = project.configurations.findByName('dbscripts')
        if (dbscripts) {
            project.logger.info '>>> project has dbscripts'
            def dir = new File("${project.buildDir}/db")
            if (dir.exists()) {
                project.logger.info ">>> delete $dir.absolutePath"
                project.delete(dir)
            }
            dir.mkdir()

            dbscripts.files.each { dep ->
                project.logger.info ">>> copy db from: $dep.absolutePath"
                project.copy {
                    from project.zipTree(dep.absolutePath)
                    into dir
                }
            }

            def srcDbDir = new File(project.projectDir, 'db')
            project.logger.info ">>> srcDbDir: $srcDbDir.absolutePath"
            if (srcDbDir.exists() && dir.exists()) {
                def moduleDirName = moduleAlias
                if (!moduleDirName) {
                    def moduleNames = Arrays.asList(dir.list()).sort()
                    if (!moduleNames.empty) {
                        def lastName = moduleNames.last()
                        def num = lastName.substring(0, 2).toInteger()
                        moduleDirName = "${Math.max(50, num + 10)}-${project.rootProject.name}"
                    }
                }
                if (moduleDirName) {
                    project.copy {
                        project.logger.info ">>> copy db from: $srcDbDir.absolutePath"
                        from srcDbDir
                        into "${project.buildDir}/db/${moduleDirName}"
                    }
                }
            }
        }
    }
}