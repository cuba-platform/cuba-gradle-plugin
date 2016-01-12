/*
 * Copyright (c) 2008-2013 Haulmont. All rights reserved.
 * Use is subject to license terms, see http://www.cuba-platform.com/license for details.
 */

import org.gradle.api.tasks.TaskAction

/**
 * @author krivopustov
 * @version $Id$
 */
class CubaDbCreation extends CubaDbTask {

    def dropDbSql
    def createDbSql
    def masterUrl
    def oracleSystemUser = 'system'
    def oracleSystemPassword = 'manager'

    CubaDbCreation() {
        setGroup('Database')
    }

    @TaskAction
    def createDb() {
        init()

        if (dbms == 'postgres') {
            if (!masterUrl)
                masterUrl = "jdbc:postgresql://$host/postgres$connectionParams"
            if (!dropDbSql)
                dropDbSql = "drop database if exists $dbName;"
            if (!createDbSql)
                createDbSql = "create database $dbName with template=template0 encoding='UTF8';"

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

        project.logger.warn("Using database URL: $dbUrl, user: $dbUser")
        try {
            getSql().executeUpdate("create table SYS_DB_CHANGELOG (" +
                    "SCRIPT_NAME varchar(300) not null primary key, " +
                    "CREATE_TS $timeStampType default current_timestamp, " +
                    "IS_INIT integer default 0)")

            initDatabase()
        } finally {
            closeSql()
        }
    }
}