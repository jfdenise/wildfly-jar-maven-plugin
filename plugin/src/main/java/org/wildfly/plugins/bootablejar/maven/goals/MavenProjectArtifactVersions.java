/*
 * Copyright 2016-2019 Red Hat, Inc. and/or its affiliates
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

import java.util.Map;
import java.util.TreeMap;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Dependency;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.artifact.ArtifactCoordinate;
import org.wildfly.plugins.bootablejar.maven.common.OverridenArtifact;

public final class MavenProjectArtifactVersions {

    private class ArtifactCoords implements ArtifactCoordinate {

        private final Artifact artifact;

        ArtifactCoords(Artifact artifact) {
            this.artifact = artifact;
        }

        @Override
        public String getGroupId() {
            return artifact.getGroupId();
        }

        @Override
        public String getArtifactId() {
            return artifact.getArtifactId();
        }

        @Override
        public String getVersion() {
            return artifact.getVersion();
        }

        @Override
        public String getExtension() {
            return artifact.getType();
        }

        @Override
        public String getClassifier() {
            return artifact.getClassifier();
        }

    }

    private class DependencyCoords implements ArtifactCoordinate {

        private final Dependency artifact;

        DependencyCoords(Dependency artifact) {
            this.artifact = artifact;
        }

        @Override
        public String getGroupId() {
            return artifact.getGroupId();
        }

        @Override
        public String getArtifactId() {
            return artifact.getArtifactId();
        }

        @Override
        public String getVersion() {
            return artifact.getVersion();
        }

        @Override
        public String getExtension() {
            return artifact.getType();
        }

        @Override
        public String getClassifier() {
            return artifact.getClassifier();
        }

    }
    private static final String TEST_JAR = "test-jar";
    private static final String SYSTEM = "system";

    static MavenProjectArtifactVersions getInstance(MavenProject project) {
        return new MavenProjectArtifactVersions(project);
    }

    private final Map<String, ArtifactCoordinate> artifactVersions = new TreeMap<>();
    private final Map<String, ArtifactCoordinate> fpVersions = new TreeMap<>();

    private MavenProjectArtifactVersions(MavenProject project) {
        for (Artifact artifact : project.getArtifacts()) {
            if (TEST_JAR.equals(artifact.getType()) || SYSTEM.equals(artifact.getScope())) {
                continue;
            }
            put(new ArtifactCoords(artifact));
        }
        // We retrieve the versions from dep management
        if (project.getDependencyManagement() != null) {
            for (Dependency dependency : project.getDependencyManagement().getDependencies()) {
                final String key = getKey(dependency.getGroupId(), dependency.getArtifactId(), dependency.getClassifier());
                if (!artifactVersions.containsKey(key) && !fpVersions.containsKey(key)) {
                    put(new DependencyCoords(dependency));
                }
            }
        }
    }

    public String getArtifactVersion(String groupId, String artifactId, String classifier) {
        String key = getKey(groupId, artifactId, classifier);
        ArtifactCoordinate a = artifactVersions.get(key);
        return a == null ? null : a.getVersion();
    }

    public ArtifactCoordinate getArtifact(OverridenArtifact artifact) {
        String key = getKey(artifact.getGroupId(), artifact.getArtifactId(), artifact.getClassifier());
        ArtifactCoordinate a = artifactVersions.get(key);
        return a;
    }

    public String getFPVersion(String groupId, String artifactId, String classifier) {
        String key = getKey(groupId, artifactId, classifier);
        ArtifactCoordinate a = fpVersions.get(key);
        return a == null ? null : a.getVersion();
    }

    private static String getKey(String groupId, String artifactId, String classifier) {
        StringBuilder buf = new StringBuilder(groupId).append(':').
                append(artifactId);
        if (classifier != null && !classifier.isEmpty()) {
            buf.append("::").append(classifier);
        }
        return buf.toString();
    }

    public String getVersion(OverridenArtifact artifact) {
        return getArtifactVersion(artifact.getGroupId(), artifact.getArtifactId(), artifact.getClassifier());
    }

    private void put(ArtifactCoordinate artifact) {
        String key = getKey(artifact.getGroupId(), artifact.getArtifactId(), artifact.getClassifier());
        if ("zip".equals(artifact.getExtension())) {
            fpVersions.put(key, artifact);
        } else {
            artifactVersions.put(key, artifact);
        }
    }
}
