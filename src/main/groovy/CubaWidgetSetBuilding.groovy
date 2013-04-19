/*
 * Copyright (c) 2012 Haulmont Technology Ltd. All Rights Reserved.
 * Haulmont Technology proprietary and confidential.
 * Use is subject to license terms.
 */

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.file.FileCollection
import org.gradle.api.internal.file.collections.SimpleFileCollection
import org.gradle.api.logging.LogLevel
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
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
    List<Project> widgetSetModules = []
    Map compilerArgs

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
            throw new IllegalStateException('Please specify \'String widgetSetsDir\' for build GWT')

        if (!widgetSetClass)
            throw new IllegalStateException('Please specify \'String widgetSetClass\' for build GWT')

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

    @InputFiles @SkipWhenEmpty @Optional
    def FileCollection getSourceFiles() {
        def sources = []
        def files = new ArrayList<File>()

        sources.addAll(project.sourceSets.main.java.srcDirs)
        sources.addAll(project.sourceSets.main.output.classesDir)
        sources.addAll(project.sourceSets.main.output.resourcesDir)

        Configuration widgetSetBuildingConfiguration = project.configurations.findByName('widgetSetBuilding')
        if (widgetSetBuildingConfiguration) {
            for (Project module in widgetSetModules) {
                sources.addAll(module.sourceSets.main.java.srcDirs)
                sources.addAll(module.sourceSets.main.output.classesDir)
                sources.addAll(module.sourceSets.main.output.resourcesDir)
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
        println(compilerJvmArgs)

        return new LinkedList(compilerJvmArgs)
    }

    protected List collectCompilerArgs(warPath) {
        List args = []

        args.add('-war')
        args.add(warPath)

        for (def entry : defaultCompilerArgs.entrySet()) {
            args.add(entry.getKey())
            args.add(getCompilerArg(entry.getKey()))
        }

        args.add(widgetSetClass)

        println('GWT Compiler args: ')
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
        return excludes.find { it.name.contains(name) } != null
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
                sb.append(String.valueOf(classPathEntry)).append("\n")
            }
            project.logger.debug("GWT Compiler ClassPath: \n${sb.toString()}")
        }

        return compilerClassPath
    }
}