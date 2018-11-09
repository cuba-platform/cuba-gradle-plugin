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

import com.vaadin.sass.internal.ScssContext
import com.vaadin.sass.internal.ScssStylesheet
import com.vaadin.sass.internal.handler.SCSSDocumentHandlerImpl
import com.vaadin.sass.internal.handler.SCSSErrorHandler
import com.yahoo.platform.yui.compressor.CssCompressor
import groovy.transform.CompileStatic
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.w3c.css.sac.CSSException
import org.w3c.css.sac.CSSParseException

import java.util.function.Consumer
import java.util.jar.JarFile
import java.util.jar.JarInputStream
import java.util.jar.Manifest
import java.util.zip.GZIPOutputStream

import static org.apache.commons.io.FileUtils.deleteQuietly

class CubaWebScssThemeCreation extends DefaultTask {

    // additional scss root from modules
    @Input
    List<File> includes = []
    @Input
    List<String> includedAppComponentIds = []

    // theme names to build
    @Input
    List<String> themes = []

    @Input
    Object scssDir = 'themes'
    Object destDir = "${project.buildDir}/web"

    @Input
    boolean compress = true
    @Deprecated
    boolean sprites = true
    @Input
    boolean cleanup = true
    @Input
    boolean gzip = true

    List<String> excludedThemes = new ArrayList<String>()
    List<String> excludePaths = new ArrayList<String>()
    List<String> doNotUnpackPaths = [
            'VAADIN/themes/valo/*.css',
            'VAADIN/themes/valo/*.css.gz',
            'VAADIN/themes/valo/favicon.ico',
            'VAADIN/themes/valo/util/readme.txt',
            'META-INF/**',
    ]

    private FileFilter dirFilter = new FileFilter() {
        @Override
        boolean accept(File pathname) {
            return pathname.isDirectory() && !pathname.name.startsWith(".")
        }
    }

    CubaWebScssThemeCreation() {
        setDescription('Compile scss styles in theme')
        setGroup('Web resources')
    }

    @OutputDirectory
    File getOutputDirectory() {
        return project.file(destDir)
    }

    @InputFiles
    FileCollection getSourceFiles() {
        def scssRoot = project.file(scssDir)
        if (!scssRoot.exists()) {
            return project.files()
        }

        def files = new ArrayList<File>()
        def themeDirs = themes.empty ?
                scssRoot.listFiles(dirFilter).toList() :
                themes.collect { new File(scssRoot, it) }

        project.fileTree(scssDir, {
            for (themeDir in themeDirs)
                include "${themeDir.name}/**"
            exclude '**/.*'
        }).each { def file ->
            files.add(file)
        }

        for (include in includes) {
            project.rootProject.fileTree(include, {
                exclude '**/.*'
            }).each {def file ->
                files.add(file)
            }
        }

        return project.files(files)
    }

    @TaskAction
    void buildThemes() {
        def stylesDirectory = project.file(scssDir)
        if (!stylesDirectory.exists()) {
            throw new FileNotFoundException("Unable to find SCSS themes root directory ${stylesDirectory.absolutePath}")
        }

        def themesTmp = new File(project.buildDir, "themes-tmp")
        if (themesTmp.exists()) {
            themesTmp.deleteDir()
        }
        themesTmp.mkdir()

        def vaadinThemesRoot = new File(themesTmp, "VAADIN/themes")
        vaadinThemesRoot.mkdir()

        def destinationDirectory = project.file(destDir)

        if (themes.empty) {
            project.logger.info("[CubaWebScssThemeCreation] scan directory '{}' for themes", stylesDirectory.absolutePath)

            themes.addAll(stylesDirectory
                    .listFiles(dirFilter)
                    .collect { it.name })
        }

        unpackVaadinAddonsThemes(themesTmp)
        unpackThemesConfDependencies(themesTmp, vaadinThemesRoot)

        // copy includes to build dir
        for (includeThemeDir in includes) {
            project.logger.info("[CubaWebScssThemeCreation] copy includes from {}", includeThemeDir.name)
            if (!includeThemeDir.exists())
                throw new GradleException("Could not found include dir ${includeThemeDir.absolutePath}")

            project.copy {
                from includeThemeDir
                into new File(vaadinThemesRoot, includeThemeDir.name)
            }
        }

        for (themeDirName in themes) {
            buildTheme(themeDirName, stylesDirectory, vaadinThemesRoot)
        }

        copyResources(themesTmp, destinationDirectory)

        if (cleanup) {
            // remove empty directories
            removeEmptyDirs(destinationDirectory)
        }

        for (themeName in excludedThemes) {
            def themeDestDir = new File(destinationDirectory, themeName)
            project.logger.info("[CubaWebScssThemeCreation] excluded theme '{}'", themeName)

            deleteQuietly(themeDestDir)
        }

        for (path in excludePaths) {
            def pathFile = new File(destinationDirectory, path)
            project.logger.info("[CubaWebScssThemeCreation] excluded path '{}'", path)

            deleteQuietly(pathFile)
        }
    }

    void unpackThemesConfDependencies(File themesTmp, File vaadinThemesRoot) {
        Configuration themesConf = project.configurations.findByName('themes')
        if (themesConf) {
            def themeArchives = collectThemeArchives(themesConf)

            for (archive in themeArchives) {
                project.logger.info("[CubaWebScssThemeCreation] unpack themes artifact {}", archive.name)

                if (!archive.name.contains('vaadin-themes')) {
                    project.copy {
                        from project.zipTree(archive)
                        into vaadinThemesRoot
                        excludes = doNotUnpackPaths.toSet()
                    }
                } else {
                    project.copy {
                        from project.zipTree(archive)
                        into themesTmp
                        include 'VAADIN/**'
                        excludes = doNotUnpackPaths.toSet()
                    }
                }
            }
        }
    }

    // unpack dependencies first
    @CompileStatic
    List<File> collectThemeArchives(Configuration themesConfiguration) {
        def firstLevelModuleDependencies = themesConfiguration.resolvedConfiguration.firstLevelModuleDependencies
        def files = new ArrayList<File>()
        def passedArtifacts = new HashSet<ResolvedArtifact>()

        for (dependency in firstLevelModuleDependencies) {
            collectThemeArchives(dependency, passedArtifacts, files)
        }

        return files
    }

    @CompileStatic
    void collectThemeArchives(ResolvedDependency dependency, Set<ResolvedArtifact> passedArtifacts,
                              List<File> files) {
        for (child in dependency.children) {
            collectThemeArchives(child, passedArtifacts, files)
        }

        for (artifact in dependency.moduleArtifacts) {
            if (passedArtifacts.contains(artifact)) {
                continue
            }

            passedArtifacts.add(artifact)

            if (artifact.file != null) {
                files.add(artifact.file)
            }
        }
    }

    void unpackVaadinAddonsThemes(File themesTmp) {
        def compileConfiguration = project.configurations.compile
        def resolvedArtifacts = compileConfiguration.resolvedConfiguration.resolvedArtifacts
        def dependentJarFiles = resolvedArtifacts.collect { it.file }.findAll { it.exists() && it.file && it.name.endsWith(".jar") }

        for (jarFile in dependentJarFiles) {
            jarFile.withInputStream { is ->
                def jarStream = new JarInputStream(is)
                def mf = jarStream.manifest
                def attributes = mf?.mainAttributes
                if (attributes) {
                    def vaadinStylesheets = attributes.getValue('Vaadin-Stylesheets')
                    if (vaadinStylesheets) {
                        project.logger.info("[CubaWebScssThemeCreation] unpack Vaadin addon styles {}", jarFile.name)
                        project.copy {
                            from project.zipTree(jarFile)
                            into themesTmp
                            include 'VAADIN/**'
                        }
                    }
                }
            }
        }
    }

    void buildTheme(String themeDirName, File stylesDirectory, File vaadinThemesRoot) {
        project.logger.info("[CubaWebScssThemeCreation] build theme '{}'", themeDirName)

        def themeDir = new File(stylesDirectory, themeDirName)
        if (!themeDir.exists()) {
            throw new FileNotFoundException("Unable to find theme directory ${themeDir.absolutePath}")
        }

        def themeBuildDir = new File(vaadinThemesRoot, themeDirName)

        project.logger.info("[CubaWebScssThemeCreation] copy theme '{}' to build directory", themeDir.name)
        // copy theme to build directory
        project.copy {
            from themeDir
            into themeBuildDir
            exclude {
                it.file.name.startsWith('.')
            }
        }

        prepareAppComponentsInclude(themeBuildDir)

        project.logger.info("[CubaWebScssThemeCreation] compile theme '{}'", themeDir.name)

        def scssFile = project.file("${themeBuildDir}/styles.scss")
        def cssFile = project.file("${themeBuildDir}/styles.css")

        compileScss(scssFile, cssFile)

        if (compress) {
            performCssCompression(themeDir, cssFile)
        }

        if (gzip) {
            project.logger.info("[CubaWebScssThemeCreation] compress css file 'styles.css'")

            def uncompressedStream = new FileInputStream(new File("${themeBuildDir}/styles.css"))
            def gzos = new GZIPOutputStream(new FileOutputStream(new File("${themeBuildDir}/styles.css.gz")))

            def buffer = new byte[1024]
            int len
            while ((len = uncompressedStream.read(buffer)) > 0) {
                gzos.write(buffer, 0, len)
            }

            uncompressedStream.close()

            gzos.finish()
            gzos.close()
        }

        project.logger.info("[CubaWebScssThemeCreation] successfully compiled theme '{}'", themeDir.name)
    }

    @CompileStatic
    void compileScss(File scssFile, File cssFile) {
        def urlMode = ScssContext.UrlMode.MIXED
        def errorHandler = new SCSSErrorHandler() {
            def hasErrors = false

            @Override
            void error(CSSParseException e) throws CSSException {
                project.logger.error("[CubaWebScssThemeCreation] Error when parsing file \n{} on line {}, column {}",
                        e.getURI(), e.lineNumber, e.columnNumber, e)

                hasErrors = true
            }

            @Override
            void fatalError(CSSParseException e) throws CSSException {
                project.logger.error("[CubaWebScssThemeCreation] Error when parsing file \n{} on line {}, column {}",
                        e.getURI(), e.lineNumber, e.columnNumber, e)

                hasErrors = true
            }

            @Override
            void warning(CSSParseException e) throws CSSException {
                project.logger.error("[CubaWebScssThemeCreation] Warning when parsing file \n{} on line {}, column {}",
                        e.getURI(), e.lineNumber, e.columnNumber, e)
            }

            @Override
            void traverseError(Exception e) {
                project.logger.error("[CubaWebScssThemeCreation] Error on SCSS traverse", e)

                hasErrors = true
            }

            @Override
            void traverseError(String message) {
                project.logger.error("[CubaWebScssThemeCreation] {}", message)

                hasErrors = true
            }

            @Override
            boolean isErrorsDetected() {
                return super.isErrorsDetected() || hasErrors
            }
        }
        errorHandler.setWarningsAreErrors(false)

        try {
            def scss = ScssStylesheet.get(scssFile.absolutePath, null, new SCSSDocumentHandlerImpl(), errorHandler)

            if (scss == null) {
                throw new GradleException("Unable to find SCSS file " + scssFile.absolutePath)
            }

            scss.compile(urlMode)

            def writer = new FileWriter(cssFile)
            scss.write(writer, false)
            writer.close()
        } catch (Exception e) {
            throw new GradleException("Unable to build theme " + scssFile.absolutePath, e)
        }

        if (errorHandler.isErrorsDetected()) {
            throw new GradleException("Unable to build theme " + scssFile.absolutePath)
        }
    }

    @CompileStatic
    void performCssCompression(File themeDir, File cssFile) {
        project.logger.info("[CubaWebScssThemeCreation] compress theme '{}'", themeDir.name)

        def compressedFile = new File(cssFile.absolutePath + '.compressed')

        def cssReader = new FileReader(cssFile)
        def out = new BufferedWriter(new FileWriter(compressedFile))

        def compressor = new CssCompressor(cssReader)
        compressor.compress(out, 0)

        out.close()
        cssReader.close()

        if (compressedFile.exists()) {
            cssFile.delete()
            compressedFile.renameTo(cssFile)
        }
    }

    @CompileStatic
    void prepareAppComponentsInclude(File themeBuildDir) {
        def appComponentConf = project.rootProject.configurations.getByName('appComponent')
        if (appComponentConf.dependencies.size() > 0) {
            prepareAppComponentsIncludeConfiguration(themeBuildDir)
        } else {
            prepareAppComponentsIncludeClasspath(themeBuildDir)
        }
    }

    @CompileStatic
    void prepareAppComponentsIncludeConfiguration(File themeBuildDir) {
        project.logger.info("[CubaWebScssThemeCreation] include styles from app components using Gradle configuration")

        def appComponentsIncludeFile = new File(themeBuildDir, 'app-components.scss')
        if (appComponentsIncludeFile.exists()) {
            // can be completely overridden in project
            return
        }

        def appComponentsIncludeBuilder = new StringBuilder()
        appComponentsIncludeBuilder.append('/* This file is automatically managed and will be overwritten */\n\n')

        def appComponentConf = project.rootProject.configurations.getByName('appComponent')
        def resolvedConfiguration = appComponentConf.resolvedConfiguration
        def dependencies = resolvedConfiguration.firstLevelModuleDependencies

        def addedArtifacts = new HashSet<ResolvedArtifact>()
        def includeMixins = new ArrayList<String>()
        def includedAddonsPaths = new HashSet<String>()
        def scannedJars = new HashSet<File>()

        walkDependenciesFromAppComponentsConfiguration(dependencies, addedArtifacts, { ResolvedArtifact artifact ->
            def jarFile = new JarFile(artifact.file)
            jarFile.withCloseable {
                def manifest = jarFile.manifest
                if (manifest == null) {
                    return
                }

                def compId = manifest.mainAttributes.getValue(CubaPlugin.APP_COMPONENT_ID_MANIFEST_ATTRIBUTE)
                def compVersion = manifest.mainAttributes.getValue(CubaPlugin.APP_COMPONENT_VERSION_MANIFEST_ATTRIBUTE)
                if (compId == null || compVersion == null) {
                    return
                }

                project.logger.info("[CubaWebScssThemeCreation] include styles from app component {}", compId)

                def componentThemeDir = new File(themeBuildDir, compId)
                def addonsIncludeFile = new File(componentThemeDir, 'vaadin-addons.scss')

                List<File> dependentJarFiles = findDependentJarsByAppComponent(compId) - scannedJars
                scannedJars.addAll(dependentJarFiles)

                // ignore automatic lookup if defined file vaadin-addons.scss
                if (!addonsIncludeFile.exists()) {
                    findAndIncludeVaadinStyles(dependentJarFiles, includedAddonsPaths, includeMixins, appComponentsIncludeBuilder)
                } else {
                    project.logger.info("[CubaWebScssThemeCreation] ignore vaadin addon styles for {}", compId)
                }

                includeComponentScss(themeBuildDir, compId, appComponentsIncludeBuilder, includeMixins)
            }
        })

        for (includeAppComponentId in includedAppComponentIds) {
            project.logger.info("[CubaWebScssThemeCreation] include styles from app component {}", includeAppComponentId)

            // autowiring of vaadin addons from includes is not supported
            includeComponentScss(themeBuildDir, includeAppComponentId, appComponentsIncludeBuilder, includeMixins)
        }

        appComponentsIncludeBuilder.append('\n')

        // include project includes and vaadin addons
        project.logger.info("[CubaWebScssThemeCreation] include styles from project and addons")

        def currentProjectId = project.group.toString()
        def componentThemeDir = new File(themeBuildDir, currentProjectId)
        def addonsIncludeFile = new File(componentThemeDir, 'vaadin-addons.scss')

        if (!addonsIncludeFile.exists()) {
            def compileConfiguration = project.configurations.getByName('compile')
            def resolvedArtifacts = compileConfiguration.resolvedConfiguration.resolvedArtifacts
            def resolvedFiles = resolvedArtifacts.collect({ it.file })
            def currentProjectDependencies = resolvedFiles.findAll({
                it.exists() && it.name.endsWith(".jar")
            }) - scannedJars

            findAndIncludeVaadinStyles(currentProjectDependencies, includedAddonsPaths, includeMixins,
                    appComponentsIncludeBuilder)
        } else {
            project.logger.info("[CubaWebScssThemeCreation] ignore vaadin addon styles for $currentProjectId")
        }

        // print mixins
        appComponentsIncludeBuilder.append('\n@mixin app_components {\n')
        for (mixin in includeMixins) {
            appComponentsIncludeBuilder.append('  @include ').append(mixin).append(';\n')
        }
        appComponentsIncludeBuilder.append('}')

        appComponentsIncludeFile.write(appComponentsIncludeBuilder.toString())

        project.logger.info("[CubaWebScssThemeCreation] app-components.scss initialized")
    }

    @CompileStatic
    void walkDependenciesFromAppComponentsConfiguration(Set<ResolvedDependency> dependencies,
                                                        Set<ResolvedArtifact> addedArtifacts,
                                                        Consumer<ResolvedArtifact> artifactAction) {
        for (dependency in dependencies) {
            walkDependenciesFromAppComponentsConfiguration(dependency.children, addedArtifacts, artifactAction)

            for (artifact in dependency.moduleArtifacts) {
                if (addedArtifacts.contains(artifact)) {
                    continue
                }

                addedArtifacts.add(artifact)

                if (artifact.file != null && artifact.file.name.endsWith('.jar')) {
                    artifactAction.accept(artifact)
                }
            }
        }
    }

    @CompileStatic
    void prepareAppComponentsIncludeClasspath(File themeBuildDir) {
        project.logger.info("[CubaWebScssThemeCreation] include styles from app components using classpath")

        def appComponentsIncludeFile = new File(themeBuildDir, 'app-components.scss')
        if (appComponentsIncludeFile.exists()) {
            // can be completely overridden in project
            return
        }

        def appComponentsIncludeBuilder = new StringBuilder()
        appComponentsIncludeBuilder.append('/* This file is automatically managed and will be overwritten */\n\n')

        def includeMixins = new ArrayList<String>()
        def includedAddonsPaths = new HashSet<String>()
        def scannedJars = new HashSet<File>()

        def classLoader = CubaWebScssThemeCreation.class.getClassLoader()
        def manifests = classLoader.getResources("META-INF/MANIFEST.MF")
        while (manifests.hasMoreElements()) {
            def manifest = new Manifest(manifests.nextElement().openStream())

            def compId = manifest.mainAttributes.getValue(CubaPlugin.APP_COMPONENT_ID_MANIFEST_ATTRIBUTE)
            def compVersion = manifest.mainAttributes.getValue(CubaPlugin.APP_COMPONENT_VERSION_MANIFEST_ATTRIBUTE)

            if (compId && compVersion) {
                project.logger.info("[CubaWebScssThemeCreation] include styles from app component {}", compId)

                def componentThemeDir = new File(themeBuildDir, compId)
                def addonsIncludeFile = new File(componentThemeDir, 'vaadin-addons.scss')

                List<File> dependentJarFiles = findDependentJarsByAppComponent(compId) - scannedJars

                scannedJars.addAll(dependentJarFiles)

                // ignore automatic lookup if defined file vaadin-addons.scss
                if (!addonsIncludeFile.exists()) {
                    findAndIncludeVaadinStyles(dependentJarFiles, includedAddonsPaths, includeMixins, appComponentsIncludeBuilder)
                } else {
                    project.logger.info("[CubaWebScssThemeCreation] ignore vaadin addon styles for {}", compId)
                }

                includeComponentScss(themeBuildDir, compId, appComponentsIncludeBuilder, includeMixins)
            }
        }

        for (includeAppComponentId in includedAppComponentIds) {
            project.logger.info("[CubaWebScssThemeCreation] include styles from app component {}", includeAppComponentId)

            // autowiring of vaadin addons from includes is not supported

            includeComponentScss(themeBuildDir, includeAppComponentId, appComponentsIncludeBuilder, includeMixins)
        }

        appComponentsIncludeBuilder.append('\n')

        // include project includes and vaadin addons
        project.logger.info("[CubaWebScssThemeCreation] include styles from project and addons")

        def currentProjectId = project.group.toString()
        def componentThemeDir = new File(themeBuildDir, currentProjectId)
        def addonsIncludeFile = new File(componentThemeDir, 'vaadin-addons.scss')

        if (!addonsIncludeFile.exists()) {
            def compileConfiguration = project.configurations.getByName('compile')
            def resolvedArtifacts = compileConfiguration.resolvedConfiguration.resolvedArtifacts
            def resolvedFiles = resolvedArtifacts.collect({ it.file })
            def currentProjectDependencies = resolvedFiles.findAll({
                it.exists() && it.name.endsWith(".jar")
            }) - scannedJars

            findAndIncludeVaadinStyles(currentProjectDependencies, includedAddonsPaths, includeMixins,
                    appComponentsIncludeBuilder)
        } else {
            project.logger.info("[CubaWebScssThemeCreation] ignore vaadin addon styles for $currentProjectId")
        }

        // print mixins
        appComponentsIncludeBuilder.append('\n@mixin app_components {\n')
        for (mixin in includeMixins) {
            appComponentsIncludeBuilder.append('  @include ').append(mixin).append(';\n')
        }
        appComponentsIncludeBuilder.append('}')

        appComponentsIncludeFile.write(appComponentsIncludeBuilder.toString())

        project.logger.info("[CubaWebScssThemeCreation] app-components.scss initialized")
    }

    @CompileStatic
    void includeComponentScss(File themeBuildDir, String componentId,
                              StringBuilder appComponentsIncludeBuilder, List<String> includeMixins) {
        def componentThemeDir = new File(themeBuildDir, componentId)
        def componentIncludeFile = new File(componentThemeDir, 'app-component.scss')

        if (componentIncludeFile.exists()) {
            appComponentsIncludeBuilder.append("@import \"${componentThemeDir.name}/${componentIncludeFile.name}\";\n")

            includeMixins.add(componentId.replace('.', '_'))
        }
    }

    // find all dependencies of this app component
    List<File> findDependentJarsByAppComponent(String compId) {
        def compileConfiguration = project.configurations.getByName('compile')
        def firstLevelModuleDependencies = compileConfiguration.resolvedConfiguration.firstLevelModuleDependencies

        return firstLevelModuleDependencies.collectMany({ rd ->
            if (compId == rd.moduleGroup) {
                def jarArtifacts = rd.allModuleArtifacts.findAll({ ra -> ra.file.name.endsWith('.jar') && ra.file.exists() })
                def jarFiles = jarArtifacts.collect{ it.file }

                return jarFiles
            }

            return []
        })
    }

    // find all vaadin addons in dependencies of this app component
    @CompileStatic
    void findAndIncludeVaadinStyles(List<File> dependentJarFiles, Set<String> includedAddonsPaths,
                                    List<String> includeMixins, StringBuilder appComponentsIncludeBuilder) {
        for (file in dependentJarFiles) {
            file.withInputStream { is ->
                def jarStream = new JarInputStream(is)
                def mf = jarStream.manifest
                def attributes = mf?.mainAttributes
                if (attributes) {
                    def vaadinStylesheets = attributes.getValue('Vaadin-Stylesheets')
                    if (vaadinStylesheets) {
                        includeVaadinStyles(vaadinStylesheets, includeMixins, includedAddonsPaths, appComponentsIncludeBuilder)
                    }
                }
            }
        }
    }

    void includeVaadinStyles(String vaadinStylesheets, List<String> includeMixins, Set<String> includedPaths,
                             StringBuilder appComponentsIncludeBuilder) {
        def vAddonIncludes = vaadinStylesheets.split(',')
                .collect({ it.trim() })
                .findAll({ it.length() > 0 })
                .unique()

        for (include in vAddonIncludes) {
            if (!include.startsWith('/')) {
                include = '/' + include
            }

            if (includedPaths.contains(include)) {
                continue
            }

            includedPaths.add(include)

            project.logger.info("[CubaWebScssThemeCreation] include vaadin addons styles '{}'", include)

            if (include.endsWith('.css')) {
                appComponentsIncludeBuilder.append("@import url(\"../../..$include\");\n")
            } else {
                def mixin = include.substring(include.lastIndexOf("/") + 1,
                        include.length() - '.scss'.length())

                appComponentsIncludeBuilder.append("@import \"../../..$include\";\n")

                includeMixins.add(mixin)
            }
        }
    }

    void copyResources(File themesBuildDir, File themesDestDir) {
        project.copy {
            from themesBuildDir
            into themesDestDir
            exclude {
                it.file.name.startsWith('.') || it.file.name.endsWith('.scss')
            }
        }
    }

    @CompileStatic
    void removeEmptyDirs(File themesDestDir) {
        recursiveVisitDir(themesDestDir, { File f ->
            boolean isEmpty = f.list().length == 0
            if (isEmpty) {
                def relativePath = themesDestDir.toPath().relativize(f.toPath())
                project.logger.debug("[CubaWebScssThemeCreation] remove empty dir {} in '{}'", relativePath,
                        themesDestDir.name)
                f.deleteDir()
            }
        })
    }

    @CompileStatic
    void recursiveVisitDir(File dir, Closure apply) {
        for (f in dir.listFiles()) {
            if (f.exists() && f.isDirectory()) {
                recursiveVisitDir(f, apply)
                apply(f)
            }
        }
    }

    boolean getSprites() {
        return sprites
    }

    void setSprites(boolean sprites) {
        this.sprites = sprites

        project.logger.warn('[CubaWebScssThemeCreation] sprites option is not supported anymore')
    }
}