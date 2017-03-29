/*
 * Copyright (c) 2008-2017 Haulmont.
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

package com.haulmont.gradle.uberjar

import org.apache.commons.io.FileUtils
import org.apache.commons.io.FilenameUtils
import org.apache.commons.io.filefilter.FileFilterUtils

import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class Unpack {
    protected final File fromDir
    protected final File toDir
    protected final List<UnpackTransformer> transformers

    Unpack(File fromDir, File toDir, List<UnpackTransformer> transformers) {
        this.fromDir = fromDir
        this.toDir = toDir
        if (transformers == null) {
            this.transformers = Collections.emptyList();
        } else {
            this.transformers = transformers
        }
        if (!this.toDir.exists()) {
            FileUtils.forceMkdir(this.toDir)
        }
    }

    public void runAction() {
        def listFiles = FileUtils.listFiles(fromDir, FileFilterUtils.trueFileFilter(), FileFilterUtils.trueFileFilter());
        listFiles.each {
            file ->
                if (isJar(file)) {
                    def zipFile = new ZipFile(file)
                    try {
                        zipFile.entries().each {
                            zipEntry ->
                                if (zipEntry.directory) {
                                    visitDirectory(zipFile, zipEntry)
                                } else {
                                    visitFile(zipFile, zipEntry)
                                }
                        }
                    } finally {
                        try {
                            zipFile.close()
                        } catch (Exception e) {
                            //Do nothing
                        }
                    }

                }
        }
    }

    protected void visitDirectory(ZipFile zipFile, ZipEntry zipEntry) {
        def paths = zipEntry.name.split('/')
        def currentDir = toDir
        paths.each {
            def file = new File(currentDir, it);
            if (!file.exists()) {
                FileUtils.forceMkdir(file)
            }
            currentDir = file
        }
    }

    protected void visitFile(ZipFile zipFile, ZipEntry zipEntry) {
        def paths = zipEntry.name.split('/')
        def currentDir = toDir
        paths.eachWithIndex {
            it, idx ->
                if (idx == paths.length - 1) {
                    def file = new File(currentDir, it)
                    if (!file.exists()) {
                        if (isClassEntry(zipEntry) || !canTransformEntry(zipEntry)) {
                            FileUtils.copyInputStreamToFile(zipFile.getInputStream(zipEntry), file)
                        } else {
                            transformEntry(file, zipFile, zipEntry)
                        }
                    } else if (canTransformEntry(zipEntry)) {
                        transformEntry(file, zipFile, zipEntry)
                    }
                } else {
                    def file = new File(currentDir, it);
                    if (!file.exists()) {
                        FileUtils.forceMkdir(file)
                    }
                    currentDir = file
                }
        }
    }


    protected boolean isJar(File file) {
        return "jar" == FilenameUtils.getExtension(file.name)
    }

    protected boolean isClassEntry(ZipEntry zipEntry) {
        return zipEntry.name.endsWith(".class")
    }

    protected boolean canTransformEntry(ZipEntry zipEntry) {
        transformers.any { it.canTransformEntry(zipEntry.name) }
    }

    protected void transformEntry(File destFile, ZipFile zipFile, ZipEntry zipEntry) {
        def transformer = transformers.find { it.canTransformEntry(zipEntry.name) }
        transformer.transform(destFile, zipFile, zipEntry)
    }

}
