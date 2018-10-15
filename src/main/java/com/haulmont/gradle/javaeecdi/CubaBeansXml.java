/*
 * Copyright (c) 2008-2018 Haulmont.
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

package com.haulmont.gradle.javaeecdi;

import org.apache.commons.io.IOUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.net.URL;

import static org.apache.commons.io.FileUtils.writeByteArrayToFile;

public class CubaBeansXml extends DefaultTask {

    public static final String NAME = "beansXml";

    public CubaBeansXml() {
        setGroup("Compile");
        setDescription("Generates beans.xml file to disable JavaEE CDI");
    }

    @OutputDirectory
    public File getOutputDir() {
        return new File(getProject().getBuildDir(), "beans-xml");
    }

    @TaskAction
    public void generate() {
        File beansXmlDir = getOutputDir();

        File file = new File(beansXmlDir, "META-INF/beans.xml");
        //noinspection ResultOfMethodCallIgnored
        file.getParentFile().mkdirs();

        try {
            URL resource = CubaBeansXml.class.getResource("/javaeecdi/beans.xml");
            writeByteArrayToFile(file, IOUtils.toByteArray(resource));
        } catch (IOException e) {
            throw new GradleException("Unable to create beans.xml", e);
        }
    }
}