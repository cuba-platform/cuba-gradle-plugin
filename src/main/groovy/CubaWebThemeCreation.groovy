/*
 * Copyright (c) 2008-2013 Haulmont. All rights reserved.
 * Use is subject to license terms, see http://www.cuba-platform.com/license for details.
 */

import com.yahoo.platform.yui.compressor.CssCompressor
import org.gradle.api.DefaultTask
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.*

/**
 * @author artamonov
 * @version $Id$
 */
class CubaWebThemeCreation extends DefaultTask {

    def themes = []
    def cssDir = 'css/VAADIN/themes'
    def destDir = "${project.buildDir}/web/VAADIN/themes"

    def dirFilter = new FileFilter() {
        @Override
        boolean accept(File pathname) {
            return pathname.isDirectory() && !pathname.name.startsWith(".")
        }
    }

    CubaWebThemeCreation() {
        setDescription('Builds GWT themes')
        setGroup('Web resources')
    }

    @TaskAction
    def buildThemes() {
        project.logger.info('>>> copying themes to outDir')
        File outDir = new File(destDir)
        outDir.mkdirs()
        themes.each {
            def themeName = it['themeName']
            def themeInclude = themeName + '/**'
            project.copy {
                from cssDir
                into outDir
                include themeInclude
            }
            def destFile = it['destFile'] != null ? it['destFile'] : 'styles-include.css'
            project.logger.info('>>> build theme ' + themeName)
            buildCssTheme(new File(outDir, '/' + themeName), destFile)
        }
    }

    @OutputDirectory
    def File getOutputDirectory() {
        return project.file(destDir)
    }

    @InputFiles @SkipWhenEmpty @Optional
    def FileCollection getSourceFiles() {
        def themeDirs = themes.empty ?
            project.files(cssDir).listFiles(dirFilter) :
            themes.collect { new File(project.file(cssDir), it['themeName']) }

        return project.fileTree(cssDir, {
            for (def themeDir : themeDirs)
                include "${themeDir.name}/**"
            exclude '**/.*'
        })
    }

    def buildCssTheme(themeDir, destFile) {

        if (!themeDir.isDirectory()) {
            throw new IllegalArgumentException("ThemeDir should be a directory")
        }

        def themeName = themeDir.getName()
        def combinedCss = new StringBuilder()
        combinedCss.append("/* Automatically created css file from subdirectories. */\n")

        final File[] subdir = themeDir.listFiles()

        Arrays.sort(subdir, new Comparator<File>() {
            @Override
            public int compare(File arg0, File arg1) {
                return (arg0).compareTo(arg1)
            }
        })

        for (final File dir: subdir) {
            String name = dir.getName()
            String filename = dir.getPath() + "/" + name + ".css"

            final File cssFile = new File(filename)
            if (cssFile.isFile()) {
                combinedCss.append("\n")
                combinedCss.append("/* >>>>> ").append(cssFile.getName()).append(" <<<<< */")
                combinedCss.append("\n")

                BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(cssFile)))
                String strLine
                while ((strLine = br.readLine()) != null) {
                    String urlPrefix = "../" + themeName + "/"

                    if (strLine.indexOf("url(../") > 0) {
                        strLine = strLine.replaceAll("url\\(../", "url\\(" + urlPrefix)

                    } else {
                        strLine = strLine.replaceAll("url\\(", "url\\(" + urlPrefix + name + "/")

                    }
                    combinedCss.append(strLine)
                    combinedCss.append("\n")
                }
                br.close()
                // delete obsolete css and empty directories
                cssFile.delete()
            }
            if (dir.isDirectory() && ((dir.listFiles() == null) || (dir.listFiles().length == 0)))
                dir.delete()
        }

        def themePath = themeDir.absolutePath

        if (!themePath.endsWith("/")) {
            themePath += "/"
        }

        if (destFile.indexOf(".") == -1) {
            destFile += ".css"
        }

        def themeFileName = themePath + destFile
        BufferedWriter out = new BufferedWriter(new FileWriter(themeFileName))

        CssCompressor compressor = new CssCompressor(new StringReader(combinedCss.toString()))
        compressor.compress(out, 0)

        out.close()

        project.logger.info(">>> compiled CSS to " + themePath + destFile
                + " (" + combinedCss.toString().length() + " bytes)")
    }
}