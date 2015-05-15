/*
 * Copyright (c) 2008-2013 Haulmont. All rights reserved.
 * Use is subject to license terms, see http://www.cuba-platform.com/license for details.
 */

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

/**
 *
 * @author krivopustov
 * @version $Id$
 */
class CubaClearMessagesCache extends DefaultTask {

    def appName = 'app'

    CubaClearMessagesCache() {
        setDescription('Clears messages cache')
        setGroup('Deployment')
    }

    @TaskAction
    protected void copyTriggerFile() {
        def fileName = "${project.tomcatDir}/temp/$appName/triggers/cuba_Messages.clearCache"
        File file = new File(fileName)
        if (!file.exists()) {
            project.logger.info ">>> creating $fileName"
            file.getParentFile().mkdirs()
            file.createNewFile()
        }
    }
}