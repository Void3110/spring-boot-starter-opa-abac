---
tags:
  - status/active
  - type/decision
  - area/abac
  - area/architecture
  - area/security
---

# ADR 0018 — Team membership is the sole access path (team-scoped resource isolation)

**Status:** Accepted (planned — Slice B4, [[MULTI-TENANT-ISOLATION]])
**Date:** 2026-06-29
**Context tags:** tenant isolation, realm-role fallback removal, `GovernedScopeResolver`, `filter` entrypoint, fail-closed

> Pins the **isolation fork** for **Slice B4**: catalog access is decided by **team membership**, not
> by a Keycloak realm role. Settled in a planning interview (2026-06-29). Companion:
> ADR [[0019-pluggable-cross-service-ownership|0019]] (the ownership check the self-service half needs).

## Context

The per-type catalog policies carry a **realm-role fallback** (`catalog.rego` `allow`,
`category.rego` / `product.rego` `granted`): when no `role_definition` is resolved,
`catalog-viewer` → READ and `catalog-editor` → READ+WRITE+TAG on **any** resource. This fallback
predates the team model (it was the 5.97-era stand-in) and now **contradicts** it: empirically every
authenticated user (`viewer` / `outsider` / `editor`) lists **all 13 catalogs**, and `catalog-editor`
can `PUT` any of them. There is no tenant isolation.

Catalog visibility is fundamentally a **membership join** — "which team governs this catalog" — that
lives in the user-service, keyed by the catalog id (`Team.target_type='catalog'`, `target_id=<id>`).
It is **not** an attribute on the catalog row, so OPA partial-evaluation (which filters on
`input.resource.*`) cannot express it; the residual approach that scopes `category` lists by their
`tags` JSONB has nothing to bite on for catalogs.

The catalog **LIST** compounds the leak: `listCatalogs` is a flat `@OpaPreAuthorize(catalog:list)` on
the `allow` path (so it rides the fallback), and `catalog.rego` has **no `filter` entrypoint**.

## Decision

**Team membership is the only access path to the catalog hierarchy.**

1. **Remove the realm-role fallback** from the single-decision path of **all three** policies
   (`catalog` `allow`, `category` / `product` `granted`) — **unconditional**, no re-enable flag (the
   B2 doctrine: *the off-ramp would be the vuln*). A bare realm role no longer grants view/update/delete
   on any instance; a **resolved team role** is required at every level. This closes the direct-id leak
   (`GET /catalogs/{not-mine}` → 403) and is consistent down the hierarchy (no deep-link leak into a
   category/product).

2. **Retain a narrow `catalog:create`-only fallback.** `create` is type-level (no resourceId) and
   therefore cannot be team-scoped — no instance/team exists yet. A verb-gated `allow` clause grants
   `catalog-editor` the `create` verb only. So: **realm role = "may onboard a catalog"; team
   membership = "what you can access."** The one coherent asterisk on "membership is the sole access
   path" — creation is definitionally pre-membership.

3. **Add a `filter` entrypoint to `catalog.rego`** (role-def-only, mirroring `category.rego`): no tag
   requirement → `ALLOW_ALL` residual when the role grants `list`, `DENY_ALL` otherwise. No
   subject-roles fallback → a missing role fails **closed** (empty list, never the whole table).

4. **`GovernedScopeResolver` SPI** (`opa-abac-spring-data`) supplies the **base scope** for the catalog
   list: `<T> Specification<T> governedScope(String subject, String resourceType)` — subject-keyed,
   **fail-closed via an always-false predicate, never throws** (mirrors `AncestorResolver.subtreeOf`).
   The example impl calls the user-service for the governed catalog ids and returns `id IN (…)`. The
   `CatalogListAuthorizer` (example) composes it through `AbacQueryService.findAuthorized` as the
   **base `scope`** (`subtreeSpec = null`): `governedScope.and(opaListResidual).and(notDenied)` — so
   the governed-id set is the AND-gate nothing escapes, and OPA's residual still decides the `list`
   grant on top.

## Consequences

- **Fail-closed throughout.** No `GovernedScopeResolver` bean → catalog list is empty (no per-id
  allowlist escape). The isolation is opt-in **example wiring** (`CatalogListAuthorizer`); the library
  default (`@OpaPreAuthorize(catalog:list)`) is unaffected, so existing adopters/matrices are not
  forced onto governed-scoping. Resolver **absence ⇒ the safe (empty) outcome**, never a leak.
- **Low blast radius (verified).** No e2e matrix lists top-level `/api/v1/catalogs` and asserts a
  count; the matrices already avoid the fallback (they bind a role definition — the policy prefers a
  role-def over the fallback) and treat bare-realm users as the deny/empty case — exactly what removal
  produces. The one fallback dependency (`run-permission-categories-matrix`, `catalog:create`) is
  preserved by the narrow create clause. The SPA smoke test asserts status-only.
- **`CatalogRepository`** must add `JpaSpecificationExecutor<CatalogEntity>` (it has `JpaRepository` +
  `LockableJpaRepository`) for `findAuthorized`.
- **Not ReBAC.** Governance is resolved app-side (membership → governed ids), not expressed as a
  membership join in Rego — that remains Phase 8.

## Alternatives rejected

- **Per-id `bulk` post-filter** (list all, decide each via the allowlist primitive): O(N) degraded
  path; the governed-scope base-`scope` is exact and cheap (one user-service call → `id IN`).
- **ReBAC-in-policy now:** Phase 8; a far larger change.
- **Keep the fallback, isolate only the list:** half-isolation (hidden-but-reachable-by-id) — fails
  the isolation claim and is exactly what a reviewer would catch.
- **Config-gate the fallback (demo off / legacy on):** a flag that exists only to preserve a leak for
  test convenience; rejected for the honest, unconditional removal.

## Related

- Slice: [[MULTI-TENANT-ISOLATION]] (00-DESIGN)
- Companion: [[0019-pluggable-cross-service-ownership|0019]]
- Precedents: [[0005-partial-eval-to-jpa-specification|0005]] (`findAuthorized`),
  [[0010-hierarchy-aware-list-filter|0010]] (base-scope composition),
  [[0014-supplier-outage-error-distinct|0014]] (fail-closed-on-failure doctrine)
