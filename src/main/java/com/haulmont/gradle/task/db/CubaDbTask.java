/*
 * Copyright (c) 2008-2017 Haulmont.
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
 */

package com.haulmont.gradle.task.db;

import com.haulmont.gradle.utils.AppPropertiesLoader;
import groovy.lang.GroovyObject;
import groovy.sql.Sql;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.Project;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.function.Function;
import java.util.stream.Collectors;

public abstract class CubaDbTask extends DefaultTask {

    public static final String POSTGRES_DBMS = "postgres";
    public static final String MSSQL_DBMS = "mssql";
    public static final String ORACLE_DBMS = "oracle";
    public static final String MYSQL_DBMS = "mysql";
    public static final String HSQL_DBMS = "hsql";

    protected static final String SQL_COMMENT_PREFIX = "--";
    protected static final String CURRENT_SCHEMA_PARAM = "currentSchema";
    protected static final String MS_SQL_2005 = "2005";

    protected AppPropertiesLoader appPropertiesLoader = new AppPropertiesLoader();
    protected Properties properties;
    protected String storeName = Stores.MAIN;
    protected String dbms;
    protected String dbmsVersion;
    protected String delimiter = "^";
    protected String host = "localhost";
    protected String dbFolder = "db";
    protected String connectionParams = "";
    protected String dbName;
    protected String dbUser;
    protected String dbPassword;
    protected String driverClasspath;
    protected String dbUrl;
    protected String driver;
    protected String timeStampType;
    protected String appHomeDir;
    protected File dbDir;
    protected Sql sqlInstance;

    private final Logger log = LoggerFactory.getLogger(CubaDbTask.class);

    public String getStoreName() {
        return storeName;
    }

    public void setStoreName(String storeName) {
        this.storeName = storeName;
    }

    public String getDbms() {
        return dbms;
    }

    public void setDbms(String dbms) {
        this.dbms = dbms;
    }

    public String getDbmsVersion() {
        return dbmsVersion;
    }

    public void setDbmsVersion(String dbmsVersion) {
        this.dbmsVersion = dbmsVersion;
    }

    public String getDelimiter() {
        return delimiter;
    }

    public void setDelimiter(String delimiter) {
        this.delimiter = delimiter;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getDbFolder() {
        return dbFolder;
    }

    public void setDbFolder(String dbFolder) {
        this.dbFolder = dbFolder;
    }

    public String getConnectionParams() {
        return connectionParams;
    }

    public void setConnectionParams(String connectionParams) {
        this.connectionParams = connectionParams;
    }

    public String getDbName() {
        return dbName;
    }

    public void setDbName(String dbName) {
        this.dbName = dbName;
    }

    public String getDbUser() {
        return dbUser;
    }

    public void setDbUser(String dbUser) {
        this.dbUser = dbUser;
    }

    public String getDbPassword() {
        return dbPassword;
    }

    public void setDbPassword(String dbPassword) {
        this.dbPassword = dbPassword;
    }

    public String getDriverClasspath() {
        return driverClasspath;
    }

    public void setDriverClasspath(String driverClasspath) {
        this.driverClasspath = driverClasspath;
    }

    public String getDbUrl() {
        return dbUrl;
    }

    public void setDbUrl(String dbUrl) {
        this.dbUrl = dbUrl;
    }

    public String getDriver() {
        return driver;
    }

    public void setDriver(String driver) {
        this.driver = driver;
    }

    public String getTimeStampType() {
        return timeStampType;
    }

    public void setTimeStampType(String timeStampType) {
        this.timeStampType = timeStampType;
    }

    public File getDbDir() {
        return dbDir;
    }

    public void setDbDir(File dbDir) {
        this.dbDir = dbDir;
    }

    public void setAppHomeDir(String appHomeDir) {
        this.appHomeDir = appHomeDir;
    }

    protected void initAppHomeDir() {
    }

    protected void init() {
        initAppHomeDir();
        properties = appPropertiesLoader.initProperties(getProject(), appHomeDir);
        String dataSourceProvider = properties.getProperty("cuba.dataSourceProvider" + getStorePostfix());
        if (dataSourceProvider == null || "jndi".equals(dataSourceProvider)) {
            initJndiConnectionParams();
        } else if ("application".equals(dataSourceProvider)) {
            initApplicationConnectionParams();
        } else {
            throw new RuntimeException(String.format("DataSource provider '%s' is unsupported! Available: 'jndi', 'application'", dataSourceProvider));
        }

        Project project = getProject();
        dbDir = new File(project.getBuildDir(), dbFolder);

        initDriverClasspath(project);
    }

    protected void initDriverClasspath(Project project) {
        ClassLoader classLoader = GroovyObject.class.getClassLoader();
        if (StringUtils.isBlank(driverClasspath)) {
            driverClasspath = project.getConfigurations().getByName("jdbc").fileCollection(dependency -> true).getAsPath();
            project.getConfigurations().getByName("jdbc").fileCollection(dependency -> true).getFiles()
                    .forEach(file -> {
                        try {
                            Method addURLMethod = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
                            addURLMethod.setAccessible(true);
                            addURLMethod.invoke(classLoader, file.toURI().toURL());
                        } catch (NoSuchMethodException | IllegalAccessException
                                | InvocationTargetException | MalformedURLException e) {
                            throw new GradleException("Exception when invoke 'java.net.URLClassLoader.addURL' method", e);
                        }

                    });
        } else {
            java.util.StringTokenizer tokenizer = new java.util.StringTokenizer(driverClasspath, File.pathSeparator);
            while (tokenizer.hasMoreTokens()) {
                try {
                    URL url = new File(tokenizer.nextToken()).toURI().toURL();
                    Method addURLMethod = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
                    addURLMethod.setAccessible(true);
                    addURLMethod.invoke(classLoader, url);
                } catch (NoSuchMethodException | IllegalAccessException
                        | InvocationTargetException | MalformedURLException e) {
                    throw new GradleException("Exception when invoke 'java.net.URLClassLoader.addURL' method", e);
                }
            }
        }
        project.getLogger().info("[CubaDbTask] driverClasspath: " + driverClasspath);
    }

    protected void initJndiConnectionParams() {
        if (StringUtils.isBlank(driver) || StringUtils.isBlank(dbUrl)) {
            initConnectionParams(false);
        }
    }

    protected void initApplicationConnectionParams() {
        dbms = properties.getProperty("cuba.dbmsType" + getStorePostfix());
        dbmsVersion = properties.getProperty("cuba.dbmsVersion" + getStorePostfix());

        String cubaDataSourcePrefix = "cuba.dataSource" + getStorePostfix();
        dbUrl = properties.getProperty(cubaDataSourcePrefix + ".jdbcUrl");
        dbUser = properties.getProperty(cubaDataSourcePrefix + ".username");
        dbPassword = properties.getProperty(cubaDataSourcePrefix + ".password");
        dbName = properties.getProperty(cubaDataSourcePrefix + ".dbName");
        host = properties.getProperty(cubaDataSourcePrefix + ".host");
        if (StringUtils.isNotEmpty(properties.getProperty(cubaDataSourcePrefix + ".port"))) {
            host = host + ":" + properties.getProperty(cubaDataSourcePrefix + ".port");
        }
        connectionParams = properties.getProperty(cubaDataSourcePrefix + ".connectionParams");

        if (!StringUtils.isBlank(dbUrl)) {
            initConnectionParams(true);
        } else {
            initConnectionParams(false);
        }
    }

    protected String getStorePostfix() {
        if (Stores.MAIN.equals(storeName)) {
            return "";
        }
        return "_".concat(storeName);
    }

    protected void initConnectionParams(boolean dbUrlProvided) {
        connectionParams = StringUtils.defaultIfEmpty(connectionParams, "");
        if (POSTGRES_DBMS.equals(dbms)) {
            driver = "org.postgresql.Driver";
            dbUrl = dbUrlProvided ? dbUrl : "jdbc:postgresql://" + host + "/" + dbName + connectionParams;
            if (StringUtils.isBlank(timeStampType)) {
                timeStampType = "timestamp";
            }
        } else if (MSSQL_DBMS.equals(dbms)) {
            if (MS_SQL_2005.equals(dbmsVersion)) {
                driver = "net.sourceforge.jtds.jdbc.Driver";
                dbUrl = dbUrlProvided ? dbUrl : "jdbc:jtds:sqlserver://" + host + "/" + dbName + connectionParams;
            } else {
                driver = "com.microsoft.sqlserver.jdbc.SQLServerDriver";
                dbUrl = dbUrlProvided ? dbUrl : "jdbc:sqlserver://" + host + ";databaseName=" + dbName + connectionParams;
            }
            if (StringUtils.isBlank(timeStampType)) {
                timeStampType = "datetime";
            }
        } else if (ORACLE_DBMS.equals(dbms)) {
            driver = "oracle.jdbc.OracleDriver";
            dbUrl = dbUrlProvided ? dbUrl : "jdbc:oracle:thin:@//" + host + "/" + dbName + connectionParams;
            if (StringUtils.isBlank(timeStampType)) {
                timeStampType = "timestamp";
            }
        } else if (HSQL_DBMS.equals(dbms)) {
            driver = "org.hsqldb.jdbc.JDBCDriver";
            dbUrl = dbUrlProvided ? dbUrl : "jdbc:hsqldb:hsql://" + host + "/" + dbName + connectionParams;
            if (StringUtils.isBlank(timeStampType)) {
                timeStampType = "timestamp";
            }
        } else if (MYSQL_DBMS.equals(dbms)) {
            driver = "com.mysql.jdbc.Driver";
            if (StringUtils.isBlank(connectionParams)) {
                connectionParams = "?useSSL=false&allowMultiQueries=true&serverTimezone=UTC";
            }
            dbUrl = dbUrlProvided ? dbUrl : "jdbc:mysql://" + host + "/" + dbName + connectionParams;
            if (StringUtils.isBlank(timeStampType)) {
                timeStampType = "datetime";
            }
        } else
            throw new UnsupportedOperationException("DBMS " + dbms + " is not supported. " +
                    "You should either provide 'driver' and 'dbUrl' properties, or specify one of supported DBMS in 'dbms' property");
    }

    protected void initDatabase(String oneModuleDir) {
        initDatabase(oneModuleDir, any -> true);
    }

    protected void initDatabase(String oneModuleDir, Function<File, Boolean> scriptFilter) {
        Project project = getProject();
        try {
            ScriptFinder scriptFinder = new ScriptFinder(storeName, dbms, dbmsVersion, dbDir, Collections.singletonList("sql"), project);

            List<File> initScripts = scriptFinder.getInitScripts(oneModuleDir)
                    .stream()
                    .filter(scriptFilter::apply)
                    .collect(Collectors.toList());

            initScripts.forEach(file -> {
                project.getLogger().warn("Executing SQL script: " + file.getAbsolutePath());
                executeSqlScript(file);
                String name = getScriptName(file);
                markScript(name, true);
            });
        } finally {
            // mark all update scripts as executed even in case of createDb failure
            ScriptFinder scriptFinder = new ScriptFinder(storeName, dbms, dbmsVersion, dbDir, Arrays.asList("sql", "groovy"), project);
            List<File> updateScripts = scriptFinder.getUpdateScripts(oneModuleDir);
            updateScripts.forEach(file -> {
                String name = getScriptName(file);
                markScript(name, true);
            });
        }
    }

    protected String getScriptName(File file) {
        try {
            String dir = dbDir.getCanonicalPath();
            String path = file.getCanonicalPath();
            return path.substring(dir.length() + 1).replace("\\", "/");
        } catch (IOException e) {
            throw new GradleException("Exception while resolve script name", e);
        }
    }

    protected boolean isEmpty(String sql) {
        String[] lines = sql.split("[\r\n]+");
        for (String line : lines) {
            line = line.trim();
            if (!line.startsWith(SQL_COMMENT_PREFIX) && !StringUtils.isBlank(line)) {
                return false;
            }
        }
        return true;
    }

    protected void executeSqlScript(File file) {
        try {
            String script = FileUtils.readFileToString(file, "UTF-8");
            script = script.replaceAll("[\r\n]+", System.getProperty("line.separator"));

            Sql sql = getSql();

            ScriptSplitter splitter = new ScriptSplitter(delimiter);
            List<String> commands = splitter.split(script);
            for (String sqlCommand : commands) {
                if (!isEmpty(sqlCommand)) {
                    getProject().getLogger().info("[CubaDbTask] executing SQL: " + sqlCommand);
                    try {
                        sql.execute(sqlCommand);
                    } catch (SQLException e) {
                        throw new GradleException("Exception when executing SQL: " + sqlCommand, e);
                    }
                }
            }
        } catch (IOException e) {
            throw new GradleException("Exception when executing sql script: " + file.getAbsolutePath(), e);
        }
    }

    protected void markScript(String name, boolean init) {
        Sql sql = getSql();
        try {
            sql.executeUpdate("insert into SYS_DB_CHANGELOG (SCRIPT_NAME, IS_INIT) values (?, ?)",
                    Arrays.asList(name, (init ? 1 : 0)));
        } catch (SQLException e) {
            throw new GradleException("Exception when mark sql script", e);
        }
    }

    protected Sql getSql() {
        if (sqlInstance == null)
            try {
                sqlInstance = Sql.newInstance(dbUrl, dbUser, dbPassword, driver);
            } catch (SQLException | ClassNotFoundException e) {
                throw new GradleException("Unable to get SQL instance", e);
            }
        return sqlInstance;
    }

    protected void closeSql() {
        if (sqlInstance != null) {
            sqlInstance.close();
            sqlInstance = null;
        }
    }

    public static class ScriptFinder {

        protected String storeName;
        protected String dbmsType;
        protected String dbmsVersion;
        protected File dbDir;
        protected List<String> extensions;
        private Project project;

        public ScriptFinder(String storeName, String dbmsType, String dbmsVersion, File dbDir, List<String> extensions, Project project) {
            this.storeName = storeName;
            this.dbmsType = dbmsType;
            this.dbmsVersion = dbmsVersion;
            this.dbDir = dbDir;
            this.extensions = extensions;
            this.project = project;
        }

        public List<String> getModuleDirs() {
            if (!dbDir.exists()) {
                return Collections.emptyList();
            }
            String[] moduleDirs = dbDir.list();
            if (moduleDirs == null) {
                return Collections.emptyList();
            }
            List<String> sortedDirs = Arrays.asList(moduleDirs);
            sortedDirs.sort(Comparator.comparingLong(this::getModuleIndex)
                    .thenComparing(String::compareTo));
            return sortedDirs;
        }

        protected Long getModuleIndex(String moduleDir) {
            int dashIndex = moduleDir.indexOf('-');
            if (dashIndex > 1) {
                try {
                    return Long.valueOf(moduleDir.substring(0, dashIndex));
                } catch (NumberFormatException e) {
                    throw new GradleException(String.format("Invalid DB scripts directory name: %s", moduleDir));
                }
            } else
                throw new GradleException(String.format("Invalid DB scripts directory name: %s", moduleDir));
        }

        // Copy of com.haulmont.cuba.core.sys.DbUpdaterEngine#getUpdateScripts
        public List<File> getUpdateScripts(String oneModuleDir) {
            if (!dbDir.exists()) {
                return Collections.emptyList();
            }
            List<String> moduleDirs = getModuleDirs();
            List<File> databaseScripts = new ArrayList<>();
            for (String moduleDirName : moduleDirs) {
                if (StringUtils.isNotBlank(oneModuleDir) && !oneModuleDir.equals(moduleDirName)) {
                    continue;
                }

                File moduleDir = new File(dbDir, moduleDirName);
                File initDir = new File(moduleDir, getUpdateDirName());
                File scriptDir = new File(initDir, dbmsType);
                if (scriptDir.exists()) {
                    String[] extensionsArr = extensions.toArray(new String[extensions.size()]);
                    List<File> list = new ArrayList<>(FileUtils.listFiles(scriptDir, extensionsArr, true));
                    Map<File, File> file2dir = new HashMap<>();
                    list.forEach(file -> file2dir.put(file, scriptDir));

                    if (StringUtils.isNotBlank(dbmsVersion)) {
                        File optScriptDir = new File(initDir, dbmsType + "-" + dbmsVersion);
                        if (optScriptDir.exists()) {
                            Map<String, File> filesMap = new HashMap<>();
                            list.forEach(file -> filesMap.put(
                                    scriptDir.toPath().relativize(file.toPath()).toString(), file));
                            List<File> optList = new ArrayList<>(FileUtils.listFiles(optScriptDir, extensionsArr, true));
                            optList.forEach(file -> file2dir.put(file, optScriptDir));

                            Map<String, File> optFilesMap = new HashMap<>();
                            optList.forEach(file -> optFilesMap.put(
                                    optScriptDir.toPath().relativize(file.toPath()).toString(), file));

                            filesMap.putAll(optFilesMap);
                            list.clear();
                            list.addAll(filesMap.values());
                        }
                    }

                    list.sort((f1, f2) -> {
                        File f1Parent = f1.getAbsoluteFile().getParentFile();
                        File f2Parent = f2.getAbsoluteFile().getParentFile();
                        if (f1Parent.equals(f2Parent)) {
                            String f1Name = FilenameUtils.getBaseName(f1.getName());
                            String f2Name = FilenameUtils.getBaseName(f2.getName());
                            return f1Name.compareTo(f2Name);
                        }
                        File dir1 = file2dir.get(f1);
                        File dir2 = file2dir.get(f2);
                        Path p1 = dir1.toPath().relativize(f1.toPath());
                        Path p2 = dir2.toPath().relativize(f2.toPath());
                        return p1.compareTo(p2);
                    });

                    databaseScripts.addAll(list);
                }
            }

            return databaseScripts;
        }

        public List<File> getInitScripts(String oneModuleDir) {
            if (!dbDir.exists()) {
                logInfo("[CubaDbTask] [getInitScripts] " + dbDir + " doesn't exist");
                return Collections.emptyList();
            }
            List<String> moduleDirs = getModuleDirs();
            List<File> files = new ArrayList<>();
            logInfo("[CubaDbTask] [getInitScripts] modules: [" + StringUtils.join(moduleDirs, ", ") + "]");
            for (String moduleDirName : moduleDirs) {
                if (StringUtils.isNotBlank(oneModuleDir) && !oneModuleDir.equals(moduleDirName)) {
                    continue;
                }
                File moduleDir = new File(dbDir, moduleDirName);
                File initDir = new File(moduleDir, getInitDirName());
                File scriptDir = new File(initDir, dbmsType);
                if (!scriptDir.exists()) {
                    logInfo("[CubaDbTask] [getInitScripts] " + scriptDir + " doesn't exist");
                    continue;
                }
                FilenameFilter filenameFilter = (dir, name) -> name.endsWith("create-db.sql");
                File[] scriptFiles = scriptDir.listFiles(filenameFilter);
                if (scriptFiles == null) {
                    scriptFiles = new File[]{};
                }
                List<File> list = new ArrayList<>(Arrays.asList(scriptFiles));
                if (StringUtils.isNotBlank(dbmsVersion)) {
                    File optScriptDir = new File(initDir, dbmsType + "-" + dbmsVersion);
                    if (optScriptDir.exists()) {
                        File[] optFiles = optScriptDir.listFiles(filenameFilter);
                        if (optFiles == null) {
                            optFiles = new File[]{};
                        }

                        Map<String, File> filesMap = new HashMap<>();
                        for (File scriptFile : scriptFiles) {
                            filesMap.put(scriptDir.toPath().relativize(scriptFile.toPath()).toString(), scriptFile);
                        }

                        Map<String, File> optFilesMap = new HashMap<>();
                        for (File optFile : optFiles) {
                            optFilesMap.put(optScriptDir.toPath().relativize(optFile.toPath()).toString(), optFile);
                        }

                        filesMap.putAll(optFilesMap);
                        list.clear();
                        list.addAll(filesMap.values());
                    }
                }

                list.sort(Comparator.comparing(File::getName));

                logInfo("[CubaDbTask] [getInitScripts] files: " + list);
                files.addAll(list);
            }
            return files;
        }

        private void logInfo(String msg) {
            if (project != null) {
                project.getLogger().info(msg);
            }
        }

        protected String getInitDirName() {
            return Stores.MAIN.equals(storeName) ? "init" : "init_" + storeName.toLowerCase(Locale.ROOT);
        }

        protected String getUpdateDirName() {
            return Stores.MAIN.equals(storeName) ? "update" : "update_" + storeName.toLowerCase(Locale.ROOT);
        }
    }

    protected Map<String, String> parseDatabaseParams(String connectionParams) {
        Map<String, String> result = new HashMap<>();
        if (connectionParams.startsWith("?")) {
            connectionParams = connectionParams.substring(1);
        }
        for (String param : connectionParams.split("[&,;]")) {
            int index = param.indexOf('=');
            if (index > 0) {
                String key = param.substring(0, index);
                String value = param.substring(index + 1);
                if (StringUtils.isNotBlank(key) && StringUtils.isNotBlank(value)) {
                    result.put(key.trim(), value.trim());
                }
            }
        }
        return result;
    }

    protected String cleanSchemaName(String schemaName) {
        return StringUtils.isNotEmpty(schemaName) ? schemaName.replace("\"", "") : schemaName;
    }
}