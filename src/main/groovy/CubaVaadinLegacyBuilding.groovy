/*
 * Copyright (c) 2008-2013 Haulmont. All rights reserved.
 * Use is subject to license terms, see http://www.cuba-platform.com/license for details.
 */

import org.gradle.api.DefaultTask
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
 * Build widget set for Vaadin 6.6.x and older 6 version
 *
 * @author artamonov
 * @version $Id$
 */
class CubaVaadinLegacyBuilding extends DefaultTask {

    String widgetSetsDir
    List widgetSetModules = []
    String widgetSetClass
    Map compilerArgs

    boolean printCompilerClassPath = false

    def inheritedArtifacts

    private def excludes = []

    private def defaultCompilerArgs = [
            '-style': 'OBF',
            '-localWorkers': Runtime.getRuntime().availableProcessors(),
            '-logLevel': 'INFO'
    ]

    private def compilerJvmArgs = new HashSet([
            '-Xmx512m', '-Xss8m', '-XX:MaxPermSize=256m', '-Djava.awt.headless=true'
    ])

    CubaVaadinLegacyBuilding() {
        setDescription('Builds GWT widgetset for Vaadin 6')
        setGroup('Web resources')
    }

    @TaskAction
    def buildWidgetSet() {
        if (!widgetSetsDir)
            throw new IllegalStateException('Please specify \'String widgetSetsDir\' for build GWT')

        if (!widgetSetClass)
            throw new IllegalStateException('Please specify \'String widgetSetClass\' for build GWT')

        if (!widgetSetModules || widgetSetModules.isEmpty())
            throw new IllegalStateException('Please specify not empty \'Collection widgetSetModules\' for build GWT')

        File widgetSetsDirectory = new File(this.widgetSetsDir)
        if (widgetSetsDirectory.exists())
            widgetSetsDirectory.deleteDir()

        widgetSetsDirectory.mkdir()

        Collection compilerClassPath = collectClassPathEntries()
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

    @InputFiles
    @SkipWhenEmpty
    @Optional
    def FileCollection getSourceFiles() {
        def fileCollection = []
        if (widgetSetModules) {
            widgetSetModules.each { def module ->
                def sourcesRoot = new File((File) module.projectDir, 'src')

                for (File file : project.fileTree(sourcesRoot, { exclude '**/.*' }))
                    fileCollection.add(file)
            }
        }
        return new SimpleFileCollection(fileCollection)
    }

    def excludeJars(String... artifacts) {
        excludes.addAll(artifacts)
    }

    def jvmArgs(String... jvmArgs) {
        compilerJvmArgs.addAll(Arrays.asList(jvmArgs))
    }

    private static class InheritedArtifact {
        def name
        def jarFile
    }

    boolean excludedArtifact(String name) {
        for (def artifactName : excludes)
            if (name.contains(artifactName))
                return true
        return false
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

    protected Collection collectClassPathEntries() {
        def gwtBuildingArtifacts = []
        def compilerClassPath = new LinkedHashSet()
        if (project.configurations.findByName('gwtBuilding')) {
            gwtBuildingArtifacts = project.configurations.gwtBuilding.resolvedConfiguration.getResolvedArtifacts()
            def validationApiArtifact = gwtBuildingArtifacts.find { a -> a.name == 'validation-api' }
            if (validationApiArtifact) {
                File validationSrcDir = validationApiArtifact.file
                compilerClassPath.add(validationSrcDir)
            }
        }
        def providedArtefacts = project.configurations.provided.resolvedConfiguration.getResolvedArtifacts()

        if (inheritedArtifacts) {
            def inheritedWidgetSets = []
            def inheritedSources = []
            for (def artifactName : inheritedArtifacts) {
                def artifact = providedArtefacts.find { it.name == artifactName }
                if (artifact) {
                    println("Use inherited artifact " + artifact.name)

                    inheritedWidgetSets.add(new InheritedArtifact(name: artifactName, jarFile: artifact.file))
                } else {
                    println("[ERROR] Ignored inherited artifact ${artifactName}. Add it to provided configuration")
                }

                def artifactSource = gwtBuildingArtifacts.find { it.name == artifactName }
                if (artifactSource) {
                    println("Found inherited artifact sources " + artifact.name)

                    inheritedSources.add(new InheritedArtifact(name: artifactName, jarFile: artifactSource.file))
                } else {
                    println("[ERROR] Could not find inherited artifact sources " + artifactName)
                }
            }

            for (InheritedArtifact toolkit : inheritedWidgetSets) {
                def toolkitArtifact = providedArtefacts.find { it.name == toolkit.name }
                if (toolkitArtifact) {
                    File toolkitJar = toolkitArtifact.file
                    mainClasspath.add(0, toolkitJar)
                }
            }

            for (InheritedArtifact sourceArtifact : inheritedSources) {
                compilerClassPath.add(sourceArtifact.jarFile)
            }
        }

        if (widgetSetModules) {
            if (!(widgetSetModules instanceof Collection)) {
                widgetSetModules = Collections.singletonList(widgetSetModules)
            }

            for (def widgetSetModule : widgetSetModules) {
                SourceSet widgetSetModuleSourceSet = widgetSetModule.sourceSets.main

                compilerClassPath.addAll(widgetSetModuleSourceSet.java.srcDirs)
                compilerClassPath.add(widgetSetModuleSourceSet.output.classesDir)
                compilerClassPath.add(widgetSetModuleSourceSet.output.resourcesDir)
            }
        }

        SourceSet mainSourceSet = project.sourceSets.main

        compilerClassPath.addAll(mainSourceSet.java.srcDirs)
        compilerClassPath.add(mainSourceSet.output.classesDir)
        compilerClassPath.add(mainSourceSet.output.resourcesDir)
        compilerClassPath.addAll(
                mainSourceSet.compileClasspath.findAll {
                    !excludedArtifact(it.name)
                }
        )

        if (widgetSetModules) {
            // after modules add compile dependencies
            for (def widgetSetModule : widgetSetModules) {
                SourceSet widgetSetModuleSourceSet = widgetSetModule.sourceSets.main
                compilerClassPath.addAll(
                        widgetSetModuleSourceSet.compileClasspath.findAll {
                            !excludedArtifact(it.name)
                        }
                )
            }
        }

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