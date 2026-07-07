rootProject.name = "spring-boot-starter-opa-abac"

// --- Library modules (publishable to Maven Central) ---
include(
    "opa-abac-core",
    "opa-abac-spring-security",
    "opa-abac-spring-data",
    "opa-abac-keycloak-directory",
    "opa-abac-spring-boot-starter",
)

// --- Example applications (NOT published) ---
// Flat root modules with an `example-` prefix; built up step by step to demonstrate the
// full concept: Keycloak + APISIX (OIDC, OPA, tracing) -> example-catalog-management-service
// (ABAC checks). The example-user-management-service is the second app: the ABAC attribute
// source (teams, role definitions, grants) that resolves a caller's effective role for a resource.
include(
    "example-catalog-management-service",
    "example-user-management-service",
)
