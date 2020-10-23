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
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;

/**
 *
 * @author jdenise
 */
class DevWatchContext {

    private final MavenProject project;
    private final Map<WatchKey, Path> watchedDirectories = new HashMap<>();
    private final Path javaDir;
    private final Path webAppDir;
    private final Set<Path> resourceDirectories = new HashSet<>();
    private final Path pom;
    private final WatchService watcher;
    private final Path projectBuildDir;
    private final Log log;

    DevWatchContext(MavenProject project, Path javaDir, Path projectBuildDir, WatchService watcher, Log log) throws IOException {
        this.project = project;
        this.watcher = watcher;
        this.projectBuildDir = projectBuildDir;
        this.log = log;
        Path srcDir = project.getBasedir().toPath().resolve("src");
        Path mainDir = srcDir.resolve("main");
        this.javaDir = javaDir;
        webAppDir = mainDir.resolve("webapp");

        // Register at the root of the project, in case we have resources inside it.
        // So we support resources only inside the project.
        registerDir(project.getBasedir().toPath());

        pom = project.getBasedir().toPath().resolve("pom.xml");

        for (Resource res : project.getResources()) {
            Path p = Paths.get(res.getDirectory());
            if (!p.isAbsolute()) {
                p = project.getBasedir().toPath().resolve(p);
            }
            // We must add it even if it doesn't exist.
            // That the way to know the resource files in case they are created later.
            resourceDirectories.add(p);
            log.debug("[WATCH] resources dir: " + p);
            if (Files.exists(p)) {
                registerDir(p);
            }
        }
    }

    boolean requiresFullRebuild(Path absolutePath) {
        return pom.equals(absolutePath);
    }

    boolean requiresClean(Path absolutePath) {
        return requiresCompile(absolutePath) || requiresResources(absolutePath) || requiresRePackage(webAppDir);
    }

    boolean fileDeleted(Path absolutePath) {
        boolean b = requiresCompile(absolutePath) || requiresResources(absolutePath) || requiresRePackage(webAppDir);
        // Cleanup
        WatchKey key = null;
        for (Entry<WatchKey, Path> entry : watchedDirectories.entrySet()) {
            if (entry.getValue().equals(absolutePath)) {
                key = entry.getKey();
                break;
            }
        }
        if (key != null) {
            log.debug("[WATCH] cancelling monitoring of " + absolutePath);
            key.cancel();
            // Do not fire a clean when a directory is removed, the files in the directory (if any have already fired it.)
            b = false;
        }
        return b;
    }

    boolean requiresCompile(Path absolutePath) {
        return absolutePath.startsWith(javaDir);
    }

    boolean requiresResources(Path absolutePath) {
        return isResourcesDir(absolutePath);
    }

    boolean requiresRePackage(Path absolutePath) {
        return absolutePath.startsWith(webAppDir);
    }

    Path getPath(WatchKey key, Path fileName) {
        Path p = watchedDirectories.get(key);
        return p.resolve(fileName);

    }

    final boolean isResourcesDir(Path p) {
        for (Path path : resourceDirectories) {
            if (p.startsWith(path)) {
                return true;
            }
        }
        return false;
    }

    final void registerDir(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!projectBuildDir.equals(dir)) {
                    watchedDirectories.put(dir.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY), dir);
                    log.debug("[WATCH] watching " + dir);
                    return FileVisitResult.CONTINUE;
                } else {
                    return FileVisitResult.SKIP_SUBTREE;
                }
            }
        });
    }

    void cleanup() {
        for (WatchKey k : watchedDirectories.keySet()) {
            k.cancel();
        }
    }
}
