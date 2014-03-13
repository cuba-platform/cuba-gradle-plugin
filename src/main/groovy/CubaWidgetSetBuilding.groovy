/*
 * Copyright (c) 2008-2013 Haulmont. All rights reserved.
 * Use is subject to license terms, see http://www.cuba-platform.com/license for details.
 */

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.file.FileCollection
import org.gradle.api.internal.file.collections.SimpleFileCollection
import org.gradle.api.logging.LogLevel
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskAction

/**
 * @author artamonov
 * @version $Id$
 */
class CubaWidgetSetBuilding extends DefaultTask {

    String widgetSetsDir
    String widgetSetClass
    Map compilerArgs
    boolean printCompilerClassPath = false
    boolean strict = true

    private def excludes = []

    private def defaultCompilerArgs = [
            '-style': 'OBF',
            '-localWorkers': Runtime.getRuntime().availableProcessors(),
            '-logLevel': 'INFO'
    ]

    private def compilerJvmArgs = new HashSet([
            '-Xmx512m', '-Xss8m', '-XX:MaxPermSize=256m', '-Djava.awt.headless=true'
    ])

    CubaWidgetSetBuilding() {
        setDescription('Builds GWT widgetset')
        setGroup('Web resources')
        // set default task dependsOn
        setDependsOn(project.getTasksByName('classes', false))
    }

    @TaskAction
    def buildWidgetSet() {
        if (!widgetSetsDir)
            throw new IllegalStateException('Please specify \'String widgetSetsDir\' for build widgetset')

        if (!widgetSetClass)
            throw new IllegalStateException('Please specify \'String widgetSetClass\' for build widgetset')

        File widgetSetsDirectory = new File(this.widgetSetsDir)
        if (widgetSetsDirectory.exists())
            widgetSetsDirectory.deleteDir()

        widgetSetsDirectory.mkdir()

        List compilerClassPath = collectClassPathEntries()
        List gwtCompilerArgs = collectCompilerArgs(widgetSetsDirectory.absolutePath)
        List gwtCompilerJvmArgs = collectCompilerJvmArgs()

        project.javaexec {
            main = 'com.google.gwt.dev.Compiler'
            classpath = new SimpleFileCollection(compilerClassPath)
            args = gwtCompilerArgs
            jvmArgs = gwtCompilerJvmArgs
        }

        new File(widgetSetsDirectory, 'WEB-INF').deleteDir()
    }

    @OutputDirectory
    def File getOutputDirectory() {
        return new File(this.widgetSetsDir)
    }

    @InputFiles @SkipWhenEmpty
    def FileCollection getSourceFiles() {
        project.logger.info("Analyze source projects for widgetset building in ${project.name}")

        def sources = []
        def files = new ArrayList<File>()

        sources.addAll(project.sourceSets.main.java.srcDirs)
        sources.addAll(project.sourceSets.main.output.classesDir)
        sources.addAll(project.sourceSets.main.output.resourcesDir)

        Configuration widgetSetBuildingConfiguration = project.configurations.findByName('widgetSetBuilding')
        if (widgetSetBuildingConfiguration) {
            for (def dependencyItem in widgetSetBuildingConfiguration.dependencies) {
                if (dependencyItem instanceof ProjectDependency) {
                    Project dependencyProject = dependencyItem.dependencyProject

                    Configuration compileConf = dependencyProject.configurations.getByName('compile')
                    def artifacts = compileConf.resolvedConfiguration.getResolvedArtifacts()
                    def vaadinClientArtifact = artifacts.find { ResolvedArtifact artifact ->
                        artifact.name == 'vaadin-client'
                    }

                    if (vaadinClientArtifact) {
                        project.logger.info("\tFound source project ${dependencyProject.name} for widgetset building")

                        sources.addAll(dependencyProject.sourceSets.main.java.srcDirs)
                        sources.addAll(dependencyProject.sourceSets.main.output.classesDir)
                        sources.addAll(dependencyProject.sourceSets.main.output.resourcesDir)
                    }
                }
            }
        }

        sources.each { File sourceDir ->
            if (sourceDir.exists()) {
                project.fileTree(sourceDir, { exclude '**/.*' }).each { File sourceFile ->
                    files.add(sourceFile)
                }
            }
        }

        return new SimpleFileCollection(files)
    }

    def jvmArgs(String... jvmArgs) {
        compilerJvmArgs.addAll(Arrays.asList(jvmArgs))
    }

    protected List collectCompilerJvmArgs() {
        println('JVM Args:')
        print('\t')
        println(compilerJvmArgs)

        return new LinkedList(compilerJvmArgs)
    }

    protected List collectCompilerArgs(warPath) {
        List args = []

        args.add('-war')
        args.add(warPath)

        if (strict)
            args.add('-strict')

        for (def entry : defaultCompilerArgs.entrySet()) {
            args.add(entry.getKey())
            args.add(getCompilerArg(entry.getKey()))
        }

        args.add(widgetSetClass)

        println('GWT Compiler args: ')
        print('\t')
        println(args)

        return args
    }

    protected def getCompilerArg(argName) {
        if (compilerArgs && compilerArgs.containsKey(argName))
            return compilerArgs.get(argName)
        else
            return defaultCompilerArgs.get(argName)
    }

    def excludeJars(String... artifacts) {
        excludes.addAll(artifacts)
    }

    boolean excludedArtifact(String name) {
        return excludes.find { it.contains(name) } != null
    }

    protected List collectClassPathEntries() {
        def compilerClassPath = []

        Configuration widgetSetBuildingConfiguration = project.configurations.findByName('widgetSetBuilding')

        if (widgetSetBuildingConfiguration) {
            // try to add sources to all artifacts in widgetSetBuilding
            for (Dependency dependencyItem in widgetSetBuildingConfiguration.dependencies.collect()) {
                // add sources dependency to widgetSetBuilding configuration
                if (!(dependencyItem instanceof ProjectDependency)) {
                    project.dependencies {
                        widgetSetBuilding(
                                group: dependencyItem.group,
                                name: dependencyItem.name,
                                version: dependencyItem.version,
                                classifier: 'sources'
                        )
                    }
                }
            }

            def widgetSetBuildingResolvedArtifacts = widgetSetBuildingConfiguration.resolvedConfiguration.getResolvedArtifacts()
            for (def dependencyItem in widgetSetBuildingConfiguration.dependencies) {
                if (dependencyItem instanceof ProjectDependency) {
                    Project dependencyProject = dependencyItem.dependencyProject

                    SourceSet dependencyMainSourceSet = dependencyProject.sourceSets.main

                    compilerClassPath.addAll(dependencyMainSourceSet.java.srcDirs)
                    compilerClassPath.add(dependencyMainSourceSet.output.classesDir)
                    compilerClassPath.add(dependencyMainSourceSet.output.resourcesDir)
                    compilerClassPath.addAll(
                            dependencyMainSourceSet.compileClasspath.findAll {
                                !excludedArtifact(it.name) && !compilerClassPath.contains(it)
                            }
                    )

                    project.logger.debug("Widget set building Module: ${dependencyProject.name}")

                } else if (dependencyItem instanceof ModuleDependency) {
                    // find resolved artifacts and add it to compiler classpath
                    dependencyItem.getArtifacts().each { def dependencyArtifact ->
                        def resolvedDependencyArtifact = widgetSetBuildingResolvedArtifacts.find {
                            a -> a.name == dependencyArtifact.name && dependencyArtifact.classifier == a.classifier
                        }

                        if (resolvedDependencyArtifact) {
                            compilerClassPath.add(resolvedDependencyArtifact.file)

                            project.logger.debug("Widget set building Artifact: ${resolvedDependencyArtifact.file}")
                        }
                    }
                }
            }
        }

        SourceSet mainSourceSet = project.sourceSets.main

        compilerClassPath.addAll(mainSourceSet.java.srcDirs)
        compilerClassPath.add(mainSourceSet.output.classesDir)
        compilerClassPath.add(mainSourceSet.output.resourcesDir)
        compilerClassPath.addAll(
                mainSourceSet.compileClasspath.findAll {
                    !excludedArtifact(it.name) && !compilerClassPath.contains(it)
                }
        )

        if (project.logger.isEnabled(LogLevel.DEBUG)) {
            def sb = new StringBuilder()
            for (def classPathEntry : compilerClassPath) {
                sb.append('\t' + String.valueOf(classPathEntry)).append("\n")
            }
            project.logger.debug("GWT Compiler ClassPath: \n${sb.toString()}")
            project.logger.debug("")
        } else if (printCompilerClassPath) {
            def sb = new StringBuilder()
            for (def classPathEntry : compilerClassPath) {
                sb.append('\t' + String.valueOf(classPathEntry)).append("\n")
            }
            println("GWT Compiler ClassPath: \n${sb.toString()}")
            println("")
        }

        return compilerClassPath
    }
}