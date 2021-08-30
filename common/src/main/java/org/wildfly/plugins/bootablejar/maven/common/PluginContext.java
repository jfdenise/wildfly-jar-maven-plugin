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
package org.wildfly.plugins.bootablejar.maven.common;

import java.io.UnsupportedEncodingException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.shared.artifact.ArtifactCoordinate;
import org.codehaus.plexus.configuration.PlexusConfigurationException;
import org.apache.maven.project.MavenProject;
import org.jboss.galleon.universe.maven.MavenUniverseException;
import org.wildfly.plugins.bootablejar.maven.upgrade.MavenProjectArtifactVersions;

/**
 *
 * @author jdenise
 */
public interface PluginContext {
    public MavenProject getProject();
    public String retrievePluginVersion() throws PlexusConfigurationException, MojoExecutionException;
    public Path resolveArtifact(String groupId, String artifactId, String classifier, String version) throws UnsupportedEncodingException,
            PlexusConfigurationException, MojoExecutionException;
    public Path getJBossHome();
    public Log getLog();
    public Level disableLog();
    public void enableLog(Level level);
    public void debug(String msg, Object... args);
    public Path resolveMaven(ArtifactCoordinate coordinate) throws MavenUniverseException;
    public MavenProjectArtifactVersions getArtifactVersions();
    public List<OverriddenArtifact> getOverriddenServerArtifacts();
    public Map<String, String> getPluginOptions();
    public boolean isWarnForArtifactDowngradeDisabled();
    public BootLoggingConfiguration getBootLoggingConfiguration();
    public boolean isDisplayCliScriptsOutputEnabled();
    public List<String> getExtraServerContentDirs();
}
