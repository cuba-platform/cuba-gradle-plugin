/*
 * Copyright (c) 2008-2013 Haulmont. All rights reserved.
 * Use is subject to license terms, see http://www.cuba-platform.com/license for details.
 */


import groovy.sql.Sql
import org.apache.commons.io.FileUtils
import org.apache.commons.io.FilenameUtils
import org.apache.commons.lang.StringUtils
import org.apache.commons.lang.text.StrMatcher
import org.apache.commons.lang.text.StrTokenizer
import org.gradle.api.DefaultTask

import java.nio.file.Path
import java.util.regex.Pattern

/**
 * @author krivopustov
 * @version $Id$
 */
public abstract class CubaDbTask extends DefaultTask {

    def dbms
    def dbmsVersion
    def delimiter = '^'
    def host = 'localhost'
    def dbFolder = 'db'
    def connectionParams = ''
    def dbName
    def dbUser
    def dbPassword
    def driverClasspath
    def dbUrl
    def driver
    protected File dbDir
    protected Sql sqlInstance

    private static final String SQL_COMMENT_PREFIX = "--"

    protected void init() {
        if (!driver || !dbUrl) {
            if (dbms == 'postgres') {
                driver = 'org.postgresql.Driver'
                dbUrl = "jdbc:postgresql://$host/$dbName$connectionParams"
            } else if (dbms == 'mssql') {
                driver = 'net.sourceforge.jtds.jdbc.Driver'
                dbUrl = "jdbc:jtds:sqlserver://$host/$dbName$connectionParams"
            } else if (dbms == 'oracle') {
                driver = 'oracle.jdbc.OracleDriver'
                dbUrl = "jdbc:oracle:thin:@//$host/$dbName$connectionParams"
            } else if (dbms == 'hsql') {
                driver = 'org.hsqldb.jdbc.JDBCDriver'
                dbUrl = "jdbc:hsqldb:hsql://$host/$dbName$connectionParams"
            } else if (dbms == 'mysql') {
                driver = 'com.mysql.jdbc.Driver'
                if (!connectionParams) connectionParams = '?useSSL=false&allowMultiQueries=true'
                dbUrl = "jdbc:mysql://$host/$dbName$connectionParams"
            } else
                throw new UnsupportedOperationException("DBMS $dbms is not supported. " +
                        "You should either provide 'driver' and 'dbUrl' properties, or specify one of supported DBMS in 'dbms' property")
        }

        dbDir = new File(project.buildDir, dbFolder)

        if (!driverClasspath) {
            driverClasspath = project.configurations.jdbc.fileCollection { true }.asPath
            project.configurations.jdbc.fileCollection { true }.files.each { File file ->
                GroovyObject.class.classLoader.addURL(file.toURI().toURL())
            }
        } else {
            driverClasspath.tokenize(File.pathSeparator).each { String path ->
                def url = new File(path).toURI().toURL()
                GroovyObject.class.classLoader.addURL(url)
            }
        }
        project.logger.info("[CubaDbTask] driverClasspath: $driverClasspath")

    }

    protected void initDatabase(String oneModuleDir = null) {
        try {
            ScriptFinder scriptFinder = new ScriptFinder(dbms, dbmsVersion, dbDir, ['sql'])
            def initScripts = scriptFinder.getInitScripts(oneModuleDir)
            initScripts.each { File file ->
                project.logger.warn("Executing SQL script: ${file.absolutePath}")
                executeSqlScript(file)
                String name = getScriptName(file)
                markScript(name, true)
            }
        } finally {
            // mark all update scripts as executed even in case of createDb failure
            ScriptFinder scriptFinder = new ScriptFinder(dbms, dbmsVersion, dbDir, ['sql', 'groovy'])
            def updateScripts = scriptFinder.getUpdateScripts(oneModuleDir)
            updateScripts.each { File file ->
                String name = getScriptName(file)
                markScript(name, true)
            }
        }
    }

    protected String getScriptName(File file) {
        String path = file.getCanonicalPath()
        String dir = dbDir.getCanonicalPath()
        return path.substring(dir.length() + 1).replace("\\", "/")
    }

    protected boolean isEmpty(String sql) {
        String[] lines = sql.split("[\r\n]+")
        for (String line : lines) {
            line = line.trim()
            if (!line.startsWith(SQL_COMMENT_PREFIX) && !StringUtils.isBlank(line)) {
                return false
            }
        }
        return true
    }

    protected void executeSqlScript(File file) {
        String script = FileUtils.readFileToString(file, "UTF-8")
        script = script.replaceAll("[\r\n]+", System.getProperty("line.separator"))

        Sql sql = getSql()

        def splitter = new ScriptSplitter(delimiter)
        def commands = splitter.split(script)
        for (String sqlCommand : commands) {
            if (!isEmpty(sqlCommand)) {
                project.logger.info("[CubaDbTask] executing SQL: $sqlCommand")
                sql.execute(sqlCommand)
            }
        }
    }

    protected void markScript(String name, boolean init) {
        Sql sql = getSql()
        sql.executeUpdate('insert into SYS_DB_CHANGELOG (SCRIPT_NAME, IS_INIT) values (?, ?)', [name, (init ? 1 : 0)])
    }

    protected Sql getSql() {
        if (!sqlInstance)
            sqlInstance = Sql.newInstance(dbUrl, dbUser, dbPassword, driver)
        return sqlInstance
    }

    protected void closeSql() {
        if (sqlInstance) {
            sqlInstance.close()
            sqlInstance = null
        }
    }

    static class ScriptFinder {

        def dbmsType
        def dbmsVersion
        File dbDir
        List extensions
        def logger

        ScriptFinder(dbmsType, dbmsVersion, File dbDir, List extensions) {
            this.dbmsType = dbmsType
            this.dbmsVersion = dbmsVersion
            this.dbDir = dbDir
            this.extensions = extensions
        }

        List<String> getModuleDirs() {
            if (dbDir.exists()) {
                String[] moduleDirs = dbDir.list()
                Arrays.sort(moduleDirs)
                return Arrays.asList(moduleDirs)
            }
            return []
        }

        // Copy of com.haulmont.cuba.core.sys.DbUpdaterEngine#getUpdateScripts
        List<File> getUpdateScripts(String oneModuleDir = null) {
            List<File> databaseScripts = new ArrayList<>();

            if (dbDir.exists()) {
                String[] moduleDirs = dbDir.list()
                Arrays.sort(moduleDirs)
                for (String moduleDirName : moduleDirs) {
                    if (oneModuleDir && moduleDirName != oneModuleDir)
                        continue
                    File moduleDir = new File(dbDir, moduleDirName)
                    File initDir = new File(moduleDir, 'update')
                    File scriptDir = new File(initDir, dbmsType)
                    if (scriptDir.exists()) {
                        List list = new ArrayList(FileUtils.listFiles(scriptDir, extensions as String[], true))
                        def file2dir = [:]
                        list.each { file2dir.put(it, scriptDir) }

                        if (dbmsVersion) {
                            File optScriptDir = new File(initDir, dbmsType + "-" + dbmsVersion)
                            if (optScriptDir.exists()) {
                                def filesMap = [:]
                                list.each { File file ->
                                    filesMap.put(scriptDir.toPath().relativize(file.toPath()).toString(), file)
                                }

                                List optList = new ArrayList(FileUtils.listFiles(optScriptDir, extensions as String[], true))
                                optList.each { file2dir.put(it, optScriptDir) }

                                def optFilesMap = [:]
                                optList.each { File optFile ->
                                    optFilesMap.put(optScriptDir.toPath().relativize(optFile.toPath()).toString(), optFile)
                                }

                                filesMap.putAll(optFilesMap)
                                list.clear()
                                list.addAll(filesMap.values())
                            }
                        }

                        List files = list.sort { File f1, File f2 ->
                            File f1Parent = f1.getAbsoluteFile().getParentFile()
                            File f2Parent = f2.getAbsoluteFile().getParentFile()
                            if (f1Parent.equals(f2Parent)) {
                                String f1Name = FilenameUtils.getBaseName(f1.getName())
                                String f2Name = FilenameUtils.getBaseName(f2.getName())
                                return f1Name.compareTo(f2Name)
                            }
                            File dir1 = file2dir.get(f1)
                            File dir2 = file2dir.get(f2)
                            Path p1 = dir1.toPath().relativize(f1.toPath())
                            Path p2 = dir2.toPath().relativize(f2.toPath())
                            return p1.compareTo(p2)
                        }

                        databaseScripts.addAll(files);
                    }
                }
            }
            return databaseScripts;
        }

        List<File> getInitScripts(String oneModuleDir = null) {
            List<File> files = []
            if (dbDir.exists()) {
                String[] moduleDirs = dbDir.list()
                Arrays.sort(moduleDirs)
                logger?.info("[CubaDbTask] [getInitScripts] modules: $moduleDirs")
                for (String moduleDirName: moduleDirs) {
                    if (oneModuleDir && moduleDirName != oneModuleDir)
                        continue
                    File moduleDir = new File(dbDir, moduleDirName)
                    File initDir = new File(moduleDir, "init")
                    File scriptDir = new File(initDir, dbmsType)
                    if (scriptDir.exists()) {
                        FilenameFilter filenameFilter = new FilenameFilter() {
                            public boolean accept(File dir, String name) {
                                return name.endsWith("create-db.sql")
                            }
                        }
                        File[] scriptFiles = scriptDir.listFiles(filenameFilter)
                        List<File> list = new ArrayList<>(Arrays.asList(scriptFiles))

                        if (dbmsVersion) {
                            File optScriptDir = new File(initDir, dbmsType + "-" + dbmsVersion)
                            if (optScriptDir.exists()) {
                                File[] optFiles = optScriptDir.listFiles(filenameFilter)

                                def filesMap = [:]
                                scriptFiles.each { File file ->
                                    filesMap.put(scriptDir.toPath().relativize(file.toPath()).toString(), file)
                                }

                                def optFilesMap = [:]
                                optFiles.each { File optFile ->
                                    optFilesMap.put(optScriptDir.toPath().relativize(optFile.toPath()).toString(), optFile)
                                }

                                filesMap.putAll(optFilesMap)
                                list.clear()
                                list.addAll(filesMap.values())
                            }
                        }

                        list.sort { File f1, File f2 -> f1.getName().compareTo(f2.getName()) }

                        logger?.info("[CubaDbTask] [getInitScripts] files: $list")
                        files.addAll(list)
                    } else {
                        logger?.info("[CubaDbTask] [getInitScripts] $scriptDir doesn't exist")
                    }
                }
            } else {
                logger?.info("[CubaDbTask] [getInitScripts] $dbDir doesn't exist")
            }
            return files
        }
    }

    static class ScriptSplitter {

        String delimiter

        ScriptSplitter(String delimiter) {
            this.delimiter = delimiter
        }

        def List<String> split(String script) {
            def qd = Pattern.quote(delimiter)
            String[] commands = script.split('(?<!' + qd + ')' + qd + '(?!' + qd + ')') // regex for ^: (?<!\^)\^(?!\^)
            return Arrays.asList(commands).collect { it.replace(delimiter + delimiter, delimiter) }
        }
    }
}