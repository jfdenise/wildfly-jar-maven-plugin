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
package org.wildfly.plugins.bootablejar.maven.cloud;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import org.wildfly.plugins.bootablejar.maven.goals.BuildBootableJarMojo;

/**
 * A Enable Openshift configuration
 *
 * @author jdenise
 */
public class CloudConfig {

    private static final String OPENSHIFT = "openshift";
    private static final String KUBERNETES = "kubernetes";

    private boolean enableJgroupsPassword = false;

    private static final String[] CLI_SCRIPTS = {
        "openshift-management-script.cli",
        "openshift-logging-script.cli",
        "openshift-interfaces-script.cli",
        "openshift-https-script.cli",
        "openshift-undertow-script.cli",
        "openshift-tx-script.cli",
        "openshift-clustering-script.cli",
        "openshift-webservices-script.cli"};

    private static final String KUBE_PING = "kubernetes.KUBE_PING";
    private static final String DNS_PING = "dns.DNS_PING";
    //Can be openshift or kubernetes
    String type = OPENSHIFT;

    String jgroupsPingProtocol = KUBE_PING;

    public void setJGroupsPingProtocol(String jgroupsPingProtocol) {
        this.jgroupsPingProtocol = jgroupsPingProtocol;
    }

    public String getJGroupsPingProtocol() {
        return jgroupsPingProtocol;
    }

    // XXX JFDENISE, do we allow for fixed ping service name???

    public boolean getEnableJGroupsPassword() {
        return enableJgroupsPassword;
    }

    public void setEnableJGroupsPassword(boolean enableJgroupsPassword) {
        this.enableJgroupsPassword = enableJgroupsPassword;
    }

    public void validate() throws MojoExecutionException {
        if (type == null) {
            type = OPENSHIFT;
        } else {
            switch (type) {
                case OPENSHIFT:
                case KUBERNETES:
                    return;
                default:
                    throw new MojoExecutionException("Invalid cloud type " + type + ". Can be " + OPENSHIFT + " or " + KUBERNETES);
            }
        }
    }

    public void copyExtraContent(BuildBootableJarMojo mojo, Path wildflyDir, Path contentDir)
            throws IOException, PlexusConfigurationException, MojoExecutionException {
        try (InputStream stream = CloudConfig.class.getResourceAsStream("logging.properties")) {
            Path target = wildflyDir.resolve("standalone").resolve("configuration").resolve("logging.properties");
            Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
        }
        Path marker = contentDir.resolve(type + ".properties");
        Properties props = new Properties();
        // TODO, if we need it, add properties there.
        try (FileOutputStream s = new FileOutputStream(marker.toFile())) {
            props.store(s, type + " properties");
        }
        Path extensionJar = mojo.resolveArtifact("org.wildfly.plugins", "wildfly-jar-cloud-extension", null, mojo.retrievePluginVersion());
        ZipUtils.unzip(extensionJar, contentDir);
    }

    public Set<String> getExtraLayers(BuildBootableJarMojo mojo) {
        Set<String> set = new HashSet<>();
        set.add("microprofile-health");
        set.add("core-tools");
        return set;
    }

    public void addCLICommands(BuildBootableJarMojo mojo, List<String> commands) throws Exception {
        // Must be done first before to modify the config with static script
        if (enableJgroupsPassword) {
            Path p = mojo.getJBossHome();
            Path config = p.resolve("standalone").resolve("configuration").resolve("standalone.xml");
            commands.addAll(JGroupsUtil.getAuthProtocolCommands(config));
        }
        for (String script : CLI_SCRIPTS) {
            try (InputStream stream = CloudConfig.class.getResourceAsStream(script)) {
                List<String> lines
                        = new BufferedReader(new InputStreamReader(stream,
                                StandardCharsets.UTF_8)).lines().collect(Collectors.toList());
                commands.addAll(lines);
            }
        }
        String pingScript = null;
        if (DNS_PING.equals(jgroupsPingProtocol)) {
            pingScript = "openshift-clustering-dns-ping-script.cli";
        } else {
            if (KUBE_PING.equals(jgroupsPingProtocol)) {
                pingScript = "openshift-clustering-kube-ping-script.cli";
            } else {
                throw new Exception("Unsupported jgroups ping protocol " + jgroupsPingProtocol);
            }
        }
        try (InputStream stream = CloudConfig.class.getResourceAsStream(pingScript)) {
            List<String> lines
                    = new BufferedReader(new InputStreamReader(stream,
                            StandardCharsets.UTF_8)).lines().collect(Collectors.toList());
            commands.addAll(lines);
        }

    }
}
