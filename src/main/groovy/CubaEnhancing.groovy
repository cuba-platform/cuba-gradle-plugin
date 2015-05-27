/*
 * Copyright (c) 2008-2013 Haulmont. All rights reserved.
 * Use is subject to license terms, see http://www.cuba-platform.com/license for details.
 */

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFiles
import org.gradle.api.tasks.TaskAction

/**
 * Enhances entity classes specified in persistence xml
 *
 * @author krivopustov
 * @version $Id$
 */
class CubaEnhancing extends DefaultTask {

    String persistenceXml

    CubaEnhancing() {
        setDescription('Enhances persistent classes')
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
        getClassNames(persistenceXml).collect { name ->
            new File("$project.buildDir/classes/main/${name.replace('.', '/')}.class")
        }
    }

    @OutputFiles
    def List getOutputFiles() {
        getClassNames(persistenceXml).collect { name ->
            new File("$project.buildDir/enhanced-classes/main/${name.replace('.', '/')}.class")
        }
    }

    private List getClassNames(String persistenceXml) {
        if (!persistenceXml)
            throw new IllegalArgumentException('persistenceXml property is not specified')
        File f = new File(persistenceXml)
        if (f.exists()) {
            def persistence = new XmlParser().parse(f)
            def pu = persistence.'persistence-unit'[0]
            pu.'class'.collect { it.value()[0] }
        } else {
            logger.error("File $persistenceXml doesn't exist")
            throw new IllegalArgumentException("File $persistenceXml doesn't exist")
        }
    }

    @TaskAction
    def enhanceClasses() {
        if (persistenceXml) {
            File f = new File(persistenceXml)
            if (f.exists()) {
                def persistence = new XmlParser().parse(f)
                def pu = persistence.'persistence-unit'[0]
                def properties = pu.properties[0]
                if (!properties)
                    properties = pu.appendNode('properties')
                def prop = properties.find { it.@name == 'openjpa.DetachState' }
                if (!prop)
                    properties.appendNode('property', [name: 'openjpa.DetachState', value: 'loaded(DetachedStateField=true, DetachedStateManager=true)'])

                File tmpDir = new File(project.buildDir, 'tmp')
                tmpDir.mkdirs()
                File tmpFile = new File(tmpDir, 'persistence.xml')
                new XmlNodePrinter(new PrintWriter(new FileWriter(tmpFile))).print(persistence)

                project.javaexec {
                    main = 'org.apache.openjpa.enhance.PCEnhancer'
                    classpath(
                            project.sourceSets.main.compileClasspath,
                            project.sourceSets.main.output.classesDir,
                            project.configurations.enhance
                    )
                    args('-properties', tmpFile, '-d', "$project.buildDir/enhanced-classes/main")
                }
            }
        }
    }
}