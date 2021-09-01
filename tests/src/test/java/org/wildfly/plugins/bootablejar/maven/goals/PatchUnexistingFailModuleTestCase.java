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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.jboss.as.patching.HashUtils;
import org.jboss.as.patching.metadata.ContentModification;

import org.junit.Test;
import org.wildfly.plugins.bootablejar.patching.ContentModificationUtils;
import org.wildfly.plugins.bootablejar.patching.Module;
import static org.wildfly.plugins.bootablejar.patching.PatchingTestUtil.AS_DISTRIBUTION;
import static org.wildfly.plugins.bootablejar.patching.PatchingTestUtil.buildModulePatch;
import static org.wildfly.plugins.bootablejar.patching.PatchingTestUtil.randomString;
import org.wildfly.plugins.bootablejar.patching.ResourceItem;

/**
 * @author jdenise
 */
public class PatchUnexistingFailModuleTestCase extends BootableJarMojoTest {

    public PatchUnexistingFailModuleTestCase() {
        super("test15-pom.xml", true, null);
    }

    @Test
    public void testUpdateModulePatch()
            throws Exception {
        String patchid = randomString();
        String baseLayerPatchID = randomString();
        Path patchContentDir = createTestDirectory("patch-test-content", patchid);

        final String moduleName = "javax.management.j2ee.api";
        Path moduleDir = Paths.get(AS_DISTRIBUTION);
        moduleDir = moduleDir.resolve("modules").resolve("system").resolve("layers").
                resolve("base").resolve("javax").resolve("management").resolve("j2ee").resolve("api").resolve("main");
        assertTrue(Files.exists(moduleDir));
        Path moduleFile = moduleDir.resolve("module.xml");
        Module updatedModule = new Module.Builder(moduleName)
                .miscFile(new ResourceItem("res1", "new resource in the module".getBytes(StandardCharsets.UTF_8)))
                .originalModuleXml(moduleFile)
                .property("foo", "bar")
                .build();
        // create the patch with the updated module
        ContentModification moduleModified = ContentModificationUtils.modifyModule(patchContentDir.toFile(),
                baseLayerPatchID, HashUtils.hashFile(moduleDir.toFile()), updatedModule);
        final Path dir = getTestDir();
        buildModulePatch(patchContentDir, false, dir, patchid, moduleModified, baseLayerPatchID);
        BuildBootableJarMojo mojo = lookupMojo("package");
        assertNotNull(mojo);
        boolean failed = false;
        try {
            mojo.execute();
            failed = true;
        } catch (Exception ex) {
            // OK expected
            System.err.println("EXPECTED exception");
            ex.printStackTrace();
        }
        if (failed) {
            throw new Exception("Should have failed");
        }
    }
}
