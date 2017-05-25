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

import java.nio.file.Path

class SharedResourceLocator implements ResourceLocator {
    String relocationPath
    String webRelocationPath

    SharedResourceLocator(String relocationPath, String webRelocationPath) {
        this.relocationPath = relocationPath
        this.webRelocationPath = webRelocationPath
    }

    @Override
    boolean canRelocateEntry(String path) {
        return true
    }

    @Override
    Path relocate(Path toRootPath, Path fromPath) {
        String path = fromPath.toString()
        if (path.startsWith("/VAADIN/")) {
            return toRootPath.resolve(webRelocationPath).resolve(path.substring(1))
        } else {
            return toRootPath.resolve(relocationPath).resolve(path.substring(1))
        }
    }
}
