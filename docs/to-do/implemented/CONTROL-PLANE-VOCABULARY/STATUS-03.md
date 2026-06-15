---
tags:
  - status/done
  - type/project
  - area/user-service
  - area/abac
  - area/spring
---

# STATUS — T3: Controller verbs (split `team:manage` into the membership fine actions)

**Status:** ✅ DONE (2026-06-15)

## What shipped

- **`MembershipController` — the four `@OpaPreAuthorize` verbs split** from the retired coarse
  `team:manage`: `listMembers` → **`team:list-members`** (now `READ`), `addMember` →
  **`team:add-member`**, `changeMemberRole` → **`team:change-role`**, `removeMember` →
  **`team:remove-member`** (the three `CONTROL` verbs). `resourceType="'team'"`, `resourceId="#teamId"`
  unchanged on each. Class javadoc rewritten to the fine-verb model + the two-axis note.
- **Three unchanged controllers verified, not edited** — `TagDefinitionController` (`team:define-tags`),
  `RoleDefinitionController` (`team:define-roles`), `TeamController.transferOwnership`
  (`team:transfer-ownership`).
- **`AbacTestConfig.inProcessTeamOpaClient()` recast** (test support) to mirror the **new
  category-driven `team.rego`** — verb ∈ `effective_actions(role_def, type)` (category tokens expanded
  through the parity-pinned `PermissionCategories.EXPANSION` minus `denied_actions`, concrete-key-wins +
  `"*"` fallback) OR the owner-only-by-code fence (`{define-roles, transfer-ownership}` AND
  `code == "owner"`). This was a **required build-breaker fix**: T1+T2 made the projection emit category
  tokens, so the old raw-match mirror (`granted.contains(verb)`) could no longer decide the dogfooded
  gates. Reusing the parity-pinned table keeps the IT math from drifting from production.

## Tests

- **New `MembershipControllerAnnotationsTest`** (reflection, U5): each handler's `action()` is the
  expected fine verb; `team:manage` appears on no handler; `resourceType`/`resourceId` unchanged. 6 tests.
- **Grep proof:** `team:manage` no longer appears as an annotation in `MembershipController` (only in the
  javadoc that explains the retirement); the four new verbs each appear exactly once.
- **Full `:example-user-management-service:test` green — 142 tests, 0 failures** (incl. all membership /
  gate / tag / transfer ITs through the updated mirror, against real Postgres via Testcontainers).
- `MembershipGateIT` (all 12 escalation-gate cells) still green through the renamed verbs — the two-axis
  invariant holds (the rename did not bypass `MembershipService`).

## Architecture review + refactor

Self-review at the ★ gate. **Found one required test-contract update + one necessary build-breaker fix;
no speculative refactor.**

- **Known intended break (the same class as the rego READ-expansion break):** the existing IT
  `viewerCannotManage` asserted a reader GETting `/members` → **403** — the *old* contract. Phase 6.7
  deliberately moves `list-members` to `READ`, so a reader can now list the roster. Rewrote it to
  `readerListsButCannotManage`: a reader **lists → 200** (the loosening) **but add → 403** (the loosening
  is exactly listing, nothing wider). This is intended-break maintenance, not a regression.
- **The `AbacTestConfig` mirror update (above)** was necessary, not optional: leaving the raw-match mirror
  would have left every membership IT red from T2 onward. Scoped to T3 because it is a direct consequence
  of the verb/projection change (test infrastructure tracking the policy), not a T4 feature; T4 then
  *uses* the corrected mirror for the new headline IT.
- **Two-axis / boundary verified:** `MembershipService` and its escalation gates **byte-untouched** (`git
  diff --stat` empty); the three other controllers untouched; the library untouched. `MembershipGateIT`'s
  full escalation matrix re-proves the gates fire identically through the renamed verbs.
- **Known stale doc, deliberately NOT edited (the pin):** `MembershipService.java:21` javadoc still says
  `@OpaPreAuthorize(action="team:manage")`. `MembershipService` is the **pinned-untouched** file (editing
  it — even a comment — is called out as the defect), so I left it byte-untouched and surface it here.
  The now-true model is reconciled in the **guides** in T4; if the maintainer wants the class javadoc line
  corrected, that is a one-line follow-up explicitly outside this slice's no-touch pin.

## Integration / e2e

T3's renamed verbs are exercised end-to-end by the existing membership/tag/transfer ITs (green through the
updated mirror) and re-proven at the rig in T4 (the e2e verb-split + member-can-list cells).

## Decisions

- The `AbacTestConfig` mirror reuses `PermissionCategories.EXPANSION` (the U9-parity-pinned table) rather
  than re-listing the expansion — so the IT decision math is the *same* table the app validates against
  and cannot silently diverge from the OPA data file.
- `MembershipService.java:21`'s stale `team:manage` javadoc left untouched to honor the hard
  "no edit to `MembershipService`" pin; disclosed rather than silently fixed.

## Commit

`feat(user-service): T3 — split team:manage into the membership fine verbs`
