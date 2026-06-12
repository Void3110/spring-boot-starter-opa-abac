---
tags:
  - status/active
  - type/review
  - area/abac
  - area/spring
  - area/opa
---

# Resource resolution (Phase 5.97) — Code Review

> **Verdict**: Approved with fixes
> **Scope**: The full 5.97 slice — attribute-rich pre-authorization (core split SPI + version binding,
> the manager's resolution flow + request cache, starter composition + kill-switch + persistence 409
> advice, catalog adoption, the `tags_satisfied` policy siblings, the e2e matrix, docs).
> · **Branch**: `feature/void3110/resource-resolution` vs `main` (11 commits, 64 files, +3369/−311)

## Summary

Reviewed via the multi-lens adversarial workflow (8 failure-mode lenses → adversarial refutation →
completeness critic; 13 agents). **Zero Critical findings. Zero findings in the library, security,
concurrency, or policy surfaces.** Three confirmed findings, all in the new e2e runner
(`run-resource-resolution-matrix.sh`) — two idempotency/DB-hygiene issues and one self-containment
gap — all fixed in this commit, with the idempotency fix swept across the two sibling runners that
shared the class. Nothing was refuted (the lenses produced exactly the three findings, each
re-confirmed from source by the skeptics).

## Critical Issues

None.

## Medium Issues

| # | Issue | Status |
|---|-------|--------|
| 1 | **e2e seed accumulation** — the runner POSTed fixture categories/products fresh on every run with no pre-seed cleanup (the catalog rows were upserted, but the server-id'd rows under them accumulated; violates the "e2e seeds re-run without accumulating" rule the pagination runner encodes) | **Fixed**: `DELETE FROM category WHERE catalog_id IN ('8888…8888','8888…8889')` before seeding (the `ON DELETE CASCADE` FKs sweep the products). Proven: two consecutive runs green, exactly 4 category rows remain after three total runs. |

## Low Issues

| # | Issue | Status |
|---|-------|--------|
| 2 | **Stale-OPA flake risk** — the deny cells E2/E6a assert this slice's `tags_satisfied` conjunct is live, but OPA has no `--watch` and `deploy.sh build` never reloads it; the runner documented the restart as a manual prereq only | **Fixed**: the runner restarts `opa-abac-opa` and polls `/health` (30s bound, fail-loud) before minting tokens — self-contained regardless of when the rig came up. |
| 3 | **Same accumulation class, second lens** (gateway-created fixtures non-idempotent) | **Fixed** by the same cleanup as #1. |

## Fail-closed verification

The fail-closed-authz and rego lenses confirmed every error/empty path lands on deny/empty/collapse,
and the skeptics could not refute it: instance resolution empty → deny with **no OPA call** (U8/I6
assert zero captured inputs); resolver throw → the manager's global fail-closed catch (U9); ancestor
throw → chain collapses to `[]`, caught locally so it can never take the deny path (U10); with
support present there is **no branch to an attribute-less context** (the `resolutionSupport == null`
check is the only baseline exit); kill-switch off / no bean → byte-identical baseline (U5 golden
string, U14/U16, the off-profile CRUD suites); the `tags_satisfied` conjunct fails closed on missing
attributes and malformed `match_mode` (P3, the ported vacuous-only-on-absence rule).

## Security audit

No findings. Verified by the security-audit lens and re-checked: the request cache is
request-attributes-bounded, populated only on allow, and the manager's sole cache interaction is the
gated `put` (it can never serve across subjects/requests or feed a decision — U11/U12); the resolver
loads by id alone and the URL-scope rule stayed in the handlers (I3's wrong-catalog 404), so no
routing semantics were absorbed into authorization; the missing-id 403 / wrong-scope 404 split is
not an existence oracle (the 404 is reachable only by callers the policy already allowed for the
resource); problem bodies carry static details (no versions, no exception text — U13/U17 assert);
no SpEL/SQL surface changed shape (the annotation SpEL idiom is pre-existing; new SQL is
parameterized through repositories).

## Concurrency & idempotency

Library/example: no findings. Every gate→write window is detected — gate→handler-load by
`VersionGuard` (the deterministic I4 race: bump → 409, mutation not applied), post-guard by
`@Version` + `PersistenceConflictProblemAdvice` (the deterministic I5 stale-save: 409, not 500);
`updateProduct` guards **inside** `mutate()`'s locked transaction (decide-under-protection, Rules
1–2); the audited `reparentCategory` lock ordering is untouched; a 409 retry re-runs the gate on the
new state. The one idempotency defect found was in the e2e *harness* (finding #1), not the runtime —
fixed and swept.

## Wiring & sibling sweep

Every new seam has a named consumer and a non-happy-path test (resolver → the gate, I6; cache → 3
read handlers + the manager put, off-profile fallback; guards → 6 mutating handlers, I4/I5; the
kill-switch property → U16 + the off-profile suites; the advice → U17/I5; the conjunct → P1–P5 +
E6). **Sibling sweep of the fixes**: the accumulation class hit `run-tag-matrix.sh` (dedicated
`2222…`) and `run-hierarchy-matrix.sh` (shared `3333…` + `6666…`) — both fixed in this commit and
re-run green (tag 12/12, hierarchy 4/4), with the shared-fixture `run-filter-matrix.sh` re-run green
(16/16) to prove the cross-cleanup is safe. The OPA-restart class was **not** widened to sibling
runners: it only bites when a runner's own slice edits the policies it asserts, which is unique to
this matrix today; the siblings' policies are stable and their headers document the manual step.
The remaining runners already clean (pagination/filter/hierarchy-list) or create nothing (team).

## Autonomous-run check

- **Laziness**: none found — every ticket's deliverables/acceptance are met; the decision-asserting
  tests assert the actual cut (row state, captured OPA inputs, role-lookup coordinates), not shape.
- **Self-preferential bias**: the STATUS notes' "nothing substantive refactored" claims match the
  diff; deviations are named where they exist (public accessors in T3, `deleteCatalog`'s
  exists→find switch in T4, the I5 mechanism change, the ltree-path seed fix in T6).
- **Goal drift**: none — core is Spring-free (import-set proof), the manager change is a
  byte-compatible overload (pre-existing tests unmodified-green), user-mgmt is zero bytes,
  `AbacQueryService`/`CategoryListAuthorizer`/pagination byte-identical, the policy diff is the one
  audit-mandated narrowing conjunct.

## What's done right

The split failure semantics are structurally separated in the manager (instance-throw and
ancestor-throw cannot take each other's paths); the U5 golden string pins the byte-identical
baseline; the two deterministic race hooks pin both TOCTOU windows without sleeps; the off-profile
CRUD suites double as the kill-switch proof; the e2e matrix asserts decisions (applied updates,
problem codes, contrast pairs), not status shapes; the 404→403 flip class was swept suite-wide and
produced exactly one documented cell.

## Test results

- `./gradlew build`: green (all modules + both example IT suites; unchanged by the review fixes).
- `opa test infra/opa/policies/`: 97/97 (unchanged by the review fixes).
- newman after the fixes: resource-resolution **12/12 twice consecutively** (re-run proof: 4
  category rows remain under the fixture pair after three total runs), tag 12/12, hierarchy 4/4,
  filter 16/16 (the shared-`3333…` cross-check).
