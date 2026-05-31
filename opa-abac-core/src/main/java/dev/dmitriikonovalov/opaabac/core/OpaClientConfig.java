package dev.dmitriikonovalov.opaabac.core;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable configuration for {@link HttpOpaClient}. Kept in core so the client is usable without
 * Spring; the starter maps its properties onto this carrier.
 *
 * @param baseUrl       the OPA server base URL (e.g. {@code http://localhost:8181}); no trailing slash required
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
    }

    /** Config with the default {@code allow} decision field. */
    public OpaClientConfig(String baseUrl, Duration timeout) {
        this(baseUrl, timeout, "allow");
    }
}
