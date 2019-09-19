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
import org.apache.commons.lang3.StringUtils
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.regex.Matcher
import java.util.regex.Pattern

class CubaSetupTomcat extends DefaultTask {

    public static final String VERSION_LOGGER_XML = '<Listener className="org.apache.catalina.startup.VersionLoggerListener" />'

    public static final List<String> TOMCAT_RESOURCES = Collections.unmodifiableList([
            "/tomcat/bin/call_and_exit.bat",
            "/tomcat/bin/catalina.bat",
            "/tomcat/bin/catalina.sh",
            "/tomcat/bin/debug.bat",
            "/tomcat/bin/debug.sh",
            "/tomcat/bin/kill_by_port.sh",
            "/tomcat/bin/setenv.bat",
            "/tomcat/bin/setenv.sh",
            "/tomcat/conf/catalina.properties",
            "/tomcat/conf/logging.properties"
    ])

    public static final List<String> APP_HOME_RESOURCES = Collections.unmodifiableList([
            "/app_home/logback.xml",
            "/app_home/local.app.properties"
    ])

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

        TOMCAT_RESOURCES.each { resourceName ->
            def resourcePath = Paths.get(resourceName)
            def resourceSubPath = resourcePath.subpath(1, resourcePath.getNameCount())

            def targetFile = project.file(tomcatRootDir).toPath().resolve(resourceSubPath).toFile()
            logger.debug("Copy ${resourceName} to ${targetFile.getAbsolutePath()}")

            FileUtils.copyInputStreamToFile(getClass().getResourceAsStream(resourceName), targetFile)
        }

        APP_HOME_RESOURCES.each { resourceName ->
            def resourcePath = Paths.get(resourceName)
            def resourceSubPath = resourcePath.subpath(1, resourcePath.getNameCount())

            def targetFile = project.file(project.cuba.appHome).toPath().resolve(resourceSubPath).toFile()
            logger.debug("Copy ${resourceName} to ${targetFile.getAbsolutePath()}")

            FileUtils.copyInputStreamToFile(getClass().getResourceAsStream(resourceName), targetFile)
        }

        logger.debug("Disable VersionLoggerListener")
        updateServerXmlLogging()

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

    private def updateServerXmlLogging() {
        Path serverXml = Paths.get(tomcatRootDir, 'conf', 'server.xml')
        if (!Files.exists(serverXml)) {
            logger.error('conf/server.xml has not been updated: cannot find server.xml file')
            return
        }
        def serverXmlFile = serverXml.toFile()

        // Disable version information in log by default
        def replacement = '<!-- ' + VERSION_LOGGER_XML + ' -->'
        def text = serverXmlFile.text

        if (!text.contains(replacement)) {
            serverXml.text = StringUtils.replace(text, VERSION_LOGGER_XML, replacement)
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
            String newPortValue = project.cuba.tomcat.shutdownPort
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
                changed = true
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
                changed = true
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