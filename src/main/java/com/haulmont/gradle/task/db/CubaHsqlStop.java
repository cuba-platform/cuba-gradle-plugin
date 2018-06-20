/*
 * Copyright (c) 2008-2017 Haulmont.
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
 */

package com.haulmont.gradle.task.db;

import org.apache.tools.ant.taskdefs.SQLExec;
import org.gradle.api.tasks.TaskAction;

public class CubaHsqlStop extends CubaHsqlTask {

    public CubaHsqlStop() {
        setGroup("Database");
    }

    @TaskAction
    public void stopDb() {
        init();

        try {
            SQLExec sql = new SQLExec();
            sql.setProject(getProject().getAnt().getProject());
            sql.createClasspath().setPath(driverClasspath);
            sql.setDriver("org.hsqldb.jdbc.JDBCDriver");
            sql.setUrl(String.format("jdbc:hsqldb:hsql://localhost:%s/%s", getDbPort(), getDbName()));
            sql.setUserid("sa");
            sql.setPassword("");
            sql.setAutocommit(true);
            sql.addText("shutdown");
            sql.execute();

            Thread.sleep(1000);
        } catch (Exception e) {
            getLogger().warn("[CubaHsqlStop] error stopping local HSQLDB server: " + e.getMessage());
        }
    }
}