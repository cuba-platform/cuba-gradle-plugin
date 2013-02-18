/*
 * Copyright (c) 2012 Haulmont Technology Ltd. All Rights Reserved.
 * Haulmont Technology proprietary and confidential.
 * Use is subject to license terms.
 */

/**
 * @author artamonov
 * @version $Id$
 */
class CubaWidgetSetBuilding extends CubaWidgetSetTask {

    def inheritedArtifacts

    private def excludes = []

    CubaWidgetSetBuilding() {
        setDescription('Builds GWT widgetset')
        setGroup('Web resources')
    }

    def excludeJars(String... artifacts) {
        excludes.addAll(artifacts)
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

    @Override
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