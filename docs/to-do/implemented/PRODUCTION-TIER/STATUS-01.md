---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/spring
---

# STATUS — T1: user-service: the `operatorManaged` dictionary flag, the `env` seed, and the wire models

**Status:** ✅ DONE

## What shipped

- **`TagDefinition` entity** (`…usermgmt.domain`) — new `operator_managed` boolean column, mapped as
  `operatorManaged`, defaulting `false`. It sits **alongside** `is_system`, whose meaning ("the
  definition is immutable through the API") it does not replace: the new flag protects the **values**,
  the old one the **shape**. The pre-existing 9-arg constructor is kept as a convenience delegate to a
  new 10-arg one (`operatorManaged = false`), so every existing call site — `TagDefinitionService`,
  three test fixtures — compiles **unchanged**.
- **Liquibase `0008-operator-managed-tag.yaml`** (appended to `db.changelog-master.yaml` after `0007`),
  two changesets: `0008-add-operator-managed` (the column, `NOT NULL DEFAULT false`) and
  `0008-seed-tag-env` — the `env` seed row, id `00000000-0000-0000-0000-0000000000a3`, `GLOBAL` /
  `ENUM` / `SINGLE` over `["production","staging","dev"]`, `is_system=true` **and**
  `operator_managed=true`, mirroring the `0004` seed shape (including its `version: 0` column).
- **`user-mgmt-api.yaml`** — the **response** schema `TagDefinition` gains `operatorManaged` with the
  documented meaning. **No request schema changed**: the flag is not client-authorable, and GLOBAL
  definitions have no public create path at all (only `createTeamTagDefinition` exists).
- **`UserMgmtMapper.toDto`** sets the new field. The internal projection
  (`InternalResolveController.tagDefinitionsForResource`, `GET /internal/tag-definitions`) maps through
  that same method, so it carries the flag with **no further change** — verified by I2 rather than
  assumed.
- **Guide delta** — a new *Operator-managed keys* subsection in `docs/guides/TAG-BASED-AUTHORIZATION.md`
  (Layer 1): the `system`-vs-`operatorManaged` table, the not-client-authorable rule, the `env` seed,
  and ADR 0030 §3's **trust dependency** (untagged ⇒ non-production holds only while `env` has no public
  write path).

## Tests

`./gradlew :example-user-management-service:test` — **BUILD SUCCESSFUL**; the whole pre-existing suite
unmodified and green. Five new cases:

| Case | Where | Asserts |
|---|---|---|
| **U1** | `OperatorManagedTagIT.operatorManagedDefaultsToFalseWhenUnset` / `…PersistsWhenSet` | the column maps and round-trips; a definition saved through the 9-arg shape reads back `false` |
| **U2** | `web/UserMgmtMapperTagDefinitionTest` (3 cases) | `toDto` carries `true` **and** `false`; the 9-arg entity shape maps to `false` |
| **I1** | `OperatorManagedTagIT.seedsEnvAsAnOperatorManagedGlobalEnum` / `preExistingSeedsStayUnmanaged` | the seed row's every field; `sensitivity`/`region` stay `operator_managed=false` (the additive default) |
| **I2** | `OperatorManagedTagIT.internalProjectionCarriesTheFlagForEnvAndFalseForTheOthers` | `GET /internal/tag-definitions` returns `env` with `operatorManaged:true` and the two older globals with `false` |

`ddl-auto: validate` boots clean — the ITs extend `AbstractSecuredPostgresIT` (real Postgres via
Testcontainers), so **booting at all** is I1's first half: the `0008` changeset and the entity mapping
agree. `opa test infra/opa/policies/` untouched by this ticket (unchanged until T4).

## Architecture review + refactor

Inline ★ review over the ticket's diff (the gate's checklist, before the ITs' final run).

- **Fail-closed** — T1 adds no decision path, so the invariant it can touch is the *supply* side: the
  catalog service cannot enforce what it never receives. The failure mode that would matter is the flag
  being **silently dropped in the projection**, which reads exactly like "the key is not
  operator-managed" — a widening. That is why I2 asserts the flag over the wire on the internal
  endpoint rather than trusting that the shared `toDto` carries it.
- **Security — the widening that would matter here is a client-authorable `operatorManaged`**, and it
  cannot happen: the field is in the **response** schema only (grep of `user-mgmt-api.yaml`: one hit,
  under `TagDefinition`, not `TagDefinitionRequest`); the generated request models have no such
  property; and no service path sets it — `defineForTeam` constructs through the 9-arg delegate
  (`false`), `updateTeamKey` mutates shape fields only.
- **Refactor applied (the one substantive finding): `setOperatorManaged` was removed.** The first draft
  gave the field a setter for symmetry with every sibling field. On review that is precisely the wrong
  symmetry: the flag's entire purpose is "values under this key are not writable through the API", and
  an unused public mutator on it is the first affordance a future caller would reach for. Nothing needs
  it — JPA uses field access here, the constructor sets it, and the seed sets it in SQL — so its
  **absence is a structural guarantee** rather than a rule someone must remember. Tests re-run green
  after the removal. (`setKey`/`setScope`/`setSystem` are likewise callerless on this entity, so the
  removal costs no established idiom.)
- **Wiring** — every seam has a named consumer and a non-happy-path test: the column → the entity (U1,
  incl. the default), the entity → `toDto` (U2, both values), `toDto` → the internal projection (I2,
  the flag **and** its `false` control), and the seed → the tier (T2's enforcement, T4's policy).
- **Boundary / additivity** — no library module, no policy file, no catalog-service file touched; the
  additive-column + convenience-constructor-delegate shape is the repo's established evolution pattern
  (mx-9c901e), and the existing suite passing **unmodified** is the proof.
- **Module-layer separation** — the dictionary flag lives in the user-service only. Enforcement (T2)
  belongs to the catalog service, where tag *values* are written; nothing here reaches across.
- **Pattern reuse** — `0004-seed-tag-definitions.yaml`'s changeset shape (columns, `version: 0`, stable
  id in the same `…00aN` sequence) reused, not reinvented.
- **Static-analysis gate** — `./.sonar-local/sonar-local.sh`: **1 finding, S107** ("constructor has 9
  parameters") on `TagDefinition`'s hydration constructor. This is a **documented by-design FP class**
  in Mulch `quality-gate-sonar` **mx-302e78**, which names `TagDefinition` explicitly (JPA entity
  hydration constructors in `example-*` demo modules, one parameter per persisted column, protected
  no-arg JPA constructor present). Not re-fixed, per the FP-catalog discipline. No other findings on
  the changed files.

## Integration / e2e

ITs only (this ticket predates the rig work). **I1** and **I2** as tabled above, both green under
Testcontainers Postgres. No e2e in T1 — the rig matrix is T6's, in part 1.

## Decisions

- **No seam deviations.** Every seam the decomposition named was found exactly as described: the entity
  fields and `is_system` mapping, `0007` as the master changelog's last include, `toDto` at
  `UserMgmtMapper:95-112`, and the internal projection mapping through that same `toDto`.
- **The 9-arg constructor is kept as a delegate rather than migrated.** The decomposition did not pin
  how the widened constructor should reach its four existing call sites; keeping the old arity as a
  convenience overload leaves `TagDefinitionService` and the three test fixtures byte-identical, which
  is the additivity discipline this slice applies to the library and is worth applying here too.
- **The flag has no setter** (see the review section) — a deliberate asymmetry with the entity's other
  fields, documented in the getter's javadoc so it does not read as an oversight and get "fixed" later.

## Commit

`feat(production-tier): add the operatorManaged dictionary flag and seed env (T1)` — entity + changeset
`0008` + OpenAPI response schema + mapper + the guide subsection + the five new cases, on
`feature/void3110/production-tier`.
