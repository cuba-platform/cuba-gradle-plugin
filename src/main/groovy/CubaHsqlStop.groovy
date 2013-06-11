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
class CubaHsqlStop extends CubaHsqlTask {

    @TaskAction
    def stopDb() {
        init()

        try {
            ant.sql(classpath: driverClasspath,
                    driver: 'org.hsqldb.jdbc.JDBCDriver',
                    url: "jdbc:hsqldb:hsql://localhost/$dbName",
                    userid: 'sa', password: '',
                    autocommit: true,
                    'shutdown'
            )
            Thread.sleep(1000)
        } catch (Exception e) {
            logger.warn(">>> error stopping local HSQLDB server: $e")
        }
    }
}
