---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/user-service
---

# STATUS — T5: user-mgmt: hybrid assignment gates + `data.role.assignable`

**Status:** ✅ DONE (2026-06-12)

## What shipped

- `infra/opa/policies/role.rego` — `data.role.assignable` over two **raw row snapshots**
  (`{permissions, denied_actions}`): `default assignable := false`; true iff for **every** type key
  the candidate grants on, `effective_actions(candidate, type) ⊆ effective_actions(actor, type)`
  (the shared, wildcard-aware T2 function). Malformed/missing snapshots never reach the subset walk.
  `role_test.rego` = the P13 truth table (17 cells: subset/superset, denial-blocks/denial-rescues,
  wildcard four ways incl. the `"*"`-scoped actor denial, stale-token vacuity, malformed → false).
- `service/RoleAssignableClient` (app-side — `OpaClient` has no arbitrary-entrypoint call; the
  library stays untouched): `RestClient` POST to `{opa.abac.base-url}/v1/data/role/assignable`,
  2s connect/read timeouts; **any error / non-2xx / missing or non-boolean `result` → false**.
- `MembershipService.addMember/changeRole` — the gate pair replaces
  `subsetGuard.requireAssignableByActor`, inside the existing `lockTeam` transaction over lock-read
  snapshots: (1) cross-tier `actorLevel > candidateLevel`, levels from `attributes.role_level`,
  **missing/non-numeric on either side → reject** (pinned #5); (2) at senior (25): candidate ≤ 20
  AND the OPA verdict. Every rejection = `SubsetRuleViolationException` → `422
  ROLE_SUBSET_VIOLATION`. Level gates run first — `assignable` is never consulted when the tier
  already rejects (I7/I10 assert zero stub calls). `requireTargetIsNotTheOwner`,
  `resolveAssignableRole`'s owner block, and `TeamService.transferOwnership` unchanged.
- `TeamRoleCapabilities`: `senior → [read, manage]` (the coarse entry; the constraint lives in the
  gates); custom default stays `[read]`.
- **Deleted**: `SubsetGuard` + `PermissionSubset` (zero callers after T4+T5).
  `SubsetRuleViolationException` stays — it is the rejection contract.

## Tests

`opa test infra/opa/policies/` **157/157** (P13 included). `./gradlew build` green (full suite):
- **MembershipGateIT** (new; in-process `HttpServer` stub with programmable verdicts + a call
  counter, wired via `@DynamicPropertySource opa.abac.base-url`): I6 (senior+true → 201),
  I7 (senior→senior/admin → 422, **stub never consulted**), I8 (false → 422, no row),
  I9 (500 / missing-result → 422, no row), I10 (admin: below 201, peer 422, the designed
  admin-with-denial-assigns-full-WRITE cell, zero OPA calls), I12 (custom level-25 → 403 at
  `team:manage` — ceiling ≠ capability).
- **MembershipManagementIT**: the old `superpower` subset cell → `roleWithoutLevelIsNotAssignable`
  (pinned #5 — a stale level-less row rejects, membership unchanged).
- **MembershipConcurrencyIT migrated (I11, the Critical-1 re-proof)**: owner demotes admin → READER
  (10) in tx A; tx B (the admin granting MEMBER 20) **blocks** on the team row, then fails the
  **new cross-tier gate** on the post-commit snapshot. The contrast is sharp: the stale admin
  snapshot (30 > 20) would have PASSED — only the lock-read decision rejects.

## Architecture review + refactor

- **Test-shape finding (fix applied)**: the designed I10 cell can't ride the HTTP path — a custom
  level-30 code carries no `team:manage` (the I12 pin itself), so the call 403s before the gates.
  Converted to a service-level call (the `MembershipConcurrencyIT` precedent). **Carry-to-T7:** the
  e2e E4 designed cell has the same constraint through the gateway — it needs a direct-DB tweak
  (like E5's INSERT) or an equivalent reachable construction; decide there.
- **Latch-cell redesign (fix applied)**: demote-to-senior + grant-admin would reject on the STALE
  snapshot too (30 ≤ 30) — outcome couldn't distinguish stale-read from lock-read. Switched to
  demote-to-reader + grant-member, where the stale snapshot passes and only the lock-read rejects.
- **Accepted bounded trade-off**: the `assignable` HTTP call runs while holding the team-row lock —
  decide-under-protection requires the verdict over lock-read snapshots (the design's pin); the
  cost is bounded by the 2s+2s client timeouts on a per-team lock.
- Fail-closed verified end-to-end: level-unreadable rejects; OPA non-answer rejects; gates ordered
  level-first; `default assignable := false` with malformed-input guards.

## Decisions

- The latch-cell shape (above) — same invariant, sharper proof.
- The designed-cell test level (above).

## Commit

`feat(usermgmt): hybrid assignment gates + data.role.assignable (Phase 6.5 T5)`
