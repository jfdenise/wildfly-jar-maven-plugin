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
import java.util.List;
import org.wildfly.plugins.bootablejar.maven.cli.CLIWrapper;
import org.wildfly.plugins.bootablejar.maven.cli.LocalCLIExecutor;
import org.wildfly.plugins.bootablejar.maven.common.PluginContext;
import org.wildfly.plugins.bootablejar.maven.goals.BootLoggingConfiguration;

/**
 * A CLI executor, resolving CLI classes from an URL Classloader. We can't have
 * cli/embedded/jboss modules in plugin classpath, it causes issue because we
 * are sharing the same jboss module classes between execution run inside the
 * same JVM.
 *
 * CLI dependencies are retrieved from provisioned server artifacts list and
 * resolved using maven. In addition jboss-modules.jar located in the
 * provisioned server * is added.
 *
 * @author jdenise
 */
public class LocalCLIExecutorBootLogging extends LocalCLIExecutor implements CLIExecutorBootLogging {

    private final BootLoggingConfiguration bootLoggingConfiguration;
    public LocalCLIExecutorBootLogging(PluginContext ctx, List<Path> cliArtifacts,
            boolean resolveExpression, BootLoggingConfiguration bootLoggingConfiguration) throws Exception {
        super(ctx, cliArtifacts, resolveExpression);
        this.bootLoggingConfiguration = bootLoggingConfiguration;
    }

    @Override
    protected CLIWrapper buildCliWrapper() throws Exception {
        return new CLIWrapperBootLogging(ctx.getJBossHome(), resolveExpression, cliCl, bootLoggingConfiguration);
    }

    @Override
    public void generateBootLoggingConfig() throws Exception {
       ( (CLIWrapperBootLogging) getCLIWrapper()).generateBootLoggingConfig();
    }
}
