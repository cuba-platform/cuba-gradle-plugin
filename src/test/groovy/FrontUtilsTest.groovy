import com.haulmont.gradle.utils.FrontUtils

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

class FrontUtilsTest extends GroovyTestCase {

    def html = """<!DOCTYPE html><html><head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, minimum-scale=1, initial-scale=1, user-scalable=yes">
  <title>Singlefront</title>
  <!--
    The `<base>` tag below is being changed in a build time.
    See the `basePath` property in `polymer.json`.
  -->
  <base href="/app-front/">
  <link rel="icon" href="images/favicon.ico">
</head>
<body>
  <singlefront-shell api-url="/app/rest/"></singlefront-shell>
</body></html>"""

    void testRewriteBaseUrl() {
        def result = FrontUtils.rewriteBaseUrl(html, "/app/front/")
        assertEquals(html.replace("/app-front/", "<#if cubaFrontBaseUrl??>\${cubaFrontBaseUrl}<#else>/app/front/</#if>"), result)

        result = FrontUtils.rewriteBaseUrl(html, null)
        assertEquals(html.replace("/app-front/", "<#if cubaFrontBaseUrl??>\${cubaFrontBaseUrl}<#else>/app-front/</#if>"), result)
    }

    void testRewriteApiUrl() {
        def result = FrontUtils.rewriteApiUrl(html, "/app1/rest/")
        assertEquals(html.replace("/app/rest/", "<#if cubaFrontApiUrl??>\${cubaFrontApiUrl}<#else>/app1/rest/</#if>"), result)

        result = FrontUtils.rewriteApiUrl(html, null)
        assertEquals(html.replace("/app/rest/", "<#if cubaFrontApiUrl??>\${cubaFrontApiUrl}<#else>/app/rest/</#if>"), result)
    }
}