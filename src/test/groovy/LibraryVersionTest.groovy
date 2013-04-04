/**
 * @author hasanov
 * @version $Id$
 */
class LibraryVersionTest extends GroovyTestCase {

    void testLibraryVersionMatcher() {
        def testData = [
                "charts-global-4.0-SNAPSHOT.jar": ["charts-global", "4.0-SNAPSHOT"],
                "asm-3.2-RELEASE.jar": ["asm", "3.2-RELEASE"],
                "tika-core-0.9.jar": ["tika-core", "0.9"],
                "spring-context-support-3.1.3.RELEASE.jar": ["spring-context-support", "3.1.3.RELEASE"],
                "slf4j-api-1.5.6.jar": ["slf4j-api", "1.5.6"],
                "core-renderer-1.1.3-SNAPSHOT.jar": ["core-renderer", "1.1.3-SNAPSHOT"],
                "javassist-3.4.GA.jar": ["javassist", "3.4.GA"],
                "antlr-runtime-3.2.haulmont.jar": ["antlr-runtime", "3.2.haulmont"],
                "core-renderer-R8-SNAPSHOT.jar": ["core-renderer", "R8-SNAPSHOT"],
                "core-renderer-1.1.3.RELEASE.jar": ["core-renderer", "1.1.3.RELEASE"],
                "core-lib-renderer2-3.1.haulmont-SNAPSHOT.jar": ["core-lib-renderer2", "3.1.haulmont-SNAPSHOT"],
                "xpp3_min-1.1.4c.jar": ["xpp3_min", "1.1.4c"],
                "some-lab.jar": [null, null]
        ]

        def resolver = new CubaDeployment.DependencyResolver()
        for (pair in testData) {
            def libraryName = pair.key
            def libraryDefinition = resolver.getLibraryDefinition(libraryName)
            if (libraryDefinition != null) {
                assertEquals(pair.getValue().get(0), libraryDefinition.name)
                assertEquals(pair.getValue().get(1), libraryDefinition.version)
            } else {
                assertNull(pair.getValue().get(0))
                assertNull(pair.getValue().get(1))
            }
        }
    }
}