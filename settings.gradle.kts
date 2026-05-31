rootProject.name = "spring-boot-starter-opa-abac"

// --- Library modules (publishable to Maven Central) ---
include(
    "opa-abac-core",
    "opa-abac-spring-security",
    "opa-abac-spring-data",
    "opa-abac-spring-boot-starter",
)

// --- Example applications (NOT published) ---
// Flat root modules with an `example-` prefix; built up step by step to demonstrate the
// full concept: Keycloak + APISIX (OIDC, OPA, tracing) -> example-catalog-management-service
// (ABAC checks). A future example-user-management-service will join as a sibling.
include(
    "example-catalog-management-service",
)
