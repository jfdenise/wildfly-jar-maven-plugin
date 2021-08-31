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
package org.wildfly.plugins.bootablejar.maven.cli.bootlogging;

import java.nio.file.Path;
import java.util.Objects;
import org.jboss.as.controller.client.ModelControllerClient;
import org.wildfly.plugins.bootablejar.maven.cli.CLIWrapper;
import org.wildfly.plugins.bootablejar.maven.goals.BootLoggingConfiguration;

/**
 * A CLI executor, resolving CLI classes from the provided Classloader. We can't
 * have cli/embedded/jboss modules in plugin classpath, it causes issue because
 * we are sharing the same jboss module classes between execution run inside the
 * same JVM.
 *
 * CLI dependencies are retrieved from provisioned server artifacts list and
 * resolved using maven. In addition jboss-modules.jar located in the
 * provisioned server is added.
 *
 * @author jdenise
 */
public class CLIWrapperBootLogging extends CLIWrapper {

    private final BootLoggingConfiguration bootLoggingConfiguration;

    public CLIWrapperBootLogging(Path jbossHome, boolean resolveExpression, ClassLoader loader) throws Exception {
        this(jbossHome, resolveExpression, loader, null);
    }

    public CLIWrapperBootLogging(Path jbossHome, boolean resolveExpression, ClassLoader loader, BootLoggingConfiguration bootLoggingConfiguration) throws Exception {
        super(jbossHome, resolveExpression, loader);
        this.bootLoggingConfiguration = bootLoggingConfiguration;
    }

    public void generateBootLoggingConfig() throws Exception {
        Objects.requireNonNull(bootLoggingConfiguration);
        Exception toThrow = null;
        try {
            // Start the embedded server
            handle("embed-server --jboss-home=" + getJBossHome() + " --std-out=discard");
            // Get the client used to execute the management operations
            final ModelControllerClient client = getModelControllerClient();
            // Update the bootable logging config
            final Path configDir = getJBossHome().resolve("standalone").resolve("configuration");
            bootLoggingConfiguration.generate(configDir, client);
        } catch (Exception e) {
            toThrow = e;
        } finally {
            try {
                // Always stop the embedded server
                handle("stop-embedded-server");
            } catch (Exception e) {
                if (toThrow != null) {
                    e.addSuppressed(toThrow);
                }
                toThrow = e;
            }
        }
        // Check if an error has been thrown and throw it.
        if (toThrow != null) {
            throw toThrow;
        }
    }
}
