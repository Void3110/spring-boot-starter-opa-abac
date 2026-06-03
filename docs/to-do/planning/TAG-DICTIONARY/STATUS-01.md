---
tags:
  - status/done
  - type/project
  - area/user-service
  - area/abac
---

# STATUS — Ticket 01: TagDefinition domain + Liquibase + seed + read API + TagValueValidator

> Filled in at the ticket-01 checkpoint during the autonomous run. See [[01-DECOMPOSITION]] ticket 1.

**Status:** ✅ done

## What shipped

The **definition** layer of the dictionary, in the user-management-service (`…usermgmt`):

- **Domain:** `TagDefinition` entity (`key, scope, teamId?, valueType, cardinality, allowedValues jsonb,
  valuePattern?, system`) + enums `TagScope{GLOBAL,TEAM}`, `TagValueType{STRING,ENUM}`,
  `TagCardinality{SINGLE,MULTI}`. Modelled on `RoleDefinitionEntity`: JSONB via
  `@JdbcTypeCode(SqlTypes.JSON)`, enums as `@Enumerated(STRING)`. `TagDefinitionRepository` (global /
  team / global-or-team finders).
- **Liquibase:** `0003-create-tag-definition.yaml` — the table, FK `team_id → team(id)` (CASCADE), and the
  **two partial unique indexes** (`uq_tag_def_global_key ON (key) WHERE team_id IS NULL`,
  `uq_tag_def_team_key ON (team_id, key) WHERE team_id IS NOT NULL`) via the `<sql splitStatements:true>`
  pattern. `0004-seed-tag-definitions.yaml` seeds the two GLOBAL/system keys: `sensitivity`
  (ENUM/SINGLE/[public,internal,confidential], id `…00a1`) and `region`
  (ENUM/MULTI/[emea,amer,apac], id `…00a2`). Both wired into the master changelog.
- **Read API:** `GET /api/v1/tag-definitions` (globals; `?teamId=` adds a team's keys) and
  `GET …/{id}`, via the OpenAPI-generated `TagDefinitionApi` → `TagDefinitionController` →
  `TagDefinitionService`, mapped by a new `UserMgmtMapper.toDto(TagDefinition)`. Read is open to any
  authenticated caller (the vocabulary isn't sensitive); defining is the secured op (ticket 2).
- **`TagValueValidator`** (the shared rule for ticket 2/3): cardinality (SINGLE rejects a list, MULTI
  rejects a scalar), value type (ENUM membership; STRING optional regex), empty-value rejection, and a
  **malformed stored regex fails closed** (never silently passes).

## Tests

`./gradlew :example-user-management-service:test` green — **all** modules' user-service tests pass,
including the new ones; no pre-existing test changed.

- **`TagValueValidatorTest`** (12, pure unit) — D6 (enum hit/miss, multi all-in/any-out), D7 (string
  no-pattern / match / mismatch / **malformed-pattern fail-closed**), D8 (single-given-array,
  multi-given-scalar), plus empty-scalar / empty-multi.
- **`TagDefinitionDomainIT`** (6, Testcontainers Postgres) — D2 (seeded globals: scope/system/value-type/
  cardinality), D3 (allowedValues JSONB round-trip), D4 (2nd global `sensitivity` → integrity violation),
  D5 (same key allowed globally + per team; 2 team keys same-team-same-key → violation; same key across
  different teams allowed).
- **`TagDefinitionReadApiIT`** (3, RANDOM_PORT secured chain) — D9 (globals list; globals+team list;
  get-one; unknown id → 404).
- **D1** (ddl-auto: validate) is proven implicitly: every IT boots the app against the real Liquibase
  schema with `ddl-auto: validate`, so the JPA mapping matching the schema is a precondition of the suite
  even running.

## Architecture review + refactor

- **Additivity / boundary:** T1 touches only the user-service — no `opa-abac-core`/`-security`/`-data`/
  `-starter` change. The additive library change is deferred to T4, as planned. ✅
- **Fail-closed:** the validator rejects empty values, enum misses, cardinality mismatches, and a
  malformed stored regex (catches `PatternSyntaxException` → invalid, never passes). ✅
- **Three-layer separation:** T1 is pure **definition** — `TagDefinition` constrains legality only; no
  assignment and no grant logic leaked in. ✅
- **Pattern reuse:** `TagDefinition` reuses the `RoleDefinitionEntity` partial-unique-index + JSONB scheme
  verbatim (not a new bespoke scheme); the repository mirrors `RoleDefinitionRepository`'s finder style;
  validation is one shared `TagValueValidator`, not duplicated. ✅
- One naming fix applied during the loop: the `Result` record's boolean component was renamed `ok` (from
  `valid`) to avoid clashing with the `valid()` static factory. No other refactoring was warranted —
  **nothing substantive invented.**

## Integration / e2e

Testcontainers ITs above (real Postgres, never H2). No rig/newman at this ticket (that is ticket 6).
Clean-room scan of the full T1 diff (new + changed files) is clean — no proprietary names/paths/ids.

## Decisions recorded

Nothing non-obvious beyond the existing `mx-40324e` (the partial-unique + JSONB role-def template, which
`TagDefinition` directly reuses) and `mx-94e70d` (the Phase-4.5 design). A consolidated insight is best
recorded once the grant half (T4/T5) lands — skipped here to avoid a thin record.

## Commit

One focused commit on `feature/void3110/tag-dictionary`: `feat(user-mgmt): add the tag-definition
dictionary domain, seed, read API, and validator (T1)`.
