/*
 * Copyright (c) 2008-2018 Haulmont.
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
 */

package com.haulmont.gradle.task.widgetset;

import org.apache.commons.io.FileUtils;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.FileCollection;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.*;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class CubaWidgetSetBuilding extends AbstractCubaWidgetSetTask {

    protected String widgetSetsDir;
    @Input
    protected String widgetSetClass;
    @Input
    protected Map<String, Object> compilerArgs = new HashMap<>();
    @Input
    protected boolean strict = true;
    @Input
    protected boolean draft = false;
    @Input
    protected boolean disableCastChecking = false;
    @Input
    protected int optimize = 9;
    @Input
    protected String style = "OBF";

    protected String xmx = "-Xmx768m";
    protected String xss = "-Xss8m";
    @Deprecated
    protected String xxMPS = "-XX:MaxPermSize=256m";

    protected String logLevel = "ERROR";

    protected int workers = Math.max(Runtime.getRuntime().availableProcessors() - 1, 1);

    protected boolean printCompilerClassPath = false;

    public CubaWidgetSetBuilding() {
        setDescription("Builds GWT widgetset");
        setGroup("Web resources");
        // set default task dependsOn
        dependsOn(getProject().getTasks().getByPath(JavaPlugin.CLASSES_TASK_NAME));
    }

    @TaskAction
    public void buildWidgetSet() {
        if (widgetSetClass == null || widgetSetClass.isEmpty()) {
            throw new IllegalStateException("Please specify \"String widgetSetClass\" for build widgetset");
        }

        if (widgetSetsDir == null || widgetSetsDir.isEmpty()) {
            widgetSetsDir = getDefaultBuildDir();
        }

        File widgetSetsDirectory = new File(widgetSetsDir);
        if (widgetSetsDirectory.exists()) {
            FileUtils.deleteQuietly(widgetSetsDirectory);
        }

        // strip gwt-unitCache
        File gwtTemp = getProject().file("build/gwt");
        if (!gwtTemp.exists()) {
            gwtTemp.mkdir();
        }

        File gwtJavaTmp = getProject().file("build/tmp/" + getName());
        if (gwtJavaTmp.exists()) {
            FileUtils.deleteQuietly(gwtJavaTmp);
        }
        gwtJavaTmp.mkdirs();

        File gwtWidgetSetTemp = new File(gwtTemp, "widgetset");
        gwtWidgetSetTemp.mkdir();

        List<File> compilerClassPath = collectClassPathEntries();
        List<String> gwtCompilerArgs = collectCompilerArgs(gwtWidgetSetTemp.getAbsolutePath());
        List<String> gwtCompilerJvmArgs = collectCompilerJvmArgs(gwtJavaTmp);

        getProject().javaexec(javaExecSpec -> {
            javaExecSpec.setMain("com.google.gwt.dev.Compiler");
            javaExecSpec.setClasspath(getProject().files(compilerClassPath));
            javaExecSpec.setArgs(gwtCompilerArgs);
            javaExecSpec.setJvmArgs(gwtCompilerJvmArgs);
        });

        FileUtils.deleteQuietly(new File(gwtWidgetSetTemp, "WEB-INF"));

        gwtWidgetSetTemp.renameTo(widgetSetsDirectory);
    }

    @InputFiles
    @SkipWhenEmpty
    public FileCollection getSourceFiles() {
        getProject().getLogger().info("Analyze source projects for widgetset building in %s", getProject().getName());

        List<File> sources = new ArrayList<>();
        List<File> files = new ArrayList<>();

        SourceSet mainSourceSet = getSourceSet(getProject(), "main");

        sources.addAll(mainSourceSet.getJava().getSrcDirs());
        sources.addAll(getClassesDirs(mainSourceSet));
        sources.add(mainSourceSet.getOutput().getResourcesDir());

        for (Project dependencyProject : collectProjectsWithDependency("vaadin-client")) {
            getProject().getLogger().info("\tFound source project %s for widgetset building", dependencyProject.getName());

            SourceSet depMainSourceSet = getSourceSet(dependencyProject, "main");

            sources.addAll(depMainSourceSet.getJava().getSrcDirs());
            sources.addAll(getClassesDirs(depMainSourceSet));
            sources.add(depMainSourceSet.getOutput().getResourcesDir());
        }

        sources.forEach(sourceDir -> {
            if (sourceDir.exists()) {
                getProject()
                        .fileTree(sourceDir, f ->
                                f.setExcludes(Collections.singleton("**/.*")))
                        .forEach(files::add);
            }
        });

        return getProject().files(files);
    }

    @OutputDirectory
    public File getOutputDirectory() {
        if (widgetSetsDir == null || widgetSetsDir.isEmpty()) {
            return new File(getDefaultBuildDir());
        }
        return new File(widgetSetsDir);
    }

    protected String getDefaultBuildDir() {
        return getProject().getBuildDir().toString() + "/web/VAADIN/widgetsets";
    }

    protected List<File> collectClassPathEntries() {
        List<File> compilerClassPath = new ArrayList<>();

        Configuration compileConfiguration = getProject().getConfigurations().findByName("compile");
        if (compileConfiguration != null) {
            for (Project dependencyProject : collectProjectsWithDependency("vaadin-shared")) {
                SourceSet dependencyMainSourceSet = getSourceSet(dependencyProject, "main");

                compilerClassPath.addAll(dependencyMainSourceSet.getJava().getSrcDirs());
                compilerClassPath.addAll(getClassesDirs(dependencyMainSourceSet));
                compilerClassPath.add(dependencyMainSourceSet.getOutput().getResourcesDir());

                getProject().getLogger().debug(">> Widget set building Module: %s", dependencyProject.getName());
            }
        }

        SourceSet mainSourceSet = getSourceSet(getProject(), "main");

        compilerClassPath.addAll(mainSourceSet.getJava().getSrcDirs());
        compilerClassPath.addAll(getClassesDirs(mainSourceSet));
        compilerClassPath.add(mainSourceSet.getOutput().getResourcesDir());

        List<File> compileClassPathArtifacts = StreamSupport
                .stream(mainSourceSet.getCompileClasspath().spliterator(), false)
                .filter(f -> includedArtifact(f.getName()) && !compilerClassPath.contains(f))
                .collect(Collectors.toList());
        compilerClassPath.addAll(compileClassPathArtifacts);

        if (getProject().getLogger().isEnabled(LogLevel.DEBUG)) {
            StringBuilder sb = new StringBuilder();
            for (File classPathEntry : compilerClassPath) {
                sb.append('\t')
                        .append(String.valueOf(classPathEntry))
                        .append('\n');
            }

            getProject().getLogger().debug("GWT Compiler ClassPath: \n%s", sb.toString());
            getProject().getLogger().debug("");
        } else if (printCompilerClassPath) {
            StringBuilder sb = new StringBuilder();
            for (File classPathEntry : compilerClassPath) {
                sb.append('\t')
                        .append(String.valueOf(classPathEntry))
                        .append('\n');
            }
            System.out.println("GWT Compiler ClassPath: \n" + sb.toString());
            System.out.println();
        }

        return compilerClassPath;
    }

    protected List collectCompilerArgs(String warPath) {
        List args = new ArrayList();

        args.add("-war");
        args.add(warPath);

        if (strict) {
            args.add("-strict");
        }

        if (draft) {
            args.add("-draftCompile");
        }

        if (disableCastChecking) {
            args.add("-XdisableCastChecking");
        }

        Map<String, Object> gwtCompilerArgs = new HashMap<>();

        gwtCompilerArgs.put("-style", style);
        gwtCompilerArgs.put("-logLevel", logLevel);
        gwtCompilerArgs.put("-localWorkers", workers);
        gwtCompilerArgs.put("-optimize", optimize);

        if (compilerArgs != null) {
            gwtCompilerArgs.putAll(compilerArgs);
        }

        for (Map.Entry<String, Object> entry : gwtCompilerArgs.entrySet()) {
            args.add(entry.getKey());
            args.add(entry.getValue());
        }

        args.add(widgetSetClass);

        if (getProject().getLogger().isInfoEnabled()) {
            System.out.println("GWT Compiler args: ");
            System.out.print('\t');
            System.out.println(args);
        }

        return args;
    }

    protected List<String> collectCompilerJvmArgs(File gwtJavaTmp) {
        List<String> args = new ArrayList<>(compilerJvmArgs);

        args.add(xmx);
        args.add(xss);
        args.add("-Djava.io.tmpdir=" + gwtJavaTmp.getAbsolutePath());

        if (getProject().getLogger().isInfoEnabled()) {
            System.out.println("JVM Args:");
            System.out.print('\t');
            System.out.println(args);
        }

        return args;
    }

    public void setWidgetSetsDir(String widgetSetsDir) {
        this.widgetSetsDir = widgetSetsDir;
    }

    public void setWidgetSetClass(String widgetSetClass) {
        this.widgetSetClass = widgetSetClass;
    }

    public void setCompilerArgs(Map<String, Object> compilerArgs) {
        this.compilerArgs = compilerArgs;
    }

    public void setStrict(boolean strict) {
        this.strict = strict;
    }

    public void setDraft(boolean draft) {
        this.draft = draft;
    }

    public void setDisableCastChecking(boolean disableCastChecking) {
        this.disableCastChecking = disableCastChecking;
    }

    public void setOptimize(int optimize) {
        this.optimize = optimize;
    }

    public void setStyle(String style) {
        this.style = style;
    }

    public void setXmx(String xmx) {
        this.xmx = xmx;
    }

    public void setXss(String xss) {
        this.xss = xss;
    }

    @Deprecated
    public void setXxMPS(String xxMPS) {
        this.xxMPS = xxMPS;
    }

    public void setLogLevel(String logLevel) {
        this.logLevel = logLevel;
    }

    public void setWorkers(int workers) {
        this.workers = workers;
    }

    public void setPrintCompilerClassPath(boolean printCompilerClassPath) {
        this.printCompilerClassPath = printCompilerClassPath;
    }
}
