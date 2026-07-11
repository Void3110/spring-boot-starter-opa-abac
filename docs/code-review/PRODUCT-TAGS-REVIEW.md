---
tags:
  - status/active
  - type/review
  - area/abac
  - area/opa
  - area/spring-data
---

# Taggable Products — Code Review

> **Verdict**: Approved with fixes (4 test-gap findings, all fixed in-review; 0 code defects)
> **Scope**: The taggable-products slice (ADR 0025) — tags on the Product contract, tag-on-create,
> the delta-dispatched product PUT, `product:list` moved to the partial-eval data-filter cut, the
> `product.rego` filter entrypoint, enrichment `assign-tags`, and full SPA parity.
> **Branch**: `feature/void3110/product-tags` vs `main` (base commit `de4df80`, slice commit `eec9796`)

## Summary

Multi-lens adversarial review (Path 2B): 8 lenses — fail-closed/authz, core-boundary, rego-policy,
persistence/concurrency, security-audit, api-contract, conflict/CI/dead-code, infra-e2e — plus
per-finding adversarial refutation and the completeness critic (14 agents). **Zero fail-open,
security, concurrency, or logic defects survived any lens.** Zero findings were refuted (no lens
overclaimed). Four findings confirmed, **all TEST_GAPs** — three of them the critic's specialty
(new seams whose fail-closed branches lacked the mirror tests their category/catalog siblings have)
— and all four fixed in this review. A fifth gap (the mx-49f3a3 mirrored-dispatch test-cell sweep)
was found by the reviewer's own pre-pass and fixed before the workflow returned.

## Critical Issues

None.

## Medium Issues (all fixed)

| # | Finding (lens) | Fix |
|---|---|---|
| 1 | The new product-list row cut had **no gateway e2e** asserting differential row sets — the only product-list e2e was a happy-path `include` under an allow-all role (infra-e2e) | `data-filter-matrix` extended with a four-way product contrast (reader-emea sees only EMEA / reader-apac only APAC / curator all three / stranger 403); `run-filter-matrix.sh` seeds an untagged holder category + three region-tagged products **via tag-on-create through the gateway**, and grants the three roles `product` perms |
| 2 | `ProductListAuthorizer`'s role-source-outage → empty-page branch untested (`CategoryListAuthorizerOutageTest` had no product sibling) (critic) | `ProductListAuthorizerOutageTest` — outage → empty page, query service never invoked |
| 3 | No differential-row-set test at any Java layer for the product residual cut (critic) | `ProductListIsolationIT` (real Postgres): two tag residuals see disjoint single rows; deny-all → empty page while 4 rows exist; **AND-not-replace** pinned twice (an ALLOW_ALL residual and a tag-matching residual both stay inside the categoryId scope — a sibling category's tag-matching row never leaks) |

## Low Issues (fixed)

| # | Finding (lens) | Fix |
|---|---|---|
| 4 | The product list's `_actions` enrichment now rides filter-survivor write-through (the manual per-row seeding was removed with the plain page) — untested (critic) | Product cell in `ActionEnrichmentListIT`: per-row maps reflect each row's own tags (incl. the new `assign-tags` verb), and the advice never re-loads a row by id |
| 5 | **Found in validation, introduced by fix #3**: `ProductListIsolationIT.seed()` cleaned with `products/categories/catalogs.deleteAll()` — order-dependent flake under the FULL suite. `categories.deleteAll()` deletes loaded rows one-by-one with a version check while `fk_category_parent ON DELETE CASCADE` removes a parent's children mid-iteration; when a prior suite leaves a parent-child tree in the shared container, deleting the already-cascade-deleted child throws `ObjectOptimisticLockingFailure`. Passed targeted, failed 4/4 in the full run | Clean via the ROOT table only (`catalogs.deleteAll()` — the `CatalogListIsolationIT` idiom; DB cascades clear categories/products). Sibling sweep: no other test calls `categories/products.deleteAll()` — siblings clean. Full module suite re-run fresh: green |

## Reviewer pre-pass (mx-49f3a3)

The recorded lesson from the PR #65 review — *a handler adopting a mirrored dispatch must inherit
the mirror's test cells* — was applied before the workflow ran: the `deniedUpdateBlocksContentEdit`
and `emptyDeltaPutAsksUpdate` cells existed only for the category. Added for **both** catalog and
product (4 cells; `TagDecisionGateIT` 14 → 22 cells across the three types).

## Fail-closed verification

- `ProductListAuthorizer`: unauthenticated → empty page; starter off (no `AbacQueryService`) →
  empty page; role-source outage → empty page (now pinned by test); no role definition → the
  `filter` rule compiles to DENY_ALL → empty page, never the full table.
- `product.rego filter`: role-definition-ONLY (no subject-roles fallback); PE residual shapes
  verified empirically with `opa eval --partial` — tag-gated role → clean DNF; untagged role →
  type-eq tautology (ALLOW_ALL); **no role definition → undefined (DENY_ALL)**.
- Tag validation: illegal tag → 422, dictionary-fetch failure → 503, nothing stored either way
  (`ProductTagAssignmentIT`); validation runs AFTER authorization (no 422-vocabulary leak).
- Tag-on-create: a denied type-level `product:assign-tags` persists nothing (`TagDecisionGateIT`).

## Security audit

No IDOR/scope weakening: the URL-scope rule (`requireCategory`/`requireProduct` → 404) still
precedes every decision; the residual is AND-ed with the categoryId scope (pinned twice in
`ProductListIsolationIT`); no new fallback engages (B4's sole surviving fallback untouched); the
request cache stays request-scoped (no cross-subject reuse); no injection surface (bound JPA
Criteria literals only); nothing sensitive added to logs/errors.

## Concurrency & idempotency

The delta dispatch preserves decide-under-protection: deltas are computed on the pre-dispatch load,
`guardGateSnapshot(current)` binds the deltas' basis to the gate's resolved snapshot, and the
version guard runs INSIDE `mutate()`'s locked transaction (drift → 409). The
`ResourceResolutionGateIT` I4 race cell — which had to *move* in both prior dispatch adoptions —
**survives in place** for the product PUT precisely because of that in-tx guard; its comment now
explains why. Persistence/concurrency lens: no findings.

## Wiring & sibling sweep

- Every new seam has non-test callers and non-happy-path tests: `ProductListAuthorizer` (controller
  + outage/isolation tests), `TagDecisionGate` product methods (controller dispatch + 7 dispatch
  cells), `product.rego filter` (the compile path + 16 rego cases + the e2e matrix).
- Sibling sweeps done: the mirrored-dispatch **test cells** swept to catalog + product (4 added);
  the stale "carries no tags" doc claims swept (`ACTION-ENRICHMENT.md` — including the **catalog**
  row PR #65 missed — `PERMISSION-MODEL.md`, `TagDecisionGate`/`ProductEnrichable` javadocs, the
  `ProductController` list comment).

## Autonomous-run check

Not an autonomous run (interactive slice with an in-session design decision, recorded as ADR 0025).

## What's done right

The persistence layer needed zero change (the secured base already carried tags on all three
tables); the policy needed only the filter entrypoint (the type-level coarse gate was already
verb-agnostic from B4); the SPA update button was fixed to echo `sku`+`tags` in the same change
that made the PUT clear-on-absent semantics reachable for products.

## Test results

- `./gradlew build` (all modules, Testcontainers ITs, `ddl-auto: validate` boot): **green**;
  the catalog module re-run **fresh** (`--rerun-tasks`) after fix #5: **157 tests, 0 failures**
- `opa check --strict` + `opa test infra/opa/policies/`: **228/228** (212 on main + 16 product cases)
- newman `data-filter-matrix` (extended: 4 category + 4 product requests) through the rebuilt rig
  (branch images, OPA restarted): **29/29 assertions, 0 failed** — the product differential cut is
  proven at the gateway (emea-reader: exactly the EMEA row; apac-reader: a different single row;
  curator: all three; stranger: 403), and the seeding itself exercised **product tag-on-create
  through APISIX** (three tagged creates by the curator)
- SPA `tsc -b`: **clean** (visual pass of the tag panel left to the maintainer — the reviewer does
  not enter credentials; the vite dev server at :3000 now proxies the rebuilt branch backend)
