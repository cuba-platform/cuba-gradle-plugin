/*
 * Copyright (c) 2011 Haulmont Technology Ltd. All Rights Reserved.
 * Haulmont Technology proprietary and confidential.
 * Use is subject to license terms.
 */

import groovy.sql.Sql
import org.apache.commons.io.FileUtils
import org.apache.commons.lang.StringUtils
import org.apache.commons.lang.text.StrMatcher
import org.apache.commons.lang.text.StrTokenizer
import org.apache.commons.logging.LogFactory
import org.gradle.api.tasks.TaskAction

/**
 * @author krivopustov
 * @version $Id$
 */
class CubaDbUpdate extends CubaDbTask {

    CubaDbUpdate() {
        setGroup('Database')
    }

    @TaskAction
    def updateDb() {
        init()

        try {
            List<File> files = getUpdateScripts()
            List<String> scripts = getExecutedScripts()
            files.each { File file ->
                String name = getScriptName(file)
                if (!scripts.contains(name)) {
                    executeScript(file)
                    markScript(name, false)
                }
            }
        } finally {
            closeSql()
        }
    }

    protected void executeScript(File file) {
        project.logger.warn("Executing script " + file.getPath())
        if (file.name.endsWith('.sql'))
            executeSqlScript(file)
        else if (file.name.endsWith('.groovy'))
            executeGroovyScript(file);
    }

    protected void executeSqlScript(File file) {
        String script = FileUtils.readFileToString(file)
        StrTokenizer tokenizer = new StrTokenizer(
                script, StrMatcher.charSetMatcher(delimiter), StrMatcher.singleQuoteMatcher())
        Sql sql = getSql()
        while (tokenizer.hasNext()) {
            String sqlCommand = tokenizer.nextToken().trim()
            if (!StringUtils.isEmpty(sqlCommand)) {
                if (sqlCommand.toLowerCase().startsWith("select")) {
                    sql.execute(sqlCommand)
                } else {
                    sql.executeUpdate(sqlCommand)
                }
            }
        }
    }

    protected void executeGroovyScript(File file) {
        try {
            Class.forName(driver);
        } catch (ClassNotFoundException ignored) {
            project.logger.error("Unable to load driver class " + driver);
            return;
        }

        try {
            String scriptRoot = file.getParentFile().getAbsolutePath();
            ClassLoader classLoader = getClass().getClassLoader();
            GroovyScriptEngine scriptEngine = new GroovyScriptEngine(scriptRoot, classLoader);

            Binding bind = new Binding();
            bind.setProperty("ds", new SingleConnectionDataSource(dbUrl, dbUser, dbPassword));
            bind.setProperty("log", LogFactory.getLog(file.getName()));
            bind.setProperty("postUpdate", new PostUpdateScripts(file));

            scriptEngine.run(file.getAbsolutePath(), bind);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
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

                    List updateScripts = files
                            .findAll { File f -> f.name.endsWith('.sql') | f.name.endsWith('.groovy') }
                            .sort { File f1, File f2 ->
                        URI f1Uri = scriptDirUri.relativize(f1.toURI());
                        URI f2Uri = scriptDirUri.relativize(f2.toURI());
                        f1Uri.getPath().compareTo(f2Uri.getPath());
                    }

                    databaseScripts.addAll(updateScripts);
                }
            }
        }
        return databaseScripts;
    }

    protected List<String> getExecutedScripts() {
        return getSql().rows('select SCRIPT_NAME from SYS_DB_CHANGELOG').collect { row -> row.script_name }
    }

    public class PostUpdateScripts {

        protected File file;

        PostUpdateScripts(File file) {
            this.file = file
        }

        public void add(Closure closure) {
            project.logger.warn("Added post update action from file '${file.absolutePath}' will be ignored");
        }
    }
}