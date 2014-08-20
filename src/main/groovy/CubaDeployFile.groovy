import org.apache.commons.lang.StringUtils
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.tasks.TaskAction

/**
 *
 * @author degtyarjov
 * @version $Id$
 */
class CubaDeployFile extends DefaultTask {

    def appName = "app";

    @TaskAction
    def deployFile() {
        if (!project.hasProperty("fileToDeploy")) return;
        if (!project.hasProperty("moduleToDeploy")) return;

        String file = project.property("fileToDeploy")
        String module = project.property("moduleToDeploy")
        String confDir = resolveConfDir(module)

        Project moduleProject;
        if (project.name.equalsIgnoreCase(module)) {
            moduleProject = project
        } else {
            moduleProject = project.allprojects.find { def subProject ->
                subProject.name.equalsIgnoreCase(module)
            }
        }

        File src = moduleProject.file("src")
        File target = new File("$project.tomcatDir/conf/$confDir")

        project.logger.info "Deploy file >>> src: $src.absolutePath"
        project.logger.info "Deploy file >>> include: $file"
        project.logger.info "Deploy file >>> target: $target.absolutePath"
        project.logger.info "Deploy file >>> Application name: $appName"

        project.copy {
            from src
            include file
            into target
        }
    }

    //todo resolve it more clever
    private String resolveConfDir(String module) {
        def postfix = StringUtils.substringAfterLast(module, "-")
        if (postfix == "core") {
            return appName + "-" + postfix
        } else {
            return appName;
        }
    }
}
