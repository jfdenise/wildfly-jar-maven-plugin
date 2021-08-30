/*
 * JBoss, Home of Professional Open Source.
 *
 * Copyright 2021 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wildfly.plugins.bootablejar.maven.common;

/**
 *
 * @author jdenise
 */
public class Constants {

    public static final String HEALTH = "health";
    public static final String MP_HEALTH = "microprofile-health";
    public static final String BOOTABLE_SUFFIX = "bootable";
    public static final String JAR = "jar";
    public static final String WAR = "war";
    public static final String MODULE_ID_JAR_RUNTIME = "org.wildfly.bootable-jar";

    public static final String BOOT_ARTIFACT_ID = "wildfly-jar-boot";

    public static final String STANDALONE = "standalone";
    public static final String STANDALONE_XML = "standalone.xml";
    public static final String STANDALONE_MICROPROFILE_XML = "standalone-microprofile.xml";
    public static final String SERVER_CONFIG = "--server-config";
    public static final String MAVEN_REPO_PLUGIN_OPTION = "jboss-maven-repo";

    public static final String JBOSS_MAVEN_DIST = "jboss-maven-dist";
    public static final String JBOSS_PROVISIONING_MAVEN_REPO = "jboss-maven-provisioning-repo";
    public static final String MAVEN_REPO_LOCAL = "maven.repo.local";
    public static final String PLUGIN_PROVISIONING_FILE = ".wildfly-jar-plugin-provisioning.xml";

    public static final String WILDFLY_ARTIFACT_VERSIONS_RESOURCE_PATH = "wildfly/artifact-versions.properties";
}
