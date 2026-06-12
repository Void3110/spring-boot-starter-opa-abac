---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/user-service
---

# STATUS — T3: user-mgmt: schema + the seed migration + the resolve wire

**Status:** ✅ DONE (2026-06-12)

## What shipped

- Liquibase `0006-permission-categories.yaml` (included from the master changelog):
  1. `denied_actions` jsonb, default `'{}'`, non-null, on `role_definition`.
  2. Seed migration by stable id: owner (`…0001`, 40) + administrator (`…0002`, 30) →
     `{"*": ["READ","WRITE","TAG","GRANT"]}`; **new `senior`** (`…0005`, 25,
     `{"*": ["READ","WRITE","TAG"]}`); member (`…0003`, 20) → `{"*": ["READ","WRITE","TAG"]}`;
     viewer (`…0004`) → **code `reader`**, `{"*": ["READ"]}` (same id — membership FKs untouched).
  3. The defensive sweep (raw SQL) rewriting stray flat tokens in non-system rows by the pinned
     rule (`read → READ`; `write → WRITE + TAG`; valid category tokens kept); whatever it cannot
     fix still denies via ∅-expansion.
- `RoleDefinitionEntity.deniedActions` (jsonb, mirrors `permissions`' mapping; setter-initialized
  — the constructors were not widened).
- `SystemRoles`: `VIEWER → READER` (+ `READER_ID`), new `SENIOR`/`SENIOR_ID`; `ALL_CODES` = 5.
  `TeamRoleCapabilities`' viewer entry follows the constant (behavior-neutral — viewer ≡ the
  custom-role default `["read"]`; senior's `manage` entry is T5's).
- `EffectiveRoleService.resourceRole` passes `expandWildcard(role.getDeniedActions(), targetType)`
  into the T1-widened core record — wildcard expansion applies to denials exactly as to grants.
  `managementRole` keeps the 3-arg constructor (empty denials; team verbs out of scope).
- `user-mgmt-api.yaml` `RoleDefinition` **response** schema + `UserMgmtMapper.toDto`: `roleLevel`
  (read from `attributes.role_level`, null if unreadable — a response lens, not an authz input)
  + `deniedActions`.

## Tests

`./gradlew :example-user-management-service:test` green against real Postgres (forced rerun):
- **I1** (CoreDomainIT): five seed rows; senior present at 25; `reader` present / `viewer` gone
  (same id); owner+admin carry all four categories; `denied_actions = {}` on every seed;
  denials round-trip jsonb.
- **I3** (EffectiveRoleResolveIT): `denied_actions` rides the resolve wire snake_case,
  wildcard-expanded (`{"*": ["delete"]}` → `{"catalog": ["delete"]}`); omitted entirely when
  empty (NON_EMPTY); owner resolves all four categories; reader resolves `["READ"]`.
- `ddl-auto: validate` proven by every IT's context boot against the migrated schema (I2).

## Build-breaker sweep (this commit)

`SystemRoles.VIEWER*` → `READER*` across: MembershipManagementIT, RoleDefinitionManagementIT,
TransferOwnershipIT, TagDefinitionManagementIT, CoreDomainIT, EffectiveRoleResolveIT,
MembershipConcurrencyIT, PaginationEnvelopeIT, OwnerOnCreateIT. Fixture tokens → categories in
RoleDefinitionManagementIT (CRUD cells), EffectiveRoleResolveIT, CoreDomainIT, ErrorContractIT.
System-role counts 4 → 5 in PaginationEnvelopeIT (×3) + RoleDefinitionManagementIT list cell.
**Deliberately NOT migrated** (they pin the OLD mechanism their owning ticket replaces):
the authoring-subset cell in RoleDefinitionManagementIT (~line 110, flips to a ceiling cell in
T4) and the assignment-subset `superpower` cell in MembershipManagementIT (replaced by the gate
matrix in T5) — both still 422 mid-branch because lowercase tokens ⊄ the migrated uppercase sets.

## Architecture review + refactor

- **Refactor applied**: `expandWildcard` previously let `"*"` REPLACE the map even when a
  concrete type key existed — the opposite shadowing of the policy-side `tokens_for` (concrete
  wins). Aligned to concrete-wins (pure-wildcard / pure-concrete behavior unchanged; mixed maps
  are unreachable today, but the two homes can no longer diverge on one). Re-tested green.
- Liquibase gotcha (fix-until-green loop #1): the jsonb `?` operator in the sweep SQL is parsed
  as a JDBC bind parameter (`syntax error at or near "$1"`) — replaced with `jsonb_exists()`.
  Recorded to Mulch.
- Fail-closed: by-id seed UPDATEs are deterministic; the sweep only maps by the pinned rule and
  anything else lands on ∅-expansion; an unreadable `role_level` maps to a null response field
  (lens) — the gate-side missing-level **rejection** is T5's contract.

## Decisions

- Entity denials are setter-initialized rather than widening the constructors (smaller blast
  radius; the seeds set the column, T4's service sets the field).
- `TeamRoleCapabilities` senior entry deferred to T5 as decomposed (forCode("senior") falls to
  the `["read"]` default mid-branch — nothing gates on it before T5).

## Commit

`feat(usermgmt): permission-categories schema + five-tier seed migration + resolve wire (Phase 6.5 T3)`
