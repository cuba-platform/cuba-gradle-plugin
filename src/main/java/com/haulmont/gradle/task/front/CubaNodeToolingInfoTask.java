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

package com.haulmont.gradle.task.front;

import com.moowork.gradle.node.NodeExtension;
import com.moowork.gradle.node.npm.NpmSetupTask;
import com.moowork.gradle.node.variant.Variant;
import groovy.json.JsonBuilder;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class CubaNodeToolingInfoTask extends DefaultTask {

    public static final String NAME = "nodeToolingInfo";

    protected static final String BUILD_DIR  = "tooling";
    protected static final String INFO_FILE_NAME = "info.json";

    public CubaNodeToolingInfoTask() {
        setDescription("Provides info about node/npm tooling used in project");
        setGroup("Node");
        setDependsOn(this.getProject().getTasks().withType(NpmSetupTask.class));
    }

    @TaskAction
    public void generateInfo() {
        NodeExtension ext = NodeExtension.get( this.getProject() );
        Variant variant = ext.getVariant();
        String nodeExec = variant.getNodeExec();
        String npmExec = variant.getNpmExec();
        Info info = new Info(nodeExec, npmExec);
        byte[] jsonBytes = new JsonBuilder(info).toPrettyString().getBytes(StandardCharsets.UTF_8);
        try {
            Files.write(new File(getOutputDirectory(), INFO_FILE_NAME).toPath(), jsonBytes);
        } catch (IOException e) {
            throw new GradleException("Unable to write tooling info", e);
        }
    }

    @OutputDirectory
    protected File getOutputDirectory() {
        return new File(getProject().getBuildDir(), BUILD_DIR);
    }

    @InputFile
    protected File getInputFile() {
        // build.gradle since node/npm versions specified there
        return new File(getProject().getRootProject().getProjectDir(), "build.gradle");
    }

    protected static class Info {
        protected String nodeExec;
        protected String npmExec;

        public Info(String nodeExec, String npmExec) {
            this.nodeExec = nodeExec;
            this.npmExec = npmExec;
        }

        public String getNodeExec() {
            return nodeExec;
        }

        public void setNodeExec(String nodeExec) {
            this.nodeExec = nodeExec;
        }

        public String getNpmExec() {
            return npmExec;
        }

        public void setNpmExec(String npmExec) {
            this.npmExec = npmExec;
        }
    }
}