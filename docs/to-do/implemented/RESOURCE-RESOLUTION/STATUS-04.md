---
tags:
  - status/done
  - type/project
  - area/abac
  - area/spring
  - area/opa
---

# STATUS — T4: catalog adoption: resolver bean, getCategory to the gate, version guards + ITs

**Status:** ✅ DONE

## What shipped

Package `dev.dmitriikonovalov.example.catalog`:

- `config/CatalogResourceResolver` — the one resolver bean (3-way `switch` over the repositories,
  `findById` by id alone; unknown type / unparseable UUID / missing row → empty → the gate denies).
  Registering it is the whole opt-in.
- `web/CategoryController.getCategory` — gains its first-ever annotation
  (`category:read`, `resourceId = "#categoryId"`), **drops the `categoryAuthorizer.require` call**,
  serves the gate's snapshot from `AbacResourceCache` (repo fallback for resolution-off), and keeps
  the **URL-scope rule in the handler** (`entity.catalogId != path catalogId` → 404).
- **`config/CategoryAuthorizer` deleted** — one production call site, zero test references (the
  build-breaker scan found nothing to migrate). `HierarchicalAuthorizer` (library) stays.
- Cache reuse in `getCatalog` and `getProduct` (the product path re-checks the cached instance's
  category scope and falls back to the scoped repo load — wrong scope stays a 404).
- Version guards in all six mutating handlers: `updateCategory`/`deleteCategory` (guard right after
  the fresh scoped load, before tags/reparent/any write), `updateCatalog`/`deleteCatalog`, and
  `deleteProduct` via a per-controller `guardGateSnapshot` helper; **`updateProduct` guards INSIDE
  `ProductService.mutate`'s locked transaction against the row it locked** (decide-under-protection,
  Rules 1–2). No snapshot (resolution off / non-web) → today's window, documented. The snapshot is
  never persisted.
- `AbstractPostgresIT` pins `opa.abac.resource-resolution.enabled=false` — every pre-existing IT
  suite is now the kill-switch off-state proof (pre-5.97 status codes preserved, incl. the
  missing-id 404s).
- `ResourceResolutionGateIT` (resolution ON, own Postgres container, `catalog.role-source=none`):
  programmable **context-aware** `OpaClient` stub (decides from resolved attributes/ancestors,
  captures every input), recording `RoleDefinitionSupplier`, and a `RaceInjector` test aspect with
  two deterministic hooks — `BEFORE_HANDLER` (unordered aspect ⇒ runs inside the order-190
  method-security interceptor: the gate→handler window) and `BEFORE_SAVE` (immediately before the
  repository save: the post-guard window `@Version` owns).

## Tests

`ResourceResolutionGateIT` — 9 cases, green first run; **`./gradlew build` green** (all existing
catalog ITs on the off-profile + the untouched user-mgmt suite):

- I1 tag-match PUT → 200, row updated, the captured input carried `region=emea` (resolved attributes).
- I2 tag-mismatch PUT → 403 `ACCESS_DENIED`, row name **and version** byte-identical (handler never ran).
- I3 GET → 200, body = the snapshot (spy: exactly one `findById` — the resolver's; `findByIdAndCatalogId`
  never called); wrong-catalog scope → 404 `RESOURCE_NOT_FOUND` (the handler's rule).
- I4 gate-window bump → **409 `STATE_CONFLICT`**, the row keeps the racer's state (`name='raced'`).
- I5 post-guard stale-`@Version` save → **409, not 500** (the dao advice live — fold-in #1 reached).
  Note: the planned FK-violation variant is impossible here (every FK is `onDelete: CASCADE`), so I5
  uses the stale-save route exclusively.
- I6 missing id, resolution on → 403 on GET and PUT **with zero OPA calls** (captured list empty);
  the off-state 404 half is pinned by the CRUD/error-contract suites on the off-profile.
- I7 nested category → ancestors `[catalog, parentCategory]` root-first at the gate, role looked up
  **once** on `("catalog", id)`; product two levels deep → same chain shape, root role.

## Architecture review + refactor

Review path: window-by-window concurrency walk; oracle analysis of the 403/404 split; diff-boundary
grep; annotation byte-compare.

- **Every gate→write window is detected:** gate→handler-load by the guard (I4); post-guard by
  `@Version` + the T3 advice (I5); the reparent path's own transaction locks rows, and a racer
  between reparent and save still trips `@Version`. Nothing silent.
- **No existence oracle:** the wrong-scope 404 is reachable only after the gate *allowed* the
  resource — unauthorized callers see 403 for missing and existing alike.
- **Boundary clean** (grep over the diff): user-mgmt zero bytes; `AbacQueryService`,
  `CategoryListAuthorizer`, pagination, OpenAPI specs untouched; only `getCategory` gained an
  annotation — every other annotation byte-identical.
- **Named mechanical cost:** `deleteCatalog` switched `existsById`+`deleteById` →
  `findById`+`delete` (the guard needs the entity; 404 behavior unchanged, one extra SELECT).
- Minor accepted duplication: the `cachedX`/`guardGateSnapshot` helper pair appears per controller —
  a generic shared helper would need type tokens for less readability than three 6-line helpers.
- **Nothing else refactored**; all green first run.

## Integration / e2e

`./gradlew build` green (every module; catalog ITs off-profile + GateIT on-profile + user-mgmt).
Live e2e through APISIX is T6.

## Decisions

- **I5 mechanism**: stale-`@Version` save via the `BEFORE_SAVE` hook (deterministic), since the
  schema's `CASCADE` FKs rule out a natural integrity violation on delete.
- The GateIT runs `catalog.role-source=none` so the recording supplier is the only
  `RoleDefinitionSupplier` bean (both app suppliers are property-gated).
- 404→403 flips introduced by this ticket: **none in the existing suites** — they run on the
  off-profile by design; the flip class is pinned in the GateIT (I6) and will surface in T6's e2e
  sweep if any live cell pinned a missing-id 404 through an annotated endpoint.

## Commit

`feat(example): catalog adopts gate-side resource resolution (T4)` — see `git log` on
`feature/void3110/resource-resolution`.
