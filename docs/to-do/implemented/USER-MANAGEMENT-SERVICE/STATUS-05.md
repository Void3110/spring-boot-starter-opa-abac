---
tags:
  - status/done
  - type/project
  - area/user-service
  - area/abac
---

# STATUS — Ticket 05: Role-def management API (team-scoped custom roles)

> Filled in at the ticket-05 checkpoint. See [[01-DECOMPOSITION]] ticket 5.

**Status:** ✅ done

## What shipped

The team-scoped custom role-definition API — **owner only**, dogfooding the starter.

- `RoleDefinitionService` (`@Transactional`) — create / update / delete / list team-scoped custom
  roles. System-role **immutability** (update/delete of a `system=true` role → 409; a custom code
  clashing with a system code → 409) and the **subset-of-own guard**.
- `RoleDefinitionController` — each endpoint `@OpaPreAuthorize(action="team:define-roles",
  resourceType="'team'", resourceId="#teamId")`. Only the owner's resolved management ladder carries
  the `define-roles` verb, so administrators (who can manage members) **cannot** define roles —
  matching the system-role table in [[00-DESIGN]].
- New exceptions: `RoleConflictException` (409), `SystemRoleImmutableException` (409),
  `RoleNotFoundException` (404); `ApiExceptionHandler` mappings.
- `UserMgmtMapper.toDto(RoleDefinitionEntity)`; OpenAPI role-definition endpoints + DTOs
  (`RoleDefinition`, `RoleDefinitionRequest`, `RoleDefinitionUpdate` — `permissions` as
  `additionalProperties: array<string>`).

## Tests

`:example-user-management-service:test` → **green (25 total; +5 this ticket)**.
`RoleDefinitionManagementIT` (real Postgres + the real secured chain):
- **R1** owner creates / updates / deletes a team-scoped custom role;
- **R2** a custom role exceeding the owner's own perms (`delete` beyond `*:[read,write]`) → **422**;
- owner-only: an **administrator** defining a role → **403** (`define-roles` not in the admin ladder);
- **R3** a reserved system code on create → **409**; updating a system role → **409** (immutable);
- **R5** the list returns the four system roles **and** the team's custom role.

`./gradlew build` (whole repo) → green; catalog ITs unaffected.

## Architecture review + refactor

- **System-role immutability** ✅ — create-with-reserved-code and update/delete of a system role both
  rejected (409).
- **Subset-of-own guard** ✅ — enforced on create + update.
- **Owner-only** ✅ — `team:define-roles` is owner-only by the capability ladder; the admin-403 test
  proves the boundary.
- **Fail-closed / layering** ✅ — `@Transactional` service, thin controller, exceptions fail-safe.

**Refactor applied during the gate.** The review surfaced a real duplication: `MembershipService` and
`RoleDefinitionService` each had a private `requireSubsetOfActor` that resolved the actor's effective
permissions and called `PermissionSubset`. Extracted a single **`SubsetGuard`** component
(`requireWithinActorPermissions(actor, team, candidate)`); both services now depend on it, and
`RoleDefinitionService` no longer needs `EffectiveRoleService` directly. The subset rule now has exactly
**one** implementation (the decomposition's "shared, not duplicated" requirement). Re-ran the full
suite → all 25 green.

## Integration / e2e

ITs run the genuine dogfooded `@OpaPreAuthorize` → supplier → policy chain against real Postgres; the
OPA hop is the in-process client mirroring `team.rego`. Container rig + newman is T9.

## Decisions recorded

Nothing non-obvious beyond the design + the already-recorded subset-rule material (`mx-7d3605`,
`mx-b17da2`). The `SubsetGuard` extraction is a local tidy, not a durable cross-project insight — no
Mulch record (no ritual filler).

## Commit

`feat(user-mgmt): role-definition management + shared SubsetGuard (T5)` — code + tests + this note.
