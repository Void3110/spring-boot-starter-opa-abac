---
tags:
  - status/done
  - type/project
  - area/user-service
  - area/abac
  - area/spring
---

# STATUS — T2: Resolve-side (`TeamRoleCapabilities` → category tokens; `validateContract` rejects custom team tokens)

**Status:** ✅ DONE (2026-06-15)

## What shipped

- **`TeamRoleCapabilities` recast to category tokens.** `BY_CODE` now maps system codes to the coarse
  category tokens `team.rego` expands: `owner`/`administrator` → `[READ, CONTROL, TAG]`, `senior` →
  `[READ, CONTROL]`, `member`/`reader` → `[READ]`; `forCode` default (every custom code) → `[READ]`.
  Dropped the retired fine-verb constants (`MANAGE`/`DEFINE_ROLES`/`DEFINE_TAGS`/`TRANSFER_OWNERSHIP`) —
  no external consumer (verified by grep). Class javadoc rewritten to the category model + the note that
  the two owner-only verbs are the `team.rego` fence (by `owner` code), not tokens here.
- **`EffectiveRoleService.managementRole` unchanged in shape** — still
  `Map.of("team", TeamRoleCapabilities.forCode(code))`; it now emits category tokens (the file itself was
  not edited; `git diff` confirms).
- **`PermissionCategories.EXPANSION` mirrored to the JSON** — added `CONTROL → [add-member, change-role,
  remove-member]` and `list-members` to `READ`, so the U9 parity test stays green. Added
  `CONTROL_PLANE_CATEGORIES = {CONTROL}` and `AUTHORABLE_CATEGORIES = {READ, WRITE, TAG, GRANT}` as the
  one home for "which categories a custom role may author" (CONTROL is control-plane-only).
- **`RoleDefinitionService.validateContract` tightened.** New `rejectTeamManagementTokens` step (runs
  before the catalog-plane category/ceiling loop) rejects, with the named 422 message
  `ROLE_DEFINITION_INVALID`:
  - `CONTROL` under **any** key (control-plane-only, never authorable); and
  - a team-management category (`CONTROL`/`TAG`) under a `"team"` key (`TAG` there would grant team
    `define-tags`).
  `READ` under `"team"` (the harmless list-members loosening) still validates; catalog-plane authoring is
  unchanged. The category-loop now checks `PermissionCategories.AUTHORABLE_CATEGORIES` (not `categories()`,
  which now includes CONTROL).

## Tests

`./gradlew :example-user-management-service:test` (service-layer unit suite) — all green:

| Class | Tests | Covers |
|---|---|---|
| `TeamRoleCapabilitiesTest` (new) | 5 | U1, U2 — the ladder per code; custom → `[READ]`; no fence verbs as tokens |
| `EffectiveRoleServiceManagementRoleTest` (new) | 3 | U3 — `managementRole` emits `[READ,CONTROL,TAG]` for admin, `[READ]` for custom, empty for no-membership (mock-driven) |
| `RoleDefinitionContractTest` (extended +5) | 17 | U4 — custom `team:[CONTROL]`→422, `team:[TAG]`→422, `CONTROL` under any key→422, catalog-only still validates, `team:[READ]` allowed |
| `PermissionCategoriesParityTest` (extended +1) | 3 | U9 — Java↔JSON parity holds with CONTROL+list-members; the authorable/control-plane sets partition the vocabulary; no ceiling holds a control-plane category |
| `TeamRoleDefinitionSupplierTest` (unchanged) | 4 | B2 tri-state contract still green (untouched) |

`./gradlew :example-user-management-service:compileJava` green. `opa-abac-core`/`spring-security`/
`spring-data`/`starter` untouched (`git diff --stat` empty — core stays Spring-free).

## Architecture review + refactor

Self-review at the ★ gate found **one substantive improvement, applied**:

- **Cohesion / DIP refactor:** I first added the authorable/team-management sets as local literals in
  `RoleDefinitionService`. On review, "which categories are authorable / which are control-plane" is
  vocabulary knowledge owned by `PermissionCategories` (the category-vocabulary class), not the service.
  Moved `AUTHORABLE_CATEGORIES` + `CONTROL_PLANE_CATEGORIES` into `PermissionCategories` and had
  `validateContract` depend on those; added a **partition parity test**
  (`authorableAndControlPlaneSetsPartitionAllCategories`) so the two sets can never drift from the table
  (every category is in exactly one set; no ceiling references a control-plane category). Re-ran — green.

Verified invariants:
- **Fail-closed / security (the load-bearing T2 property):** a custom role's stored `team` tokens can
  **never** reach `managementRole` — `forCode(customCode)` returns `[READ]` regardless of stored tokens
  (proven by `customManagementRoleEmitsReadOnly`), AND `validateContract` 422s storing `CONTROL`/`TAG`
  under `"team"` (two independent fences). The `"*": ["CONTROL"]` smuggle is rejected (CONTROL under any
  key); `"*": ["TAG"]` is allowed but inert (the projection forces custom team → `[READ]`; TAG-under-`*`
  is a catalog grant expanded to the target type, never team-management).
- **Boundary / additivity:** `managementRole`'s signature/return type unchanged; `resourceRole`/
  `resolveForResource` (the resource projection) untouched; `MembershipService` + the escalation gates
  untouched; `RoleDefinitionEntity`/repository/schema untouched. No controller change (T3).
- **Concurrency:** `validateContract` runs inside the existing `lockTeam`-guarded `create`/`update`
  transactions (unchanged); no new lock, no new mutation, no decide-under-protection ordering change.

## Integration / e2e

T2's correctness is proven at unit level here; the end-to-end 422 wire contract + the projection driving
real OPA decisions land in T4 (the headline IT).

## Decisions

- The new 422 message: `"custom roles cannot carry team-management categories (token '<TOKEN>' under key
  '<KEY>') — management capability is fixed to the system-role ladder; put the team's resource
  permissions on the team-target type"`. Error code unchanged: `ROLE_DEFINITION_INVALID` (422) — no new
  error type (ADR 0011 preserved).
- "team-meaningful token under team" = `CONTROL` or `TAG` (the two that grant team-management verbs);
  `READ` under `"team"` is the intended loosening and stays valid.

## Commit

`feat(user-service): T2 — TeamRoleCapabilities → category tokens + validateContract rejects custom team-management tokens`
