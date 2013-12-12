/*
 * Copyright (c) 2008-2013 Haulmont. All rights reserved.
 * Use is subject to license terms, see http://www.cuba-platform.com/license for details.
 */


import org.apache.commons.lang.StringUtils
import org.apache.commons.lang.text.StrMatcher
import org.apache.commons.lang.text.StrTokenizer
import org.gradle.api.DefaultTask
import org.apache.commons.io.FileUtils
import groovy.sql.Sql

/**
 * @author krivopustov
 * @version $Id$
 */
public abstract class CubaDbTask extends DefaultTask {

    def dbms
    def delimiter = '^'
    def host = 'localhost'
    def dbFolder = 'db'
    def dbName
    def dbUser
    def dbPassword
    def driverClasspath
    protected def dbUrl
    protected def driver
    protected File dbDir
    protected Sql sqlInstance

    private static final String SQL_COMMENT_PREFIX = "--"

    protected void init() {
        if (dbms == 'postgres') {
            driver = 'org.postgresql.Driver'
            dbUrl = "jdbc:postgresql://$host/$dbName"
        } else if (dbms == 'mssql') {
            driver = 'net.sourceforge.jtds.jdbc.Driver'
            dbUrl = "jdbc:jtds:sqlserver://$host/$dbName"
        } else if (dbms == 'oracle') {
            driver = 'oracle.jdbc.OracleDriver'
            dbUrl = "jdbc:oracle:thin:@//$host/$dbName"
        } else if (dbms == 'hsql') {
            driver = 'org.hsqldb.jdbc.JDBCDriver'
            dbUrl = "jdbc:hsqldb:hsql://$host/$dbName"
        } else
            throw new UnsupportedOperationException("DBMS $dbms not supported")

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
        project.logger.info(">>> driverClasspath: $driverClasspath")

    }

    protected List<File> getUpdateScripts() {
        List<File> databaseScripts = new ArrayList<>();

        if (dbDir.exists()) {
            String[] moduleDirs = dbDir.list()
            Arrays.sort(moduleDirs)
            for (String moduleDirName : moduleDirs) {
                File moduleDir = new File(dbDir, moduleDirName)
                File initDir = new File(moduleDir, 'update')
                File scriptDir = new File(initDir, dbms)
                if (scriptDir.exists()) {
                    List files = new ArrayList(FileUtils.listFiles(scriptDir, null, true))
                    URI scriptDirUri = scriptDir.toURI()

                    List sqlFiles = files
                        .findAll { File f -> f.name.endsWith('.sql') || f.name.endsWith('.groovy') }
                        .sort { File f1, File f2 ->
                            URI f1Uri = scriptDirUri.relativize(f1.toURI());
                            URI f2Uri = scriptDirUri.relativize(f2.toURI());
                            f1Uri.getPath().compareTo(f2Uri.getPath());
                        }

                    databaseScripts.addAll(sqlFiles);
                }
            }
        }
        return databaseScripts;
    }

    protected String getScriptName(File file) {
        String path = file.getCanonicalPath()
        String dir = dbDir.getCanonicalPath()
        return path.substring(dir.length() + 1).replace("\\", "/")
    }

    protected boolean isEmpty(String sql) {
        String[] lines = sql.split("\\r?\\n")
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
        StrTokenizer tokenizer = new StrTokenizer(
                script, StrMatcher.charSetMatcher(delimiter), StrMatcher.singleQuoteMatcher())
        Sql sql = getSql()
        while (tokenizer.hasNext()) {
            String sqlCommand = tokenizer.nextToken().trim()
            if (!isEmpty(sqlCommand)) {
                project.logger.info(">>> executing SQL: $sqlCommand")
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
}