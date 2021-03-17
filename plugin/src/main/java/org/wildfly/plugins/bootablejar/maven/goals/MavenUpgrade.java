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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
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

final class MavenUpgrade {

    private final Map<String, FeaturePack> dependencies = new HashMap<>();
    private final Map<String, FeaturePack> topLevels = new HashMap<>();
    private final AbstractBuildBootableJarMojo mojo;
    private final ProvisioningConfig config;
    private final Map<ProducerSpec, String> producerToGAC = new HashMap<>();
    private final Map<String, FeaturePackLocation.FPID> gACToProducer = new HashMap<>();
    private final ProvisioningManager pm;
    private ScannedModules modules;

    MavenUpgrade(AbstractBuildBootableJarMojo mojo, ProvisioningConfig config, ProvisioningManager pm)
            throws MavenUniverseException, ProvisioningException, MojoExecutionException {
        this.mojo = mojo;
        this.config = config;
        this.pm = pm;
        for (FeaturePackConfig cfg : config.getFeaturePackDeps()) {
            FeaturePack fp = toFeaturePack(cfg.getLocation(), pm);
            if (fp == null) {
                throw new ProvisioningException("Invalid location " + cfg.getLocation());
            }
            topLevels.put(fp.getGAC(), fp);
        }

        Map<String, FeaturePack> explicitDependencies = new HashMap<>();
        for (Entry<FeaturePack, FeaturePackLocation> entry : mojo.resolvedLocations.entrySet()) {
            FeaturePackLocation location = entry.getValue();
            FeaturePack original = entry.getKey();
            if (original.isDependency()) {
                FeaturePack fp = toFeaturePack(location, pm);
                if (fp == null) {
                    throw new ProvisioningException("Invalid location " + location);
                }
                explicitDependencies.put(fp.getGAC(), fp);
            }
        }

        // Resolve the FP to retrieve dependencies as expressed in fp spec.
        Map<String, Path> resolvedFeaturePacks = new HashMap<>();
        for (FeaturePack fp : topLevels.values()) {
            resolvedFeaturePacks.put(fp.getGAC(), mojo.resolveMaven(fp));
        }
        for (FeaturePack fp : explicitDependencies.values()) {
            resolvedFeaturePacks.put(fp.getGAC(), mojo.resolveMaven(fp));
        }
        mojo.debug("Top level feature-packs: %s", topLevels);
        mojo.debug("Explicit dependencies feature-packs: %s", explicitDependencies);
        mojo.debug("Resolved feature-packs: %s", resolvedFeaturePacks);
        for (Entry<String, Path> entry : resolvedFeaturePacks.entrySet()) {
            FeaturePackSpec spec = FeaturePackDescriber.readSpec(entry.getValue());
            producerToGAC.put(spec.getFPID().getProducer(), entry.getKey());
            gACToProducer.put(entry.getKey(), spec.getFPID());
            List<FeaturePackConfig> allDeps = new ArrayList<>();
            for (FeaturePackConfig cfg : spec.getFeaturePackDeps()) {
                allDeps.add(cfg);
            }
            for (FeaturePackConfig cfg : spec.getTransitiveDeps()) {
                allDeps.add(cfg);
            }
            for (FeaturePackConfig cfg : allDeps) {
                FeaturePack fp = toFeaturePack(cfg.getLocation(), pm);
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
        mojo.debug("FP dependencies %s", dependencies);
    }

    private Map<String, String> getOriginalVersions() throws ProvisioningException, MojoExecutionException {
        return getScannedModules().getProvisionedArtifacts();
    }

    private Map<String, String> getOriginalVersions(String producer) throws ProvisioningException, MojoExecutionException {
        return getScannedModules().getProvisionedArtifacts(producer);
    }

    private ScannedModules getScannedModules() throws ProvisioningException, MojoExecutionException {
        if (modules == null) {
            modules = ScannedModules.scanProvisionedArtifacts(pm, config);
        }
        return modules;
    }

    void dumpArtifacts(Path file) throws ProvisioningException, MojoExecutionException, IOException {
        Map<String, Map<String, Map<String, String>>> perFeaturePack = getScannedModules().getPerFeaturePackArtifacts();
        StringBuilder builder = new StringBuilder();
        builder.append("<all-artifacts>").append(System.lineSeparator());

        // Add feature-pack dependencies
        builder.append("  <galleon-feature-pack-dependencies>").append(System.lineSeparator());
        for (FeaturePack fp : dependencies.values()) {
            builder.append("    <feature-pack-dependency>").append(System.lineSeparator());
            builder.append("      <groupId>").append(fp.getGroupId()).append("</groupId>").append(System.lineSeparator());
            builder.append("      <artifactId>").append(fp.getArtifactId()).append("</artifactId>").append(System.lineSeparator());
            if (fp.getClassifier() != null && !fp.getClassifier().isEmpty()) {
                builder.append("      <classifier>").append(fp.getClassifier()).append("</classifier>").append(System.lineSeparator());
            }
            builder.append("      <version>").append(fp.getVersion()).append("</version>").append(System.lineSeparator());
            builder.append("      <type>").append(fp.getType()).append("</type>").append(System.lineSeparator());
            builder.append("    </feature-pack-dependency>").append(System.lineSeparator());
        }
        builder.append("  </galleon-feature-pack-dependencies>").append(System.lineSeparator());
        builder.append("  <jboss-modules-runtime>").append(System.lineSeparator());
        Artifact jbossModules = AbstractBuildBootableJarMojo.getArtifact(getScannedModules().getModuleRuntime());
        builder.append("    <groupId>").append(jbossModules.getGroupId()).append("</groupId>").append(System.lineSeparator());
        builder.append("    <artifactId>").append(jbossModules.getArtifactId()).append("</artifactId>").append(System.lineSeparator());
        if (jbossModules.getClassifier() != null && !jbossModules.getClassifier().isEmpty()) {
            builder.append("    <classifier>").append(jbossModules.getClassifier()).append("</classifier>").append(System.lineSeparator());
        }
        builder.append("    <version>").append(jbossModules.getVersion()).append("</version>").append(System.lineSeparator());
        builder.append("    <type>").append(jbossModules.getType()).append("</type>").append(System.lineSeparator());
        builder.append("  </jboss-modules-runtime>").append(System.lineSeparator());
        builder.append("  <feature-packs>").append(System.lineSeparator());
        for (Entry<String, Map<String, Map<String, String>>> fp : perFeaturePack.entrySet()) {
            builder.append("    <feature-pack producer=\"").append(fp.getKey()).append("\" >").append(System.lineSeparator());
            builder.append("      <modules>").append(System.lineSeparator());
            for (Entry<String, Map<String, String>> module : fp.getValue().entrySet()) {
                builder.append("        <module name=\"").append(module.getKey()).append("\">").append(System.lineSeparator());
                for (String s : module.getValue().values()) {
                    Artifact a = AbstractBuildBootableJarMojo.getArtifact(s);
                    builder.append("          <artifact>").append(System.lineSeparator());
                    builder.append("            <groupId>").append(a.getGroupId()).append("</groupId>").append(System.lineSeparator());
                    builder.append("            <artifactId>").append(a.getArtifactId()).append("</artifactId>").append(System.lineSeparator());
                    if (a.getClassifier() != null && !a.getClassifier().isEmpty()) {
                        builder.append("            <classifier>").append(a.getClassifier()).append("</classifier>").append(System.lineSeparator());
                    }
                    builder.append("            <version>").append(a.getVersion()).append("</version>").append(System.lineSeparator());
                    builder.append("            <type>").append(a.getType()).append("</type>").append(System.lineSeparator());
                    builder.append("          </artifact>").append(System.lineSeparator());
                }
                builder.append("        </module>").append(System.lineSeparator());
            }
            builder.append("      </modules>").append(System.lineSeparator());
            builder.append("    </feature-pack>").append(System.lineSeparator());
        }
        builder.append("  </feature-packs>").append(System.lineSeparator());
        builder.append("</all-artifacts>").append(System.lineSeparator());
        Files.write(file, builder.toString().getBytes("UTF-8"));
    }

    private static String getOriginalArtifactVersion(OverriddenArtifact a, Map<String, String> originalArtifactVersions) {
        StringBuilder keyBuilder = new StringBuilder();
        keyBuilder.append(a.getGroupId()).append(":").append(a.getArtifactId());
        if (a.getClassifier() != null && !a.getClassifier().isEmpty()) {
            keyBuilder.append("::").append(a.getClassifier());
        }
        String key = keyBuilder.toString();
        String value = originalArtifactVersions.get(key);
        if (value == null) {
            return null;
        }
        Artifact artifact = AbstractBuildBootableJarMojo.getArtifact(value);
        return artifact.getVersion();
    }

    ProvisioningConfig upgrade() throws MojoExecutionException, ProvisioningDescriptionException, ProvisioningException {
        if (mojo.overriddenServerArtifacts.isEmpty() && mojo.resolvedLocations.isEmpty()) {
            return config;
        }
        Map<String, String> originalVersions = getOriginalVersions();
        List<FeaturePack> featurePackDependencies = new ArrayList<>();
        List<OverriddenArtifact> artifactDependencies = new ArrayList<>();
        Map<String, OverriddenArtifact> allArtifacts = new HashMap<>();
        for (OverriddenArtifact a : mojo.overriddenServerArtifacts) {
            if (a.getGroupId() == null || a.getArtifactId() == null) {
                throw new MojoExecutionException("Invalid Artifact , groupId and artifactId are required");
            }
            String key = a.getGAC();
            if (allArtifacts.containsKey(key)) {
                throw new MojoExecutionException("Artifact " + key + " is present more than once in the global overridden artifacts. Must be unique.");
            } else {
                allArtifacts.put(key, a);
            }
            // Is it a potential feature-pack
            if (dependencies.containsKey(key)) {
                Artifact fpArtifact = mojo.artifactVersions.getFeaturePackArtifact(a.getGroupId(), a.getArtifactId(), a.getClassifier());
                if (fpArtifact == null) {
                    throw new MojoExecutionException("No version for Galleon feature-pack " + a.getGAC());
                } else {
                    checkScope(fpArtifact);
                    FeaturePack dep = dependencies.get(key);
                    DefaultArtifactVersion orig = new DefaultArtifactVersion(dep.getVersion());
                    DefaultArtifactVersion overriddenVersion = new DefaultArtifactVersion(fpArtifact.getVersion());
                    int compared = orig.compareTo(overriddenVersion);
                    if (compared > 0) {
                        if (mojo.warnArtifactDowngrade) {
                            mojo.getLog().warn("[UPDATE] Downgrading dependency " + key + " from " + dep.getVersion() + " to " + fpArtifact.getVersion());
                        }
                    } else {
                        if (compared == 0) {
                            mojo.getLog().warn("[UPDATE] Dependency " + key + " will be not upgraded, already at version: " + fpArtifact.getVersion());
                        }
                    }
                    if (compared != 0) {
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
                    addArtifact(a, mavenArtifact, originalVersions, artifactDependencies, null);
                }
            }
        }
        Map<String, List<OverriddenArtifact>> perFeaturePack = new HashMap<>();
        for (Entry<FeaturePack, FeaturePackLocation> entry : mojo.resolvedLocations.entrySet()) {
            FeaturePack fp = entry.getKey();
            String gac = fp.getGAC();
            FeaturePackLocation.FPID fpid = gACToProducer.get(gac);
            // A universe base FPL.
            if (fpid == null) {
                fpid = entry.getValue().getFPID();
            }
            String producerName = fpid.getProducer().getName();
            Map<String, String> originalversionsForProducer = getOriginalVersions(producerName);
            Map<String, OverriddenArtifact> seenArtifacts = new HashMap<>();
            for (OverriddenArtifact o : fp.getOverridenArtifacts()) {
                String key = o.getGAC();
                if (seenArtifacts.containsKey(key)) {
                    throw new MojoExecutionException("Artifact " + key + " is present more than once in the overridden artifacts of  " + gac + ". Must be unique.");
                } else {
                    seenArtifacts.put(key, o);
                }
                if (o.getGroupId() == null || o.getArtifactId() == null) {
                    throw new MojoExecutionException("Invalid Artifact , groupId and artifactId are required");
                }
                Artifact mavenArtifact = mojo.artifactVersions.getArtifact(o);
                if (mavenArtifact == null) {
                    throw new MojoExecutionException("No version for artifact " + o.getGAC());
                }
                List<OverriddenArtifact> lst = perFeaturePack.get(producerName);
                if (lst == null) {
                    lst = new ArrayList<>();
                    perFeaturePack.put(producerName, lst);
                }
                addArtifact(o, mavenArtifact, originalversionsForProducer, lst, producerName);
            }
        }

        if (!artifactDependencies.isEmpty() || !featurePackDependencies.isEmpty() || !perFeaturePack.isEmpty()) {
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
            StringBuilder artifactsOption = new StringBuilder();
            if (mojo.pluginOptions.containsKey("jboss-overridden-artifacts")) {
                mojo.getLog().warn("[UPDATE] jboss-overridden-artifacts plugin option already set, any "
                        + "specified artifact upgrade will be not applied.");
            } else {
                // Global upgrade
                if (!artifactDependencies.isEmpty()) {
                    mojo.getLog().info("[UPDATE] Overriding server artifacts globally with:");
                    String updates = toOptionValue(artifactDependencies);
                    advertiseArtifactsUpgrade(artifactDependencies);
                    artifactsOption.append(updates);
                }
                // per feature-pack
                if (!perFeaturePack.isEmpty()) {
                    for (Entry<String, List<OverriddenArtifact>> entry : perFeaturePack.entrySet()) {
                        List<OverriddenArtifact> artifacts = entry.getValue();
                        String producer = entry.getKey();
                        mojo.getLog().info("[UPDATE] Overriding server artifacts from " + producer + " with:");
                        String updates = toOptionValue(artifacts);
                        advertiseArtifactsUpgrade(artifacts);
                        artifactsOption.append("@").append(producer).append("=").append(updates);
                    }
                }
                if (artifactsOption.length() != 0) {
                    c.addOption("jboss-overridden-artifacts", artifactsOption.toString());
                }
            }
            return c.build();
        } else {
            return config;
        }
    }

    private void advertiseArtifactsUpgrade(List<OverriddenArtifact> artifactDependencies) {
        for (OverriddenArtifact update : artifactDependencies) {
            mojo.getLog().info("[UPDATE]  " + update.getGroupId() + ":" + update.getArtifactId() + ":"
                    + (update.getClassifier() == null ? "" : update.getClassifier() + ":")
                    + update.getVersion() + (update.getType() == null ? "" : ":" + update.getType()));
        }
    }

    private void addArtifact(OverriddenArtifact a, Artifact mavenArtifact,
            Map<String, String> originalVersions, List<OverriddenArtifact> artifactDependencies, String producer) throws MojoExecutionException {
        checkScope(mavenArtifact);
        if (a.getVersion() == null) {
            a.setVersion(mavenArtifact.getVersion());
        }
        if (a.getType() == null) {
            a.setType(mavenArtifact.getType());
        }
        String originalVersion = getOriginalArtifactVersion(a, originalVersions);
        if (originalVersion == null) {
            throw new MojoExecutionException("Overridden artifact " + a.getGAC() + " not known in " +
                    (producer == null ? "provisioned feature-packs" : producer));
        }
        DefaultArtifactVersion orig = new DefaultArtifactVersion(originalVersion);
        DefaultArtifactVersion overriddenVersion = new DefaultArtifactVersion(a.getVersion());
        int compared = orig.compareTo(overriddenVersion);
        if (compared > 0) {
            if (mojo.warnArtifactDowngrade) {
                mojo.getLog().warn("[UPDATE] Downgrading artifact " + a.getGAC() + " from " + originalVersion + " to " + a.getVersion());
            }
        } else {
            if (compared == 0) {
                mojo.getLog().warn("[UPDATE] Artifact " + a.getGAC() + " is already at version " + a.getVersion() + ", will be not upgraded.");
            }
        }
        if (compared != 0) {
            artifactDependencies.add(a);
        }
    }

    void checkScope(Artifact a) {
        if (!"provided".equals(a.getScope())) {
            mojo.getLog().warn("[UPDATE] Overridden artifact " + a.getGroupId() +":"+ a.getArtifactId()+
                    (a.getClassifier() == null ? "" : ":" + a.getClassifier()) + ":" + a.getVersion() + " is not of provided scope.");
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

    private FeaturePack toFeaturePack(FeaturePackLocation location, ProvisioningManager pm) throws MojoExecutionException {
        FeaturePack fp;
        validateFPL(location);
        if (location.isMavenCoordinates()) {
            fp = getFeaturePack(location.toString());
        } else {
            fp = getFeaturePack(location, pm);
        }
        return fp;
    }

    private static void validateFPL(FeaturePackLocation fpl) throws MojoExecutionException {
        if (fpl.getUniverse() == null || fpl.getProducer() == null) {
            throw new MojoExecutionException("Invalid feature-pack location format: " + fpl);
        }
    }

    String getMavenFeaturePack(FeaturePackLocation.FPID location) {
        String gac = producerToGAC.get(location.getProducer());
        if (gac == null) {
            return location.toString();
        } else {
            return gac + ":" + location.getBuild();
        }
    }

    private FeaturePack getFeaturePack(FeaturePackLocation location, ProvisioningManager pm) {
        try {
            Channel channel = pm.getLayoutFactory().getUniverseResolver().getChannel(location);
            if (channel instanceof MavenChannel) {
                MavenChannel mavenChannel = (MavenChannel) channel;
                FeaturePack fp = new FeaturePack();
                fp.setGroupId(mavenChannel.getFeaturePackGroupId());
                fp.setArtifactId(mavenChannel.getFeaturePackArtifactId());
                String build = location.getBuild();
                if (build == null) {
                    build = mavenChannel.getLatestBuild(location);
                }
                fp.setVersion(build);
                return fp;
            }
        } catch (ProvisioningException ex) {
            // OK, invalid channel, can occurs for non registered FP that are referenced from GAV.
            mojo.debug("Invalid channel for %s, the feature-pack is not known in the universe, skipping it.", location);
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
