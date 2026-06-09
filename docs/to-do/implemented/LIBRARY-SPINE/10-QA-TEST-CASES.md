---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/spring-security
  - area/build
---

# Library spine — QA test cases

> The concrete cases the unit + integration + e2e work in [[01-DECOMPOSITION]] must satisfy.
> Grouped by layer. Each row names a check; the implementer turns it into a test or a manual step.

## Unit — `HttpOpaClient` (core, in-process HTTP stub) — ticket 1

| # | Case | Expected |
|---|------|----------|
| U1 | OPA returns `200 {"result": true}` (decisionField=allow → `{"result":{"allow":true}}`) | `allow(ctx)` = `true`. |
| U2 | OPA returns the deny document | `allow(ctx)` = `false`. |
| U3 | OPA returns `500` | `false` (fail-closed). |
| U4 | Connection refused (server down / bad port) | `false`; no exception escapes. |
| U5 | Handler sleeps past the per-request timeout | `false` (timeout fail-closed). |
| U6 | Malformed body (`200 not-json`) or missing decision field | `false`. |
| U7 | Request body shape | captured body is `{"input":{subject,…,resource,role_definition,environment}}`; serialization matches the record component names. |
| U8 | Resolved path | `PerTypePolicyPathResolver` with `policyPrefix` + `resourceType="product"` → the expected `/v1/data/…/product` URL. |
| U9 | `RoleDefinition` defensive copies | mutating an input map after construction does not change the record's `attributes`/`permissions`. |
| U10 | `NoOpRoleDefinitionSupplier.lookup(...)` | always `Optional.empty()`. |

## Unit — extraction (`JwtClaimsSubjectExtractor` / `AbacFilter`) — ticket 2

| # | Case | Expected |
|---|------|----------|
| U11 | Well-formed Keycloak-shaped JWT | Subject with id (`sub`), roles (`realm_access.roles`), `preferred_username` attribute. |
| U12 | Missing `sub` | `Optional.empty()`. |
| U13 | Missing roles claim | Subject with empty roles (not a failure). |
| U14 | Flat `roles` claim (configured) | roles mapped from the configured path. |
| U15 | Expired `exp` (validateExpiry on) | `Optional.empty()`. |
| U16 | 2-segment / non-JSON payload | `Optional.empty()` (no exception). |
| U17 | No `Authorization` header | `Optional.empty()`. |
| U18 | Configurable claim paths | honored (id/roles/username read from configured names). |
| U19 | `AbacFilter` with a valid token | `SecurityContextHolder` holds an `AbacAuthentication` whose Subject matches; chain continued. |
| U20 | `AbacFilter` with no/invalid token | context left anonymous; chain still continued; no throw. |
| U21 | **No signature verification performed** | a token with a garbage signature still extracts (the app trusts the gateway). |

## Unit — enforcement (`OpaPreAuthorizeAuthorizationManager`) — ticket 3

| # | Case | Expected |
|---|------|----------|
| U22 | OPA allows | `AuthorizationDecision(granted=true)`. |
| U23 | OPA denies | `granted=false` → `AccessDeniedException` at the interceptor. |
| U24 | `OpaClient` errors / fails closed | `denied` (fail-closed). |
| U25 | Unauthenticated (no `AbacAuthentication`) | `denied`. |
| U26 | Unresolvable resource (SpEL yields nothing) | `denied` (clear log). |
| U27 | `RoleDefinitionSupplier` consulted | `lookup(userId,type,id)` called; the returned `RoleDefinition` is on the `AbacContext` sent to OPA (ArgumentCaptor). |
| U28 | Captured `AbacContext` | action + resource type/id match the annotation; `role_definition` present. |
| U29 | SpEL resolution | `resourceType="'catalog'"` → type-only resource; `resourceId="#id"` → id from the arg; `resource="#product"` → the `AbacDataObject`. |

## Unit / slice — starter wiring (`ApplicationContextRunner`) — ticket 4

| # | Case | Expected |
|---|------|----------|
| U30 | `opa.abac.enabled=true` + security on classpath | `OpaClient`, `PolicyPathResolver`, `RoleDefinitionSupplier`, `AbacSubjectExtractor`, `AbacFilter`, `OpaPreAuthorizeAuthorizationManager` beans present. |
| U31 | `opa.abac.enabled=false` | none of the spine beans present. |
| U32 | User-defined `@Bean OpaClient` / `@Bean RoleDefinitionSupplier` | starter backs off (`@ConditionalOnMissingBean`). |
| U33 | `FilteredClassLoader` removes spring-security/web | only the core client/resolver beans; no security beans; no `SecurityFilterChain` created by the starter. |
| U34 | Property binding | `decisionField`, `subjectRolesClaim`, `validateExpiry`, etc. bind from `opa.abac.*`. |

## Integration / policy — example + rego — ticket 5

| # | Case | Expected |
|---|------|----------|
| I1 | `opa test infra/opa/policies/` | the per-type `*_test.rego` pass; reads allowed for viewer/editor role defs, writes only for editor. |
| I2 | App boots with security + `ddl-auto: validate` | clean boot; no schema change; `AbacFilter` installed. |
| I3 | `CatalogCrudIT` / `ProductConcurrencyIT` (existing) | pass **unchanged** under the permissive test setup (`opa.abac.enabled=false` / mock auth + stub `OpaClient`). |
| I4 | `DemoRoleDefinitionSupplier` | `catalog-viewer` → read perms on each type; `catalog-editor` → +write. |
| I5 | `AuditorAware` | with an authenticated `AbacAuthentication`, `created_by`/`last_modified_by` = the Subject `sub` UUID; empty when unauthenticated. |

## E2E — through the gateway (rig up) — ticket 7

| # | Case | Expected |
|---|------|----------|
| E1 | Viewer token → GET catalogs/category/product | `200` (read allowed). |
| E2 | Viewer token → POST/PUT/DELETE | **`403`** (write denied — `@OpaPreAuthorize` deny → `AccessDeniedException`). |
| E3 | Editor token → create→get→update→delete chain | `201`/`200`/`204` (write allowed). |
| E4 | Tokens minted **in-network** | per the rig's issuer rule (`keycloak:8888`); injected by `run-tests.sh`. |
| E5 | Suite stable across reruns | green twice; chained ids in collection scope. |

## Cross-cutting

| # | Case | Expected |
|---|------|----------|
| X1 | `./gradlew build` | all modules + example + codegen + ITs green. |
| X2 | `opa-abac-core` dependencies | Jackson + SLF4J + JDK only (no Spring, no Feign). |
| X3 | Clean-room scan of the diff | no proprietary company/project names, internal prefixes, token prefixes, local paths, or ticket ids (the concrete scan list is maintainer-local, per the IP boundary in `CLAUDE.md`). |
| X4 | Fail-closed | every OPA error/timeout/ambiguity denies, at both the client and the manager. |
