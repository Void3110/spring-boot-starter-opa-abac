package dev.dmitriikonovalov.example.mcp.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the example MCP server's own concerns, under {@code example.mcp}.
 *
 * <p>Deliberately narrow: where the catalog REST API lives and how long this server is willing to wait
 * for it. Authorization settings arrive in T4 under {@code example.mcp.authz} and identity settings in T2
 * under {@code example.mcp.identity}, each with their own properties type, so no single class becomes the
 * grab-bag that hides which switch belongs to which layer.
 */
@ConfigurationProperties("example.mcp")
public class McpServerProperties {

    private final Catalog catalog = new Catalog();

    public Catalog getCatalog() {
        return catalog;
    }

    /** The downstream catalog-management service the tools proxy. */
    public static class Catalog {

        /**
         * Base URL of the catalog service — on the rig its <strong>in-network</strong> address, not a
         * published host port.
         */
        private String baseUrl = "http://localhost:8080";

        /** TCP connect timeout for a catalog call. */
        private Duration connectTimeout = Duration.ofSeconds(2);

        /**
         * Per-request read timeout. A tool call must fail fast and structurally rather than hang: an agent
         * waiting on a stalled tool has no way to make progress.
         */
        private Duration readTimeout = Duration.ofSeconds(5);

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }
    }
}
