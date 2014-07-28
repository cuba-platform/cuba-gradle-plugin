/*
 * Copyright (c) 2008-2013 Haulmont. All rights reserved.
 * Use is subject to license terms, see http://www.cuba-platform.com/license for details.
 */

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.file.FileCollection
import org.gradle.api.internal.file.collections.SimpleFileCollection
import org.gradle.api.logging.LogLevel
import org.gradle.api.tasks.*

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
        if (!widgetSetClass) {
            throw new IllegalStateException('Please specify \'String widgetSetClass\' for build widgetset')
        }

        if (!widgetSetsDir) {
            widgetSetsDir = "$project.buildDir/web/VAADIN/widgetsets"
        }

        File widgetSetsDirectory = new File(this.widgetSetsDir)
        if (widgetSetsDirectory.exists()) {
            widgetSetsDirectory.deleteDir()
        }

        // strip gwt-unitCache
        File gwtTemp = project.file("build/gwt")
        if (gwtTemp.exists()) {
            gwtTemp.deleteDir()
        }
        gwtTemp.mkdir()

        File gwtWidgetSetTemp = new File(gwtTemp, 'widgetset')
        gwtWidgetSetTemp.mkdir()

        List compilerClassPath = collectClassPathEntries()
        List gwtCompilerArgs = collectCompilerArgs(gwtWidgetSetTemp.absolutePath)
        List gwtCompilerJvmArgs = collectCompilerJvmArgs()

        project.javaexec {
            main = 'com.google.gwt.dev.Compiler'
            classpath = new SimpleFileCollection(compilerClassPath)
            args = gwtCompilerArgs
            jvmArgs = gwtCompilerJvmArgs
        }

        new File(gwtWidgetSetTemp, 'WEB-INF').deleteDir()

        gwtWidgetSetTemp.renameTo(widgetSetsDirectory)
    }

    @OutputDirectory
    def File getOutputDirectory() {
        if (!widgetSetsDir) {
            return new File("$project.buildDir/web/VAADIN/widgetsets")
        }

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

        for (Project dependencyProject : collectProjectsWithDependency('vaadin-client')) {
            project.logger.info("\tFound source project ${dependencyProject.name} for widgetset building")

            sources.addAll(dependencyProject.sourceSets.main.java.srcDirs)
            sources.addAll(dependencyProject.sourceSets.main.output.classesDir)
            sources.addAll(dependencyProject.sourceSets.main.output.resourcesDir)
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

    void jvmArgs(String... jvmArgs) {
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

        if (strict) {
            args.add('-strict')
        }

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
        if (compilerArgs && compilerArgs.containsKey(argName)) {
            return compilerArgs.get(argName)
        } else {
            return defaultCompilerArgs.get(argName)
        }
    }

    def excludeJars(String... artifacts) {
        excludes.addAll(artifacts)
    }

    boolean excludedArtifact(String name) {
        return excludes.find { it.contains(name) } != null
    }

    protected void collectProjectsWithDependency(Project project, String dependencyName, Set<Project> explored) {
        Configuration compileConfiguration = project.configurations.findByName('compile')
        if (compileConfiguration) {
            for (def dependencyItem in compileConfiguration.allDependencies) {
                if (dependencyItem instanceof ProjectDependency) {
                    Project dependencyProject = dependencyItem.dependencyProject

                    if (!explored.contains(dependencyProject)) {
                        Configuration dependencyCompile = dependencyProject.configurations.findByName('compile')
                        if (dependencyCompile) {
                            def artifacts = dependencyCompile.resolvedConfiguration.getResolvedArtifacts()
                            def vaadinArtifact = artifacts.find { ResolvedArtifact artifact ->
                                artifact.name == dependencyName
                            }
                            if (vaadinArtifact) {
                                explored.add(dependencyProject)
                                collectProjectsWithDependency(dependencyProject, dependencyName, explored)
                            }
                        }
                    }
                }
            }
        }
    }

    protected Set<Project> collectProjectsWithDependency(String dependencyName) {
        def result = new LinkedHashSet()
        collectProjectsWithDependency(project, dependencyName, result)

        return result
    }

    protected List collectClassPathEntries() {
        def compilerClassPath = []

        Configuration compileConfiguration = project.configurations.findByName('compile')
        if (compileConfiguration) {
            for (dependencyProject in collectProjectsWithDependency('vaadin-shared')) {
                SourceSet dependencyMainSourceSet = dependencyProject.sourceSets.main

                compilerClassPath.addAll(dependencyMainSourceSet.java.srcDirs)
                compilerClassPath.add(dependencyMainSourceSet.output.classesDir)
                compilerClassPath.add(dependencyMainSourceSet.output.resourcesDir)
                compilerClassPath.addAll(
                        dependencyMainSourceSet.compileClasspath.findAll {
                            !excludedArtifact(it.name) && !compilerClassPath.contains(it)
                        }
                )

                project.logger.debug(">> Widget set building Module: ${dependencyProject.name}")
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