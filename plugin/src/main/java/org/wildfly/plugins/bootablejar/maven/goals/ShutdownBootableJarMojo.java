/*
 * Copyright 2020 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wildfly.plugins.bootablejar.maven.goals;

import java.io.IOException;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.jboss.as.controller.client.ModelControllerClient;
import org.wildfly.plugin.tools.server.ServerManager;

/**
 * Shutdown the bootable JAR. In order to be able to shutdown a running server,
 * the 'management' Galleon layer must have been included. If that is not the
 * case, the server would have to be killed.
 *
 * @author jfdenise
 */
@Mojo(name = "shutdown")
public class ShutdownBootableJarMojo extends AbstractServerConnection {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * Set to {@code true} if you want the shutdown goal to be skipped,
     * otherwise     * {@code false}.
     */
    @Parameter(defaultValue = "false", property = "wildfly.bootable.shutdown.skip")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().debug(String.format("Skipping " + goal() + " of %s:%s", project.getGroupId(), project.getArtifactId()));
            return;
        }
        try ( ModelControllerClient client = createClient()) {
            ServerManager.builder().client(client).standalone().shutdown(timeout);
        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }

    }

    @Override
    public String goal() {
        return "shutdown";
    }
}
