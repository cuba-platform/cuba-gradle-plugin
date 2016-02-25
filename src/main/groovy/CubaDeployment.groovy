/*
 * Copyright (c) 2008-2013 Haulmont. All rights reserved.
 * Use is subject to license terms, see http://www.cuba-platform.com/license for details.
 */

import groovy.io.FileType
import org.apache.commons.lang.StringUtils
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

import java.util.regex.Pattern
import java.util.zip.ZipFile

/**
 * @author hasanov
 */
class CubaDeployment extends DefaultTask {

    private static final Pattern LIBRARY_PATTERN = Pattern.compile('((?:(?!-\\d)\\S)+)-(\\S*\\d\\S*(?:-SNAPSHOT)?)\\.jar$')
    private static final Pattern LIBRARY_SNAPSHOT_PATTERN = Pattern.compile('((?:(?!-\\d)\\S)+)-(?:SNAPSHOT)\\.jar$')
    private static final Pattern LIBRARY_WITHOUT_VERSION_PATTERN = Pattern.compile('((?:(?!-\\d)\\S)+)\\.jar$')
    private static final Pattern DIGITAL_PATTERN = Pattern.compile('\\d+')
    private static final String VERSION_SPLIT_PATTERN = "[\\.\\-]"     // split version string by '.' and '-' chars

    def jarNames
    def appName
    def Closure doAfter
    def tomcatRootDir = new File(project.cuba.tomcat.dir).canonicalPath
    def webcontentExclude = []
    def dbScriptsExcludes = []

    def sharedlibResolve = true

    CubaDeployment() {
        setDescription('Deploys applications for local usage')
        setGroup('Deployment')
    }

    @TaskAction
    def deploy() {
        if (!tomcatRootDir)
            tomcatRootDir = new File(project.cuba.tomcat.dir).canonicalPath
        project.logger.info("[CubaDeployment] copying from configurations.jdbc to ${tomcatRootDir}/lib")
        project.copy {
            from project.configurations.jdbc {
                exclude {f ->
                    if (new File("${tomcatRootDir}/lib".toString(), f.file.name).exists()) {
                        return true
                    }

                    f.file.absolutePath.startsWith(project.file("${tomcatRootDir}/lib/").absolutePath)
                }
            }
            into "${tomcatRootDir}/lib"
        }
        project.logger.info("[CubaDeployment] copying shared libs from configurations.runtime")

        File sharedLibDir = new File("${tomcatRootDir}/shared/lib")
        List<String> copiedToSharedLib = []
        project.copy {
            from project.configurations.runtime
            into "${tomcatRootDir}/shared/lib"
            include { details ->
                String name = details.file.name

                if (new File(sharedLibDir, name).exists() && !name.contains("-SNAPSHOT")) {
                    return false
                }

                if (!(name.endsWith('-sources.jar')) && !name.endsWith('-tests.jar') && (jarNames.find { name.startsWith(it) } == null)) {
                    copiedToSharedLib.add(name)

                    return true
                }

                return false
            }
        }

        File appLibDir = new File("${tomcatRootDir}/webapps/$appName/WEB-INF/lib")
        List<String> copiedToAppLib = []
        project.logger.info("[CubaDeployment] copying app libs from configurations.runtime")
        project.copy {
            from project.configurations.runtime
            from project.libsDir
            into "${tomcatRootDir}/webapps/$appName/WEB-INF/lib"  //
            include { details ->
                String name = details.file.name
                if (new File(appLibDir, name).exists() && !name.contains("-SNAPSHOT")) {
                    return false
                }

                if (!(name.endsWith('.zip')) && !(name.endsWith('-tests.jar')) && !(name.endsWith('-sources.jar')) &&
                        (jarNames.find { name.startsWith(it) } != null)) {
                    copiedToAppLib.add(name)

                    return true
                }

                return false
            }
        }

        if (project.configurations.getAsMap().dbscripts) {
            project.logger.info("[CubaDeployment] copying dbscripts from ${project.buildDir}/db to ${tomcatRootDir}/webapps/$appName/WEB-INF/db")
            project.copy {
                from "${project.buildDir}/db"
                into "${tomcatRootDir}/webapps/$appName/WEB-INF/db"
                excludes = dbScriptsExcludes
            }
        }

        if (project.configurations.getAsMap().webcontent) {
            def excludePatterns = ['**/web.xml'] + webcontentExclude
            project.configurations.webcontent.files.each { dep ->
                project.logger.info("[CubaDeployment] copying webcontent from $dep.absolutePath to ${tomcatRootDir}/webapps/$appName")
                project.copy {
                    from project.zipTree(dep.absolutePath)
                    into "${tomcatRootDir}/webapps/$appName"
                    excludes = excludePatterns
                    includeEmptyDirs = false
                }
            }
        }

        project.logger.info("[CubaDeployment] unpack web resources to ${tomcatRootDir}/webapps/$appName")

        List<File> appJarFiles = []
        project.file("${tomcatRootDir}/webapps/$appName/WEB-INF/lib").eachFile { f ->
            def name = f.name
            if (!(name.endsWith('.zip')) && !(name.endsWith('-tests.jar')) && !(name.endsWith('-sources.jar')) &&
                    (jarNames.find { jarName -> name.startsWith(jarName) } != null)) {
                appJarFiles.add(f)
            }
        }

        def jarWebDir = project.file("$project.buildDir/jar-web-unzip")
        if (jarWebDir.exists()) {
            jarWebDir.deleteDir()
        }

        for (File appJar : appJarFiles) {
            boolean webContentJar = false

            ZipFile zf = new ZipFile(appJar);
            try {
                def webEntry = zf.getEntry("web/")
                if (webEntry != null) {
                    webContentJar = true
                }
            } finally {
                zf.close()
            }

            if (webContentJar) {
                // unpack only web directory
                project.copy {
                    from project.zipTree(appJar)
                    into jarWebDir
                    include 'web/**'
                }
            }
        }

        if (jarWebDir.exists()) {
            project.copy {
                from project.file("$jarWebDir/web")
                into project.file("${tomcatRootDir}/webapps/$appName")
            }

            jarWebDir.deleteDir()
        }

        project.logger.info("[CubaDeployment] copying deployment descriptors from web to ${tomcatRootDir}/webapps/$appName")
        project.copy {
            from 'web'
            into "${tomcatRootDir}/webapps/$appName"
            include 'WEB-INF/**', 'META-INF/**'
        }

        if (sharedlibResolve) {
            DependencyResolver resolver = new DependencyResolver(
                    libraryRoot: new File("${tomcatRootDir}"),
                    logger: { String message -> project.logger.info(message) })
            if (!copiedToSharedLib.isEmpty()) {
                resolver.resolveDependencies(sharedLibDir, copiedToSharedLib)
            }
            resolver.resolveDependencies(appLibDir, copiedToAppLib)
        }

        if (doAfter) {
            project.logger.info("[CubaDeployment] calling doAfter")
            doAfter.call()
        }

        def webXml = new File("${tomcatRootDir}/webapps/$appName/WEB-INF/web.xml")
        if (webXml.exists()) {
            if (project.ext.has('webResourcesTs')) {
                project.logger.info("[CubaDeployment] update web resources timestamp")

                // detect version automatically
                def buildTimeStamp = project.ext.get('webResourcesTs')

                def webXmlText = webXml.text
                if (StringUtils.contains(webXmlText, '${webResourcesTs}')) {
                    webXmlText = webXmlText.replace('${webResourcesTs}', buildTimeStamp.toString())
                }
                webXml.write(webXmlText)
            }

            project.logger.info("[CubaDeployment] touch ${tomcatRootDir}/webapps/$appName/WEB-INF/web.xml")
            webXml.setLastModified(System.currentTimeMillis())
        }
    }

    def appJars(Object... names) {
        jarNames = names
    }

    public static class LibraryDefinition {
        String name
        String version
    }

    public static LibraryDefinition getLibraryDefinition(String libraryName) {
        def m = LIBRARY_PATTERN.matcher(libraryName)
        if (m.matches()) {
            def currentLibName = m.group(1)
            def currentLibVersion = m.group(2)

            if (currentLibName != null && currentLibVersion != null) {
                return new LibraryDefinition(name: currentLibName, version: currentLibVersion)
            }
        }

        def sm = LIBRARY_SNAPSHOT_PATTERN.matcher(libraryName)
        if (sm.matches()) {
            def currentLibName = sm.group(1)

            if (currentLibName != null) {
                return new LibraryDefinition(name: currentLibName, version: 'SNAPSHOT')
            }
        }

        def nvm = LIBRARY_WITHOUT_VERSION_PATTERN.matcher(libraryName)
        if (nvm.matches()) {
            def currentLibName = nvm.group(1)

            if (currentLibName != null) {
                return new LibraryDefinition(name: currentLibName)
            }
        }

        return null
    }

    /**
     * @param aLibraryVersion
     * @param bLibraryVersion
     * @return lowest version from aLibraryVersion and bLibraryVersion
     */
    public static String getLowestVersion(String aLibraryVersion, String bLibraryVersion) {
        String[] labelAVersionArray = aLibraryVersion.split(VERSION_SPLIT_PATTERN)
        String[] labelBVersionArray = bLibraryVersion.split(VERSION_SPLIT_PATTERN)

        def maxLengthOfBothArrays
        if (labelAVersionArray.size() >= labelBVersionArray.size()) {
            maxLengthOfBothArrays = labelAVersionArray.size()
        } else {
            maxLengthOfBothArrays = labelBVersionArray.size()

            def temp = aLibraryVersion
            aLibraryVersion = bLibraryVersion
            bLibraryVersion = temp

            def tempArr = labelAVersionArray
            labelAVersionArray = labelBVersionArray
            labelBVersionArray = tempArr
        }

        for (def i = 0; i < maxLengthOfBothArrays; i++) {
            if (i < labelBVersionArray.size()) {
                String aVersionPart = labelAVersionArray[i]
                String bVersionPart = labelBVersionArray[i]

                if (aVersionPart == "SNAPSHOT") {
                    return bLibraryVersion
                } else if (bVersionPart == "SNAPSHOT") {
                    return aLibraryVersion
                }

                def matcherA = DIGITAL_PATTERN.matcher(aVersionPart)
                def matcherB = DIGITAL_PATTERN.matcher(bVersionPart)

                if (matcherA.matches() && !matcherB.matches()) return bLibraryVersion //labelA = number, labelB = string
                if (!matcherA.matches() && matcherB.matches()) return aLibraryVersion
                //labelA = string, labelB = number

                if (matcherA.matches()) {
                    // convert parts to integer
                    int libAVersionNumber = Integer.parseInt(aVersionPart)
                    int libBVersionNumber = Integer.parseInt(bVersionPart)

                    if (libAVersionNumber > libBVersionNumber) {
                        return bLibraryVersion
                    }

                    if (libAVersionNumber < libBVersionNumber) {
                        return aLibraryVersion
                    }
                } else {
                    // both labels are numbers or strings
                    if (aVersionPart > bVersionPart)
                        return bLibraryVersion
                    if (aVersionPart < bVersionPart)
                        return aLibraryVersion
                }

                if (i == maxLengthOfBothArrays - 1) { //equals
                    return aLibraryVersion
                }
            } else {
                return bLibraryVersion // labelAVersionArray.length > labelBVersionArray.length
            }
        }

        return aLibraryVersion
    }

    public static class DependencyResolver {

        Closure logger
        File libraryRoot

        void resolveDependencies(File libDir, List<String> copied) {
            def libraryNames = []
            def copiedLibNames = copied.collect { getLibraryDefinition(it).name }

            libDir.eachFile(FileType.FILES) { file ->
                libraryNames << file.name
            }
            libraryNames = copiedLibNames.collectNested { String copiedLibName ->
                libraryNames.findAll { it.startsWith(copiedLibName) }
            }.flatten().toSet().toList()

            if (logger) {
                libraryNames.each {
                    logger(">> check library " + it)
                }
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

            def path = libDir.absolutePath
            def relativePath = libraryRoot != null ? path.substring(libraryRoot.absolutePath.length()) : path
            for (key in versionsMap.keySet()) {
                def versionsList = versionsMap.get(key)
                for (int i = 0; i < versionsList.size(); i++) {
                    for (int j = i + 1; j < versionsList.size(); j++) {
                        def versionToDelete = getLowestVersion(versionsList.get(i), versionsList.get(j))
                        if (versionToDelete != null) {
                            versionToDelete = key + "-" + versionToDelete + ".jar"
                            removeSet.add(versionToDelete)
                            def aNameLibrary = key + "-" + versionsList.get(i) + ".jar"
                            def bNameLibrary = key + "-" + versionsList.get(j) + ".jar"
                            if (logger)
                                logger("[CubaDeployment] library ${relativePath}/${aNameLibrary} conflicts with ${bNameLibrary}")
                        }
                    }
                }
            }

            removeSet.each { String fileName ->
                new File(path, fileName).delete()
                if (logger)
                    logger("[CubaDeployment] remove library ${relativePath }/${fileName}")
            }
        }
    }
}