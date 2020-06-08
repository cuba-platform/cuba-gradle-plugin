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

import org.apache.commons.io.IOUtils
import org.apache.commons.io.filefilter.WildcardFileFilter

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

class MergeResourceTransformer implements ResourceTransformer {
    protected final WildcardFileFilter wildcardsFilter

    MergeResourceTransformer() {
        this(Collections.emptyList())
    }

    MergeResourceTransformer(List<String> wildcards) {
        def newWildcards = new ArrayList<String>()
        newWildcards.add("META-INF/spring.schemas")
        newWildcards.add("META-INF/spring.handlers")
        newWildcards.add("META-INF/services/org.apache.lucene.codecs.PostingsFormat")
        newWildcards.add("META-INF/services/org.apache.lucene.codecs.DocValuesFormat")
        newWildcards.add("META-INF/services/org.apache.lucene.codecs.Codec")
        newWildcards.add("META-INF/services/javax.script.ScriptEngineFactory")
        newWildcards.addAll(wildcards)
        this.wildcardsFilter = new WildcardFileFilter(newWildcards)
    }

    @Override
    boolean canTransformEntry(String path) {
        return wildcardsFilter.accept(null, path.startsWith("/") ? path.substring(1) : path)
    }

    @Override
    void transform(Path toPath, Path fromPath) {
        if (Files.exists(toPath)) {
            def destLines = Files.readAllLines(toPath)
            def sourceLines = Files.readAllLines(fromPath)
            def resultLines = new ArrayList<>(destLines)
            resultLines.addAll(sourceLines)

            ByteArrayOutputStream byteOutput = new ByteArrayOutputStream();
            IOUtils.writeLines(resultLines, null, byteOutput, StandardCharsets.UTF_8)
            Files.copy(new ByteArrayInputStream(byteOutput.toByteArray()), toPath, StandardCopyOption.REPLACE_EXISTING)

        } else {
            Files.copy(fromPath, toPath)
        }
    }
}
