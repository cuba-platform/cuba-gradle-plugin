import groovy.io.FileType
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * @author hasanov
 * @version $Id$
 */
class CubaDeployment extends DefaultTask {

    private static final Pattern LIBRARY_PATTERN = Pattern.compile('((?:(?!-\\d)\\S)+)-(\\S*\\d\\S*(?:-SNAPSHOT)?)\\.jar$')
    private static final Pattern DIGITAL_PATTERN = Pattern.compile('\\d+')
    private static final String VERSION_SPLIT_PATTERN = "[\\.\\-]{1}"     // split version string by '.' and '-' chars

    def jarNames
    def appName
    def Closure doAfter
    def tomcatRootDir = project.tomcatDir
    def webcontentExclude = []
    def dbScriptsExcludes = []

    CubaDeployment() {
        setDescription('Deploys applications for local usage')
        setGroup('Development server')
    }

    @TaskAction
    def deploy() {
        project.logger.info(">>> copying from configurations.jdbc to ${tomcatRootDir}/lib")
        project.copy {
            from project.configurations.jdbc
            into "${tomcatRootDir}/lib"
        }
        project.logger.info(">>> copying shared libs from configurations.runtime")

        project.copy {
            from project.configurations.runtime
            into "${tomcatRootDir}/shared/lib"
            include { details ->
                def name = details.file.name
                return !(name.endsWith('-sources.jar')) && (jarNames.find { name.startsWith(it) } == null)
            }
        }

        project.logger.info(">>> copying app libs from configurations.runtime")
        project.copy {
            from project.configurations.runtime
            from project.libsDir
            into "${tomcatRootDir}/webapps/$appName/WEB-INF/lib"  //
            include { details ->
                def name = details.file.name
                return !(name.endsWith('.zip')) && !(name.endsWith('-tests.jar')) && !(name.endsWith('-sources.jar')) &&
                        (jarNames.find { name.startsWith(it) } != null)
            }
        }

        if (project.configurations.getAsMap().dbscripts) {
            project.logger.info(">>> copying dbscripts from ${project.buildDir}/db to ${tomcatRootDir}/webapps/$appName/WEB-INF/db")
            project.copy {
                from "${project.buildDir}/db"
                into "${tomcatRootDir}/webapps/$appName/WEB-INF/db"
                excludes = dbScriptsExcludes
            }
        }

        if (project.configurations.getAsMap().webcontent) {
            def excludePatterns = ['**/web.xml'] + webcontentExclude
            project.configurations.webcontent.files.each { dep ->
                project.logger.info(">>> copying webcontent from $dep.absolutePath to ${tomcatRootDir}/webapps/$appName")
                project.copy {
                    from project.zipTree(dep.absolutePath)
                    into "${tomcatRootDir}/webapps/$appName"
                    excludes = excludePatterns
                    includeEmptyDirs = false
                }
            }
            project.logger.info(">>> copying webcontent from ${project.buildDir}/web to ${tomcatRootDir}/webapps/$appName")
            project.copy {
                from "${project.buildDir}/web"
                into "${tomcatRootDir}/webapps/$appName"
            }
        }

        project.logger.info(">>> copying from web to ${tomcatRootDir}/webapps/$appName")
        project.copy {
            from 'web'
            into "${tomcatRootDir}/webapps/$appName"
        }

        if (doAfter) {
            project.logger.info(">>> calling doAfter")
            doAfter.call()
        }

        DependencyResolver resolver = new DependencyResolver(
                libraryRoot: new File(tomcatRootDir),
                logger: { String message -> project.logger.info(message) })
        resolver.resolveDependences("${tomcatRootDir}/shared/lib")
        resolver.resolveDependences("${tomcatRootDir}/webapps/${appName}/WEB-INF/lib")

        project.logger.info(">>> touch ${tomcatRootDir}/webapps/$appName/WEB-INF/web.xml")
        File webXml = new File("${tomcatRootDir}/webapps/$appName/WEB-INF/web.xml")
        webXml.setLastModified(new Date().getTime())
    }

    def appJars(Object... names) {
        jarNames = names
    }

    public static class LibraryDefinition {
        String name
        String version
    }

    public static class DependencyResolver {

        Closure logger
        File libraryRoot

        LibraryDefinition getLibraryDefinition(String libraryName) {
            def currentLibName
            def currentLibVersion

            Matcher m = LIBRARY_PATTERN.matcher(libraryName)
            if (m.matches()) {
                currentLibName = m.group(1)
                currentLibVersion = m.group(2)
            }
            if ((currentLibName != null) && (currentLibVersion != null)) {
                LibraryDefinition libraryDefinition = new LibraryDefinition()
                libraryDefinition.name = currentLibName
                libraryDefinition.version = currentLibVersion
                return libraryDefinition
            }
            return null
        }

        void resolveDependences(String path) {
            def libraryNames = []
            def dir = new File(path)
            dir.eachFileRecurse(FileType.FILES) { file ->
                libraryNames << file.name
            }
            // filenames to remove
            def removeSet = new HashSet<String>()
            // key - nameOfLib , value = list of matched versions
            def versionsMap = new HashMap<String, List<String>>()

            for (String libraryName in libraryNames) {
                LibraryDefinition curLibDef = getLibraryDefinition(libraryName)
                if (curLibDef != null) {
                    def currentLibName = curLibDef.name
                    def currentLibVersion = curLibDef.version
                    //fill versionsMap
                    List<String> tempList = versionsMap.get(currentLibName)
                    if (tempList != null)
                        tempList.add(currentLibVersion)
                    else {
                        tempList = new LinkedList<String>()
                        tempList.add(currentLibVersion)
                        versionsMap.put(currentLibName, tempList)
                    }
                }
            }

            def relativePath = libraryRoot != null ? path.substring(libraryRoot.absolutePath.length()) : path
            for (key in versionsMap.keySet()) {
                def versionsList = versionsMap.get(key)
                for (int i = 0; i < versionsList.size(); i++) {
                    for (int j = i + 1; j < versionsList.size(); j++) {
                        def versionToDelete = compareVersions(versionsList.get(i), versionsList.get(j))
                        if (versionToDelete != null) {
                            versionToDelete = key + "-" + versionToDelete + ".jar"
                            removeSet.add(versionToDelete)
                            def aNameLibrary = key + "-" + versionsList.get(i) + ".jar"
                            def bNameLibrary = key + "-" + versionsList.get(j) + ".jar"
                            if (logger)
                                logger(">>> library ${relativePath}/${aNameLibrary} conflicts with ${bNameLibrary}")
                        }
                    }
                }
            }

            removeSet.each { String fileName ->
                new File(path + "/" + fileName).delete()
                if (logger)
                    logger(">>> library ${relativePath }/${fileName} has been removed")
            }
        }

        def compareVersions(String aLibraryVersion, String bLibraryVersion) {
            def labelAVersionArray = aLibraryVersion.split(VERSION_SPLIT_PATTERN)
            def labelBVersionArray = bLibraryVersion.split(VERSION_SPLIT_PATTERN)

            def maxLengthOfBothArrays
            if (labelAVersionArray.size() >= labelBVersionArray.size())
                maxLengthOfBothArrays = labelAVersionArray.size()
            else {
                maxLengthOfBothArrays = labelBVersionArray.size()
                def temp = aLibraryVersion
                aLibraryVersion = bLibraryVersion
                bLibraryVersion = temp
            }

            for (def i = 0; i < maxLengthOfBothArrays; i++) {
                if (i < labelBVersionArray.size()) {
                    def matcherA = DIGITAL_PATTERN.matcher(labelAVersionArray[i])
                    def matcherB = DIGITAL_PATTERN.matcher(labelBVersionArray[i])

                    if (matcherA.matches() && !matcherB.matches()) return bLibraryVersion //labelA = number, labelB = string
                    if (!matcherA.matches() && matcherB.matches()) return aLibraryVersion  //labelA = string, labelB = number

                    // both labels are numbers or strings
                    if (labelAVersionArray[i] > labelBVersionArray[i])
                        return bLibraryVersion
                    if (labelAVersionArray[i] < labelBVersionArray[i])
                        return aLibraryVersion
                    if (i == maxLengthOfBothArrays - 1) //equals
                        return aLibraryVersion
                } else {
                    if (labelAVersionArray[i].equals("SNAPSHOT"))
                        return aLibraryVersion
                    return bLibraryVersion // labelAVersionArray.length > labelBVersionArray.length
                }
            }
        }
    }
}
