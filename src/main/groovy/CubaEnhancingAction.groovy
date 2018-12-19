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

import com.haulmont.gradle.enhance.CubaEnhancer
import groovy.io.FileType
import groovy.xml.QName
import groovy.xml.XmlUtil
import javassist.ClassPool
import org.apache.commons.io.FileUtils
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.FileTree
import org.gradle.api.tasks.SourceSet

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.regex.Pattern

class CubaEnhancingAction implements Action<Task> {

    protected static final String ABSTRACT_INSTANCE_FQN = 'com.haulmont.chile.core.model.impl.AbstractInstance'

    protected final Project project

    protected final String srcRoot
    protected final String classesRoot
    protected final SourceSet sourceSet

    protected final String persistenceConfig
    protected final String metadataConfig
    protected final String metadataPackageRegExp
    protected final File customClassesDir

    CubaEnhancingAction(Project project, String sourceSet) {
        this.project = project

        def mainSourceSet = sourceSet == 'main'

        srcRoot = mainSourceSet ? 'src' : 'test'
        classesRoot = mainSourceSet ? 'main' : 'test'
        this.sourceSet = mainSourceSet ? project.sourceSets.main : project.sourceSets.test

        def enhancingConfig = mainSourceSet ? project.entitiesEnhancing.main : project.entitiesEnhancing.test

        persistenceConfig = enhancingConfig.persistenceConfig
        metadataConfig = enhancingConfig.metadataConfig
        metadataPackageRegExp = enhancingConfig.metadataPackageRegExp
        customClassesDir = enhancingConfig.customClassesDir
    }

    @Override
    void execute(Task task) {
        def enhancedClasses = enhanceClasses()
        replaceAndCopyClasses(enhancedClasses)
    }

    protected List<String> enhanceClasses() {
        def outputDir = new File(enhancedDirPath)
        List<String> allClasses = []

        def javaOutputDir = getEntityClassesDir()

        project.logger.info('[CubaEnhancing] Entity classes directory: ' + javaOutputDir.absolutePath)

        def ownMetadataXmlFiles = getOwnMetadataXmlFiles()
        project.logger.info("[CubaEnhancing] Metadata XML files: ${ownMetadataXmlFiles}")

        File fullPersistenceXml = createFullPersistenceXml()

        if (javaOutputDir.exists()) {
            project.logger.info("[CubaEnhancing] Start EclipseLink enhancing")
            project.javaexec {
                main = 'org.eclipse.persistence.tools.weaving.jpa.CubaStaticWeave'
                classpath(
                        sourceSet.compileClasspath,
                        javaOutputDir
                )
                args "-loglevel"
                args "INFO"
                args "-persistenceinfo"
                args "$project.buildDir/tmp/persistence"
                args "$javaOutputDir"
                args enhancedDirPath
                debug = System.getProperty("debugEnhance") ? Boolean.valueOf(System.getProperty("debugEnhance")) : false
            }
        }

        // EclipseLink enhancer copies all classes to build/tmp/enhance-${classesRoot},
        // so we should delete files that are not in persistence.xml and metadata.xml
        def persistence = new XmlParser().parse(fullPersistenceXml)
        def persistenceUnit = persistence.'persistence-unit'[0]

        allClasses.addAll(persistenceUnit.'class'.collect { it.value()[0] })
        allClasses.addAll(getTransientEntities())
        // AbstractInstance is not registered but shouldn't be deleted
        allClasses.add(ABSTRACT_INSTANCE_FQN)

        if (outputDir.exists()) {
            outputDir.eachFileRecurse(FileType.FILES) { File file ->
                Path path = outputDir.toPath().relativize(file.toPath())
                String name = path.findAll().join('.')
                name = name.substring(0, name.lastIndexOf('.'))
                if (!allClasses.contains(name)) {
                    file.delete()
                }
            }
            // delete empty dirs
            List<File> emptyDirs = []
            outputDir.eachDirRecurse { File dir ->
                if (dir.listFiles({ File file -> !file.isDirectory() } as FileFilter).toList().isEmpty()) {
                    emptyDirs.add(dir)
                }
            }
            emptyDirs.reverse().each { File dir ->
                if (dir.listFiles().toList().isEmpty())
                    dir.delete()
            }
        }

        if (outputDir.exists()) {
            // run CUBA enhancing on all classes remaining in build/tmp/enhance-${classesRoot}
            project.logger.info("[CubaEnhancing] Start CUBA enhancing")

            ClassPool pool = new ClassPool(null)
            pool.appendSystemPath()

            for (file in sourceSet.compileClasspath) {
                pool.insertClassPath(file.getAbsolutePath())
            }

            pool.insertClassPath(javaOutputDir.getAbsolutePath())
            pool.insertClassPath(outputDir.getAbsolutePath())

            def cubaEnhancer = new CubaEnhancer(pool, outputDir.getAbsolutePath())
            cubaEnhancer.logger = project.logger

            for (className in allClasses) {
                def classFileName = className.replace('.', '/') + '.class'
                def classFile = new File(javaOutputDir, classFileName)

                if (classFile.exists()) {
                    // skip files from dependencies, enhance only classes from `javaOutputDir`
                    cubaEnhancer.run(className)
                }
            }
        }

        return allClasses
    }

    def replaceAndCopyClasses(List<String> enhancedClassesFqn) {
        def javaOutputDir = getEntityClassesDir()
        def legacyEnhDir = getLegacyEnhancedDir()

        enhancedClassesFqn.each { String classFqn ->
            def classPath = classFqn.replace('.', '/')

            Path srcFile = Paths.get("$enhancedDirPath/${classPath}.class")
            Path dstFile = Paths.get("$javaOutputDir/${classPath}.class")
            Path legacyDstFile = Paths.get("$legacyEnhDir/${classPath}.class")

            if (Files.exists(srcFile)) {
                Files.copy(srcFile, dstFile, StandardCopyOption.REPLACE_EXISTING)

                if (Files.notExists(legacyDstFile.getParent())) {
                    legacyDstFile.getParent().toFile().mkdirs()
                }
                Files.copy(srcFile, legacyDstFile, StandardCopyOption.REPLACE_EXISTING)
            }
        }

        try {
            FileUtils.deleteDirectory(new File(enhancedDirPath))
        } catch (IOException ignored) {
            project.logger.debug("Unable to remove directory with enhanced classes: $enhancedDirPath")
        }
    }

    private File getEntityClassesDir() {
        return customClassesDir ?: sourceSet.java.outputDir
    }

    private List<File> getOwnPersistenceXmlFiles() {
        List<File> files = []
        if (persistenceConfig) {
            List<String> fileNames = Arrays.asList(persistenceConfig.split(' '))
            fileNames.each { fileName ->
                File file = project.file("$srcRoot/$fileName")
                if (file.exists()) {
                    files.add(file)
                } else {
                    throw new IllegalArgumentException("File $file doesn't exist")
                }
            }
        } else {
            FileTree fileTree = project.fileTree(srcRoot).matching {
                include '**/*-persistence.xml'
                include '**/persistence.xml'
            }
            fileTree.each { files.add(it) }
        }
        return files
    }

    private File createFullPersistenceXml() {
        def fileNames = persistenceConfig ? persistenceConfig.tokenize() : null

        def xmlFiles = []

        def compileConf = project.configurations.findByName('compile')
        compileConf.resolvedConfiguration.resolvedArtifacts.each { artifact ->
            if (artifact.file.name.endsWith('.jar')) {
                def files = project.zipTree(artifact.file).matching {
                    if (fileNames) {
                        for (name in fileNames) {
                            include "$name"
                        }
                    } else {
                        include '**/*-persistence.xml'
                        include '**/persistence.xml'
                    }
                }
                files.each { xmlFiles.add(it) }
            }
        }

        def files = project.fileTree(srcRoot).matching {
            if (fileNames) {
                for (name in fileNames) {
                    include "$name"
                }
            } else {
                include '**/*-persistence.xml'
                include '**/persistence.xml'
            }
        }
        files.each { xmlFiles.add(it) }

        project.logger.info("[CubaEnhancing] Persistence XML files: $xmlFiles")

        def parser = new XmlParser()
        Node doc = null
        for (File file in xmlFiles) {
            Node current = parser.parse(file)
            if (doc == null) {
                doc = current
            } else {
                def docPu = doc.'persistence-unit'[0]
                int idx = docPu.children().findLastIndexOf {
                    it instanceof Node && it.name().localPart == 'class'
                }

                if (idx == -1) {
                    idx = 0
                }

                def currentPu = current.'persistence-unit'[0]
                currentPu.'class'.each {
                    def classNode = parser.createNode(docPu, new QName('http://java.sun.com/xml/ns/persistence', 'class'), [:])
                    classNode.value = it.value()[0]

                    docPu.remove(classNode)
                    docPu.children().add(idx++, classNode)
                }
            }
        }

        def string = XmlUtil.serialize(doc)
        project.logger.debug('[CubaEnhancing] fullPersistenceXml:\n' + string)

        def fullPersistenceXml = new File("$project.buildDir/tmp/persistence/META-INF/persistence.xml")
        fullPersistenceXml.parentFile.mkdirs()
        fullPersistenceXml.write(string)
        return fullPersistenceXml
    }

    private List getTransientEntities() {
        List resultList = []
        getOwnMetadataXmlFiles().each { file ->
            def metadata = new XmlParser().parse(file)
            def metadataModel = metadata.'metadata-model'[0]
            List allClasses = metadataModel.'class'.collect { it.value()[0] }

            if (metadataPackageRegExp) {
                Pattern pattern = Pattern.compile(metadataPackageRegExp)
                resultList.addAll(allClasses.findAll { it.matches(pattern) })
            } else {
                resultList.addAll(allClasses)
            }
        }
        return resultList
    }

    private List<File> getOwnMetadataXmlFiles() {
        List<File> files = []

        if (metadataConfig) {
            Arrays.asList(metadataConfig.split(' ')).each { fileName ->
                File file = project.file("$srcRoot/$fileName")
                if (file.exists()) {
                    files.add(file)
                } else {
                    throw new IllegalArgumentException("File $file doesn't exist")
                }
            }
        } else {
            FileTree fileTree = project.fileTree(srcRoot).matching {
                include '**/*-metadata.xml'
                include '**/metadata.xml'
            }
            fileTree.each { files.add(it) }
        }
        return files
    }

    private String getEnhancedDirPath() {
        return "${project.buildDir}/tmp/enhance-${classesRoot}"
    }

    private File getLegacyEnhancedDir() {
        return new File("${project.buildDir}/enhanced-classes/${classesRoot}/")
    }
}
