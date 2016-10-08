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

import com.haulmont.gradle.utils.CssUrlInspector
import org.apache.commons.io.IOUtils

class CssUrlsTest extends GroovyTestCase {
    void testUrlMatcher() {
        String cssContent = IOUtils.toString(getClass().getResourceAsStream('css-version-test.css'))

        def urls = ['picture1.png', 'url/path/picture2.png', 'picture3.png', 'picture4.png']
        def foundUrls = []

        def inspector = new CssUrlInspector()

        for (String url : inspector.getUrls(cssContent)) {
            assertFalse(foundUrls.contains(url))
            assertTrue(urls.contains(url))

            foundUrls.add(url)
        }

        assertEquals(foundUrls.size(), urls.size())
    }
}