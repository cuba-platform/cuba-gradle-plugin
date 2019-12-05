/*
 * Copyright (c) 2008-2019 Haulmont.
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

import org.gradle.api.Project;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;

public class WebXmlUtils {

    public static String getParamValueFromWebXml(Project project, String paramName) {
        Document document;
        try {
            DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            File webXmlFile = project.file(project.getProjectDir() + "/web/WEB-INF/web.xml");
            if (!webXmlFile.exists()) {
                return null;
            }
            document = builder.parse(webXmlFile);
        } catch (SAXException | ParserConfigurationException | IOException e) {
            throw new RuntimeException("Can't get properties files names from core web.xml", e);
        }

        NodeList nList = document.getElementsByTagName("context-param");
        for (int i = 0; i < nList.getLength(); i++) {
            Element nNode = (Element) nList.item(i);
            String currentParamName = nNode.getElementsByTagName("param-name").item(0).getTextContent();
            if (paramName.equals(currentParamName)) {
                return nNode.getElementsByTagName("param-value").item(0).getTextContent();
            }
        }
        return null;
    }
}
