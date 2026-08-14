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

### The decision envelope — an optional, structured `deny_reason` (ADR 0030 §6)

`result.allow` is a boolean across the client, both authorization managers and the policy convention.
Some denies, though, are **answerable**: the subject would be allowed if they re-authenticated with a
stronger, fresher factor. Rather than version the envelope, that case travels as an **optional,
omitted-when-absent** `deny_reason` object beside `allow`:

```jsonc
{"allow": true}                                   // unchanged
{"allow": false}                                  // unchanged — a plain deny
{"allow": false,                                  // an ANSWERABLE deny
 "deny_reason": {"type": "insufficient_user_authentication",
                 "required_acr": "aal2", "max_age": 300}}
```

Every existing consumer keeps reading `allow` and is unaffected when the field is absent. A caller that
wants the reason asks for it explicitly:

```java
OpaDecision decision = opaClient.decide(context);   // OpaDecision(boolean allow, DenyReason denyReason)
if (!decision.allow() && decision.hasCompleteReason()) {
    DenyReason reason = decision.denyReason();      // type, requiredAcr, maxAge
}
```

`OpaClient.decide` is a **`default` method** delegating to `allow` with a `null` reason, so every
implementation written before it existed compiles and behaves identically — the additive move, chosen
over an envelope version deliberately (ADR 0030 §6). **Two caveats, both about existing code rather
than behavior:**

- **Mocks.** A Mockito `mock(OpaClient.class)` does not run default methods, so a test that stubs
  only `when(client.allow(any()))` now gets `null` from `decide()` — which the gate reads as a
  fail-closed deny. Tests that mock `OpaClient` must stub `decide(...)` (e.g.
  `thenReturn(OpaDecision.of(true))`), or use a real stub class so the default runs.
- **Name collision.** An existing implementation that already declares its own
  `decide(AbacContext)` member no longer compiles against the interface unless the signature is
  compatible — a source-incompatibility the additive default cannot avoid. Rename the local member
  (the in-repo precedent is `verdictFor`). Implementations without such a member are unaffected.

**Four fail-closed rules, each landing at the layer it arises:**

| Situation | Result | Why |
|---|---|---|
| A **malformed** reason on the wire (wrong types, missing field, not an object) | plain deny, reason **dropped** | A reason whose types are not the contract is a policy the library does not understand. Types are checked by hand rather than coerced — Jackson would turn `"300"` into a window the library then advertises. |
| A reason accompanying **`allow: true`** | allow, reason dropped | An allow is an allow; a document carrying both is contradictory. |
| Transport failure, non-200, breaker open, retries exhausted | plain deny, **never a fabricated reason** | A reason promises "re-authenticating clears this". During an outage that promise is false, and the client would loop on a factor that changes nothing. |
| A reason **missing any field** at the enforcement point | the ordinary 403 | See `hasCompleteReason()` — a challenge without its window is an infinite challenge loop (ADR 0030 §7). |

**A decorator MUST override `decide`.** A wrapper that implements `OpaClient` and overrides only
`allow` inherits the default — which calls the *wrapper's own* `allow` — so the delegate's reason is
silently swallowed and every answerable deny degrades to a plain one, with nothing failing and nothing
logged. `ResilientOpaClient` overrides it for exactly this reason, and a test asserts the call reaches
the delegate.

**Reasons are a single-decision concern.** `compile` (the list residual) and `allowAll` (the batch)
stay boolean: a residual is a row predicate and a batch is an affordance list, and neither is a request
a client could re-authenticate for.

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

> **Batch + request memo (Slice 7.3).** `lookupAll(userId, Set<ResolveTarget>)` is the seam's **batch
> form** ([[0024-batch-role-resolution|ADR 0024]]): a `default` method (every lambda/impl stays valid)
> answering **exactly one two-state entry per requested target** (`of`/`empty`); the *outage* is
> **whole-batch** — any unknown answer throws for the whole call, and a short/extra/duplicate response is
> malformed = outage (strict completeness — a partial body never yields partial roles). The enrichment
> advice resolves each page's distinct governing roots through it in one exchange. Above the supplier, the
> starter wraps the bean in a **request-scoped memo** ([[0023-request-scoped-resolution-memoization|ADR
> 0023]]): one request sees exactly one resolve answer per `(userId, type, id)` — all three tri-state
> outcomes replayed, including the throw — governed by `opa.abac.resolve-memo.enabled` (default **on**;
> one flag also covers the ancestor-chain memo). A resolve answer is a per-request snapshot: a mid-request
> role change lands at the next request boundary, and nothing survives the request.
>
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

### Root-attribute enrichment — `input.resource.root_attributes` (ADR 0032)

Tags are **leaf-scoped**: `input.resource.attributes` on a product is the product's own tag map, and
nothing is inherited. So a policy that wants to gate a *child* read on something written on the
**governing root** (the production tier is the first such case — see [[TAG-BASED-AUTHORIZATION]]) needs
the root's tags in the input.

With resource resolution on, the manager supplies them. Whenever the **governing target is distinct
from the decided leaf** — the ancestor chain's root on an instance check, the
`roleResourceType`/`roleResourceId` override target on a type-level child gate — it resolves that target
through the app's existing `AbacResourceResolver` and threads its tag map into the context as
`input.resource.root_attributes`. The resolve is **read-through-memoized** in the request cache, so a
request pays at most one extra resolver call however many checks it runs, and every check in that
request sees **one coherent snapshot** of the root.

**Three states, and the whole contract is that they stay distinguishable:**

| Wire state | Meaning |
|---|---|
| key **absent** | enrichment failed, or was never attempted — the root's state is **unproven** |
| `{}` | the root was fetched and carries **no tags** |
| `{"env": "production", …}` | the root was fetched and is tagged |

Hence the field is serialized `NON_NULL` and **never `NON_EMPTY`**: under `NON_EMPTY` an untagged root's
empty map would vanish and become indistinguishable from a failed fetch — merging the two states the
contract exists to separate, in the open direction.

**Failure is narrow by construction.** A resolver that returns empty, throws, or is not configured
leaves the field **absent** — never an exception out of the manager, never a deny by itself, never a
5xx. The policy decides what absence means, and that is the point: a decision that never reads the field
is untouched, while a decision that gates on it treats "unproven" as closed.

> **The Rego trap, worth repeating.** Testing the field with a bare
> `not input.resource.root_attributes.env == "production"` reads naturally and is **wrong** — an absent
> value passes a negated comparison. Give the absent state its own clause:
>
> ```rego
> denied if { …; not input.resource.root_attributes }               # unproven
> denied if { …; input.resource.root_attributes.env == "production" } # proven production
> ```

The field is **additive**: `AbacContext.Resource` keeps its prior constructor arities, an unenriched
resource serializes byte-for-byte as before, and nothing enters the partial-evaluation `filter` input —
a list's tier decision belongs at the coarse gate, never in the SQL residual.

*One consequence to know if you read the request cache:* the root memo is written **decision-
independently** (after a successful resolve, before the OPA call), so a cache entry is a *resolved*
snapshot and is no longer necessarily an *authorized* one. The decided leaf is still always resolved
fresh, so no decision can read its own cached answer.

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
