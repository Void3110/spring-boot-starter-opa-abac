rootProject.name = "spring-boot-starter-opa-abac"

// --- Library modules (publishable to Maven Central) ---
include(
    "opa-abac-core",
    "opa-abac-spring-security",
    "opa-abac-spring-data",
    "opa-abac-spring-boot-starter",
)

// --- Example applications (NOT published) ---
// Built up step by step to demonstrate the full concept:
//   Keycloak + APISIX (OIDC, OPA, tracing) -> catalog-management-service (ABAC checks)
include(
    "example:catalog-management-service",
)
