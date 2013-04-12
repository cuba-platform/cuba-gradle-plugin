/*
 * Copyright (c) 2012 Haulmont Technology Ltd. All Rights Reserved.
 * Haulmont Technology proprietary and confidential.
 * Use is subject to license terms.
 */

/**
 * @author artamonov
 * @version $Id$
 */
class CubaModularWidgetSetBuilding extends CubaWidgetSetTask {

    CubaModularWidgetSetBuilding() {
        setDescription('Builds GWT widgetset in project-all')
        setGroup('Web resources')
        // set default task dependsOn
        setDependsOn(project.getTasksByName('compileJava', false))
    }
}