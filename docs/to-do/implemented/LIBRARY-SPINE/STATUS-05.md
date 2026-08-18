---
tags:
  - status/done
  - type/project
  - area/catalog-service
  - area/abac
---

# STATUS — Ticket 5: Example adoption (security chain, demo role defs, annotations, per-type rego, retire enricher)

> Filled in at the ticket-5 checkpoint. See [[01-DECOMPOSITION]] ticket 5.

**Status:** ✅ implemented (2026-06-01)

## What shipped
- **Build:** `example-catalog-management-service` now depends on
  `:opa-abac-spring-boot-starter` (brings the security spine + auto-config) and declares its own
  `spring-boot-starter-security`.
- **`SecurityConfig`** (`@EnableWebSecurity @EnableMethodSecurity`): stateless `SecurityFilterChain`,
  CSRF off, installs the injected `AbacFilter` before `AuthorizationFilter`, permits
  health/swagger/api-docs, requires auth on `/api/v1/**`. `AbacFilter` injected via `ObjectProvider`
  (a permissive test profile without the filter still builds a chain). A `FilterRegistrationBean`
  (disabled) stops Boot from also auto-registering `AbacFilter` outside the chain.
- **`DemoRoleDefinitionSupplier`** (`@Component`): maps the caller's realm roles → `RoleDefinition`
  (`catalog-viewer` → read on catalog/category/product; `catalog-editor` → +write). Overrides the
  starter's no-op (the documented Phase-4 swap seam).
- **Controllers annotated** with `@OpaPreAuthorize` — all 15 endpoints across Catalog/Category/Product
  (reads `<type>:read`, writes `<type>:write`, by-id ops carry `resourceId="#<idArg>"`).
- **`AuditingConfig`** now reads the real principal: the `sub` UUID of the current `AbacAuthentication`
  (empty when unauthenticated or non-UUID) — replacing the fixed `DEMO_PRINCIPAL`.
- **Per-type rego** (`infra/opa/policies/{catalog,category,product}.rego` + `*_test.rego`) authored via
  `/rego-skill`: `package <type>`, `default allow := false`, allow when the action verb ∈
  `input.role_definition.permissions[input.resource.type]`, falling back to subject roles when no role
  definition. `gateway.rego` kept coarse.
- **Enricher retired:** removed the `serverless-pre-function` wiring from `infra/apisix/init-routes.sh`
  and **deleted `infra/apisix/enricher-plugin.py`**; APISIX keeps `openid-connect` (validates + forwards
  the bearer); the app does Spring-native extraction.
- **`deploy.sh`:** pods get `OPA_ABAC_ENABLED/BASE_URL/POLICY_PREFIX` — enabled only when
  `ENABLE_OIDC=1 && ENABLE_OPA=1` (no token ⇒ nothing to authorize ⇒ fail-closed), policy prefix empty
  (the per-type resolver posts to `/v1/data/{type}`).
- **Permissive test setup:** `PermissiveSecurityTestConfig` provides an always-editor
  `AbacSubjectExtractor` + an allow-all `OpaClient`, imported by `AbstractPostgresIT`. The
  persistence/concurrency ITs run through the real secured chain without a token.

## Tests
- `./gradlew build` — **BUILD SUCCESSFUL** (all modules + example + OpenAPI codegen + ITs).
- Example ITs — **8 passed, 0 failed**: `CatalogCrudIT` (×3, incl. the full create→get→delete walk
  through the secured chain), `BaseEntityAuditingIT` (×3 — boot under `ddl-auto: validate` is the
  schema-match proof I2; auditor now records the authenticated principal I5), `ProductConcurrencyIT`
  (×2). (I3 — the ITs stay green under the permissive setup.)
- `opa test infra/opa/policies/` — **27/27 PASS** on OPA 1.10.1 (I1): viewer reads / viewer-write denied /
  editor read+write, the no-role-definition fallback, default deny. `opa check` + `opa fmt` clean.
- `:opa-abac-spring-security:test` — **30 passed** (added the `AbacFilter` anonymous-override cases).

## Architecture review + refactor
Ran the gate against `00-DESIGN.md`:
- **Starter does not seize the chain** — confirmed: the app declares its own `SecurityFilterChain` and
  installs `AbacFilter`; the starter only supplied beans.
- **Pluggability** — `DemoRoleDefinitionSupplier` overrides the no-op via `@ConditionalOnMissingBean`,
  proven live; the single-bean Phase-4 swap holds.
- **Fail-closed** — the per-type rego defaults deny; the `@OpaPreAuthorize` path fails closed.

**Two real bugs the integration validation surfaced (fixed + regression-tested) — exactly what the gate
is for:**
1. **Starter must not expose a primary `ObjectMapper`.** The starter had a
   `@Bean @ConditionalOnMissingBean ObjectMapper`; this made Boot's `JacksonAutoConfiguration` back off,
   so the app's MVC mapper lost JSR-310 and every `OffsetDateTime` (audit `createdAt`,
   `ApiError.timestamp`) failed to serialize — surfacing as 500s deep in the CRUD IT. Fixed: inject
   `ObjectProvider<ObjectMapper>` and `getIfAvailable(ObjectMapper::new)` in both the OPA client and the
   extractor beans. Mulch failure `mx-6023c0`.
2. **`AbacFilter` must take precedence over an anonymous token.** It set the context only when
   `getAuthentication()==null`, but installed after Spring Security's `AnonymousAuthenticationFilter` it
   saw a non-null anonymous token, skipped, and every `/api/**` request 403'd. Fixed: extract when the
   current authentication is null **or** an `AnonymousAuthenticationToken` (still never overwriting a real
   one). Added two `AbacFilterTest` cases. Mulch failure `mx-410831`.

No invented churn beyond these two genuine fixes.

## Integration / e2e
`./gradlew build` (the example ITs incl. the `ddl-auto: validate` boot) + `opa test` — both green. The
full rig + newman allow/deny matrix is T7.

## Decisions recorded
Two Mulch **failures**: `mx-6023c0` (starter must not register a primary ObjectMapper) and `mx-410831`
(JWT filter must override an anonymous authentication). `ml sync` → `.mulch`-only commit `ff73faf`.

## Commit
`feat(example): adopt the library — security chain, demo role defs, @OpaPreAuthorize, per-type rego, retire enricher`.
