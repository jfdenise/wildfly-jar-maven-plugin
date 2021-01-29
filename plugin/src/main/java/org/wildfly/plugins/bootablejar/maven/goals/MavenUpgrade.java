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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
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
import org.jboss.galleon.universe.FeaturePackLocation.ProducerSpec;
import org.jboss.galleon.universe.maven.MavenChannel;
import org.jboss.galleon.universe.maven.MavenUniverseException;
import org.wildfly.plugins.bootablejar.maven.common.FeaturePack;
import org.wildfly.plugins.bootablejar.maven.common.OverriddenArtifact;

public final class MavenUpgrade {

    private final Map<String, FeaturePack> dependencies = new HashMap<>();
    private final Map<String, FeaturePack> topLevels = new HashMap<>();
    private final AbstractBuildBootableJarMojo mojo;
    private final ProvisioningConfig config;
    private final Map<ProducerSpec, String> producerToGAC = new HashMap<>();

    MavenUpgrade(AbstractBuildBootableJarMojo mojo, ProvisioningConfig config, ProvisioningManager pm)
            throws MavenUniverseException, ProvisioningException, MojoExecutionException {
        this.mojo = mojo;
        this.config = config;
        for (FeaturePackConfig cfg : config.getFeaturePackDeps()) {
            FeaturePack fp = toFeaturePack(cfg, pm);
            if (fp == null) {
                throw new ProvisioningException("Invalid location " + cfg.getLocation());
            }
            topLevels.put(fp.getGAC(), fp);
        }

        // Resolve the FP to retrieve dependencies as expressed in fp spec.
        Map<String, Path> resolvedFeaturePacks = new HashMap<>();
        for (FeaturePack fp : topLevels.values()) {
            resolvedFeaturePacks.put(fp.getGAC(), mojo.resolveMaven(fp));
        }
        mojo.getLog().debug("Top level feature-packs: " + topLevels);
        mojo.getLog().debug("Resolved feature-packs " + resolvedFeaturePacks);
        for (Entry<String, Path> entry : resolvedFeaturePacks.entrySet()) {
            FeaturePackSpec spec = FeaturePackDescriber.readSpec(entry.getValue());
            producerToGAC.put(spec.getFPID().getProducer(), entry.getKey());
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
                    String gac = fp.getGAC();
                    // Only add the dep if not already seen. The first installed FP dep wins.
                    if (!topLevels.containsKey(gac) && !dependencies.containsKey(gac)) {
                        // Resolve to retrieve the actual producer and map to GAC
                        Path p = mojo.resolveMaven(fp);
                        FeaturePackSpec depSpec = FeaturePackDescriber.readSpec(p);
                        producerToGAC.put(depSpec.getFPID().getProducer(), gac);
                        dependencies.put(gac, fp);
                    }
                }
            }
        }
        mojo.getLog().debug("FP dependencies " + dependencies);
    }

    ProvisioningConfig upgrade() throws MojoExecutionException, ProvisioningDescriptionException, ProvisioningException {
        List<FeaturePack> featurePackDependencies = new ArrayList<>();
        List<OverriddenArtifact> artifactDependencies = new ArrayList<>();
        Map<String, OverriddenArtifact> allArtifacts = new HashMap<>();
        for (OverriddenArtifact a : mojo.overriddenServerArtifacts) {
            if (a.getGroupId() == null || a.getArtifactId() == null) {
                throw new MojoExecutionException("Invalid Artifact , groupId and artifactId are required");
            }
            String key = a.getGAC();
            if (allArtifacts.containsKey(key)) {
                throw new MojoExecutionException("Artifact " + key + " is present more than once in the overridden artifacts. Must be unique.");
            } else {
                allArtifacts.put(key, a);
            }
            // Is it a potential feature-pack
            if (dependencies.containsKey(key)) {
                Artifact fpArtifact = mojo.artifactVersions.getFeaturePackArtifact(a.getGroupId(), a.getArtifactId(), a.getClassifier());
                if (fpArtifact == null) {
                    throw new MojoExecutionException("No version for Galleon feature-pack " + a.getGAC());
                } else {
                    FeaturePack dep = dependencies.get(key);
                    if (fpArtifact.getVersion().equals(dep.getVersion())) {
                        mojo.getLog().warn("[UPDATE] Dependency " + key + " wll be not upgraded, already at version: " + fpArtifact.getVersion());
                    } else {
                        FeaturePack fp = new FeaturePack();
                        fp.setGroupId(a.getGroupId());
                        fp.setArtifactId(a.getArtifactId());
                        fp.setClassifier(fpArtifact.getClassifier());
                        fp.setExtension(fpArtifact.getType());
                        fp.setVersion(fpArtifact.getVersion());
                        featurePackDependencies.add(fp);
                    }
                }
            } else {
                Artifact mavenArtifact = mojo.artifactVersions.getArtifact(a);
                if (mavenArtifact == null) {
                    // It could be a wrong FP not present in the list of dependencies
                    Artifact fpArtifact = mojo.artifactVersions.getFeaturePackArtifact(a.getGroupId(), a.getArtifactId(), a.getClassifier());
                    if (fpArtifact != null) {
                        throw new MojoExecutionException("Zip artifact " + a.getGAC() + " not found in dependencies. "
                                + " Could be a wrong Galleon feature-pack used to override a feature-pack dependency.");
                    }
                    throw new MojoExecutionException("No version for artifact " + a.getGAC());
                } else {
                    if (a.getVersion() == null) {
                        a.setVersion(mavenArtifact.getVersion());
                    }
                    if (a.getType() == null) {
                        a.setType(mavenArtifact.getType());
                    }
                    artifactDependencies.add(a);
                }
            }
        }
        if (!artifactDependencies.isEmpty() || !featurePackDependencies.isEmpty()) {
            ProvisioningConfig.Builder c = ProvisioningConfig.builder(config);
            if (!featurePackDependencies.isEmpty()) {
                mojo.getLog().info("[UPDATE] Overriding Galleon feature-pack dependency with: ");
                for (FeaturePack fp : featurePackDependencies) {
                    FeaturePackLocation fpl = FeaturePackLocation.fromString(fp.getMavenCoords());
                    mojo.getLog().info("[UPDATE]  " + fp.getGroupId() + ":" + fp.getArtifactId() + ":"
                            + (fp.getClassifier() == null ? "" : fp.getClassifier() + ":")
                            + fp.getVersion() + (fp.getExtension() == null ? "" : ":" + fp.getExtension()));
                    c.addTransitiveDep(fpl);
                }
            }
            if (!artifactDependencies.isEmpty()) {
                mojo.getLog().info("[UPDATE] Overriding server artifacts with:");
                if (!mojo.pluginOptions.containsKey("jboss-overridden-artifacts")) {
                    String updates = toOptionValue(artifactDependencies);
                    for (OverriddenArtifact update : artifactDependencies) {
                        mojo.getLog().info("[UPDATE]  " + update.getGroupId() + ":" + update.getArtifactId() + ":"
                                + (update.getClassifier() == null ? "" : update.getClassifier() + ":")
                                + update.getVersion() + (update.getType() == null ? "" : ":" + update.getType()));
                    }
                    c.addOption("jboss-overridden-artifacts", updates);
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
            FeaturePack fp = getFeaturePack(featurePackLocation);
            if (fp.getVersion() == null) {
                Artifact fpArtifact = artifactVersions.getFeaturePackArtifact(fp.getGroupId(), fp.getArtifactId(), null);
                if (fpArtifact == null) {
                    throw new MojoExecutionException("No version found for " + featurePackLocation);
                }
                fp.setVersion(fpArtifact.getVersion());
                featurePackLocation = fp.getMavenCoords();
            }
        }
        return featurePackLocation;
    }

    private FeaturePack toFeaturePack(FeaturePackConfig cfg, ProvisioningManager pm) throws MojoExecutionException {
        FeaturePack fp;
        if (cfg.getLocation().isMavenCoordinates()) {
            fp = getFeaturePack(cfg.getLocation().toString());
        } else {
            fp = getFeaturePack(cfg, pm);
        }
        return fp;
    }

    String getMavenFeaturePack(FeaturePackLocation.FPID location) {
        String gac = producerToGAC.get(location.getProducer());
        if (gac == null) {
            return location.toString();
        } else {
            return gac + ":" + location.getBuild();
        }
    }

    private FeaturePack getFeaturePack(FeaturePackConfig cfg, ProvisioningManager pm) {
        try {
            Channel channel = pm.getLayoutFactory().getUniverseResolver().getChannel(cfg.getLocation());
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
        } catch (ProvisioningException ex) {
            // OK, invalid channel, can occurs for non registered FP that are referenced from GAV.
            mojo.getLog().debug("Invalid channel for " + cfg.getLocation() + " The feature-pack is not known in the universe, skipping it.");
        }
        return null;
    }

    //groupId:artifactid[:classfier:extension:][:version]
    static FeaturePack getFeaturePack(String str) throws MojoExecutionException {
        if (str == null) {
            throw new MojoExecutionException("Null feature-pack coords");
        }
        final String[] parts = str.split(":");
        if (parts.length < 2 || parts.length > 5) {
            throw new MojoExecutionException("Invalid feature-pack location format: " + str);
        }
        FeaturePack fp = new FeaturePack();
        fp.setGroupId(parts[0]);
        fp.setArtifactId(parts[1]);
        String classifier;
        String extension;
        String version = null;

        if (parts.length >= 4) {
            classifier = parts[2] == null || parts[2].isEmpty() ? null : parts[2];
            extension = parts[3] == null || parts[3].isEmpty() ? null : parts[3];
            fp.setClassifier(classifier);
            if (extension != null) {
                fp.setExtension(extension);
            }
            if (parts.length == 5) {
                version = parts[4] == null || parts[4].isEmpty() ? null : parts[4];
            }
        } else if (parts.length == 3) {
            version = parts[2];
        }

        fp.setVersion(version);
        return fp;
    }

    static String toOptionValue(List<OverriddenArtifact> lst) throws ProvisioningException {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < lst.size(); i++) {
            OverriddenArtifact artifact = lst.get(i);
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

    private static void validate(OverriddenArtifact artifact) throws ProvisioningException {
        if (artifact.getGroupId() == null) {
            throw new ProvisioningException("No groupId set for overridden artifact");
        }
        if (artifact.getArtifactId() == null) {
            throw new ProvisioningException("No artifactId set for overridden artifact");
        }
        if (artifact.getVersion() == null) {
            throw new ProvisioningException("No version set for overridden artifact");
        }
        if (artifact.getType() == null) {
            throw new ProvisioningException("No type set for overridden artifact");
        }
    }
}
