---
tags:
  - status/done
  - type/project
  - area/user-service
  - area/abac
---

# STATUS — Ticket 02: Dictionary management API (team:define-tags; system keys immutable)

> Filled in at the ticket-02 checkpoint during the autonomous run. See [[01-DECOMPOSITION]] ticket 2.

**Status:** ✅ done

## What shipped

The **governance** half of the dictionary — owner/administrator curate a team's tag keys at runtime,
dogfood-secured; global/system keys stay immutable.

- **API (OpenAPI-generated):** `POST/PUT/DELETE/GET /api/v1/teams/{teamId}/tag-definitions[/{key}]` plus
  `TagDefinitionRequest` / `TagDefinitionUpdate` schemas. The controller mirrors `RoleDefinitionController`
  — thin, each write `@OpaPreAuthorize(action="team:define-tags", resourceType="'team'",
  resourceId="#teamId")`.
- **Dogfooded authorization:** `team:define-tags` added to the `TeamRoleCapabilities` ladder for **owner
  AND administrator** (a management capability, like governed-tag models where admins curate the
  vocabulary). It shares the `team:` verb prefix, so `team.rego` (which is generic —
  `verb in permissions["team"]`) needed **no logic change**, only doc + test additions. The capability
  flows owner/admin → `EffectiveRoleService.managementRole` → `TeamRoleDefinitionSupplier` → the policy.
- **Shape validation (422):** `TagDefinitionService.validateShape` — kebab-case key format, ENUM requires
  a non-empty `allowedValues` (≤ cap of 50, no blanks), STRING forbids `allowedValues` and validates the
  optional `valuePattern` regex, and contradictions (pattern-on-ENUM, values-on-STRING) are rejected.
- **Immutability (409):** `requireTeamKey` returns a team key if present (mutable); else, if a global key
  of that name exists, rejects as immutable (an owner can't edit a seeded key via the team route); else
  404. Duplicate team key → 409.
- **Exceptions + handler:** `TagKeyConflictException`/`TagDefinitionImmutableException` → 409,
  `InvalidTagDefinitionException` → 422, `TagDefinitionNotFoundException` → 404 (wired into
  `ApiExceptionHandler`; the 422 handler renamed `handleUnprocessable` now covers subset + tag).

## Tests

`./gradlew :example-user-management-service:test` green; no pre-existing test changed.

- **`TagDefinitionManagementIT`** (9, RANDOM_PORT secured chain) — G1 owner-defines, G2 admin-defines,
  G3 member→403 + viewer→403, G4 edit/delete a global key → 409, G5 owner edits+deletes a team key,
  G6 ENUM-empty-allowedValues → 422 + bad-key-format → 422, plus duplicate-team-key → 409.
- **`team_test.rego`** (now 14/14 via `opa test`) — P1: `team:define-tags` allowed for owner +
  administrator, denied for member + viewer; default deny + unknown verb still hold.

## Architecture review + refactor

- **Additivity / boundary:** user-service only; no library change. ✅
- **Fail-closed:** member/viewer → 403 (the ladder default-denies); malformed → 422; system/global edit →
  409; the team route cannot reach a global key for mutation. ✅
- **Three-layer separation:** define is pure **governance** — no assignment, no grant logic. The
  dictionary constrains legality; the `@OpaPreAuthorize` decision governs *who* curates. ✅
- **Pattern reuse:** controller mirrors `RoleDefinitionController`; `team:define-tags` shares the `team:`
  prefix so the generic `team.rego` is unchanged; validation centralized in the service; the ladder
  extends `TeamRoleCapabilities`. ✅
- **Refactor applied:** `requireTeamKey` originally issued two redundant repository lookups (a global
  probe *and* a team probe, then a scope re-check). Simplified to "team key wins if present; else global
  ⇒ immutable; else 404" — one fewer query and a clearer precedence rule. Re-ran the suite: green.

## Integration / e2e

Testcontainers ITs (real Postgres) + `opa test` above. Clean-room scan of the T2 diff clean. No
rig/newman at this ticket (ticket 6).

## Decisions recorded

`ml record opa-abac --type pattern` — the **`team:define-tags` governance verb**: a management capability
granted to owner **and** administrator (distinct from owner-only `define-roles`), reusing the dogfooded
`@OpaPreAuthorize` + capability-ladder so the generic `permissions["team"]` policy needs no change.
Relates to the team/role-def core (`mx-40324e`) and the Phase-4.5 design (`mx-94e70d`). `ml sync` touched
`.mulch/` only.

## Commit

One focused commit on `feature/void3110/tag-dictionary`: `feat(user-mgmt): add team-scoped tag-dictionary
management with team:define-tags (T2)`.
