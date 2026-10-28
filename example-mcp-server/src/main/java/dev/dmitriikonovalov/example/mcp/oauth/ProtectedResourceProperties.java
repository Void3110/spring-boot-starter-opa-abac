package dev.dmitriikonovalov.example.mcp.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The RFC 9728 protected-resource metadata this MCP server advertises, under
 * {@code example.mcp.oauth.protected-resource} (ADR 0035 §6).
 *
 * <p>Two values, and the document is built from them alone — never from the request — so a client cannot
 * be told to log in somewhere a forged {@code Host} names. Both unset is a legitimate state (discovery
 * off); one set, or a malformed value, fails startup. {@link ProtectedResourceDocument} holds that
 * contract.
 */
@ConfigurationProperties("example.mcp.oauth.protected-resource")
public class ProtectedResourceProperties {

    /**
     * The MCP endpoint's absolute URL as clients reach it — the document's {@code resource}. Its path must
     * be the MCP endpoint's ({@code /mcp}).
     */
    private String resource = "";

    /** The authorization server's issuer URL — the document's single {@code authorization_servers} entry. */
    private String authorizationServer = "";

    public String getResource() {
        return resource;
    }

    public void setResource(String resource) {
        this.resource = resource;
    }

    public String getAuthorizationServer() {
        return authorizationServer;
    }

    public void setAuthorizationServer(String authorizationServer) {
        this.authorizationServer = authorizationServer;
    }
}
