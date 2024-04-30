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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.jar.Manifest;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.stream.XMLStreamException;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptorBuilder;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.codehaus.plexus.configuration.PlexusConfigurationException;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.jboss.galleon.Constants;
import org.jboss.galleon.ProvisioningDescriptionException;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.maven.plugin.util.MavenArtifactRepositoryManager;
import org.jboss.galleon.maven.plugin.util.MvnMessageWriter;
import org.jboss.galleon.api.GalleonArtifactCoordinate;
import org.jboss.galleon.api.GalleonBuilder;
import org.jboss.galleon.api.Provisioning;
import org.jboss.galleon.config.ConfigId;
import org.jboss.galleon.api.config.GalleonConfigurationWithLayersBuilder;
import org.jboss.galleon.api.config.GalleonFeaturePackConfig;
import org.jboss.galleon.api.config.GalleonProvisioningConfig;
import org.jboss.galleon.universe.FeaturePackLocation;
import org.jboss.galleon.universe.maven.MavenArtifact;
import org.jboss.galleon.universe.maven.MavenUniverseException;
import org.jboss.galleon.universe.maven.repo.MavenRepoManager;
import org.jboss.galleon.util.IoUtils;
import org.jboss.galleon.util.ZipUtils;
import org.wildfly.channel.UnresolvedMavenArtifactException;
import org.wildfly.plugin.common.PropertyNames;
import org.wildfly.plugin.core.MavenJBossLogger;
import org.wildfly.plugin.tools.PluginProgressTracker;

import org.wildfly.plugins.bootablejar.maven.cli.CLIExecutor;
import org.wildfly.plugins.bootablejar.maven.cli.LocalCLIExecutor;
import org.wildfly.plugins.bootablejar.maven.cli.RemoteCLIExecutor;
import org.wildfly.plugins.bootablejar.maven.common.FeaturePack;
import org.wildfly.plugins.bootablejar.maven.common.MavenRepositoriesEnricher;
import org.wildfly.plugins.bootablejar.maven.common.OverriddenArtifact;
import org.wildfly.plugins.bootablejar.maven.common.Utils;
import org.wildfly.plugins.bootablejar.maven.common.Utils.ProvisioningSpecifics;
import org.wildfly.plugin.tools.bootablejar.BootLoggingConfiguration;
import org.wildfly.plugin.tools.bootablejar.BootableJarSupport;
import static org.wildfly.plugin.tools.bootablejar.BootableJarSupport.BOOTABLE_SUFFIX;
import org.wildfly.plugin.tools.bootablejar.ScannedArtifacts;
import org.wildfly.security.manager.WildFlySecurityManager;

/**
 * Build a bootable JAR containing application and provisioned server
 *
 * @author jfdenise
 */
public abstract class AbstractBuildBootableJarMojo extends AbstractMojo {

    public static final String JAR = "jar";
    public static final String WAR = "war";

    public static final String STANDALONE = "standalone";
    public static final String STANDALONE_XML = "standalone.xml";
    private static final String SERVER_CONFIG = "--server-config";
    private static final String MAVEN_REPO_PLUGIN_OPTION = "jboss-maven-repo";

    private static final String JBOSS_MAVEN_DIST = "jboss-maven-dist";
    private static final String JBOSS_PROVISIONING_MAVEN_REPO = "jboss-maven-provisioning-repo";
    private static final String MAVEN_REPO_LOCAL = "maven.repo.local";
    private static final String PLUGIN_PROVISIONING_FILE = ".wildfly-jar-plugin-provisioning.xml";

    @Component
    RepositorySystem repoSystem;

    @Component
    MavenProjectHelper projectHelper;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    RepositorySystemSession repoSession;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    List<RemoteRepository> repositories;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    MavenSession session;

    /**
     * Arbitrary Galleon options used when provisioning the server. In case you
     * are building a large amount of bootable JAR in the same maven session, it
     * is strongly advised to set 'jboss-fork-embedded' option to 'true' in
     * order to fork Galleon provisioning and CLI scripts execution in dedicated
     * processes. For example:
     * <br/>
     * &lt;plugin-options&gt;<br/>
     * &lt;jboss-fork-embedded&gt;true&lt;/jboss-fork-embedded&gt;<br/>
     * &lt;/plugin-options&gt;
     */
    @Parameter(alias = "plugin-options", required = false)
    Map<String, String> pluginOptions = Collections.emptyMap();

    /**
     * Whether to use offline mode when the plugin resolves an artifact. In
     * offline mode the plugin will only use the local Maven repository for an
     * artifact resolution.
     */
    @Parameter(alias = "offline", defaultValue = "false")
    boolean offline;

    /**
     * Whether to log provisioning time at the end
     */
    @Parameter(alias = "log-time", defaultValue = "false")
    boolean logTime;

    /**
     * A list of Galleon layers to provision. Can be used when
     * feature-pack-location or feature-packs are set.
     */
    @Parameter(alias = "layers", required = false)
    List<String> layers = Collections.emptyList();

    /**
     * A list of Galleon layers to exclude. Can be used when
     * feature-pack-location or feature-packs are set.
     */
    @Parameter(alias = "excluded-layers", required = false)
    List<String> excludedLayers = Collections.emptyList();

    /**
     * Whether to record provisioned state in .galleon directory.
     */
    @Parameter(alias = "record-state", defaultValue = "false")
    boolean recordState;

    /**
     * Project build dir.
     */
    @Parameter(defaultValue = "${project.build.directory}")
    String projectBuildDir;

    /**
     * To make the WAR deployment registered under root resource path ('/').
     */
    @Parameter(alias = "context-root", defaultValue = "true", property = "wildfly.bootable.context.root")
    boolean contextRoot;

    /**
     * The WildFly Galleon feature-pack location to use if no provisioning.xml
     * file found. Can't be used in conjunction with feature-packs.
     */
    @Parameter(alias = "feature-pack-location", required = false,
            property = "wildfly.bootable.fpl")
    String featurePackLocation;

    /**
     * List of CLI execution sessions. An embedded server is started for each CLI session.
     * If a script file is not absolute, it has to be relative to the project base directory.
     * CLI session are configured in the following way:
     * <br/>
     * &lt;cli-sessions&gt;<br/>
     * &lt;cli-session&gt;<br/>
     * &lt;script-files&gt;<br/>
     * &lt;script&gt;../scripts/script1.cli&lt;/script&gt;<br/>
     * &lt;/script-files&gt;<br/>
     * &lt;!-- Expressions resolved during server execution --&gt;<br/>
     * &lt;resolve-expressions&gt;false&lt;/resolve-expressions&gt;<br/>
     * &lt;/cli-session&gt;<br/>
     * &lt;cli-session&gt;<br/>
     * &lt;script-files&gt;<br/>
     * &lt;script&gt;../scripts/script2.cli&lt;/script&gt;<br/>
     * &lt;/script-files&gt;<br/>
     * &lt;properties-file&gt;../scripts/cli.properties&lt;/properties-file&gt;<br/>
     * &lt;/cli-session&gt;<br/>
     * &lt;/cli-sessions&gt;
     */
    @Parameter(alias = "cli-sessions")
    List<CliSession> cliSessions = Collections.emptyList();

    /**
     * Hollow JAR. Create a bootable JAR that doesn't contain application.
     */
    @Parameter(alias = "hollow-jar", property = "wildfly.bootable.hollow")
    boolean hollowJar;

    /**
     * Set to {@code true} if you want the deployment to be skipped, otherwise
     * {@code false}.
     */
    @Parameter(defaultValue = "false", property = "wildfly.bootable.package.skip")
    boolean skip;

    /**
     * By default the generated JAR is ${project.build.finalName}-bootable.jar
     */
    @Parameter(alias = "output-file-name", property = "wildfly.bootable.package.output.file.name")
    String outputFileName;

    /**
     * A list of feature-pack configurations to install, can be combined with
     * layers. Overrides galleon/provisioning.xml file. Can't be used in
     * conjunction with feature-pack-location.
     */
    @Parameter(alias = "feature-packs", required = false)
    List<FeaturePack> featurePacks = Collections.emptyList();

    /**
     * A list of directories to copy content to the provisioned server.
     * If a directory is not absolute, it has to be relative to the project base directory.
     */
    @Parameter(alias = "extra-server-content-dirs", property = "wildfly.bootable.package.extra.server.content.dirs")
    List<String> extraServerContentDirs = Collections.emptyList();

    /**
     * The path to the {@code provisioning.xml} file to use. Note that this cannot be used with the {@code feature-packs}
     * or {@code layers} configuration parameters.
     * If the provisioning file is not absolute, it has to be relative to the project base directory.
     */
    @Parameter(alias = "provisioning-file", property = "wildfly.bootable.provisioning.file", defaultValue = "${project.basedir}/galleon/provisioning.xml")
    private File provisioningFile;

    /**
     * By default executed CLI scripts output is not shown if execution is
     * successful. In order to display the CLI output, set this option to true.
     */
    @Parameter(alias = "display-cli-scripts-output")
    boolean displayCliScriptsOutput;

    /**
     * Overrides the default {@code logging.properties} the container uses when booting.
     * <br/>
     * In most cases this should not be required. The use-case is when the generated {@code logging.properties} causes
     * boot errors or you do not use the logging subsystem and would like to use a custom logging configuration.
     * <br/>
     * An example of a boot error would be using a json  formatted file as a {@code custom-handler}.
     * If the file is not absolute, it has to be relative to the project base directory.
     */
    @Parameter(alias = "boot-logging-config", property = "wildfly.bootable.logging.config")
    private File bootLoggingConfig;

    /**
     * By default, when building a bootable JAR, the plugin extracts build
     * artifacts in the directory 'bootable-jar-build-artifacts'. You can use
     * this property to change this directory name. In most cases
     * this should not be required. The use-case is when building multiple bootable JARs in the same project
     * on Windows Platform. In this case, each execution should have its own directory, the plugin being
     * unable to delete the directory due to some references to JBoss module files.
     */
    @Parameter(alias = "bootable-jar-build-artifacts", property = "wildfly.bootable.jar.build.artifacts", defaultValue = "bootable-jar-build-artifacts")
    private String bootableJarBuildArtifacts;

    /**
     * Deprecated. A list of artifacts that override the one referenced in the WildFly
     * Galleon feature-pack used to build the Bootable JAR. The artifacts
     * present in this list must exist in the project dependencies (with a
     * {@code provided} scope). GroupId and ArtifactId are mandatory.
     * Classifier is required if non null. Version and Type are optional and are
     * retrieved from the project dependencies. Dependencies on Galleon
     * feature-pack can also be referenced from this list. {@code zip} type must be used for Galleon feature-packs.
     * NB: This configuration item can't be used when Channels are in use.<br/>
     *  Example of an override of the {@code io.undertow:undertow-core}
     * artifact:<br/>
     * &lt;overridden-server-artifacts&gt;<br/>
     * &lt;artifact&gt;<br/>
     * &lt;groupId&gt;io.undertow&lt;/groupId&gt;<br/>
     * &lt;artifactId&gt;undertow-core&lt;/artifactId&gt;<br/>
     * &lt;/artifact&gt;<br/>
     * &lt;/overridden-server-artifacts&gt;<br/>
     */
    @Deprecated
    @Parameter(alias = "overridden-server-artifacts")
    List<OverriddenArtifact> overriddenServerArtifacts = Collections.emptyList();

    /**
     * Deprecated. Set this parameter to true in order to retrieve the set of artifacts that can be upgraded.
     * The file `target/bootable-jar-build-artifacts/bootable-jar-server-original-artifacts.xml` is generated.
     * It contains XML elements for the Galleon feature-packs dependencies, JBoss Modules runtime and artifacts.
     * JBoss Modules modules artifacts are grouped by JBoss Modules name.
     * The generated file contains only the artifacts that are provisioned by Galleon.
     * Each artifact version is the one that would get installed when building the Bootable JAR without upgrade.
     */
    @Deprecated
    @Parameter(alias = "dump-original-artifacts", property = "bootable.jar.dump.original.artifacts" , defaultValue = "false")
    boolean dumpOriginalArtifacts;

    /**
     * Deprecated. The plugin prints a warning when an overridden artifact is downgraded (updated to an older version).
     * The version comparison is done based on Maven versioning. This warning can be disabled by setting this parameter to
     * true.
     */
    @Deprecated
    @Parameter(alias = "disable-warn-for-artifact-downgrade", property = "bootable.jar.disable.warn.for.artifact.downgrade", defaultValue = "false")
    boolean disableWarnForArtifactDowngrade;

    /**
     * When calling mvn 'install', the bootable JAR artifact is attached to the project with the classifier 'bootable'. Use this parameter to
     * configure the classifier.
     */
    @Parameter(alias = "install-artifact-classifier", property = "bootable.jar.install.artifact.classifier", defaultValue = BOOTABLE_SUFFIX)
    String installArtifactClassifier;

    /**
     * A list of channels used for resolving artifacts while provisioning.
     * <p>
     * Defining a channel:
     *
     * <pre>
     * <channels>
     *     <channel>
     *         <manifest>
     *             <groupId>org.wildfly.channels</groupId>
     *             <artifactId>wildfly-30.0</artifactId>
     *         </manifest>
     *     </channel>
     *     <channel>
     *         <manifest>
     *             <url>https://example.example.org/channel/30</url>
     *         </manifest>
     *     </channel>
     * </channels>
     * </pre>
     * </p>
     * <p>
     * The {@code wildfly.channels} property can be used pass a comma delimited string for the channels. The channel
     * can be a URL or a Maven GAV. If a Maven GAV is used, the groupId and artifactId are required.
     * <br>
     * Examples:
     *
     * <pre>
     *     -Dwildfly.channels=&quot;https://channels.example.org/30&quot;
     *     -Dwildfly.channels=&quot;https://channels.example.org/30,org.example.channel:updates-30&quot;
     *     -Dwildfly.channels=&quot;https://channels.example.org/30,org.example.channel:updates-30:1.0.2&quot;
     * </pre>
     * </p>
     */
    @Parameter(alias = "channels", property = PropertyNames.CHANNELS)
    List<ChannelConfiguration> channels;

    MavenProjectArtifactVersions artifactVersions;

    private final BootLoggingConfiguration bootLoggingConfiguration = new BootLoggingConfiguration();

    private final List<String> extraLayers = new ArrayList<>();

    private Path wildflyDir;

    private MavenRepoManager artifactResolver;

    private boolean forkCli;
    private ScannedArtifacts scannedArtifacts;

    public Path getJBossHome() {
        return wildflyDir;
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        MavenRepositoriesEnricher.enrich(session, project, repositories);
        if (isChannelsProvisioning()) {
            try {
                artifactResolver = new ChannelMavenArtifactRepositoryManager(channels,
                        repoSystem, repoSession, repositories,
                        getLog(), offline);
            } catch (MalformedURLException | UnresolvedMavenArtifactException ex) {
                throw new MojoExecutionException(ex.getLocalizedMessage(), ex);
            }
        } else {
            artifactResolver = offline ? new MavenArtifactRepositoryManager(repoSystem, repoSession)
                    : new MavenArtifactRepositoryManager(repoSystem, repoSession, repositories);
        }
        if (outputFileName == null) {
            outputFileName = this.project.getBuild().getFinalName() + "-" + BootableJarSupport.BOOTABLE_SUFFIX + "." + JAR;
        }

        artifactVersions = MavenProjectArtifactVersions.getInstance(project);
        validateProjectFile();

        if (isPackageDev()) {
            Path deployments = getDeploymentsDir();
            IoUtils.recursiveDelete(deployments);
            try {
                Files.createDirectory(deployments);
                copyProjectFile(deployments);
            } catch (IOException ex) {
                throw new MojoExecutionException("Fail creating deployments directory ", ex);
            }
            return;
        }
        Path contentRoot = Paths.get(project.getBuild().getDirectory()).resolve(bootableJarBuildArtifacts);
        if (Files.exists(contentRoot)) {
            deleteDir(contentRoot);
        }
        Path jarFile = Paths.get(project.getBuild().getDirectory()).resolve(outputFileName);
        IoUtils.recursiveDelete(contentRoot);

        wildflyDir = contentRoot.resolve("wildfly");
        Path contentDir = contentRoot.resolve("jar-content");
        try {
            Files.createDirectories(contentRoot);
            Files.createDirectories(contentDir);
            Files.deleteIfExists(jarFile);
        } catch (IOException ex) {
            throw new MojoExecutionException("Packaging wildfly failed", ex);
        }
        try {
            provisionServer(wildflyDir, contentDir.resolve("provisioning.xml"), contentRoot);
            if (artifactResolver instanceof ChannelMavenArtifactRepositoryManager) {
                ((ChannelMavenArtifactRepositoryManager) artifactResolver).done(wildflyDir);
            }
        } catch (ProvisioningException | IOException | XMLStreamException ex) {
            throw new MojoExecutionException("Provisioning failed", ex);
        }

        try {
            // We are forking CLI executions in order to avoid JBoss Modules static references to ModuleLoaders.
            forkCli = Boolean.parseBoolean(pluginOptions.getOrDefault("jboss-fork-embedded", "true"));
            if (forkCli) {
                getLog().info("CLI executions are done in forked process");
            }

            copyExtraContentInternal(wildflyDir, contentDir);
            copyExtraContent(wildflyDir);
            List<String> commands = new ArrayList<>();
            deploy(commands);
            List<String> serverConfigCommands = new ArrayList<>();
            configureCli(serverConfigCommands);
            commands.addAll(serverConfigCommands);
            if (!commands.isEmpty()) {
                executeCliScript(wildflyDir, commands, null, false, "Server configuration", true);
                if (!serverConfigCommands.isEmpty()) {
                    // Store generated commands to file in build artifacts.
                    Path genCliScript = contentRoot.resolve("generated-cli-script.txt");
                    try (BufferedWriter writer = Files.newBufferedWriter(genCliScript, StandardCharsets.UTF_8)) {
                        for (String str : serverConfigCommands) {
                            writer.write(str);
                            writer.newLine();
                        }
                    }
                    getLog().info("Stored CLI script executed to update server configuration in " + genCliScript + " file.");
                }
            }
            userScripts(wildflyDir, cliSessions, true);

            Path loggingFile = copyLoggingFile(contentRoot);
            if (bootLoggingConfig == null) {
                generateLoggingConfig(wildflyDir);
            } else {
                // Copy the user overridden logging.properties
                final Path loggingConfig = resolvePath(bootLoggingConfig.toPath());
                if (Files.notExists(loggingConfig)) {
                    throw new MojoExecutionException(String.format("The bootLoggingConfig %s does not exist.", loggingConfig));
                }
                final Path target = getJBossHome().resolve("standalone").resolve("configuration").resolve("logging.properties");
                Files.copy(loggingConfig, target, StandardCopyOption.REPLACE_EXISTING);
            }
            BootableJarSupport.zipServer(wildflyDir, contentDir);
            buildJar(contentDir, jarFile);
            restoreLoggingFile(loggingFile);
        } catch (Exception ex) {
            if (ex instanceof MojoExecutionException) {
                throw (MojoExecutionException) ex;
            } else if (ex instanceof MojoFailureException) {
                throw (MojoFailureException) ex;
            }
            throw new MojoExecutionException("Packaging wildfly failed", ex);
        } finally {
            // Although cli and embedded are run in their own classloader,
            // the module.path system property has been set and needs to be cleared for
            // in same JVM next execution.
            System.clearProperty("module.path");
        }

        attachJar(jarFile);
    }

    private boolean isChannelsProvisioning() {
        return channels != null && !channels.isEmpty();
    }

    protected boolean isPackageDev() {
        return System.getProperty("dev") != null;
    }

    // Keep a safe copy of logging.properties to be set back into
    // unzipped WildFly dir. That is needed to be able to execute WildFly
    // from the generated artifacts (for investigation purpose) with original
    // logging.properties file content.
    private Path copyLoggingFile(Path contentRoot) throws IOException {
        final Path configDir = getJBossHome().resolve("standalone").resolve("configuration");
        final Path loggingFile = configDir.resolve("logging.properties");
        Path originalLoggingFile = contentRoot.resolve("logging.properties");
        if (Files.exists(loggingFile)) {
            Files.copy(loggingFile, originalLoggingFile, StandardCopyOption.REPLACE_EXISTING);
        }
        return originalLoggingFile;
    }

    private void restoreLoggingFile(Path originalLoggingFile) throws IOException {
        // Replace logging file with original one, keep a copy of the generated one.
        if (Files.exists(originalLoggingFile)) {
            Path configDir = getJBossHome().resolve("standalone").resolve("configuration");
            Path bootableLoggingFile = configDir.resolve("wildfly-jar-generated-logging.properties");
            final Path loggingFile = configDir.resolve("logging.properties");
            Files.copy(loggingFile, bootableLoggingFile, StandardCopyOption.REPLACE_EXISTING);
            Files.copy(originalLoggingFile, loggingFile, StandardCopyOption.REPLACE_EXISTING);
            Files.delete(originalLoggingFile);
        }
    }

    private void copyExtraContent(Path wildflyDir) throws Exception {
        for (String path : extraServerContentDirs) {
            Path extraContent = Paths.get(path);
            extraContent = resolvePath(extraContent);
            if (Files.notExists(extraContent)) {
                throw new Exception("Extra content dir " + extraContent + " doesn't exist");
            }
            // Check for the presence of a standalone.xml file
            warnExtraConfig(extraContent);
            IoUtils.copy(extraContent, wildflyDir);
        }

    }

    private void warnExtraConfig(Path extraContentDir) {
        Path config = extraContentDir.resolve(STANDALONE).resolve("configurations").resolve(STANDALONE_XML);
        if (Files.exists(config)) {
            getLog().warn("The file " + config + " overrides the Galleon generated configuration, "
                    + "un-expected behavior can occur when starting the bootable JAR");
        }
    }

    protected void copyExtraContentInternal(Path wildflyDir, Path contentDir) throws Exception {

    }

    protected boolean updateManifest(Manifest manifest) {
        return false;
    }

    protected void addExtraLayer(String layer) {
        extraLayers.add(layer);
    }

    private void copyProjectFile(Path targetDir) throws IOException, MojoExecutionException {
        if (hollowJar) {
            getLog().info("Hollow jar, No application deployment added to server.");
            return;
        }
        File f = validateProjectFile();

        String fileName = f.getName();
        if (project.getPackaging().equals(WAR) || fileName.endsWith(WAR)) {
            if (contextRoot) {
                fileName = "ROOT." + WAR;
            }
        }
        Files.copy(f.toPath(), targetDir.resolve(fileName));
    }

    protected Path getDeploymentsDir() {
        return Paths.get(project.getBuild().getDirectory()).resolve("deployments");
    }

    protected void configureCli(List<String> commands) {

    }

    protected File validateProjectFile() throws MojoExecutionException {
        File f = getProjectFile();
        if (f == null && !hollowJar) {
            throw new MojoExecutionException("Cannot package without a primary artifact; please `mvn package` prior to invoking wildfly-jar:package from the command-line");
        }
        return f;
    }

    private void userScripts(Path wildflyDir, List<CliSession> sessions, boolean startEmbedded) throws Exception {
        for (CliSession session : sessions) {
            List<String> commands = new ArrayList<>();
            for (String path : session.getScriptFiles()) {
                File f = new File(path);
                Path filePath = resolvePath(f.toPath());
                if (Files.notExists(filePath)) {
                    throw new RuntimeException("Cli script file " + filePath + " doesn't exist");
                }
                try (BufferedReader reader = new BufferedReader(new FileReader(filePath.toFile()))) {
                    String line = reader.readLine();
                    while (line != null) {
                        commands.add(line.trim());
                        line = reader.readLine();
                    }
                }
            }
            if(!commands.isEmpty()) {
                executeCliScript(wildflyDir, commands, session.getPropertiesFile(),
                        session.getResolveExpression(), session.toString(), startEmbedded);
            }
        }
    }

    private void executeCliScript(Path jbossHome, List<String> commands, String propertiesFile,
            boolean resolveExpression, String message, boolean startEmbedded) throws Exception {
        getLog().info("Executing CLI, " + message);
        Properties props = null;
        if (propertiesFile != null) {
            props = loadProperties(propertiesFile);
        }
        try {
            processCLI(jbossHome, commands, resolveExpression, startEmbedded);
        } finally {
            if (props != null) {
                for (String key : props.stringPropertyNames()) {
                    WildFlySecurityManager.clearPropertyPrivileged(key);
                }
            }
        }
    }

    private void generateLoggingConfig(final Path wildflyDir) throws Exception {
        try (CLIExecutor cmdCtx = forkCli ? new RemoteCLIExecutor(wildflyDir, getCLIArtifacts(), this, false)
                : new LocalCLIExecutor(wildflyDir, getCLIArtifacts(), this, false, bootLoggingConfiguration)) {
            try {
                cmdCtx.generateBootLoggingConfig();
            } catch (Exception e) {
                getLog().error("Failed to generate logging configuration: " + cmdCtx.getOutput());
                throw e;
            }
        }
    }

    private void processCLI(Path jbossHome, List<String> commands,
            boolean resolveExpression, boolean startEmbedded) throws Exception {

        List<String> allCommands = new ArrayList<>();
        if (startEmbedded) {
            allCommands.add("embed-server --jboss-home=" + jbossHome + " --std-out=discard");
        }
        for (String line : commands) {
            allCommands.add(line.trim());
        }
        if (startEmbedded) {
            allCommands.add("stop-embedded-server");
        }
        try (CLIExecutor executor = forkCli ? new RemoteCLIExecutor(jbossHome, getCLIArtifacts(), this, resolveExpression)
                : new LocalCLIExecutor(jbossHome, getCLIArtifacts(), this, resolveExpression, bootLoggingConfiguration)) {

            try {
                executor.execute(allCommands);
            } catch (Exception ex) {
                getLog().error("Error executing CLI script " + ex.getLocalizedMessage(), ex);
                getLog().error(executor.getOutput());
                throw ex;
            }
            if (displayCliScriptsOutput) {
                getLog().info(executor.getOutput());
            }
        }
        getLog().info("CLI scripts execution done.");
    }

    private List<Path> getCLIArtifacts() throws MojoExecutionException {
        debug("CLI artifacts %s", scannedArtifacts.getCliArtifacts());
        List<Path> paths = new ArrayList<>();
        paths.add(wildflyDir.resolve("jboss-modules.jar"));
        for (MavenArtifact a : scannedArtifacts.getCliArtifacts()) {
            paths.add(resolveArtifact(a));
        }
        return paths;
    }

    public Level disableLog() {
        Logger l = Logger.getLogger("");
        Level level = l.getLevel();
        // Only disable logging if debug is not ebnabled.
        if (!getLog().isDebugEnabled()) {
            l.setLevel(Level.OFF);
        }
        return level;
    }

    public void enableLog(Level level) {
        Logger l = Logger.getLogger("");
        l.setLevel(level);
    }

    private Path resolvePath(Path path) {
        if (!path.isAbsolute()) {
            path = Paths.get(project.getBasedir().getAbsolutePath()).resolve(path);
        }
        return path;
    }

    private Properties loadProperties(String propertiesFile) throws Exception {
        File f = new File(propertiesFile);
        Path filePath = resolvePath(f.toPath());
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

    private File getProjectFile() {
        if (this.project.getArtifact().getFile() != null) {
            return this.project.getArtifact().getFile();
        }

        String finalName = this.project.getBuild().getFinalName();

        Path candidate = Paths.get(this.projectBuildDir, finalName + "." + this.project.getPackaging());

        if (Files.exists(candidate)) {
            return candidate.toFile();
        }
        return null;
    }

    protected Path getProvisioningFile() {
        return resolvePath(provisioningFile.toPath());
    }

    protected boolean hasLayers() {
        return !layers.isEmpty();
    }

    protected List<String> getLayers() {
        return layers;
    }

    protected List<String> getExcludedLayers() {
        return excludedLayers;
    }

    private GalleonConfig buildFeaturePacksConfig(GalleonBuilder pm, boolean hasLayers,
            ConfigId defaultConfig) throws ProvisioningException, MojoExecutionException {
        GalleonProvisioningConfig.Builder state = GalleonProvisioningConfig.builder();
        ConfigId provisionedConfigId = null;
        for (FeaturePack fp : featurePacks) {

            if (fp.getLocation() == null && (fp.getGroupId() == null || fp.getArtifactId() == null)
                    && fp.getNormalizedPath() == null) {
                throw new MojoExecutionException("Feature-pack location, Maven GAV or feature pack path is missing");
            }

            final FeaturePackLocation fpl;
            if (fp.getNormalizedPath() != null) {
                fpl = pm.addLocal(fp.getNormalizedPath(), false);
            } else if (fp.getGroupId() != null && fp.getArtifactId() != null) {
                String coords = fp.getMavenCoords();
                fpl = FeaturePackLocation.fromString(coords);
            } else {
                fpl = FeaturePackLocation.fromString(fp.getLocation());
            }

            final GalleonFeaturePackConfig.Builder fpConfig = GalleonFeaturePackConfig.builder(fpl);
            fpConfig.setInheritConfigs(false);
            if (fp.isInheritPackages() != null) {
                fpConfig.setInheritPackages(fp.isInheritPackages());
            }

            if (fp.getIncludedDefaultConfig() != null) {
                ConfigId includedConfigId = new ConfigId(STANDALONE, fp.getIncludedDefaultConfig());
                fpConfig.includeDefaultConfig(includedConfigId);
                if (provisionedConfigId == null) {
                    provisionedConfigId = includedConfigId;
                } else {
                    if (!provisionedConfigId.getName().equals(fp.getIncludedDefaultConfig())) {
                        throw new ProvisioningException("Feature-packs are not including the same default config");
                    }
                }
            } else {
                // We don't have an explicit default config and we have no layers, must include the default one.
                if (!hasLayers && provisionedConfigId == null) {
                    provisionedConfigId =defaultConfig;
                    fpConfig.includeDefaultConfig(provisionedConfigId);
                }
            }

            if (!fp.getIncludedPackages().isEmpty()) {
                for (String includedPackage : fp.getIncludedPackages()) {
                    fpConfig.includePackage(includedPackage);
                }
            }
            if (!fp.getExcludedPackages().isEmpty()) {
                for (String excludedPackage : fp.getExcludedPackages()) {
                    fpConfig.excludePackage(excludedPackage);
                }
            }

            state.addFeaturePackDep(fpConfig.build());
        }
        if (hasLayers) {
            getLog().info("Provisioning server configuration based on the set of configured layers");
        } else {
            getLog().info("Provisioning server configuration based on the " + provisionedConfigId.getName() + " default configuration.");
        }
        return hasLayers ? new LayersFeaturePacksConfig(state) : new DefaultFeaturePacksConfig(provisionedConfigId, state);
    }

    private interface GalleonConfig {

        GalleonProvisioningConfig buildProvisioningConfig() throws ProvisioningException;
    }

    /**
     * Parse provisioning.xml to build the configuration.
     */
    private class ProvisioningFileConfig implements GalleonConfig {
        private final GalleonBuilder pm;
        ProvisioningFileConfig(GalleonBuilder pm) {
            Objects.requireNonNull(pm);
            this.pm = pm;
        }
        @Override
        public GalleonProvisioningConfig buildProvisioningConfig() throws ProvisioningException {
            try(Provisioning p = pm.newProvisioningBuilder(getProvisioningFile()).build()) {
                return p.loadProvisioningConfig(getProvisioningFile());
            }
        }
    }

    /**
     * Abstract Galleon config that handles plugin options and build the config
     * based on the state provided by sub class.
     */
    private abstract class AbstractGalleonConfig implements GalleonConfig {

        protected final GalleonConfigurationWithLayersBuilder configuration;
        AbstractGalleonConfig(GalleonConfigurationWithLayersBuilder configuration) throws ProvisioningException {
            Objects.requireNonNull(configuration);
            this.configuration = configuration;
            setupPluginOptions();
        }

        @Override
        public GalleonProvisioningConfig buildProvisioningConfig() throws ProvisioningException {
            return buildConfig();
        }

        private void setupPluginOptions() throws ProvisioningException {
            // passive+ in all cases
            // For included default config not based on layers, default packages
            // must be included.
            if (pluginOptions.isEmpty()) {
                pluginOptions = Collections.
                        singletonMap(Constants.OPTIONAL_PACKAGES, Constants.PASSIVE_PLUS);
            } else {
                if (!pluginOptions.containsKey(Constants.OPTIONAL_PACKAGES)) {
                    pluginOptions.put(Constants.OPTIONAL_PACKAGES, Constants.PASSIVE_PLUS);
                }
                if (pluginOptions.containsKey(MAVEN_REPO_PLUGIN_OPTION)) {
                    String val = pluginOptions.get(MAVEN_REPO_PLUGIN_OPTION);
                    if (val != null) {
                        Path path = Paths.get(val);
                        if (!path.isAbsolute()) {
                            path = project.getBasedir().toPath().resolve(path);
                            pluginOptions.put(MAVEN_REPO_PLUGIN_OPTION, path.toString());
                        }
                    }
                }
            }
        }

        protected abstract GalleonProvisioningConfig.Builder buildState() throws ProvisioningException;

        public GalleonProvisioningConfig buildConfig() throws ProvisioningException {
            GalleonProvisioningConfig.Builder state = buildState();
            state.addConfig(configuration.build());
            state.addOptions(pluginOptions);
            return state.build();
        }
    }

    /**
     * Abstract config for config based on added Galleon layers. Parent class of
     * all configuration constructed from Galleon layers + FPL or set of
     * feature-packs.
     */
    private abstract class AbstractLayersConfig extends AbstractGalleonConfig {

        public AbstractLayersConfig() throws ProvisioningDescriptionException, ProvisioningException {
            super(buildConfiguration(STANDALONE, STANDALONE_XML));
            List<String> allLayers = new ArrayList<>();
            allLayers.addAll(layers);
            allLayers.addAll(extraLayers);
            for(String l : allLayers) {
                configuration.includeLayer(l);
            }
            for(String l : excludedLayers) {
                configuration.excludeLayer(l);
            }
        }
    }

    private static GalleonConfigurationWithLayersBuilder buildConfiguration(String model, String name) {
        return GalleonConfigurationWithLayersBuilder.builder(model, name);
    }

    /**
     * Galleon layers based config that uses the set of feature-packs.
     */
    private class LayersFeaturePacksConfig extends AbstractLayersConfig {

        private final GalleonProvisioningConfig.Builder state;

        private LayersFeaturePacksConfig(GalleonProvisioningConfig.Builder state) throws ProvisioningDescriptionException, ProvisioningException {
            this.state = state;
        }

        @Override
        public GalleonProvisioningConfig.Builder buildState() throws ProvisioningException {
            return state;
        }
    }

    private static GalleonConfigurationWithLayersBuilder buildDefaultConfig(ConfigId defaultConfigId) {
        Objects.requireNonNull(defaultConfigId);
        GalleonConfigurationWithLayersBuilder config = buildConfiguration(defaultConfigId.getModel(), defaultConfigId.getName());
        Map<String, String> props = new HashMap<>();
        config.setProperty(SERVER_CONFIG, STANDALONE_XML);
        return config;
    }

    /**
     * Abstract config, parent of all config based on a default configuration.
     * Default configuration can be explicitly included in feature-packs or be a
     * default one (microprofile or microprofile-ha for Cloud). These
     * configurations benefit from layers exclusion and extra layers added by
     * cloud.
     */
    private abstract class AbstractDefaultConfig extends AbstractGalleonConfig {

        private AbstractDefaultConfig(ConfigId defaultConfigId) throws ProvisioningException {
            super(buildDefaultConfig(defaultConfigId));
            // We can have extra layers to add to default config.
            for (String l : extraLayers) {
                configuration.includeLayer(l);
            }
            for (String l : excludedLayers) {
                configuration.excludeLayer(l);
            }
        }

    }

    /**
     * A config based on the set of feature-packs. Default config is explicitly
     * included or is the default.
     */
    private class DefaultFeaturePacksConfig extends AbstractDefaultConfig {

        private final GalleonProvisioningConfig.Builder state;

        private DefaultFeaturePacksConfig(ConfigId defaultConfigId, GalleonProvisioningConfig.Builder state) throws ProvisioningException {
            super(defaultConfigId);
            Objects.requireNonNull(state);
            this.state = state;
        }

        @Override
        protected GalleonProvisioningConfig.Builder buildState() throws ProvisioningException {
            return state;
        }

    }

    private void normalizeFeaturePackList() throws MojoExecutionException {
        if (featurePackLocation != null && !featurePacks.isEmpty()) {
            throw new MojoExecutionException("feature-pack-location can't be used with a list of feature-packs");
        }

        // Retrieve versions from Maven in case versions not set.
        if (featurePackLocation != null) {
            if (isChannelsProvisioning()) {
                featurePackLocation = formatLocation(featurePackLocation);
            } else {
                featurePackLocation = MavenUpgrade.locationWithVersion(featurePackLocation, artifactVersions);
            }
            featurePacks = new ArrayList<>();
            FeaturePack fp = new FeaturePack();
            fp.setLocation(featurePackLocation);
            featurePacks.add(fp);
        } else {
            for (FeaturePack fp : featurePacks) {
                if (fp.getLocation() != null) {
                    if (isChannelsProvisioning()) {
                        fp.setLocation(formatLocation(fp.getLocation()));
                    } else {
                        fp.setLocation(MavenUpgrade.locationWithVersion(fp.getLocation(), artifactVersions));
                    }
                } else {
                    if (fp.getGroupId() == null || fp.getArtifactId() == null) {
                        throw new MojoExecutionException("Invalid Maven coordinates for galleon feature-pack ");
                    }
                    if (fp.getVersion() == null) {
                        if (!isChannelsProvisioning()) {
                            Artifact fpArtifact = artifactVersions.getFeaturePackArtifact(fp.getGroupId(), fp.getArtifactId(), fp.getClassifier());
                            if (fpArtifact == null) {
                                throw new MojoExecutionException("No version found for " + fp.getGAC());
                            }
                            fp.setVersion(fpArtifact.getVersion());
                        }
                    }
                }
            }
        }
    }

    private String formatLocation(String location) {
        //Special case for G:A that conflicts with producer:channel that we can't have in the plugin.
        if (!FeaturePackLocation.fromString(location).hasUniverse()) {
            long numSeparators = location.chars().filter(ch -> ch == ':').count();
            if (numSeparators <= 1) {
                location += ":";
            }
        }
        return location;
    }

    private GalleonProvisioningConfig buildGalleonConfig(GalleonBuilder provider, ConfigId defaultConfig) throws ProvisioningException, MojoExecutionException {
        boolean isLayerBasedConfig = !layers.isEmpty();
        boolean hasFeaturePack = !featurePacks.isEmpty();
        boolean hasProvisioningFile = Files.exists(getProvisioningFile());
        if (!hasFeaturePack && !hasProvisioningFile) {
            throw new ProvisioningException("No valid provisioning configuration, "
                    + "you must set a feature-pack-location, a list of feature-packs or use a provisioning.xml file");
        }

        if (hasFeaturePack && hasProvisioningFile) {
            getLog().warn("Feature packs defined in pom.xml override provisioning file located in " + getProvisioningFile());
        }

        if (isLayerBasedConfig) {
            if (!hasFeaturePack) {
                throw new ProvisioningException("No server feature-pack location to provision layers, you must set a feature-pack-location");
            }
            return buildFeaturePacksConfig(provider, true, defaultConfig).buildProvisioningConfig();
        }

        // Based on default config
        if (!featurePacks.isEmpty()) {
            getLog().info("Provisioning server using feature-packs");
            return buildFeaturePacksConfig(provider, isLayerBasedConfig, defaultConfig).buildProvisioningConfig();
        }

        if (hasProvisioningFile) {
            getLog().info("Provisioning server using " + getProvisioningFile());
            return new ProvisioningFileConfig(provider).buildProvisioningConfig();
        }
        throw new ProvisioningException("Invalid Galleon configuration");
    }

    private ConfigId willProvision(List<FeaturePack> featurePacks, GalleonBuilder provider)
            throws MojoExecutionException, ProvisioningException, IOException {
        ProvisioningSpecifics specifics = Utils.getSpecifics(featurePacks, provider);
        return willProvision(specifics);
    }

    protected abstract ConfigId willProvision(ProvisioningSpecifics specifics) throws MojoExecutionException;

    private void provisionServer(Path home, Path outputProvisioningFile, Path workDir) throws ProvisioningException,
            MojoExecutionException, IOException, XMLStreamException {
        GalleonBuilder provider = new GalleonBuilder();
        provider.addArtifactResolver(artifactResolver);

        // Prior to build the config, sub classes could have to inject content to the config according to the
        // provisioned FP.
        normalizeFeaturePackList();
        ConfigId defaultConfig = willProvision(featurePacks, provider);
        GalleonProvisioningConfig config = buildGalleonConfig(provider, defaultConfig);
        try (Provisioning pm = provider.newProvisioningBuilder(config)
                .setInstallationHome(home)
                .setMessageWriter(new MvnMessageWriter(getLog()))
                .setLogTime(logTime)
                .setRecordState(recordState)
                .build()) {
            IoUtils.recursiveDelete(home);
            getLog().info("Building server based on " + config.getFeaturePackDeps() + " galleon feature-packs");
            MavenUpgrade mavenUpgrade = null;
            GalleonProvisioningConfig newConfig;
            if (isChannelsProvisioning()) {
                if (!overriddenServerArtifacts.isEmpty()) {
                    throw new MojoExecutionException("overridden-server-artifacts can't be configured when channels are configured.");
                }
                newConfig = config;
            } else {
                mavenUpgrade = new MavenUpgrade(this, pm, config);
                // Dump artifacts
                if (dumpOriginalArtifacts) {
                    Path file = workDir.resolve("bootable-jar-server-original-artifacts.xml");
                    getLog().info("Dumping original Maven artifacts in " + file);
                    mavenUpgrade.dumpArtifacts(file);
                }
                newConfig = mavenUpgrade.upgrade();
            }
            // store provisioning.xml
            pm.storeProvisioningConfig(newConfig, outputProvisioningFile);

            try {
                scannedArtifacts = BootableJarSupport.scanArtifacts(pm, newConfig, new MvnMessageWriter(getLog()));
            } catch (Exception ex) {
                throw new MojoExecutionException(ex);
            }
            PluginProgressTracker.initTrackers(pm, new MavenJBossLogger(getLog()));
            pm.provision(newConfig);

            if (!recordState) {
                Path file = home.resolve(PLUGIN_PROVISIONING_FILE);
                pm.storeProvisioningConfig(newConfig, file);
            }
        }
    }

    // Get Artifact, syntax comply with WildFly feature-pack versions file.
    static Artifact getArtifact(String str) {
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

    static void readProperties(Path propsFile, Map<String, String> propsMap) throws Exception {
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

    private void deploy(List<String> commands) throws MojoExecutionException {
        if (hollowJar) {
            getLog().info("Hollow jar, No application deployment added to server.");
            return;
        }
        File f = validateProjectFile();

        String runtimeName = f.getName();
        if (project.getPackaging().equals(WAR) || runtimeName.endsWith(WAR)) {
            if (contextRoot) {
                runtimeName = "ROOT." + WAR;
            }
        }
        commands.add("deploy " + f.getAbsolutePath() + " --name=" + f.getName() + " --runtime-name=" + runtimeName);
    }

    private OverriddenArtifact getOverriddenArtifact(String grpId, String artifactId) {
        OverriddenArtifact ret = null;
        for (OverriddenArtifact art : overriddenServerArtifacts) {
            if(art.getGroupId().equals(grpId) && art.getArtifactId().equals(artifactId)) {
                ret = art;
                break;
            }
        }
        return ret;
    }

    private void buildJar(Path contentDir, Path jarFile) throws MojoExecutionException, IOException {
        Path rtJarFile = resolveArtifact(scannedArtifacts.getBoot());
        // Check if that is an older server for which we can't upgrade the jboss-modules dependency.
        Path contentRoot = Paths.get(project.getBuild().getDirectory()).resolve(bootableJarBuildArtifacts);
        Path tmpDir = contentRoot.resolve("tmp_runtime");
        Files.createDirectories(tmpDir);
        ZipUtils.unzip(rtJarFile, tmpDir);
        OverriddenArtifact modules = getOverriddenArtifact(BootableJarSupport.JBOSS_MODULES_GROUP_ID, BootableJarSupport.JBOSS_MODULES_ARTIFACT_ID);
        Path jbossModulesDependency = tmpDir.resolve("META-INF").resolve("maven").resolve(BootableJarSupport.JBOSS_MODULES_GROUP_ID).
                resolve(BootableJarSupport.JBOSS_MODULES_ARTIFACT_ID).resolve("pom.xml");
        if (Files.exists(jbossModulesDependency)) {
            if (modules != null) {
                getLog().warn("Bootable JAR dependency on jboss-modules can't be upgraded, you must use a more recent version of the server.");
            }
        } else {
            if (modules != null) {
                scannedArtifacts.getJbossModules().setVersion(modules.getVersion());
            }
            Path jbossModulesFile = resolveArtifact(scannedArtifacts.getJbossModules());
            ZipUtils.unzip(jbossModulesFile, contentDir);
        }
        ZipUtils.unzip(rtJarFile, contentDir);
        updateManifest(contentDir);
        ZipUtils.zip(contentDir, jarFile);
    }

    private void updateManifest(Path target) throws IOException {
        Path targetMetaInf = target.resolve("META-INF");
        Path targetManifestPath = targetMetaInf.resolve("MANIFEST.MF");
        boolean updated;
        Manifest manifest;
        try (FileInputStream stream = new FileInputStream(targetManifestPath.toFile())) {
            manifest = new Manifest(stream);
            updated = updateManifest(manifest);
        }
        if (updated) {
            Files.deleteIfExists(targetManifestPath);
            try (FileOutputStream out = new FileOutputStream(targetManifestPath.toFile())) {
                manifest.write(out);
            }
        }
    }

    public String retrievePluginVersion() throws PlexusConfigurationException, MojoExecutionException {
        InputStream is = getClass().getResourceAsStream("/META-INF/maven/plugin.xml");
        if (is == null) {
            throw new MojoExecutionException("Can't retrieve plugin descriptor");
        }
        PluginDescriptorBuilder builder = new PluginDescriptorBuilder();
        PluginDescriptor pluginDescriptor = builder.build(new InputStreamReader(is, StandardCharsets.UTF_8));
        return pluginDescriptor.getVersion();
    }

    public Path resolveArtifact(String groupId, String artifactId, String classifier, String version) throws UnsupportedEncodingException,
            PlexusConfigurationException, MojoExecutionException {
        MavenArtifact ma = new MavenArtifact();
        ma.setGroupId(groupId);
        ma.setArtifactId(artifactId);
        ma.setVersion(version);
        ma.setClassifier(classifier);
        ma.setExtension(JAR);
        return resolveArtifact(ma);
    }

    Path resolveArtifact(MavenArtifact mavenArtifact) throws MojoExecutionException {
        try {
            if (!mavenArtifact.isResolved()) {
                resolve(mavenArtifact);
            }
            return mavenArtifact.getPath();
        } catch (IOException | MavenUniverseException ex) {
            throw new MojoExecutionException(ex.toString(), ex);
        }
    }

    private void resolve(MavenArtifact artifact) throws MavenUniverseException, IOException {
        artifactResolver.resolve(artifact);
    }

    public MavenRepoManager getArtifactResolver() {
        return artifactResolver;
    }

    private void attachJar(Path jarFile) {
        debug("Attaching bootable jar %s as a project artifact with classifier %s", jarFile, installArtifactClassifier);
        projectHelper.attachArtifact(project, JAR, installArtifactClassifier, jarFile.toFile());
    }

    void debug(String msg, Object... args) {
        if (getLog().isDebugEnabled()) {
            getLog().debug(String.format(msg, args));
        }
    }

    static void deleteDir(Path root) {
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

    Path resolveMaven(GalleonArtifactCoordinate coordinate) throws MavenUniverseException {
        final MavenArtifact artifact = new MavenArtifact()
                .setGroupId(coordinate.getGroupId())
                .setArtifactId(coordinate.getArtifactId())
                .setVersion(coordinate.getVersion())
                .setExtension(coordinate.getExtension())
                .setClassifier(coordinate.getClassifier());
        artifactResolver.resolve(artifact);
        return artifact.getPath();
    }
}
