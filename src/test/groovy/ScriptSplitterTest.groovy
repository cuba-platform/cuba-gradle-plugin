/**
 *
 * @author Konstantin Krivopustov
 * @version $Id$
 */
class ScriptSplitterTest extends GroovyTestCase {

    void testSplit() {
        def splitter = new CubaDbTask.ScriptSplitter('^')
        def commands = splitter.split('''
alter table FOO add BAR varchar(36)^--go
alter table FOO add BOO varchar(255)^

alter table ^^ABC REF_COMPANY(ID)^''')
        assertEquals('''
alter table FOO add BAR varchar(36)''', commands[0])

        assertEquals('''--go
alter table FOO add BOO varchar(255)''', commands[1])

        assertEquals('''

alter table ^ABC REF_COMPANY(ID)''', commands[2])
    }

    void testSplit2() {
        def splitter = new CubaDbTask.ScriptSplitter(';')
        def commands = splitter.split('''
alter table FOO add BAR varchar(36);--go
alter table FOO add BOO varchar(255);

alter table ;;ABC REF_COMPANY(ID);''')
        assertEquals('''
alter table FOO add BAR varchar(36)''', commands[0])

        assertEquals('''--go
alter table FOO add BOO varchar(255)''', commands[1])

        assertEquals('''

alter table ;ABC REF_COMPANY(ID)''', commands[2])
    }

    void testSplit3() {
        def splitter = new CubaDbTask.ScriptSplitter('--go')
        def commands = splitter.split('''
alter table FOO add BAR varchar(36)--go--bla
alter table FOO add BOO varchar(255)
--go

alter table --go--goABC REF_COMPANY(ID)
--go''')
        assertEquals('''
alter table FOO add BAR varchar(36)''', commands[0])

        assertEquals('''--bla
alter table FOO add BOO varchar(255)
''', commands[1])

        assertEquals('''

alter table --goABC REF_COMPANY(ID)
''', commands[2])
    }

}
