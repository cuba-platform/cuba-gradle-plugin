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


import org.apache.commons.lang.StringUtils
import org.gradle.api.tasks.TaskAction

/**
 */
class CubaDbCreation extends CubaDbTask {

    def dropDbSql
    def createDbSql
    def masterUrl
    def oracleSystemUser = 'system'
    def oracleSystemPassword = 'manager'

    File auxiliaryScript

    CubaDbCreation() {
        setGroup('Database')
    }

    @TaskAction
    def createDb() {
        init()
        String createSchemaSql
        if (dbms == 'postgres') {
            if (!masterUrl)
                masterUrl = "jdbc:postgresql://$host/postgres$connectionParams"
            if (!dropDbSql)
                dropDbSql = "drop database if exists $dbName;"
            if (!createDbSql) {
                createDbSql = "create database $dbName with template=template0 encoding='UTF8';"
                if (connectionParams) {
                    Map<String, Object> paramsMap = parseDatabaseParams(connectionParams);
                    String currentSchema = cleanSchemaName(paramsMap.get(CURRENT_SCHEMA_PARAM))
                    if (StringUtils.isNotEmpty(currentSchema)) {
                        createSchemaSql = "create schema \"$currentSchema\";"
                    }
                }
            }

        } else if (dbms == 'mssql') {
            if (!masterUrl)
                masterUrl = "jdbc:jtds:sqlserver://$host/master$connectionParams"
            if (!dropDbSql)
                dropDbSql = "drop database $dbName;"
            if (!createDbSql)
                createDbSql = "create database $dbName;"

        } else if (dbms == 'oracle') {
            if (!masterUrl)
                masterUrl = "jdbc:oracle:thin:@//$host/$dbName$connectionParams"
            if (!dropDbSql)
                dropDbSql = "drop user $dbUser cascade;"
            if (!createDbSql)
                createDbSql =
"""create user $dbUser identified by $dbPassword default tablespace users;
alter user $dbUser quota unlimited on users;
grant create session,
    create table, create view, create procedure, create trigger, create sequence,
    alter any table, alter any procedure, alter any trigger,
    delete any table,
    drop any table, drop any procedure, drop any trigger, drop any view, drop any sequence
    to $dbUser;"""

        } else if (dbms == 'hsql') {
            if (!masterUrl)
                masterUrl = "jdbc:hsqldb:hsql://$host/$dbName$connectionParams"
            if (!dropDbSql)
                dropDbSql = "drop schema public cascade;"

        } else if (dbms == 'mysql') {
            if (!masterUrl) {
                if (!connectionParams) connectionParams = '?useSSL=false&allowMultiQueries=true'
                masterUrl = "jdbc:mysql://$host$connectionParams"
            }
            if (!createDbSql)
                createDbSql = "create database $dbName;"
            if (!dropDbSql)
                dropDbSql = "drop database $dbName;"

        } else if (!masterUrl || !dropDbSql || !createDbSql || !timeStampType) {
            throw new UnsupportedOperationException("DBMS $dbms not supported. " +
                    "You should either provide 'masterUrl', 'dropDbSql', 'createDbSql' and 'timeStampType' properties, " +
                    "or specify one of supported DBMS in 'dbms' property")
        }

        def user = dbms == 'oracle' ? (oracleSystemUser ? oracleSystemUser : 'system') : dbUser
        project.logger.warn("Using database URL: $masterUrl, user: $user")

        project.logger.warn("Executing SQL: $dropDbSql")
        try {
            project.ant.sql(
                    classpath: driverClasspath,
                    driver: driver,
                    url: masterUrl,
                    userid: user,
                    password: dbms == 'oracle' ? oracleSystemPassword : dbPassword,
                    autocommit: true,
                    encoding: "UTF-8",
                    dropDbSql
            )
        } catch (Exception e) {
            project.logger.warn(e.getMessage())
        }

        if (createDbSql) {
            project.logger.warn("Executing SQL: $createDbSql")
            project.ant.sql(
                    classpath: driverClasspath,
                    driver: driver,
                    url: masterUrl,
                    userid: user,
                    password: dbms == 'oracle' ? oracleSystemPassword : dbPassword,
                    autocommit: true,
                    encoding: "UTF-8",
                    createDbSql
            )
        }

        if (createSchemaSql) {
            project.logger.warn("Executing SQL: $createSchemaSql")
            project.ant.sql(
                    classpath: driverClasspath,
                    driver: driver,
                    url: dbUrl,
                    userid: dbUser,
                    password: dbPassword,
                    autocommit: true,
                    encoding: "UTF-8",
                    createSchemaSql
            )
        }

        project.logger.warn("Using database URL: $dbUrl, user: $dbUser")
        try {
            def pkLength = dbms == 'mysql' ? 190 : 300;
            getSql().executeUpdate("create table SYS_DB_CHANGELOG (" +
                    "SCRIPT_NAME varchar($pkLength) not null primary key, " +
                    "CREATE_TS $timeStampType default current_timestamp, " +
                    "IS_INIT integer default 0)")

            initDatabase()

            if (auxiliaryScript) {
                project.logger.warn("Executing SQL script: ${auxiliaryScript.absolutePath}")
                executeSqlScript(auxiliaryScript)
            }
        } finally {
            closeSql()
        }
    }
}