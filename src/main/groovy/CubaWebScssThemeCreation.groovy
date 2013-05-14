/*
 * Copyright (c) 2013 Haulmont Technology Ltd. All Rights Reserved.
 * Haulmont Technology proprietary and confidential.
 * Use is subject to license terms.
 */


import com.yahoo.platform.yui.compressor.CssCompressor
import org.carrot2.labs.smartsprites.SmartSpritesParameters
import org.carrot2.labs.smartsprites.SpriteBuilder
import org.carrot2.labs.smartsprites.message.MessageLog
import org.carrot2.labs.smartsprites.message.PrintStreamMessageSink
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.file.FileCollection
import org.gradle.api.internal.file.collections.SimpleFileCollection
import org.gradle.api.tasks.*
import org.kohsuke.args4j.CmdLineParser

import java.nio.file.Files
import java.util.regex.Pattern

/**
 * @author artamonov
 * @version $Id$
 */
class CubaWebScssThemeCreation extends DefaultTask {

    // additional scss root from modules
    def includes = []

    // theme names to build
    def themes = []

    // copy resources from specified themes
    def requiresResourcesFrom = []

    def scssDir = 'themes'
    def destDir = "${project.buildDir}/web/VAADIN/themes"

    def buildTimeStamp = ''
    def compress = true
    def sprites = false
    def cleanup = true

    def dirFilter = new FileFilter() {
        @Override
        boolean accept(File pathname) {
            return pathname.isDirectory() && !pathname.name.startsWith(".")
        }
    }

    CubaWebScssThemeCreation() {
        setDescription('Compile scss styles in theme')
        setGroup('Web resources')

        addVaadinThemesDependency(project)
    }

    // used static function due to 'themes' field clashes with 'themes' configuration in dependency closure
    static addVaadinThemesDependency(Project project) {
        def themesConf = project.configurations.findByName('themes')
        if (!themesConf)
            project.configurations.add('themes')
        // find vaadin version
        def vaadinLib = project.configurations.getByName('compile').resolvedConfiguration.resolvedArtifacts.find {
            it.name.startsWith('vaadin-')
        }
        // add default vaadin-themes dependency
        if (vaadinLib) {
            def dependency = vaadinLib.moduleVersion.id
            project.logger.info(">>> add default themes dependency on com.vaadin:vaadin-themes:${dependency.version}")

            project.dependencies {
                themes(group: dependency.group, name: 'vaadin-themes', version: dependency.version)
            }
        }
    }

    @OutputDirectory
    def File getOutputDirectory() {
        return project.file(destDir)
    }

    @InputFiles @SkipWhenEmpty @Optional
    def FileCollection getSourceFiles() {
        def files = new ArrayList<File>()
        def themeDirs = themes.empty ?
                    project.files(scssDir).listFiles(dirFilter) :
                    themes.collect {new File(project.file(scssDir), it)}

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
            }).each {def file ->
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

        File stylesDirecrory
        if (scssDir instanceof String)
            stylesDirecrory = project.file(scssDir)
        else
            stylesDirecrory = scssDir as File

        if (themes.empty) {
            project.logger.info(">>> scan directory '${stylesDirecrory}' for themes")
            for (File themeDir : project.files(stylesDirecrory).listFiles(dirFilter))
                themes.add(themeDir)
        }

        // unpack dependencies to destDir
        Configuration themesConf = project.configurations.findByName('themes')
        if (themesConf) {
            def themesResolvedArtifacts = themesConf.resolvedConfiguration.getResolvedArtifacts()
            themesResolvedArtifacts.each { ResolvedArtifact artifact ->
                project.logger.info(">>> unpack themes artifact ${artifact.name}")

                File tmpDir = Files.createTempDirectory('themes_' + artifact.name).toFile()
                try {
                    project.copy {
                        from project.zipTree(artifact.file.absolutePath)
                        into tmpDir
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
        includes.each { File includeThemeDir ->
            project.logger.info(">>> copy includes from '${includeThemeDir.name}'")
            if (!includeThemeDir.exists())
                throw new FileNotFoundException("Could not found include dir ${includeThemeDir.absolutePath}")

            project.copy {
                from includeThemeDir
                into new File(themesTmp, includeThemeDir.name)
            }
        }

        // copy include resources
        includes.each { File includeThemeDir ->
            project.logger.info(">>> copy resources from '${includeThemeDir.name}'")
            if (!includeThemeDir.exists())
                throw new FileNotFoundException("Could not found include dir ${includeThemeDir.absolutePath}")

            def themeDestDir = new File(destinationDirectory, includeThemeDir.name)

            copyIncludeResources(includeThemeDir, themeDestDir)
        }

        // copy include resources
        requiresResourcesFrom.each { String themeName ->
            project.logger.info(">>> copy resources from '${themeName}'")

            def themeSourceDir = new File(themesTmp, themeName)
            def themeDestDir = new File(destinationDirectory, themeName)

            if (!themeSourceDir.exists())
                throw new FileNotFoundException("Could not found include dir ${themeSourceDir.absolutePath}")

            copyIncludeResources(themeSourceDir, themeDestDir)
        }

        themes.each { def themeDir ->
            if (themeDir instanceof String)
                themeDir = new File(stylesDirecrory, themeDir)

            def themeBuildDir = new File(themesTmp, themeDir.name)
            def themeDestDir = new File(destinationDirectory, themeDir.name)
            if (!themeDestDir.exists())
                themeDestDir.mkdir()

            project.logger.info(">>> copy theme '${themeDir.name}' to build directory")
            // copy theme to build directory
            project.copy {
                from themeDir
                into themeBuildDir
                exclude {
                    it.file.name.startsWith('.')
                }
            }

            project.logger.info(">>> copy theme resources for '${themeDir.name}'")
            // copy resources
            project.copy {
                from themeDir
                into themeDestDir
                exclude {
                    it.file.name.startsWith('.') || it.file.name.endsWith('.scss')
                }
            }

            project.logger.info(">>> compile theme '${themeDir.name}'")

            def scssFilePath = project.file("${themeBuildDir}/styles.scss").absolutePath
            def cssFilePath = project.file("${themeDestDir}/styles.css").absolutePath

            project.javaexec {
                main = 'com.vaadin.sass.SassCompiler'
                classpath = project.sourceSets.main.compileClasspath
                args = [scssFilePath, cssFilePath]
                jvmArgs = []
            }

            if (sprites) {
                project.logger.info(">>> compile sprites for theme '${themeDir.name}'")

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
                themeDestDir.eachDirRecurse { if ('sprites'.equals(it.name)) dirsToDelete.add(it) }
                dirsToDelete.each { it.deleteDir() }

                // replace file
                if (processedFile.exists()) {
                    cssFile.delete()
                    processedFile.renameTo(cssFile)
                }
            }

            if (compress) {
                project.logger.info(">>> compress theme '${themeDir.name}'")

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
                        project.logger.info(">>> remove empty dir ${themeDestDir.toPath().relativize(f.toPath())} in '${themeDir.name}'")
                        f.deleteDir()
                    }
                })
            }

            // update build timestamp for urls
            if (buildTimeStamp != null && !buildTimeStamp.isEmpty()) {
                project.logger.info(">>> add build timestamp to '${themeDir.name}'")
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
                versionedFile.renameTo(cssFile)
            }

            project.logger.info(">>> successfully compiled theme '${themeDir.name}'")
        }

        themesTmp.deleteDir()
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
                    project.logger.info(">>> remove empty dir ${themeDestDir.toPath().relativize(f.toPath())} in '${themeDestDir.name}'")
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