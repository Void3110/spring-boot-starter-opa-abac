package dev.dmitriikonovalov.opaabac.security.ownership;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The cross-service ownership registry (Slice B4, ADR 0019), bound from {@code abac.ownership.*}.
 *
 * <pre>
 * abac:
 *   ownership:
 *     ttl: 30s                 # how long a (type,id) -> createdBy lookup is cached
 *     timeout-ms: 2000         # per-request timeout for the created-by read
 *     services:
 *       catalog: http://catalog:8080   # type -> base URL of the owning service
 * </pre>
 *
 * <p>Adding a new owning service is <strong>one config line</strong> under {@code services} plus that
 * service implementing the standard {@code GET /internal/{type}/{id}/created-by} contract — no per-service
 * code dependency. A type with <strong>no</strong> entry is unknown → the resolver fails closed to
 * not-owner (it never guesses a URL).
 */
@ConfigurationProperties(prefix = "abac.ownership")
public class OwnershipProperties {

    /** Type → owning-service base URL. A type absent here is "unknown" → fail-closed not-owner. */
    private Map<String, String> services = new LinkedHashMap<>();

    /** How long a {@code (type,id) → createdBy} result is cached. Transfer staleness is bounded by this. */
    private Duration ttl = Duration.ofSeconds(30);

    /** Per-request timeout for the {@code created-by} read. */
    private long timeoutMs = 2000;

    public Map<String, String> getServices() {
        return services;
    }

    public void setServices(Map<String, String> services) {
        this.services = services;
    }

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }
}
