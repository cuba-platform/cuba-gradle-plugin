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

    def tomcatRootDir = project.cuba.tomcat.dir

    CubaSetupTomcat() {
        setDescription('Sets up local Tomcat')
        setGroup('Deployment')
    }

    @TaskAction
    def setup() {
        if (!tomcatRootDir) {
            tomcatRootDir = project.cuba.tomcat.dir
        }

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

        if (project.cuba.tomcat.port || project.cuba.tomcat.shutdownPort || project.cuba.tomcat.ajpPort) {
            updateServerXml()
        }
        if (project.cuba.tomcat.debugPort) {
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

        if (project.cuba.tomcat.shutdownPort) {
            String currPortValue = serverNode.@port
            String newPortValue = project.cuba.tomcat.shutdownPort;
            if (!Objects.equals(currPortValue, newPortValue)) {
                serverNode.@port = newPortValue
                changed = true
            }
        }

        if (project.cuba.tomcat.port) {
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
                logger.error('conf/server.xml has not been updated: cannot find HTTP Connector node')
                return
            }
            String currPortValue = connectorNode.@port
            String newPortValue = project.cuba.tomcat.port
            if (!Objects.equals(currPortValue, newPortValue)) {
                connectorNode.@port = newPortValue
                changed = true;
            }
        }

        if (project.cuba.tomcat.ajpPort) {
            def serviceNode = serverNode.find { node ->
                node.name() == 'Service' && node.@name == 'Catalina'
            }
            if (!serviceNode) {
                logger.error('conf/server.xml has not been updated: cannot find Service node')
                return
            }
            def connectorNode = serviceNode.find { node ->
                node.name() == 'Connector' && node.@protocol == 'AJP/1.3'
            }
            if (!connectorNode) {
                logger.error('conf/server.xml has not been updated: cannot find AJP Connector node')
                return
            }
            String currPortValue = connectorNode.@port
            String newPortValue = project.cuba.tomcat.ajpPort
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
                String newBatContent = matcher.replaceFirst('$1' + project.cuba.tomcat.debugPort)
                if (!Objects.equals(oldBatContent, newBatContent)) {
                    setenvXml.toFile().text = newBatContent
                }
            } else {
                logger.error("$setenvXml has not been updated: pattern ${jpdaOpts.pattern()} not found")
            }
        }
    }
}