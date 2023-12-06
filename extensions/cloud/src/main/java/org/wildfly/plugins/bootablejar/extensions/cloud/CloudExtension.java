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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.wildfly.core.jar.boot.Main;
import org.wildfly.core.jar.boot.RuntimeExtension;

/**
 *
 * @author jdenise
 */
public class CloudExtension implements RuntimeExtension {

    private static final String OPENSHIFT_RESOURCE = "/openshift.properties";
    private static final String KUBERNETES_RESOURCE = "/kubernetes.properties";
    private static final String OPENSHIFT_HOST_NAME_ENV = "HOSTNAME";
    private static final String JBOSS_NODE_NAME_PROPERTY = "jboss.node.name";
    private static final String JBOSS_TX_NODE_ID_PROPERTY = "jboss.tx.node.id";
    private static final Path JBOSS_CONTAINER_DIR = Paths.get("/opt/jboss/container");
    private static final Path JBOSS_CONTAINER_BOOTABLE_DIR = JBOSS_CONTAINER_DIR.resolve("wildfly-bootable-jar");
    private static final Path INSTALL_DIR_FILE = JBOSS_CONTAINER_BOOTABLE_DIR.resolve("install-dir");

    @Override
    public void boot(List<String> args, Path installDir) throws Exception {
        String hostname = System.getenv(OPENSHIFT_HOST_NAME_ENV);
        doBoot(args, installDir, hostname);
    }

    void doBoot(List<String> args, Path installDir, String hostname) throws Exception {
        handleCloud(args, installDir, hostname);
        // Required by Operator
        if (Files.exists(JBOSS_CONTAINER_DIR)) {
            try {
                if (!Files.exists(JBOSS_CONTAINER_BOOTABLE_DIR)) {
                    Files.createDirectory(JBOSS_CONTAINER_BOOTABLE_DIR);
                }
                Files.write(INSTALL_DIR_FILE, installDir.toString().getBytes(), StandardOpenOption.CREATE);
            } catch (IOException ex) {
                System.err.println("Warning, can't generate " + INSTALL_DIR_FILE + ". " + ex);
            }
        }
    }

    private static void handleCloud(List<String> args, Path installDir, String hostname) throws Exception {
        Properties props = new Properties();
        // For now no difference.
        boolean isOpenshift;
        try (InputStream wf = Main.class.getResourceAsStream(OPENSHIFT_RESOURCE)) {
            if (wf != null) {
                isOpenshift = true;
                props.load(wf);
            } else {
                try (InputStream wf2 = Main.class.getResourceAsStream(KUBERNETES_RESOURCE)) {
                    isOpenshift = false;
                    props.load(wf2);
                }
            }
        }

        String nodeName = null;
        Set<String> overridingProps = new HashSet<>();
        for (String arg : args) {
            if (arg.startsWith("-D" + JBOSS_NODE_NAME_PROPERTY + "=")) {
                int eq = arg.indexOf("=");
                nodeName = arg.substring(eq + 1, arg.length());
            } else {
                if (arg.startsWith("-D")) {
                    String prop;
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
        // Detect that we have a set of launch scripts to call.
        handleCliScript(args, installDir);
        if (nodeName == null) {
            boolean setNodeName = true;
            nodeName = System.getProperty(JBOSS_NODE_NAME_PROPERTY);
            if (nodeName == null) {
                nodeName = hostname;
            } else {
                setNodeName = false;
            }
            if (nodeName != null) {
                String txId = trunkTxIdValue(nodeName);
                if (setNodeName) {
                    args.add("-D" + JBOSS_NODE_NAME_PROPERTY + "=" + nodeName);
                }
                args.add("-D" + JBOSS_TX_NODE_ID_PROPERTY + "=" + txId);
            }
        } else {
            String txId = trunkTxIdValue(nodeName);
            args.add("-D" + JBOSS_TX_NODE_ID_PROPERTY + "=" + txId);
        }
    }

    private static void handleCliScript(List<String> args, Path jbossHome) throws Exception {
        Path script = jbossHome.resolve("bin/launch/generate_cli_script.sh");
        if (!Files.exists(script)) {
            return;
        }
        Path outputScript = jbossHome.resolve("bin/launch/generated_cli_script.cli");
        List<String> cmds = new ArrayList<>();
        cmds.add("sh");
        cmds.add(script.toAbsolutePath().toString());
        cmds.add(outputScript.toAbsolutePath().toString());
        ProcessBuilder builder = new ProcessBuilder(cmds);
        builder.environment().
                put("JBOSS_HOME", jbossHome.toAbsolutePath().toString());
        Process p = builder.inheritIO().start();
        int ret = p.waitFor();
        if (ret != 0) {
            throw new Exception("Error generating CLI script");
        }
        // XXX TODO detect that a custom CLI script is set and set CLI_LAUNCH_SCRIPT
        if (Files.exists(outputScript)) {
            Path errFile = Files.createTempFile("err-boot-script", null);
            Files.delete(errFile);
            Path warnFile = Files.createTempFile("warn-boot-script", null);
            Files.delete(warnFile);
            Path propsFile =  Files.createTempFile("props-boot-script", null);
            StringBuilder propsBuilder = new StringBuilder();
            propsBuilder.append("error_file="+errFile.toAbsolutePath().toString()+"\n");
            propsBuilder.append("warning_file="+warnFile.toAbsolutePath().toString()+"\n");
            Files.write(propsFile, propsBuilder.toString().getBytes());
            args.add("-Dorg.wildfly.internal.cli.boot.hook.script.properties=" + propsFile.toAbsolutePath().toString());
            args.add("-Dorg.wildfly.internal.cli.boot.hook.script.error.file=" + errFile.toAbsolutePath().toString());
            args.add("-Dorg.wildfly.internal.cli.boot.hook.script.warn.file=" + warnFile.toAbsolutePath().toString());
            args.add("--cli-script=" + outputScript.toAbsolutePath().toString());
        }
    }

    private static String trunkTxIdValue(String value) {
        if (value.length() > 23) {
            String originalValue = value;
            StringBuilder builder = new StringBuilder();
            char[] chars = value.toCharArray();
            for (int i = 1; i <= 23; i++) {
                char c = chars[value.length() - i];
                builder.insert(0, c);
            }
            value = builder.toString();
            System.out.println("The HOSTNAME env variable used to set "
                    + JBOSS_TX_NODE_ID_PROPERTY + " is longer than 23 bytes. "
                    + JBOSS_TX_NODE_ID_PROPERTY + " value was adjusted to 23 bytes long string "
                    + value + " from the original value " + originalValue);
        }
        return value;
    }
}
