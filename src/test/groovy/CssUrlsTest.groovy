/*
 * Copyright (c) 2013 Haulmont Technology Ltd. All Rights Reserved.
 * Haulmont Technology proprietary and confidential.
 * Use is subject to license terms.
 */

import org.apache.commons.io.IOUtils

/**
 * @author artamonov
 * @version $Id$
 */
class CssUrlsTest extends GroovyTestCase {
    void testUrlMatcher() {
        String cssContent = IOUtils.toString(getClass().getResourceAsStream('css-version-test.css'))

        def urls = ['picture1.png', 'url/path/picture2.png', 'picture3.png', 'picture4.png']
        def foundUrls = []

        def inspector = new CubaWebScssThemeCreation.CssUrlInspector()

        for (String url : inspector.getUrls(cssContent)) {
            assertFalse(foundUrls.contains(url))
            assertTrue(urls.contains(url))

            foundUrls.add(url)
        }

        assertEquals(foundUrls.size(), urls.size())
    }
}