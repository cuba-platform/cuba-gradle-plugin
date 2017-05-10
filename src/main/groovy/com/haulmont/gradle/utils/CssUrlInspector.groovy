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
 */

package com.haulmont.gradle.utils

import groovy.transform.CompileStatic

import java.util.regex.Pattern

@CompileStatic
class CssUrlInspector {
    private static final Pattern CSS_URL_PATTERN =
            Pattern.compile('url\\((?!((//)|(http://)|(https://)))[\\s]*[\'|\"]?([^) \'\"]*)[\'|\"]?[\\s]*\\)')

    Set<String> getUrls(String cssContent) {
        // replace comments
        cssContent = cssContent.replaceAll('/\\*.*\\*/', '')

        def matcher = CSS_URL_PATTERN.matcher(cssContent)
        def urls = new HashSet<String>()
        while (matcher.find()) {
            urls.add(matcher.group(5))
        }
        return urls
    }
}