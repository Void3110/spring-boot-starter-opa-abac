---
tags:
  - status/planned
  - type/design
  - area/abac
  - area/opa
  - area/spring
  - slice/B4
---

# 00 — Design: Multi-tenant isolation + self-service (Slice B4)

> **Status:** Design settled (grill-me, 2026-06-29). Input to `/decompose`. Pinned by
> ADR [[0018-team-scoped-resource-isolation|0018]] (isolation / membership-as-sole-access-path) and
> ADR [[0019-pluggable-cross-service-ownership|0019]] (pluggable cross-service ownership resolution).

## 1. The problem, precisely

The rig has **no tenant isolation at the catalog level**. Three authenticated users — `viewer`,
`outsider`, `editor` — each see **all 13 catalogs**, and `catalog-editor` can `PUT` any of them.
Two distinct leaks:

1. **The catalog LIST** (`listCatalogs`) is a flat `@OpaPreAuthorize(action="catalog:list")` on the
   single-decision **`allow`** path, which carries the **realm-role fallback** (`catalog.rego`
   lines 45–59): `catalog-viewer` → READ on *any* catalog, `catalog-editor` → READ+WRITE+TAG on
   *any* catalog. So every authenticated user lists every catalog. `catalog.rego` has **no `filter`
   entrypoint** (unlike `category.rego`).
2. **Single-GET / update / delete** of *any* catalog (and category/product) succeeds via the same
   realm-role fallback — so even after the list is scoped, a direct `GET /catalogs/{other-id}` would
   still leak.

The honest model the system *intends* is team-governance: a **team** (`target_type='catalog'`,
`target_id=<catalog id>`) governs a catalog, and **membership** in that team is what grants access.
The realm fallback predates the team model and **contradicts** it. This slice makes **team membership
the sole access path** to the catalog hierarchy, and adds the **self-service** flow (a new user
creates a catalog + team, adds members) the isolation makes meaningful — with a **real cross-service
ownership check** so team-create cannot squat another user's catalog.

## 2. The mechanism

Catalog visibility is a **membership join** (which team governs this catalog) that lives in the
**user-service**, keyed by the exact catalog id — *not* an attribute on the catalog row. So OPA
partial-evaluation (which filters on `input.resource.*` columns/tags) has nothing to bite on for
catalogs. The visible-catalog set is "the `target_id`s of the teams I'm a member of." Two SPIs and a
policy change realize this.

### 2a. Catalog-list isolation — `GovernedScopeResolver` SPI (library, `opa-abac-spring-data`)

```java
public interface GovernedScopeResolver {
    /** A Specification selecting exactly the rows of resourceType the subject governs (via team
     *  membership). Fail-closed: an always-false predicate on any breach; does NOT throw. */
    <T> Specification<T> governedScope(String subject, String resourceType);
}
```

- Subject-keyed (the IdP `sub`), **not** role-keyed — governance is a pure membership question; OPA's
  `filter` residual AND-s the role's `list` grant on top.
- Fail-closed via an **always-false** predicate, never throws (mirrors `AncestorResolver.subtreeOf`).
- The example impl `HttpGovernedScopeResolver` (catalog) calls the new user-service endpoint
  `GET /internal/governed-targets?subject={sub}&resourceType=catalog → ["<id>", …]`, builds
  `id IN (those ids)` (an empty/failed result → always-false → empty list).

**Composition.** A new `CatalogListAuthorizer` (example, mirroring `CategoryListAuthorizer`) replaces
`listCatalogs`'s flat gate with the paged `AbacQueryService.findAuthorized`:

```
findAuthorized(catalogRepo, governedScope, queryContext, /*subtreeSpec*/ null, pageable)
  == governedScope.and( opaListResidual ).and( notDenied )
```

The **governed-id Spec is the base `scope`** (the AND-gate everything composes inside — nothing
escapes scope), **not** an OR-widener like `subtreeSpec`. A new `catalog.rego` **`filter`** rule
(role-def-only, no tag requirement) yields an `ALLOW_ALL` residual when the role grants `list` and a
`DENY_ALL` residual otherwise — so `scope ∧ ALLOW_ALL` = the governed set, `scope ∧ DENY_ALL` = empty.
Catalogs are roots → `subtreeSpec = null`.

### 2b. Fallback removal (all three policies — `catalog` / `category` / `product`)

Remove the realm-role fallback from the **single-decision path** (`allow` in `catalog.rego`,
`granted` in `category.rego` / `product.rego`) — **unconditional**. Membership becomes the only access
path at every level, so a direct `GET /catalogs/{id}/categories/{cid}` for a non-member fails closed.
A **narrow `catalog:create`-only** fallback is **retained** (see §2d). Category/product **lists**
already isolate (their role-def-only `filter` + `catalogId` scope) and are **unchanged**.

### 2c. Cross-service ownership — `ResourceOwnershipResolver` SPI (library)

```java
public interface ResourceOwnershipResolver {
    /** Is `subject` the owner/creator of (resourceType, resourceId)? Fail-closed: false on breach. */
    boolean isOwner(String subject, String resourceType, UUID resourceId);
}
```

Default impl `DiscoveryOwnershipResolver`:
- A **config-driven** type→base-URL registry: `abac.ownership.services.<type> = <url>` (cached as
  ~static). Adding an owning service = **one config line** + that service implementing the standard
  contract — no per-service code dependency.
- Calls the standard contract `GET /internal/{resourceType}/{resourceId}/created-by → 200 {createdBy}`
  (or `404`), compares `createdBy` to the caller `sub`.
- A short-TTL **cache** on the `(type,id)→createdBy` result (subject-independent key → good hit rate).
  Ownership-transfer staleness up to the TTL is **documented**; event-invalidation is a follow-up.
- Fail-closed: unknown type (no registry entry) / unreachable / `404` → **not owner**.

**Identity join key = the `sub`.** Catalog `created_by` already holds the sub (its `AuditorAware`
returns `UUID.fromString(abac.getSubject().id())`); user-service `User.subject` holds the same sub.
Sub-vs-sub compare. A non-UUID sub → `created_by` null → ownership fails closed.

### 2d. Self-service flow

- **`catalog:create`** is type-level (no resourceId) → cannot be team-scoped (no instance/team exists
  yet). A **narrow realm-role fallback** grants `create` only (verb-gated `allow` clause); all other
  verbs require a resolved role. So: realm role `catalog-editor` = "may onboard new catalogs"; team
  membership = "what you can access."
- **Team-create ownership check.** `createTeam(targetType=catalog, targetId=X)` (public, gateway path)
  calls `ResourceOwnershipResolver.isOwner(callerSub, "catalog", X)` → mismatch / unverifiable →
  **403** (fail-closed). Closes the "target squatting" hole. The **`/internal/bootstrap`** path
  **bypasses** the check (trusted in-network admin seam, `permitAll`, never gateway-exposed) — the
  seed and the e2e matrices keep working.
- **Gateway routing.** Route the public self-service prefixes through APISIX to a new `usermgmt-pool`
  upstream (`:28090`), bearer-validated: `/api/v1/teams*`, `/api/v1/users*`. **`/internal/**` is
  NEVER gateway-exposed** (it is `permitAll` + `trust-forwarded-jwt` — direct exposure would let
  anyone forge a `sub`). Distinct prefixes (`/api/v1/catalogs*` → catalog-pool) — no collision.
- **Add-member UX.** `addMember` takes an internal `userId`; the SPA powers a **dropdown** from
  `GET /api/v1/users` (the "directory" — in production backed by LDAP/SCIM). Demo users are
  pre-seeded so the picker is named, not a UUID-paste.

## 3. Behavior matrix (the cells that change — and the ones that must not)

| Subject (realm role) | team-bound to catalog X? | `GET /catalogs` (list) | `GET /catalogs/X` | `GET /catalogs/Y` (not bound) | `POST /catalogs` (create) |
|---|---|---|---|---|---|
| no role-def, `catalog-viewer` | no | **[] (was: all 13)** | **403 (was: 200)** | **403 (was: 200)** | 403 |
| no role-def, `catalog-editor` | no | **[] (was: all 13)** | **403 (was: 200)** | **403 (was: 200)** | **201 (narrow create fallback)** |
| resolved role on X (member) | yes | **[X] (only X)** | 200 | **403** | 201 (if `catalog-editor`) |
| member on X+Z (multi-team) | yes (X,Z) | **[X,Z]** | 200 (X,Z) | 403 (others) | 201 |

**Team-create:** owner of X → team on X **succeeds**; non-owner of Y → team on Y **403** (squatting
closed). `/internal/bootstrap/teams` → unchanged (bypass).

## 4. What this slice does NOT change

- **No ReBAC** (membership-join-in-policy) — that stays Phase 8. Governance is resolved app-side.
- **Category/product LIST** paths — already isolate (role-def-only `filter`); untouched.
- **The `filter` PE residual translator**, the `bulk` allowlist, pagination, hierarchy, enrichment —
  all reused as-is. The catalog `filter` rule is additive.
- **The `/internal` resolve/bootstrap contracts** — additive endpoints only; existing ones unchanged.
- **`opa-abac-core`** — stays Spring-free; both new SPIs return `Specification`/use `UUID`, so they
  live in `opa-abac-spring-data` (governed-scope) and the appropriate Spring module (ownership).

## 5. Gating & fail-closed (the security crux)

- Realm-fallback removal (§2b) and the narrow `create` fallback (§2d) are **unconditional** — a fix,
  not a feature; no re-enable flag (the B2 lesson: *the off-ramp would be the vuln*).
- **`GovernedScopeResolver` absent** → catalog list fails closed to **empty** (no per-id allowlist
  escape hatch). The *isolation* is opt-in **example wiring** (the `CatalogListAuthorizer` bean); the
  library default (plain `@OpaPreAuthorize(catalog:list)`) is unaffected — so the 8 e2e matrices,
  which don't use `CatalogListAuthorizer`, keep working.
- **`ResourceOwnershipResolver` absent** → public `createTeam` denies if it cannot verify (fail-closed
  on the gateway path); the `/internal/bootstrap` path bypasses regardless (trusted).

**Verified low blast radius:** no e2e matrix lists top-level `/api/v1/catalogs` and asserts a count;
the matrices already avoid the fallback (they always bind a role definition — the policy prefers a
role-def over the fallback) and treat bare-realm users (`outsider`/`stranger`) as the **deny/empty**
case — exactly what removal produces. The one preserved dependency is
`run-permission-categories-matrix`'s reliance on the fallback for `catalog:create`, which §2d's narrow
create fallback retains. The SPA smoke test asserts status-only (empty-body `200` still passes).

## 6. Proof obligations (QA skeleton — cases get ids in 10-QA)

**Policy (`opa test`):**
- `catalog.rego` `filter`: role-def with `list` → ALLOW_ALL residual; no role-def → DENY_ALL (the
  fail-closed boundary); a role denying `list` → DENY_ALL. Single-decision `allow`: removed fallback ⇒
  bare realm role denied for view/update/delete; the **narrow `create`** clause ⇒ `catalog-editor`
  granted `create`, `catalog-viewer` denied.
- `category.rego` / `product.rego`: bare realm role denied (fallback removed); a resolved role still
  grants. (Their `filter` rules unchanged.)

**SPIs (unit):**
- `GovernedScopeResolver`: governed ids → `id IN` Spec; empty / HTTP error / unknown → **always-false**
  (never throws). `HttpGovernedScopeResolver` against an `HttpServer` stub: classify-on-outage.
- `ResourceOwnershipResolver` / `DiscoveryOwnershipResolver`: owner sub → true; mismatch → false;
  unknown type / unreachable / `404` → false; cache hit skips the call; TTL expiry re-fetches.

**Service ITs (real Postgres):**
- Catalog list: two subjects → **different** governed catalog sets; a non-member → **empty**;
  multi-team member → the **union**.
- Single-GET: non-member → **403** at catalog / category / product (fallback removed, all three).
- Team-create ownership: owner → team created; non-owner → **403**; `/internal/bootstrap` → bypass OK.

**e2e (newman, through the gateway — the headline):** a new **isolation matrix** —
`alice` creates a catalog+team (live-equivalent via the gateway) → sees only hers; `bob` added →
sees only Alice's; `carol` multi-team → sees both; `bob` (single team) cannot see Carol's; a
non-owner team-create on Alice's catalog → **403**. Every existing matrix re-runs **green**.

## 7. Forks already closed (do not reopen during decomposition)

1. Mechanism: governed-id Spec as **base scope** (not OR-widener); `filter` rule + SPI. *(Not ReBAC,
   not per-id `bulk` post-filter.)*
2. SPI shape: `GovernedScopeResolver` subject-keyed, fail-closed always-false, never throws; **library**.
3. Fallback removal: **all three** policies' single-decision path; **unconditional**. Narrow
   `catalog:create` fallback **retained**.
4. Ownership: **real cross-service check** (not unique-constraint-only); **pluggable**
   `ResourceOwnershipResolver` + config-driven discovery client + TTL cache; **library**; standard
   `/internal/{type}/{id}/created-by` contract; `404`/unreachable → fail-closed deny.
5. Routing: `/api/v1/teams*` + `/api/v1/users*` through the gateway (bearer-validated); `/internal/**`
   **never** gateway-exposed.
6. Add-member: a `GET /api/v1/users` dropdown; pre-seeded named users.
7. Demo cast: `alice` / `bob` / `carol` (Keycloak `catalog-editor`); Alice's create+add **live**,
   Carol's multi-team **pre-seeded**.
8. Gating: **fail-closed** throughout (not opt-in-convenience); resolvers opt-in by bean, absence ⇒
   the safe (empty / deny) outcome.
9. Docs: **two ADRs** (0018 isolation, 0019 ownership); roadmap slot **B4**, before Phase 7 publish.

**Impl gap pre-caught:** `CatalogRepository` lacks `JpaSpecificationExecutor` (it has `JpaRepository`
+ `LockableJpaRepository`). `findAuthorized` requires it → **add `JpaSpecificationExecutor<CatalogEntity>`**
(one line) or `CatalogListAuthorizer` won't compile.

## Related

- ADRs: [[0018-team-scoped-resource-isolation|0018]], [[0019-pluggable-cross-service-ownership|0019]]
- Precedents: [[adr/0005-partial-eval-to-jpa-specification|0005]] (`findAuthorized`),
  [[adr/0010-hierarchy-aware-list-filter|0010]] (`subtreeSpec` base-scope composition),
  [[adr/0014-supplier-outage-error-distinct|0014]] (fail-closed-on-outage doctrine)
- Demo: [[demo-spa-state]] (the SPA that demonstrates this slice)
- Roadmap: [[POC-ROADMAP]] (slice B4)
