import groovy.sql.Sql
import org.apache.commons.io.FileUtils
import org.apache.commons.lang.text.StrMatcher
import org.apache.commons.lang.text.StrTokenizer
import org.gradle.api.tasks.TaskAction

/**
 * 
 * @author krivopustov
 * @version $Id$
 */
class CubaDbUpdate extends CubaDbTask {

    @TaskAction
    def updateDb() {
        init()

        List<File> files = getUpdateScripts()
        List<String> scripts = getExecutedScripts()
        files.each { File file ->
            String name = getScriptName(file)
            if (!scripts.contains(name)) {
                executeScript(file)
                markScript(name, false)
            }
        }
    }

    protected void executeScript(File file) {
        project.logger.warn("Executing script " + file.getPath())
        executeSqlScript(file)
    }

    protected void executeSqlScript(File file) {
        String script = FileUtils.readFileToString(file)
        StrTokenizer tokenizer = new StrTokenizer(
                script, StrMatcher.charSetMatcher(delimiter), StrMatcher.singleQuoteMatcher())
        Sql sql = createSql()
        while (tokenizer.hasNext()) {
            String sqlCommand = tokenizer.nextToken()
            if (sqlCommand.trim().toLowerCase().startsWith("select")) {
                sql.execute(sqlCommand)
            } else {
                sql.executeUpdate(sqlCommand)
            }
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

    protected List<String> getExecutedScripts() {
        return createSql().rows('select SCRIPT_NAME from SYS_DB_CHANGELOG').collect { row -> row.script_name }
    }

    protected List<File> getScriptsByExtension(List files, final URI scriptDirUri, final String extension) {
        return files.findAll { it.name.endsWith(".$extension") }.sort(new Comparator<File>() {
            @Override
            public int compare(File f1, File f2) {
                URI f1Uri = scriptDirUri.relativize(f1.toURI());
                URI f2Uri = scriptDirUri.relativize(f2.toURI());

                return f1Uri.getPath().compareTo(f2Uri.getPath());
            }
        })
    }
}
