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
import com.haulmont.gradle.task.db.ScriptSplitter
import org.apache.commons.lang3.StringUtils
import org.codehaus.groovy.tools.GroovyClass
import org.gradle.api.tasks.TaskAction

import java.lang.reflect.Field
import java.sql.DriverManager
import java.sql.SQLException

class CubaDbCreation extends AbstractCubaDbCreation {

    protected static final String VENDOR_CODE_FIELD = "vendorCode"

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

    @SuppressWarnings("GroovyAssignabilityCheck")
    @Override
    void dropAndCreateDatabase() {
        String createPostgresSchemeSql = null
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

        GroovyClass.forName(driver)

        if (!executeSql(user, password, dropDbSql)) {
            throw new RuntimeException('[CubaDbCreation] Failed to drop database')
        }

        if (createDbSql) {
            project.logger.debug('[CubaDbCreation] Creating database')

            def executed = executeSql(user, password, createDbSql)
            if (!executed) {
                throw new RuntimeException('[CubaDbCreation] Failed to create database')
            }
        }

        if (createPostgresSchemeSql) {
            project.logger.debug('[CubaDbCreation] Creating Postgres scheme')

            def executed = executeSql(user, password, createPostgresSchemeSql)
            if (!executed) {
                throw new RuntimeException('[CubaDbCreation] Failed to create Postgres scheme')
            }
        }
    }

    @Override
    protected void initAppHomeDir() {
        setAppHomeDir(project.cuba.appHome);
    }

    protected boolean executeSql(String user, String password, String sql) {
        def executed = true

        def conn = null
        def statement = null

        try {
            Properties properties = System.getProperties()
            if (properties != null) {
                for (Object k : properties.keySet()) {
                    def v = properties.get(key)
                    project.logger.warn("[CubaDbCreation] key: $k, value: $v")

                }

            }
            conn = DriverManager.getConnection((String) masterUrl, user, password)
            statement = conn.createStatement()

            ScriptSplitter splitter = new ScriptSplitter(delimiter)
            List<String> commands = splitter.split(sql)
            project.logger.debug("[CubaDbCreation] Executing SQL: $sql")

            for (String command : commands) {
                if (!isEmpty(command)) {
                    statement.execute(command)
                }
            }
        } catch (Exception e) {
            if (!isAcceptableException(e)) {
                if (project.logger.isInfoEnabled()) {
                    project.logger.info("[CubaDbCreation] Failed to execute SQL: $sql", e)
                } else {
                    project.logger.warn("[CubaDbCreation] Failed to execute SQL: $sql\nError: $e.message")
                }
                executed = false
            }
        } finally {
            if (statement) {
                statement.close()
            }
            if (conn) {
                conn.close()
            }
        }

        executed
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
            dropDbSql = "drop user $dbUser cascade$delimiter"
        }
        if (!createDbSql) {
            createDbSql =
                    """create user $dbUser identified by $dbPassword default tablespace users$delimiter
alter user $dbUser quota unlimited on users$delimiter
grant create session,
    create table, create view, create procedure, create trigger, create sequence,
    alter any table, alter any procedure, alter any trigger,
    delete any table,
    drop any table, drop any procedure, drop any trigger, drop any view, drop any sequence
    to $dbUser$delimiter"""
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

    protected boolean isAcceptableException(Exception e) {
        def acceptableDbms = dbms == ORACLE_DBMS || dbms == MYSQL_DBMS || dbms == MSSQL_DBMS
        if (!acceptableDbms) {
            return false
        }

        def vendorCode = getVendorCode(e)

        dbNotExistsError(dbms, vendorCode)
    }

    /**
     * Obtains exception vendor code via reflection from the given exception.
     *
     * @param e exception
     *
     * @return vendor specific error code
     */
    @SuppressWarnings("GroovyAccessibility")
    protected static int getVendorCode(Exception e) {
        Field vendorCodeField = null

        def fields = SQLException.class.privateGetDeclaredFields(false)
        for (int i = 0; i < fields.length; i++) {
            def f = fields[i]
            if (VENDOR_CODE_FIELD == f.getName()) {
                vendorCodeField = f
                break
            }
        }

        vendorCodeField.setAccessible(true)
        vendorCodeField.get(e) as int
    }

    /**
     * Checks whether the given {@code errorCode} relates to exception
     * caused by performing DB drop when DB does not exist.
     *
     * @param dbms DBMS type
     * @param vendorCode vendor error code
     */
    protected static boolean dbNotExistsError(String dbms, int vendorCode) {
        switch (dbms) {
            case MYSQL_DBMS:
                return vendorCode == 1008
            case MS_SQL_2005:
            case MSSQL_DBMS:
                return vendorCode == 3701
            case ORACLE_DBMS:
                return vendorCode == 1918
            default:
                return false
        }
    }
}
