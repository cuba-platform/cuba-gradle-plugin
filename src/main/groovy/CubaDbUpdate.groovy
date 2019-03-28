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

import com.haulmont.gradle.task.db.CubaDbTask
import groovy.sql.Sql
import org.apache.commons.dbcp2.BasicDataSource
import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.StringUtils
import org.codehaus.groovy.control.CompilerConfiguration
import org.gradle.api.tasks.TaskAction
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.ResultSet
import java.sql.SQLException
import java.util.logging.Level
import java.util.logging.Logger
import static com.haulmont.gradle.task.db.CubaDbTask.*

class CubaDbUpdate extends CubaDbTask {

    boolean executeGroovy = true

    CubaDbUpdate() {
        setGroup('Database')
    }

    @TaskAction
    def updateDb() {
        init()

        try {
            runRequiredInitScripts()

            ScriptFinder scriptFinder = new ScriptFinder(
                    dbms, dbmsVersion, dbDir, executeGroovy ? ['sql', 'upgrade.groovy'] : ['sql'], project)
            List<File> files = scriptFinder.getUpdateScripts(null)

            List<String> scripts = getExecutedScripts()
            def toExecute = files.findAll { File file ->
                String name = getScriptName(file)
                !containsIgnoringPrefix(scripts, name)
            }

            if (project.logger.isInfoEnabled()) {
                project.logger.info("Updates: \n" + toExecute.collect { "\t" + it.getAbsolutePath() }.join("\n"))
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
                def pkLength = dbms == 'mysql' ? 190 : 300
                getSql().executeUpdate("create table SYS_DB_CHANGELOG (" +
                        "SCRIPT_NAME varchar($pkLength) not null primary key, " +
                        "CREATE_TS $timeStampType default current_timestamp, " +
                        "IS_INIT integer default 0)")

                initDatabase(null)
            } finally {
                closeSql()
            }
            return
        }

        List<String> executedScripts = getExecutedScripts()
        ScriptFinder scriptFinder = new ScriptFinder(dbms, dbmsVersion, dbDir, ['sql'], project)
        def dirs = scriptFinder.getModuleDirs()
        if (dirs.size() > 1) {
            def lastDir = dirs[dirs.size() - 1]
            def dashIdx = lastDir.indexOf('-')
            if (dashIdx < 1 || dashIdx > lastDir.length() - 2)
                throw new RuntimeException("Invalid DB scripts directory name format: $lastDir")
            if (lastDir.substring(dashIdx + 1) == project.rootProject.name) {
                // if own scripts exist, check all db folders except the last because it is the folder of the app and we need only components
                dirs = dirs.subList(0, dirs.size() - 1)
            }
            dirs.each { String dirName ->
                def anInitScriptHasBeenExecuted = false
                List<File> initScripts = scriptFinder.getInitScripts(dirName)
                if (!initScripts.isEmpty()) {
                    for (File file : initScripts) {
                        String script = getScriptName(file)
                        if (containsIgnoringPrefix(executedScripts, script)) {
                            anInitScriptHasBeenExecuted = true
                            break
                        }
                    }
                    if (!anInitScriptHasBeenExecuted && !initializedByOwnScript(executedScripts, dirName)) {
                        project.logger.warn("No init scripts from $dirName have been executed, running init scripts")
                        initDatabase(dirName)
                    }
                }
            }
        }
    }

    protected boolean initializedByOwnScript(List<String> executedScripts, String dirName) {
        executedScripts.find {
            it.substring(it.lastIndexOf('/') + 1).equalsIgnoreCase("01.${dirName.substring(3)}-create-db.sql")
        }
    }

    protected boolean containsIgnoringPrefix(List<String> strings, String s) {
        strings.find { it -> it.length() > 3 && s.length() > 3 && it.substring(3) == s.substring(3) }
    }

    protected boolean tableExists(String tableName) {
        if (connectionParams) {
            Map<String, String> paramsMap = parseDatabaseParams(connectionParams)
            String currentSchema = cleanSchemaName(paramsMap.get(CURRENT_SCHEMA_PARAM))
            if (StringUtils.isNotEmpty(currentSchema)) {
                return tableExistsInSchema(tableName, currentSchema)
            }
        }
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
                throw e
            }
        }
    }

    protected boolean tableExistsInSchema(String tableName, String schemaName) {
        Connection connection = getSql().getConnection()
        DatabaseMetaData dbMetaData = connection.getMetaData()
        String catalogName = dbms == 'mysql' ? connection.getCatalog() : null
        ResultSet tables = dbMetaData.getTables(catalogName, schemaName, "%", null)
        while (tables.next()) {
            String tableNameFromDb = tables.getString("TABLE_NAME")
            if (tableName.equalsIgnoreCase(tableNameFromDb)) {
                return true
            }
        }
        return false
    }

    protected void executeScript(File file) {
        project.logger.warn("Executing script " + file.getPath())
        if (file.name.endsWith('.sql')) {
            executeSqlScript(file)
        } else if (file.name.endsWith(".upgrade.groovy")) {
            if (!executeGroovy) {
                project.logger.warn("Skip execution of groovy script " + file.getPath())
            } else {
                executeGroovyScript(file)
            }
        }
    }

    protected void executeGroovyScript(File file) {
        def dataSource = null
        try {
            dataSource = new BasicDataSource()
            dataSource.setUrl(dbUrl)
            dataSource.setUsername(dbUser)
            dataSource.setPassword(dbPassword)
            dataSource.setDriverClassName(driver)
            dataSource.setDriverClassLoader(GroovyObject.class.classLoader)
            dataSource.setMaxTotal(5)

            def cc = new CompilerConfiguration()
            cc.setRecompileGroovySource(true)

            def bind = new Binding()
            bind.setProperty("ds", dataSource)
            bind.setProperty("log", LoggerFactory.getLogger(file.getName()))

            def shell = new GroovyShell(getClass().getClassLoader(), bind, cc)
            def content = FileUtils.readFileToString(file, StandardCharsets.UTF_8)
            def script = shell.parse(content)
            script.run()
        } catch (Exception e) {
            throw new RuntimeException(e)
        } finally {
            if (dataSource != null) {
                try {
                    dataSource.close()
                } catch (Exception e) {
                    //Do nothing
                }
            }
        }
    }

    protected List<String> getExecutedScripts() {
        return getSql().rows('select SCRIPT_NAME from SYS_DB_CHANGELOG').collect { row -> row.script_name }
    }
}