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

import groovy.sql.Sql
import org.gradle.api.tasks.TaskAction

import java.sql.SQLException
import java.util.logging.Level
import java.util.logging.Logger

/**
 */
class CubaDbUpdate extends CubaDbTask {

    def requiredTables = ['reports': 'report_report',
                          'workflow': 'wf_proc',
                          'ccpayments': 'cc_credit_card',
                          'bpm': 'bpm_proc_definition']

    CubaDbUpdate() {
        setGroup('Database')
    }

    @TaskAction
    def updateDb() {
        init()

        try {
            runRequiredInitScripts()

            ScriptFinder scriptFinder = new ScriptFinder(dbms, dbmsVersion, dbDir, ['sql'])
            List<File> files = scriptFinder.getUpdateScripts()

            List<String> scripts = getExecutedScripts()
            def toExecute = files.findAll { File file ->
                String name = getScriptName(file)
                !scripts.contains(name)
            }

            if (project.logger.isInfoEnabled()) {
                project.logger.info("Updates: \n" + toExecute.collect{ "\t" + it.getAbsolutePath() }.join("\n") )
            }

            toExecute.each { File file ->
                executeScript(file)
                String name = getScriptName(file)
                markScript(name, false)
            }
        } finally {
            closeSql()
        }
    }

    protected void runRequiredInitScripts() {
        if (!tableExists('SYS_DB_CHANGELOG')) {
            project.logger.warn("Table SYS_DB_CHANGELOG does not exist, running all init scripts")
            try {
                getSql().executeUpdate("create table SYS_DB_CHANGELOG (" +
                        "SCRIPT_NAME varchar(300) not null primary key, " +
                        "CREATE_TS $timeStampType default current_timestamp, " +
                        "IS_INIT integer default 0)")

                initDatabase()
            } finally {
                closeSql()
            }
            return
        }

        ScriptFinder scriptFinder = new ScriptFinder(dbms, dbmsVersion, dbDir, ['sql'])
        scriptFinder.getModuleDirs().each { String dirName ->
            def moduleName = dirName.substring(3)
            def reqTable = requiredTables[moduleName]
            if (reqTable && !tableExists(reqTable)) {
                project.logger.warn("Table $reqTable required for $moduleName does not exist, running init scripts")
                initDatabase(dirName)
            }
        }
    }

    protected boolean tableExists(String tableName) {
        try {
            def sqlLogger = Logger.getLogger(Sql.class.getName())
            def saveLevel = sqlLogger.level
            try {
                sqlLogger.level = Level.SEVERE // suppress confusing error output
                getSql().rows("select * from $tableName where 0=1".toString())
            } finally {
                sqlLogger.level = saveLevel
            }
            return true
        } catch (SQLException e) {
            String mark = dbms == 'oracle' ? 'ora-00942' : tableName.toLowerCase()
            if (e.message?.toLowerCase()?.contains(mark)) {
                return false
            } else {
                throw e;
            }
        }
    }

    protected void executeScript(File file) {
        project.logger.warn("Executing script " + file.getPath())
        if (file.name.endsWith('.sql'))
            executeSqlScript(file)
    }


    protected List<String> getExecutedScripts() {
        return getSql().rows('select SCRIPT_NAME from SYS_DB_CHANGELOG').collect { row -> row.script_name }
    }
}