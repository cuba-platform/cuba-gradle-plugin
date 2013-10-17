/*
 * Copyright (c) 2008-2013 Haulmont. All rights reserved.
 * Use is subject to license terms, see http://www.cuba-platform.com/license for details.
 */

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFiles
import org.gradle.api.tasks.TaskAction

/**
 * Enhances specified transient entity classes
 *
 * @author krivopustov
 * @version $Id$
 */
class CubaEnhanceTransient extends DefaultTask {

    def classes = []

    CubaEnhanceTransient() {
        setDescription('Enhances transient entities')
        setGroup('Compile')
        // set default task dependsOn
        setDependsOn(project.getTasksByName('compileJava', false))
        project.getTasksByName('classes', false).each { it.dependsOn(this) }
        // add default assist dependency on cuba-plugin
        def enhanceConfiguration = project.configurations.findByName("enhance")
        if (!enhanceConfiguration)
            project.configurations.create("enhance").extendsFrom(project.configurations.getByName("provided"))

        project.dependencies {
            enhance(CubaPlugin.getArtifactDefinition())
        }
    }

    @InputFiles
    def List getInputFiles() {
        classes.collect { name ->
            new File("$project.buildDir/classes/main/${name.replace('.', '/')}.class")
        }
    }

    @OutputFiles
    def List getOutputFiles() {
        classes.collect { name ->
            new File("$project.buildDir/enhanced-classes/main/${name.replace('.', '/')}.class")
        }
    }

    @TaskAction
    def enhanceClasses() {
        project.logger.info(">>> enhancing classes: $classes")
        project.javaexec {
            main = 'CubaTransientEnhancer'
            classpath(
                    project.sourceSets.main.compileClasspath,
                    project.sourceSets.main.output.classesDir,
                    project.configurations.enhance
            )
            args(classes + "-o $project.buildDir/enhanced-classes/main")
        }
    }
}