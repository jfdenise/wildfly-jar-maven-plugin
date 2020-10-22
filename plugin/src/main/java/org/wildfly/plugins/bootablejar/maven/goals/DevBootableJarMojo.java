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

import java.nio.file.Path;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.wildfly.core.launcher.Launcher;
import org.wildfly.plugins.bootablejar.maven.common.Utils;

/**
 * Build and start a bootable JAR for dev mode. In order to be able to shutdown
 * the server started in 'dev' mode, the 'management' Galleon layer must have
 * been included. If that is not the case, the server would have to be killed.
 *
 * @author jfdenise
 */
@Mojo(name = "dev", requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, defaultPhase = LifecyclePhase.COMPILE)
public final class DevBootableJarMojo extends AbstractDevBootableJarMojo {

    @Override
    protected void doExecute() throws MojoExecutionException, MojoFailureException {
        try {
            final Path stdout = Utils.createTemporaryFile(project, "wildfly-jar-dev-stdout.log");
            getLog().info(String.format("The stdout and stderr for the process are being logged to %s", stdout));
            Launcher.of(buildCommandBuilder())
                    .setRedirectErrorStream(true)
                    .redirectOutput(stdout)
                    .launch();
        } catch (Exception e) {
            throw new MojoExecutionException(e.getLocalizedMessage(), e);
        }
    }
}
