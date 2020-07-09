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

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.maven.plugin.MojoExecutionException;
import org.codehaus.plexus.configuration.PlexusConfigurationException;
import org.jboss.galleon.util.ZipUtils;

/**
 * A Enable Openshift configuration
 * @author jdenise
 */
public class CloudConfig {

    private static final String OPENSHIFT = "openshift";
    private static final String KUBERNETES = "kubernetes";

    // Can't enable until https://issues.redhat.com/browse/WFLY-13650 is fixed.
    // boolean enableMicroprofileConfigMap;
    //Can be openshift or kubernetes
    String type = OPENSHIFT;

    void validate() throws MojoExecutionException {
        if(type != null) {
            if(OPENSHIFT.equals(type) || KUBERNETES.equals(type)) {
                return;
            } else
                throw new MojoExecutionException("Invalid clud type " + type + ". Can be "+OPENSHIFT+ "or" + KUBERNETES);
        } else {
            type = OPENSHIFT;
        }
    }

    void copyExtraContent(AbstractBuildBootableJarMojo mojo, Path wildflyDir, Path contentDir)
            throws IOException, UnsupportedEncodingException, PlexusConfigurationException, MojoExecutionException {
        try (InputStream stream = BuildBootableJarMojo.class.getResourceAsStream("logging.properties")) {
                Path target = wildflyDir.resolve("standalone").resolve("configuration").resolve("logging.properties");
                Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
            }
            Path marker = contentDir.resolve(type + ".properties");
            Properties props = new Properties();
            // TODO, if we need it, add properties there.
            try (FileOutputStream s = new FileOutputStream(marker.toFile())) {
                props.store(s, type + " properties");
            }
            Path extensionJar = mojo.resolveArtifact("org.wildfly.plugins", "wildfly-jar-cloud-extension", mojo.retrievePluginVersion());
            ZipUtils.unzip(extensionJar, contentDir);
    }

    Set<String> getExtraLayers(AbstractBuildBootableJarMojo mojo) {
        Set<String> set = new HashSet<>();
        set.add("microprofile-health");
        set.add("core-tools");
//      // Should be already added by health, anyway, enforce it.
//      set.add("microprofile-config");
        return set;
    }

    void addCLICommands(AbstractBuildBootableJarMojo mojo, List<String> commands) throws IOException {
        try (InputStream stream = BuildBootableJarMojo.class.getResourceAsStream("openshift-interfaces-script.cli")) {
            List<String> lines
                    = new BufferedReader(new InputStreamReader(stream,
                            StandardCharsets.UTF_8)).lines().collect(Collectors.toList());
            commands.addAll(lines);
        }

        try (InputStream stream = BuildBootableJarMojo.class.getResourceAsStream("openshift-logging-script.cli")) {
            List<String> lines
                    = new BufferedReader(new InputStreamReader(stream,
                            StandardCharsets.UTF_8)).lines().collect(Collectors.toList());
            commands.addAll(lines);
        }

        try (InputStream stream = BuildBootableJarMojo.class.getResourceAsStream("openshift-port-offset-script.cli")) {
            List<String> lines
                    = new BufferedReader(new InputStreamReader(stream,
                            StandardCharsets.UTF_8)).lines().collect(Collectors.toList());
            commands.addAll(lines);
        }

        try (InputStream stream = BuildBootableJarMojo.class.getResourceAsStream("openshift-tracing-script.cli")) {
            List<String> lines
                    = new BufferedReader(new InputStreamReader(stream,
                            StandardCharsets.UTF_8)).lines().collect(Collectors.toList());
            commands.addAll(lines);
        }

//        if (openshift.enableMicroprofileConfigMap) {
//            try (InputStream stream = BuildBootableJarMojo.class.getResourceAsStream("openshift-mp-config-script.cli")) {
//                List<String> lines
//                        = new BufferedReader(new InputStreamReader(stream,
//                                StandardCharsets.UTF_8)).lines().collect(Collectors.toList());
//                commands.addAll(lines);
//            }
//        }

        try (InputStream stream = BuildBootableJarMojo.class.getResourceAsStream("openshift-tx-script.cli")) {
            List<String> lines
                    = new BufferedReader(new InputStreamReader(stream,
                            StandardCharsets.UTF_8)).lines().collect(Collectors.toList());
            commands.addAll(lines);
        }
    }
}
