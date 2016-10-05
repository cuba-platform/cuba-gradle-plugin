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

import com.vaadin.sass.SassCompiler
import com.yahoo.platform.yui.compressor.CssCompressor
import org.apache.commons.io.FileUtils
import org.apache.commons.lang.StringUtils
import org.carrot2.labs.smartsprites.SmartSpritesParameters
import org.carrot2.labs.smartsprites.SpriteBuilder
import org.carrot2.labs.smartsprites.message.MessageLog
import org.carrot2.labs.smartsprites.message.PrintStreamMessageSink
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.file.FileCollection
import org.gradle.api.internal.file.collections.SimpleFileCollection
import org.gradle.api.tasks.*
import org.kohsuke.args4j.CmdLineParser

import java.nio.file.Files
import java.util.jar.JarInputStream
import java.util.jar.Manifest
import java.util.regex.Pattern

class CubaWebScssThemeCreation extends DefaultTask {

    // additional scss root from modules
    List<File> includes = []

    // theme names to build
    List<String> themes = []

    def scssDir = 'themes'
    def destDir = "${project.buildDir}/web/VAADIN/themes"

    def buildTimeStamp = ''
    def compress = true
    def sprites = true
    def cleanup = true

    def excludedThemes = new ArrayList<String>()
    def excludePaths = new ArrayList<String>()
    def doNotUnpackPaths = [
            'VAADIN/themes/base/*.css',
            'VAADIN/themes/base/*.css.gz',
            'VAADIN/themes/base/favicon.ico',
            'VAADIN/themes/valo/*.css',
            'VAADIN/themes/valo/*.css.gz',
            'VAADIN/themes/valo/favicon.ico',
            'VAADIN/themes/reindeer/**',
            'VAADIN/themes/chameleon/**',
            'VAADIN/themes/runo/**',
            'VAADIN/themes/liferay/**',
            'VAADIN/themes/valo/fonts/lato/*.eot',
            'VAADIN/themes/valo/fonts/lato/*.ttf',
            'VAADIN/themes/valo/fonts/lato/*.woff',
            'VAADIN/themes/valo/fonts/lora/*.eot',
            'VAADIN/themes/valo/fonts/lora/*.ttf',
            'VAADIN/themes/valo/fonts/lora/*.woff',
            'VAADIN/themes/valo/fonts/roboto/*.eot',
            'VAADIN/themes/valo/fonts/roboto/*.ttf',
            'VAADIN/themes/valo/fonts/roboto/*.woff',
            'VAADIN/themes/valo/fonts/source-sans-pro/*.eot',
            'VAADIN/themes/valo/fonts/source-sans-pro/*.ttf',
            'VAADIN/themes/valo/fonts/source-sans-pro/*.woff',
            'META-INF/**',
    ]

    def dirFilter = new FileFilter() {
        @Override
        boolean accept(File pathname) {
            return pathname.isDirectory() && !pathname.name.startsWith(".")
        }
    }

    CubaWebScssThemeCreation() {
        setDescription('Compile scss styles in theme')
        setGroup('Web resources')
        // we need to scan dependendent jar files
        setDependsOn(project.getTasksByName('compileJava', false))
    }

    @OutputDirectory
    def File getOutputDirectory() {
        return project.file(destDir)
    }

    @InputFiles
    @SkipWhenEmpty
    @Optional
    def FileCollection getSourceFiles() {
        def files = new ArrayList<File>()
        def themeDirs = themes.empty ?
                project.file(scssDir).listFiles(dirFilter) :
                themes.collect { new File(project.file(scssDir), it) }

        project.fileTree(scssDir, {
            for (def themeDir : themeDirs)
                include "${themeDir.name}/**"
            exclude '**/.*'
        }).each { def file ->
            files.add(file)
        }

        includes.each { def include ->
            File includeDir
            if (include instanceof String)
                includeDir = project.rootProject.file(include)
            else
                includeDir = include

            project.rootProject.fileTree(includeDir, {
                exclude '**/.*'
            }).each { def file ->
                files.add(file)
            }
        }

        return new SimpleFileCollection(files)
    }

    @TaskAction
    def buildThemes() {
        File themesTmp = project.file("${project.buildDir}/themes-tmp")
        if (themesTmp.exists())
            themesTmp.deleteDir()
        themesTmp.mkdir()

        File destinationDirectory
        if (destDir instanceof String)
            destinationDirectory = project.file(destDir)
        else
            destinationDirectory = destDir as File

        File stylesDirectory
        if (scssDir instanceof String)
            stylesDirectory = project.file(scssDir)
        else
            stylesDirectory = scssDir as File

        if (themes.empty) {
            project.logger.info("[CubaWebScssThemeCreation] scan directory '${stylesDirectory}' for themes")

            themes.addAll(project.file(stylesDirectory)
                    .listFiles(dirFilter)
                    .collect { it.name })
        }

        // unpack dependencies to destDir
        Configuration themesConf = project.configurations.findByName('themes')
        if (themesConf) {
            // unpack dependencies first
            def themesResolvedArtifacts = themesConf.resolvedConfiguration
                    .getResolvedArtifacts()
                    .toList()
                    .reverse()

            themesResolvedArtifacts.each { ResolvedArtifact artifact ->
                project.logger.info("[CubaWebScssThemeCreation] unpack themes artifact ${artifact.name}")

                File tmpDir = Files.createTempDirectory('themes_' + artifact.name).toFile()
                try {
                    project.copy {
                        from project.zipTree(artifact.file.absolutePath)
                        into tmpDir
                        excludes = doNotUnpackPaths.toSet()
                    }

                    File artifactThemesRoot = tmpDir
                    // if used vaadin-style theme artifact
                    def vaadinThemesRoot = new File(tmpDir, 'VAADIN/themes')
                    if (vaadinThemesRoot.exists()) {
                        artifactThemesRoot = vaadinThemesRoot
                    }

                    artifactThemesRoot.eachDir { File dir ->
                        project.copy {
                            from dir
                            into new File(themesTmp, dir.name)
                        }
                    }
                } finally {
                    tmpDir.deleteDir()
                }
            }
        }

        // copy includes to build dir
        for (def includeThemeDir : includes) {
            project.logger.info("[CubaWebScssThemeCreation] copy includes from '${includeThemeDir.name}'")
            if (!includeThemeDir.exists())
                throw new GradleException("Could not found include dir ${includeThemeDir.absolutePath}")

            project.copy {
                from includeThemeDir
                into new File(themesTmp, includeThemeDir.name)
            }
        }

        // copy include resources
        for (def includeThemeDir : includes) {
            project.logger.info("[CubaWebScssThemeCreation] copy resources from '${includeThemeDir.name}'")
            if (!includeThemeDir.exists())
                throw new GradleException("Could not found include dir ${includeThemeDir.absolutePath}")

            def themeDestDir = new File(destinationDirectory, includeThemeDir.name)

            copyIncludeResources(includeThemeDir, themeDestDir)
        }

        File[] themeDirs = themesTmp.listFiles()
        if (themeDirs) {
            for (File themeSourceDir : themeDirs) {
                if (themeSourceDir.isDirectory()) {
                    project.logger.info("[CubaWebScssThemeCreation] copy resources from '${themeSourceDir.name}'")

                    def themeDestDir = new File(destinationDirectory, themeSourceDir.name)
                    copyIncludeResources(themeSourceDir, themeDestDir)
                }
            }
        }

        for (def themeName : excludedThemes) {
            def themeDestDir = new File(destinationDirectory, themeName)
            project.logger.info("[CubaWebScssThemeCreation] excluded theme '$themeName'")

            FileUtils.deleteQuietly(themeDestDir)
        }

        for (def path : excludePaths) {
            def pathFile = new File(destinationDirectory, path)
            project.logger.info("[CubaWebScssThemeCreation] excluded path '$path'")

            FileUtils.deleteQuietly(pathFile)
        }

        def includedVaadinAddons = new HashSet<File>()

        for (def themeDirName : themes) {
            project.logger.info("[CubaWebScssThemeCreation] build theme '$themeDirName'")

            def themeDir = new File(stylesDirectory, themeDirName)

            def themeBuildDir = new File(themesTmp, themeDirName)
            def themeDestDir = new File(destinationDirectory, themeDirName)
            if (!themeDestDir.exists())
                themeDestDir.mkdir()

            project.logger.info("[CubaWebScssThemeCreation] copy theme '${themeDir.name}' to build directory")
            // copy theme to build directory
            project.copy {
                from themeDir
                into themeBuildDir
                exclude {
                    it.file.name.startsWith('.')
                }
            }

            project.logger.info("[CubaWebScssThemeCreation] copy theme resources for '${themeDir.name}'")
            // copy resources from themeBuildDir, override may be used
            project.copy {
                from themeBuildDir
                into themeDestDir
                exclude {
                    it.file.name.startsWith('.') || it.file.name.endsWith('.scss')
                }
            }

            prepareAppComponentsInclude(themeBuildDir, includedVaadinAddons)

            project.logger.info("[CubaWebScssThemeCreation] compile theme '${themeDir.name}'")

            def scssFilePath = project.file("${themeBuildDir}/styles.scss").absolutePath
            def cssFilePath = project.file("${themeDestDir}/styles.css").absolutePath

            try {
                SassCompiler.main(scssFilePath, cssFilePath)
            } catch (Exception e) {
                throw new GradleException("Unable to build theme. SCSS parsing error.", e)
            }

            if (sprites) {
                project.logger.info("[CubaWebScssThemeCreation] compile sprites for theme '${themeDir.name}'")

                def compiledSpritesDir = new File(themeDestDir, 'compiled')
                if (!compiledSpritesDir.exists())
                    compiledSpritesDir.mkdir()

                def processedFile = new File(themeDestDir, 'styles-sprite.css')
                def cssFile = new File(cssFilePath)

                // process
                final SmartSpritesParameters parameters = new SmartSpritesParameters()
                final CmdLineParser parser = new CmdLineParser(parameters)

                parser.parseArgument('--root-dir-path', themeDestDir.absolutePath)

                // Get parameters form system properties
                final MessageLog messageLog = new MessageLog(new PrintStreamMessageSink(System.out, parameters.getLogLevel()))
                new SpriteBuilder(parameters, messageLog).buildSprites()

                def dirsToDelete = []
                // remove sprites directories
                themeDestDir.eachDirRecurse { if ('sprites' == it.name) dirsToDelete.add(it) }
                dirsToDelete.each { it.deleteDir() }

                // replace file
                if (processedFile.exists()) {
                    cssFile.delete()
                    processedFile.renameTo(cssFile)
                }
            }

            if (compress) {
                project.logger.info("[CubaWebScssThemeCreation] compress theme '${themeDir.name}'")

                def compressedFile = new File(cssFilePath + '.compressed')
                def cssFile = new File(cssFilePath)

                def cssReader = new FileReader(cssFile)
                BufferedWriter out = new BufferedWriter(new FileWriter(compressedFile))

                CssCompressor compressor = new CssCompressor(cssReader)
                compressor.compress(out, 0)

                out.close()
                cssReader.close()

                if (compressedFile.exists()) {
                    cssFile.delete()
                    compressedFile.renameTo(cssFile)
                }
            }

            if (cleanup) {
                // remove empty directories
                recursiveVisitDir(themeDestDir, { File f ->
                    boolean isEmpty = f.list().length == 0
                    if (isEmpty) {
                        project.logger.debug("[CubaWebScssThemeCreation] remove empty dir ${themeDestDir.toPath().relativize(f.toPath())} in '${themeDir.name}'")
                        f.deleteDir()
                    }
                })
            }

            if (StringUtils.isEmpty(buildTimeStamp) && project.ext.has('webResourcesTs')) {
                // detect version automatically
                buildTimeStamp = project.ext.get('webResourcesTs')
            }

            // update build timestamp for urls
            if (StringUtils.isNotEmpty(buildTimeStamp)) {
                project.logger.info("[CubaWebScssThemeCreation] add build timestamp to '${themeDir.name}'")
                // read
                def cssFile = new File(cssFilePath)
                def versionedFile = new File(cssFilePath + ".versioned")
                def cssContent = cssFile.getText("UTF-8")
                // find
                def inspector = new CssUrlInspector()
                // replace
                for (String url : inspector.getUrls(cssContent)) {
                    cssContent = cssContent.replace(url, url + '?v=' + buildTimeStamp)
                }
                // write
                versionedFile.write(cssContent, "UTF-8")
                cssFile.delete()
                versionedFile.renameTo(cssFile)
            }

            project.logger.info("[CubaWebScssThemeCreation] successfully compiled theme '${themeDir.name}'")
        }
    }

    def prepareAppComponentsInclude(File themeBuildDir, Set<File> includedVaadinAddons) {
        project.logger.info("[CubaWebScssThemeCreation] include styles from app components")

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

        Enumeration<URL> manifests = CubaWebScssThemeCreation.class.getClassLoader().getResources("META-INF/MANIFEST.MF")
        while (manifests.hasMoreElements()) {
            Manifest manifest = new Manifest(manifests.nextElement().openStream());

            def compId = manifest.mainAttributes.getValue(CubaPlugin.APP_COMPONENT_ID_MANIFEST_ATTRIBUTE)
            def compVersion = manifest.mainAttributes.getValue(CubaPlugin.APP_COMPONENT_VERSION_MANIFEST_ATTRIBUTE)

            if (compId && compVersion) {
                project.logger.info("[CubaWebScssThemeCreation] include styles from app component $compId")

                def componentThemeDir = new File(themeBuildDir, compId)
                def addonsIncludeFile = new File(componentThemeDir, 'vaadin-addons.scss')

                List<File> dependentJarFiles = findDependentJarsByAppComponent(compId) - scannedJars

                scannedJars.addAll(dependentJarFiles)

                // ignore automatic lookup if defined file vaadin-addons.scss
                if (!addonsIncludeFile.exists()) {
                    findAndIncludeVaadinStyles(themeBuildDir.parentFile, dependentJarFiles, includedVaadinAddons,
                            includedAddonsPaths, includeMixins, appComponentsIncludeBuilder)
                } else {
                    project.logger.info("[CubaWebScssThemeCreation] ignore vaadin addon styles for $compId")
                }

                // check if exists directory with name == compId
                includeComponentScss(themeBuildDir, compId, appComponentsIncludeBuilder, includeMixins)
            }
        }

        appComponentsIncludeBuilder.append('\n')

        // include project includes and vaadin addons
        project.logger.info("[CubaWebScssThemeCreation] include styles from project and addons")

        def currentProjectId = project.group.toString()
        def componentThemeDir = new File(themeBuildDir, currentProjectId)
        def addonsIncludeFile = new File(componentThemeDir, 'vaadin-addons.scss')

        if (!addonsIncludeFile.exists()) {
            def compileConfiguration = project.configurations.compile
            def resolvedArtifacts = compileConfiguration.resolvedConfiguration.resolvedArtifacts
            def currentProjectDependencies = resolvedArtifacts.collect({ it.file }) - scannedJars

            findAndIncludeVaadinStyles(themeBuildDir.parentFile, currentProjectDependencies, includedVaadinAddons,
                    includedAddonsPaths, includeMixins, appComponentsIncludeBuilder)
        } else {
            project.logger.info("[CubaWebScssThemeCreation] ignore vaadin addon styles for $currentProjectId")
        }

        // print mixins
        appComponentsIncludeBuilder.append('\n@mixin app_components {\n')
        for (def mixin : includeMixins) {
            appComponentsIncludeBuilder.append('  @include ').append(mixin).append(';\n')
        }
        appComponentsIncludeBuilder.append('}')

        appComponentsIncludeFile.write(appComponentsIncludeBuilder.toString())

        project.logger.info("[CubaWebScssThemeCreation] app-components.scss initialized")
    }

    def includeComponentScss(File themeBuildDir, String componentId,
                             StringBuilder appComponentsIncludeBuilder, List<String> includeMixins) {
        def componentThemeDir = new File(themeBuildDir, componentId)
        def componentIncludeFile = new File(componentThemeDir, 'app-component.scss')

        if (componentIncludeFile.exists()) {
            appComponentsIncludeBuilder.append("@import \"${componentThemeDir.name}/${componentIncludeFile.name}\";\n")

            includeMixins.add(componentId.replace('.', '_'))
        }
    }

    // find all dependencies of this app component
    def List<File> findDependentJarsByAppComponent(String compId) {
        def compileConfiguration = project.configurations.compile
        def firstLevelModuleDependencies = compileConfiguration.resolvedConfiguration.firstLevelModuleDependencies

        return firstLevelModuleDependencies.collectMany({ rd ->
            if (compId == rd.moduleGroup) {
                def jarArtifacts = rd.allModuleArtifacts.findAll({ ra -> ra.file.name.endsWith('.jar') })
                def jarFiles = jarArtifacts.collect { it.file }

                return jarFiles
            }

            return []
        })
    }

    // find all vaadin addons in dependencies of this app component
    def findAndIncludeVaadinStyles(File themesBuildDir, List<File> dependentJarFiles,
                                   Set<File> includedVaadinAddons, Set<String> includedAddonsPaths,
                                   List<String> includeMixins, StringBuilder appComponentsIncludeBuilder) {
        for (def file : dependentJarFiles) {
            file.withInputStream { is ->
                def jarStream = new JarInputStream(is)
                def mf = jarStream.manifest
                def attributes = mf?.mainAttributes
                if (attributes) {
                    def vaadinStylesheets = attributes.getValue('Vaadin-Stylesheets')
                    if (vaadinStylesheets) {
                        if (!includedVaadinAddons.contains(file)) {
                            project.logger.info("[CubaWebScssThemeCreation] unpack Vaadin addon styles ${file.name}")
                            project.copy {
                                from project.zipTree(file)
                                into themesBuildDir
                                include 'VAADIN/**'
                            }

                            includedVaadinAddons.add(file)
                        }

                        includeVaadinStyles(vaadinStylesheets, includeMixins, includedAddonsPaths, appComponentsIncludeBuilder)
                    }
                }
            }
        }
    }

    def void includeVaadinStyles(String vaadinStylesheets, List<String> includeMixins, Set<String> includedPaths,
                                 StringBuilder appComponentsIncludeBuilder) {
        def vAddonIncludes = vaadinStylesheets.split(',')
                .collect({ it.trim() })
                .findAll({ it.length() > 0 })
                .unique()

        for (def include : vAddonIncludes) {
            if (!include.startsWith('/')) {
                include = '/' + include
            }

            if (includedPaths.contains(include)) {
                continue
            }

            includedPaths.add(include)

            project.logger.info("[CubaWebScssThemeCreation] include vaadin addons styles '${include}'")

            if (include.endsWith('.css')) {
                appComponentsIncludeBuilder.append("@import url(\"..$include\");\n")
            } else {
                def mixin = include.substring(include.lastIndexOf("/") + 1,
                        include.length() - '.scss'.length());

                appComponentsIncludeBuilder.append("@import \"..$include\";\n")

                includeMixins.add(mixin)
            }
        }
    }

    def copyIncludeResources(File themeSourceDir, File themeDestDir) {
        project.copy {
            from themeSourceDir
            into themeDestDir
            exclude {
                it.file.name.startsWith('.') || 'favicon.ico' == it.file.name || it.file.name.endsWith('.scss') || it.file.name.endsWith('.css')
            }
        }

        if (cleanup) {
            // remove empty directories
            recursiveVisitDir(themeDestDir, { File f ->
                boolean isEmpty = f.list().length == 0
                if (isEmpty) {
                    def relativePath = themeDestDir.toPath().relativize(f.toPath())
                    project.logger.debug("[CubaWebScssThemeCreation] remove empty dir $relativePath in '${themeDestDir.name}'")
                    f.deleteDir()
                }
            })
        }
    }

    def recursiveVisitDir(File dir, Closure apply) {
        for (def f : dir.listFiles()) {
            if (f.exists() && f.isDirectory()) {
                recursiveVisitDir(f, apply)
                apply(f)
            }
        }
    }

    static class CssUrlInspector {
        private static final Pattern CSS_URL_PATTERN =
                Pattern.compile('url\\([\\s]*[\'|\"]?([^\\)\\ \'\"]*)[\'|\"]?[\\s]*\\)')

        @SuppressWarnings("GrMethodMayBeStatic")
        public Set<String> getUrls(String cssContent) {
            // replace comments
            cssContent = cssContent.replaceAll('/\\*.*\\*/', '')

            def matcher = CSS_URL_PATTERN.matcher(cssContent)
            def urls = new HashSet<String>()
            while (matcher.find()) {
                urls.add(matcher.group(1))
            }
            return urls
        }
    }
}