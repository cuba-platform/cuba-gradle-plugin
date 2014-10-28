import org.apache.commons.io.FileUtils

import java.nio.file.Paths

/**
 *
 * @author krivopustov
 * @version $Id$
 */
class DbTaskTest extends GroovyTestCase {

    private File dbmsDir;

    private List<File> mssqlInitFiles = new ArrayList<>();
    private List<File> mssql2012InitFiles = new ArrayList<>();
    private List<File> mssqlUpdateFiles = new ArrayList<>();
    private List<File> mssql2012UpdateFiles = new ArrayList<>();

    @Override
    void setUp() {
        super.setUp();

        dbmsDir = Paths.get("test-run", "db").toFile()
        if (dbmsDir.exists()) {
            FileUtils.deleteDirectory(dbmsDir);
        }
        dbmsDir.mkdirs();

        File dir;
        File file;

        // Init scripts

        dir = new File(dbmsDir, "10-cuba/init/mssql");
        dir.mkdirs();
        file = new File(dir, "create-db.sql");
        file.createNewFile();
        mssqlInitFiles.add(file);

        dir = new File(dbmsDir, "10-cuba/init/mssql-2012");
        dir.mkdirs();
        file = new File(dir, "create-db.sql");
        file.createNewFile();
        mssql2012InitFiles.add(file);

        dir = new File(dbmsDir, "50-app/init/mssql");
        dir.mkdirs();
        file = new File(dir, "10.create-db.sql");
        file.createNewFile();
        mssqlInitFiles.add(file);
        mssql2012InitFiles.add(file);
        file = new File(dir, "20.create-db.sql");
        file.createNewFile();
        mssqlInitFiles.add(file);
        mssql2012InitFiles.add(file);
        file = new File(dir, "30.create-db.sql");
        file.createNewFile();
        mssqlInitFiles.add(file);

        dir = new File(dbmsDir, "50-app/init/mssql-2012");
        dir.mkdirs();
        file = new File(dir, "30.create-db.sql");
        file.createNewFile();
        mssql2012InitFiles.add(file);

        file = new File(dir, "40.create-db.sql");
        file.createNewFile();
        mssql2012InitFiles.add(file);

        // Update scripts

        dir = new File(dbmsDir, "10-cuba/update/mssql/13");
        dir.mkdirs();
        file = new File(dir, "cuba-update-1.sql");
        file.createNewFile();
        mssqlUpdateFiles.add(file);
        mssql2012UpdateFiles.add(file);

        dir = new File(dbmsDir, "10-cuba/update/mssql/14");
        dir.mkdirs();
        file = new File(dir, "cuba-update-2.sql");
        file.createNewFile();
        mssqlUpdateFiles.add(file);
        mssql2012UpdateFiles.add(file);

        dir = new File(dbmsDir, "50-app/update/mssql/14");
        dir.mkdirs();
        file = new File(dir, "app-update-0.sql");
        file.createNewFile();
        mssqlUpdateFiles.add(file);
        mssql2012UpdateFiles.add(file);
        file = new File(dir, "app-update-1.sql");
        file.createNewFile();
        mssqlUpdateFiles.add(file);

        dir = new File(dbmsDir, "50-app/update/mssql-2012/14");
        dir.mkdirs();
        file = new File(dir, "app-update-1.sql");
        file.createNewFile();
        mssql2012UpdateFiles.add(file);
        file = new File(dir, "app-update-2.sql");
        file.createNewFile();
        mssql2012UpdateFiles.add(file);
    }

    public void testGetInitScripts() throws Exception {
        CubaDbTask.ScriptFinder scriptFinder = new CubaDbTask.ScriptFinder('mssql', null, dbmsDir, [])
        List<File> scripts = scriptFinder.getInitScripts();
        assertEquals(mssqlInitFiles, scripts);

        scriptFinder = new CubaDbTask.ScriptFinder('mssql', '2012', dbmsDir, [])
        scripts = scriptFinder.getInitScripts();
        assertEquals(mssql2012InitFiles, scripts);
    }

    public void testGetUpdateScripts() throws Exception {
        CubaDbTask.ScriptFinder scriptFinder = new CubaDbTask.ScriptFinder('mssql', null, dbmsDir, ['sql'])
        List<File> scripts = scriptFinder.getUpdateScripts()
        assertEquals(mssqlUpdateFiles, scripts);

        scriptFinder = new CubaDbTask.ScriptFinder('mssql', '2012', dbmsDir, ['sql'])
        scripts = scriptFinder.getUpdateScripts()
        assertEquals(mssql2012UpdateFiles, scripts);
    }
}
