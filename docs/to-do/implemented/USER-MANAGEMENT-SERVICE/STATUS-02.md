---
tags:
  - status/done
  - type/project
  - area/user-service
  - area/abac
---

# STATUS — Ticket 02: Core domain: User / Team / TeamMembership / RoleDefinition + seed

> Filled in at the ticket-02 checkpoint. See [[01-DECOMPOSITION]] ticket 2.

**Status:** ✅ done

## What shipped

The team + role-def core, designed and built together.

**Entities** (`…usermgmt.domain/`):
- `User` (`AbstractAuditableEntity`) — `subject` (IdP `sub`, unique) + `displayName`. A *principal*,
  not an authorizable resource, so no tags.
- `Team` (`AbstractSecuredEntity` → ABAC resource type **`"team"`**) — `name` + the **team-target**
  (`targetType` + `targetId`), unique on `(target_type, target_id)` (resource→team indirection).
- `RoleDefinitionEntity` (`AbstractAuditableEntity`) — `code`, `system` flag, nullable `teamId`,
  JSONB `attributes` + `permissions` (the `{resourceType:[verbs]}` shape OPA reads). Named distinctly
  from the library's `core.RoleDefinition` record (wire/decision shape) to avoid a clash.
- `TeamMembership` (`AbstractAuditableEntity`) — the **grant**: `teamId` × `userId` × `roleDefinitionId`,
  unique `(team_id, user_id)`.
- `SystemRoles` — constants holder: the four codes + their **stable seed UUIDs** (mirrored in the
  Liquibase seed), so app code resolves them without a query.

**Repositories** — `User`/`Team`/`RoleDefinition`/`TeamMembership`, with the finders later tickets need
(`findBySubject`, `findByTargetTypeAndTargetId`, `findBySystemTrueAndCode`, `findBySystemTrueOrTeamId`,
`findByTeamIdAndUserId`, …). `TeamRepository` also adopts `LockableJpaRepository` for the transactional
work in T3/T6.

**Schema** (Liquibase):
- `0001-create-usermgmt-schema.yaml` — the four tables with base-entity columns declared **inline**
  (greenfield tables; no separate alter), JSONB `attributes`/`permissions`/`tags`, FKs, the two
  unique constraints, and **two partial unique indexes** for role codes (system: `code WHERE team_id
  IS NULL`; team-scoped: `(team_id, code) WHERE team_id IS NOT NULL`) via a `<sql splitStatements>`.
- `0002-seed-system-roles.yaml` — seeds `owner`/`administrator`/`member`/`viewer` (`system=true`,
  `teamId=null`) with the stable ids/codes. Permissions use the wildcard resource type `"*"` (system
  roles are team-target-type-agnostic; the resolver expands `"*"` to the concrete type in T7).

**Web** — hand-written `UserMgmtMapper` (entity ↔ DTO, no MapStruct), `NotFoundException` +
`ApiExceptionHandler`, read-only `UserController` (create/list/get) and `TeamController` (list/get).
**`AuditingConfig`** — `@EnableJpaAuditing` + the `OffsetDateTime` `DateTimeProvider` (the known
`LocalDateTime→OffsetDateTime` auditing gotcha) + an `AbacAuthentication`-based `AuditorAware`.

OpenAPI spec extended with `User`/`UserRequest`/`Team` schemas + the read endpoints.

## Tests

`:example-user-management-service:test` → **green** (8 tests):
- `CoreDomainIT` — D2 (four system roles seeded, `system=true`, `teamId=null`, stable codes),
  stable seed ids + `"*"` permissions, D3 (JSONB `permissions`/`attributes` round-trip), D4
  (`(team_id, user_id)` unique → `DataIntegrityViolationException`). D1 (schema + `ddl-auto: validate`)
  is implicit in the context booting.
- `UserTeamCrudIT` — D5: create/list/get users + list/get teams over HTTP (`TestRestTemplate`,
  random port), plus a 404 path. Runs under a permissive test security chain (the service's own
  production `SecurityConfig` lands in T4).
- `ContextLoadsIT` still green.

`./gradlew build` (whole repo) → **green**.

## Architecture review + refactor

- **`role ≠ grant`** ✅ — `RoleDefinitionEntity` (role) and `TeamMembership` (grant) are modeled as
  separate entities; the membership carries the role via `roleDefinitionId`, not a role-per-team.
- **Boundary check** ✅ — library public APIs untouched. `Team` *adopts* `AbstractSecuredEntity`
  (so it's a real ABAC resource type) and the others adopt `AbstractAuditableEntity` — pure consumption
  of the base stack.
- **Naming** — `RoleDefinitionEntity` deliberately distinct from `core.RoleDefinition`; the entity is
  the persistent role, the record is the wire/decision shape the resolve API (T7) returns.
- **Fail-closed / hard rules** — no authorization or grant logic yet (that's T3+); the schema just
  *enables* the rules (the unique constraints, the system/immutability flags). Nothing to fail-open.

**No refactor applied** — the design held; no invented churn. One thing flagged for downstream: the
`SystemRoles` seed-id coupling to the Liquibase seed is intentional and documented (a `CoreDomainIT`
case pins the ids so drift is caught).

## Integration / e2e

All ITs run against **real Postgres via Testcontainers** (`postgres:16-alpine`), never H2 — so the
JSONB mapping, the partial indexes, the `timestamptz` audit columns, and `ddl-auto: validate` are all
exercised against the real dialect. No rig/newman yet (T9).

## Decisions recorded

Recorded one Mulch **pattern**: the team/role-def core domain shape (role≠grant entities, JSONB
permissions in the OPA-input shape, `RoleDefinitionEntity` vs `core.RoleDefinition` naming, stable
system-role seed ids, the `"*"` wildcard system-role permissions, and the **two-partial-unique-index**
trick for system-vs-team role codes). `relates-to mx-b17da2`. Synced as a `.mulch`-only commit.

## Commit

- `mulch: record team/role-def core domain pattern (T2)` — `23ce4aa` (`.mulch` only).
- `feat(user-mgmt): core domain + system-role seed (T2)` — the code + tests + this note.
