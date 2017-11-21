import com.haulmont.gradle.enhance.BeanValidationMessageTransformer

/*
 * Copyright (c) 2008-2017 Haulmont.
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

class EnhanceRegExTest extends GroovyTestCase {

    def packageName = "com.haulmont.app.global"
    BeanValidationMessageTransformer transformer = new BeanValidationMessageTransformer()

    void testEnhanceRegexMatcherValid() {
        def testData = [
                "{msg://User.name.empty}" : "{msg://" + packageName + "/User.name.empty}",
                "{msg://.User.name.empty}": "{msg://" + packageName + "/.User.name.empty}",
                "{msg://User.name.empty.}": "{msg://" + packageName + "/User.name.empty.}",
        ]

        for (pair in testData) {
            assertEquals(pair.value, transformer.transformAnnotationMessage(pair.key, packageName))
        }
    }

    void testEnhanceRegexMatcherInvalid() {
        def testData = [
                "{msg://com.haulmont.app.global/User.name.empty}",
                "{msg:///com.haulmont.app.global/User.name.empty}",
                "{{msg://User.name.empty}}",
                "{{msg://User.name.empty}",
                "{msg://User.name.empty}}",
                "{msg:///User.name.empty}",
                "{msg:://User.name.empty}",
                "msg://User.name.empty}",
                "{msg://User.name.empty",
                "{msg:/User.name.empty}"
        ]

        for (value in testData) {
            assertEquals(value, transformer.transformAnnotationMessage(value, packageName))
        }
    }
}