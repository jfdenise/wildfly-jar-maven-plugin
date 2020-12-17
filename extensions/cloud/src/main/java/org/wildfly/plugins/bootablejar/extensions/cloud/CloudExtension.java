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
    private static final String TX_PROPAGATION_CLIENT_MAPPING_PROPERTY = "org.wildfly.bootable.tx.propagation.client-mapping";
    private static final String STATEFULSET_HEADLESS_SERVICE_NAME_ENV = "STATEFULSET_HEADLESS_SERVICE_NAME";
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

        boolean hasJbossNodeName = false;
        boolean hasTxPropagationClientMapping = false;
        Set<String> overridingProps = new HashSet<>();
        for (String arg : args) {
            if (arg.startsWith("-D" + JBOSS_NODE_NAME_PROPERTY + "=")) {
                hasJbossNodeName = true;
            } else {
                if (arg.startsWith("-D" + TX_PROPAGATION_CLIENT_MAPPING_PROPERTY + "=")) {
                    hasTxPropagationClientMapping = true;
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

            String originalHostName = hostname;
            String jbossNodeName = null;
            if (!hasJbossNodeName) {
                String value = System.getProperty(JBOSS_NODE_NAME_PROPERTY);
                if (value == null) {

                    if (hostname != null) {
                        // This is a constraint that breaks the server at startup.
                        if (hostname.length() > 23) {

                            StringBuilder builder = new StringBuilder();
                            char[] chars = hostname.toCharArray();
                            for (int i = 1; i <= 23; i++) {
                                char c = chars[hostname.length() - i];
                                builder.insert(0, c);
                            }
                            jbossNodeName = builder.toString();
                            System.out.println("The HOSTNAME env variable used to set "
                                    + "jboss.node.name is longer than 23 bytes. "
                                    + "jboss.node.name value was adjusted to 23 bytes long string "
                                    + jbossNodeName + " from the original value " + originalHostName);
                        }
                        args.add("-Djboss.node.name=" + jbossNodeName);
                    }
                }
            }

            if (!hasTxPropagationClientMapping) {
                String value = System.getProperty(TX_PROPAGATION_CLIENT_MAPPING_PROPERTY);
                if (value == null) {
                    String headlessServiceName = System.getenv(STATEFULSET_HEADLESS_SERVICE_NAME_ENV);
                    // We set the default value to the full hostName.
                    // XXX Is it correct to have such mapping in all cases?
                    // if that is not the case, we need to make this a user choice when building the bootable JAR.
                    //
                    String clientMapping = originalHostName == null ? "" : originalHostName;
                    if (headlessServiceName != null) {
                        if (jbossNodeName == null) {
                            System.err.println("HostName not set, can't properly configure the client-mapping for transaction propagation.");
                        } else {
                            clientMapping = jbossNodeName + "." + headlessServiceName;
                        }
                    }
                    // This is added in all cases. In case we don't want that, must have a user option to enable it.
                    args.add("-D" + TX_PROPAGATION_CLIENT_MAPPING_PROPERTY + "=" + clientMapping);
                }
            }
        }
    }
}
