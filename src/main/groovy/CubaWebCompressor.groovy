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

import com.yahoo.platform.yui.compressor.JavaScriptCompressor
import com.yahoo.platform.yui.compressor.CssCompressor
import groovy.io.FileType
import org.apache.commons.lang.StringUtils
import org.gradle.api.DefaultTask
import org.mozilla.javascript.ErrorReporter
import org.mozilla.javascript.tools.ToolErrorReporter

/**
 *
 */
class CubaWebCompressor extends DefaultTask {

    public class FileSource {
        def file
        Closure handler

        FileSource(file) {
            this.file = file
        }

        def to(def destination) {
            handler(file, destination)
        }
    }

    public FileSource from(def file) {
        def source = new FileSource(file)
        source.handler = { from, to ->
            from = preparePath(from)
            to = preparePath(to)
            def fromFile = new File(from)
            if (fromFile.exists()) {
                def resultFile = new File(to)
                if (!resultFile.exists() && !resultFile.isFile())
                    resultFile.mkdirs()
                if (fromFile.isFile()) {
                    File resultPath = new File(StringUtils.substringBeforeLast(to, "\\"))
                    resultPath.mkdirs()
                    compressFile(from, to)
                } else
                    compressDirectory(from, to)
            } else
                throw new RuntimeException(from + " not exist")
        }
        return source
    }

    public void compressFile(String from, String to) {
        String format = StringUtils.substringAfterLast(from, '.')
        boolean isDirectory = StringUtils.substringAfterLast(to, '.').isEmpty()
        if (isDirectory)
            throw new RuntimeException("Result path must be a file")
        if (format.equals("js"))
            compressJsFile(from, to)
        else if (format.equals("css"))
            compressCssFile(from, to)
    }

    public void compressJsFile(String from, String to) {
        FileReader reader = new FileReader(from)
        ErrorReporter reporter = new ToolErrorReporter(true);
        FileWriter writer = new FileWriter(to)
        boolean isCompressed = false;
        try {
            JavaScriptCompressor compressor = new JavaScriptCompressor(reader, reporter);
            compressor.compress(writer, 0, true, false, true, true)
            isCompressed = true
        }
        catch (Exception e) {
            project.logger.warn("[CubaWebCompressor] File ${from} is NOT compressed", e)
        }
        finally {
            reader.close()
            writer.close()
            checkFile(isCompressed, to)
        }
    }

    public void compressCssFile(String from, String to) {
        FileReader reader = new FileReader(from)
        FileWriter writer = new FileWriter(to)
        boolean isCompressed = false;
        try {
            CssCompressor compressor = new CssCompressor(reader);
            compressor.compress(writer, 0)
            isCompressed = true
        }
        catch (Exception e) {
            project.logger.warn("[CubaWebCompressor] File ${from} is NOT compressed", e)
        }
        finally {
            reader.close()
            writer.close()
            checkFile(isCompressed, to)
        }
    }

    public void compressDirectory(String from, String to) {
        boolean isFile = !StringUtils.substringAfterLast(to, '.').isEmpty()
        if (isFile)
            throw new RuntimeException("Result path must be a directory")
        File rootDirectory = new File(from)
        rootDirectory.eachFile(FileType.FILES) {
            def resultFilePath = StringUtils.replace(it.path, from, to)
            compressFile(it.path, resultFilePath)
        }
        rootDirectory.eachDirRecurse {
            File resultDir = new File(StringUtils.replace(it.path, from, to))
            if (!resultDir.exists())
                resultDir.mkdir()
            it.eachFile(FileType.FILES) {
                def resultFilePath = StringUtils.replace(it.path, from, to)
                compressFile(it.path, resultFilePath)
            }
        }
    }

    public String preparePath(String path) {
        path = StringUtils.replace(path, "//", "\\")
        path = StringUtils.replace(path, "/", "\\")
        if (StringUtils.endsWith(path, "\\"))
            path = StringUtils.substringBeforeLast(path, "\\")
        return path
    }

    public void checkFile(Boolean isCompressed, String path) {
        if (!isCompressed) {
            File file = new File(path)
            file.delete()
        }
    }
}