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
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.*
import org.kohsuke.args4j.CmdLineParser

import java.util.regex.Pattern

/**
 * @author artamonov
 * @version $Id$
 */
class CubaWebScssThemeCreation extends DefaultTask {
    def themes = []
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
    }

    @OutputDirectory
    def File getOutputDirectory() {
        return project.file(destDir)
    }

    @InputFiles @SkipWhenEmpty @Optional
    def FileCollection getSourceFiles() {
        def themeDirs = themes.empty ?
                    project.files(scssDir).listFiles(dirFilter) :
                    themes.collect {new File(project.file(scssDir), it)}

        return project.fileTree(scssDir, {
            for (def themeDir : themeDirs)
                include "${themeDir.name}/**"
            exclude '**/.*'
        })
    }

    @TaskAction
    def buildThemes() {
        if (destDir instanceof String)
            destDir = project.file(destDir)

        if (scssDir instanceof String)
            scssDir = project.file(scssDir)

        if (themes.empty) {
            project.logger.info(">>> scan directory '${scssDir}' for themes")
            for (File themeDir : project.files(scssDir).listFiles(dirFilter))
                themes.add(themeDir)
        }

        themes.each { def themeDir ->
            if (themeDir instanceof String)
                themeDir = new File(scssDir, themeDir)

            def themeDestDir = new File(destDir, themeDir.name)

            project.copy {
                from themeDir
                into themeDestDir
                exclude {
                    it.file.name.startsWith('.') || it.file.name.endsWith('.scss')
                }
            }

            project.logger.info(">>> compile theme '${themeDir.name}'")

            def scssFilePath = project.file("${themeDir}/styles.scss").absolutePath
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
                        project.logger.info(">>> remove empty dir ${themeDestDir.toPath().relativize(f.toPath())}")
                        f.deleteDir()
                    }
                })
            }

            // update build timestamp for urls
            if (buildTimeStamp != null && !buildTimeStamp.isEmpty()) {
                project.logger.info(">>> add build timestamp to '${themeDir.name}'")

                Pattern urlPattern = Pattern.compile('url\\([\\s]*[\'|\"]?([^\\)\\ \'\"]*)[\'|\"]?[\\s]*\\)')
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
        private Pattern urlPattern = Pattern.compile('url\\([\\s]*[\'|\"]?([^\\)\\ \'\"]*)[\'|\"]?[\\s]*\\)')

        public Set<String> getUrls(String cssContent) {
            def matcher = urlPattern.matcher(cssContent)
            def urls = new HashSet<String>()
            while (matcher.find()) {
                urls.add(matcher.group(1))
            }
            return urls
        }
    }
}