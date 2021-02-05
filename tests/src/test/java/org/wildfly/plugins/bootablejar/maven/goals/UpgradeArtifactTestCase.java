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

import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.maven.artifact.Artifact;
import org.wildfly.plugins.bootablejar.maven.common.OverridenArtifact;

import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

/**
 * @author jdenise
 */
public class UpgradeArtifactTestCase extends AbstractBootableJarMojoTestCase {

    public UpgradeArtifactTestCase() {
        super("upgrade-artifact-pom.xml", true, null);
    }

    // Un-ignore when we test with a WildFly release that supports overriden artifacts.
    @Ignore
    @Test
    public void testUpgrade() throws Exception {
        BuildBootableJarMojo mojo = lookupMojo("package");
        MavenProjectArtifactVersions artifacts = MavenProjectArtifactVersions.getInstance(mojo.project);
        // We have an older release of undertow-core in the pom.xml
        Assert.assertEquals(1, mojo.overridenServerArtifacts.size());
        String version = null;
        for (OverridenArtifact oa : mojo.overridenServerArtifacts) {
            Artifact a = artifacts.getArtifact(oa);
            Assert.assertNotNull(a);
            version = a.getVersion();
            Assert.assertNotNull(version);
            Assert.assertEquals(oa.getGroupId(), a.getGroupId());
            Assert.assertEquals(oa.getArtifactId(), a.getArtifactId());
        }
        mojo.execute();
        final Path dir = getTestDir();
        Path unzippedJar = checkAndGetWildFlyHome(dir, true, true, null, null, (String[]) null);
        try {
            Path artifact = unzippedJar.resolve("modules").resolve("system").resolve("layers").resolve("base").
                    resolve("io").resolve("undertow").resolve("core").resolve("main").resolve("undertow-core-" + version + ".jar");
            Assert.assertTrue(artifact.toString(), Files.exists(artifact));
        } finally {
            BuildBootableJarMojo.deleteDir(unzippedJar);
        }
    }
}
