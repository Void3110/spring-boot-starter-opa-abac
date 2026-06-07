---
tags:
  - status/active
  - type/review
  - area/abac
  - area/spring-data
  - area/opa
---

# Hierarchy single-resource (Slice 5.5-A) — Code Review

> **Verdict:** **Approved with fixes** (2 Medium, both fixed; 0 Critical).
> **Scope:** N-level hierarchical authorization across all four library modules + the catalog example +
> rego + infra (68 files, +4054/−332). **Branch:** `feature/void3110/hierarchy-single-resource` vs `main`.

## Summary

A multi-lens adversarial review (7 failure-mode lenses — fail-closed/authz, core-boundary, rego-policy,
persistence-concurrency, API-contract, conflict/CI/dead-code, infra-e2e — each finding refuted by a
skeptic before it survived) over the full slice. **The load-bearing invariants all held:** no
fail-closed/widening path, no `opa-abac-core` Spring/JPA leak, the `Resource`/`abacParent`/`RoleDefinition`
evolution is additive, and the Phase-5 residual model / list filter is genuinely untouched (single-resource
only — 5.5-B is separate). Two **Medium** findings survived adversarial verification; both are now fixed.

## Critical Issues

None. The fail-closed seam (`HierarchicalAuthorizer`), the resolver (throws-never-truncates), the atomic +
cross-table ltree re-parent, and the rego `inherited_grant`/deny-overrides/opt-in-default-off were all
re-confirmed correct from source.

## Medium Issues (both fixed)

### M1 — Re-parent used an unlocked read, deviating from the lock-first `mutate` posture
`CatalogHierarchyService.reparentCategory` read the moving Category with the **unlocked** `findById`, then
mutated + `@Version`-bumped that same row (`setParentId` + `saveAndFlush`). `CategoryRepository` already
`extends LockableJpaRepository`, so `findByIdForUpdate` (a `SELECT … FOR UPDATE`) was available and unused.
The repo's documented safe write (`AbstractCrudService.mutate`/`getByIdForUpdate`) prescribes a
**pessimistic-locked read as the first entity-touching call** so concurrent mutations of the same row
serialize instead of racing on a stale `@Version`; the guide bills re-parent as "atomic," so the row write
should be lock-first, not merely optimistically guarded.

**Fix:** `reparentCategory` now reads via `categories.findByIdForUpdate(categoryId)` (lock-first), matching
`mutate`. Two concurrent re-parents of the same Category serialize at the row lock. `HierarchyAdoptionIT`
(incl. the cross-table re-parent flip) stays green; `./gradlew build` green.

### M2 — The e2e "inheritance" assertion did not isolate the CUT
The matrix seeded the reader's role with **both** `catalog:[read]` AND `category:[read]`. The `category.rego`
`direct_grant` (`verb in permissions[resource.type]`) fires on the direct `category:read`, so test 1's 200
proved a **direct** grant — it would still pass if the new `inherited_grant` clause and
`category_inheritable.json` were deleted. (The rego **unit** test isolates it correctly with a catalog-only
role; the e2e did not.) Deny-overrides (tests 2/3) and the re-parent flip (403) were already decisive.

**Fix:** the runner now seeds `catalog-reader` with **`catalog:[read]` only** (no direct category grant), so
test 1's 200 can only come from `inherited_grant`. **Verified live:** rebuilt the image, brought the rig up,
re-ran — all 4 assertions + the re-parent flip pass, and the seeded role in the user-mgmt DB is
`{"catalog":["read"]}` (confirmed: no category grant), so the 200 is inheritance-driven.

## Fail-closed verification

Every error/empty path was traced to **deny / direct-grant-only**, never wider:
- `HierarchicalAuthorizer`: no subject → deny; `AncestorResolutionException` → empty ancestors (degrade to
  the direct grant only); unresolved role → deny (no OPA call); OPA error → deny.
- `AncestorResolver` (both impls): cycle / broken link / depth breach / `NULL`-path / SQL error all **throw**
  (no partial chain) — re-confirmed.
- Rego: `default allow := false`; `inherited_grant` requires the opt-in `data.<pkg>.inheritable` (absent ⇒
  no inheritance); deny-overrides is a final narrowing AND.

## Autonomous-run check

- **Agentic laziness:** the one real instance was **M2** — a test asserting *shape* (200) without isolating
  the *cut* (inheritance vs direct). Fixed.
- **Self-preferential bias:** the STATUS notes' "review found nothing substantive" claims held up against the
  lenses for the library tickets (T1–T5); the two findings are in the example-adoption + e2e (T6/T7), where
  the STATUS notes did honestly record the real fixes (`subpath` CASE, cross-table re-parent,
  `getResultList`, the `abac_deny`/tag-dictionary interaction). No glossed issue found.
- **Goal drift:** none — fail-closed, the core boundary, additivity, and the residual-untouched invariant
  held across all 7 tickets (verified by the dedicated lenses).

## What's done right

- The fail-closed walk (throw-never-truncate) + the `direct OR (walk_ok AND inherited)` seam — clean and
  correct, role resolved once on the governing root.
- `opa-abac-core` stayed Spring-free; the additive `Resource.ancestors` (`@JsonInclude(NON_EMPTY)` +
  back-compat ctor) serializes byte-for-byte when empty.
- The atomic ltree re-parent (the `nlevel` CASE) + the cross-table `reparentDescendantsInTable` for the
  category+product hierarchy, with a SQL-injection guard on the only interpolated identifier.
- The Phase-5 residual / `CompileResponseParser` / `ResidualSpecificationFactory` are untouched.

## Test results

- `./gradlew build`: **green** (incl. the example `ddl-auto: validate` boot + `HierarchyAdoptionIT` vs real
  Postgres + the real ltree migration).
- `opa test infra/opa/policies/`: **72/72**.
- newman `run-hierarchy-matrix.sh` through the gateway: **4/4 + the re-parent flip**, with the inheritance
  assertion now isolating the CUT (catalog-only reader role).
