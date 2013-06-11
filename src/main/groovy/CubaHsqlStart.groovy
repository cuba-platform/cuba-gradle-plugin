/*
 * Copyright (c) 2011 Haulmont Technology Ltd. All Rights Reserved.
 * Haulmont Technology proprietary and confidential.
 * Use is subject to license terms.
 */

import org.gradle.api.tasks.TaskAction

/**
 * @author krivopustov
 * @version $Id$
 */
class CubaHsqlStart extends CubaHsqlTask {

    @TaskAction
    def startDb() {
        init()

        dbDataDir.mkdirs()
        if ('linux'.equalsIgnoreCase(System.getProperty('os.name'))) {
            ant.exec(dir: dbDataDir.absolutePath, executable: 'java', spawn: true) {
                arg(line: "-cp \"$driverClasspath\" org.hsqldb.server.Server --database.0 file:\"$dbName\" --dbname.0 \"$dbName\"")
            }
        } else {
            ant.exec(dir: dbDataDir.absolutePath, executable: 'cmd.exe', spawn: true) {
                arg(line: "-cp $driverClasspath org.hsqldb.server.Server --database.0 file:$dbName --dbname.0 $dbName")
            }
        }
        Thread.sleep(1000)
    }
}
