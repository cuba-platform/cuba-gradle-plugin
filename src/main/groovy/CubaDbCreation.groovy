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

import com.haulmont.gradle.task.db.AbstractCubaDbCreation
import org.apache.commons.lang3.StringUtils
import org.codehaus.groovy.tools.GroovyClass
import org.gradle.api.tasks.TaskAction

import java.sql.DriverManager

class CubaDbCreation extends AbstractCubaDbCreation {

    def dropDbSql
    def createDbSql
    def masterUrl

    def oracleSystemUser = 'system'
    def oracleSystemPassword = 'manager'

    @TaskAction
    @Override
    void createDb() {
        super.createDb()
    }

    @Override
    void dropAndCreateDatabase() {
        String createPostgresSchemeSql
        if (dbms == POSTGRES_DBMS) {
            createPostgresSchemeSql = configurePostgres()
        } else if (dbms == MSSQL_DBMS) {
            configureMsSql()
        } else if (dbms == ORACLE_DBMS) {
            configureOracle()
        } else if (dbms == HSQL_DBMS) {
            configureHsql()
        } else if (dbms == MYSQL_DBMS) {
            configureMySql()
        } else if (!masterUrl || !dropDbSql || !createDbSql || !timeStampType) {
            throw new UnsupportedOperationException("[CubaDbCreation] DBMS '$dbms' is not supported. " +
                    "You should either provide 'masterUrl', 'dropDbSql', 'createDbSql' and 'timeStampType' properties, " +
                    "or specify one of supported DBMS in the 'dbms' property")
        }

        def user = dbms == 'oracle' ? oracleSystemUser : dbUser
        def password = dbms == 'oracle' ? oracleSystemPassword : dbPassword

        project.logger.debug("[CubaDbCreation] Using database URL: '$masterUrl', user: '$user'")

        def dbConnection = null
        try {
            GroovyClass.forName(driver)

            dbConnection = DriverManager.getConnection((String) masterUrl, user, password)
            def statement = dbConnection.createStatement()

            project.logger.debug("[CubaDbCreation] Executing SQL: $dropDbSql")

            statement.executeUpdate((String) dropDbSql)

            if (createDbSql) {
                project.logger.debug("[CubaDbCreation] Executing SQL: $createDbSql")
                statement.executeUpdate((String) createDbSql)
            }

            if (createPostgresSchemeSql) {
                project.logger.debug("[CubaDbCreation] Executing SQL: $createPostgresSchemeSql")
                statement.executeUpdate(createPostgresSchemeSql)
            }
        } catch (Exception e) {
            project.logger.warn('[CubaDbCreation] An error occurred while initializing DB', e)
        } finally {
            if (dbConnection) {
                dbConnection.close()
            }
        }
    }

    protected String configurePostgres() {
        if (!masterUrl) {
            masterUrl = "jdbc:postgresql://$host/postgres$connectionParams"
        }
        if (!dropDbSql) {
            dropDbSql = "drop database if exists \"$dbName\";"
        }
        if (!createDbSql) {
            createDbSql = "create database \"$dbName\" with template=template0 encoding='UTF8';"
            if (connectionParams) {
                def params = parseDatabaseParams(connectionParams)
                String currentSchema = cleanSchemaName(params.get(CURRENT_SCHEMA_PARAM))
                if (StringUtils.isNotEmpty(currentSchema)) {
                    return "create schema \"$currentSchema\";"
                }
            }
        }
        return null
    }

    protected void configureMsSql() {
        if (!masterUrl) {
            masterUrl = dbmsVersion == MS_SQL_2005
                    ? "jdbc:jtds:sqlserver://$host/master$connectionParams"
                    : "jdbc:sqlserver://$host;databaseName=master$connectionParams"
        }
        if (!dropDbSql) {
            dropDbSql = "drop database $dbName;"
        }
        if (!createDbSql) {
            createDbSql = "create database $dbName;"
        }
    }

    protected void configureOracle() {
        if (!masterUrl) {
            masterUrl = "jdbc:oracle:thin:@//$host/$dbName$connectionParams"
        }
        if (!dropDbSql) {
            dropDbSql = "drop user $dbUser cascade;"
        }
        if (!createDbSql) {
            createDbSql =
                    """create user $dbUser identified by $dbPassword default tablespace users;
alter user $dbUser quota unlimited on users;
grant create session,
    create table, create view, create procedure, create trigger, create sequence,
    alter any table, alter any procedure, alter any trigger,
    delete any table,
    drop any table, drop any procedure, drop any trigger, drop any view, drop any sequence
    to $dbUser;"""
        }
    }

    protected void configureHsql() {

        if (!masterUrl) {
            masterUrl = "jdbc:hsqldb:hsql://$host/$dbName$connectionParams"
        }
        if (!dropDbSql) {
            dropDbSql = "drop schema public cascade;"
        }
    }

    protected void configureMySql() {
        if (!masterUrl) {
            if (!connectionParams) {
                connectionParams = '?useSSL=false&allowMultiQueries=true'
            }
            masterUrl = "jdbc:mysql://$host$connectionParams"
        }
        if (!createDbSql) {
            createDbSql = "create database $dbName;"
        }
        if (!dropDbSql) {
            dropDbSql = "drop database $dbName;"
        }
    }
}
