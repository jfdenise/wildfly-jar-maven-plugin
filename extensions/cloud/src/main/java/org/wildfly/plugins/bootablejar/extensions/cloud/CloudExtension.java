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
package org.wildfly.plugins.bootablejar.extensions.cloud;

import java.io.FileOutputStream;
import org.wildfly.plugins.bootablejar.extensions.cloud.generators.MPConfigMapCLIGenerator;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.wildfly.core.jar.boot.Main;
import org.wildfly.core.jar.boot.RuntimeExtension;
import org.wildfly.plugins.bootablejar.extensions.cloud.generators.StatefulSetCLIGenerator;
import org.wildfly.plugins.bootablejar.extensions.cloud.generators.TracingCLIGenerator;

/**
 *
 * @author jdenise
 */
public class CloudExtension implements RuntimeExtension {

    private static final String OPENSHIFT_RESOURCE = "/openshift.properties";
    private static final String KUBERNETES_RESOURCE = "/kubernetes.properties";
    private static final String OPENSHIFT_HOST_NAME_ENV = "HOSTNAME";
    private static final String JBOSS_NODE_NAME_PROPERTY = "jboss.node.name";

    private static final List<CLIGenerator> GENERATORS = new ArrayList<>();

    static {
        GENERATORS.add(new MPConfigMapCLIGenerator());
        GENERATORS.add(new StatefulSetCLIGenerator());
        GENERATORS.add(new TracingCLIGenerator());
    }
    @Override
    public void boot(List<String> args, Path installDir) throws Exception {
       handleCloud(args, installDir);
    }

    private static void handleCloud(List<String> args, Path installDir) throws Exception {
        Properties props = new Properties();
        // For now no difference.
        boolean isOpenshift;
        try (InputStream wf = Main.class.getResourceAsStream(OPENSHIFT_RESOURCE)) {
            if(wf != null) {
                isOpenshift = true;
                props.load(wf);
            } else {
                try (InputStream wf2 = Main.class.getResourceAsStream(KUBERNETES_RESOURCE)) {
                    isOpenshift = false;
                    props.load(wf2);
                }
            }
        }

        boolean hasJbossNodeName = false;
        Set<String> overridingProps = new HashSet<>();
        for (String arg : args) {
            if (arg.startsWith("-D" + JBOSS_NODE_NAME_PROPERTY + "=")) {
                hasJbossNodeName = true;
            } else {
                if (arg.startsWith("-D")) {
                    String prop = null;
                    int eq = arg.indexOf("=");
                    if (eq == -1) {
                        prop = arg.substring(2);
                    } else {
                        prop = arg.substring(2, eq);
                    }
                    overridingProps.add(prop);
                }
            }
        }
        // Set openshift specific properties not redefined
        for (String p : props.stringPropertyNames()) {
            if (!overridingProps.contains(p)) {
                args.add("-D" + p + "=" + props.getProperty(p));
            }
        }

        if (!hasJbossNodeName) {
            String value = System.getProperty(JBOSS_NODE_NAME_PROPERTY);
            if (value == null) {
                String hostname = System.getenv(OPENSHIFT_HOST_NAME_ENV);
                if (hostname != null) {
                    // This is a constraint that breaks the server at startup.
                    if (hostname.length() > 23) {
                        StringBuilder builder = new StringBuilder();
                        char[] chars = hostname.toCharArray();
                        for (int i = 1; i <= 23; i++) {
                            char c = chars[hostname.length() - i];
                            builder.insert(0, c);
                        }
                        hostname = builder.toString();
                    }
                    args.add("-Djboss.node.name=" + hostname);
                }
            }
        }

        Path scriptFile = generateCliScript(installDir);
        if (scriptFile != null) {
            Path markerDir = installDir.resolve("cli-boot-marker-dir");
            Files.createDirectory(markerDir);

            Path propertiesFile = installDir.resolve("cli-script-property.properties");
            Path errorFile = installDir.resolve("cli-script-error.cli");
            Path warnFile = installDir.resolve("cli-script-warning.cli");
            Properties cliProperties = new Properties();
            cliProperties.setProperty("error_file", errorFile.toString());
            cliProperties.setProperty("warning_file", warnFile.toString());
            try (FileOutputStream s = new FileOutputStream(propertiesFile.toFile())) {
                cliProperties.store(s, "CLI properties");
            }

            Path outputFile = installDir.resolve("cli-script-output.cli");

            args.add("--start-mode=admin-only");
            args.add("-Dorg.wildfly.internal.cli.boot.hook.script=" + scriptFile);
            args.add("-Dorg.wildfly.internal.cli.boot.hook.marker.dir=" + markerDir);
            args.add("-Dorg.wildfly.internal.cli.boot.hook.script.properties=" + propertiesFile);
            args.add("-Dorg.wildfly.internal.cli.boot.hook.script.output.file=" + outputFile);
            args.add("-Dorg.wildfly.internal.cli.boot.hook.script.error.file=" + errorFile);
            args.add("-Dorg.wildfly.internal.cli.boot.hook.script.warn.file=" + warnFile);
        }
    }

    private static Path generateCliScript(Path installDir) throws Exception {
        List<String> cmds = new ArrayList<>();
        for (CLIGenerator generator : GENERATORS) {
            generator.generate(cmds);
        }
        if (cmds.isEmpty()) {
            return null;
        } else {
            Path scriptFile = installDir.resolve("boot-config.cli");
            Files.deleteIfExists(scriptFile);
            StringBuilder builder = new StringBuilder();
            for(String cmd : cmds) {
                builder.append(cmd).append("\n");
            }
            Files.write(scriptFile, builder.toString().getBytes());
            return scriptFile;
        }
    }
}
