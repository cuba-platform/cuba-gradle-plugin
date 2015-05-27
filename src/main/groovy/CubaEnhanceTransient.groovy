/*
 * Copyright (c) 2008-2013 Haulmont. All rights reserved.
 * Use is subject to license terms, see http://www.cuba-platform.com/license for details.
 */

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFiles
import org.gradle.api.tasks.TaskAction

import java.util.regex.Pattern

/**
 * Enhances specified transient entity classes
 *
 * @author krivopustov
 * @version $Id$
 */
class CubaEnhanceTransient extends DefaultTask {

    String metadataXml
    String packageRegExp

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
        getClassNames(metadataXml).collect { name ->
            new File("$project.buildDir/classes/main/${name.replace('.', '/')}.class")
        }
    }

    @OutputFiles
    def List getOutputFiles() {
        getClassNames(metadataXml).collect { name ->
            new File("$project.buildDir/enhanced-classes/main/${name.replace('.', '/')}.class")
        }
    }

    @TaskAction
    def enhanceClasses() {
        def classes = getClassNames(metadataXml)
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

    private List getClassNames(String metadataXml) {
        if (!metadataXml)
            throw new IllegalArgumentException('metadataXml property is not specified')
        File f = new File(metadataXml)
        if (f.exists()) {
            def metadata = new XmlParser().parse(f)
            def mm = metadata.'metadata-model'[0]
            List allClasses = mm.'class'.collect { it.value()[0] }
            if (packageRegExp) {
                Pattern pattern = Pattern.compile(packageRegExp)
                return allClasses.findAll { it.matches(pattern) }
            } else
                return allClasses
        } else {
            logger.error("File $metadataXml doesn't exist")
            throw new IllegalArgumentException("File $metadataXml doesn't exist")
        }
    }
}