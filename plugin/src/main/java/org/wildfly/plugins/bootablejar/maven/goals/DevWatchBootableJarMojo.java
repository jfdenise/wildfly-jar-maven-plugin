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

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.BuildPluginManager;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jboss.galleon.util.IoUtils;
import org.twdata.maven.mojoexecutor.MojoExecutor;
import static org.twdata.maven.mojoexecutor.MojoExecutor.artifactId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.configuration;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executeMojo;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executionEnvironment;
import static org.twdata.maven.mojoexecutor.MojoExecutor.goal;
import static org.twdata.maven.mojoexecutor.MojoExecutor.groupId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.plugin;
import static org.twdata.maven.mojoexecutor.MojoExecutor.version;
import org.wildfly.core.launcher.Launcher;
import static org.wildfly.plugins.bootablejar.maven.goals.AbstractBuildBootableJarMojo.JAR;
import static org.wildfly.plugins.bootablejar.maven.goals.AbstractBuildBootableJarMojo.WAR;

/**
 * Build and start a bootable JAR for dev-watch mode. This goal monitors the
 * changes in the project and recompile/re-deploy. Type Ctrl-C to kill the
 * running server.
 *
 * @author jfdenise
 */
@Mojo(name = "dev-watch", requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, defaultPhase = LifecyclePhase.COMPILE)
public final class DevWatchBootableJarMojo extends AbstractDevBootableJarMojo {

    /**
     * running any one of these phases means the compile phase will have been
     * run, if these have not been run we manually run compile
     */
    private static final Set<String> POST_COMPILE_PHASES = new HashSet<>(Arrays.asList(
            "compile",
            "process-classes",
            "generate-test-sources",
            "process-test-sources",
            "generate-test-resources",
            "process-test-resources",
            "test-compile",
            "process-test-classes",
            "test",
            "prepare-package",
            "package",
            "pre-integration-test",
            "integration-test",
            "post-integration-test",
            "verify",
            "install",
            "deploy"));
    private static final String ORG_APACHE_MAVEN_PLUGINS = "org.apache.maven.plugins";
    private static final String MAVEN_COMPILER_PLUGIN = "maven-compiler-plugin";
    private static final String MAVEN_COMPILER_GOAL = "compile";
    private static final String MAVEN_WAR_PLUGIN = "maven-war-plugin";
    private static final String MAVEN_EXPLODED_GOAL = "exploded";
    private static final String MAVEN_JAR_PLUGIN = "maven-jar-plugin";
    private static final String MAVEN_JAR_GOAL = "jar";
    private static final String MAVEN_RESOURCES_PLUGIN = "maven-resources-plugin";
    private static final String MAVEN_RESOURCES_GOAL = "resources";
    private static final String REBUILD_MARKER = "wildfly.bootable.jar.rebuild";

    @Component
    private BuildPluginManager pluginManager;

    @Component
    private ProjectBuilder projectBuilder;

    @Parameter(defaultValue = "${project.build.sourceDirectory}")
    private File sourceDir;

    private Process process;
    @Override
    protected void doExecute() throws MojoExecutionException, MojoFailureException {
        boolean isRebuild = System.getProperty(REBUILD_MARKER) != null;
        if (isRebuild) {
            return;
        }
        try {
            rebuild(true, false, true, true);
        } catch (IOException | MojoExecutionException e) {
            throw new MojoExecutionException(e.getLocalizedMessage(), e);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                if (process != null) {
                    process.destroy();
                    try {
                        process.waitFor();
                    } catch (InterruptedException ex) {
                        getLog().error("Error waiting for process to terminate " + ex);
                    }
                }
            }
        }));
        try {
            process = Launcher.of(buildCommandBuilder())
                    .inherit()
                    .launch();
            watch();
        } catch (Exception e) {
            throw new MojoExecutionException(e.getLocalizedMessage(), e);
        }
    }

    private void watch() throws IOException, MojoExecutionException, InterruptedException, MojoFailureException, ProjectBuildingException {
        WatchService watcher = FileSystems.getDefault().newWatchService();
        DevWatchContext ctx = new DevWatchContext(project, sourceDir.toPath(), Paths.get(projectBuildDir), watcher,
                cliSessions, extraServerContentDirs, getLog());
        for (;;) {
            WatchKey key = watcher.take();
            boolean needCompile = false;
            boolean needResources = false;
            boolean needClean = false;
            boolean needRepackage = false;
            boolean needFullRebuild = false;
            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                if (kind == OVERFLOW) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                WatchEvent<Path> ev = (WatchEvent<Path>) event;
                Path absolutePath = ctx.getPath(key, ev.context());
                getLog().debug("[WATCH] file change [" + ev.kind().name() + "]: " + absolutePath);
                if (kind == ENTRY_CREATE) {
                    if (Files.isDirectory(absolutePath)) {
                        ctx.registerDir(absolutePath);
                    }
                    continue;
                } else if (kind == ENTRY_MODIFY) {
                    if (ctx.requiresFullRebuild(absolutePath)) {
                        needFullRebuild = true;
                    }
                } else if (kind == ENTRY_DELETE) {
                    // We must clean
                    if (ctx.fileDeleted(absolutePath)) {
                        getLog().debug("[WATCH] File deleted, need to clean.");
                        needClean = true;
                    }
                    continue;
                }

                if (ctx.requiresCompile(absolutePath)) {
                    getLog().debug("[WATCH] java dir updated, need to re-compile");
                    needCompile = true;
                } else if (ctx.requiresResources(absolutePath)) {
                    getLog().debug("[WATCH] resources dir updated, need to update resources");
                    needResources = true;
                } else if (ctx.requiresRePackage(absolutePath)) {
                    getLog().debug("[WATCH] webapp dir updated, need to re-package");
                    needRepackage = true;
                }
            }
            try {
                if (needFullRebuild) {
                    // Must rebuild the bootable JAR.
                    getLog().info("[WATCH] stopping running bootable JAR");
                    process.destroy();
                    process.waitFor();
                    getLog().info("[WATCH] server stopped, rebuilding JAR");
                    System.setProperty(REBUILD_MARKER, "true");
                    ctx = triggerRebuildBootableJar(watcher, ctx);
                    process = Launcher.of(buildCommandBuilder())
                            .inherit()
                            .launch();
                    getLog().info("[WATCH] server re-started");
                    // We need a rebuild.
                    needCompile = true;
                }
                if (needClean || needCompile || needResources || needRepackage) {
                    getLog().info("[WATCH] updating application");
                    rebuild(false, needClean, needCompile, needResources);
                }
            } catch (Exception ex) {
                getLog().error("Error rebuilding: " + ex);
                if (getLog().isDebugEnabled()) {
                    ex.printStackTrace();
                }
            }
            key.reset();
        }
    }

    private void rebuild(boolean autoCompile, boolean clean, boolean compile, boolean resources) throws IOException, MojoExecutionException {
        if (clean) {
            cleanClasses();
        }
        if (resources || clean) {
            triggerResources();
        }
        if (compile || clean) {
            if (autoCompile) {
                handleAutoCompile();
            } else {
                triggerCompile();
            }
        }
        String finalName = this.project.getBuild().getFinalName();
        Path artifactFile = Paths.get(this.projectBuildDir, finalName + "." + this.project.getPackaging());
        Path deploymentsDir = getDeploymentsDir();
        if (!Files.exists(deploymentsDir)) {
            Files.createDirectories(deploymentsDir);
        }
        String fileName = artifactFile.getFileName().toString();
        if (project.getPackaging().equals(WAR) || fileName.endsWith(WAR)) {
            if (contextRoot) {
                fileName = "ROOT." + WAR;
            }
            Path targetDir = deploymentsDir.resolve(fileName);
            triggerExplodeWar(targetDir);
        } else {
            if (project.getPackaging().equals(JAR) || fileName.endsWith(JAR)) {
                triggerJar();
                Files.copy(artifactFile, deploymentsDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path failedMarker = deploymentsDir.resolve(fileName + ".failed");
        if (Files.exists(failedMarker)) {
            Files.delete(failedMarker);
        }
        Path marker = deploymentsDir.resolve(fileName + ".dodeploy");
        if (Files.notExists(marker)) {
            Files.createFile(marker);
        }
    }

    private void handleAutoCompile() throws MojoExecutionException {
        //we check to see if there was a compile (or later) goal before this plugin
        boolean compileNeeded = true;
        for (String goal : session.getGoals()) {
            if (POST_COMPILE_PHASES.contains(goal)) {
                compileNeeded = false;
                break;
            }
            if (goal.endsWith("wildfly-jar:dev-watch")) {
                break;
            }
        }

        //if the user did not compile we run it for them
        if (compileNeeded) {
            cleanClasses();
            triggerResources();
            triggerCompile();
        }
    }

    private void triggerCompile() throws MojoExecutionException {
        // Compile the Java sources if needed
        final String compilerPluginKey = ORG_APACHE_MAVEN_PLUGINS + ":" + MAVEN_COMPILER_PLUGIN;
        final Plugin compilerPlugin = project.getPlugin(compilerPluginKey);
        if (compilerPlugin != null) {
            executeGoal(compilerPlugin, ORG_APACHE_MAVEN_PLUGINS, MAVEN_COMPILER_PLUGIN, MAVEN_COMPILER_GOAL, getPluginConfig(compilerPlugin));
        }
    }

    private void triggerExplodeWar(Path targetDir) throws MojoExecutionException {
        // Compile the Java sources if needed
        final String warPluginKey = ORG_APACHE_MAVEN_PLUGINS + ":" + MAVEN_WAR_PLUGIN;
        final Plugin warPlugin = project.getPlugin(warPluginKey);
        // We could have removed content, so must delete the previous deployment.
        IoUtils.recursiveDelete(targetDir);
        if (warPlugin != null) {
            executeGoal(warPlugin, ORG_APACHE_MAVEN_PLUGINS, MAVEN_WAR_PLUGIN, MAVEN_EXPLODED_GOAL, getPluginConfig(warPlugin, targetDir));
        }
    }

    private void triggerJar() throws MojoExecutionException {
        // Compile the Java sources if needed
        final String jarPluginKey = ORG_APACHE_MAVEN_PLUGINS + ":" + MAVEN_JAR_PLUGIN;
        final Plugin jarPlugin = project.getPlugin(jarPluginKey);
        if (jarPlugin != null) {
            executeGoal(jarPlugin, ORG_APACHE_MAVEN_PLUGINS, MAVEN_JAR_PLUGIN, MAVEN_JAR_GOAL, getPluginConfig(jarPlugin));
        }
    }

    private DevWatchContext triggerRebuildBootableJar(WatchService watcher, DevWatchContext ctx) throws MojoExecutionException, ProjectBuildingException, IOException {
        ProjectBuildingRequest buildingRequest = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
        MavenProject mavenProject = projectBuilder.build(project.getBasedir().toPath().resolve("pom.xml").toFile(), buildingRequest).getProject();
        final String jarPluginKey = "org.wildfly.plugins" + ":" + "wildfly-jar-maven-plugin";
        final Plugin jarPlugin = mavenProject.getPlugin(jarPluginKey);
        Path updatedSrcDir = sourceDir.toPath();
        Path updatedBuildDir = Paths.get(projectBuildDir);
        List<String> updatedExtras = new ArrayList<>();
        List<CliSession> updatedCliSessions = new ArrayList<>();
        if (jarPlugin != null) {
            Xpp3Dom config = getPluginConfig(jarPlugin);
            executeGoal(jarPlugin, "org.wildfly.plugins", "wildfly-jar-maven-plugin", "dev-watch", config);

            // Resync the jvmArguments and arguments that we are going to re-use when launching the server
            Xpp3Dom jvmArguments = config.getChild("jvmArguments");
            this.jvmArguments.clear();
            if (jvmArguments != null) {
                //rebuild them.
                for (Xpp3Dom child : jvmArguments.getChildren()) {
                    this.jvmArguments.add(child.getValue());
                }
            }
            Xpp3Dom serverArguments = config.getChild("arguments");
            this.arguments.clear();
            if (serverArguments != null) {
                //rebuild them.
                for (Xpp3Dom child : serverArguments.getChildren()) {
                    this.arguments.add(child.getValue());
                }
            }
            Xpp3Dom srcDir = config.getChild("sourceDir");
            if (srcDir != null) {
                // Is null for defaultValue.
                if (srcDir.getValue() != null) {
                    updatedSrcDir = Paths.get(srcDir.getValue());
                }
            }
            Xpp3Dom projectBuildDir = config.getChild("projectBuildDir");
            if (projectBuildDir != null) {
                // Is null for defaultValue.
                if (projectBuildDir.getValue() != null) {
                    updatedSrcDir = Paths.get(projectBuildDir.getValue());
                }
            }

            Xpp3Dom extra = config.getChild("extra-server-content-dirs");
            if (extra == null) {
                config.getChild("extraServerContentDirs");
            }
            if (extra != null) {
                for (Xpp3Dom child : extra.getChildren()) {
                    updatedExtras.add(child.getValue());
                }
            }

            Xpp3Dom cli = config.getChild("cli-sessions");
            if (cli == null) {
                config.getChild("cliSessions");
            }
            if (cli != null) {
                for (Xpp3Dom child : cli.getChildren()) {
                    CliSession session = new CliSession();
                    Xpp3Dom props = child.getChild("properties-file");
                    if (props != null) {
                        session.setPropertiesFile(props.getValue());
                    }
                    Xpp3Dom scripts = child.getChild("script-files");
                    if (scripts != null) {
                        List<String> lst = new ArrayList<>();
                        for (Xpp3Dom script : scripts.getChildren()) {
                            lst.add(script.getValue());
                        }
                        session.setScriptFiles(lst);
                    }
                    updatedCliSessions.add(session);
                }
            }
        }
        ctx.cleanup();
        return new DevWatchContext(mavenProject, updatedSrcDir, updatedBuildDir,
                watcher, updatedCliSessions, updatedExtras, getLog());
    }

    private void executeGoal(Plugin plugin, String groupId, String artifactId, String goal, Xpp3Dom config) throws MojoExecutionException {
        executeMojo(
                plugin(
                        groupId(groupId),
                        artifactId(artifactId),
                        version(plugin.getVersion()),
                        plugin.getDependencies()),
                goal(goal),
                config,
                executionEnvironment(
                        project,
                        session,
                        pluginManager));
    }

    private void triggerResources() throws MojoExecutionException {
        List<Resource> resources = project.getResources();
        if (resources.isEmpty()) {
            return;
        }
        Plugin resourcesPlugin = project.getPlugin(ORG_APACHE_MAVEN_PLUGINS + ":" + MAVEN_RESOURCES_PLUGIN);
        if (resourcesPlugin == null) {
            return;
        }
        executeGoal(resourcesPlugin, ORG_APACHE_MAVEN_PLUGINS, MAVEN_RESOURCES_PLUGIN, MAVEN_RESOURCES_GOAL, getPluginConfig(resourcesPlugin));
    }

    private void cleanClasses() throws MojoExecutionException {
        Path buildDir = Paths.get(this.projectBuildDir);
        IoUtils.recursiveDelete(Paths.get(this.project.getBuild().getOutputDirectory()));
        final String compilerPluginKey = ORG_APACHE_MAVEN_PLUGINS + ":" + MAVEN_COMPILER_PLUGIN;
        final Plugin compilerPlugin = project.getPlugin(compilerPluginKey);
        if (compilerPlugin != null) {
            Path p;
            Xpp3Dom config = getPluginConfig(compilerPlugin);
            Xpp3Dom genSources = config.getChild("generatedSourcesDirectory");
            if (genSources == null) {
                p = buildDir.resolve("generated-sources").resolve("annotations");
            } else {
                String path = genSources.getValue();
                p = Paths.get(path);
            }
            IoUtils.recursiveDelete(p);
        }
    }

    private Xpp3Dom getPluginConfig(Plugin plugin, Path target) {

        Xpp3Dom configuration = configuration();

        Xpp3Dom pluginConfiguration = (Xpp3Dom) plugin.getConfiguration();
        if (pluginConfiguration != null) {
            //Filter out `test*` configurations
            for (Xpp3Dom child : pluginConfiguration.getChildren()) {
                if (!child.getName().startsWith("test") && !child.getName().startsWith("failOnMissingWebXml")) {
                    configuration.addChild(child);
                }
            }
        }
        MojoExecutor.Element e = new MojoExecutor.Element("webappDirectory", target.toAbsolutePath().toString());
        configuration.addChild(e.toDom());
        return configuration;
    }

    private Xpp3Dom getPluginConfig(Plugin plugin) {

        Xpp3Dom configuration = configuration();

        Xpp3Dom pluginConfiguration = (Xpp3Dom) plugin.getConfiguration();
        if (pluginConfiguration != null) {
            //Filter out `test*` configurations
            for (Xpp3Dom child : pluginConfiguration.getChildren()) {
                if (!child.getName().startsWith("test")) {
                    configuration.addChild(child);
                }
            }
        }

        return configuration;
    }

}
