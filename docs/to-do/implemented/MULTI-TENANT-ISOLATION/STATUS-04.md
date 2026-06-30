---
tags:
  - status/done
  - type/project
  - area/abac
---

# STATUS — T4: CatalogListAuthorizer + JpaSpecificationExecutor + listCatalogs adoption + ITs

**Status:** ✅ DONE

## What shipped

- **Build-breaker (same commit):** `CatalogRepository` now also extends
  `JpaSpecificationExecutor<CatalogEntity>` (it had `JpaRepository` + `LockableJpaRepository`).
  `AbacQueryService.findAuthorized` requires it.
- **`CatalogListAuthorizer`** (`…catalog.config`) — the **sole authority** for the catalog list. Mirrors
  `CategoryListAuthorizer` but adapts to catalogs being **roots**:
  - one membership fetch — `GovernedScopeResolver.governedIds(sub, "catalog")` — drives BOTH the base
    scope (`id IN (governedIds)`) AND the residual-role resolution (no table scan, no second round-trip);
  - resolves the role on the **first governed id** to drive the role-def-only `filter` residual (any
    governed catalog's membership role answers the coarse "may I list"; the per-row cut is the governed
    scope itself);
  - `findAuthorized(catalogs, governedScope, ctx(catalog:list), /*subtreeSpec*/ null, pageable)`.
- **`CatalogController.listCatalogs`** — **dropped the `@OpaPreAuthorize(catalog:list)` gate** (see the
  Decision below) and delegates to `CatalogListAuthorizer.readable(pageable)`.
- **`GovernedScopeResolver` SPI refactor (within the slice):** the SPI now exposes `governedIds(subject,
  type): List<UUID>` as the primitive, with `governedScope` as a **default** method built from it (and the
  shared `denyAll()`). This lets the authorizer get the ids for the role lookup AND the scope from one
  call. `HttpGovernedScopeResolver` now implements `governedIds` (was `governedScope`). T2's unit test
  still passes unchanged (the default delegates to the impl).
- **No starter change needed:** the `ObjectProvider<GovernedScopeResolver>` in the authorizer handles
  absence (demo profile → no bean → empty page); there is no sensible library default, exactly as
  `SubtreeSpecResolver` is example-provided (not a starter default).

## Tests

- `CatalogListAuthorizerTest` (mockito, fast) — **5/5**: I4 (no resolver bean → empty, never queries),
  I2 (governs nothing → empty, never even resolves a role), role-source outage → empty (no 500),
  unauthenticated → empty, and the happy-path delegation (role resolved on the FIRST governed id only,
  null subtreeSpec, `catalog:list` context).
- `CatalogListIsolationIT` (**real Postgres**, programmable OPA stub) — **4/4**: **I1** (two subjects,
  different governed sets → **disjoint** row sets, neither sees the unowned catalog), **I2** (governs
  nothing → empty page while the table holds 4 rows), **I3** (multi-team subject → the **union**, exact
  count), and the role-denies-list path (`governedScope ∧ DENY_ALL residual = empty`).
- `PaginationEnvelopeIT` — updated (see Decision) and green.
- **`./gradlew build` GREEN** — all modules + ITs, real Postgres. `opa-abac-core` has **zero** Spring
  imports (verified).

## Architecture review + refactor

- **Fail-closed:** every breach → empty page, never the table, never a 500 — proven across I2/I4/outage/
  unauthenticated (unit) + I1/I2 (IT). The governed-id Spec is the **base scope** (the AND-gate); I1's
  disjoint sets + the never-surfaced unowned catalog prove no un-governed row escapes.
- **Security widenings refuted:** (a) un-governed row entering the list → I1 disjoint + unowned-never-seen;
  (b) whole-table on breach → I2/I4/outage all empty.
- **Refactor applied (not churn):** the T2 SPI grew a `governedIds` primitive + default `governedScope`
  so the authorizer makes ONE membership call for both the scope and the role lookup — the first draft
  probed `catalogs.findAll()` per-id (a table scan + N HTTP calls), which the review rejected. The clean
  shape resolves the role on `governedIds.get(0)`.
- **Boundary:** `opa-abac-core` Spring-free (SPI in spring-data); `filter` translator / `bulk` /
  pagination / category+product authorizers byte-for-byte unchanged.
- **Pattern reuse:** mirrors `CategoryListAuthorizer` (resolve-then-`findAuthorized`, `ObjectProvider`
  for the optional resolver, role-source-outage → empty) and the `ProgrammableOpaClient` IT idiom.

## Integration / e2e

- ITs run against **real Postgres** (Testcontainers / Docker Desktop). The OPA decision is a programmable
  in-process stub (`compile → allowAll` / `denyAll`) — so the IT proves the **governed-scope SQL cut**;
  the live-OPA residual is T1's Compile-API check + T9's e2e.
- **I5 scoping (honest note):** I5 (single-GET 403 at catalog/category/product for a non-member) is the
  `@OpaPreAuthorize(view/...)` gate against the REAL OPA — proven at the **policy layer** in T1 (R4 catalog
  deny, R7 category/product deny, both with no role-def) and end-to-end as **E6** in T9. A live-OPA IT here
  would duplicate T9; the policy-level proof + e2e is the cut. (The deep-link leak is closed because T1
  removed the fallback in all three single-decision paths.)

## Decisions

- **Dropped the coarse `@OpaPreAuthorize(catalog:list)` gate; `CatalogListAuthorizer` is the sole
  authority** (maintainer-approved). The fork: a catalog list is type-level (no resourceId), so no
  per-resource role resolves; after T1 removed the realm fallback, the coarse `catalog:list` gate would
  **deny every membership-driven caller** under the http profile. (Latent until now: existing matrices run
  under the **demo** profile, where the demo supplier resolves a role at type-level.) The governed-scope ∧
  filter-residual cut is fail-closed to empty, so it subsumes "may you list at all" — a subject governing
  nothing sees `[]` (effectively denied). Cleanest, matches the design's "rows come from `findAuthorized`".
- **Which role drives the residual:** the first governed id's membership role — any one answers the coarse
  `list` question; the per-row membership cut is the governed scope.
- **`PaginationEnvelopeIT` catalog-count assertion relaxed `>=1` → `>=0`** (documented in-test, not a
  silent patch): the catalog list is now membership-scoped and that permissive IT wires no
  `GovernedScopeResolver`, so the governed scope is empty → count 0. The **envelope contract** (members
  present, params echoed, items an array) is what the case pins and it holds at 0; the row-cut is proven
  by `CatalogListIsolationIT`. No e2e matrix asserts a catalog-list count (verified — all are status-only),
  so no matrix regresses (E8).

## Commit

(see branch `feature/void3110/multi-tenant-isolation`)
