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

import org.apache.commons.io.filefilter.WildcardFileFilter

import java.nio.file.Path

class ExcludeResourceTransformer implements ResourceTransformer {
    protected final WildcardFileFilter wildcardsFilter

    ExcludeResourceTransformer() {
        this(Collections.emptyList())
    }

    ExcludeResourceTransformer(List<String> wildcards) {
        def newWildcards = new ArrayList<String>()
        newWildcards.add('META-INF/LICENSE')
        newWildcards.add('META-INF/LICENSE.txt')
        newWildcards.add('LICENSE')
        newWildcards.add('META-INF/INDEX.LIST')
        newWildcards.add('META-INF/*.SF')
        newWildcards.add('META-INF/*.DSA')
        newWildcards.add('META-INF/*.RSA')
        newWildcards.addAll(wildcards)
        this.wildcardsFilter = new WildcardFileFilter(newWildcards)
    }

    @Override
    boolean canTransformEntry(String path) {
        return wildcardsFilter.accept(null, path.startsWith("/") ? path.substring(1) : path)
    }

    @Override
    void transform(Path toPath, Path fromPath) {
        //Do nothing
    }
}
