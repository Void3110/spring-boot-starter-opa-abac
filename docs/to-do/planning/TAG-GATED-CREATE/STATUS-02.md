---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/opa
  - area/spring
  - area/catalog-service
---

# STATUS — T2: the starter contract — parent_attributes + the three annotation attributes (U13–U22)

**Status:** ✅ done — 2026-09-11 (collaborative build)

## What shipped

- `opa-abac-core` — `AbacContext.Resource` gains the sixth component
  `@JsonInclude(NON_NULL) @JsonProperty("parent_attributes") Map<String, Object> parentAttributes`,
  null-preserving in the compact constructor; the 5-arg constructor kept as a compat constructor beside
  the 3- and 4-arg ones (javadoc: the three states, never derived from `root_attributes`).
- `opa-abac-spring-security` — `@OpaPreAuthorize` gains `parentResourceType`, `parentResourceId`,
  `attributes` (SpEL, blank by default; the fail-closed edges in the javadoc).
  `OpaPreAuthorizeAuthorizationManager`: `resolveCheck` = `withParentDeclaration(resolveTarget(…))`;
  the type-level branch builds the resource from `declaredAttributes(…)` (`Optional` — empty ⇒ deny for
  a non-map / non-string-keyed / null-valued payload; SpEL `null` ⇒ `{}`); `attributes` on an instance
  form (`resourceId` / `resource()`) ⇒ deny; half a parent pair or a parent expression resolving to
  null/blank ⇒ deny; `ResolvedCheck` carries the declared `(parentType, parentId)`;
  `enrichWithParentAttributes` runs after `enrichWithRootAttributes` (which now preserves the parent
  declaration) through the generalized `resolveAttributesOf(type, id)` (the former
  `resolveRootAttributes`, byte-identical behavior: memo read-through, decision-independent put, `null`
  on any failure ⇒ the field absent). Class javadoc gains the ADR 0034 section.
- Guides: `ABAC-AUTHORIZATION.md` gains "Placement-parent enrichment — `input.resource.parent_attributes`
  (ADR 0034)" beside the ADR 0032 subsection; `ATTRIBUTE-RICH-PRE-AUTHORIZATION.md` — the mechanism
  diagram, a bullet for the two declarations, three rows in the split-failure-semantics table.

## Tests

- `AbacContextParentAttributesTest` (core, 11 cells — U13–U15): the 3/4/5-arg constructors serialize
  byte-for-byte as before (the exact strings the ADR 0032 test pins), `parentAttributes()` null on every
  compat arity, the three states on the wire, both enrichments side by side (root first), null preserved,
  the defensive copy immutable and detached, the whole context.
- `OpaPreAuthorizeParentAttributesTest` (spring-security, 19 invocations — U16–U22): declared attributes
  reach the decision, a null payload ⇒ `{}`, the SpEL ternary parent resolves to the catalog / the parent
  category (asserted on the resolver's received `(type, id)`), the product create carries the category's
  map and the catalog's `root_attributes` side by side, one resolver call for a top-level create (the
  memo), a null-valued payload denies, a payload on an instance form denies (both forms), a parameterized
  cell for the four un-honorable declarations (string / number payload, blank parent, half a pair) —
  all before OPA is asked; resolver empty / throws / no support ⇒ absent with the payload still riding;
  an untagged parent ⇒ `{}`; an undeclared gate ⇒ no parent, empty payload, `root_attributes` unchanged.
- The ADR 0032 suites (`AbacContextRootAttributesTest` 8, `OpaPreAuthorizeRootAttributeEnrichmentTest`
  11) pass unmodified. `./gradlew build` (all eight modules, the Testcontainers ITs): **BUILD SUCCESSFUL,
  1,259 tests, 0 failures** — the compat-constructor claim across every call site.
- Local Sonar gate: first scan 3 findings (S1168 ×2 — the null-returning map sentinel; S5976 — four
  near-identical deny cells) → refactored to `Optional` and one `@ParameterizedTest` → **CLEAN**.

## Architecture review + refactor

Filled at the ticket's checkpoint: the review path used (the collaborative build's per-ticket read-through; the slice-level passes are recorded in STATUS-05), what it found, what was refactored (or "nothing substantive").

## Integration / e2e

None in this ticket (library only); T3 puts the declarations on the example's gates and pins the wire.

## Decisions

- `declaredAttributes` returns `Optional<Map>` rather than a null sentinel (Sonar S1168) — `empty()`
  is the deny; `Optional.of(Map.of())` is the honest "no payload".
- The parent declaration is honored on an instance form too (only `attributes` conflicts with it):
  nothing in ADR 0034 forbids enriching an instance decision with a declared parent, and refusing it
  would be an undocumented rule.
- `withParentDeclaration` wraps `resolveTarget` inside `resolveCheck` (the SpEL context stays local to
  `resolveCheck`); the resolve itself happens in `authorize()` after the root enrichment, which now
  rebuilds `ResolvedCheck` with the parent coordinates preserved — the order of enrichment is the
  contract.

## Commit
