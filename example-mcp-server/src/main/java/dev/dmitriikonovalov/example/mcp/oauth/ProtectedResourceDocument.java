package dev.dmitriikonovalov.example.mcp.oauth;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The validated protected-resource document — or its absence (ADR 0035 §6).
 *
 * <h2>Off is a state, half is an error</h2>
 * Both values unset means discovery is off: the endpoints answer {@code 404} and the server logs one WARN.
 * Exactly one set, or a value that is not an absolute {@code http(s)} URL, carries a fragment, or (for
 * {@code resource}) names a path other than the MCP endpoint's, fails startup naming the key and the
 * reason. A half-written document would advertise a guess, and a client acting on it would send the user's
 * login to the wrong place.
 *
 * <h2>Never request-derived</h2>
 * The body is computed once, here, from configuration. Nothing about a request — its {@code Host}, its
 * {@code X-Forwarded-*} headers — can reach it.
 */
public final class ProtectedResourceDocument {

    /** The {@code resource} property's full key, as the failure messages name it. */
    public static final String RESOURCE_KEY = "example.mcp.oauth.protected-resource.resource";

    /** The {@code authorization-server} property's full key, as the failure messages name it. */
    public static final String AUTHORIZATION_SERVER_KEY = "example.mcp.oauth.protected-resource.authorization-server";

    /** The MCP endpoint's path: the only path a {@code resource} value may name. */
    static final String MCP_ENDPOINT_PATH = "/mcp";

    private static final ProtectedResourceDocument OFF = new ProtectedResourceDocument(null);

    private final Map<String, Object> body;

    private ProtectedResourceDocument(Map<String, Object> body) {
        this.body = body;
    }

    /**
     * Validates the two values and builds the document.
     *
     * @throws IllegalStateException when exactly one value is set or a set value is malformed
     */
    public static ProtectedResourceDocument from(ProtectedResourceProperties properties) {
        String resource = trimmed(properties.getResource());
        String authorizationServer = trimmed(properties.getAuthorizationServer());
        if (resource.isEmpty() && authorizationServer.isEmpty()) {
            return OFF;
        }
        if (resource.isEmpty() || authorizationServer.isEmpty()) {
            String missing = resource.isEmpty() ? RESOURCE_KEY : AUTHORIZATION_SERVER_KEY;
            String present = resource.isEmpty() ? AUTHORIZATION_SERVER_KEY : RESOURCE_KEY;
            throw new IllegalStateException(missing + " is not set, but " + present + " is. Protected-resource "
                    + "discovery is either fully configured or off (both unset); a half-written document "
                    + "would advertise a guess.");
        }
        URI resourceUri = absoluteHttpUrl(RESOURCE_KEY, resource);
        absoluteHttpUrl(AUTHORIZATION_SERVER_KEY, authorizationServer);
        if (!MCP_ENDPOINT_PATH.equals(resourceUri.getRawPath())) {
            throw new IllegalStateException(RESOURCE_KEY + "='" + resource + "' must name the MCP endpoint, "
                    + "whose path is " + MCP_ENDPOINT_PATH + "; it names '" + resourceUri.getRawPath() + "'.");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resource", resource);
        body.put("authorization_servers", List.of(authorizationServer));
        body.put("bearer_methods_supported", List.of("header"));
        return new ProtectedResourceDocument(Collections.unmodifiableMap(body));
    }

    /** Whether discovery is on — both values set and valid. */
    public boolean isEnabled() {
        return body != null;
    }

    /**
     * The document's JSON members, in their published order.
     *
     * @throws IllegalStateException when discovery is off
     */
    public Map<String, Object> body() {
        if (body == null) {
            throw new IllegalStateException("protected-resource discovery is off");
        }
        return body;
    }

    private static URI absoluteHttpUrl(String key, String value) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(key + "='" + value + "' is not a valid URL: " + e.getMessage(), e);
        }
        String scheme = uri.getScheme();
        if (!uri.isAbsolute() || uri.getRawAuthority() == null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalStateException(key + "='" + value + "' is not an absolute http(s) URL.");
        }
        if (uri.getRawFragment() != null) {
            throw new IllegalStateException(key + "='" + value + "' carries a fragment, which RFC 9728 forbids.");
        }
        return uri;
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
