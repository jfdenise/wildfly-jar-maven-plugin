/*
 * Copyright 2022 Red Hat, Inc. and/or its affiliates
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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jboss.galleon.universe.maven.MavenArtifact;

import org.junit.Assert;
import org.junit.Test;
import org.wildfly.channel.ChannelManifestCoordinate;

/**
 * @author jdenise
 */
public class ChannelsTestCase extends AbstractBootableJarMojoTestCase {

    public ChannelsTestCase() {
        super("channels", true, null);
    }

    @Test
    public void testChannel() throws Exception {
        BuildBootableJarMojo mojo = lookupMojo("package");
        setupTestChannel(mojo);
        mojo.execute();
        final Path dir = getTestDir();
        Path unzippedJar = checkAndGetWildFlyHome(dir, false, true, null, null, false);
        try {
            Path modulesDir = unzippedJar.resolve("modules").resolve("org").resolve("postgresql").resolve("jdbc").resolve("main");
            Assert.assertTrue(modulesDir.toString(), Files.exists(modulesDir));
        } finally {
            BuildBootableJarMojo.deleteDir(unzippedJar);
        }
    }

    private void generateChannel(List<MavenArtifact> artifacts, Path file) throws IOException {
        StringBuilder channel = new StringBuilder();
        channel.append("schemaVersion: \"1.0.0\"").append(System.lineSeparator());
        channel.append("name: Test Channel").append(System.lineSeparator());
        channel.append("description: Test Channel").append(System.lineSeparator());
        channel.append("streams:").append(System.lineSeparator());
        for (MavenArtifact artifact : artifacts) {
            channel.append("  - groupId: ").append(artifact.getGroupId()).append(System.lineSeparator());
            channel.append("    artifactId: ").append(artifact.getArtifactId()).append(System.lineSeparator());
            channel.append("    ").append((artifact.hasVersion() ? "version" : "versionPattern" )).append(": ").append((artifact.hasVersion() ? artifact.getVersion() : artifact.getVersionRange())).append(System.lineSeparator());
        }
        Files.write(file, channel.toString().getBytes());
    }

    private void setupTestChannel(BuildBootableJarMojo mojo) throws IOException {
        List<MavenArtifact> artifacts = new ArrayList<>();
        MavenArtifact ds = new MavenArtifact();
        ds.setGroupId("org.wildfly");
        ds.setArtifactId("wildfly-datasources-galleon-pack");
        ds.setVersionRange("'3\\.\\d+\\.\\d+\\.Final'");
        artifacts.add(ds);
        MavenArtifact cg = new MavenArtifact();
        cg.setGroupId("org.wildfly.galleon-plugins");
        cg.setArtifactId("wildfly-config-gen");
        cg.setVersion("'7.3.1.Final'");
        artifacts.add(cg);
        MavenArtifact launcher = new MavenArtifact();
        launcher.setGroupId("org.wildfly.core");
        launcher.setArtifactId("wildfly-launcher");
        launcher.setVersionRange("'26\\.0\\.\\d+\\.Final'");
        artifacts.add(launcher);
        MavenArtifact modules = new MavenArtifact();
        modules.setGroupId("org.jboss.modules");
        modules.setArtifactId("jboss-modules");
        modules.setVersionRange("'2\\.1\\.\\d+\\.Final'");
        artifacts.add(modules);
        Path channel = new File(mojo.project.getBasedir().getAbsoluteFile().toPath().toString() + "/my-channel.yaml").toPath();
        generateChannel(artifacts, channel);
        ChannelManifestCoordinate coordinate = new ChannelManifestCoordinate(channel.toUri().toURL());
        mojo.channels = new ArrayList<>();
        ChannelConfiguration config = new ChannelConfiguration();
        config.setManifest(coordinate);
        mojo.channels.add(config);
    }
}
