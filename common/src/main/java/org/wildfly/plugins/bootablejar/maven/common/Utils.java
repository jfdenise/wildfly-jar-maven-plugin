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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.ProvisioningManager;
import org.jboss.galleon.config.ConfigId;
import org.jboss.galleon.config.FeaturePackConfig;
import org.jboss.galleon.config.ProvisioningConfig;
import org.jboss.galleon.layout.FeaturePackLayout;
import org.jboss.galleon.layout.ProvisioningLayout;
import org.jboss.galleon.universe.FeaturePackLocation;
import org.jboss.galleon.util.IoUtils;
import static org.wildfly.plugins.bootablejar.maven.common.Constants.BOOTABLE_SUFFIX;
import static org.wildfly.plugins.bootablejar.maven.common.Constants.HEALTH;
import static org.wildfly.plugins.bootablejar.maven.common.Constants.JAR;
import static org.wildfly.plugins.bootablejar.maven.common.Constants.MP_HEALTH;
import static org.wildfly.plugins.bootablejar.maven.common.Constants.STANDALONE;
import static org.wildfly.plugins.bootablejar.maven.common.Constants.STANDALONE_XML;
import org.wildfly.security.manager.WildFlySecurityManager;

/**
 * @author jdenise
 */
public class Utils {

    public static class ProvisioningSpecifics {

        private final boolean isMicroprofile;
        private final String healthLayer;

        ProvisioningSpecifics(Set<String> allLayers) {
            if (allLayers.contains(MP_HEALTH)) {
                healthLayer = MP_HEALTH;
                isMicroprofile = true;
            } else {
                if (allLayers.contains(HEALTH)) {
                    healthLayer = HEALTH;
                } else {
                    healthLayer = null;
                }
                isMicroprofile = false;
            }
        }

        public String getHealthLayer() {
            return healthLayer;
        }
    }

    private static final Pattern WHITESPACE_IF_NOT_QUOTED = Pattern.compile("(\\S+\"[^\"]+\")|\\S+");

    public static String getBootableJarPath(String jarFileName, MavenProject project, String goal) throws MojoExecutionException {
        String jarName = jarFileName;
        if (jarName == null) {
            String finalName = project.getBuild().getFinalName();
            jarName = finalName + "-" + BOOTABLE_SUFFIX + "." + JAR;
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

    public static ProvisioningSpecifics getSpecifics(List<FeaturePack> fps, ProvisioningManager pm) throws ProvisioningException, IOException {
        return new ProvisioningSpecifics(getAllLayers(fps, pm));
    }

    private static Set<String> getAllLayers(List<FeaturePack> fps, ProvisioningManager pm) throws ProvisioningException, IOException {
        Set<String> allLayers = new HashSet<>();
        for (FeaturePack fp : fps) {
            final FeaturePackLocation fpl;
            if (fp.getNormalizedPath() != null) {
                fpl = pm.getLayoutFactory().addLocal(fp.getNormalizedPath(), false);
            } else if (fp.getGroupId() != null && fp.getArtifactId() != null) {
                String coords = fp.getMavenCoords();
                fpl = FeaturePackLocation.fromString(coords);
            } else {
                fpl = FeaturePackLocation.fromString(fp.getLocation());
            }
            ProvisioningConfig pConfig = ProvisioningConfig.builder().
                    addFeaturePackDep(FeaturePackConfig.builder(fpl).build()).build();
            try (ProvisioningLayout<FeaturePackLayout> layout = pm.
                    getLayoutFactory().newConfigLayout(pConfig)) {
                allLayers.addAll(getAllLayers(layout));
            }
        }
        return allLayers;
    }

    private static Set<String> getAllLayers(ProvisioningLayout<FeaturePackLayout> pLayout)
            throws ProvisioningException, IOException {
        Set<String> layers = new HashSet<>();
        for (FeaturePackLayout fp : pLayout.getOrderedFeaturePacks()) {
            for (ConfigId layer : fp.loadLayers()) {
                layers.add(layer.getName());
            }
        }
        return layers;
    }

    public static void readProperties(Path propsFile, Map<String, String> propsMap) throws Exception {
        try (BufferedReader reader = Files.newBufferedReader(propsFile)) {
            String line = reader.readLine();
            while (line != null) {
                line = line.trim();
                if (!line.isEmpty() && line.charAt(0) != '#') {
                    final int i = line.indexOf('=');
                    if (i < 0) {
                        throw new Exception("Failed to parse property " + line + " from " + propsFile);
                    }
                    propsMap.put(line.substring(0, i), line.substring(i + 1));
                }
                line = reader.readLine();
            }
        }
    }

    // Get Artifact, syntax comply with WildFly feature-pack versions file.
    public static Artifact getArtifact(String str) {
        final String[] parts = str.split(":");
        final String groupId = parts[0];
        final String artifactId = parts[1];
        String version = parts[2];
        String classifier = parts[3];
        String extension = parts[4];

        return new DefaultArtifact(groupId, artifactId, version,
                "provided", extension, classifier,
                new DefaultArtifactHandler(extension));
    }

    public static Properties loadProperties(PluginContext ctx, String propertiesFile) throws Exception {
        File f = new File(propertiesFile);
        Path filePath = resolvePath(ctx.getProject(), f.toPath());
        if (Files.notExists(filePath)) {
            throw new RuntimeException("Cli properties file " + filePath + " doesn't exist");
        }
        final Properties props = new Properties();
        try (InputStreamReader inputStreamReader = new InputStreamReader(new FileInputStream(filePath.toFile()),
                StandardCharsets.UTF_8)) {
            props.load(inputStreamReader);
        } catch (IOException e) {
            throw new Exception(
                    "Failed to load properties from " + propertiesFile + ": " + e.getLocalizedMessage());
        }
        for (String key : props.stringPropertyNames()) {
            WildFlySecurityManager.setPropertyPrivileged(key, props.getProperty(key));
        }
        return props;
    }

    public static Path resolvePath(MavenProject project, Path path) {
        if (!path.isAbsolute()) {
            path = Paths.get(project.getBasedir().getAbsolutePath()).resolve(path);
        }
        return path;
    }

    public static void copyExtraContent(PluginContext ctx) throws Exception {
        for (String path : ctx.getExtraServerContentDirs()) {
            Path extraContent = Paths.get(path);
            extraContent = Utils.resolvePath(ctx.getProject(), extraContent);
            if (Files.notExists(extraContent)) {
                throw new Exception("Extra content dir " + extraContent + " doesn't exist");
            }
            // Check for the presence of a standalone.xml file
            warnExtraConfig(ctx, extraContent);
            IoUtils.copy(extraContent, ctx.getJBossHome());
        }

    }

    private  static void warnExtraConfig(PluginContext ctx, Path extraContentDir) {
        Path config = extraContentDir.resolve(STANDALONE).resolve("configurations").resolve(STANDALONE_XML);
        if (Files.exists(config)) {
            ctx.getLog().warn("The file " + config + " overrides the Galleon generated configuration, "
                    + "un-expected behavior can occur when starting the server");
        }
    }

    public static void deleteDir(Path root) {
        if (root == null || Files.notExists(root)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    try {
                        Files.delete(file);
                    } catch (IOException ex) {
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e)
                        throws IOException {
                    if (e != null) {
                        // directory iteration failed
                        throw e;
                    }
                    try {
                        Files.delete(dir);
                    } catch (IOException ex) {
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
        }
    }
}
