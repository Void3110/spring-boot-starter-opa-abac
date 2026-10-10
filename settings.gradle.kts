rootProject.name = "spring-boot-starter-opa-abac"

// --- Library modules (publishable to Maven Central) ---
include(
    "opa-abac-core",
    "opa-abac-spring-security",
    "opa-abac-spring-data",
    "opa-abac-keycloak-directory",
    // The MCP tool-gate (ADR 0036): agent tool-call authorization for MCP servers. Incubating;
    // auto-configured by the starter when added next to Spring AI's MCP server.
    "opa-abac-mcp",
    "opa-abac-spring-boot-starter",
    // The BOM (java-platform) — the 7th published coordinate. Lets adopters do
    // implementation(platform("dev.dmitriikonovalov:opa-abac-bom:<v>")) and reference the
    // modules version-free. It applies the publish plugin in its own build (POM-only artifact),
    // NOT via the root jar-module allow-list.
    "opa-abac-bom",
)

// --- Example applications (NOT published) ---
// Flat root modules with an `example-` prefix; built up step by step to demonstrate the
// full concept: Keycloak + APISIX (OIDC, OPA, tracing) -> example-catalog-management-service
// (ABAC checks). The example-user-management-service is the second app: the ABAC attribute
// source (teams, role definitions, grants) that resolves a caller's effective role for a resource.
// The example-mcp-server is the third app (AGENT-TOOL-AUTHZ, Phase 9): a Spring AI MCP server whose
// @McpTool methods proxy the catalog service's REST API with the caller's own bearer, gated by a
// tool-gate policy. It consumes the shipped starter and the opa-abac-mcp module like any adopter.
include(
    "example-catalog-management-service",
    "example-user-management-service",
    "example-mcp-server",
)
