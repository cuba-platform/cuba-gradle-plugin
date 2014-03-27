/*
 * Copyright (c) 2008-2013 Haulmont. All rights reserved.
 * Use is subject to license terms, see http://www.cuba-platform.com/license for details.
 */

import org.gradle.api.tasks.TaskAction

/**
 * @author artamonov
 * @version $Id$
 */
class CubaDbScript extends CubaDbTask {

    def scripts = []

    @TaskAction
    def runScripts() {
        init()

        for (def script in scripts) {
            File scriptFile
            if (script instanceof File) {
                scriptFile = script
            } else if (script instanceof String) {
                scriptFile = project.file(script)
            } else
                throw new IllegalArgumentException("Unable to run script $script")

            project.logger.warn("Executing SQL script: ${scriptFile.absolutePath}")

            executeSqlScript(scriptFile)
        }
    }

    def script(def scriptObject) {
        scripts.add(scriptObject)
    }
}