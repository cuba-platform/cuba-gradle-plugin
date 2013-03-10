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
class CubaDbCreation extends CubaDbTask {

    def dropDbSql
    def createDbSql
    def oracleSystemPassword = 'manager'

    CubaDbCreation() {
        setGroup('Database')
    }

    @TaskAction
    def createDb() {
        init()

        def masterUrl

        if (dbms == 'postgres') {
            masterUrl = "jdbc:postgresql://$host/postgres"
            if (!dropDbSql)
                dropDbSql = "drop database if exists $dbName;"
            if (!createDbSql)
                createDbSql = "create database $dbName with template=template0 encoding='UTF8';"
        } else if (dbms == 'mssql') {
            masterUrl = "jdbc:jtds:sqlserver://$host/master"
            if (!dropDbSql)
                dropDbSql = "drop database $dbName;"
            if (!createDbSql)
                createDbSql = "create database $dbName;"
        } else if (dbms == 'oracle') {
            masterUrl = "jdbc:oracle:thin:@//$host/orcl"
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
        } else
            throw new UnsupportedOperationException("DBMS $dbms not supported")

        project.logger.warn("Executing SQL: $dropDbSql")
        try {
            project.ant.sql(
                    classpath: driverClasspath,
                    driver: driver,
                    url: masterUrl,
                    userid: dbms == 'oracle' ? 'system' : dbUser,
                    password: dbms == 'oracle' ? oracleSystemPassword : dbPassword,
                    autocommit: true,
                    encoding: "UTF-8",
                    dropDbSql
            )
        } catch (Exception e) {
            project.logger.warn(e.getMessage())
        }

        project.logger.warn("Executing SQL: $createDbSql")
        project.ant.sql(
                classpath: driverClasspath,
                driver: driver,
                url: masterUrl,
                userid: dbms == 'oracle' ? 'system' : dbUser,
                password: dbms == 'oracle' ? oracleSystemPassword : dbPassword,
                autocommit: true,
                encoding: "UTF-8",
                createDbSql
        )

        try {
            getSql().executeUpdate("create table SYS_DB_CHANGELOG(" +
                    "SCRIPT_NAME varchar${dbms == 'oracle' ? '2' : ''}(300) not null primary key, " +
                    "CREATE_TS " + (dbms == 'mssql' ? "datetime" : "timestamp") + " default current_timestamp, " +
                    "IS_INIT integer default 0)")

            getInitScripts().each { File file ->
                project.logger.warn("Executing SQL script: ${file.absolutePath}")
                project.ant.sql(
                        classpath: driverClasspath,
                        src: file.absolutePath,
                        delimiter: delimiter,
                        driver: driver,
                        url: dbUrl,
                        userid: dbUser, password: dbPassword,
                        autocommit: true,
                        encoding: "UTF-8"
                )
                String name = getScriptName(file)
                markScript(name, true)
            }

            getUpdateScripts().each { File file ->
                String name = getScriptName(file)
                markScript(name, true)
            }
        } finally {
            closeSql()
        }
    }

    private List<File> getInitScripts() {
        List<File> files = []
        if (dbDir.exists()) {
            String[] moduleDirs = dbDir.list()
            Arrays.sort(moduleDirs)
            for (String moduleDirName: moduleDirs) {
                File moduleDir = new File(dbDir, moduleDirName)
                File initDir = new File(moduleDir, "init")
                File scriptDir = new File(initDir, dbms)
                if (scriptDir.exists()) {
                    File[] scriptFiles = scriptDir.listFiles(new FilenameFilter() {
                        public boolean accept(File dir, String name) {
                            return name.endsWith("create-db.sql")
                        }
                    })
                    Arrays.sort(scriptFiles)
                    files.addAll(Arrays.asList(scriptFiles))
                }
            }
        }
        return files
    }
}