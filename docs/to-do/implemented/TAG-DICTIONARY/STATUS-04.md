---
tags:
  - status/done
  - type/project
  - area/user-service
  - area/abac
---

# STATUS — Ticket 04: RoleDefinition.requiredTags + matchMode (the one additive core change)

> Filled in at the ticket-04 checkpoint during the autonomous run. See [[01-DECOMPOSITION]] ticket 4.

**Status:** ✅ done

## What shipped

The **requirement** half — a role can carry a tag requirement, added as the *only* library change, purely
additive.

- **opa-abac-core (the one library change):** `RoleDefinition` gains two optional record components,
  `requiredTags` (`@JsonProperty("required_tags")` `@JsonInclude(NON_EMPTY)`) and `matchMode`
  (`@JsonProperty("match_mode")` `@JsonInclude(NON_NULL)`), plus a new `TagMatchMode{ANY_OF,ALL_OF}` enum.
  The compact constructor defends/copies and normalizes `match_mode` (→ `ANY_OF` only when a requirement is
  present, else `null`). A **3-arg convenience constructor** (no requirement) keeps every prior caller
  compiling and serializing byte-for-byte as before.
- **user-service persistence:** `RoleDefinitionEntity` gains `required_tags` (jsonb, default `{}`) +
  `match_mode` (varchar, nullable) via Liquibase `0005-add-role-required-tags.yaml` (additive `addColumn`);
  a second 8-arg constructor (the 6-arg one delegates). The role-def management API
  (`RoleDefinitionService` create/update, controller, `RoleDefinitionRequest`/`Update`/`RoleDefinition`
  DTOs, `UserMgmtMapper`) accepts + returns the two fields, normalizing `match_mode` the same way as core.
- **resolve passthrough:** `EffectiveRoleService.resourceRole` now builds the 5-arg `core.RoleDefinition`,
  carrying `requiredTags`/`matchMode` verbatim into the resolved role the catalog consumes (the management
  projection stays 3-arg — requirements apply to the *resource* role).

## Tests

- **Whole-repo `./gradlew build` green** — all library modules + both example apps + every pre-existing test
  pass **unchanged** (the additivity proof).
- **core `RoleDefinitionTest`** (+6): C-core1 back-compat serialization (untagged role → `required_tags`/
  `match_mode` absent; exactly `code`/`attributes`/`permissions`), C-core2 new-fields round-trip + default
  `ANY_OF`, C-core3 convenience-constructor-has-no-requirement, defensive-copy/immutability, empty-tags-
  keeps-mode-null.
- **`RoleDefinitionManagementIT`** (+2): RD1 (requiredTags + ALL_OF persist + round-trip through the API),
  RD3 (no-requiredTags keeps the prior shape — empty/null).
- **`EffectiveRoleResolveIT`** (+1): RD2 (a role's requiredTags + matchMode ride through `/internal/
  effective-role` into the `core.RoleDefinition`, snake_case on the wire). The pre-existing
  `wireShapeMatchesCoreRoleDefinition` still passes → untagged roles unchanged.

## Architecture review + refactor

- **Additivity / boundary (the gate):** whole-repo build green, every old test unchanged; an untagged role
  serializes byte-for-byte as before (`wireShapeMatchesCoreRoleDefinition` + the C-core1 field-name assert
  both prove it). The **only** `src/main` library changes are `RoleDefinition.java` (additive) + new
  `TagMatchMode.java` — `opa-abac-spring-security`/`-spring-data`/`-starter` are **untouched** (verified by
  `git diff --name-only`). The 3-arg constructor preserves all 7 callers. ✅ No non-additive edit was
  needed.
- **Fail-closed:** `parseMatchMode` (resolve) and `normalizeMatchMode` (management) both return null on
  unknown/blank and default `ANY_OF` only with a requirement present — stored row and resolved role agree;
  a malformed mode never silently becomes a different grant. ✅
- **Three-layer separation:** T4 is pure **requirement** (the role carries it); the Rego match is T5. ✅
- **Pattern reuse:** the entity JSONB column + management/resolve passthrough mirror the existing
  `permissions` field exactly. ✅
- **No refactoring warranted** — the normalization is deliberately duplicated in core and the service
  because they are different layers (the library can't depend on the app), and each is one small method.
  Nothing substantive invented.

## Integration / e2e

Testcontainers ITs above + `ddl-auto: validate` boots clean against the new columns (every IT proves it).
Clean-room scan of the T4 diff clean. No rig/newman at this ticket (ticket 6).

## Decisions recorded

`ml record opa-abac --type pattern` — **additive record evolution**: extend a public Jackson-serialized
record without breaking the wire by (1) adding optional components with `@JsonInclude(NON_EMPTY/NON_NULL)`
so absent ⇒ old shape, (2) normalizing dependent fields to null when the feature is unused (here
`match_mode` is null unless `required_tags` is non-empty), and (3) keeping the prior-arity constructor as a
convenience delegate so every caller compiles unchanged. Prove it with a whole-repo build + a byte-shape
assertion. Relates to the library-spine role-def decision (`mx-360261`) and the Phase-4.5 design
(`mx-94e70d`). `ml sync` touched `.mulch/` only.

## Commit

One focused commit on `feature/void3110/tag-dictionary`: `feat(core): add additive
RoleDefinition.requiredTags + matchMode, with user-service persistence + resolve (T4)`.
