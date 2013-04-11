/**
 * 
 * @author krivopustov
 * @version $Id$
 */
class CubaEnhancingTest extends GroovyTestCase {

    void testGetClassNames() {
        def names = CubaEnhancing.getClassNames('src/test/resources/cuba-persistence.xml')
        assertEquals(3, names.size())
        assertNotNull(names.find { it == 'com.haulmont.cuba.core.entity.BaseUuidEntity' })
    }
}
