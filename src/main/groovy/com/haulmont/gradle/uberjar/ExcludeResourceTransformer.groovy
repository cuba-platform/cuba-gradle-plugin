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
        newWildcards.add('META-INF/about.html')
        newWildcards.add('readme.html')
        newWildcards.add('README')
        newWildcards.add('README.txt')
        newWildcards.add('LICENSE')
        newWildcards.add('NOTICE')
        newWildcards.add('about.html')
        newWildcards.add('license.txt')
        newWildcards.add('license.html')
        newWildcards.add('INSTALL.html')
        newWildcards.add('VERSION.txt')
        newWildcards.add('LICENSE.APACHE2')
        newWildcards.add('META-INF/INDEX.LIST')
        newWildcards.add('META-INF/LICENSE.TXT')
        newWildcards.add('META-INF/license.txt')
        newWildcards.add('META-INF/notice.txt')
        newWildcards.add('META-INF/NOTICE.txt')
        newWildcards.add('META-INF/NOTICE')
        newWildcards.add('META-INF/README')
        newWildcards.add('META-INF/README.txt')
        newWildcards.add('META-INF/README.')
        newWildcards.add('META-INF/DEPENDENCIES')
        newWildcards.add('META-INF/CHANGES')
        newWildcards.add('META-INF/LICENSE.APACHE2')
        newWildcards.add('META-INF/LICENSE-W3C-TEST')
        newWildcards.add('META-INF/LICENSE-LGPL-3.txt')
        newWildcards.add('META-INF/LICENSE-LGPL-2.1.txt')
        newWildcards.add('META-INF/LICENSE-GPL-3.txt')
        newWildcards.add('META-INF/LICENSE-GPL-2.txt')
        newWildcards.add('META-INF/hsqldb_lic.txt')
        newWildcards.add('META-INF/hypersonic_lic.txt')
        newWildcards.add('META-INF/DEPENDENCIES')
        newWildcards.add('META-INF/CHANGES')
        newWildcards.add('META-INF/ASL2.0')
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
