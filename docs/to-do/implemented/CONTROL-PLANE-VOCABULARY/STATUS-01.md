---
tags:
  - status/done
  - type/project
  - area/user-service
  - area/abac
  - area/spring
---

# STATUS — T1: Vocabulary + policy (the `CONTROL` category, category-driven `team.rego`, the bundle mirror)

**Status:** ✅ DONE (2026-06-15)

## What shipped

- **`permission_categories.json` — both copies, byte-identical.** Added `"CONTROL": ["add-member",
  "change-role", "remove-member"]`; added `"list-members"` to `READ` →
  `["view", "list", "list-members"]`. `WRITE`/`TAG`/`GRANT` byte-unchanged. The diff is exactly
  additive (one new key + one new `READ` member).
- **`permissions.rego` copied verbatim** into the service bundle
  (`example-user-management-service/src/main/resources/opa/policies/`) so the dogfood `team.rego`
  resolves `data.permissions.effective_actions` under an isolated `opa test`. `permission_categories.json`
  also copied verbatim into the service bundle.
- **`team.rego` — both copies, byte-identical, category-driven rewrite.** `import data.permissions`
  (mirrors `catalog.rego`). Two `allow` rules:
  1. category-driven — `input.role_definition.permissions` guard, then
     `verb in permissions.effective_actions(input.role_definition, input.resource.type)` (the ADR-0015
     §3 / 00-DESIGN §2.2 reference rule, verbatim);
  2. owner-only fence — `verb in {"define-roles", "transfer-ownership"}` AND
     `input.role_definition.code == "owner"`.
  Kept the `verb` helper (split on `":"`) and `default allow := false`. Header rewritten to the
  category model + the two-axis note (verb category here; the escalation gates are `MembershipService`).
- **`permissions_test.rego`** (infra): the `READ`-expansion update (the known intended break) applied at
  every assertion that expands `READ` — 8 sites (`{view,list}` → `{view,list,list-members}`), incl.
  `test_denying_everything_granted_yields_empty_set` (now denies all three so it still yields ∅). Added
  5 `CONTROL`/team-plane cases: per-category expansion (R2), deny-override subtraction (R3), the
  `[READ,CONTROL]` senior union (R4), and an unknown-team-token → ∅ floor case.
- **`team_test.rego` — both copies, byte-identical, full rewrite** to the category model: the system
  ladder (owner/admin `[READ,CONTROL,TAG]`, senior `[READ,CONTROL]`, member/reader `[READ]`) × every
  live verb; the owner-only fence (R10 — define-roles/transfer-ownership allowed ONLY for `code=="owner"`,
  denied for admin/senior/member); R11 (a custom level-30 `"lead"` carrying `CONTROL` still denied —
  fence keyed on code, not level); R7 (senior holds `CONTROL` not `TAG` → no `define-tags`); R9
  (`list-members` for any READ-holder, every mutation still denied for member/reader); a `CONTROL`
  deny-override cell; R12 (unknown verb / stale `manage` / no role_definition / empty token list all deny).

## Tests

- **`opa test infra/opa/policies/`: 157 → 177 PASS** (+20).
- **`opa test example-user-management-service/src/main/resources/opa/policies/`: 14 → 30 PASS** (+16).
- `opa check` clean on both bundles. `./gradlew :example-user-management-service:compileJava` green (no
  Java touched in T1).
- **Mirror obligation:** `permission_categories.json`, `team.rego`, `team_test.rego`, `permissions.rego`
  all `diff`-clean (byte-identical) across the two bundles; `permissions.rego` present in the service
  bundle.

## Architecture review + refactor

Self-review at the ★ gate. **Found nothing substantive to refactor** — the policy matches the pinned
ADR-0015 §3 reference rule verbatim and the idiom mirrors `catalog.rego` (no novel expansion). What was
verified (with `opa eval` fail-closed probes, recorded so the gate is auditable):

- **Fail-closed:** (a) no `role_definition` at all → `define-roles` **false** (the fence's
  `input.role_definition.code` is undefined, default deny stands); (c) `code:"owner"` but no
  `permissions` → `add-member` **false** (the category rule's `permissions` guard); unknown/stale token
  → ∅ → deny (`test_unknown_team_token_expands_to_nothing`, `test_default_deny_stale_manage_verb`).
- **Security (the unspoofable fence):** (d) a **custom level-40** role carrying `CONTROL` asks
  `transfer-ownership` → **false** — the fence keys on the reserved `owner` *code*, not `role_level`, so
  no custom/non-owner role can ever reach the two escalation verbs. `list-members` in `READ` does not
  widen any mutation (member/reader deny add/change/remove). Both bundles edited byte-identically (no drift).
- **Boundary / additivity:** `catalog.rego`/`category.rego`/`product.rego`/`role.rego`/`gateway.rego`
  **untouched** (git `diff --stat` empty); `WRITE`/`TAG`/`GRANT` byte-unchanged; the table change is
  additive; `list-members` is inert on the catalog plane (no catalog endpoint asks it — catalog suite
  still 177/177 green). No `MembershipService`/escalation-gate edit (no Java in T1).
- **Concurrency:** pure policy — no mutation, no lock, no `MembershipService` touch.

One note carried to T2/T4 (not a T1 defect): `PermissionCategoriesParityTest` (U9) compares
`PermissionCategories.EXPANSION` to the JSON — it will go red until **T2** mirrors `CONTROL` +
`list-members` into that Java constant; and `AbacTestConfig.inProcessTeamOpaClient()` is still the
raw-match mirror — **T4** must recast it to the category-driven shape once the projection emits category
tokens. T1's acceptance is `compileJava` (not full `test`), so this is the expected inter-ticket state.

## Integration / e2e

T1 is the policy core; the IT/e2e proof lands in T4. T1 acceptance is `opa test` on both bundles (done,
risen counts) + the byte-identity diffs (done) + `compileJava` (done).

## Decisions

- The category rule keeps the `input.role_definition.permissions` truthy guard from the design's
  reference rule (the old `has_role_definition` check, inlined) — fail-closed and verbatim-to-ADR; not a
  deviation from `catalog.rego` (which is total via `effective_actions` returning ∅).
- `permissions_test.rego` lives only in the infra bundle (the service bundle holds only
  `team*`/`permissions`/the table) — the `READ`-expansion break is asserted there.
- **opa test counts:** infra **177**, service-team **30** (the new risen baselines for T4's cross-check).

## Commit

`feat(rego): T1 — CONTROL category + category-driven team.rego + bundle mirror`
