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

import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.TaskAction

import java.awt.*
import java.util.List

/**
 */
class CubaHsqlStart extends CubaHsqlTask {

    def File dbDataDir

    @Override
    protected void init() {
        super.init()
        if (!dbDataDir)
            dbDataDir = new File("$project.rootProject.buildDir/hsqldb")
    }

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
                arg(line: "$dbPort \"${dbDataDir.absolutePath}\" \"${dbName}\"")
            }
        }
        ant.waitfor(maxwait: 10, maxwaitunit: 'second', checkevery: 1, checkeveryunit: 'second') {
            socket(server: 'localhost', port: "$dbPort")
        }
    }
}