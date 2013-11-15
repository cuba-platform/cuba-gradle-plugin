/*
 * Copyright (c) 2008-2013 Haulmont. All rights reserved.
 * Use is subject to license terms, see http://www.cuba-platform.com/license for details.
 */

import groovy.sql.Sql
import org.apache.commons.io.FileUtils
import org.apache.commons.lang.StringUtils
import org.apache.commons.lang.text.StrMatcher
import org.apache.commons.lang.text.StrTokenizer
import org.gradle.api.tasks.TaskAction

/**
 * @author krivopustov
 * @version $Id$
 */
class CubaDbUpdate extends CubaDbTask {

    private static final String SQL_COMMENT_PREFIX = "--"

    CubaDbUpdate() {
        setGroup('Database')
    }

    @TaskAction
    def updateDb() {
        init()

        try {
            List<File> files = getUpdateScripts()
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

    protected void executeScript(File file) {
        project.logger.warn("Executing script " + file.getPath())
        if (file.name.endsWith('.sql'))
            executeSqlScript(file)
    }

    protected void executeSqlScript(File file) {
        String script = FileUtils.readFileToString(file)
        StrTokenizer tokenizer = new StrTokenizer(
                script, StrMatcher.charSetMatcher(delimiter), StrMatcher.singleQuoteMatcher())
        Sql sql = getSql()
        while (tokenizer.hasNext()) {
            String sqlCommand = tokenizer.nextToken().trim()
            if (!StringUtils.isEmpty(sqlCommand)) {
                if (isLikelySelect(sqlCommand)) {
                    sql.execute(sqlCommand)
                } else {
                    sql.executeUpdate(sqlCommand)
                }
            }
        }
    }

    // If first keyword is not SELECT then its probably not a select query.
    protected boolean isLikelySelect(String sql) {
        String[] lines = sql.split("\\r?\\n")
        for (String line : lines) {
            line = line.trim()
            if (!line.startsWith(SQL_COMMENT_PREFIX) && !StringUtils.isBlank(line)) {
                return line.toLowerCase().startsWith("select")
            }
        }
        return false
    }

    @Override
    protected List<File> getUpdateScripts() {
        List<File> databaseScripts = new ArrayList<>()

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
                            .findAll { File f -> f.name.endsWith('.sql') }
                            .sort { File f1, File f2 ->
                        URI f1Uri = scriptDirUri.relativize(f1.toURI())
                        URI f2Uri = scriptDirUri.relativize(f2.toURI())
                        f1Uri.getPath().compareTo(f2Uri.getPath())
                    }

                    databaseScripts.addAll(updateScripts)
                }
            }
        }
        return databaseScripts
    }

    protected List<String> getExecutedScripts() {
        return getSql().rows('select SCRIPT_NAME from SYS_DB_CHANGELOG').collect { row -> row.script_name }
    }
}