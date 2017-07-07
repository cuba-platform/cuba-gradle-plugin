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

class CubaHsqlStop extends CubaHsqlTask {
    @TaskAction
    void stopDb() {
        init()

        try {
            ant.sql(classpath: driverClasspath,
                    driver: 'org.hsqldb.jdbc.JDBCDriver',
                    url: "jdbc:hsqldb:hsql://localhost:$dbPort/$dbName",
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