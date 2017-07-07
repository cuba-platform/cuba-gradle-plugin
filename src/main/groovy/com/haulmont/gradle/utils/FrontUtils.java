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

package com.haulmont.gradle.utils;

import org.apache.commons.io.IOUtils;
import org.gradle.api.GradleException;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FrontUtils {
    public static final String BASE_TAG_REGEXP = "<base [^>]*href=\\\"(.*?)\\\"";
    public static final String API_URL_REGEXP = "<\\w+-shell [^>]*api-url=\\\"(.*?)\\\"";
    public static final String BASE_URL_VAR = "cubaFrontBaseUrl";
    public static final String API_URL_VAR = "cubaFrontApiUrl";

    public static String rewriteBaseUrl(String html, String defaultValue) {
        Matcher matcher = Pattern.compile(BASE_TAG_REGEXP).matcher(html);
        StringBuffer result = new StringBuffer();
        if (matcher.find()) {
            String expression = "<#if " + BASE_URL_VAR + "??>\\${" + BASE_URL_VAR + "}<#else>" + (defaultValue == null ? matcher.group(1) : defaultValue) + "</#if>";
            matcher.appendReplacement(result, matcher.group().replace(matcher.group(1), expression));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public static String rewriteApiUrl(String html, String defaultValue) {
        Matcher matcher = Pattern.compile(API_URL_REGEXP).matcher(html);
        StringBuffer result = new StringBuffer();
        if (matcher.find()) {
            String expression = "<#if " + API_URL_VAR + "??>\\${" + API_URL_VAR + "}<#else>" + (defaultValue == null ? matcher.group(1) : defaultValue) + "</#if>";
            matcher.appendReplacement(result, matcher.group().replace(matcher.group(1), expression));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public static String getFrontWebXml() {
        try {
            return IOUtils.toString(
                    FrontUtils.class.getClassLoader().getResourceAsStream("front-web.xml"));
        } catch (IOException e) {
            throw new GradleException("Web.xml not found", e);
        }
    }
}
