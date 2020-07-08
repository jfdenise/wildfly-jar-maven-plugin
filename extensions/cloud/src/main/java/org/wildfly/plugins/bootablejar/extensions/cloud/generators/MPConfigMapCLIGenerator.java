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
package org.wildfly.plugins.bootablejar.extensions.cloud.generators;

import java.util.List;
import org.wildfly.plugins.bootablejar.extensions.cloud.CLIGenerator;

/**
 *
 * @author jdenise
 */
public class MPConfigMapCLIGenerator implements CLIGenerator {
    @Override
    public void generate(List<String> cmds) throws Exception {
        String dir = System.getenv("MICROPROFILE_CONFIG_DIR");
        String ordinal = System.getenv("MICROPROFILE_CONFIG_DIR_ORDINAL");
        if (dir != null) {
            cmds.add("/subsystem=microprofile-config-smallrye/config-source=config-map:add(dir={path="+dir+"}, ordinal=" + (ordinal == null ? "500" : ordinal) + ")");
        }
    }
}
