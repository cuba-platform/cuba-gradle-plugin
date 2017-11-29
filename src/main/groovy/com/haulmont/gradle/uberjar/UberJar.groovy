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

package com.haulmont.gradle.uberjar

import org.apache.commons.io.IOUtils
import org.gradle.api.logging.Logger
import org.gradle.internal.logging.progress.ProgressLogger

import java.nio.file.*

class UberJar {
    protected final Logger logger
    protected final Path toPath
    protected final List<ResourceTransformer> transformers
    protected final String jarName
    protected FileSystem toJarFs
    protected ProgressLogger progressLogger

    UberJar(Logger logger, ProgressLogger progressLogger, Path toPath, String jarName, List<ResourceTransformer>
            transformers) {
        this.progressLogger = progressLogger
        this.logger = logger
        this.toPath = toPath
        if (transformers == null) {
            this.transformers = Collections.emptyList();
        } else {
            this.transformers = transformers
        }
        if (Files.notExists(this.toPath)) {
            Files.createDirectories(this.toPath)
        }
        this.jarName = jarName
    }

    public void copyJars(Path fromPath, ResourceLocator locator) {
        execute({
            def stream = Files.newDirectoryStream(fromPath, "*.{jar}")
            try {
                List<Path> paths = new ArrayList<>()
                for (path in stream) {
                    paths.add(path)
                }
                def stepSize = paths.size() / 5
                def jarIndex = 0
                int currentPercent = 0, nextPercent
                progressLogger.progress("Pack ${jarName} libs progress: $currentPercent%")
                for (path in paths) {
                    nextPercent = jarIndex / stepSize
                    if (nextPercent != currentPercent) {
                        progressLogger.progress("Pack ${jarName} libs progress: ${nextPercent * 20}%")
                    }
                    visitJar(path, locator)
                    currentPercent = nextPercent
                    jarIndex++
                }
                progressLogger.progress("Pack ${jarName} libs progress: 100%")
            } finally {
                IOUtils.closeQuietly(stream)
            }
        })
    }

    public void copyFiles(Path fromPath, ResourceLocator locator) {
        execute({
            def toRootPath = toJarRoot()
            if (Files.isDirectory(fromPath)) {
                for (path in Files.walk(fromPath)) {
                    def relativePath = fromPath.relativize(path)
                    def toPath
                    if (locator != null && locator.canRelocateEntry(relativePath.toString())) {
                        toPath = locator.relocate(toRootPath, relativePath)
                    } else {
                        toPath = toRootPath.resolve(relativePath.toString())
                    }
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(toPath)
                    } else {
                        Path parentPath = toPath.getParent()
                        if (parentPath != null && Files.notExists(parentPath)) {
                            Files.createDirectories(parentPath)
                        }
                        Files.copy(path, toPath, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            } else {
                def relativePath = fromPath.getName(fromPath.getNameCount() - 1)
                def toPath
                if (locator != null && locator.canRelocateEntry(relativePath.toString())) {
                    toPath = locator.relocate(toRootPath, relativePath)
                } else {
                    toPath = toRootPath.resolve(relativePath.toString())
                }
                Files.copy(fromPath, toPath, StandardCopyOption.REPLACE_EXISTING)
            }
        })
    }

    public void createManifest(String mainClass) {
        execute({
            def toRootPath = toJarRoot()
            def manifestPath = toRootPath.resolve("META-INF/MANIFEST.MF")
            Files.write(manifestPath, Collections.singletonList("Main-Class: $mainClass"), StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE)
        })
    }

    protected void execute(Runnable runnable) {
        Path jarPath = toPath.resolve(jarName)
        toJarFs = createZipFileSystem(jarPath, Files.notExists(jarPath))
        try {
            runnable.run()
        } finally {
            IOUtils.closeQuietly(toJarFs)
            toJarFs = null
        }
    }

    protected Path toJarRoot() {
        return ++toJarFs.rootDirectories.iterator()
    }

    protected void visitJar(Path jarPath, ResourceLocator locator) {
        def fromJarFs = createZipFileSystem(jarPath, false)
        try {
            for (path in Files.walk(++fromJarFs.rootDirectories.iterator())) {
                if (Files.isDirectory(path)) {
                    visitDirectory(path, locator)
                } else {
                    visitFile(path, locator)
                }
            }
        } finally {
            IOUtils.closeQuietly(fromJarFs)
        }
    }

    protected void visitDirectory(Path path, ResourceLocator locator) {
        def toRootPath = toJarRoot()
        def toPath
        if (locator != null && locator.canRelocateEntry(path.toString())) {
            toPath = locator.relocate(toRootPath, path)
        } else {
            toPath = toRootPath.resolve(path.toString())
        }
        Files.createDirectories(toPath)
    }

    protected void visitFile(Path path, ResourceLocator locator) {
        def toRootPath = toJarRoot()
        def toPath
        if (locator != null && locator.canRelocateEntry(path.toString())) {
            toPath = locator.relocate(toRootPath, path)
        } else {
            toPath = toRootPath.resolve(path.toString())
        }
        if (Files.notExists(toPath)) {
            if (isClassEntry(path) || !canTransformEntry(path)) {
                Files.copy(path, toPath, StandardCopyOption.REPLACE_EXISTING)
            } else {
                transformEntry(toPath, path)
            }
        } else if (canTransformEntry(path)) {
            transformEntry(toPath, path)
        }
    }

    protected boolean canTransformEntry(Path path) {
        return transformers.any {
            it.canTransformEntry(path.toString())
        }
    }

    protected void transformEntry(Path toPath, Path fromPath) {
        def transformer = transformers.find { it.canTransformEntry(fromPath.toString()) }
        transformer.transform(toPath, fromPath)
    }

    protected static FileSystem createZipFileSystem(Path path, boolean create) {
        String uriString = path.toUri().path.replace(" ","%20")
        URI uri = URI.create("jar:file:$uriString")
        Map<String, String> env = new HashMap<>()
        if (create) {
            env.put("create", "true")
        }
        return FileSystems.newFileSystem(uri, env)
    }

    protected static boolean isClassEntry(Path path) {
        return path.toString().endsWith(".class")
    }
}
