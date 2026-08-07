---
tags:
  - status/active
  - type/review
  - area/abac
  - area/spring
---

# SUPERVISED-SCOPE — Code Review

> **Verdict**: Approved with fixes
> **Scope**: Layer-3 whole-delivery review of the supervisor slice A (T1–T6, two-part autonomous
> run: the reporting relation + synthesized read-only role + ADR 0031 confinement, then the
> catalog-side two-leg list + e2e proof). · **Branch**: `feature/void3110/supervised-scope` vs `main`

## Summary

Multi-lens adversarial workflow (8 lenses → per-finding refutation → completeness critic), 23
agents over the 53-file / +6282 diff. **9 findings confirmed (1 Critical, 3 Medium, 5 Low), 4
refuted.** Every confirmed finding was fixed in this review; the Critical was a *test-instrument*
defect, not an enforcement defect — after making the assertions real, every deny cell passes
against the live rig, so **no production behavior changed and no widening existed**. The two
fail-open edges the package named (T3's confinement conjunct, T5's `S \ M` set difference) were
each probed by dedicated lenses and held; the layer-2 anchor-provenance race fix from part 1 was
re-examined at branch scope and confirmed sound (fail-closed, narrower than strictly necessary —
acceptable).

## Critical Issues

| # | Issue | Status |
|---|---|---|
| 1 | **Every deny cell in the supervised matrix was a vacuous assertion.** `pm.test(name, () => pm.response.code === N)` returns a boolean instead of throwing; newman passes it unconditionally. E5a–d (read-only ceiling), E6a–c (ADR 0031 contents-closed), and E4c (withdrawal is denial) asserted **nothing** — the collection would stay green on a total fail-open. Proven empirically by the refutation verifier with a 200-returning probe server. | **Fixed** — all 18 occurrences rewritten to `pm.response.to.have.status(N)`; matrix re-run green (44 assertions, 0 failed) with the assertions now load-bearing. |

**Class sweep (mandatory):** the same vacuous form pre-existed on `main` in
`isolation-matrix` (11, incl. the E6 deep-link and E7 squat deny cells), `data-filter-matrix`
(8, incl. two 403 cells), and `hierarchy-list-matrix` (2) — **39 rewritten repo-wide**. The two
`if (pm.response.code === 200)` hits (`catalog-e2e`, `hierarchy-list`) are conditionals, not
tests, and were left alone. All three affected matrices re-run green on the live rig with real
assertions — the historical deny cells genuinely hold, so this was latent instrument debt, not
latent enforcement failure.

## Medium Issues

| # | Issue | Status |
|---|---|---|
| 2 | `product.rego`'s type-level gate `list_inheritable_grant` had **zero coverage on its new ADR-0031 conjunct** (mutation-proven: deleting the conjunct — or the whole clause — left 274/274 green; the category sibling was properly held by U37). The unguarded path is live: the supervisor role + `product_inheritable` would open the coarse product list gate on regression. | **Fixed** — product-side U37 mirror added (`product_list_gate_input` + negative/positive pair; the positive resolves *only* through `list_inheritable_grant`, so clause deletion also fails). Mutation re-run: 275/276 FAIL, restored 276/276 PASS. |
| 3 | The reserved `provenance` attribute is silently stripped on the public role-definition write path but **undocumented in the OpenAPI spec**. | **Fixed** — `attributes` descriptions on RoleDefinition response/request/update now name both system-owned keys (`role_level`, `provenance`) and the strip. |
| 4 | The E8 rig recreate/restore in `run-supervised-scope-matrix.sh` **hardcoded the two base flags**, silently downgrading a directory/SPA/MCP-flavoured rig on recreate (measured: E7 needed the directory flavour). | **Fixed** — both `deploy.sh up` sites forward `ENABLE_DIRECTORY`/`ENABLE_SPA`/`ENABLE_MCP` pass-through. |

## Low Issues

| # | Issue | Status |
|---|---|---|
| 5 | `deep_product_input` stamped provenance on the *resource tag map* instead of the role definition — meaningless at that position, and it would mask a future tag-map/role-def confusion. | **Fixed** — reverted to `{}`; suite stays green, confirming the stamp arrives via `role_def`. |
| 6 | Supervised single-`GET`s emit no audit event and the pinned Javadoc's rationale was factually wrong (the supervised authority *is* applied on single-GETs via the synthesized role through the generic gate; what's missing is a supervised-specific emission point on that shared path). | **Fixed** — comment corrected to state the real reason; the emission itself remains deliberately deferred to slice-C audit work (building a cross-service seam is out of review scope and would reach part 0's code). |
| 7 | `ProblemDetail.errorCode` description claimed "the union of codes this service can emit" while the internal-only `REPORTING_EDGE_INVALID` is absent from the enum. | **Fixed** — description narrowed to documented-endpoints scope (matches STATUS-01's recorded decision; internal fixture endpoints stay out of the public spec). |
| 8 | The new `eeee…` fixture-registry row was detached from the registry table by a blank line (rendered as an orphan one-row table). | **Fixed** — row rejoined. |
| 9 | E8b's two id-membership checks sat outside any `pm.test` (would report as an unnamed script error, aborting remaining checks). | **Fixed** — wrapped in a named test (`E8b the ids are carol's and dave's`). |

## Fail-closed verification

The fail-closed-authz lens traced every error/empty branch; all land on deny/empty/narrower:
`SupervisedScopeClient` (any non-200, unparseable body, malformed element → empty list, one WARN,
own breaker), the two request-time classes stay distinct (errored org source → own memberships;
partial derivation → membership-only), T5's floor is the empty page, and the layer-2 race fix
drops the whole membership leg on a provenance-mismatched anchor (fail-closed, self-healing).
Refuted-but-checked: the "Java list-widening mirror" claim (no such inheritance exists app-side)
and the "concurrent-cycle persistence" claim (the collapse-to-empty guard makes a persisted cycle
deny, not widen).

## Security audit

No IDOR/widening/injection findings survived refutation. The realm marker stays UX-only (never
resolver input); role-code spoofing is self-demotion (ADR 0029 §7 — reach never derives from the
role); the `/internal` endpoints stay gateway-unreachable; no secrets/state in logs beyond the
deliberate audit payload.

## Concurrency & idempotency

The declarative reporting-edge replace converges under retry; the Kahn cycle/diamond distinction
is tested both directions; the layer-2 fix closes the revoked-membership race between the two
legs. The refuted concurrent-cycle finding is documented above.

## Wiring & sibling sweep

Every new seam has a non-test caller and a non-happy-path test (`supervisedCallGuard`, the
dedicated base-URL property, the audit logger, the 422 advice mapping). Sweeps performed: the
vacuous-assertion class (39 sites, 4 files — the Critical's sweep), and the product/category
policy-test parity gap (finding 2 was itself the unswept sibling of U37).

## Autonomous-run check

- **Laziness**: the Critical is exactly the "asserted shape but not the cut" failure mode —
  STATUS-06 claimed "E5/E6 prove the ceiling/boundary" while those cells asserted nothing. The
  *enforcement* was right; the *proof* wasn't. (Mitigating: the pattern was inherited from three
  older collections, not invented by this run.)
- **Self-preferential bias**: none found — STATUS notes' claims otherwise matched the diff
  (the T5 re-measurement was genuinely done pre-code; the layer-2 race finding was real and fixed).
- **Goal drift**: none — zero library-module changes, the only policy change is T3's four clauses,
  `opa-abac-core` untouched, envelope unchanged.

## What's done right

The layer-2 part review caught a genuine race-widening (anchor provenance) that four validation
rounds missed; the T5 re-measurement discipline held; the fixture-stamp migration was done
honestly (all roles, not the minimal five, with a vacuousness argument recorded); the two
failure classes stayed distinct end to end; the declarative bootstrap seam is a clean E4
liveness mechanism.

## Test results

- `./gradlew build`: green (all modules + Testcontainers ITs)
- sonar-local: CLEAN — 0 open findings (changed files)
- `opa test infra/opa/policies/`: **276/276** (274 + the 2 new product-gate mirrors); mutation
  probe fails 1/276 with the conjunct removed, restored green
- newman: supervised-scope 44/44 · isolation 20/20 · data-filter 29/29 · hierarchy-list 9/9 + 9/9
  — all with the assertions now real, on the directory-flavoured rig

## Commits

- The commit carrying this note: `fix(supervised-scope): layer-3 review — real newman assertions
  (repo-wide sweep), product-gate confinement tests, spec/doc/runner corrections` (all 9 fixes +
  this review note, one commit).
