package dev.dmitriikonovalov.opaabac.keycloak.directory;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for the Keycloak user-directory implementation (ADR 0020 §4/§6), bound from
 * {@code opa.abac.directory.keycloak.*}:
 *
 * <pre>
 * opa:
 *   abac:
 *     directory:
 *       keycloak:
 *         enabled: true
 *         server-url: http://keycloak:8888    # the in-network REST base — NOT the console rewrite
 *         realm: catalog-demo
 *         client-id: catalog-directory        # a dedicated confidential client, view-users ONLY
 *         client-secret: ...
 * </pre>
 *
 * <p>The server URL is private to this module (the port and the endpoint stay URL-agnostic, §6). The
 * client is a dedicated service account holding only {@code realm-management → view-users} — never reuse
 * the gateway's client, never a human credential (§4).
 */
@ConfigurationProperties(prefix = "opa.abac.directory.keycloak")
public class KeycloakDirectoryProperties {

    /** Master opt-in; without it the starter supplies the {@code NoOpUserDirectory} fallback. */
    private boolean enabled = false;

    /** Keycloak base URL as seen by this service (in the rig: the in-network {@code http://keycloak:8888}). */
    private String serverUrl;

    /** The realm whose accounts are searched. */
    private String realm;

    /** The dedicated confidential client (service account, {@code view-users} only). */
    private String clientId;

    /** The client secret for the {@code client_credentials} grant. */
    private String clientSecret;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    public String getRealm() {
        return realm;
    }

    public void setRealm(String realm) {
        this.realm = realm;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }
}
