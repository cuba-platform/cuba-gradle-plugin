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

import org.gradle.api.DefaultTask;
import org.gradle.api.artifacts.Configuration;

public abstract class CubaHsqlTask extends DefaultTask {
    protected String driverClasspath;
    protected String dbName = "cubadb";
    protected int dbPort = 9001;

    protected void init() {
        if (driverClasspath == null) {
            Configuration jdbcConfiguration = getProject().getConfigurations().getByName("jdbc");
            driverClasspath = jdbcConfiguration.getAsPath();
        }
        getLogger().info("[CubaHsqlTask] driverClasspath: " + driverClasspath);
    }

    public String getDriverClasspath() {
        return driverClasspath;
    }

    public void setDriverClasspath(String driverClasspath) {
        this.driverClasspath = driverClasspath;
    }

    public String getDbName() {
        return dbName;
    }

    public void setDbName(String dbName) {
        this.dbName = dbName;
    }

    public int getDbPort() {
        return dbPort;
    }

    public void setDbPort(int dbPort) {
        this.dbPort = dbPort;
    }
}