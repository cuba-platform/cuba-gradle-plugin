/*
 * Copyright (c) 2008-2013 Haulmont. All rights reserved.
 * Use is subject to license terms, see http://www.cuba-platform.com/license for details.
 */


import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * @author artamonov
 * @version $Id$
 */
class CubaSetupTomcat extends DefaultTask {

    def tomcatRootDir = project.tomcatDir

    CubaSetupTomcat() {
        setDescription('Sets up local Tomcat')
        setGroup('Deployment')
    }

    @TaskAction
    def setup() {
        project.configurations.tomcat.files.each { dep ->
            project.copy {
                from project.zipTree(dep.absolutePath)
                into "$tomcatRootDir/tmp"
            }
        }
        new File("$tomcatRootDir/tmp").eachDirMatch(~/apache-tomcat-.*/) { File dir ->
            project.copy {
                from dir
                into tomcatRootDir
                exclude '**/webapps/*'
                exclude '**/work/*'
                exclude '**/temp/*'
            }
        }
        project.delete("$tomcatRootDir/tmp")

        project.configurations.tomcatInit.files.each { dep ->
            project.copy {
                from project.zipTree(dep.absolutePath)
                into tomcatRootDir
            }
        }

        if (project.hasProperty('tomcatPort') || project.hasProperty('tomcatShutdownPort')) {
            updateServerXml()
        }
        if (project.hasProperty('tomcatDebugPort')) {
            Pattern pattern = Pattern.compile(/(JPDA_OPTS\s*?=\s*?\"*-Xrunjdwp:transport\s*?=\s*?dt_socket,\s*?address\s*?=\s*?)(\d+)/)
            updateDebugPort(Paths.get(tomcatRootDir, 'bin', 'setenv.bat'), pattern)
            updateDebugPort(Paths.get(tomcatRootDir, 'bin', 'setenv.sh'), pattern)
        }

        ant.chmod(osfamily: 'unix', perm: 'a+x') {
            fileset(dir: "${tomcatRootDir}/bin", includes: '*.sh')
        }
    }

    private void updateServerXml() {
        Path serverXml = Paths.get(tomcatRootDir, 'conf', 'server.xml')
        if (!Files.exists(serverXml)) {
            logger.error('conf/server.xml has not been updated: cannot find server.xml file')
            return
        }
        def serverNode = new XmlParser().parse(serverXml.toFile())
        boolean changed = false

        if (project.hasProperty('tomcatShutdownPort')) {
            String currPortValue = serverNode.@port
            String newPortValue = project.tomcatShutdownPort;
            if (!Objects.equals(currPortValue, newPortValue)) {
                serverNode.@port = newPortValue
                changed = true
            }
        }

        if (project.hasProperty('tomcatPort')) {
            def serviceNode = serverNode.find { node ->
                node.name() == 'Service' && node.@name == 'Catalina'
            }
            if (!serviceNode) {
                logger.error('conf/server.xml has not been updated: cannot find Service node')
                return
            }
            def connectorNode = serviceNode.find { node ->
                node.name() == 'Connector' && node.@protocol == 'HTTP/1.1'
            }
            if (!connectorNode) {
                logger.error('conf/server.xml has not been updated: cannot find Connector node')
                return
            }
            currPortValue = connectorNode.@port
            newPortValue = project.tomcatPort
            if (!Objects.equals(currPortValue, newPortValue)) {
                connectorNode.@port = newPortValue
                changed = true;
            }
        }

        if (changed) {
            new XmlNodePrinter(new PrintWriter(new FileWriter(serverXml.toFile()))).print(serverNode)
        }
    }

    private void updateDebugPort(Path setenvXml, Pattern jpdaOpts) {
        if (Files.exists(setenvXml)) {
            String oldBatContent = setenvXml.toFile().text
            Matcher matcher = jpdaOpts.matcher(oldBatContent)
            if (matcher.find()) {
                String newBatContent = matcher.replaceFirst('$1' + project.tomcatDebugPort)
                if (!Objects.equals(oldBatContent, newBatContent)) {
                    setenvXml.toFile().text = newBatContent
                }
            } else {
                logger.error("$setenvXml has not been updated: pattern ${jpdaOpts.pattern()} not found")
            }
        }
    }
}