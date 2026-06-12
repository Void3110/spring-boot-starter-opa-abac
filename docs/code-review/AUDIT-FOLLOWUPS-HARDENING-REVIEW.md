---
tags:
  - status/done
  - type/review
  - area/abac
  - area/spring-security
  - area/spring-data
---

# Audit-followups hardening — Code Review

> **Verdict**: Approved with fixes
> **Scope**: The post-5.97 sweep of the [[RETRO-AUDIT-2026-06-12]] follow-ups — 13 commits touching
> all four library modules, both example services, the e2e runners, and docs (~920 insertions).
> **Branch**: `feature/void3110/audit-followups-hardening` vs `main`.

## Summary

The multi-lens adversarial workflow (8 lenses → per-finding refutation → completeness critic; 13
agents) confirmed **3 findings — 0 Critical, 2 Medium, 1 Low — none in the fail-closed, security, or
concurrency dimensions**. All three are contract/test-gap class and were fixed on the branch in the
same session. The hardening fixes themselves (path sanitization, fail-closed expiry, fresh
SecurityContext, version-bumped subtree rewrite, locked create path, team-row grant locks, actuator
lock-down, fail-closed match_mode, runner seeding) survived refutation unchallenged.

## Critical Issues

None.

## Medium Issues

1. **`STATE_CONFLICT` missing from the user-mgmt OpenAPI `errorCode` enum** (api-contract lens).
   The in-use-role-delete fix (in-method flush → the starter advice's 409) made `STATE_CONFLICT`
   newly reachable on `DELETE /role-definitions/{code}`, but `ProblemDetail.errorCode` is a *closed*
   enum that didn't list it — a generated client would fail to deserialize. **Fixed**: enum extended.
2. **The same omission in `catalog-api.yaml`** (the critic's sibling catch). The catalog has emitted
   `STATE_CONFLICT` since 5.97 (the I4/I5 409s) — the enum gap predates this branch and was caught
   only because the critic swept the mirror spec. **Fixed**: enum extended.

## Low Issues

3. **`EntityNotFoundProblemAdvice` had no through-the-HTTP-stack test** — only advice-unit and
   auto-config-presence tests. **Fixed**: `ResourceResolutionGateIT` gained a third deterministic
   race hook (`BEFORE_MUTATE`, firing between the handler's scope check and `mutate()`'s locked
   load) and the I8 case: the row vanishes in that window → the library `EntityNotFoundException` →
   the starter advice's `404 RESOURCE_NOT_FOUND` problem+json, live. (Found while wiring it: an
   AspectJ `execution` pattern naming the subclass does **not** match a method declared on the
   library base — the pointcut must name `AbstractCrudService.mutate` + `target()`.)

## Fail-closed verification

The fail-closed lens confirmed every new error/empty path lands on deny/empty: unsafe/empty OPA
policy paths throw inside the client's try and deny without an HTTP call (allow → `false`, compile →
`PartialResult.error()`, allowAll → all-false); a missing/non-numeric `exp` with validation on
rejects the subject; an unknown stored `match_mode` narrows to `ALL_OF` (never null → never the
wider ANY_OF default, never an absent role-def re-enabling the realm fallback); the runner seeding
fix *removes* a fail-closed crash without weakening any decision. No widening-on-failure found.

## Security audit

No findings survived. Checked: the actuator `authenticated()` catch-all (error dispatch stays
permitted; `/internal/**` unchanged in-network; health/docs explicit); the fresh-context swap keeps
the no-override-of-real-authentication property; the path sanitizer is a whitelist (no bypass via
encoding — the pattern admits only `[A-Za-z0-9_-]` and `/`); no secrets/internal state added to
logs (the rejected path is logged, consistent with existing path logging); the no-grant e2e role
narrows, never widens.

## Concurrency & idempotency

No findings survived. The decide-under-protection invariant holds across the swept surfaces: every
team-scoped grant mutation (membership add/change/remove, transfer-ownership, custom-role
create/update/delete) computes its subset/ceiling decision under the team-row lock that holds
through commit; `createWithPath` reads the parent path under the parent-row lock in the insert
transaction (lock order vs `reparentCategory` is acyclic — create holds only the parent row);
the version-bumped subtree rewrite is safe for its only caller (saveAndFlush → clear → rewrite →
fresh re-read). The two latch ITs (catalog create-vs-move, user-mgmt demote-vs-grant) prove the
serialization deterministically.

## Wiring & sibling sweep

- `EntityNotFoundProblemAdvice`: reachable via `ProductService.mutate` (now pinned end-to-end by I8).
- `createWithPath`: all three create flows migrated; `assignPath` retained for in-transaction test
  seeding with a javadoc pointing production flows at the new seam.
- The `STATE_CONFLICT` enum fix was swept to **both** specs (the critic found the catalog mirror).
- `lockTeam` exists in both mutating services; `transferOwnership` takes the same lock.
- The runner path-seeding fix covered all three lagging runners; the hierarchy matrix's no-grant
  foreign team is registered in the fixture-id table with the cross-matrix rule updated.

## Test results

- `./gradlew build`: green (all modules, regenerated DTOs from both spec edits, Testcontainers ITs).
- newman, full suite on a rig rebuilt from the branch (both service images): **9/9 runners green**,
  including the re-pinned hierarchy matrix (twice, proving re-run idempotency) and contamination
  re-checks of the filter and hierarchy-list matrices.
- `opa test`: not run — no `.rego` touched by this branch (the policy lens verified this).

## What's done right

- The branch's own validation (the whole-suite e2e on a rebuilt rig) caught the stale hierarchy
  negative cell *before* this review — the finding and its fix are part of the branch
  ([[RETRO-AUDIT-2026-06-12]] disposition addendum), with the lesson recorded in Mulch.
- Every behavioral fix carries a pinning test, including two latch-based concurrency ITs (the
  user-mgmt service's first, closing the guide-Rule-6 gap the audit flagged).

Related: [[RETRO-AUDIT-2026-06-12]] (the findings this branch disposes) ·
[[RESOURCE-RESOLUTION-REVIEW]] (the slice that re-shaped the deny semantics the e2e re-pin tracks).
