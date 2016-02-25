import org.apache.tools.ant.DirectoryScanner
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

/**
 *
 * @author gorelov
 */
class CubaZipProject extends DefaultTask {

    def excludeFromZip = []
    def includeToZip = []
    def zipDir = "${project.rootDir}"

    @TaskAction
    def zipProject() {

        def tmpDir = "${project.buildDir}/zip"

        def includeToZip = ['.gitignore']
        includeToZip += this.includeToZip

        def excludeFromZip = [
                'build',
                '.iml',
                '.ipr',
                '.iws'
        ]
        excludeFromZip += this.excludeFromZip

        String zipFilePath = "${zipDir}/${project.name}.zip"

        project.logger.info("[CubaZipProject] Deleting old archive")
        // to exclude recursive packing
        project.delete(zipFilePath)

        // Due to GRADLE-1883
        DirectoryScanner.removeDefaultExclude("**/.gitignore")

        project.logger.info("[CubaZipProject] Packing files from: ${project.rootDir}")
        project.copy {
            from '.'
            into tmpDir
            exclude { details ->
                String name = details.file.name
                if (isFileMatched(name, includeToZip)) return false
                // eclipse project files, gradle, git, idea (directory based), Mac OS files
                if (name.startsWith(".")) return true
                return isFileMatched(name, excludeFromZip)
            }
        }

        ant.zip(destfile: zipFilePath, basedir: tmpDir)

        DirectoryScanner.resetDefaultExcludes()

        project.delete(tmpDir)
    }

    protected static boolean isFileMatched(String name, def rules) {
        for (String rule : rules) {
            if (rule.startsWith(".")) {     // extension
                if (name.endsWith(rule)) {
                    return true
                }
            } else {                        // file name
                if (name == rule) {
                    return true
                }
            }
        }
        return false
    }
}
