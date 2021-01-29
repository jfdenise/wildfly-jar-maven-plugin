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

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import org.jboss.galleon.ProvisioningManager;
import org.jboss.galleon.config.ProvisioningConfig;
import org.jboss.galleon.layout.FeaturePackLayout;
import org.jboss.galleon.layout.ProvisioningLayout;
import org.jboss.galleon.spec.FeatureParameterSpec;
import org.jboss.galleon.spec.FeatureSpec;
import org.jboss.galleon.xml.FeatureSpecXmlParser;

/**
 *
 * @author jdenise
 */
public class GalleonFeatures {

    public static void store(Path workDir, Path contentDir, ProvisioningManager pm, ProvisioningConfig config) throws Exception {
        // Store all features.
        Path features = workDir.resolve("galleon-features");
        Files.createDirectories(features);
        try (ProvisioningLayout<FeaturePackLayout> pl = pm.getLayoutFactory().newConfigLayout(config)) {
            for (FeaturePackLayout fl : pl.getOrderedFeaturePacks()) {
                Path feat = fl.getDir().resolve("features");
                if (!Files.exists(feat)) {
                    continue;
                }
                Files.walk(feat).forEach((f) -> {
                    if ("spec.xml".equals(f.getFileName().toString())) {
                        String name = f.getParent().getFileName().toString();
                        if (!name.startsWith("host.") && !name.startsWith("domain.")) {
                            try {
                                try (BufferedReader reader = Files.newBufferedReader(f)) {
                                    FeatureSpec spec = FeatureSpecXmlParser.getInstance().parse(reader);
                                    Properties specProps = new Properties();
                                    if (spec.hasAnnotation("jboss-op")) {
                                        specProps.setProperty("spec.op", spec.getAnnotation("jboss-op").getElement("name"));
                                        specProps.setProperty("spec.op.params", spec.getAnnotation("jboss-op").getElement("op-params"));
                                        specProps.setProperty("spec.addr.params", spec.getAnnotation("jboss-op").getElement("addr-params"));
                                    }
                                    StringBuilder paramNames = new StringBuilder();
                                    for (Map.Entry<String, FeatureParameterSpec> p : spec.getParams().entrySet()) {
                                        String pname = p.getKey();
                                        FeatureParameterSpec param = p.getValue();
                                        boolean id = param.isFeatureId();
                                        if (param.isFeatureId()) {
                                            specProps.setProperty("spec.param." + pname + ".id", "true");
                                        }
                                        if (param.hasDefaultValue()) {
                                            specProps.setProperty("spec.param." + pname + ".default", param.getDefaultValue());
                                        }
                                        if (paramNames.length() != 0) {
                                            paramNames.append(",");
                                        }
                                        paramNames.append(pname);
                                    }
                                    specProps.setProperty("spec.params", paramNames.toString());
                                    Path target = features.resolve(name + ".properties");
                                    try (FileOutputStream s = new FileOutputStream(target.toFile())) {
                                        specProps.store(s, "galleon properties used for yaml transformation");
                                    }

                                }

                            } catch (Exception ex) {
                                throw new RuntimeException(ex);
                            }
                        }
                    }
                });
            }
        }
        zipFeatures(features, contentDir);
    }

    private static void zipFeatures(Path home, Path contentDir) throws IOException {
        Path target = contentDir.resolve("galleon-features.zip");
        AbstractBuildBootableJarMojo.zip(home, target);
    }
}
