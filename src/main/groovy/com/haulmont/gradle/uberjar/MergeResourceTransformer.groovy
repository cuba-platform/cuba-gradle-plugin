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
import org.apache.commons.io.IOUtils
import org.apache.commons.io.filefilter.WildcardFileFilter

import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class MergeResourceTransformer implements UnpackTransformer {
    protected final WildcardFileFilter wildcardsFilter

    MergeResourceTransformer() {
        this(null)
    }

    MergeResourceTransformer(List<String> wildcards) {
        def newWildcards = new ArrayList<String>()
        newWildcards.add("META-INF/spring.schemas")
        newWildcards.add("META-INF/spring.handlers")
        if (wildcards != null) {
            newWildcards.addAll(wildcards)
        }
        this.wildcardsFilter = new WildcardFileFilter(newWildcards)
    }

    @Override
    boolean canTransformEntry(String path) {
        return wildcardsFilter.accept(null, path)
    }

    @Override
    void transform(File destFile, ZipFile zipFile, ZipEntry zipEntry) {
        if (destFile.exists()) {
            def destLines = FileUtils.readLines(destFile)
            def sourceLines = IOUtils.readLines(zipFile.getInputStream(zipEntry))
            def resultLines = new ArrayList<>(destLines)
            resultLines.addAll(sourceLines)
            FileUtils.writeLines(destFile, resultLines, false)
        } else {
            FileUtils.copyInputStreamToFile(zipFile.getInputStream(zipEntry), destFile)
        }
    }
}
