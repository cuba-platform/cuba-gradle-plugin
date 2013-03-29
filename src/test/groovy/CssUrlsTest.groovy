/*
 * Copyright (c) 2013 Haulmont Technology Ltd. All Rights Reserved.
 * Haulmont Technology proprietary and confidential.
 * Use is subject to license terms.
 */

import org.apache.commons.io.IOUtils

import java.util.regex.Pattern

/**
 * @author artamonov
 * @version $Id$
 */
class CssUrlsTest extends GroovyTestCase {
    void testUrlRegex() {
        String cssContent = IOUtils.toString(getClass().getResourceAsStream('css-version-test.css'))

        // replace comments
        cssContent = cssContent.replaceAll('/\\*.*\\*/', '')

        def urls = ['picture1.png', 'url/path/picture2.png', 'picture3.png', 'picture4.png']
        def foundUrls = []

        // find urls
        Pattern urlPattern = Pattern.compile('url\\([\\s]*[\'|\"]?([^\\)\\ \'\"]*)[\'|\"]?[\\s]*\\)')

        def matcher = urlPattern.matcher(cssContent)
        int index = 0;
        while (matcher.find()) {
            String url = matcher.group(1)
            cssContent.replace(url, url + '')

            assertFalse(foundUrls.contains(url))
            assertEquals(urls[index], url)

            index++;
            foundUrls.add(url)
        }

        assertEquals(foundUrls.size(), urls.size())
    }
}