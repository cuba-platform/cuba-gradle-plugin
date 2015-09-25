/*
 * Copyright (c) 2008-2013 Haulmont. All rights reserved.
 * Use is subject to license terms, see http://www.cuba-platform.com/license for details.
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
 * @author krivopustov
 * @version $Id$
 */
class CubaEnhancing extends DefaultTask {

    String persistenceConfig

    String metadataXml
    String metadataPackageRegExp

    CubaEnhancing() {
        setDescription('Enhances persistent classes')
        setGroup('Compile')
        // set default task dependsOn
        setDependsOn(project.getTasksByName('compileJava', false))
        project.getTasksByName('classes', false).each { it.dependsOn(this) }
        // add default assist dependency on cuba-plugin
        def enhanceConfiguration = project.configurations.findByName("enhance")
        if (!enhanceConfiguration)
            project.configurations.create("enhance").extendsFrom(project.configurations.getByName("provided"))

        project.dependencies {
            enhance(CubaPlugin.getArtifactDefinition())
        }
    }

    @InputFiles
    def List getInputFiles() {
        List entities = getPersistentEntities(getPersistenceXmlFile())
        entities.addAll(getTransientEntities())

        entities.collect { name ->
            new File("$project.buildDir/classes/main/${name.replace('.', '/')}.class")
        }
    }

    @OutputFiles
    def List getOutputFiles() {
        List entities = getPersistentEntities(getPersistenceXmlFile())
        entities.addAll(getTransientEntities())

        entities.collect { name ->
            new File("$project.buildDir/enhanced-classes/main/${name.replace('.', '/')}.class")
        }
    }

    private File getPersistenceXmlFile() {
        if (persistenceConfig) {
            def fileName = Arrays.asList(persistenceConfig.split('\\s')).last()
            File file = project.file("src/$fileName")
            if (file.exists())
                return file
            else {
                throw new IllegalArgumentException("File $file doesn't exist")
            }
        } else {
            FileTree fileTree = project.fileTree('src').matching {
                include '*-persistence.xml'
                include 'persistence.xml'
            }
            if (fileTree.isEmpty()) {
                return null
            } else if (fileTree.getFiles().size() > 1) {
                throw new IllegalArgumentException("There are more than one persistence XML file in the source tree - please specify 'persistenceConfig' property for the task")
            } else {
                return fileTree.getSingleFile()
            }
        }
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
                    include '*-persistence.xml'
                    include 'persistence.xml'
                }
            }
            files.each { xmlFiles.add(it) }
        }

        FileTree files = project.fileTree('src').matching {
            if (fileNames) {
                for (name in fileNames) {
                    include "$name"
                }
            } else {
                include '*-persistence.xml'
                include 'persistence.xml'
            }
        }
        files.each { xmlFiles.add(it) }

        logger.info("Persistence XML files: $xmlFiles")

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
        logger.debug(string)

        File fullPersistenceXml = new File("$project.buildDir/tmp/persistence/META-INF/persistence.xml")
        fullPersistenceXml.parentFile.mkdirs()
        fullPersistenceXml.write(string)
        return fullPersistenceXml
    }

    private List getPersistentEntities(File persistenceXml) {
        if (!persistenceXml)
            return []

        def persistence = new XmlParser().parse(persistenceXml)
        def pu = persistence.'persistence-unit'[0]
        pu.'class'.collect { it.value()[0] }
    }

    private List getTransientEntities() {
        File file = getMetadataXmlFile()
        if (file) {
            def metadata = new XmlParser().parse(file)
            def mm = metadata.'metadata-model'[0]
            List allClasses = mm.'class'.collect { it.value()[0] }
            if (metadataPackageRegExp) {
                Pattern pattern = Pattern.compile(metadataPackageRegExp)
                return allClasses.findAll { it.matches(pattern) }
            } else
                return allClasses
        } else {
            return []
        }
    }

    private File getMetadataXmlFile() {
        if (metadataXml) {
            File f = new File(metadataXml)
            if (!f.exists()) {
                throw new IllegalArgumentException("File $metadataXml doesn't exist")
            }
            return f
        } else {
            FileTree fileTree = project.fileTree('src').matching {
                include '*-metadata.xml'
                include 'metadata.xml'
            }
            if (fileTree.isEmpty()) {
                return null
            } else if (fileTree.getFiles().size() > 1) {
                throw new IllegalArgumentException("There are more than one metadata XML file in the source tree - please specify 'metadataXml' property for the task")
            } else  {
                return fileTree.getSingleFile()
            }
        }
    }

    @TaskAction
    def enhanceClasses() {
        def outputDir = new File("$project.buildDir/enhanced-classes/main")
        List allClasses = []

        logger.info("Metadata XML file: ${getMetadataXmlFile()}")

        if (getPersistenceXmlFile()) {
            File fullPersistenceXml = createFullPersistenceXml()
            if (new File("$project.buildDir/classes/main").exists()) {
                project.javaexec {
                    main = 'org.eclipse.persistence.tools.weaving.jpa.CubaStaticWeave'
                    classpath(
                            project.sourceSets.main.compileClasspath,
                            project.sourceSets.main.output.classesDir,
                            project.configurations.enhance
                    )
                    args "-loglevel"
                    args "FINER"
                    args "-persistenceinfo"
                    args "$project.buildDir/tmp/persistence"
                    args "$project.buildDir/classes/main"
                    args "$project.buildDir/enhanced-classes/main"
                    debug = System.getProperty("debugEnhance") ? Boolean.valueOf(System.getProperty("debugEnhance")) : false
                }
            }
            // delete files that are not in persistence.xml and metadata.xml
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
                    Path srcFile = Paths.get("$project.buildDir/classes/main/${className.replace('.', '/')}.class")
                    Path dstFile = Paths.get("$project.buildDir/enhanced-classes/main/${className.replace('.', '/')}.class")
                    Files.copy(srcFile, dstFile, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        if (outputDir.exists()) {
            // CUBA-specific enhancing
            ClassPool pool = ClassPool.getDefault()
            project.sourceSets.main.compileClasspath.each { File file ->
                pool.insertClassPath(file.toString())
            }
            pool.insertClassPath(project.sourceSets.main.output.classesDir.toString())
            pool.insertClassPath(outputDir.toString())

            def cubaEnhancer = new CubaEnhancer(pool, outputDir.toString())
            allClasses.each { String name ->
                cubaEnhancer.run(name)
            }
        }
    }
}