---
tags:
  - status/planned
  - type/project
  - area/abac
  - slice/B4
---

# 01 — Decomposition: multi-tenant isolation + self-service (Slice B4)

> T1…T9, in order. Each ticket is one focused commit. Pinned by ADR
> [[0018-team-scoped-resource-isolation|0018]] (isolation) + [[0019-pluggable-cross-service-ownership|0019]]
> (ownership). Design: [[00-DESIGN]]. QA ids: [[10-QA-TEST-CASES]].

## Critical path

```
T1 (policy) ─┬─► T2 (governed SPI) ──► T3 (governed endpoint) ──► T4 (list authorizer + ITs) ─┐
             └─► T5 (ownership SPI) ──► T6 (created-by endpoint) ──► T7 (createTeam wiring) ───┼─► T8 (gateway) ──► T9 (demo + e2e)
```

- **T1 is the spine** and **independently landable** (pure `opa test`, no app code) — the policy now
  fails closed; the app catches up in T4/T7.
- **Two parallel tracks** after T1: the **isolation** track (T2→T3→T4) and the **ownership** track
  (T5→T6→T7) are independent until they converge at the gateway (T8).
- **T8** (routing) needs the public endpoints to exist (T4/T7 add none gateway-facing, but T7's
  `createTeam` enforcement is what T8 exposes). **T9** (demo + the isolation matrix) is last — it
  proves the whole cut through the rig and re-runs every existing matrix.
- **Fail-closed is carried by every ticket** (each names its concrete no-widen edge in *What NOT to touch*).

---

## T1 — `catalog.rego` `filter` + fallback removal (3 policies) + narrow `create` fallback

**Goal.** Make the policy fail closed: a role-def-only catalog `filter` entrypoint, the realm-role
fallback removed from the single-decision path of all three policies, and a narrow `catalog:create`-only
fallback retained.

**Deliverables.**
- `infra/opa/policies/catalog.rego`: a new **`filter`** rule (role-def-only, no tag requirement → the
  category-style PE-friendly shape; **no** subject-roles fallback) + its `bulk`-parity unchanged; remove
  the two realm-fallback `allow` clauses (`not has_role_definition; subject.roles…`); add **one** narrow
  clause: `allow if { verb == "create"; not has_role_definition; "catalog-editor" in input.subject.roles }`.
- `infra/opa/policies/category.rego` + `product.rego`: remove the two realm-fallback `granted` clauses
  (no create concept here — categories/products are created under a governed catalog, so the resolved
  role already applies).
- `*_test.rego` updates for all three; the `permission_categories.json` table is **untouched**.

**Acceptance.** R1–R9 (`opa test` + `opa eval --partial` for R1–R3). `opa test` **all green** (count
rises for the new `filter`/`create`/removed-fallback cases). The catalog `filter` residual is verified
**empirically** with `opa eval --partial` (mx-f63604: don't assume the residual shape).

**What NOT to touch.** The `permission_categories` expansion table; `category`/`product` **`filter`**
rules (already role-def-only — unchanged); the tag-match helpers; `bulk`. **Fail-closed edge:** R2 (no
role-def → `filter` DENY_ALL) and R4 (fallback removed → single-GET deny) are the load-bearing cells —
a `filter` rule that compiled to ALLOW_ALL for a missing role would be a whole-table leak (mx-cbd39e).

---

## T2 — `GovernedScopeResolver` SPI + `HttpGovernedScopeResolver` (catalog), fail-closed

**Goal.** The library seam that produces the catalog list's base scope from team membership, and the
catalog's HTTP impl — fail-closed, never throws.

**Deliverables.**
- `opa-abac-spring-data`: `interface GovernedScopeResolver { <T> Specification<T> governedScope(String subject, String resourceType); }`
  (mirrors `AncestorResolver`; lives here because it returns a Spring Data `Specification`).
- `example-catalog-management-service` (`…catalog.config`): `HttpGovernedScopeResolver implements
  GovernedScopeResolver` — calls `GET {userService}/internal/governed-targets?subject&resourceType`,
  parses `[ids]`, returns `id IN (ids)` or an **always-false** Specification on empty/error. Reuses the
  existing HTTP client style (`HttpRoleDefinitionSupplier` shape); **classify-on-outage** (transport/parse
  failure → always-false, **no throw**) consistent with B2/B3.

**Acceptance.** U1–U4 (`./gradlew :opa-abac-spring-data:test` + the catalog module test) against an
in-process `HttpServer` stub. U3 (5xx/timeout → always-false, no throw) is the keystone.

**What NOT to touch.** `opa-abac-core` (stays Spring-free — `Specification` is a Spring type, so the SPI
is in spring-data). The role/resolve path (`RoleDefinitionSupplier` — separate seam). **Fail-closed
edge:** an always-false Specification on every failure (U2–U4); a throw here would 500 the list instead
of emptying it.

---

## T3 — user-service `GET /internal/governed-targets` endpoint

**Goal.** Expose "the catalog ids this subject governs" from the membership join (the source the T2
resolver calls).

**Deliverables.**
- `EffectiveRoleService.governedTargets(String subject, String resourceType): List<UUID>` — walks
  `subject → user → memberships → teams WHERE target_type=resourceType → distinct target_id` (reuses the
  existing `resolveForResource` join, collecting all matches).
- `InternalResolveController`: `GET /internal/governed-targets?subject=&resourceType=` → `200 [uuid,…]`
  (empty array for an unknown subject — never an error). `/internal/**` stays `permitAll` (in-network).

**Acceptance.** U11 + I12 (`./gradlew :example-user-management-service:test`, real Postgres): distinct
target_ids for a multi-team subject; `[]` for an unknown subject.

**What NOT to touch.** The existing `/internal/effective-role` + `/internal/tag-definitions` contracts
(additive — new endpoint only). The `TeamTargetMatcher` SPI. **Boundary:** this endpoint is
**in-network only** — it must NOT be added to the gateway routes in T8.

---

## T4 — `CatalogListAuthorizer` + `JpaSpecificationExecutor` + `listCatalogs` adoption + ITs

**Goal.** Wire the catalog list through `findAuthorized` with the governed scope as the base scope, so
the list isolates; prove single-GET also isolates (fallback removed) at all three levels.

**Deliverables.**
- **Build-breaker (same commit):** `CatalogRepository` — add `JpaSpecificationExecutor<CatalogEntity>`
  (it has `JpaRepository` + `LockableJpaRepository`; `findAuthorized` requires the executor).
- `example-catalog-management-service` (`…catalog.config`): `CatalogListAuthorizer` — resolves the
  governed scope via `GovernedScopeResolver`, builds the `AbacContext` (resource type `catalog`, unknown
  resource), calls the **paged** `AbacQueryService.findAuthorized(catalogs, governedScope, ctx, null,
  pageable)` (subtreeSpec = null; catalogs are roots). Mirrors `CategoryListAuthorizer`.
- `CatalogController.listCatalogs` — replace `catalogs.findAll(...)` behind the flat
  `@OpaPreAuthorize(catalog:list)` with the `CatalogListAuthorizer` call (the manager still extracts the
  subject; the list rows come from `findAuthorized`).
- Starter auto-config: `GovernedScopeResolver` wired via `ObjectProvider` (absent → the example
  `CatalogListAuthorizer` yields an empty page — fail-closed; the library default `@OpaPreAuthorize`
  path is unaffected for non-adopters).

**Acceptance.** I1–I5 (`./gradlew :example-catalog-management-service:test` + `:opa-abac-spring-data:test`,
real Postgres). I1 (two subjects, different row sets), I2 (none → empty), I3 (multi-team → union), I5
(single-GET non-member 403 at catalog/category/product). `./gradlew build` green.

**What NOT to touch.** `opa-abac-core`. The `filter` PE translator / `bulk` / pagination (reused as-is).
The `category`/`product` list authorizers (unchanged). **Fail-closed edge:** I2 + I4 — a missing
governed scope or absent resolver bean yields an **empty page**, never the full table.

---

## T5 — `ResourceOwnershipResolver` SPI + `DiscoveryOwnershipResolver` (registry + TTL cache)

**Goal.** The pluggable, type-keyed ownership resolver with config-driven service discovery and a TTL
cache — fail-closed.

**Deliverables.**
- Library SPI: `interface ResourceOwnershipResolver { boolean isOwner(String subject, String
  resourceType, UUID resourceId); }` (in `opa-abac-spring-security` — it needs no Spring Data type; keep
  it out of core).
- `DiscoveryOwnershipResolver implements ResourceOwnershipResolver`: a config registry
  `@ConfigurationProperties("abac.ownership")` → `Map<String,String> services` (type→base-URL); calls
  `GET {baseUrl}/internal/{type}/{id}/created-by`, compares `createdBy` to `subject`; a short-TTL cache
  on `(type,id)→createdBy`. Fail-closed: unknown type (no entry) / unreachable / `404` → `false`.
- Starter auto-config (`@ConditionalOnProperty`/`ObjectProvider` — present only when `abac.ownership.*`
  configured).

**Acceptance.** U5–U10 (`./gradlew :opa-abac-spring-security:test`) against an in-process `HttpServer`
stub. U7 (unknown type → false, **no call**), U8 (unreachable/404 → false), U9 (cache hit → no second
call), U10 (TTL expiry → re-fetch) are the keystones.

**What NOT to touch.** `opa-abac-core`. The `GovernedScopeResolver` (separate seam). **Fail-closed
edge:** every non-affirmative outcome (unknown/unreachable/404/mismatch) → `false`; a default-true or
throw-then-allow would re-open squatting.

---

## T6 — catalog `GET /internal/{type}/{id}/created-by` endpoint + confirm `created_by` = sub

**Goal.** The standard ownership-read contract on the catalog service (the resolver's data source).

**Deliverables.**
- `example-catalog-management-service`: an internal controller `GET /internal/catalog/{id}/created-by`
  → `200 {"createdBy": "<sub-uuid>"}` or `404`. Confirm `CatalogEntity.created_by` is populated (the
  `AuditorAware` returns the sub) — if the auditable columns are not present on the hierarchical base,
  add the mapping (same-commit).
- `/internal/**` `permitAll` (in-network; never gateway-routed).

**Acceptance.** I10 (`200 {createdBy}` == the creator sub) + I11 (missing → `404`)
(`./gradlew :example-catalog-management-service:test`, real Postgres — create a catalog as a known sub,
read it back).

**What NOT to touch.** The public `/api/v1/catalogs` contract. The `AuditorAware` semantics (it already
stores the sub). **Boundary:** in-network only — NOT a T8 gateway route.

---

## T7 — wire ownership into `createTeam` (public enforces, bootstrap bypasses) + IT

**Goal.** Close target-squatting: the public team-create verifies the caller owns the target; the
internal bootstrap path bypasses.

**Deliverables.**
- `TeamController.createTeam` (public) → before owner-on-create, call
  `ResourceOwnershipResolver.isOwner(callerSub, targetType, targetId)`; `false` → **403**
  (problem+json `errorCode`). The caller sub comes from `CallerIdentity` (the gateway-validated subject).
- `InternalBootstrapController.ensureTeam` (`/internal/bootstrap/teams`) — **unchanged** (bypasses the
  check; trusted in-network seam). Make the resolver a constructor dependency only on the public path
  (or guard by an injected flag) so the bootstrap controller does not enforce.
- `ResourceOwnershipResolver` wired via `ObjectProvider` — absent → the public `createTeam` denies if
  it cannot verify (fail-closed); bootstrap still works.

**Acceptance.** I6–I9 (`./gradlew :example-user-management-service:test`, real Postgres + in-process
catalog `created-by` stub). I7 (non-owner → 403), I8 (resolver can't verify → 403), I9 (bootstrap path
bypasses) are the keystones.

**What NOT to touch.** The `/internal/bootstrap` contract (the seed depends on it). The owner-on-create
+ subset-rule logic (unchanged — this gates *before* it). **Fail-closed edge:** I8 — an unverifiable
ownership check denies, never defaults to allow.

---

## T8 — gateway routing: `usermgmt-pool` + `/api/v1/teams*` `/api/v1/users*`

**Goal.** Route the public self-service endpoints through APISIX (bearer-validated) so `CallerIdentity`
gets the injected sub; keep `/internal/**` off the gateway.

**Deliverables.**
- `infra/apisix/init-routes.sh`: a new `usermgmt-pool` upstream (`usermgmt:8080`) + routes
  `usermgmt-teams` (`/api/v1/teams*`) and `usermgmt-users` (`/api/v1/users*`) at a priority above the
  catalog catch-all, carrying the **same `openid-connect`** bearer validation as the catalog routes.
  **No `/internal/**` route.** Gated under `ENABLE_SPA`/`ENABLE_USER_SERVICE`.
- `deploy.sh`: ensure `usermgmt` is reachable in-network as `usermgmt:8080` (already is under
  `ENABLE_USER_SERVICE`); pass-through unchanged.
- `infra/README.md`: document the user-mgmt gateway routes + the `/internal`-stays-off-gateway invariant.

**Acceptance.** Manual/scripted curl through `:9085`: `POST /api/v1/teams` with a valid bearer reaches
user-mgmt with the sub injected (E2/E7 exercise this in T9); `GET /api/v1/users` returns the roster;
`GET :9085/internal/governed-targets` → **404/not-routed** (the in-network endpoint is NOT exposed).

**What NOT to touch.** The catalog routes (`/api/v1/catalogs*` → catalog-pool, distinct prefix). The
Keycloak `/realms/*` proxy routes (from the SPA slice — independent). **Boundary:** `/internal/**` must
never be gateway-exposed (it is `permitAll` + `trust-forwarded-jwt`).

---

## T9 — demo users alice/bob/carol + seed + e2e isolation matrix + docs/roadmap/Mulch

**Goal.** Prove the whole cut through the rig: self-service onboarding, scoped member access,
multi-team, no direct-id leak, squat denied — and every existing matrix still green.

**Deliverables.**
- `infra/keycloak/realm-export.json`: users `alice`/`bob`/`carol` (realm role `catalog-editor`).
- `scripts/postman/seed-demo-data.sh` (or a B4 sibling): pre-seed alice/bob/carol as user-service users
  (the add-member directory); pre-seed Carol's own team+catalog + her membership in Alice's team
  (multi-team). Alice's create+add stays **live** (the matrix performs it).
- `scripts/postman/run-isolation-matrix.sh` + collection: E1–E7 through the gateway.
- `infra/README.md` section for the isolation matrix; `docs/guides/TEAM-BASED-AUTHORIZATION.md` (or a new
  `MULTI-TENANT-ISOLATION` guide) reconciled; `POC-ROADMAP.md` B4 row → shipped; a Mulch `opa-abac`
  record + an `autonomous-runs` retro.
- Re-run **all** existing matrices (E8) — green.

**Acceptance.** `run-isolation-matrix.sh` green (E1–E7); every existing matrix green (E8); `opa test`
green; `./gradlew build` green.

**What NOT to touch.** The existing matrices' assertions (they should pass **unchanged** — if one needs
a team membership added, that's a real regression to flag, not silently patch). The SPA branch (separate
PR). **Fail-closed edge:** E6 (direct-id 403) + E7 (squat 403) are the headline isolation cells.

---

## Cross-cutting acceptance

- `./gradlew build` green (all modules + integration tests, real Postgres).
- `opa test` green (count updated for the new `filter`/`create`/fallback-removed cases).
- `run-isolation-matrix.sh` green **and** every existing matrix green (no regression from fallback removal).
- `opa-abac-core` has **zero** new Spring imports (the SPIs are in spring-data / spring-security).
- The **fail-closed** invariant holds on every error path: missing governed scope → empty list; missing
  role-def → `filter` DENY_ALL; unverifiable ownership → team-create 403; resolver outage → always-false /
  false, never a throw-that-widens.
