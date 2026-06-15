---
tags:
  - status/active
  - type/review
  - area/abac
  - area/opa
  - area/user-service
---

# Control-plane vocabulary categorization (Phase 6.7) — Code Review

> **Verdict**: ✅ **Approved with fixes** (2 findings, both fixed — 1 Medium test-gap, 1 Low doc-staleness; no Critical, no fail-open, no widening).
> **Scope**: extend the 6.5 coarse-category model to the control plane — one new `CONTROL` category, a category-driven `team.rego` + owner-only-by-code fence, `TeamRoleCapabilities` recast to category tokens, `validateContract` tightened, the `team:manage` verb split.
> **Branch**: `feature/void3110/control-plane-vocabulary` vs `main` (T1–T4, 4 feature commits).
> **Method**: multi-lens adversarial workflow (8 lenses → adversarial refutation → completeness critic → synthesis; 12 agents) + hand spot-verification of the load-bearing invariants.

## Summary

A clean slice. The multi-lens adversarial review ran 8 failure-mode lenses (fail-closed-authz,
core-boundary, rego-policy, persistence-concurrency, security-audit, api-contract, conflict-ci-deadcode,
infra-e2e); **zero findings survived refutation as fail-open/widening/concurrency defects**. The
completeness critic surfaced the two findings below — both real, both fixed in this review commit. The
headline invariants (fail-closed, the two-axis separation, the unspoofable owner fence, byte-mirror,
core-Spring-free) all hold, verified independently from source.

## Critical Issues

**None.** No path returns wider access on error/missing-input than on success (see Fail-closed
verification). No Critical from any lens.

## Medium Issues

**M1 (TEST_GAP) — the service-bundle `permission_categories.json` + `permissions.rego` had no
drift guard. FIXED.**
6.7 made `team.rego` category-driven, so the service bundle gained verbatim copies of `permissions.rego`
+ `permission_categories.json` (the mirror obligation, ADR 0015 §7 / 10-QA line 37). But the CI
"must not drift" step (`.github/workflows/ci.yml`) diffed **only** `team.rego` + `team_test.rego`, and the
U9 parity test (`PermissionCategoriesParityTest`) pinned Java→**infra**-json only — so the two new
service-bundle copies could drift from infra silently. The `diff` is clean today, but nothing enforced it.
- **Fix**: (a) extended the CI drift step to a loop over all four bundle files
  (`team.rego team_test.rego permissions.rego permission_categories.json`); (b) added
  `PermissionCategoriesParityTest.serviceBundlePolicyCopiesAreByteIdenticalToInfra` — a local drift guard
  (asserts both service copies are byte-identical to infra) so drift breaks `./gradlew build` even without
  Docker. Defense-in-depth: CI catches it in PR, the unit test catches it on every build.

## Low Issues

**L1 (DEAD_CODE) — stale `team:manage` Javadoc in `MembershipService`. FIXED.**
`MembershipService.java:21` still read `@OpaPreAuthorize(action="team:manage")` after the verb split
retired that verb (the controller now gates on the fine CONTROL verbs; `MembershipControllerAnnotationsTest`
asserts `team:manage` appears on no handler). This was surfaced at the T3/T4 checkpoints and left untouched
during the slice to honor the hard "no edit to `MembershipService`" implementation pin (which protects the
**escalation-gate logic**, not doc prose). Now fixed in the review phase — a **doc-only** edit: the javadoc
now references the fine verbs + frames it as the verb-category axis vs this service's escalation axis (the
two-axis split). No logic touched.

## Fail-closed verification (every error/empty path lands on deny/empty)

Independently traced + confirmed (incl. `opa eval` probes run during T1):
- **No `role_definition`** → `team.allow` is `false` (the category rule's `permissions` guard; the fence's
  `role_definition.code` is undefined → default deny).
- **Unknown/stale/removed token** (e.g. the retired `manage`) → ∅-expansion → deny
  (`test_unknown_team_token_expands_to_nothing`, `test_default_deny_stale_manage_verb`).
- **Empty `team` token list** → deny (`test_default_deny_empty_team_tokens`).
- **Owner-only fence** grants `define-roles`/`transfer-ownership` **only** for `code == "owner"** — a
  custom **level-40** role carrying `CONTROL` is denied (probe + R11). Keyed on the reserved code, not
  `role_level` → unspoofable.
- **The loosening is exactly `list-members`**: a `READ`-only role lists the roster but every mutation +
  `define-tags` still denies (I1/I2, R9, e2e E7b).
- **`AbacTestConfig` in-process mirror** (the no-container IT path): unknown verb → null → false; no
  role_def → false; unknown token → ∅; faithfully mirrors `effective_actions` + the owner-only fence.

## Security audit

- **Custom-role management-incapability — two independent fences** (no widening): the projection
  (`TeamRoleCapabilities.forCode(customCode)` → `[READ]` regardless of stored tokens) AND
  `validateContract.rejectTeamManagementTokens` (422s `CONTROL` under any key, or `CONTROL`/`TAG` under
  `"team"`). The `"*": ["CONTROL"]` smuggle is rejected; `"*": ["TAG"]` is allowed but inert (catalog
  grant, never team-management — the projection forces custom team → `[READ]`).
- **The owner fence is unspoofable** — keyed on the reserved `owner` *code*, never `role_level` (the
  rejected `role_level >= 40` option, ADR 0015 considered-options).
- **No IDOR / fallback / cache / injection / secret-leak surface** introduced — the verb rename is an
  annotation attribute, not a wire field; no new query, cache, or log path; no authn edge changed.

## Concurrency & idempotency (Rules 1/2/5)

- **`MembershipService` + the escalation gates byte-UNTOUCHED** (`git diff --stat` empty for
  `MembershipService`, `SubsetGuard`, `PermissionSubset`, `RoleAssignableClient`, `role.rego`). The
  decide-under-protection ordering, the lock-snapshot gates, and version binding are unchanged — 6.7 adds
  no mutation and no lock. `MembershipConcurrencyIT` + `MembershipGateIT` (12 cells) pass unchanged.
- `validateContract` runs inside the existing `lockTeam`-guarded create/update tx; no new race.

## Wiring & sibling sweep

- Every new seam has a tested consumer: `CONTROL` ← `team.rego` category rule (R8 + deny-override); the
  owner fence ← R10/R11; the recast `TeamRoleCapabilities` ← `managementRole` (U3); the tightened
  `validateContract` ← U4 (422 + still-valid cases); the renamed verbs ← U5 + I1–I6 + e2e E7/8a.
- **Sibling sweep (this review's fixes):** (M1) all four service-bundle files now in the CI loop + the
  unit drift guard — no other mirrored bundle exists. (L1) the only remaining `team:manage` in main
  source is the `MembershipController` javadoc that *intentionally documents the retirement* — correct,
  not stale. No stale fine-verb-ladder prose remains in main.

## Autonomous-run check (the branch came from an autonomous run; STATUS-01..04 present)

- **Agentic laziness** — none. Every ticket's deliverables/acceptance met; tests assert the actual *cut*
  (allow-vs-deny per role, the risen `opa test` counts 177/30, the live e2e 129 assertions), not just shape.
- **Self-preferential bias** — none. The STATUS notes are honest: T3 disclosed the `MembershipService:21`
  stale javadoc rather than glossing it (this review confirms it was the only one); the "review found
  nothing substantive" claims match the diff (the real refactors — the IT-mirror recast, the
  `PermissionCategories` cohesion move — are recorded, not invented churn).
- **Goal drift** — none. Fail-closed held across T1–T4; core stayed Spring-free; the table change stayed
  additive; the two-axis invariant held (re-proven through a renamed verb, I6). The two findings are a
  *consequence-not-swept* (the new mirror files outran the CI guard) and a *deliberately-deferred doc fix*
  — neither is an eroded invariant.

## What's done right

- The control plane is now genuinely symmetric with the catalog (one `effective_actions` home), and the
  `define-tags` deferral is closed by making its enforcement uniform — exactly the slice's headline.
- The unspoofable owner-by-code fence + the two-fence custom-role incapability are textbook fail-closed.
- The byte-mirror discipline is real (the diffs are clean) — and now *enforced* (the gap this review closed).
- The two intended externally-visible changes are exactly what changed, proven in unit + IT + live e2e;
  the catalog plane is provably unaffected (10 e2e matrices, 129 assertions, 0 failed).

## Test results

- `./gradlew :example-user-management-service:test`: **149 tests, 0 failures** (+1 vs the slice: the new
  service-bundle drift guard).
- `opa test`: **infra 177/177 · service-team 30/30**; all four bundle files byte-identical.
- Live e2e (pre-review run): **10 matrices, 129 assertions, 0 failed** (permission-categories +E7, team
  +8a, + 8 catalog-plane matrices unaffected).
- The new CI drift loop passes locally on all four bundle files.
