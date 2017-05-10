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

package com.haulmont.gradle.libs;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.gradle.api.GradleException;

import java.io.File;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class DependencyResolver {
    private static final Pattern LIBRARY_PATTERN = Pattern.compile("((?:(?!-\\d)\\S)+)-(\\S*\\d\\S*(?:-SNAPSHOT)?)\\.jar$");
    private static final Pattern PLATFORM_VERSION_PATTERN = Pattern.compile("(\\S+)-(((linux)|(windows)|(macosx)|(android))-[A-Za-z0-9_]+)?");

    private static final Pattern LIBRARY_SNAPSHOT_PATTERN = Pattern.compile("((?:(?!-\\d)\\S)+)-(?:SNAPSHOT)\\.jar$");
    private static final Pattern LIBRARY_WITHOUT_VERSION_PATTERN = Pattern.compile("((?:(?!-\\d)\\S)+)\\.jar$");
    private static final Pattern DIGITAL_PATTERN = Pattern.compile("\\d+");

    private static final String VERSION_SPLIT_PATTERN = "[.\\-]";

    public static String getLibraryPlatform(String libraryVersion) {
        if (libraryVersion == null) {
            return null;
        }

        Matcher versionMatcher = PLATFORM_VERSION_PATTERN.matcher(libraryVersion);
        if (versionMatcher.matches()) {
            return versionMatcher.group(2);
        }

        return null;
    }

    public static LibraryDefinition getLibraryDefinition(String libraryName) {
        Matcher m = LIBRARY_PATTERN.matcher(libraryName);
        if (m.matches()) {
            String currentLibName = m.group(1);
            String currentLibVersion = m.group(2);

            if (currentLibName != null && currentLibVersion != null) {
                return new LibraryDefinition(currentLibName, currentLibVersion);
            }
        }

        Matcher sm = LIBRARY_SNAPSHOT_PATTERN.matcher(libraryName);
        if (sm.matches()) {
            String currentLibName = sm.group(1);

            if (currentLibName != null) {
                return new LibraryDefinition(currentLibName, "SNAPSHOT");
            }
        }

        Matcher nvm = LIBRARY_WITHOUT_VERSION_PATTERN.matcher(libraryName);
        if (nvm.matches()) {
            String currentLibName = nvm.group(1);

            if (currentLibName != null) {
                return new LibraryDefinition(currentLibName);
            }
        }

        throw new GradleException("Unable to get library definition for " + libraryName);
    }

    public static String getLowestVersion(String aLibraryVersion, String bLibraryVersion) {
        String[] labelAVersionArray = aLibraryVersion.split(VERSION_SPLIT_PATTERN);
        String[] labelBVersionArray = bLibraryVersion.split(VERSION_SPLIT_PATTERN);

        int maxLengthOfBothArrays;
        if (labelAVersionArray.length >= labelBVersionArray.length) {
            maxLengthOfBothArrays = labelAVersionArray.length;
        } else {
            maxLengthOfBothArrays = labelBVersionArray.length;

            String temp = aLibraryVersion;
            aLibraryVersion = bLibraryVersion;
            bLibraryVersion = temp;

            String[] tempArr = labelAVersionArray;
            labelAVersionArray = labelBVersionArray;
            labelBVersionArray = tempArr;
        }

        for (int i = 0; i < maxLengthOfBothArrays; i++) {
            if (i < labelBVersionArray.length) {
                String aVersionPart = labelAVersionArray[i];
                String bVersionPart = labelBVersionArray[i];

                if ("SNAPSHOT".equals(aVersionPart)) {
                    return bLibraryVersion;
                } else if ("SNAPSHOT".equals(bVersionPart)) {
                    return aLibraryVersion;
                }

                if (aVersionPart.startsWith("RC") && bVersionPart.startsWith("RC")) {
                    aVersionPart = aVersionPart.substring(2);
                    bVersionPart = bVersionPart.substring(2);
                }

                Matcher matcherA = DIGITAL_PATTERN.matcher(aVersionPart);
                Matcher matcherB = DIGITAL_PATTERN.matcher(bVersionPart);

                if (matcherA.matches() && !matcherB.matches())
                    return bLibraryVersion; //labelA = number, labelB = string
                if (!matcherA.matches() && matcherB.matches())
                    return aLibraryVersion;
                //labelA = string, labelB = number

                if (matcherA.matches()) {
                    // convert parts to integer
                    int libAVersionNumber = Integer.parseInt(aVersionPart);
                    int libBVersionNumber = Integer.parseInt(bVersionPart);

                    if (libAVersionNumber > libBVersionNumber) {
                        return bLibraryVersion;
                    }

                    if (libAVersionNumber < libBVersionNumber) {
                        return aLibraryVersion;
                    }
                } else {
                    // both labels are numbers or strings

                    int compare = aVersionPart.compareTo(bVersionPart);

                    if (compare > 0)
                        return bLibraryVersion;
                    if (compare < 0)
                        return aLibraryVersion;
                }

                if (i == maxLengthOfBothArrays - 1) { //equals
                    return aLibraryVersion;
                }
            } else {
                if (i < labelAVersionArray.length) {
                    String part = labelAVersionArray[i];
                    if (part.startsWith("RC")) {
                        return aLibraryVersion;
                    }
                }

                return bLibraryVersion; // labelAVersionArray.length > labelBVersionArray.length
            }
        }

        return aLibraryVersion;
    }

    private Consumer<String> logger;
    private File libraryRoot;

    public DependencyResolver(File libraryRoot, Consumer<String> logger) {
        this.logger = logger;
        this.libraryRoot = libraryRoot;
    }

    public void resolveDependencies(File libDir, List<String> copied) {
        List<String> copiedLibNames = copied.stream()
                .map(it -> getLibraryDefinition(it).getName())
                .collect(Collectors.toList());

        File[] libFiles = libDir.listFiles(file ->
                file.isFile() && file.getName().endsWith(".jar")
        );

        List<String> allLibraryNames = new ArrayList<>();
        if (libFiles != null) {
            for (File file : libFiles) {
                allLibraryNames.add(file.getName());
            }
        }

        Set<String> libraryNames = new LinkedHashSet<>();
        for (String copiedLibName : copiedLibNames) {
            libraryNames.addAll(allLibraryNames.stream()
                    .filter(libName -> libName.startsWith(copiedLibName))
                    .collect(Collectors.toList())
            );
        }

        if (logger != null) {
            logger.accept("[DependencyResolver] check libraries: " + StringUtils.join(libraryNames, ','));
        }

        // file names to remove
        Set<String> removeSet = new HashSet<>();
        // key - nameOfLib , value = list of matched versions
        Map<String, List<String>> versionsMap = new HashMap<>();

        for (String libraryName : libraryNames) {
            LibraryDefinition curLibDef = getLibraryDefinition(libraryName);

            String currentLibName = curLibDef.getName();
            String currentLibVersion = curLibDef.getVersion();
            //fill versionsMap
            List<String> tempList = versionsMap.get(currentLibName);
            if (tempList != null)
                tempList.add(currentLibVersion);
            else {
                tempList = new LinkedList<>();
                tempList.add(currentLibVersion);
                versionsMap.put(currentLibName, tempList);
            }
        }

        String path = libDir.getAbsolutePath();
        String relativePath = libraryRoot != null ? path.substring(libraryRoot.getAbsolutePath().length()) : path;

        for (Map.Entry<String, List<String>> entry : versionsMap.entrySet()) {
            String key = entry.getKey();
            List<String> versionsList = entry.getValue();

            for (int i = 0; i < versionsList.size(); i++) {
                for (int j = i + 1; j < versionsList.size(); j++) {
                    String iPlatform = getLibraryPlatform(versionsList.get(i));
                    String jPlatform = getLibraryPlatform(versionsList.get(j));

                    if (!Objects.equals(iPlatform, jPlatform)) {
                        continue;
                    }

                    String versionToDelete = getLowestVersion(versionsList.get(i), versionsList.get(j));
                    if (versionToDelete != null) {
                        versionToDelete = key + "-" + versionToDelete + ".jar";
                        removeSet.add(versionToDelete);

                        String aNameLibrary = key + "-" + versionsList.get(i) + ".jar";
                        String bNameLibrary = key + "-" + versionsList.get(j) + ".jar";
                        if (logger != null) {
                            logger.accept(String.format("[DependencyResolver] library %s/%s conflicts with %s",
                                    relativePath, aNameLibrary, bNameLibrary));
                        }
                    }
                }
            }
        }

        for (String fileName : removeSet) {
            FileUtils.deleteQuietly(new File(path, fileName));
            if (logger != null) {
                logger.accept(String.format("[DependencyResolver] remove library %s/%s", relativePath, fileName));
            }
        }
    }
}