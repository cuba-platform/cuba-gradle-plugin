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

import org.apache.commons.io.input.BOMInputStream;
import org.apache.commons.text.StringSubstitutor;
import org.apache.commons.text.StringTokenizer;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.ResolvedConfiguration;
import org.gradle.api.artifacts.ResolvedDependency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

public class AppPropertiesLoader {
    private static final Logger log = LoggerFactory.getLogger(AppPropertiesLoader.class);

    protected static final String APP_PROPERTIES_CONFIG_PROPERTY_NAME = "appPropertiesConfig";

    public Properties initProperties(Project project, String appHomeDir) {
        Properties properties = new Properties();
        String propertiesConfigName = getPropertiesConfigName(project);
        properties.putAll(getPropertiesFromConfig(propertiesConfigName, project, appHomeDir));

        return properties;
    }

    protected Properties getPropertiesFromConfig(String propertiesConfigName, Project project, String appHomeDir) {
        SpringProfileSpecificNameResolver nameResolver = new SpringProfileSpecificNameResolver(project);
        Properties properties = new Properties();
        StringTokenizer tokenizer = new StringTokenizer(propertiesConfigName);
        tokenizer.setQuoteChar('"');
        for (String str : tokenizer.getTokenArray()) {
            log.trace("Processing properties location: {}", str);
            String baseName = StringSubstitutor.replaceSystemProperties(str);
            for (String name : nameResolver.getDerivedNames(baseName)) {
                try (InputStream stream = getPropertiesInputStream(project, appHomeDir, name)) {
                    if (stream == null) {
                        log.trace(String.format("Property file '%s' was not found in the project. Skip it.", name));
                        continue;
                    }

                    log.info("Loading app properties from {}", name);
                    BOMInputStream bomInputStream = new BOMInputStream(stream);
                    try (Reader reader = new InputStreamReader(bomInputStream, StandardCharsets.UTF_8)) {
                        properties.load(reader);
                    }
                } catch (FileNotFoundException e) {
                    log.trace("Property file '{}' was not found in the project. Skip it. Error: {}", name, e.getMessage());
                } catch (IOException e) {
                    throw new RuntimeException("Unable to read properties from stream", e);
                }
            }
        }
        return properties;
    }

    protected String getPropertiesConfigName(Project project) {
        return WebXmlUtils.getParamValueFromWebXml(project, APP_PROPERTIES_CONFIG_PROPERTY_NAME);
    }

    protected InputStream getPropertiesInputStream(Project project, String appHomeDir, String propertyStr) throws FileNotFoundException {
        InputStream stream = null;
        if (propertyStr.startsWith("classpath:")) {
            stream = getPropertiesFromClasspath(project, propertyStr);
            if (stream == null) {
                log.info(String.format("Property file '%s' was not found in the project. Searching in JARs.", propertyStr));
                stream = AppPropertiesLoader.searchForPropertiesInJars(project, propertyStr.replace("classpath:", ""));
            }
        } else if (propertyStr.startsWith("file:")) {
            stream = getPropertiesFromFile(project, appHomeDir, propertyStr);
        } else if (propertyStr.startsWith("/WEB-INF/")) {
            stream = getPropertiesFromWeb(project, propertyStr);
        }

        return stream;
    }

    protected InputStream getPropertiesFromFile(Project project, String appHomeDir, String filePath) throws FileNotFoundException {
        filePath = filePath.replace("file:", "");
        if (filePath.contains("${app.home}")) {
            filePath = filePath.replace("${app.home}", appHomeDir);
            return new FileInputStream(project.file(filePath));
        }
        return null;
    }

    protected InputStream getPropertiesFromWeb(Project project, String filePath) throws FileNotFoundException {
        return new FileInputStream(project.file(project.getProjectDir() + "/web/" + filePath));
    }

    protected InputStream getPropertiesFromClasspath(Project project, String classpath) throws FileNotFoundException {
        classpath = classpath.replace("classpath:", "");
        return new FileInputStream(project.file(project.getProjectDir() + "/src/" + classpath));
    }

    protected static InputStream searchForPropertiesInJars(Project project, String propsClasspath) {
        Configuration configuration = project.getConfigurations().getByName("compile");
        ResolvedConfiguration resolvedConf = configuration.getResolvedConfiguration();
        Set<ResolvedDependency> resolvedDependencies = resolvedConf.getFirstLevelModuleDependencies();

        return walkJarDependencies(resolvedDependencies, new HashSet<>(), propsClasspath);
    }

    protected static InputStream walkJarDependencies(Set<ResolvedDependency> dependencies,
                                                     Set<ResolvedArtifact> passedArtifacts, String propsClasspath) {
        for (ResolvedDependency dependency : dependencies) {
            walkJarDependencies(dependency.getChildren(), passedArtifacts, propsClasspath);

            for (ResolvedArtifact artifact : dependency.getAllModuleArtifacts()) {
                if (passedArtifacts.contains(artifact)) {
                    continue;
                }

                passedArtifacts.add(artifact);

                if (artifact.getFile().getName().endsWith(".jar")) {
                    if (!checkManifest(artifact)) {
                        continue;
                    }
                    return getPropertiesFromJar(artifact, propsClasspath);
                }
            }
        }
        return null;
    }

    protected static InputStream getPropertiesFromJar(ResolvedArtifact artifact, String propsClasspath) {
        try (JarFile jarFile = new JarFile(artifact.getFile())) {
            ZipEntry propsEntry = jarFile.getEntry(propsClasspath);
            if (propsEntry == null) {
                return null;
            }
            log.info("Loading app properties from {}", propsClasspath);
            return jarFile.getInputStream(propsEntry);
        } catch (IOException e) {
            throw new RuntimeException(String.format("[CubaPlugin] Error occurred during properties searching at %s", artifact.getFile().getAbsolutePath()), e);
        }
    }

    protected static boolean checkManifest(ResolvedArtifact artifact) {
        try (JarFile jarFile = new JarFile(artifact.getFile())) {
            Manifest manifest = jarFile.getManifest();
            if (manifest == null) {
                return false;
            }
        } catch (IOException e) {
            throw new RuntimeException(String.format("[CubaPlugin] Error occurred during properties searching at %s", artifact.getFile().getAbsolutePath()), e);
        }
        return true;
    }
}
