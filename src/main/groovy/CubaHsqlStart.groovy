/*
 * Copyright (c) 2008-2013 Haulmont. All rights reserved.
 * Use is subject to license terms, see http://www.cuba-platform.com/license for details.
 */

import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.TaskAction

import java.awt.*
import java.util.List

/**
 * @author krivopustov
 * @version $Id$
 */
class CubaHsqlStart extends CubaHsqlTask {

    @TaskAction
    def startDb() {
        init();

        dbDataDir.mkdirs();
        if (GraphicsEnvironment.isHeadless()) {
            if ('linux'.equalsIgnoreCase(System.getProperty('os.name'))) {
                ant.exec(dir: dbDataDir.absolutePath, executable: 'java', spawn: true) {
                    arg(line: "-cp \"$driverClasspath\" org.hsqldb.server.Server --database.0 file:\"$dbName\" --dbname.0 \"$dbName\"")
                }
            } else {
                ant.exec(dir: dbDataDir.absolutePath, executable: 'cmd.exe', spawn: true) {
                    arg(line: "/C java.exe -cp $driverClasspath org.hsqldb.server.Server --database.0 file:$dbName --dbname.0 $dbName")
                }
            }
        } else {
            // Stub configuration.
            Configuration configuration = project.configurations.findByName("hsqlStart")
            if (!configuration) {
                project.configurations.create("hsqlStart").extendsFrom(project.configurations.getByName("provided"))
            }
            configuration = project.configurations.getByName("hsqlStart");
            project.dependencies {
                hsqlStart(CubaPlugin.getArtifactDefinition())
            }
            List<String> paths = [];
            configuration.resolve().each { paths.add(it.absolutePath) }
            String classpath = driverClasspath + File.pathSeparator + paths.join(File.pathSeparator);

            ant.java(classname: CubaHSQLDBServer.class.name, classpath: classpath, fork: true, spawn: true, dir: dbDataDir.absolutePath) {
                arg(line: "\"${dbDataDir.absolutePath}\" \"${dbName}\"")
            }
        }
        Thread.sleep(1000)
    }
}