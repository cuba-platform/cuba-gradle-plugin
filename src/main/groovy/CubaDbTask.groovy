/*
 * Copyright (c) 2011 Haulmont Technology Ltd. All Rights Reserved.
 * Haulmont Technology proprietary and confidential.
 * Use is subject to license terms.
 */

import org.gradle.api.DefaultTask
import org.apache.commons.io.FileUtils
import groovy.sql.Sql

/**
 * @author krivopustov
 * @version $Id$
 */
public abstract class CubaDbTask extends DefaultTask {

    def dbms
    def delimiter
    def host = 'localhost'
    def dbFolder = 'db'
    def dbName
    def dbUser
    def dbPassword
    protected def dbUrl
    protected def driver
    protected File dbDir
    protected Sql sqlInstance

    protected void init() {
        if (dbms == 'postgres') {
            driver = 'org.postgresql.Driver'
            dbUrl = "jdbc:postgresql://$host/$dbName"
            if (!delimiter)
                delimiter = '^'
        } else if (dbms == 'mssql') {
            driver = 'net.sourceforge.jtds.jdbc.Driver'
            dbUrl = "jdbc:jtds:sqlserver://$host/$dbName"
            if (!delimiter)
                delimiter = '^'
        } else
            throw new UnsupportedOperationException("DBMS $dbms not supported")

        dbDir = new File(project.buildDir, dbFolder)

        project.configurations.jdbc.fileCollection { true }.files.each { File file ->
            GroovyObject.class.classLoader.addURL(file.toURI().toURL())
        }
    }

    protected List<File> getUpdateScripts() {
        List<File> databaseScripts = new ArrayList<>();

        if (dbDir.exists()) {
            String[] moduleDirs = dbDir.list()
            Arrays.sort(moduleDirs)
            for (String moduleDirName : moduleDirs) {
                File moduleDir = new File(dbDir, moduleDirName)
                File initDir = new File(moduleDir, "update")
                File scriptDir = new File(initDir, dbms)
                if (scriptDir.exists()) {
                    List files = new ArrayList(FileUtils.listFiles(scriptDir, null, true))
                    URI scriptDirUri = scriptDir.toURI()

                    List sqlFiles = files
                        .findAll { File f -> f.name.endsWith(".sql") }
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