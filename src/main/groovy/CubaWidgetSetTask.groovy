/*
 * Copyright (c) 2012 Haulmont Technology Ltd. All Rights Reserved.
 * Haulmont Technology proprietary and confidential.
 * Use is subject to license terms.
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
 * @author artamonov
 * @version $Id$
 */
abstract class CubaWidgetSetTask extends DefaultTask {

    String widgetSetsDir
    List widgetSetModules = []
    List dependencyModules = []
    String widgetSetClass
    Map compilerArgs

    private def defaultCompilerArgs = [
            '-style': 'OBF',
            '-localWorkers': Runtime.getRuntime().availableProcessors(),
            '-logLevel': 'INFO'
    ]

    private def compilerJvmArgs = new HashSet([
            '-Xmx512m', '-Xss8m', '-XX:MaxPermSize=256m', '-Djava.awt.headless=true'
    ])

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

    protected List collectClassPathEntries() {
        def compilerClassPath = []
        if (project.configurations.findByName('gwtBuilding')) {
            def gwtBuildingArtifacts = project.configurations.gwtBuilding.resolvedConfiguration.getResolvedArtifacts()

            def validationApiArtifact = gwtBuildingArtifacts.find { a -> a.name == 'validation-api' }
            if (validationApiArtifact) {
                File validationSrcDir = validationApiArtifact.file
                compilerClassPath.add(validationSrcDir)
            }
        }

        def moduleClassesDirs = []
        def moduleSrcDirs = []
        if (widgetSetModules) {
            for (def module : widgetSetModules) {
                moduleSrcDirs.add(new File((File) module.projectDir, 'src'))
                moduleClassesDirs.add(module.sourceSets.main.output.classesDir)
            }
        }

        if (dependencyModules) {
            for (def module : dependencyModules) {
                moduleSrcDirs.add(new File((File) module.projectDir, 'src'))
                moduleClassesDirs.add(module.sourceSets.main.output.classesDir)
            }
        }

        compilerClassPath.addAll(moduleSrcDirs)
        compilerClassPath.addAll(moduleClassesDirs)

        compilerClassPath.add(project.sourceSets.main.compileClasspath.getAsPath())
        compilerClassPath.add(project.sourceSets.main.output.classesDir)
        return compilerClassPath
    }
}