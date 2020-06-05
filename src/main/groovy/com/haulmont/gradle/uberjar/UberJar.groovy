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

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.io.IOUtils
import org.gradle.api.GradleException
import org.gradle.api.logging.Logger

import java.nio.file.*
import java.util.jar.Attributes
import java.util.jar.Manifest
import java.util.stream.Stream

class UberJar {
    protected final Logger logger
    protected final Path toPath
    protected final List<ResourceTransformer> transformers
    protected final String jarName
    protected FileSystem toJarFs

    UberJar(Logger logger, Path toPath, String jarName, List<ResourceTransformer> transformers) {
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
                logger.warn("[CubaUberJAR] Pack libs progress: $currentPercent%")
                for (path in paths) {
                    nextPercent = jarIndex / stepSize
                    if (nextPercent != currentPercent) {
                        logger.warn("[CubaUberJAR] Pack libs progress: ${nextPercent * 20}%")
                    }
                    visitJar(path, locator)
                    currentPercent = nextPercent
                    jarIndex++
                }
                logger.warn("[CubaUberJAR] Pack libs progress: 100%")
            } finally {
                IOUtils.closeQuietly(stream)
            }
        })
    }

    public void copyFiles(Path fromPath, ResourceLocator locator) {
        execute({
            def toRootPath = toJarRoot()
            if (Files.isDirectory(fromPath)) {
                def stream = Files.walk(fromPath)
                try {
                    for (path in stream) {
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
                } finally {
                    closeStream(stream)
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

    public void copy(InputStream inputStream, ResourceLocator locator) {
        execute({
            def toRootPath = toJarRoot()
            def toPath
            if (locator == null) {
                throw new GradleException("ResourceLocator is null")
            }
            toPath = locator.relocate(toRootPath, null)
            Files.copy(inputStream, toPath, StandardCopyOption.REPLACE_EXISTING)
        })
    }

    public void createManifest(String mainClass) {
        execute({
            def toRootPath = toJarRoot()
            def manifestPath = toRootPath.resolve("META-INF/MANIFEST.MF")
            Files.deleteIfExists(manifestPath)
        })
        execute({
            def toRootPath = toJarRoot()
            def manifestPath = toRootPath.resolve("META-INF/MANIFEST.MF")

            ByteArrayOutputStream byteOutput = new ByteArrayOutputStream()
            Manifest manifest = new Manifest()
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, '1.0')
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass)
            manifest.write(byteOutput)

            Files.copy(new ByteArrayInputStream(byteOutput.toByteArray()), manifestPath, StandardCopyOption.REPLACE_EXISTING)
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
            for (zipEntry in getZipEntries(jarPath)) {
                Path path = fromJarFs.getPath(zipEntry)
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

    protected List<String> getZipEntries(Path path) {
        List<String> jarEntries = new LinkedList<>()
        ZipArchiveInputStream zipStream = null
        try {
            zipStream = new ZipArchiveInputStream(Files.newInputStream(path))
            ZipArchiveEntry zipEntry
            while ((zipEntry = zipStream.nextZipEntry) != null) {
                String entryName = zipEntry.name
                if (entryName.startsWith("/")) {
                    jarEntries.add(entryName)
                } else {
                    jarEntries.add("/${entryName}")
                }
            }
        } finally {
            IOUtils.closeQuietly(zipStream)
        }
        return jarEntries
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
                Path parentPath = toPath.getParent()
                if (parentPath != null && Files.notExists(parentPath)) {
                    Files.createDirectories(parentPath)
                }
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
        String uriString = path.toUri().path.replace(" ", "%20")
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

    protected static void closeStream(Stream stream) {
        try {
            stream.close()
        } catch (Exception e) {
            //Do nothing
        }
    }
}
