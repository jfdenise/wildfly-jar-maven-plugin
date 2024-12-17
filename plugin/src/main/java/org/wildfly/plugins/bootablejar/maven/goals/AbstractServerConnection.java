/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugins.bootablejar.maven.goals;

import java.net.URISyntaxException;
import java.net.URL;

import javax.inject.Inject;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.crypto.DefaultSettingsDecryptionRequest;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.apache.maven.settings.crypto.SettingsDecryptionResult;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.ModelControllerClientConfiguration;
import org.wildfly.plugin.tools.client.ClientCallbackHandler;

/**
 * The default implementation for connecting to a running WildFly instance
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 * @author Stuart Douglas
 */
public abstract class AbstractServerConnection extends AbstractMojo {

    public static final String DEBUG_MESSAGE_NO_CREDS = "No username and password in settings.xml file - falling back to CLI entry";
    public static final String DEBUG_MESSAGE_NO_ID = "No <id> element was found in the POM - Getting credentials from CLI entry";
    public static final String DEBUG_MESSAGE_NO_SERVER_SECTION = "No <server> section was found for the specified id";
    public static final String DEBUG_MESSAGE_NO_SETTINGS_FILE = "No settings.xml file was found in this Mojo's execution context";
    public static final String DEBUG_MESSAGE_POM_HAS_CREDS = "Getting credentials from the POM";
    public static final String DEBUG_MESSAGE_SETTINGS_HAS_CREDS = "Found username and password in the settings.xml file";
    public static final String DEBUG_MESSAGE_SETTINGS_HAS_ID = "Found the server's id in the settings.xml file";

    static {
        // This is odd, but if not set we should set the JBoss Logging provider to slf4j as that is what Maven uses
        final String provider = System.getProperty("org.jboss.logging.provider");
        if (provider == null || provider.isBlank()) {
            System.setProperty("org.jboss.logging.provider", "slf4j");
        }
    }

    /**
     * The protocol used to connect to the server for management.
     */
    @Parameter(property = "wildfly.protocol")
    private String protocol;

    /**
     * Specifies the host name of the server where the deployment plan should be executed.
     */
    @Parameter(defaultValue = "localhost", property = "wildfly.hostname")
    private String hostname;

    /**
     * Specifies the port number the server is listening on.
     */
    @Parameter(defaultValue = "9990", property = "wildfly.port")
    private int port;

    /**
     * Specifies the id of the server if the username and password is to be
     * retrieved from the settings.xml file
     */
    @Parameter(property = "wildfly.id")
    private String id;

    /**
     * Provides a reference to the settings file.
     */
    @Parameter(property = "settings", readonly = true, required = true, defaultValue = "${settings}")
    private Settings settings;

    /**
     * Specifies the username to use if prompted to authenticate by the server.
     * <p/>
     * If no username is specified and the server requests authentication the user
     * will be prompted to supply the username,
     */
    @Parameter(property = "wildfly.username")
    private String username;

    /**
     * Specifies the password to use if prompted to authenticate by the server.
     * <p/>
     * If no password is specified and the server requests authentication the user
     * will be prompted to supply the password,
     */
    @Parameter(property = "wildfly.password")
    private String password;

    /**
     * The timeout, in seconds, to wait for a management connection.
     */
    @Parameter(property = "wildfly.timeout", defaultValue = "60")
    protected int timeout;

    /**
     * A URL which points to the authentication configuration ({@code wildfly-config.xml}) the client uses to
     * authenticate with the server.
     */
    @Parameter(alias = "authentication-config", property = "wildfly.authConfig")
    private URL authenticationConfig;

    @Inject
    private SettingsDecrypter settingsDecrypter;

    /**
     * The goal of the deployment.
     *
     * @return the goal of the deployment.
     */
    public abstract String goal();

    /**
     * Creates a new client.
     *
     * @return the client
     */
    protected ModelControllerClient createClient() {
        return ModelControllerClient.Factory.create(getClientConfiguration());
    }

    /**
     * Gets a client configuration used to create a new {@link ModelControllerClient}.
     *
     * @return the configuration to use
     */
    protected synchronized ModelControllerClientConfiguration getClientConfiguration() {
        final Log log = getLog();
        String username = this.username;
        String password = this.password;
        if (username == null && password == null) {
            if (id != null) {
                if (settings != null) {
                    Server server = settings.getServer(id);
                    if (server != null) {
                        log.debug(DEBUG_MESSAGE_SETTINGS_HAS_ID);
                        password = decrypt(server);
                        username = server.getUsername();
                        if (username != null && password != null) {
                            log.debug(DEBUG_MESSAGE_SETTINGS_HAS_CREDS);
                        } else {
                            log.debug(DEBUG_MESSAGE_NO_CREDS);
                        }
                    } else {
                        log.debug(DEBUG_MESSAGE_NO_SERVER_SECTION);
                    }
                } else {
                    log.debug(DEBUG_MESSAGE_NO_SETTINGS_FILE);
                }
            } else {
                log.debug(DEBUG_MESSAGE_NO_ID);
            }
        } else {
            log.debug(DEBUG_MESSAGE_POM_HAS_CREDS);
        }
        final ModelControllerClientConfiguration.Builder builder = new ModelControllerClientConfiguration.Builder()
                .setProtocol(protocol)
                .setHostName(getManagementHostName())
                .setPort(getManagementPort())
                .setConnectionTimeout(timeout * 1000);
        if (authenticationConfig != null) {
            try {
                builder.setAuthenticationConfigUri(authenticationConfig.toURI());
            } catch (URISyntaxException e) {
                throw new RuntimeException("Failed to create URI from " + authenticationConfig, e);
            }
        } else {
            builder.setHandler(new ClientCallbackHandler(username, password));
        }
        return builder.build();
    }

    protected int getManagementPort() {
        return port;
    }

    protected String getManagementHostName() {
        return hostname;
    }

    private String decrypt(final Server server) {
        SettingsDecryptionResult decrypt = settingsDecrypter.decrypt(new DefaultSettingsDecryptionRequest(server));
        return decrypt.getServer().getPassword();
    }
}
