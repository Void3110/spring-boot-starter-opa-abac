---
tags:
  - status/planned
  - type/architecture
  - area/abac
  - area/spring-security
  - area/spring
---

# Library spine — design

> Part of [[POC-ROADMAP]] Phase 3, after [[DOMAIN-MODEL-FOUNDATION]]. The decomposition into tickets
> is [[01-DECOMPOSITION]]; this note is the *why* and *shape*.

## Problem

The catalog example authenticates nobody and authorizes nothing. Concretely:

- the app has **no Spring Security** on the classpath; controllers are bare REST endpoints;
- identity is faked — `AuditingConfig` returns a fixed `DEMO_PRINCIPAL` UUID, ignoring the real caller;
- the gateway does the only "identity" work today: APISIX validates the Keycloak JWT, then a
  **throwaway Lua `serverless-pre-function` enricher** decodes the token and injects `X-User-Id` /
  `X-Username` headers, and the APISIX `opa` plugin consults a placeholder **allow-all** `gateway.rego`.

So nothing makes a real authorization decision. This slice moves fine-grained ABAC **into the app via
the library**: an OPA client, JWT→subject extraction, and a `@OpaPreAuthorize` enforcement path —
retiring the demo enricher in favour of Spring-native extraction. The [[DOMAIN-MODEL-FOUNDATION]] slice
already made every catalog entity an `AbacDataObject`; this slice is what finally *reads* that.

This is a clean-room generalization of an OPA-backed ABAC framework the author built in a prior
production platform. We take the *ideas* — a fail-closed OPA client, JWT extraction into a security
principal, role-definition-driven decisions, a method-level enforcement annotation — re-express them
with original names, and make them idiomatically Spring-native.

**Scope is single-decision only.** Batch evaluation, partial-eval → JPA `Specification` list filtering,
and hierarchical ancestor-walk authorization are **Phase 5** and explicitly out of scope here.

## The spine, end to end

```
HttpOpaClient (opa-abac-core)
   → AbacSubjectExtractor + AbacFilter (opa-abac-spring-security)
   → @OpaPreAuthorize + OpaPreAuthorizeAuthorizationManager (opa-abac-spring-security)
   → starter auto-config (opa-abac-spring-boot-starter)
   → catalog example adopts it
```

Module boundaries are strict (already established): `core` ← `spring-security`/`spring-data` ←
`starter`. The OPA client is in **core** (so a non-Spring consumer can use it); everything
Spring-Security lives in **spring-security**; the wiring lives in the **starter**.

## `HttpOpaClient` — fail-closed, zero extra deps (core)

`opa-abac-core` must stay **Spring-free**, so the client is built on the JDK `java.net.http.HttpClient`
plus Jackson (already a core dependency) — no Feign, RestTemplate, or WebClient.

`allow(AbacContext)`:

1. resolve the OPA document path (see *policy-path resolution*) → `baseUrl + "/v1/data/" + path`;
2. serialize `{"input": <the context>}` (a small wrapper so the JSON shape is explicit);
3. POST with a per-request timeout, `Content-Type: application/json`;
4. on HTTP 200, read the decision per the configured `decisionField` (default `allow`, i.e.
   `result.allow`); a missing / null / non-boolean decision ⇒ **deny**;
5. on **anything else** — non-200, `IOException`, timeout, connection refused, malformed body ⇒
   **deny** (log WARN with the path + status, never the token).

> **Fail-closed is the cardinal rule.** An authorization system that fails *open* is worse than none.
> `allow()` never throws for an OPA/transport failure — it returns `false`. (Recorded in Mulch
> `mx-926c85`.) Two layers enforce this: the client (here) and the authorization manager (which treats
> any context-building exception as deny).

**Policy-path resolution is pluggable.** A `PolicyPathResolver` SPI (`String resolve(AbacContext)`)
decides the document path; the default `PerTypePolicyPathResolver(policyPrefix)` returns
`policyPrefix + "/" + resourceType` — i.e. **one rego document per resource type**
(`catalog`/`category`/`product`). The client depends on the interface, never on the property, so an
advanced consumer can route by tenant/version/action with a one-bean override.

`OpaClientConfig` (baseUrl, timeout, decisionField) is a small immutable carrier kept in core, so core
is usable without Spring; the starter maps `OpaAbacProperties` onto it.

## `RoleDefinition` + `RoleDefinitionSupplier` — the decision backbone

The most consequential design choice in this slice. In the source platform, authorization is driven
**primarily by the caller's role definition**, not by raw token roles — JWT tags are an *override*
layer. We adopt the same model, generalized:

```java
// opa-abac-core (Spring-free)
public record RoleDefinition(
        String code,                            // e.g. "catalog-viewer", "catalog-editor"
        Map<String, Object> attributes,         // extensible (role level, tier, …) — defensive-copied
        Map<String, List<String>> permissions   // { resourceType -> [allowed action verbs] }
) { }

@FunctionalInterface
public interface RoleDefinitionSupplier {
    // resourceId may be null for type-level / create / list checks
    Optional<RoleDefinition> lookup(String userId, String resourceType, String resourceId);
}
```

- The OPA `input` carries a `role_definition` object, so the policy decides on
  `input.role_definition.permissions[resource.type]` rather than guessing from token roles.
- The library ships the SPI + a **`NoOpRoleDefinitionSupplier`** (returns empty → the policy can fall
  back to subject roles), so the client/manager work even before an app provides role definitions.
- The catalog app ships a **static `DemoRoleDefinitionSupplier`**: a map keyed on the subject's realm
  roles → a `RoleDefinition` (`catalog-viewer` → read perms on each type; `catalog-editor` → adds
  write). This is the clean, data-driven stand-in until a real authority exists.
- **Phase 4** ([[USER-MANAGEMENT-SERVICE]]) provides an `HttpRoleDefinitionSupplier` calling the
  user-service's read API; registered as a `@Bean`, it overrides the demo one
  (`@ConditionalOnMissingBean`). The seam is built **now** so Phase 4 is a single-bean swap.

> **Why build the SPI now instead of waiting for Phase 4?** The shape of the OPA `input`, the
> annotation, and the rego all depend on whether a role definition is present. Designing them around
> `role_definition` from day one avoids reworking the wire contract and the policies later. The *source*
> of role definitions is the only thing that changes between the demo and Phase 4 — and that's exactly
> what the `RoleDefinitionSupplier` interface isolates.

## Subject extraction — trust the gateway (spring-security)

`AbacSubjectExtractor` is an SPI returning `Optional<AbacContext.Subject>`; the default
`JwtClaimsSubjectExtractor` reads the forwarded `Authorization: Bearer <jwt>`, base64url-decodes the
**payload only**, and maps claims (all claim names are configurable):

- `sub` → subject id; `realm_access.roles` → roles (Keycloak realm roles); `preferred_username` +
  any configured claims → attributes.

`AbacFilter extends OncePerRequestFilter` wraps the Subject in an `AbacAuthentication` (an
`AbstractAuthenticationToken` exposing `getSubject()`, with authorities = `ROLE_<role>` so plain Spring
role checks still work) and sets it on the `SecurityContextHolder`. A missing/malformed token →
anonymous pass-through (the filter never throws; downstream authorization then denies).

### Signature-verification posture (decided)

**The app trusts the gateway; it does NOT re-verify the JWT signature.** APISIX `openid-connect`
already validated the token against the realm JWKS before forwarding it. The app does **structural +
`exp`** checks only (cheap defense-in-depth, no key material). A `verifySignature=true` mode is
**reserved** (forward-looking), not implemented in this slice.

> **Tradeoff (documented loudly):** this is safe *only* because the app sits behind a validating
> gateway and is not directly internet-exposed. Deployed gateway-less, signature trust is a
> vulnerability — hence the reserved switch and the loud doc note. Re-verifying in the app would
> duplicate JWKS handling and pull `oauth2-resource-server` into the library, contradicting the lean,
> two-layer thesis. (Generalizes Mulch `mx-cbca87`: gateway enrichment is demo scaffold; the library
> does real extraction.)

## Enforcement — `@OpaPreAuthorize`, role-definition-driven (spring-security)

```java
@OpaPreAuthorize(action = "product:write", resourceType = "'product'")   // SpEL for type/id
public ResponseEntity<Product> updateProduct(UUID productId, ...) { … }
```

`OpaPreAuthorizeAuthorizationManager implements AuthorizationManager<MethodInvocation>`:

1. read the Subject from the current `AbacAuthentication` (unauthenticated ⇒ deny);
2. resolve the resource type/id from the annotation (SpEL against method args);
3. **`RoleDefinitionSupplier.lookup(userId, resourceType, resourceId)`** → the `RoleDefinition`;
4. build `AbacContext(subject, action, resource, roleDefinition, environment)`;
5. `opaClient.allow(ctx)` → `AuthorizationDecision`; **any exception ⇒ deny (fail-closed)**.

It's registered via an `AuthorizationManagerBeforeMethodInterceptor` advisor bound to
`@OpaPreAuthorize` (an `OpaMethodSecurityConfiguration`). Deny surfaces as Spring Security's
`AccessDeniedException` → 403.

> **Pre-invocation ⇒ coarse, type-level resource (this slice).** An annotation evaluated *before* the
> method runs cannot see a return value or a not-yet-loaded entity. So `@OpaPreAuthorize` names the
> resource by **type** (+ optional id from a path var), and the *decision* is rich (role-definition
> driven), not the *resource*. Per-instance attribute checks (load-then-check, tag-based) are the
> richer **Phase 5** pattern; they need the loaded `AbacDataObject` and so a post-load hook. A
> `resource()` SpEL mode that names an `AbacDataObject` instance is also supported for callers who
> already hold one.

A minimal opt-in `OpaAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext>`
(action = lowercased HTTP method; resource type from a configured map) is **provided** for request-level
rules, but `@OpaPreAuthorize` is the headline this slice demonstrates because it can name the concrete
resource type and action.

## OPA policy shape — per-type documents

One rego document **per resource type** (`infra/opa/policies/{catalog,category,product}.rego`), each
`package <type>` with `default allow := false`. The client posts `/v1/data/<type>` and reads
`result.allow`. A rule allows when the action verb is in
`input.role_definition.permissions[input.resource.type]`, falling back to subject roles when no role
definition is present. The existing coarse `gateway.rego` stays for the APISIX layer.

> **Why per-type, not one shared document?** From a production perspective, one policy file per
> resource type scales and reads better than a single growing `allow` rule — each type's rules,
> tests, and ownership are separable. The pluggable `PolicyPathResolver` makes per-type the default
> while still allowing a single-document override.

`/rego-skill` authors these with `opa test` companions (the repo's own Rego skill).

## Two-layer authorization (gateway ↔ app)

The model this slice realizes (and documents in `docs/architecture/TWO-LAYER-AUTHORIZATION.md`):

- **Gateway (APISIX + OPA):** coarse — authenticates the caller (`openid-connect`), forwards the
  validated bearer, and may keep a coarse route-level OPA decision (`gateway.rego`).
- **App (Spring + the library):** fine-grained — extracts the Subject itself, looks up the role
  definition, and asks OPA the resource/action question via `@OpaPreAuthorize`.

The app **never trusts the gateway for the fine-grained decision** — it re-derives identity and
re-asks OPA. The demo Lua enricher is retired because the app now does identity extraction natively.
(Generalizes Mulch `mx-7130a1` two-layer authorization, `mx-cbca87` enricher-is-scaffold.)

## Example adoption + ITs

- `SecurityConfig` (`@EnableWebSecurity @EnableMethodSecurity`): a stateless `SecurityFilterChain`,
  CSRF off, installs the injected `AbacFilter`, permits health/docs, requires auth on `/api/v1/**`.
- Controllers annotated with `@OpaPreAuthorize` (reads `:read`, writes `:write`).
- `DemoRoleDefinitionSupplier` `@Bean` (overrides the no-op).
- `AuditingConfig.AuditorAware` reads the real principal (`sub` UUID) from the `SecurityContextHolder`,
  empty when unauthenticated — replacing `DEMO_PRINCIPAL`.
- **No schema change** — `ddl-auto: validate` is unaffected.
- **ITs:** `CatalogCrudIT` / `ProductConcurrencyIT` send no token and test persistence/concurrency, not
  authz. They run under a **permissive test setup** (a mock `AbacAuthentication` + a stub
  `OpaClient(allow=true)`, and/or `opa.abac.enabled=false`) so they stay unchanged-green. Authorization
  is covered by the security-module unit tests + the e2e matrix.

## Considered & rejected

| Option | Why rejected (for now) |
|--------|------------------------|
| **Raw token roles only (no `RoleDefinition`)** | The source platform proved role definitions are the decision backbone; designing the OPA input / annotation / rego around raw roles would force a wire-contract rework when the user-service lands. The SPI isolates the *source* of role definitions, so we build the shape now. |
| **Build the user-management-service first** | A large Phase-4 detour before any authorization ships. The `RoleDefinitionSupplier` SPI + a static demo supplier gets the spine working now; Phase 4 swaps one bean. No roadmap reordering. |
| **Re-verify the JWT signature in the app** | Correct for a gateway-less deployment, but duplicates JWKS/key-rotation and pulls `oauth2-resource-server` into the library, contradicting the lean two-layer model. Offered as a reserved `verifySignature` opt-in. |
| **Feign / RestTemplate / WebClient for the OPA client** | Would add a dependency and (for the reactive client) a stack; the JDK `HttpClient` keeps `opa-abac-core` Spring-free and dependency-light. |
| **One shared rego document** | A single growing `allow` rule is harder to own/test at scale than per-type documents; per-type is the production-shaped default, still overridable via the resolver. |
| **Starter auto-registers a `SecurityFilterChain`** | A library seizing the app's security chain is surprising and fights real apps. The starter exposes beans; the app declares its chain and installs `AbacFilter`. |
| **Per-instance (load-then-check) resource resolution now** | Needs the loaded entity (a post-load hook) — that's the Phase-5 attribute-based pattern. Type-level pre-auth is enough for the role-driven viewer/editor matrix and stays teachable. |

## Module placement

- **`opa-abac-core`** (Spring-free): `HttpOpaClient`, `OpaClientConfig`, `PolicyPathResolver`,
  `PerTypePolicyPathResolver`, `RoleDefinition`, `RoleDefinitionSupplier`, `NoOpRoleDefinitionSupplier`;
  widen `AbacContext` with a nullable `roleDefinition`.
- **`opa-abac-spring-security`**: `AbacSubjectExtractor`, `JwtClaimsSubjectExtractor`,
  `AbacAuthentication`, `AbacFilter`, `OpaPreAuthorize`, `OpaPreAuthorizeAuthorizationManager`,
  `OpaAuthorizationManager`, `OpaMethodSecurityConfiguration`.
- **`opa-abac-spring-boot-starter`**: extend `OpaAbacProperties`; wire every bean in
  `OpaAbacAutoConfiguration`, conditional + overridable; **no `SecurityFilterChain`**.
- **example app**: `SecurityConfig`, `DemoRoleDefinitionSupplier`, the controller annotations,
  `AuditingConfig` change; `infra/` gets the per-type rego, the realm users, the enricher retirement.

## Deferred to later phases

Batch evaluation · partial-eval → JPA `Specification` list filtering · hierarchical ancestor-walk ·
per-instance attribute decisions · the `@AutoTag` processor + dynamic tag dictionary (Phase 4) · the
HTTP-backed `RoleDefinitionSupplier` (Phase 4) · in-app JWT signature verification (`verifySignature`) ·
tightening `gateway.rego` beyond allow-all · a newman CI job.

## Related

- Work breakdown: [[01-DECOMPOSITION]]
- Run it: [[AUTONOMOUS-IMPLEMENTATION-PROMPT]]
- QA cases: [[10-QA-TEST-CASES]]
- Resource side it consumes: [[DOMAIN-MODEL]] (`AbacDataObject`)
- Roadmap: [[POC-ROADMAP]] · Phase-4 consumer: [[USER-MANAGEMENT-SERVICE]]
