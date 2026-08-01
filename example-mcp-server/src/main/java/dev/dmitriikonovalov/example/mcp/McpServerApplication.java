package dev.dmitriikonovalov.example.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The example MCP server: a Spring AI MCP server whose {@code @McpTool} methods proxy the
 * <strong>existing</strong> catalog-management service's REST API with the caller's own bearer token.
 *
 * <p>It exists to demonstrate agent tool-call authorization on the shipped starter without changing a
 * line of it: the tool surface is a second front door, and the tool-gate (from T4) decides
 * <em>principal ceiling ∩ agent capability</em> before any tool body runs. The catalog service keeps
 * enforcing the principal's ceiling on every resource with its policies untouched, so the intersection
 * holds <em>across</em> the two layers rather than being propagated between them.
 */
@SpringBootApplication
public class McpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }
}
