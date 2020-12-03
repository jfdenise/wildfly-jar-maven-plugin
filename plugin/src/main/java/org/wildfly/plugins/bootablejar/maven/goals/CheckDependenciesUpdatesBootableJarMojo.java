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
import java.util.Map.Entry;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.layout.ProvisioningPlan;
import org.jboss.galleon.layout.FeaturePackUpdatePlan;
import org.jboss.galleon.maven.plugin.util.MavenArtifactRepositoryManager;
import org.jboss.galleon.universe.FeaturePackLocation;
import org.wildfly.plugins.bootablejar.maven.common.MavenRepositoriesEnricher;

/**
 * Check if the dependencies of the FPs to provision can be updated.
 *
 * @author jfdenise
 */
@Mojo(name = "check-dependencies-update", requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, defaultPhase = LifecyclePhase.INITIALIZE)
public class CheckDependenciesUpdatesBootableJarMojo extends AbstractBuildBootableJarMojo {

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().debug(String.format("Skipping run of %s:%s", project.getGroupId(), project.getArtifactId()));
            return;
        }

        MavenRepositoriesEnricher.enrich(session, project, repositories);
        artifactResolver = offline ? new MavenArtifactRepositoryManager(repoSystem, repoSession)
                : new MavenArtifactRepositoryManager(repoSystem, repoSession, repositories);
        try {
            DependenciesUpdateUtil.DependenciesState state = DependenciesUpdateUtil.checkUpdates(this);
            if (state.getPlans().isEmpty()) {
                getLog().info("No update available.");
            } else {
                for (Entry<FeaturePackLocation.ProducerSpec, ProvisioningPlan> entry : state.getPlans().entrySet()) {
                    ProvisioningPlan plan = entry.getValue();
                    FeaturePackLocation.ProducerSpec producer = entry.getKey();
                    if (plan.isEmpty()) {
                        getLog().info("No update available for " + producer);
                    } else {
                        getLog().info("Updates exist for " + producer);
                        for (FeaturePackUpdatePlan p : plan.getUpdates()) {
                            getLog().info("  " + p.getInstalledLocation() + " ==> " + p.getNewLocation());
                        }
                    }
                }
            }
        } catch (IOException | ProvisioningException ex) {
            throw new MojoExecutionException(ex.toString(), ex);
        }
    }
}
