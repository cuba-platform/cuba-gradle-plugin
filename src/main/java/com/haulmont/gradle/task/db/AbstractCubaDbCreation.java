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

import java.io.File;
import java.sql.SQLException;

public abstract class AbstractCubaDbCreation extends CubaDbTask {
    protected File auxiliaryScript;

    public AbstractCubaDbCreation() {
        setGroup("Database");
    }

    public void createDb() {
        init();

        dropAndCreateDatabase();

        getProject().getLogger().warn("Using database URL: " + dbUrl + ", user: " + dbUser);
        try {
            createSysDbChangeLogTable();

            initDatabase(null);

            executeAuxiliaryScript();
        } finally {
            closeSql();
        }
    }

    protected void executeAuxiliaryScript() {
        if (auxiliaryScript != null) {
            getProject().getLogger().warn("Executing SQL script: " + auxiliaryScript.getAbsolutePath());
            executeSqlScript(auxiliaryScript);
        }
    }

    protected void createSysDbChangeLogTable() {
        int pkLength = MYSQL_DBMS.equals(dbms) ? 190 : 300;
        try {
            getSql().executeUpdate(String.format("create table SYS_DB_CHANGELOG (\n" +
                    "SCRIPT_NAME varchar(%s) not null primary key, \n" +
                    "CREATE_TS %s default current_timestamp, \n" +
                    "IS_INIT integer default 0)", pkLength, timeStampType));
        } catch (SQLException e) {
            throw new RuntimeException("", e);
        }
    }

    protected abstract void dropAndCreateDatabase();
}
