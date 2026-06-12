---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/user-service
---

# STATUS — T1: Core: `RoleDefinition` gains `deniedActions` (the flagged build-breaker)

**Status:** ✅ DONE (2026-06-12)

## What shipped

- `opa-abac-core` `RoleDefinition`: new component `Map<String, List<String>> deniedActions`
  (`@JsonProperty("denied_actions")`, `@JsonInclude(NON_EMPTY)`), placed adjacent to `permissions`
  in the canonical constructor; compact-constructor null-normalization to `Map.of()` via the same
  `copyOfStringListMap` defensive copy `requiredTags` uses. Javadoc documents deny-overrides
  (subtracted **after** category expansion, ADR 0007) and that denials only narrow.
- The prior 5-arg canonical (tag form) became a **delegating convenience constructor** with an
  unchanged signature; the 3-arg form likewise. Result: **the flagged build-breaker never fired** —
  all 26 existing `new RoleDefinition(…)` call sites (11 files: core tests ×4, spring-security test,
  spring-data tests ×4, `DemoRoleDefinitionSupplier`, `EffectiveRoleService`) use the 3-/5-arg forms
  and compiled unchanged. Zero call-site edits were needed; the sweep verified, not modified.

## Tests

- **U1** `denialFreeRoleSerializesAsBefore` — 3-arg and 5-arg forms omit `denied_actions`; the 3-arg
  wire is exactly `code`/`attributes`/`permissions`.
- **U2** `missingDeniedActionsDeserializesToEmpty` — absent field → `Map.of()`, never null.
- **U3** `deniedActionsRoundTrip` — snake_case wire name survives serialize → deserialize.
- Plus null-normalization across all three constructors and the defensive-copy/immutability mirror
  of the `requiredTags` tests.
- `./gradlew build` green (all modules, ITs included) — every pre-existing serialization test
  unchanged-green.

## Architecture review + refactor

Focused self-review, nothing substantive to refactor:
- Fail-closed: null/absent denials normalize to the **empty** map — the neutral "withholds nothing"
  (pre-6.5 behavior); denials are a narrowing overlay, so no error path widens access.
- Boundary: zero new imports; core stays Spring-free; this is the slice's only `opa-abac-*` change.
- Pattern reuse: exact `requiredTags` mirror (NON_EMPTY / defensive copy / convenience preservation).
- Component order: `denied_actions` would serialize between `permissions` and `required_tags`, but
  it is omitted when empty, so all pre-existing wire shapes are byte-identical (U1).

## Integration / e2e

Not applicable to T1 (wire-record only); `./gradlew build` covers all module ITs.

## Decisions

- `deniedActions` sits adjacent to `permissions` in the canonical constructor (semantic pairing:
  denials subtract from grants). Both legacy constructors survive as documented conveniences, which
  contained the build-breaker to zero call-site churn.

## Commit

`feat(core): RoleDefinition carries denied_actions deny-overrides (Phase 6.5 T1)`
