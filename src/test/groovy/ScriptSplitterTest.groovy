import com.haulmont.gradle.task.db.ScriptSplitter

/*
 * Copyright (c) 2008-2016 Haulmont.
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
 *
 */

/**
 *
 */
class ScriptSplitterTest extends GroovyTestCase {

    void testSplit() {
        def splitter = new ScriptSplitter('^')
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
        def splitter = new ScriptSplitter(';')
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
        def splitter = new ScriptSplitter('--go')
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
