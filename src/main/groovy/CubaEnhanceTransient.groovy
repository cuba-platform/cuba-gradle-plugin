import org.gradle.api.DefaultTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFiles
import org.gradle.api.tasks.TaskAction

/**
 * 
 * @author krivopustov
 * @version $Id$
 */
class CubaEnhanceTransient extends DefaultTask {

    def classes = []

    CubaEnhanceTransient() {
        setDescription('Enhances transient entities')
    }

    @InputFiles
    def List getInputFiles() {
        classes.collect { name ->
            new File("$project.buildDir/classes/main/${name.replace('.', '/')}.class")
        }
    }

    @OutputFiles
    def List getOutputFiles() {
        classes.collect { name ->
            new File("$project.buildDir/enhanced-classes/main/${name.replace('.', '/')}.class")
        }
    }

    @TaskAction
    def enhance() {
        project.logger.info(">>> enhancing classes: $classes")
        project.javaexec {
            main = 'CubaTransientEnhancer'
            classpath(project.sourceSets.main.compileClasspath, project.sourceSets.main.output.classesDir)
            args(classes + "-o $project.buildDir/enhanced-classes/main")
        }
    }
}
