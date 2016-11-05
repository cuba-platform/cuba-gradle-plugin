import com.moowork.gradle.node.task.NodeTask
import groovy.json.JsonSlurper
import org.gradle.api.GradleException

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
 */

class CubaPolymerScaffoldingTask extends NodeTask {

    static final NAME_PREFIX = "scaffoldPolymer_"
    static final OPTIONS_PROPERTY = "scaffoldingOpts"

    String command;

    CubaPolymerScaffoldingTask() {
        setDescription('' +
                'Invokes node command line code generator for CUBA Polymer client. ' +
                'Sample usage: ' + NAME_PREFIX + 'el  -P' + OPTIONS_PROPERTY + '=\'["--elementName=test-element"]\'')
        setGroup('Polymer Client')
        setScript(project.file('node_modules/generator-cuba/generators/cli.js'))
    }

    @Override
    void exec() {
        def args = []
        if (command) {
            args.add(command);
        }
        if (project.hasProperty(OPTIONS_PROPERTY)){
            def options = new JsonSlurper().parseText(project[OPTIONS_PROPERTY])
            if (!Iterable.isInstance(options)) {
                throw new GradleException('Options should be passed as an array')
            }
            args.addAll(options)
        }

        setArgs(args)
        super.exec()
    }

}
