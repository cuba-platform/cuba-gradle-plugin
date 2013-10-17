/*
 * Copyright (c) 2008-2013 Haulmont. All rights reserved.
 * Use is subject to license terms, see http://www.cuba-platform.com/license for details.
 */

import org.gradle.api.DefaultTask
import org.gradle.api.file.FileCollection
import org.gradle.api.internal.file.collections.SimpleFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.SkipWhenEmpty
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
    List dependencyModules = []
    String widgetSetClass
    Map compilerArgs

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

    protected List collectClassPathEntries() {
        def gwtBuildingArtifacts = []
        def compilerClassPath = []
        if (project.configurations.findByName('gwtBuilding')) {
            gwtBuildingArtifacts = project.configurations.gwtBuilding.resolvedConfiguration.getResolvedArtifacts()
            def validationApiArtifact = gwtBuildingArtifacts.find { a -> a.name == 'validation-api' }
            if (validationApiArtifact) {
                File validationSrcDir = validationApiArtifact.file
                compilerClassPath.add(validationSrcDir)
            }
        }
        def providedArtefacts = project.configurations.provided.resolvedConfiguration.getResolvedArtifacts()

        def mainClasspath = project.sourceSets.main.compileClasspath.findAll { !excludedArtifact(it.name) }

        if (inheritedArtifacts) {
            def inheritedWidgetSets = []
            def inheritedSources = []
            for (def artifactName : inheritedArtifacts) {
                def artifact = providedArtefacts.find { it.name == artifactName }
                if (artifact)
                    inheritedWidgetSets.add(new InheritedArtifact(name: artifactName, jarFile: artifact.file))
                def artifactSource = gwtBuildingArtifacts.find { it.name == artifactName }
                if (artifactSource)
                    inheritedSources.add(new InheritedArtifact(name: artifactName, jarFile: artifactSource.file))
            }

            // unpack inhertited toolkit (widget sets)
            for (InheritedArtifact toolkit : inheritedWidgetSets) {
                def toolkitArtifact = providedArtefacts.find { it.name == toolkit.name }
                if (toolkitArtifact) {
                    File toolkitJar = toolkitArtifact.file
                    File toolkitClassesDir = new File("${project.buildDir}/tmp/${toolkit.name}-classes")
                    project.copy {
                        from project.zipTree(toolkitJar)
                        into toolkitClassesDir
                    }
                    mainClasspath.add(0, toolkitClassesDir)
                }
            }

            for (InheritedArtifact sourceArtifact : inheritedSources)
                compilerClassPath.add(sourceArtifact.jarFile)
        }

        if (widgetSetModules) {
            if (!(widgetSetModules instanceof Collection))
                widgetSetModules = Collections.singletonList(widgetSetModules)

            for (def widgetSetModule : widgetSetModules) {
                compilerClassPath.add(new File(widgetSetModule.projectDir, 'src'))
                compilerClassPath.add(widgetSetModule.sourceSets.main.output.classesDir)
            }
        }

        if (dependencyModules) {
            for (def module : dependencyModules) {
                compilerClassPath.add(new File((File) module.projectDir, 'src'))
                compilerClassPath.add(module.sourceSets.main.output.classesDir)
            }
        }

        compilerClassPath.add(project.sourceSets.main.output.classesDir)

        compilerClassPath.addAll(mainClasspath)

        return compilerClassPath
    }
}