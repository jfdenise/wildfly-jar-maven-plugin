/*
 * Copyright 2021 Red Hat, Inc. and/or its affiliates
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

import org.wildfly.plugins.bootablejar.maven.test.AbstractBootableJarMojoTestCase;

/**
 *
 * @author jdenise
 */
abstract class BootableJarMojoTest extends AbstractBootableJarMojoTestCase {
    private static final String ARTIFACTID = "wildfly-jar-maven-plugin";

    protected BootableJarMojoTest(String pomFileName, boolean copyWar, String provisioning, String... cli) {
        super(ARTIFACTID, pomFileName, copyWar, provisioning, cli);
    }
     protected BootableJarMojoTest(final String pomFileName, String testName, final boolean copyWar, final String provisioning, final String... cli) {
         super(ARTIFACTID, pomFileName, testName, copyWar, provisioning, cli);
     }
}
