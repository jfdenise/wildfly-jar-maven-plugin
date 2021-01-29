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
package org.wildfly.plugins.bootablejar.maven.common;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.jboss.galleon.ProvisioningException;
import org.wildfly.plugins.bootablejar.maven.goals.BuildBootableJarMojo;
import org.wildfly.plugins.bootablejar.maven.goals.MavenProjectArtifactVersions;

/**
 * @author jdenise
 */
public class Utils {

    private static final Pattern WHITESPACE_IF_NOT_QUOTED = Pattern.compile("(\\S+\"[^\"]+\")|\\S+");

    public static String getBootableJarPath(String jarFileName, MavenProject project, String goal) throws MojoExecutionException {
        String jarName = jarFileName;
        if (jarName == null) {
            String finalName = project.getBuild().getFinalName();
            jarName = finalName + "-" + BuildBootableJarMojo.BOOTABLE_SUFFIX + "." + BuildBootableJarMojo.JAR;
        }
        String path = project.getBuild().getDirectory() + File.separator + jarName;
        if (!Files.exists(Paths.get(path))) {
            throw new MojoExecutionException("Cannot " + goal + " without a bootable jar; please `mvn wildfly-jar:package` prior to invoking wildfly-jar:run from the command-line");
        }
        return path;
    }

    /**
     * Splits the arguments into a list. The arguments are split based on whitespace while ignoring whitespace that is
     * within quotes.
     *
     * @param arguments the arguments to split
     *
     * @return the list of the arguments
     */
    public static List<String> splitArguments(final CharSequence arguments) {
        final List<String> args = new ArrayList<>();
        final Matcher m = WHITESPACE_IF_NOT_QUOTED.matcher(arguments);
        while (m.find()) {
            final String value = m.group();
            if (!value.isEmpty()) {
                args.add(value);
            }
        }
        return args;
    }

    public static String toOptionValue(List<OverridenArtifact> lst, MavenProjectArtifactVersions versions) throws ProvisioningException {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < lst.size(); i++) {
            OverridenArtifact artifact = lst.get(i);
            if (artifact.getVersion() == null) {
                artifact.setVersion(versions.getVersion(artifact));
            }
            validate(artifact);
            builder.append(artifact.getGroupId()).append(":").append(artifact.getArtifactId()).
                    append(":").append(artifact.getVersion()).append(":");
            String classifier = artifact.getClassifier() == null ? "" : artifact.getClassifier();
            builder.append(classifier).append(":").append(artifact.getType());
            if (i < lst.size() - 1) {
                builder.append("|");
            }
        }
        return builder.toString();
    }

    private static void validate(OverridenArtifact artifact) throws ProvisioningException {
        if (artifact.getGroupId() == null) {
            throw new ProvisioningException("No groupId set for overriden artifact");
        }
        if (artifact.getArtifactId() == null) {
            throw new ProvisioningException("No artifactId set for overriden artifact");
        }
        if (artifact.getVersion() == null) {
            throw new ProvisioningException("No version set for overriden artifact");
        }
        if (artifact.getType() == null) {
            throw new ProvisioningException("No type set for overriden artifact");
        }
    }
}
