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
import org.gradle.api.tasks.TaskAction

import java.awt.*

class CubaHsqlStart extends CubaHsqlTask {

    File dbDataDir

    @Override
    protected void init() {
        super.init()

        if (!dbDataDir) {
            dbDataDir = new File("$project.rootProject.buildDir/hsqldb")
        }
    }

    @TaskAction
    void startDb() {
        init();

        dbDataDir.mkdirs()

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
            def classPathUrls = ((URLClassLoader) CubaHSQLDBServer.class.classLoader).getURLs()
            def classpath = driverClasspath + File.pathSeparator + classPathUrls.collect({
                new File(it.toURI()).absolutePath
            }).join(File.pathSeparator)

            ant.java(classname: CubaHSQLDBServer.class.name, classpath: classpath,
                    fork: true, spawn: true, dir: dbDataDir.absolutePath) {
                arg(line: "$dbPort \"${dbDataDir.absolutePath}\" \"${dbName}\"")
            }
        }
        ant.waitfor(maxwait: 10, maxwaitunit: 'second', checkevery: 1, checkeveryunit: 'second') {
            socket(server: 'localhost', port: "$dbPort")
        }
    }
}