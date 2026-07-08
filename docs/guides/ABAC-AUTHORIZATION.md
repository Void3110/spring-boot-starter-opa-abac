---
tags:
  - status/active
  - type/guide
  - area/abac
  - area/spring-security
---

# ABAC authorization — the library spine

How the starter turns a Keycloak-authenticated request into a fine-grained, OPA-backed authorization
decision, and how an application adopts it. This is the **single-decision** path
(`@OpaPreAuthorize`); batch evaluation and partial-evaluation data filtering are later phases.

## The spine, end to end

```
Bearer JWT (forwarded by the gateway)
  → AbacFilter → AbacSubjectExtractor          → AbacContext.Subject in the SecurityContext
  → @OpaPreAuthorize → OpaPreAuthorizeAuthorizationManager
        → RoleDefinitionSupplier.lookup(...)    → the caller's RoleDefinition
        → builds AbacContext(subject, action, resource, roleDefinition, env)
        → HttpOpaClient.allow(ctx)              → OPA decides on role_definition.permissions
  → allow ? proceed : AccessDeniedException (403)
```

Module boundaries are strict: the OPA client + model live in `opa-abac-core` (Spring-free); everything
Spring-Security lives in `opa-abac-spring-security`; the starter wires the beans.

## The OPA client — fail-closed (`opa-abac-core`)

`HttpOpaClient` is built on the JDK `java.net.http.HttpClient` + Jackson (no Feign/RestTemplate/WebClient),
so core stays Spring-free. `allow(AbacContext)`:

1. resolves the per-type document path via `PolicyPathResolver` (default `PerTypePolicyPathResolver`:
   `<policyPrefix>/<resourceType>`);
2. POSTs `{"input": <context>}` to `<baseUrl>/v1/data/<path>`;
3. reads `result.<decisionField>` (default `allow`) as a boolean.

**Fail-closed is the cardinal rule.** Any non-200, `IOException`, timeout, connection refused, malformed
body, or missing/non-boolean field ⇒ `false`. `allow()` never throws for an OPA/transport failure — it
logs a warning (path + status, never the token) and denies. The authorization manager adds a second
fail-closed layer: any exception while building the context or calling OPA also denies.

## The decision backbone — `RoleDefinition` + `RoleDefinitionSupplier`

Authorization is driven **primarily by the caller's role definition**, not by raw token roles:

```java
public record RoleDefinition(
        String code,                            // e.g. "catalog-viewer"
        Map<String, Object> attributes,         // extensible (role level, tier, …)
        Map<String, List<String>> permissions   // { resourceType -> [allowed action verbs] }
) { }

@FunctionalInterface
public interface RoleDefinitionSupplier {
    Optional<RoleDefinition> lookup(String userId, String resourceType, String resourceId);
}
```

The OPA `input` carries a `role_definition` object, so a policy decides on
`role_definition.permissions[resource.type]` — since Phase 6.5 through the shared category expansion,
`permissions.effective_actions` ([[PERMISSION-MODEL]]). The library ships a `NoOpRoleDefinitionSupplier` (returns
empty → the policy decides without a role definition; a policy *may* define its own subject-role
fallback, though the catalog policies carry no blanket one post-B4). An application overrides it with
**one bean**:

> **Tri-state contract — an outage is not a no-role (Slice B2, ADR [[0014-supplier-outage-error-distinct|0014]]).**
> `lookup(...)` distinguishes three outcomes, so a role-source *outage* can never be mistaken for an
> authoritative *no-role* and silently widen access:
> - `Optional.of(def)` — **resolved**: decide on it.
> - `Optional.empty()` — **authoritative no-role**: a *designed* signal; the policy decides without it.
>   The library permits a policy to define a subject-role fallback, but the catalog policies carry **no
>   blanket** one post-B4 (ADR [[0018-team-scoped-resource-isolation|0018]]) — only the narrow
>   `catalog:create` realm-role fallback survives (see [[PERMISSION-MODEL]]).
> - **throws `RoleResolutionException`** — **outage**: the source was unavailable, the result is
>   *unknown*; every consumer **fails closed** (the gate denies before any OPA call; the data
>   consumers return no widening / an empty page) and **never** falls back.
>
> An in-process supplier (`NoOp`, the demo) never throws — only a remote/queried one (the
> `HttpRoleDefinitionSupplier`) classifies a failure as an outage. The mechanism: the **supplier
> classifies** (throws on outage), and **each consumer maps** the throw to its own fail-closed outcome —
> there is no library wrapper (swallowing the throw would re-introduce the hole). This closes the one
> tracked *widening-on-failure* path ([[PERMISSION-CATEGORIES-REVIEW]] C1/C4): before B2 an outage rode
> the then-blanket realm fallback to a grant **wider** than the resolved role (B4 has since removed that
> blanket fallback; see [[PERMISSION-MODEL]]). The strict HTTP classification:
> **only `204` → `Optional.empty()`** (authoritative no-role → the policy decides), **only `200`+valid
> body → resolved**, and **everything else throws** (200-blank, all 4xx/5xx, timeout, connection-refused,
> malformed body).
> Resilience (retry / backoff / circuit-breaking) is a separate axis — Slice **B3**, before publish.



- **now:** the catalog example's static `DemoRoleDefinitionSupplier` maps realm roles → a `RoleDefinition`
  (`catalog-viewer` → `READ` on each type; `catalog-editor` → + `WRITE`/`TAG` — **coarse category
  tokens** that expand to fine actions in OPA `data`; see [[PERMISSION-MODEL]]);
- **later (Phase 4):** an `HttpRoleDefinitionSupplier` calling a user-management service — a single-bean
  swap, because everything downstream depends only on the `RoleDefinitionSupplier` interface.

## Identity extraction — trust the gateway

`AbacFilter` (a `OncePerRequestFilter`) delegates to an `AbacSubjectExtractor`. The default
`JwtClaimsSubjectExtractor` reads `Authorization: Bearer <jwt>`, base64url-decodes the **payload segment
only**, and maps configurable claims (`sub` → id, `realm_access.roles` → roles, `preferred_username` +
configured claims → attributes) to an `AbacContext.Subject`, wrapped in an `AbacAuthentication`
(authorities `ROLE_<role>`, so plain Spring role checks still work).

### Signature-trust posture (read this)

The extractor performs **no signature verification**. The application **trusts a validating gateway**
(e.g. APISIX `openid-connect`, which verified the token against the realm JWKS before forwarding it). It
does structural + `exp` checks only — cheap defense-in-depth, no key material.

> ⚠️ **This is safe only behind such a gateway.** Deployed gateway-less / internet-exposed, signature
> trust is a vulnerability. A `verifySignature` mode is **reserved** (a forward-looking property) but
> deliberately **not implemented** in this slice — re-verifying in the app would duplicate JWKS handling
> and pull `oauth2-resource-server` into the library, contradicting the lean two-layer design. If you
> deploy without a validating gateway, do not use this extractor as-is.

The filter takes precedence over an anonymous authentication but never overwrites a real one, so it can
sit after Spring Security's `AnonymousAuthenticationFilter`.

## Enforcement — `@OpaPreAuthorize`

```java
@OpaPreAuthorize(action = "product:update", resourceType = "'product'", resourceId = "#productId")
public ResponseEntity<Product> updateProduct(UUID catalogId, UUID categoryId, UUID productId, ...) { … }
```

`resourceType` / `resourceId` / `resource` are SpEL evaluated against the method arguments;
`resource(...)` may name an `AbacResource` instance for callers that hold one. The manager
(`AuthorizationManager<MethodInvocation>`) reads the subject, resolves the resource, looks up the role
definition, builds the `AbacContext`, and asks OPA; deny surfaces as `AccessDeniedException` → 403.

**Pre-invocation ⇒ the resource is named by type (+ optional id), the *decision* is rich.** And since
Phase 5.97 ([[ATTRIBUTE-RICH-PRE-AUTHORIZATION]]), an app may register an `AbacResourceResolver` bean:
a declared `resourceId` is then **resolved at the gate**, the decision made on the instance's real
attributes + ancestor chain with the role looked up once on the governing root — per-instance,
attribute-based checks become declarative (the layer-2/3 boundary of ADR 0006 redrawn by ADR 0013:
layer 3 keeps list filtering, mid-transaction state guards, and the version guard). A minimal opt-in
`OpaAuthorizationManager` (request-level: HTTP method → action, path-prefix → type) is also provided for
apps that want a coarse request rule.

> **Affordance is a read-side layer, not a fourth enforcement layer.** Phase 6
> ([[ACTION-ENRICHMENT]]) attaches an `_actions` map to *returned* resources — *which actions the caller
> may perform on each* — **after** enforcement has decided, purely so a UI renders the right buttons. It
> never blocks a request and is never consulted by a decision: the three enforcement layers of ADR 0006
> stand untouched, and a present `_actions` map is advisory (the real gate denies independently). It
> *mirrors* enforcement (same resolved attributes, same governing-root role) so the buttons match what the
> gate would allow.

## Per-type policies

One rego document per resource type (`infra/opa/policies/{catalog,category,product}.rego`),
`default allow := false`, allowing when the action verb ∈
the role's **effective actions** for `input.resource.type` (category expansion minus denials,
[[PERMISSION-MODEL]]). Post-B4 (ADR [[0018-team-scoped-resource-isolation|0018]]) there is **no blanket
subject-role fallback** when no role definition is present — a resolved team role is required; the only
exception is the narrow `catalog:create` realm-role fallback in `catalog.rego`. See
[[TWO-LAYER-AUTHORIZATION]].

## Adoption recipe

1. Add the starter: `implementation(project(":opa-abac-spring-boot-starter"))` (+
   `spring-boot-starter-security`).
2. Configure:
   ```yaml
   opa:
     abac:
       enabled: true
       base-url: http://opa:8181
       policy-prefix: ""        # per-type resolver posts to /v1/data/<type>
       decision-field: allow
       subject:
         id-claim: sub
         roles-claim: realm_access.roles
         username-claim: preferred_username
   ```
3. Declare your own `SecurityFilterChain` and install the injected `AbacFilter` (the starter does **not**
   register a chain). Add `@EnableWebSecurity @EnableMethodSecurity`.
4. Provide a `RoleDefinitionSupplier` bean (a static map to start; an HTTP-backed one later).
5. Annotate controller methods with `@OpaPreAuthorize`.
6. Write per-type rego that reads `input.role_definition.permissions`.

> **Starter beans are all `@ConditionalOnMissingBean`.** Override the `OpaClient`,
> `RoleDefinitionSupplier`, `AbacSubjectExtractor`, or `PolicyPathResolver` with a single bean. The
> security beans only appear when Spring Security + web are on the classpath; the starter never registers
> a `SecurityFilterChain`.

## Verifying it

- Library unit tests: the OPA client (fail-closed paths), the extractor (claim mapping, no signature
  verification), the manager (allow/deny/fail-closed + the `RoleDefinition` reaches the input), the
  starter wiring (`ApplicationContextRunner`).
- Policy tests: `opa test infra/opa/policies/`.
- End-to-end: the allow/deny matrix in [[E2E-TESTING]] (`scripts/postman/run-matrix.sh`) — viewer reads
  200, viewer writes 403, editor writes succeed, through the real gateway.

## Related
- [[TWO-LAYER-AUTHORIZATION]] — gateway (coarse) vs app (fine-grained), and why the demo enricher was retired.
- [[DOMAIN-MODEL]] — the `AbacResource` resource side this consumes.
- [[ACTION-ENRICHMENT]] — the read-side `_actions` affordance layer that mirrors this enforcement (not a gate).
- [[E2E-TESTING]] — the allow/deny matrix.
