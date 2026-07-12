---
tags:
  - status/active
  - type/review
  - area/security
  - area/opa
---

# Security Review 2026-07-12 — Fix note (F1: catalog.rego `abac_deny` deny-override)

> **Fixes**: the single HIGH from [SECURITY-REVIEW-2026-07-12](SECURITY-REVIEW-2026-07-12.md) (F1).
> **Branch**: `fix/void3110/catalog-abac-deny` off `main` (`5a81fad`). **Scope**: rego + rego-tests only.
> **Status**: fixed + regression-verified; report-only review branch stays separate (unchanged).

## What was wrong

`catalog.rego` was the only per-type policy missing the `abac_deny` deny-override that
`category.rego` and `product.rego` carry. A catalog flagged `abac_deny=true` (an operator DB flag,
not client-writable) was correctly excluded from `GET /catalogs` — the SQL `notDenied()` residual in
`AbacQueryService` AND-s the deny unconditionally, catalogs included — but the single-resource
`@OpaPreAuthorize` gates (`catalog:view`/`update`/`delete`) ran `catalog.rego` `allow`, which had no
`denied` rule and no `not denied` conjunct, so they returned `allow=true`. Single-GET and the list
disagreed on what "denied" means — the exact ADR-0010 §3 invariant, never ported to the root type.
The `delete` fail-open was the dangerous direction. Confirmed pre-fix by `opa eval` (OPA 1.10.1):
`catalog:view`/`catalog:delete` + `abac_deny=true` → `true`; `category`/`product` siblings → `false`.

## The fix

`infra/opa/policies/catalog.rego` — two changes, matching the sibling idiom exactly:

1. Added the deny rule (mirrors `category.rego:120-122` / `product.rego:127-129`):
   ```rego
   denied if {
       input.resource.attributes.abac_deny == true
   }
   ```
2. Added `not denied` as a conjunct on **both** `allow` clauses — the role-definition-driven grant
   and the narrow `catalog:create` fallback. The create clause carries no resource, so `denied` is
   simply false there; the conjunct is defense-in-depth and matches how `category.rego` guards its
   second `allow` clause.

**Leaf-scoped, by design.** `denied` reads only this resource's own `abac_deny` tag — no
`input.resource.ancestors` consultation — so it stays consistent with the cross-angle finding that
`abac_deny` never cascades down a subtree (the SQL side AND-s the deny *outside* the widening OR for
the same reason). A catalog-level deny vetoes the catalog; it does not silently deny the subtree.

No change to the list/`filter` entrypoint (the SQL residual already honored the deny for catalogs);
no change to the root-read tag exemption (ADR 0022 governs tag *requirements*, orthogonal to the
`abac_deny` kill-switch — `not denied` AND-s after `tags_satisfied`, so an exempted read is still
vetoed by an explicit deny).

## Tests

`infra/opa/policies/catalog_test.rego` went from **zero** `abac_deny` cells to five (the coverage
gap that let the defect ship):

- `test_abac_deny_vetoes_view` / `_update` / `_delete` — each single-GET verb denied on a flagged catalog.
- `test_abac_deny_view_agrees_with_list` — asserts the **cut**: the same editor role allows `view` on a
  plain catalog but is denied on the `abac_deny`-flagged one (single-GET now agrees with the list).
- `test_abac_deny_false_still_allows` — no over-denial: `abac_deny=false`/absent does not deny (the veto
  is strictly on the `true` flag).

The pre-existing `test_editor_role_def_views`/`_updates` are the no-deny baselines proving the grant
still fires (so the deny cells prove the flag does the work, not a broken fixture).

## Verification

- `opa check infra/opa/policies` — clean.
- `opa test infra/opa/policies` — **233/233** (was 228 pre-fix; +5 new cells, no regressions).
- Post-fix `opa eval` (repo policies, OPA 1.10.1): `catalog:view`/`catalog:delete` + `abac_deny=true`
  → **`false`**; baseline `catalog:view` (no deny) → **`true`**; `catalog:create` fallback → **`true`**;
  role-less caller → **`false`** (B4 membership gate unchanged).
- `./gradlew test --rerun-tasks` — green (the ITs boot against these policies; regression gate).
- No e2e/collection change needed: the hierarchy matrices set `abac_deny` only on *categories*
  (`UPDATE category …`), never catalogs, so nothing asserted the old buggy catalog behavior.

## Sibling sweep

`grep -c abac_deny` per policy after the fix: `catalog.rego` 1, `category.rego` 1, `product.rego` 2
(product has the deny rule + a comment reference) — all three per-type policies now carry the override.
Test coverage: `catalog_test.rego` 5 cells (was 0), `category_test.rego` 5, `product_test.rego` 1.
The root type is no longer the blind spot.

## Follow-up

Live-rig re-probe of F1 remains for when the rig's OPA is loaded with this repo's bundle (it currently
serves an unrelated `paas/projects` corpus — a deploy-hygiene issue, not a policy defect). The static
defect is closed and proven; the live re-probe is confirmatory. The Info-level `commons-io 2.11.0`
transitive pin folds into the deferred publish-time CVE audit.
