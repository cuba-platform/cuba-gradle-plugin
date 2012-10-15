import org.gradle.api.tasks.TaskAction

/**
 * 
 * @author krivopustov
 * @version $Id$
 */
class CubaDbCreation extends CubaDbTask {

    def driverClasspath
    def dropDbSql
    def createDbSql

    @TaskAction
    def createDb() {
        init()

        def masterUrl

        if (!driverClasspath)
            driverClasspath = project.configurations.jdbc.fileCollection { true }.asPath

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
        } else
            throw new UnsupportedOperationException("DBMS $dbms not supported")

        project.logger.warn("Executing SQL: $dropDbSql")
        try {
            project.ant.sql(
                    classpath: driverClasspath,
                    driver: driver,
                    url: masterUrl,
                    userid: dbUser, password: dbPassword,
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
                userid: dbUser, password: dbPassword,
                autocommit: true,
                encoding: "UTF-8",
                createDbSql
        )

        getSql().executeUpdate("create table SYS_DB_CHANGELOG(" +
                "SCRIPT_NAME varchar(300) not null primary key, " +
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

        closeSql()
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