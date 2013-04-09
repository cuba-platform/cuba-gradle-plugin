import org.gradle.api.DefaultTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFiles
import org.gradle.api.tasks.TaskAction

/**
 * 
 * @author krivopustov
 * @version $Id$
 */
class CubaEnhancing extends DefaultTask {

    def persistenceXml

    CubaEnhancing() {
        setDescription('Enhances persistent classes')
    }

    @InputFiles
    def List getInputFiles() {
        getClassNames().collect { name ->
            new File("$project.buildDir/classes/main/${name.replace('.', '/')}.class")
        }
    }

    @OutputFiles
    def List getOutputFiles() {
        getClassNames().collect { name ->
            new File("$project.buildDir/enhanced-classes/main/${name.replace('.', '/')}.class")
        }
    }

    private List getClassNames() {
        if (persistenceXml) {
            File f = new File(persistenceXml)
            if (f.exists()) {
                def persistence = new XmlParser().parse(f)
                def pu = persistence.'persistence-unit'[0]
                pu.'class'.collect {
                    it.name().toString()
                }
            }
        }
    }

    @TaskAction
    def enhance() {
        if (persistenceXml) {
            File f = new File(persistenceXml)
            if (f.exists()) {
                def persistence = new XmlParser().parse(f)
                def pu = persistence.'persistence-unit'[0]
                def properties = pu.properties[0]
                if (!properties)
                    properties = pu.appendNode('properties')
                def prop = properties.find { it.@name == 'openjpa.DetachState' }
                if (!prop)
                    properties.appendNode('property', [name: 'openjpa.DetachState', value: 'loaded(DetachedStateField=true, DetachedStateManager=true)'])

                File tmpDir = new File(project.buildDir, 'tmp')
                tmpDir.mkdirs()
                File tmpFile = new File(tmpDir, 'persistence.xml')
                new XmlNodePrinter(new PrintWriter(new FileWriter(tmpFile))).print(persistence)

                project.javaexec {
                    main = 'org.apache.openjpa.enhance.PCEnhancer'
                    classpath(project.sourceSets.main.compileClasspath, project.sourceSets.main.output.classesDir)
                    args('-properties', tmpFile, '-d', "$project.buildDir/enhanced-classes/main")
                }
            }
        }
    }
}
