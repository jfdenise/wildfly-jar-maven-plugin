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

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.Test;

/**
 * @author jdenise
 */
public class DevWatchTestCase extends AbstractDevWatchTestCase {

    public DevWatchTestCase() {
        super("jaxrs", "DevWatch");
    }

    @Test
    public void testDevWatchWeb() throws Exception {
        startGoal();
        Thread.sleep(10000);
        // Does not contain extra layers added during the test
        assertFalse(layerExists("jmx"));
        assertTrue(layerExists("jaxrs"));

//        Path targetDir = getTestDir().resolve("target").resolve("deployments").resolve("ROOT.war");
//        assertTrue(targetDir.toString(), Files.exists(targetDir));
//        assertTrue(targetDir.toString(), Files.isDirectory(targetDir));

        String url = createUrl(TestEnvironment.getHttpPort(), "rest/hello");
        String radical = "Hello from ";
        String msg = "WildFly bootable jar!";
        String expectedContent = radical + msg;
        assertEquals(expectedContent, getBodyContent(url));

        String staticUrl = createUrl(TestEnvironment.getHttpPort(), "");
        String expectedStaticContent = "Hello from static index.html file" + System.lineSeparator();
        String liveContent = getBodyContent(staticUrl);
        System.out.println("[" + liveContent + "]");
        System.out.println("[" + expectedStaticContent + "]");
        assertEquals(expectedStaticContent, liveContent);

        Thread.sleep(5000);
        // Update Java file and check for change.
        Path javaFile = getTestDir().resolve("src").resolve("main").resolve("java").
                resolve("org").resolve("wildfly").resolve("plugins").resolve("demo").resolve("jaxrs").resolve("HelloWorldEndpoint.java");
        String str = new String(Files.readAllBytes(javaFile), "UTF-8");
        String patchedRadical = "Hi guys ";
        str = str.replace(radical, patchedRadical);
        String patchedContent = patchedRadical + msg;
        Files.write(javaFile, str.getBytes());
        Thread.sleep(5000);
        assertTrue(pollBodyContent(url, patchedContent));

        Thread.sleep(5000);
        //Update webapp static file and check for change.
        Path indexFile = getTestDir().resolve("src").resolve("main").resolve("webapp").
                resolve("index.html");
        String indexStr = new String(Files.readAllBytes(indexFile), "UTF-8");
        String patchedStaticContent = "Static File patched from test";
        indexStr = indexStr.replace(expectedStaticContent, patchedStaticContent);

        Files.write(indexFile, indexStr.getBytes());
        Thread.sleep(5000);
        assertTrue(pollBodyContent(staticUrl, patchedStaticContent));

        Thread.sleep(5000);
        // Update resources file and check.
        Path resourcesFile = getTestDir().resolve("src").resolve("main").resolve("resources").
                resolve("myresources.properties");
        Properties props = new Properties();
        String testMsg = " The test!";
        props.setProperty("msg", testMsg);
        try (FileOutputStream output = new FileOutputStream(resourcesFile.toFile())) {
            props.store(output, null);
        }
        Thread.sleep(5000);
        String expectedNewContent = patchedRadical + testMsg;
        assertTrue(pollBodyContent(url, expectedNewContent));

        Thread.sleep(5000);
        // Add a new directory dir
        Path p = getTestDir().resolve("myresources");
        Files.createDirectory(p);
        Path resourcesFile2 = p.resolve("myresources2.properties");

        Properties newProperties = new Properties();
        String msg2 = "Message 2";
        newProperties.setProperty("msg", msg2);
        try (FileOutputStream output = new FileOutputStream(resourcesFile2.toFile())) {
            newProperties.store(output, null);
        }
        Thread.sleep(5000);
        Path pomFile = getTestDir().resolve("pom.xml");
        String pomContent = new String(Files.readAllBytes(pomFile), "UTF-8");
        // Empty file, must resist
        Files.write(pomFile, "".getBytes());
        waitForLogMessage("[FATAL] Non-readable POM", TestEnvironment.getTimeout());

        String resources = "<resources><resource><directory>" + p + "</directory></resource>"
                + "<resource><directory>" + resourcesFile.getParent() + "</directory></resource></resources>";
        pomContent = pomContent.replace(RESOURCES_MARKER, resources);
        Files.write(pomFile, pomContent.getBytes());
        Thread.sleep(5000);
        String latestMsg = expectedNewContent + " " + msg2;
        assertTrue(pollBodyContent(url, latestMsg));

        assertFalse(logFileContains(LOG_SERVER_RESTART));
        assertTrue(logFileContains(LOG_RESET_WATCHER));

        Thread.sleep(5000);
        // Add extra layers!
        String updatedLayers = "<layer>jmx</layer>";
        pomContent = new String(Files.readAllBytes(pomFile), "UTF-8");
        pomContent = pomContent.replace(UPDATED_LAYERS_MARKER, updatedLayers);
        Files.write(pomFile, pomContent.getBytes());
        waitForLogMessage(LOG_REBUILD_JAR, TestEnvironment.getTimeout());
        waitForLayer(str, TestEnvironment.getTimeout());
        waitForLogMessage(LOG_SERVER_RESTART, TestEnvironment.getTimeout());
        // Server has been re-started, retrieve the endpoint returned string
        Thread.sleep(10000);
        assertTrue(pollBodyContent(url, latestMsg));

    }
}
