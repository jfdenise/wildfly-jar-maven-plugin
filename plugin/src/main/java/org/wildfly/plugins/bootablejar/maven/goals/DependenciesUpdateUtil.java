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

        private DependenciesState(Set<FeaturePackUpdatePlan> updates, Map<ProducerSpec, ProvisioningPlan> plans, ProvisioningConfig config) throws ProvisioningDescriptionException {
            for (FeaturePackUpdatePlan fp : updates) {
                globalPlan.update(fp);
            }
            this.plans = plans;
            this.config = config;
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
    static DependenciesState checkUpdates(AbstractBuildBootableJarMojo mojo)
            throws MojoExecutionException, IOException, ProvisioningException {
        Map<ProducerSpec, ProvisioningPlan> plans = new HashMap<>();
        Path home = Files.createTempDirectory("wildfly-bootable-jar-update");
        DependenciesUpdateConfig actualUpdate = mojo.dependenciesUpdate == null ? new DependenciesUpdateConfig() : mojo.dependenciesUpdate;
        home.toFile().deleteOnExit();
        ProvisioningConfig config;
        Set<FeaturePackUpdatePlan> updates = new HashSet<>();
        try (ProvisioningManager pm = ProvisioningManager.builder().addArtifactResolver(mojo.artifactResolver)
                .setInstallationHome(home)
                .setLogTime(false)
                .setRecordState(false)
                .build()) {
            config = mojo.buildGalleonConfig(pm).buildConfig();
            try (ProvisioningLayout<?> layout = pm.getLayoutFactory().newConfigLayout(config)) {

                // First case, check for updates of top level FPs.
                List<FeaturePackUpdatePlan> toUpdate = new ArrayList<>();
                List<String> updated = new ArrayList<>();
                for (FeaturePackConfig fpc : config.getFeaturePackDeps()) {
                    if (actualUpdate.getProducers().isEmpty() || actualUpdate.getProducers().contains(fpc.getLocation().getProducer().toString())) {
                        ProvisioningPlan plan = layout.getUpdates(fpc.getLocation().getProducer());
                        updated.add(fpc.getLocation().getProducer().toString());
                        if (plan.isEmpty()) {
                            // Phase 2 we can check for update for dependencies
                            ProvisioningPlan p = layout.getUpdates(true);
                            updates.addAll(p.getUpdates());
                            plans.put(fpc.getLocation().getProducer(), p);
                        } else {
                            toUpdate.addAll(plan.getUpdates());
                        }
                    }
                }
                if (!toUpdate.isEmpty()) {
                    StringBuilder builder = new StringBuilder();
                    builder.append("Some of the feature-packs in use are not updated "
                            + " to the latest version, you must first update your pom.xml file to reference:\n");
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
                        ex.append("Producer " + f + " not found in the set of producers to update\n");
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
