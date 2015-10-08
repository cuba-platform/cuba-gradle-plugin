/*
 * Copyright (c) 2008-2013 Haulmont. All rights reserved.
 * Use is subject to license terms, see http://www.cuba-platform.com/license for details.
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
            logger.warn("[CubaHsqlStop] error stopping local HSQLDB server: $e")
        }
    }
}
