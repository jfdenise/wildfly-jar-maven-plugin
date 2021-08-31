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

import org.junit.Test;
import org.wildfly.plugins.bootablejar.maven.common.Utils;
import org.wildfly.plugins.bootablejar.patching.PatchingTestUtil;
import static org.wildfly.plugins.bootablejar.patching.PatchingTestUtil.randomString;
import static org.wildfly.plugins.bootablejar.patching.PatchingTestUtil.readFile;

/**
 * @author jdenise
 */
public class PatchUnexistingMiscTestCase extends BootableJarMojoTest {
    public PatchUnexistingMiscTestCase() {
        super("test15-pom.xml", true, null);
    }

    @Test
    public void testMiscPatch()
            throws Exception {
        String patchid = randomString();
        Path patchContentDir = createTestDirectory("patch-test-content", patchid);
        final String testContent = "java -version";
        final Path dir = getTestDir();
        // Overrides so will not fail during patching.
        PatchingTestUtil.buildMiscPatch(patchContentDir, true, dir, patchid, testContent, "bin", "jboss-cli.sh");
        BuildBootableJarMojo mojo = lookupMojo("package");
        assertNotNull(mojo);
        mojo.execute();
        Path home = checkAndGetWildFlyHome(dir, true, true, null, null, mojo.recordState);
        try {
            Path patchedCli = home.resolve("bin").resolve("jboss-cli.sh");
            assertTrue(Files.exists(patchedCli));
            String patchedContent = readFile(patchedCli.toString());
            assertEquals("check content of file after patch", testContent, patchedContent);
            checkJar(dir, true, true, null, null, mojo.recordState);
            checkDeployment(dir, true);
        } finally {
            Utils.deleteDir(home);
        }
    }
}
