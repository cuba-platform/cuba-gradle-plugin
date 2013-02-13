/*
 * Copyright (c) 2012 Haulmont Technology Ltd. All Rights Reserved.
 * Haulmont Technology proprietary and confidential.
 * Use is subject to license terms.
 */

import groovyx.gpars.GParsPool
import org.apache.commons.io.FileUtils
import org.apache.commons.io.FilenameUtils
import org.gradle.api.DefaultTask
import org.gradle.api.internal.project.DefaultAntBuilder
import org.gradle.api.tasks.TaskAction

/**
 * @author krivopustov
 * @version $Id$
 */
class CubaWebStartCreation extends DefaultTask {

    def jnlpTemplateName = "${project.projectDir}/webstart/template.jnlp"
    def indexFileName
    def baseHost = 'http://localhost:8080/'
    def basePath = "${project.applicationName}-webstart"
    def signJarsAlias = 'signJars'
    def signJarsPassword = 'HaulmontSignJars'
    def signJarsKeystore = "${project.projectDir}/webstart/sign-jars-keystore.jks"
    def applicationSignJars = []
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

        project.logger.info(">>> copying app libs from configurations.runtime to ${libDir}")

        project.copy {
            from project.configurations.runtime
            from project.libsDir
            into libDir
            include { details ->
                def name = details.file.name
                return !(name.endsWith('.zip')) && !(name.endsWith('-tests.jar')) && !(name.endsWith('-sources.jar'))
            }
        }

        project.logger.info(">>> signing jars in ${libDir}")

		Date startStamp = new Date()

        if (useSignerCache && applicationSignJars.empty) {
            if (project.parent) {
                project.parent.subprojects.each { subProject ->
                    subProject.configurations.archives.allArtifacts.each {
                        if (''.equals(it.classifier))
                            applicationSignJars.add(it.name + '-' + subProject.version)
                    }
                }
            }

            project.logger.info(">>> do not cache jars: ${applicationSignJars}")
        }

		GParsPool.withPool(jarSignerThreadCount) {
			libDir.listFiles().eachParallel { File jarFile ->
                try {
                    project.logger.info(">>> started sign jar ${jarFile.name} in thread ${Thread.currentThread().id}")
                    doSignFile(jarFile, signerCacheDir)
                    project.logger.info(">>> finished sign jar ${jarFile.name} in thread ${Thread.currentThread().id}")
                } catch (Exception e) {
                    project.logger.error("failed to sign jar file $jarFile.name", e)
                }
			}
		}

		Date endStamp = new Date()
		long processTime = endStamp.getTime() - startStamp.getTime()

		project.logger.info(">>> signing time: ${processTime}")

        project.logger.info(">>> creating JNLP file from ${jnlpTemplateName}")

        File jnlpTemplate = new File(jnlpTemplateName)
        def jnlpNode = new XmlParser().parse(jnlpTemplate)

        if (!baseHost.endsWith('/'))
            baseHost += '/'

        jnlpNode.@codebase = baseHost + basePath
        def jnlpName = jnlpNode.@href

        def resourcesNode = jnlpNode.resources[0]

        libDir.listFiles().each {
            resourcesNode.appendNode('jar', [href: "lib/${it.getName()}", download: 'eager'])
        }

        File jnlpFile = new File(distDir, jnlpName)
        new XmlNodePrinter(new PrintWriter(new FileWriter(jnlpFile))).print(jnlpNode)

        if (indexFileName) {
            project.logger.info(">>> copying indes file from ${indexFileName} to ${distDir}")
            project.copy {
                from indexFileName
                into distDir.getAbsolutePath()
            }
        }
    }

    void doSignFile(File jarFile, File signerCacheDir) {
        def cachedJar = new File(signerCacheDir, jarFile.name)
        def libraryName = FilenameUtils.getBaseName(jarFile.name)

        if (useSignerCache && cachedJar.exists() &&
                !libraryName.endsWith('-SNAPSHOT')
                && !applicationSignJars.contains(libraryName)) {
            project.logger.info(">>> use cached jar: ${jarFile}")
            FileUtils.copyFile(cachedJar, jarFile)
        } else {
            project.logger.info(">>> sign: ${jarFile}")

            def sharedAnt
            if (threadLocalAnt.get())
                sharedAnt = threadLocalAnt.get()
            else {
                sharedAnt = new DefaultAntBuilder(project)
                threadLocalAnt.set(sharedAnt)
            }

            sharedAnt.signjar(jar: "${jarFile}", alias: signJarsAlias, keystore: signJarsKeystore,
                              storepass: signJarsPassword, preservelastmodified: "true")

            if (useSignerCache && !libraryName.endsWith('-SNAPSHOT')
                    && !applicationSignJars.contains(libraryName)) {
                project.logger.info(">>> cache jar: ${jarFile}")
                FileUtils.copyFile(jarFile, cachedJar)
            }
        }
    }
}