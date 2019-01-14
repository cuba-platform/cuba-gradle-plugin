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

import com.haulmont.gradle.javaeecdi.CubaBeansXml
import com.haulmont.gradle.project.Projects
import com.haulmont.gradle.task.db.CubaHsqlStart
import com.haulmont.gradle.task.db.CubaHsqlStop
import com.haulmont.gradle.task.front.CubaInstallGeneratorsTask
import com.haulmont.gradle.task.front.CubaListGeneratorsTask
import com.haulmont.gradle.task.front.CubaNodeToolingInfoTask
import com.haulmont.gradle.task.widgetset.CubaWidgetSetBuilding
import com.haulmont.gradle.task.widgetset.CubaWidgetSetDebug
import com.haulmont.gradle.utils.BOMVersions
import com.moowork.gradle.node.NodeExtension
import com.moowork.gradle.node.NodePlugin
import groovy.util.slurpersupport.GPathResult
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.internal.artifacts.repositories.DefaultMavenArtifactRepository
import org.gradle.api.internal.artifacts.repositories.DefaultMavenLocalArtifactRepository
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.plugins.GroovyPlugin
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.plugins.ide.eclipse.EclipsePlugin
import org.gradle.plugins.ide.idea.IdeaPlugin
import org.gradle.plugins.ide.idea.model.IdeaModel
import org.jetbrains.gradle.ext.IdeaExtPlugin
import org.jetbrains.gradle.ext.Remote
import org.jetbrains.gradle.ext.RunConfigurationContainer

import java.nio.charset.StandardCharsets
import java.util.function.Consumer
import java.util.jar.JarFile
import java.util.jar.Manifest

import static org.apache.commons.io.IOUtils.closeQuietly

class CubaPlugin implements Plugin<Project> {

    public static final String APP_COMPONENT_ID_MANIFEST_ATTRIBUTE = 'App-Component-Id'
    public static final String APP_COMPONENT_VERSION_MANIFEST_ATTRIBUTE = 'App-Component-Version'

    public static final String ASSEMBLE_DB_SCRIPTS_TASK_NAME = 'assembleDbScripts'
    public static final String DEPLOY_TASK_NAME = 'deploy'
    public static final String TOMCAT_TASK_NAME = 'tomcat'
    public static final String SETUP_TOMCAT_TASK_NAME = 'setupTomcat'
    public static final String START_TOMCAT_TASK_NAME = 'start'
    public static final String DROP_TOMCAT_TASK_NAME = 'dropTomcat'
    public static final String STOP_TOMCAT_TASK_NAME = 'stop'
    public static final String DB_SCRIPTS_ARCHIVE_TASK_NAME = 'dbScriptsArchive'
    public static final String BUILD_INFO_TASK_NAME = 'buildInfo'
    public static final String ZIP_PROJECT_TASK_NAME = 'zipProject'

    public static final String BOM_CONFIGURATION_NAME = 'bom'

    @Override
    void apply(Project project) {
        project.logger.info("[CubaPlugin] applying to project $project.name")

        if (!project.rootProject.hasProperty('copyScriptRepositories')
                || Boolean.TRUE == project.rootProject.property('copyScriptRepositories')) {

            copyScriptRepositories(project)
        }

        if (project != project.rootProject && Projects.isFrontProject(project)) {
            project.extensions.extraProperties.set("appModuleType", null)
            applyToFrontProject(project)
            project.afterEvaluate { Project p ->
                doAfterEvaluateForAnyProject(p)
            }
            return
        }

        exportTaskTypes(project)

        if (project == project.rootProject) {
            if (!project.plugins.findPlugin(IdeaExtPlugin.class)) {
                new IdeaExtPlugin().apply(project)
            }
            def cubaExtension = project.extensions.create("cuba", CubaPluginExtension, project)
            applyToRootProject(project, cubaExtension)
        } else {
            project.extensions.extraProperties.set("appModuleType", null)
            project.extensions.create("entitiesEnhancing", CubaEnhancingExtension, project)
            applyToModuleProject(project)
        }

        project.afterEvaluate { Project p ->
            doAfterEvaluateForAnyProject(p)
            if (p == project.rootProject) {
                doAfterEvaluateForRootProject(p)
            } else {
                doAfterEvaluateForModuleProject(p)
            }
        }
    }

    protected void copyScriptRepositories(Project project) {
        for (repo in project.rootProject.buildscript.repositories) {
            if (repo instanceof DefaultMavenLocalArtifactRepository) {
                project.logger.info("[CubaPlugin] using repository mavenLocal()")

                project.repositories {
                    mavenLocal()
                }
            } else if (repo instanceof DefaultMavenArtifactRepository) {
                DefaultMavenArtifactRepository mavenRepo = repo

                project.logger.info("[CubaPlugin] using repository $mavenRepo.name at $mavenRepo.url")

                def mavenCredentials = mavenRepo.credentials

                project.repositories {
                    maven {
                        url mavenRepo.url
                        credentials {
                            username(mavenCredentials.username ?: '')
                            password(mavenCredentials.password ?: '')
                        }
                    }
                }
            } else {
                project.logger.info("[CubaPlugin] using repository $repo.name" + (repo.hasProperty('url') ? " at $repo.url" : ""))
                // fallback
                project.repositories.add(repo)
            }
        }
    }

    private void exportTaskTypes(Project project) {
        project.ext.CubaHsqlStop = CubaHsqlStop.class
        project.ext.CubaHsqlStart = CubaHsqlStart.class

        project.ext.CubaWidgetSetBuilding = CubaWidgetSetBuilding.class
        project.ext.CubaWidgetSetDebug = CubaWidgetSetDebug.class
    }

    private void doAfterEvaluateForAnyProject(Project project) {
        project.group = project.cuba.artifact.group
        project.version = project.cuba.artifact.version + (project.cuba.artifact.isSnapshot ? '-SNAPSHOT' : '')

        if (project.hasProperty('install')) {
            def uploadUrl = project.cuba.uploadRepository.url
            def haulmontUploadRepo = System.getenv('HAULMONT_REPOSITORY_UPLOAD_URL')
            if (uploadUrl == null && haulmontUploadRepo) {
                if (!haulmontUploadRepo.endsWith('/')) {
                    haulmontUploadRepo += '/'
                }

                uploadUrl = haulmontUploadRepo + "${project.cuba.artifact.isSnapshot ? 'snapshots' : 'releases'}"
            }

            def uploadUser = project.cuba.uploadRepository.user
            def uploadPassword = project.cuba.uploadRepository.password

            // Override upload properties if passed as project parameters, gradle upload -PuploadUrl http://someurl
            if (project.hasProperty('uploadUrl')) {
                uploadUrl = project['uploadUrl']
            }
            if (project.hasProperty('uploadUser')) {
                uploadUser = project['uploadUser']
            }
            if (project.hasProperty('uploadPassword')) {
                uploadPassword = project['uploadPassword']
            }

            if (uploadUrl != null) {
                // Check if the Maven plugin has been applied
                project.configurations {
                    deployerJars
                }
                project.dependencies {
                    deployerJars(group: 'org.apache.maven.wagon', name: 'wagon-http', version: '2.12')
                }

                project.logger.info("[CubaPlugin] upload repository: $uploadUrl ($uploadUser:$uploadPassword)")

                project.uploadArchives.configure {
                    repositories.mavenDeployer {
                        name = 'httpDeployer'
                        configuration = project.configurations.deployerJars
                        repository(url: uploadUrl) {
                            authentication(userName: uploadUser, password: uploadPassword)
                        }
                    }
                }
            }
        }
    }

    private void applyToRootProject(Project project, CubaPluginExtension cubaExtension) {
        project.configurations {
            appComponent
            uberJar
            frontServlet
        }

        enableBOMVersionResolver(project, cubaExtension.bom)

        project.task([type: CubaSetupTomcat], SETUP_TOMCAT_TASK_NAME)
        project.task([type: CubaStartTomcat], START_TOMCAT_TASK_NAME)
        project.task([type: Exec], TOMCAT_TASK_NAME)
        project.task([type: CubaStopTomcat], STOP_TOMCAT_TASK_NAME)
        project.task([type: CubaDropTomcat], DROP_TOMCAT_TASK_NAME)
        project.task([type: CubaZipProject], ZIP_PROJECT_TASK_NAME)

        importBomFromDependencies(project, cubaExtension)
    }

    private void enableBOMVersionResolver(Project project, BOMVersions bomStore) {
        project.ext.bom = bomStore
    }

    private void doAfterEvaluateForModuleProject(Project project) {
        addDependenciesFromAppComponents(project)

        defineExecutionOrderForSubProject(project)

        setupEntitiesEnhancing(project)
    }

    private void doAfterEvaluateForRootProject(Project project) {
        project.configurations {
            tomcat
        }
        project.dependencies {
            tomcat(group: 'org.apache.tomcat', name: 'tomcat', version: project.cuba.tomcat.version, ext: 'zip')
        }

        CubaSetupTomcat setupTomcat = project.tasks.getByPath(SETUP_TOMCAT_TASK_NAME) as CubaSetupTomcat
        setupTomcat.tomcatRootDir = project.cuba.tomcat.dir

        CubaStartTomcat start = project.tasks.getByPath(START_TOMCAT_TASK_NAME) as CubaStartTomcat
        start.tomcatRootDir = project.cuba.tomcat.dir

        Exec tomcat = project.tasks.getByPath(TOMCAT_TASK_NAME) as Exec
        if (System.getProperty('os.name').contains('Windows')) {
            tomcat.workingDir "${project.cuba.tomcat.dir}/bin"
            tomcat.commandLine 'cmd'
            tomcat.args '/C', 'catalina.bat', 'jpda', 'run'
        } else {
            tomcat.workingDir "${project.cuba.tomcat.dir}/bin"
            tomcat.commandLine './catalina.sh'
            tomcat.args 'jpda', 'run'
        }

        CubaStopTomcat stop = project.tasks.getByPath(STOP_TOMCAT_TASK_NAME) as CubaStopTomcat
        stop.tomcatRootDir = project.cuba.tomcat.dir

        CubaDropTomcat dropTomcat = project.tasks.getByPath(DROP_TOMCAT_TASK_NAME) as CubaDropTomcat
        dropTomcat.tomcatRootDir = project.cuba.tomcat.dir
        dropTomcat.listeningPort = '8787'

        if (project.getPlugins().hasPlugin(IdeaPlugin.class)) {
            applyIdeaExtPluginSettings(project)
        }

        if (project.getPlugins().hasPlugin(EclipsePlugin.class)) {
            applyEclipseConfigRootProject(project)
        }
    }

    private void applyEclipseConfigRootProject(Project project) {
        project.logger.info "[CubaPlugin] configuring Eclipse project"

        def cleanTask = project.tasks.getByPath(BasePlugin.CLEAN_TASK_NAME)
        cleanTask.delete = ['build/libs', 'build/tmp']
    }

    private void applyIdeaExtPluginSettings(Project project) {
        def idea = project.extensions.findByName('idea') as IdeaModel
        def settings = (idea.project as ExtensionAware).extensions.findByName('settings')

        if (project.cuba.ide.copyright) {
            def copyrightProfiles = settings.copyright.profiles as NamedDomainObjectContainer

            def cubaCopyrightProfile = copyrightProfiles.create('default')

            cubaCopyrightProfile.notice = project.cuba.ide.copyright
            cubaCopyrightProfile.keyword = 'Copyright'
            cubaCopyrightProfile.allowReplaceRegexp = ''

            copyrightProfiles.add(cubaCopyrightProfile)

            settings.copyright.useDefault = 'default'
        }

        def ideaDelegationConfig = settings.delegateActions
        def cubaDelegationConfig = project.cuba.ide.buildRunDelegation

        ideaDelegationConfig.delegateBuildRunToGradle = cubaDelegationConfig.enabled
        ideaDelegationConfig.testRunner = cubaDelegationConfig.testRunner

        def runConfigurationsContainer = settings.runConfigurations as RunConfigurationContainer

        def remoteConfiguration = runConfigurationsContainer.create('localhost:8787', Remote)
        remoteConfiguration.host = 'localhost'
        remoteConfiguration.port = 8787
        remoteConfiguration.sharedMemoryAddress = 'javadebug'

        runConfigurationsContainer.add(remoteConfiguration)
    }

    /**
     * Method defines a tasks execution order, which is necessary for parallel task execution.
     * For example if we run 'deploy' and 'start' tasks in parallel mode we want the 'start' task to
     * be executed only after 'deploy' is completed
     */
    protected void defineExecutionOrderForSubProject(Project subProject) {
        def deploymentTasks = subProject.tasks.withType(CubaDeployment.class)
        def deployNameTasks = subProject.tasks.matching({ it.name == DEPLOY_TASK_NAME})
        def dbCreationTasks = subProject.tasks.withType(CubaDbCreation.class)
        def dbUpdateTasks = subProject.tasks.withType(CubaDbUpdate.class)
        def hsqlStartTasks = subProject.tasks.withType(CubaHsqlStart.class)

        def rootProject = subProject.getRootProject()

        def startTomcatTasks = rootProject.tasks.withType(CubaStartTomcat.class)
        def setupTomcatTasks = rootProject.tasks.withType(CubaSetupTomcat.class)
        def dropTomcatTasks = rootProject.tasks.withType(CubaDropTomcat.class)
        def tomcatNameTask = rootProject.tasks.getByPath(TOMCAT_TASK_NAME)

        startTomcatTasks.all {
            it.mustRunAfter deploymentTasks
            it.mustRunAfter dbCreationTasks
            it.mustRunAfter dbUpdateTasks
            it.mustRunAfter setupTomcatTasks
            it.mustRunAfter deployNameTasks
        }

        tomcatNameTask.with {
            it.mustRunAfter deploymentTasks
            it.mustRunAfter dbCreationTasks
            it.mustRunAfter dbUpdateTasks
            it.mustRunAfter setupTomcatTasks
            it.mustRunAfter deployNameTasks
        }

        deploymentTasks.all {
            it.mustRunAfter setupTomcatTasks
        }

        setupTomcatTasks.all {
            it.mustRunAfter dropTomcatTasks
        }

        dbCreationTasks.all {
            it.mustRunAfter hsqlStartTasks
        }

        dbUpdateTasks.all {
            it.mustRunAfter hsqlStartTasks
        }
    }

    private void setupEntitiesEnhancing(Project project) {
        def javaPlugin = project.plugins.findPlugin(JavaPlugin.class)
        def groovyPlugin = project.plugins.findPlugin(GroovyPlugin.class)

        if (javaPlugin || groovyPlugin) {
            def mainEnhancing = project.entitiesEnhancing.main
            if (mainEnhancing && mainEnhancing.enabled) {
                if (javaPlugin) {
                    project.tasks.findByName('compileJava')
                            .doLast(new CubaEnhancingAction(project, 'main'))
                }
                if (groovyPlugin) {
                    project.tasks.findByName('compileGroovy')
                            .doLast(new CubaEnhancingAction(project, 'main'))
                }
            }

            def testEnhancing = project.entitiesEnhancing.test
            if (testEnhancing && testEnhancing.enabled) {
                if (javaPlugin) {
                    project.tasks.findByName('compileTestJava')
                            .doLast(new CubaEnhancingAction(project, 'test'))
                }
                if (groovyPlugin) {
                    project.tasks.findByName('compileTestGroovy')
                            .doLast(new CubaEnhancingAction(project, 'test'))
                }
            }
        }
    }

    private void importBomFromDependencies(Project project, CubaPluginExtension cubaExtension) {
        def bomComponentConf = project.rootProject.configurations.findByName(BOM_CONFIGURATION_NAME)
        if (bomComponentConf == null) {
            return
        }

        project.logger.info("[CubaPlugin] Import BOM from dependencies")

        def resolvedConfiguration = bomComponentConf.resolvedConfiguration

        def dependencies = resolvedConfiguration.firstLevelModuleDependencies
        def addedArtifacts = new HashSet<ResolvedArtifact>()

        walkJarDependencies(dependencies, addedArtifacts, { artifact ->
            def jarFile = new JarFile(artifact.file)
            try {
                def manifest = jarFile.manifest
                if (manifest == null) {
                    return
                }

                def compId = manifest.mainAttributes.getValue(APP_COMPONENT_ID_MANIFEST_ATTRIBUTE)
                def compVersion = manifest.mainAttributes.getValue(APP_COMPONENT_VERSION_MANIFEST_ATTRIBUTE)
                if (compId == null || compVersion == null) {
                    return
                }

                def bomPath = compId.replace('.', '/') + '/bom.properties'
                def bomEntry = jarFile.getEntry(bomPath)
                if (bomEntry != null) {
                    def bomInputStream = jarFile.getInputStream(bomEntry)
                    try {
                        project.logger.info("[CubaPlugin] Found BOM info in ${artifact.file.absolutePath}")

                        cubaExtension.bom.load(bomInputStream)
                    } finally {
                        closeQuietly(bomInputStream)
                    }
                }
            } finally {
                closeQuietly(jarFile)
            }
        })
    }

    private void applyToModuleProject(Project project) {
        project.sourceCompatibility = '1.8'
        project.targetCompatibility = '1.8'

        project.configurations {
            themes
            jdbc
        }

        project.sourceSets {
            main {
                java {
                    srcDir 'src'
                    compileClasspath = compileClasspath + project.configurations.jdbc
                }
                resources { srcDir 'src' }
                output.dir("$project.buildDir/classes/java/main")
            }
            test {
                java {
                    srcDir 'test'
                    compileClasspath = compileClasspath + project.configurations.jdbc
                }
                resources { srcDir 'test' }
                output.dir("$project.buildDir/classes/java/test")
            }
        }

        project.tasks.withType(JavaCompile) {
            options.compilerArgs << "-Xlint:-options"
            options.encoding = StandardCharsets.UTF_8.name()
        }

        project.tasks.withType(Javadoc) {
            options.encoding = StandardCharsets.UTF_8.name()
        }

        setJavaeeCdiNoScan(project)

        if (project.name.endsWith('-global')) {
            project.task([type: CubaBuildInfo], BUILD_INFO_TASK_NAME)
        }

        if (project.name.endsWith('-core')) {
            File dbDir = new File(project.projectDir, "db")
            def assembleDbScriptsTask = project.task([type: CubaDbScriptsAssembling], ASSEMBLE_DB_SCRIPTS_TASK_NAME)
            project.assemble.dependsOn(assembleDbScriptsTask)

            if (dbDir.exists() && dbDir.isDirectory() && dbDir.list().length > 0) {
                def dbScriptsArchiveTask = project.task(
                        [type: Zip, dependsOn: ASSEMBLE_DB_SCRIPTS_TASK_NAME], DB_SCRIPTS_ARCHIVE_TASK_NAME) {
                    from "${project.buildDir}/db"
                    include "*-$project.rootProject.name/**/*"
                    exclude '**/*.bat'
                    exclude '**/*.sh'
                    classifier = 'db'
                }

                project.artifacts {
                    archives dbScriptsArchiveTask
                }
            }
        }

        if (project.name.endsWith('-toolkit')) {
            // hibernate validator breaks GWT compilation
            project.configurations {
                compile.exclude group: 'org.hibernate.validator', module: 'hibernate-validator'

                // also remove spring and other unsupported dependencies by default
                compile.exclude group: 'org.apache.commons'
                compile.exclude group: 'org.webjars'
                compile.exclude group: 'commons-fileupload'
                compile.exclude group: 'commons-io'
                compile.exclude group: 'commons-cli'
                compile.exclude group: 'commons-codec'

                compile.exclude group: 'org.springframework'
                compile.exclude group: 'org.springframework.security'
                compile.exclude group: 'org.springframework.ldap'
                compile.exclude group: 'org.eclipse.persistence'
                compile.exclude group: 'org.codehaus.groovy'
                compile.exclude group: 'org.apache.ant'
            }
        }
    }

    private void setJavaeeCdiNoScan(Project project) {
        if (!project.hasProperty('noBeansXml')) {
            // create META-INF/beans.xml
            def beansXmlTask = project.task([type: CubaBeansXml], CubaBeansXml.NAME)

            project.jar.dependsOn beansXmlTask

            project.jar {
                // add META-INF/beans.xml
                from(beansXmlTask)
            }
        }
    }

    private void applyToFrontProject(Project project) {
        project.plugins.apply(NodePlugin)
        def nodeExtension = project.extensions.getByType(NodeExtension)
        nodeExtension.version = '10.14.1'
        nodeExtension.download = true

        project.task([type: CubaNodeToolingInfoTask], CubaNodeToolingInfoTask.NAME)
        project.task([type: CubaInstallGeneratorsTask], CubaInstallGeneratorsTask.NAME)
        project.task([type: CubaListGeneratorsTask], CubaListGeneratorsTask.NAME)

        project.getTasks().withType(JavaCompile) {
            enabled = false
        }
        project.getTasks().withType(ProcessResources) {
            enabled = false
        }
    }

    private void addDependenciesFromAppComponents(Project project) {
        def moduleName = Projects.getModuleNameByProject(project)

        project.logger.info("[CubaPlugin] Setting up dependencies for module $moduleName")

        def appComponentConf = project.rootProject.configurations.appComponent
        if (appComponentConf.dependencies.size() > 0) {
            addDependenciesFromAppComponentsConfiguration(project, moduleName)
        } else {
            addDependenciesFromAppComponentsClassPath(project, moduleName)
        }
    }

    private void addDependenciesFromAppComponentsClassPath(Project project, String moduleName) {
        project.logger.info("[CubaPlugin] Import app-components to ${project.name} from classpath")

        def jarNames = new HashSet<String>()
        def skippedDeps = new ArrayList<SkippedDep>()

        def manifests = getClass().getClassLoader().getResources("META-INF/MANIFEST.MF")
        while (manifests.hasMoreElements()) {
            def manifest = new Manifest(manifests.nextElement().openStream())

            def compId = manifest.mainAttributes.getValue(APP_COMPONENT_ID_MANIFEST_ATTRIBUTE)
            def compVersion = manifest.mainAttributes.getValue(APP_COMPONENT_VERSION_MANIFEST_ATTRIBUTE)

            if (compId && compVersion) {
                def compDescrPath = compId.replace('.', '/') + '/app-component.xml'
                def compGroup = compId

                def url = CubaPlugin.class.getResource(compDescrPath)
                if (url) {
                    project.logger.info("[CubaPlugin] Found app-component info in $url")
                    def xml = new XmlSlurper().parseText(url.openStream().getText('UTF-8'))

                    applyAppComponentXml(xml, moduleName, compGroup, compVersion, project, skippedDeps, compId, jarNames)
                }
            }
        }

        if (!jarNames.isEmpty()) {
            project.logger.info("[CubaPlugin] Inherited app JAR names for deploy task: $jarNames")
            project.ext.inheritedDeployJarNames = jarNames
        }

        if (!skippedDeps.isEmpty()) {
            skippedDeps.sort()
            def last = skippedDeps.last()
            addDependencyToConfiguration(project, last.dep, last.conf)
        }
    }

    private void addDependenciesFromAppComponentsConfiguration(Project project, String moduleName) {
        project.logger.info("[CubaPlugin] Import app-components to ${project.name} from appComponent configuration")

        def addedArtifacts = new HashSet<ResolvedArtifact>()
        def jarNames = new HashSet<String>()
        def skippedDeps = new ArrayList<SkippedDep>()

        def appComponentConf = project.rootProject.configurations.appComponent
        def resolvedConfiguration = appComponentConf.resolvedConfiguration
        def dependencies = resolvedConfiguration.firstLevelModuleDependencies

        project.ext.resolvedAppComponents = []

        walkJarDependencies(dependencies, addedArtifacts, { artifact ->
            def jarFile = new JarFile(artifact.file)
            try {
                def manifest = jarFile.manifest
                if (manifest == null) {
                    return
                }

                def compId = manifest.mainAttributes.getValue(APP_COMPONENT_ID_MANIFEST_ATTRIBUTE)
                def compVersion = manifest.mainAttributes.getValue(APP_COMPONENT_VERSION_MANIFEST_ATTRIBUTE)
                if (compId == null || compVersion == null) {
                    return
                }

                project.logger.info("[CubaPlugin] Inspect app-component dependency ${artifact.name}")

                def compDescriptorPath = compId.replace('.', '/') + '/app-component.xml'
                def compGroup = compId

                def descriptorEntry = jarFile.getEntry(compDescriptorPath)
                if (descriptorEntry != null) {
                    project.ext.resolvedAppComponents.add(compId + ':' + compVersion)

                    def descriptorInputStream = jarFile.getInputStream(descriptorEntry)
                    try {
                        project.logger.info("[CubaPlugin] Found app-component info in ${artifact.file.absolutePath}")
                        def xml = new XmlSlurper().parseText(descriptorInputStream.getText(StandardCharsets.UTF_8.name()))

                        applyAppComponentXml(xml, moduleName, compGroup, compVersion, project, skippedDeps, compId, jarNames)
                    } finally {
                        closeQuietly(descriptorInputStream)
                    }
                }
            } finally {
                closeQuietly(jarFile)
            }
        })

        if (!jarNames.isEmpty()) {
            project.logger.info("[CubaPlugin] Inherited app JAR names for deploy task: $jarNames")
            project.ext.inheritedDeployJarNames = jarNames
        }

        if (!skippedDeps.isEmpty()) {
            skippedDeps.sort()
            def last = skippedDeps.last()
            addDependencyToConfiguration(project, last.dep, last.conf)
        }
    }

    private void walkJarDependencies(Set<ResolvedDependency> dependencies,
                                     Set<ResolvedArtifact> passedArtifacts,
                                     Consumer<ResolvedArtifact> artifactAction) {
        for (dependency in dependencies) {
            walkJarDependencies(dependency.children, passedArtifacts, artifactAction)

            for (artifact in dependency.moduleArtifacts) {
                if (passedArtifacts.contains(artifact)) {
                    continue
                }

                passedArtifacts.add(artifact)

                if (artifact.file != null && artifact.file.name.endsWith('.jar')) {
                    artifactAction.accept(artifact)
                }
            }
        }
    }

    private void applyAppComponentXml(GPathResult xml, String moduleName, String compGroup, String compVersion,
                                      Project project, List<SkippedDep> skippedDeps, String compId, Set<String> jarNames) {
        GPathResult module = (GPathResult) xml.module.find { it.@name == moduleName }
        if (module.size() > 0) {
            module.artifact.each { art ->
                if (Boolean.valueOf(art.@library.toString()))
                    return

                String dep = "$compGroup:${art.@name}:$compVersion"
                if (art.@classifier != "" || art.@ext != "") {
                    dep += ':'
                    if (art.@classifier != "") {
                        dep += art.@classifier
                    }
                    if (art.@ext != "") {
                        dep += "@${art.@ext}"
                    }
                }
                if (art.@skipIfExists != "") {
                    if (!project.rootProject.allprojects.find { art.@skipIfExists == Projects.getModuleNameByProject(it) }) {
                        skippedDeps.add(new SkippedDep(new AppComponent(compId, xml), dep, art.@configuration.text()))
                    }
                } else {
                    addDependencyToConfiguration(project, dep, art.@configuration.text())
                }
            }

            addJarNamesFromModule(jarNames, xml, module)
        }

        // Adding appJars from modules that work in all blocks. For example, not all components have
        // portal module, so they don't export appJars for a portal module in the project. But
        // project's global module depends from components' global modules and hence they should be added
        // to appJars.
        def globalModules = xml.module.findAll { it.@blocks.text().contains('*') }
        globalModules.each { GPathResult child ->
            addJarNamesFromModule(jarNames, xml, child)
        }
    }

    private void addDependencyToConfiguration(Project project, String dependency, String conf) {
        project.logger.info("[CubaPlugin] Adding dependency '$dependency' to configuration '$conf'")
        switch (conf) {
            case 'dbscripts':
                project.configurations {
                    dbscripts
                }
                project.dependencies {
                    dbscripts(dependency)
                }
                break
            case 'webcontent':
                project.configurations {
                    webcontent
                }
                project.dependencies {
                    webcontent(dependency)
                }
                break
            case 'themes':
                project.configurations {
                    themes
                }
                project.dependencies {
                    themes(dependency)
                    compileOnly(dependency)
                }
                break
            case '':
                project.dependencies {
                    compile(dependency)
                }
                break

            default:
                project.dependencies.add(conf, dependency)
                break
        }
    }

    private void addJarNamesFromModule(Set jarNames, GPathResult xml, GPathResult module) {
        module.artifact.each { art ->
            if (art.@appJar == "true") {
                jarNames.add(art.@name.text())
            }
            module.@dependsOn.text().tokenize(' ,').each { depName ->
                GPathResult depModule = (GPathResult) xml.module.find { it.@name == depName }
                addJarNamesFromModule(jarNames, xml, depModule)
            }
        }
    }

    private static File getEntityClassesDir(Project project) {
        SourceSet mainSourceSet = project.sourceSets.main

        return mainSourceSet.java.outputDir
    }

    private static class AppComponent {
        String id
        List<String> dependencies = []

        AppComponent(String id, GPathResult xml) {
            this.id = id
            if (xml.@dependsOn) {
                dependencies = xml.@dependsOn.text().tokenize(' ,')
            }
        }

        boolean dependsOn(AppComponent other) {
            return dependencies.contains(other.id)
        }
    }

    private static class SkippedDep implements Comparable<SkippedDep> {
        AppComponent appComponent
        String dep
        String conf

        SkippedDep(AppComponent appComponent, String dep, String conf) {
            this.appComponent = appComponent
            this.dep = dep
            this.conf = conf
        }

        @Override
        int compareTo(SkippedDep other) {
            if (this.appComponent.dependsOn(other.appComponent))
                return 1
            if (other.appComponent.dependsOn(this.appComponent))
                return -1
            return 0
        }
    }
}