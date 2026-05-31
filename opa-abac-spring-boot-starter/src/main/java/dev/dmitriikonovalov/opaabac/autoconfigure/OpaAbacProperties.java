package dev.dmitriikonovalov.opaabac.autoconfigure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the OPA ABAC starter.
 *
 * <pre>
 * opa:
 *   abac:
 *     enabled: true
 *     base-url: http://localhost:8181
 *     policy-prefix: catalog
 *     timeout: 5s
 * </pre>
 */
@ConfigurationProperties(prefix = "opa.abac")
public class OpaAbacProperties {

    /** Master switch for the ABAC auto-configuration. */
    private boolean enabled = true;

    /** Base URL of the OPA server. */
    private String baseUrl = "http://localhost:8181";

    /** Policy path prefix under OPA's data document (e.g. {@code catalog/products}). */
    private String policyPrefix = "";

    /** Per-request evaluation timeout. */
    private Duration timeout = Duration.ofSeconds(5);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getPolicyPrefix() {
        return policyPrefix;
    }

    public void setPolicyPrefix(String policyPrefix) {
        this.policyPrefix = policyPrefix;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}
