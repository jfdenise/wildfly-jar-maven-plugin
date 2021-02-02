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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.apache.maven.plugin.MojoExecutionException;
import org.jboss.galleon.ProvisioningDescriptionException;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.layout.ProvisioningPlan;
import org.jboss.galleon.layout.FeaturePackUpdatePlan;
import org.jboss.galleon.ProvisioningManager;
import org.jboss.galleon.config.FeaturePackConfig;
import org.jboss.galleon.config.ProvisioningConfig;
import org.jboss.galleon.layout.ProvisioningLayout;
import org.jboss.galleon.runtime.FeaturePackRuntime;
import org.jboss.galleon.runtime.ProvisioningRuntime;
import org.jboss.galleon.universe.Channel;
import org.jboss.galleon.universe.FeaturePackLocation;
import org.jboss.galleon.universe.FeaturePackLocation.ProducerSpec;

/**
 * Check if the dependencies of the FPs to provision can be updated.
 *
 * @author jfdenise
 */
class DependenciesUpdateUtil {

    static class DependenciesState {

        private final Map<ProducerSpec, ProvisioningPlan> plans;
        private final ProvisioningPlan globalPlan = ProvisioningPlan.builder();
        private final ProvisioningConfig config;
        private final boolean disabled;

        private DependenciesState(Set<FeaturePackUpdatePlan> updates, Map<ProducerSpec, ProvisioningPlan> plans,
                ProvisioningConfig config) throws ProvisioningDescriptionException {
            this(updates, plans, config, false);
        }

        private DependenciesState(Set<FeaturePackUpdatePlan> updates, Map<ProducerSpec, ProvisioningPlan> plans,
                ProvisioningConfig config, boolean disabled) throws ProvisioningDescriptionException {
            for (FeaturePackUpdatePlan fp : updates) {
                globalPlan.update(fp);
            }
            this.plans = plans;
            this.config = config;
            this.disabled = disabled;
        }

        boolean isDisabled() {
            return disabled;
        }
        Map<ProducerSpec, ProvisioningPlan> getPlans() {
            return plans;
        }

        ProvisioningConfig getConfig() {
            return config;
        }

        ProvisioningPlan getGlobalPlan() {
            return globalPlan;
        }
    }

    static DependenciesState checkUpdates(AbstractBuildBootableJarMojo mojo, boolean log)
            throws MojoExecutionException, IOException, ProvisioningException {
        DependenciesUpdateConfig actualUpdate = mojo.featurePackDependenciesUpdate;
        Map<ProducerSpec, ProvisioningPlan> plans = new HashMap<>();
        Path home = Files.createTempDirectory("wildfly-bootable-jar-update");

        home.toFile().deleteOnExit();
        ProvisioningConfig config;
        Set<FeaturePackUpdatePlan> updates = new HashSet<>();
        try (ProvisioningManager pm = ProvisioningManager.builder().addArtifactResolver(mojo.artifactResolver)
                .setInstallationHome(home)
                .setLogTime(false)
                .setRecordState(false)
                .build()) {
            config = mojo.buildGalleonConfig(pm, log).buildConfig();

            try (ProvisioningLayout<?> layout = pm.getLayoutFactory().newConfigLayout(config); ProvisioningRuntime rt = pm.getRuntime(config)) {

                // This is the plan for all top level FP present in the config
                ProvisioningPlan fullPlan = layout.getUpdates(true);
                // First case, check for updates of top level FPs.
                List<FeaturePackUpdatePlan> toUpdate = new ArrayList<>();
                List<String> updated = new ArrayList<>();
                for (FeaturePackConfig fpc : config.getFeaturePackDeps()) {
                    FeaturePackLocation location = fpc.getLocation();
                    if (location.getUniverse() == null || location.getUniverse().getLocation() == null) {
                        // Can't be updated.
                        continue;
                    }
                    // We can ignore an fp if there are some producers provided and it is not part of them.
                    if (!actualUpdate.getProducers().isEmpty() && !actualUpdate.getProducers().contains(location.getProducer().toString())) {
                        continue;
                    }
                    Channel channel = pm.getLayoutFactory().getUniverseResolver().getChannel(location);
                    String latestBuild = channel.getLatestBuild(location);
                    // No build, advertise the latest version
                    if (!location.hasBuild()) {
                        mojo.logUpdate("Checking updates for " + location + "#" + latestBuild, false);
                    } else {
                        // Check that the current build is the latest one.
                        if (!location.getBuild().equals(latestBuild)) {
                            mojo.logUpdate("You must upgrade to the latest release: " + latestBuild + " of "
                                    + location.getProducer() + " to be able to upgrade, updates are ignored.", true);
                            return new DependenciesState(Collections.emptySet(), Collections.emptyMap(), config, true);
                        }
                    }
                    ProvisioningPlan plan = layout.getUpdates(location.getProducer());
                    updated.add(location.getProducer().toString());
                    if (plan.isEmpty()) {
                        // Phase 2 we can check for update for dependencies
                        FeaturePackRuntime fprt = rt.getFeaturePack(location.getProducer());
                        ProvisioningPlan producerPlan = ProvisioningPlan.builder();
                        for (FeaturePackUpdatePlan pl : fullPlan.getUpdates()) {
                            ProducerSpec producer = pl.getNewLocation().getProducer();
                            for (FeaturePackConfig cfg : fprt.getSpec().getFeaturePackDeps()) {
                                if (cfg.getLocation().getProducer().equals(producer)) {
                                    // Ok, this is a dependency, we can update it.
                                    updates.add(pl);
                                    producerPlan.update(pl);
                                }
                            }
                            for (FeaturePackConfig cfg : fprt.getSpec().getTransitiveDeps()) {
                                if (cfg.getLocation().getProducer().equals(producer)) {
                                    // Ok, this is a dependency, we can update it.
                                    updates.add(pl);
                                    producerPlan.update(pl);
                                }
                            }
                        }
                        //updates.addAll(p.getUpdates());
                        plans.put(location.getProducer(), producerPlan);
                        if (producerPlan.getUpdates().isEmpty()) {
                            mojo.logUpdate("  No updates available.", false);
                        } else {
                            for (FeaturePackUpdatePlan p : producerPlan.getUpdates()) {
                                mojo.logUpdate("  " + p.getInstalledLocation() + " ==> " + p.getNewLocation(), false);
                            }
                        }
                    } else {
                        toUpdate.addAll(plan.getUpdates());
                    }
                }
                if (!toUpdate.isEmpty()) {
                    StringBuilder builder = new StringBuilder();
                    builder.append("Update failed. Some of the Galleon feature-packs are not updated "
                            + "to the latest version, you must first upgrade:\n");
                    for (FeaturePackUpdatePlan p : toUpdate) {
                        builder.append(p.getInstalledLocation() + "==>" + p.getNewLocation());
                    }
                    throw new MojoExecutionException(builder.toString());
                }
                StringBuilder ex = null;
                for (String f : actualUpdate.getProducers()) {
                    if (!updated.contains(f)) {
                        if (ex == null) {
                            ex = new StringBuilder();
                        }
                        ex.append("[UPDATE] Producer " + f + " not found in the set of producers to update\n");
                    }
                }
                if (ex != null) {
                    throw new MojoExecutionException(ex.toString());
                }
            }
        }
        return new DependenciesState(updates, plans, config);
    }
}
