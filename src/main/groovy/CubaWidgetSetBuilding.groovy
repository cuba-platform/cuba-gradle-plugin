/*
 * Copyright (c) 2008-2016 Haulmont.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.file.FileCollection
import org.gradle.api.internal.file.collections.SimpleFileCollection
import org.gradle.api.logging.LogLevel
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.*

class CubaWidgetSetBuilding extends DefaultTask {

    String widgetSetsDir
    @Input
    String widgetSetClass
    @Input
    Map compilerArgs = [:]

    @Input
    boolean strict = true
    @Input
    boolean draft = false
    @Input
    boolean disableCastChecking = false
    @Input
    int optimize = 9

    @Input
    String style = 'OBF'

    String xmx = '-Xmx768m'
    String xss = '-Xss8m'
    @Deprecated
    String xxMPS = '-XX:MaxPermSize=256m'

    String logLevel = 'ERROR'

    int workers = Math.max(Runtime.getRuntime().availableProcessors() - 1, 1)

    boolean printCompilerClassPath = false

    private def excludes = []

    def compilerJvmArgs = new LinkedHashSet(['-Djava.awt.headless=true'])

    CubaWidgetSetBuilding() {
        setDescription('Builds GWT widgetset')
        setGroup('Web resources')
        // set default task dependsOn
        dependsOn(project.tasks.getByPath(JavaPlugin.CLASSES_TASK_NAME))
    }

    @TaskAction
    def buildWidgetSet() {
        if (!widgetSetClass) {
            throw new IllegalStateException('Please specify \'String widgetSetClass\' for build widgetset')
        }

        if (!widgetSetsDir) {
            widgetSetsDir = defaultBuildDir
        }

        File widgetSetsDirectory = new File(this.widgetSetsDir)
        if (widgetSetsDirectory.exists()) {
            widgetSetsDirectory.deleteDir()
        }

        // strip gwt-unitCache
        File gwtTemp = project.file("build/gwt")
        if (!gwtTemp.exists()) {
            gwtTemp.mkdir()
        }

        File gwtJavaTmp = project.file("build/tmp/" + this.getName())
        if (gwtJavaTmp.exists()) {
            gwtJavaTmp.deleteDir()
        }
        gwtJavaTmp.mkdirs()

        File gwtWidgetSetTemp = new File(gwtTemp, 'widgetset')
        gwtWidgetSetTemp.mkdir()

        List compilerClassPath = collectClassPathEntries()
        List gwtCompilerArgs = collectCompilerArgs(gwtWidgetSetTemp.absolutePath)
        List gwtCompilerJvmArgs = collectCompilerJvmArgs(gwtJavaTmp)

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
    File getOutputDirectory() {
        if (!widgetSetsDir) {
            return new File(defaultBuildDir)
        }

        return new File(this.widgetSetsDir)
    }

    protected String getDefaultBuildDir() {
        "$project.buildDir/web/VAADIN/widgetsets"
    }

    @InputFiles @SkipWhenEmpty
    FileCollection getSourceFiles() {
        project.logger.info("Analyze source projects for widgetset building in ${project.name}")

        def sources = []
        def files = new ArrayList<File>()

        SourceSet mainSourceSet = project.sourceSets.main

        sources.addAll(mainSourceSet.java.srcDirs)
        sources.addAll(getClassesDirs(mainSourceSet))
        sources.addAll(mainSourceSet.output.resourcesDir)

        for (Project dependencyProject : collectProjectsWithDependency('vaadin-client')) {
            project.logger.info("\tFound source project ${dependencyProject.name} for widgetset building")

            SourceSet depMainSourceSet = dependencyProject.sourceSets.main

            sources.addAll(depMainSourceSet.java.srcDirs)
            sources.addAll(getClassesDirs(depMainSourceSet))
            sources.addAll(depMainSourceSet.output.resourcesDir)
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

    protected Collection<File> getClassesDirs(SourceSet sourceSet) {
        return sourceSet.output.classesDirs.files
    }

    protected List collectCompilerJvmArgs(File gwtJavaTmp) {
        List args = new ArrayList(compilerJvmArgs)

        args.add(xmx)
        args.add(xss)

        args.add('-Djava.io.tmpdir=' + gwtJavaTmp.getAbsolutePath())

        if (project.logger.isInfoEnabled()) {
            println('JVM Args:')
            print('\t')
            println(args)
        }

        return args
    }

    protected List collectCompilerArgs(warPath) {
        List args = []

        args.add('-war')
        args.add(warPath)

        if (strict) {
            args.add('-strict')
        }

        if (draft) {
            args.add('-draftCompile')
        }

        if (disableCastChecking) {
            args.add('-XdisableCastChecking')
        }

        def gwtCompilerArgs = [:]
        gwtCompilerArgs.put('-style', style)
        gwtCompilerArgs.put('-logLevel', logLevel)
        gwtCompilerArgs.put('-localWorkers', workers)
        gwtCompilerArgs.put('-optimize', optimize)

        if (compilerArgs) {
            gwtCompilerArgs.putAll(compilerArgs)
        }

        for (def entry : gwtCompilerArgs.entrySet()) {
            args.add(entry.key)
            args.add(entry.value)
        }

        args.add(widgetSetClass)

        if (project.logger.isInfoEnabled()) {
            println('GWT Compiler args: ')
            print('\t')
            println(args)
        }

        return args
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
                        def dependencyCompile = dependencyProject.configurations.findByName('compile')
                        if (dependencyCompile) {
                            def artifacts = dependencyCompile.resolvedConfiguration.resolvedArtifacts
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

    protected List<File> collectClassPathEntries() {
        List<File> compilerClassPath = new ArrayList<>()

        def compileConfiguration = project.configurations.findByName('compile')
        if (compileConfiguration) {
            for (dependencyProject in collectProjectsWithDependency('vaadin-shared')) {
                SourceSet dependencyMainSourceSet = dependencyProject.sourceSets.main

                compilerClassPath.addAll(dependencyMainSourceSet.java.srcDirs)
                compilerClassPath.addAll(getClassesDirs(dependencyMainSourceSet))
                compilerClassPath.add(dependencyMainSourceSet.output.resourcesDir)

                project.logger.debug(">> Widget set building Module: ${dependencyProject.name}")
            }
        }

        SourceSet mainSourceSet = project.sourceSets.main

        compilerClassPath.addAll(mainSourceSet.java.srcDirs)
        compilerClassPath.addAll(getClassesDirs(mainSourceSet))
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