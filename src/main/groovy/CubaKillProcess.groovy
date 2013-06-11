import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

/**
 *
 * @author krivopustov
 * @version $Id$
 */
class CubaKillProcess extends DefaultTask {

    def port

    @TaskAction
    def void kill() {
        if (!port) {
            logger.error("Port value is needed")
            return
        }
        if ('linux'.equalsIgnoreCase(System.getProperty('os.name'))) {
            ant.exec(executable: 'sh', spawn: false) {
                arg(value: "-c")
                arg(value: "kill `lsof -i :$port | tail -n +2 | sed -e 's,[ \\t][ \\t]*, ,g' | cut -f2 -d' '`")
            }
        } else {
            logger.error("Killing by port is not supported on ${System.getProperty('os.name')} operating system")
        }
    }
}
