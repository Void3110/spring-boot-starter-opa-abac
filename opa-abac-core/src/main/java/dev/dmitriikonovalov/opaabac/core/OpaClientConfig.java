package dev.dmitriikonovalov.opaabac.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Objects;

/**
 * Immutable configuration for {@link HttpOpaClient}. Kept in core so the client is usable without
 * Spring; the starter maps its properties onto this carrier.
 *
 * @param baseUrl       the OPA server base URL (e.g. {@code http://localhost:8181}); no trailing slash required.
 *                      It must be an absolute {@code http} or {@code https} URL with a host — anything else
 *                      is rejected here, so a misconfiguration fails at startup instead of on every request
 * @param timeout       the per-request evaluation timeout
 * @param decisionField the boolean field read from {@code result} (default {@code "allow"})
 */
public record OpaClientConfig(String baseUrl, Duration timeout, String decisionField) {

    public OpaClientConfig {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(timeout, "timeout");
        decisionField = (decisionField == null || decisionField.isBlank()) ? "allow" : decisionField;
        // normalize: drop any trailing slash so path joins are clean
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        requireHttpUrl(baseUrl);
    }

    /**
     * The messages never echo the value: a base URL may carry credentials in its user-info part, and a startup
     * failure is logged. Not even the parse exception is attached — its message quotes the input.
     */
    private static void requireHttpUrl(String baseUrl) {
        URI uri;
        try {
            uri = new URI(baseUrl);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(
                    "OPA base URL is not a valid URI (" + e.getReason() + " at index " + e.getIndex() + ")");
        }
        String scheme = uri.getScheme();
        boolean httpScheme = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        if (!httpScheme || uri.getHost() == null) {
            throw new IllegalArgumentException(
                    "OPA base URL must be an absolute http(s) URL with a host, e.g. http://localhost:8181");
        }
    }

    /** Config with the default {@code allow} decision field. */
    public OpaClientConfig(String baseUrl, Duration timeout) {
        this(baseUrl, timeout, "allow");
    }
}
