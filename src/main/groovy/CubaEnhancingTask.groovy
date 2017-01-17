/*
 * Copyright (c) 2008-2016 Haulmont.
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

import groovy.io.FileType
import groovy.xml.QName
import groovy.xml.XmlUtil
import javassist.ClassPool
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.FileTree
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFiles
import org.gradle.api.tasks.TaskAction

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.regex.Pattern

/**
 * Enhances entity classes specified in persistence xml
 *
 */
class CubaEnhancingTask extends DefaultTask {

    String persistenceConfig
    String metadataConfig

    @Deprecated
    String metadataXml

    String metadataPackageRegExp

    protected String srcRoot
    protected String classesRoot
    protected def sourceSet

    CubaEnhancingTask() {
    }

    @InputFiles
    def List getInputFiles() {
        List entities = getPersistentEntities(getOwnPersistenceXmlFiles())
        entities.addAll(getTransientEntities())

        entities.collect { name ->
            new File("$project.buildDir/classes/$classesRoot/${name.replace('.', '/')}.class")
        }
    }

    @OutputFiles
    def List getOutputFiles() {
        List entities = getPersistentEntities(getOwnPersistenceXmlFiles())
        entities.addAll(getTransientEntities())

        entities.collect { name ->
            new File("$project.buildDir/enhanced-classes/$classesRoot/${name.replace('.', '/')}.class")
        }
    }

    private List<File> getOwnPersistenceXmlFiles() {
        List<File> files = []
        if (persistenceConfig) {
            List<String> fileNames = Arrays.asList(persistenceConfig.split('\\s'))
            fileNames.each { fileName ->
                File file = project.file("$srcRoot/$fileName")
                if (!file.exists())
                    throw new IllegalArgumentException("File $file doesn't exist")
                else
                    files.add(file)
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

        Configuration compileConf = project.configurations.findByName('compile')
        compileConf.resolvedConfiguration.resolvedArtifacts.each { artifact ->
            FileTree files = project.zipTree(artifact.file.absolutePath).matching {
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

        FileTree files = project.fileTree(srcRoot).matching {
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

        logger.info("[CubaEnhancing] Persistence XML files: $xmlFiles")

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
                if (idx == -1) idx = 0;

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
        logger.debug('[CubaEnhancing] fullPersistenceXml:\n' + string)

        File fullPersistenceXml = new File("$project.buildDir/tmp/persistence/META-INF/persistence.xml")
        fullPersistenceXml.parentFile.mkdirs()
        fullPersistenceXml.write(string)
        return fullPersistenceXml
    }

    private List getPersistentEntities(List<File> persistenceXmlList) {
        List resultList = []
        persistenceXmlList.each { file ->
            def persistence = new XmlParser().parse(file)
            def pu = persistence.'persistence-unit'[0]
            resultList.addAll(pu.'class'.collect { it.value()[0] })
        }
        return resultList
    }

    private List getTransientEntities() {
        List resultList = []
        getOwnMetadataXmlFiles().each { file ->
            def metadata = new XmlParser().parse(file)
            def mm = metadata.'metadata-model'[0]
            List allClasses = mm.'class'.collect { it.value()[0] }
            if (metadataPackageRegExp) {
                Pattern pattern = Pattern.compile(metadataPackageRegExp)
                resultList.addAll(allClasses.findAll { it.matches(pattern) })
            } else
                resultList.addAll(allClasses)
        }
        return resultList
    }

    private List<File> getOwnMetadataXmlFiles() {
        List<File> files = []
        if (metadataXml) {
            File f = new File(metadataXml)
            if (!f.exists()) {
                throw new IllegalArgumentException("File $metadataXml doesn't exist")
            }
            files.add(f)

        } else if (metadataConfig) {
            List<String> fileNames = Arrays.asList(metadataConfig.split('\\s'))
            fileNames.each { fileName ->
                File file = project.file("$srcRoot/$fileName")
                if (!file.exists())
                    throw new IllegalArgumentException("File $file doesn't exist")
                else
                    files.add(file)
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

    @TaskAction
    def enhanceClasses() {
        def outputDir = new File("$project.buildDir/enhanced-classes/$classesRoot")
        List allClasses = []

        logger.info("[CubaEnhancing] Metadata XML files: ${getOwnMetadataXmlFiles()}")

        if (!getOwnPersistenceXmlFiles().isEmpty()) {
            File fullPersistenceXml = createFullPersistenceXml()
            if (new File("$project.buildDir/classes/$classesRoot").exists()) {
                logger.info("[CubaEnhancing] start EclipseLink enhancing")
                project.javaexec {
                    main = 'org.eclipse.persistence.tools.weaving.jpa.CubaStaticWeave'
                    classpath(
                            sourceSet.compileClasspath,
                            sourceSet.output.classesDir
                    )
                    args "-loglevel"
                    args "INFO"
                    args "-persistenceinfo"
                    args "$project.buildDir/tmp/persistence"
                    args "$project.buildDir/classes/$classesRoot"
                    args "$project.buildDir/enhanced-classes/$classesRoot"
                    debug = System.getProperty("debugEnhance") ? Boolean.valueOf(System.getProperty("debugEnhance")) : false
                }
            }
            // EclipseLink enhancer copies all classes to build/enhanced-classes,
            // so we should delete files that are not in persistence.xml and metadata.xml
            def persistence = new XmlParser().parse(fullPersistenceXml)
            def pu = persistence.'persistence-unit'[0]
            allClasses.addAll(pu.'class'.collect { it.value()[0] })

            allClasses.addAll(getTransientEntities())

            // AbstractInstance is not registered but shouldn't be deleted
            allClasses.add('com.haulmont.chile.core.model.impl.AbstractInstance')

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
                def emptyDirs = []
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
        } else {
            allClasses.addAll(getTransientEntities())

            if (outputDir.exists()) {
                allClasses.each { String className ->
                    Path srcFile = Paths.get("$project.buildDir/classes/$classesRoot/${className.replace('.', '/')}.class")
                    Path dstFile = Paths.get("$project.buildDir/enhanced-classes/$classesRoot/${className.replace('.', '/')}.class")
                    Files.copy(srcFile, dstFile, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        if (outputDir.exists()) {
            // run CUBA enhancing on all classes remaining in build/enhanced-classes
            logger.info("[CubaEnhancing] start CUBA enhancing")
            ClassPool pool = new ClassPool(null)
            pool.appendSystemPath()
            sourceSet.compileClasspath.each { File file ->
                pool.insertClassPath(file.toString())
            }
            pool.insertClassPath(sourceSet.output.classesDir.toString())
            pool.insertClassPath(outputDir.toString())

            def cubaEnhancer = new CubaEnhancer(pool, outputDir.toString())
            allClasses.each { String name ->
                cubaEnhancer.run(name)
            }
        }
    }
}