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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.jboss.galleon.ProvisioningDescriptionException;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.ProvisioningManager;
import org.jboss.galleon.config.FeaturePackConfig;
import org.jboss.galleon.config.ProvisioningConfig;
import org.jboss.galleon.layout.FeaturePackDescriber;
import org.jboss.galleon.spec.FeaturePackSpec;
import org.jboss.galleon.universe.Channel;
import org.jboss.galleon.universe.FeaturePackLocation;
import org.jboss.galleon.universe.maven.MavenChannel;
import org.jboss.galleon.universe.maven.MavenUniverseException;
import org.wildfly.plugins.bootablejar.maven.common.FeaturePack;
import org.wildfly.plugins.bootablejar.maven.common.OverridenArtifact;

public final class MavenUpgrade {

    private final Map<String, FeaturePack> dependencies = new HashMap<>();
    private Map<String, FeaturePack> topLevels = new HashMap<>();
    private final AbstractBuildBootableJarMojo mojo;
    private final ProvisioningConfig config;
    MavenUpgrade(AbstractBuildBootableJarMojo mojo, ProvisioningConfig config, ProvisioningManager pm) throws MavenUniverseException, ProvisioningException {
        this.mojo = mojo;
        this.config = config;
        for (FeaturePackConfig cfg : config.getFeaturePackDeps()) {
            FeaturePack fp = toFeaturePack(cfg, pm);
            if (fp == null) {
                throw new ProvisioningException("Invalid location " + cfg.getLocation());
            }
            topLevels.put(fp.getGroupId() + ":" + fp.getArtifactId(), fp);
        }

        // Resolve the FP to retrieve dependencies as expressed in fp spec.
        List<Path> resolvedFeaturePacks = new ArrayList<>();
        for (FeaturePack fp : topLevels.values()) {
            resolvedFeaturePacks.add(mojo.resolveMaven(fp));
        }
        mojo.getLog().debug("Top level feature-packs: " + topLevels);
        mojo.getLog().debug("Resolved feature-packs " + resolvedFeaturePacks);
        for (Path p : resolvedFeaturePacks) {
            FeaturePackSpec spec = FeaturePackDescriber.readSpec(p);
            //System.out.println(spec.getFPID());
            List<FeaturePackConfig> allDeps = new ArrayList<>();
            for (FeaturePackConfig cfg : spec.getFeaturePackDeps()) {
                allDeps.add(cfg);
            }
            for (FeaturePackConfig cfg : spec.getTransitiveDeps()) {
                allDeps.add(cfg);
            }
            for (FeaturePackConfig cfg : allDeps) {
                FeaturePack fp = toFeaturePack(cfg, pm);
                if (fp != null) {
                    String ga = fp.getGroupId() + ":" + fp.getArtifactId();
                    // Only add the dep if not already seen. The first installed FP dep wins.
                    if (!topLevels.containsKey(ga) && !dependencies.containsKey(ga)) {
                        dependencies.put(ga, fp);
                    }
                }
            }
        }
        mojo.getLog().debug("FP dependencies " + dependencies);
    }

    ProvisioningConfig upgrade() throws MojoExecutionException, ProvisioningDescriptionException, ProvisioningException {
        List<OverridenArtifact> featurePackDependencies = new ArrayList<>();
        List<Artifact> artifactDependencies = new ArrayList<>();
        for (OverridenArtifact a : mojo.overridenServerArtifacts) {
            // Is it a potential feature-pack
            String key = a.getGroupId() + ":" + a.getArtifactId();
            if (dependencies.containsKey(key)) {
                String fpVers = mojo.artifactVersions.getFPVersion(a.getGroupId(), a.getArtifactId(), a.getClassifier());
                if (fpVers == null) {
                    throw new MojoExecutionException("No version for Galleon feature-pack " + a.getGroupId() + ":" + a.getArtifactId());
                } else {
                    FeaturePack dep = dependencies.get(key);
                    if (fpVers.equals(dep.getVersion())) {
                        mojo.getLog().warn("[UPDATE] Dependency " + key + " wll be not upgraded, already at version: " + fpVers);
                    } else {
                        a.setVersion(fpVers);
                        featurePackDependencies.add(a);
                    }
                }
            } else {
                Artifact mavenArtifact = mojo.artifactVersions.getArtifact(a);
                if (mavenArtifact == null) {
                    // It could be a wrong FP not present in the list of dependencies
                    String fpVers = mojo.artifactVersions.getFPVersion(a.getGroupId(), a.getArtifactId(), a.getClassifier());
                    if (fpVers != null) {
                        throw new MojoExecutionException("Zip artifact " + a.getGroupId() + ":" + a.getArtifactId() + " not found. "
                                + " Could be a wrong Galleon feature-pack used to override a feature-pack dependency.");
                    }
                    throw new MojoExecutionException("No version for artifact " + a.getGroupId() + ":" + a.getArtifactId());
                } else {
                    artifactDependencies.add(mavenArtifact);
                }
            }
        }
        if (!artifactDependencies.isEmpty() || !featurePackDependencies.isEmpty()) {
            ProvisioningConfig.Builder c = ProvisioningConfig.builder(config);
            if (!featurePackDependencies.isEmpty()) {
                mojo.getLog().info("[UPDATE] Overriding Galleon feature-pack dependency with: ");
                for (OverridenArtifact a : featurePackDependencies) {
                    FeaturePackLocation fpl = FeaturePackLocation.fromString(a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getVersion());
                    mojo.getLog().info("[UPDATE]  " + fpl);
                    c.addTransitiveDep(fpl);
                }
            }
            if (!artifactDependencies.isEmpty()) {
                mojo.getLog().info("[UPDATE] Overriding server artifacts with:");
                if (!mojo.pluginOptions.containsKey("jboss-overriden-artifacts")) {
                    String updates = toOptionValue(artifactDependencies);
                    for (Artifact update : artifactDependencies) {
                        mojo.getLog().info("[UPDATE]  " + update.getGroupId() + ":" + update.getArtifactId() + ":"
                                + (update.getClassifier() == null ? "" : update.getClassifier() + ":")
                                + update.getVersion() + (update.getType() == null ? "" : ":" + update.getType()));
                    }
                    c.addOption("jboss-overriden-artifacts", updates);
                }
            }
            return c.build();
        } else {
            return config;
        }
    }

    static String locationWithVersion(String featurePackLocation, MavenProjectArtifactVersions artifactVersions) throws MojoExecutionException {
        FeaturePackLocation location = FeaturePackLocation.fromString(featurePackLocation);
        if (location.getUniverse() == null || location.getUniverse().getLocation() == null) {
            if (!location.hasBuild()) {
                String[] split = featurePackLocation.split(":");
                String version = artifactVersions.getFPVersion(split[0], split[1], null);
                if (version == null) {
                    throw new MojoExecutionException("No version found for " + featurePackLocation);
                }
                if (!featurePackLocation.endsWith(":")) {
                    featurePackLocation = featurePackLocation + ":";
                }
                featurePackLocation = featurePackLocation + version;
            }
        }
        return featurePackLocation;
    }
    private FeaturePack toFeaturePack(FeaturePackConfig cfg, ProvisioningManager pm) {
        FeaturePack fp = null;
        if (cfg.getLocation().isMavenCoordinates()) {
            fp = getFeaturePack(cfg.getLocation().toString());
        } else {
            fp = getFeaturePack(cfg, pm);
        }
        return fp;
    }

    private FeaturePack getFeaturePack(FeaturePackConfig cfg, ProvisioningManager pm) {
        Channel channel = null;
        try {
            channel = pm.getLayoutFactory().getUniverseResolver().getChannel(cfg.getLocation());
            if (channel instanceof MavenChannel) {
                MavenChannel mavenChannel = (MavenChannel) channel;
                FeaturePack fp = new FeaturePack();
                fp.setGroupId(mavenChannel.getFeaturePackGroupId());
                fp.setArtifactId(mavenChannel.getFeaturePackArtifactId());
                String build = cfg.getLocation().getBuild();
                if (build == null) {
                    build = mavenChannel.getLatestBuild(cfg.getLocation());
                }
                fp.setVersion(build);
                return fp;
            }
        } catch (Exception ex) {
            // OK, invalid channel, can occurs for non registered FP that are referenced from GAV.
            mojo.getLog().debug("Invalid channel for " + cfg.getLocation() + " The feature-pack is not known in the universe, skipping it.");
        }
        return null;
    }

    private static FeaturePack getFeaturePack(String str) {
        final String[] parts = str.split(":");
        final String groupId = parts[0];
        final String artifactId = parts[1];
        String version = parts[2];
        FeaturePack fp = new FeaturePack();
        fp.setGroupId(groupId);
        fp.setArtifactId(artifactId);
        fp.setVersion(version);
        return fp;
    }

    public static String toOptionValue(List<Artifact> lst) throws ProvisioningException {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < lst.size(); i++) {
            Artifact artifact = lst.get(i);
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

    private static void validate(Artifact artifact) throws ProvisioningException {
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
