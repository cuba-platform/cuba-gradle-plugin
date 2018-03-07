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
 *
 */

import org.apache.commons.io.FileUtils
import org.apache.commons.io.FilenameUtils
import org.gradle.api.DefaultTask
import org.gradle.api.internal.project.DefaultAntBuilder
import org.gradle.api.internal.project.ant.AntLoggingAdapter
import org.gradle.api.tasks.TaskAction

class CubaWebStartCreation extends DefaultTask {

    def jnlpTemplateName = "${project.projectDir}/webstart/template.jnlp"
    def indexFileName
    def baseHost = 'http://localhost:8080/'
    def basePath = "${project.applicationName}-webstart"
    def signJarsAlias = 'signJars'
    def signJarsPassword = 'HaulmontSignJars'
    def signJarsKeystore = "${project.projectDir}/webstart/sign-jars-keystore.jks"
    def applicationSignJars = []

    @Deprecated
    def jarSignerThreadCount = 4

    def useSignerCache = true

    def threadLocalAnt = new ThreadLocal<AntBuilder>()

    CubaWebStartCreation() {
        setDescription('Creates web start distribution')
        setGroup('Web Start')
    }

    @TaskAction
    def create() {
        File distDir = new File(project.buildDir, "distributions/${basePath}")
        File libDir = new File(distDir, 'lib')
        libDir.mkdirs()

        File signerCacheDir = new File(project.buildDir, "jar-signer-cache")
        if (!signerCacheDir.exists())
            signerCacheDir.mkdir()

        project.logger.info("[CubaWebStartCreation] copying app libs from configurations.runtime to ${libDir}")

        project.copy {
            from project.configurations.runtime
            from project.libsDir
            into libDir
            include { details ->
                def name = details.file.name
                return !(name.endsWith('.zip')) && !(name.endsWith('-tests.jar')) && !(name.endsWith('-sources.jar'))
            }
        }

        project.logger.info("[CubaWebStartCreation] signing jars in ${libDir}")

		long startTs = System.currentTimeMillis()

        if (useSignerCache && applicationSignJars.empty) {
            if (project.parent) {
                project.parent.subprojects.each { subProject ->
                    def archivesConf = subProject.configurations.findByName('archives')
                    if (archivesConf) {
                        archivesConf.allArtifacts.each {
                            if ('' == it.classifier) {
                                applicationSignJars.add(it.name + '-' + subProject.version)
                            }
                        }
                    }
                }
            }

            project.logger.info("[CubaWebStartCreation] do not cache jars: ${applicationSignJars}")
        }

        def libFiles = libDir.listFiles()
        if (libFiles != null) {
            libFiles.toList().stream()
                    .parallel()
                    .forEach({ File jarFile ->
                try {
                    project.logger.info("[CubaWebStartCreation] started sign jar ${jarFile.name} in thread ${Thread.currentThread().id}")

                    doSignFile(jarFile, signerCacheDir)

                    project.logger.info("[CubaWebStartCreation] finished sign jar ${jarFile.name} in thread ${Thread.currentThread().id}")
                } catch (Exception e) {
                    project.logger.error("failed to sign jar file $jarFile.name", e)
                }
            })
        }

		long processTime = System.currentTimeMillis() - startTs

		project.logger.info("[CubaWebStartCreation] signing time: ${processTime}")

        project.logger.info("[CubaWebStartCreation] creating JNLP file from ${jnlpTemplateName}")

        File jnlpTemplate = new File(jnlpTemplateName)
        def jnlpNode = new XmlParser().parse(jnlpTemplate)

        if (!baseHost.endsWith('/'))
            baseHost += '/'

        jnlpNode.@codebase = baseHost + basePath
        def jnlpName = jnlpNode.@href

        def resourcesNode = jnlpNode.resources[0]

        def mainJarName = "${project.applicationName}-${project.version}.jar"
        libDir.listFiles().each {
            def attrs = [href: "lib/${it.getName()}", download: 'eager']
            if (it.getName() == mainJarName) {
                attrs << [main: 'true']
            }
            resourcesNode.appendNode('jar', attrs)
        }

        File jnlpFile = new File(distDir, jnlpName)
        new XmlNodePrinter(new PrintWriter(new FileWriter(jnlpFile))).print(jnlpNode)

        if (indexFileName) {
            project.logger.info("[CubaWebStartCreation] copying index file from ${indexFileName} to ${distDir}")
            project.copy {
                from indexFileName
                into distDir.getAbsolutePath()
            }
        }

        project.logger.info("[CubaWebStartCreation] creating empty web.xml file")
        File webInfDir = new File(distDir, 'WEB-INF')
        webInfDir.mkdirs();
        File webXmlFile = new File(webInfDir, 'web.xml')
        webXmlFile.write("""<?xml version="1.0" encoding="UTF-8"?>
<web-app xmlns="http://java.sun.com/xml/ns/javaee"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://java.sun.com/xml/ns/javaee http://java.sun.com/xml/ns/javaee/web-app_3_0.xsd"
         version="3.0"/>""")
    }

    void doSignFile(File jarFile, File signerCacheDir) {
        def cachedJar = new File(signerCacheDir, jarFile.name)
        def libraryName = FilenameUtils.getBaseName(jarFile.name)

        if (useSignerCache && cachedJar.exists() &&
                !libraryName.endsWith('-SNAPSHOT')
                && !applicationSignJars.contains(libraryName)) {
            project.logger.info("[CubaWebStartCreation] use cached jar: ${jarFile}")
            FileUtils.copyFile(cachedJar, jarFile)
        } else {
            project.logger.info("[CubaWebStartCreation] sign: ${jarFile}")

            def sharedAnt
            if (threadLocalAnt.get())
                sharedAnt = threadLocalAnt.get()
            else {
                sharedAnt = new DefaultAntBuilder(project, new AntLoggingAdapter())
                threadLocalAnt.set(sharedAnt)
            }

            sharedAnt.signjar(jar: "${jarFile}", alias: signJarsAlias, keystore: signJarsKeystore,
                              storepass: signJarsPassword, preservelastmodified: "true")

            if (useSignerCache && !libraryName.endsWith('-SNAPSHOT')
                    && !applicationSignJars.contains(libraryName)) {
                project.logger.info("[CubaWebStartCreation] cache jar: ${jarFile}")
                FileUtils.copyFile(jarFile, cachedJar)
            }
        }
    }
}