---
tags:
  - status/done
  - type/project
  - area/abac
  - area/spring-data
---

# STATUS T1 — Core: `ParentRef` + `abacParent()` + `Resource.ancestors`

> Filled in at the T1 checkpoint during the autonomous run. One commit per ticket.

## What shipped

The Spring-free core foundation for the ancestor chain — purely additive, following the established
`RoleDefinition` back-compat precedent (`mx-9c901e`):

- **`ParentRef`** (`opa-abac-core`) — a neutral `(String type, String id)` record, both non-null
  (compact-ctor `Objects.requireNonNull`). The declarative source of truth for **one hop**; carries no
  Spring/JPA concern.
- **`AbacDataObject.abacParent()`** — a new **default method** returning `Optional<ParentRef>` (default
  `Optional.empty()`). A non-hierarchical resource declares no parent and behaves exactly as before; a
  hierarchical one overrides it to return its immediate parent.
- **`AbacContext.Resource.ancestors`** — a new `List<ParentRef> ancestors` field serialized as
  `input.resource.ancestors`, **root-first / leaf-excluded**, `@JsonInclude(NON_EMPTY)` (omitted when
  empty), defensively copied (immutable). A back-compat 3-arg `Resource(type,id,attributes)` constructor
  keeps every prior caller compiling and serializing byte-for-byte as before.

The exact `input.resource.ancestors` wire shape built:
`[{"type":"catalog","id":"1"},{"type":"category","id":"7"}]` for a leaf `product:42` (the leaf is **not**
in the list — it is already `resource.type`/`id`).

## Tests

Unit (plain JUnit, no Spring) — `:opa-abac-core:test` **green** (all prior tests + 10 new):
- `ParentRefTest` — U1: rejects null type / null id; holds type+id.
- `AbacContextResourceTest` — U2: ancestors serialize ordered root-first, leaf-excluded; U3: no ancestors
  → byte-for-byte as before (no `ancestors` key; identical to legacy ctor; field set stays `type/id/
  attributes`); U4: `abacParent()` defaults empty / can declare one hop / back-compat ctor yields empty
  ancestors; plus defensive-copy + immutability + null-normalization.

## Architecture review + refactor (the ★ gate)

**Nothing substantive to refactor** — a clean additive change matching the `RoleDefinition` precedent.
What the review verified:
- **Spring-free boundary:** the three touched core files import only `jackson.annotation` + `java.util`;
  a core-wide grep for `org.springframework|jakarta.|javax.persistence` is empty. No new dependency on
  `opa-abac-core/build.gradle.kts`.
- **Additivity:** full `./gradlew build` green (every existing caller — `HttpOpaClientTest`, the security
  manager, the example apps — compiles unchanged); U3 asserts the no-ancestors wire shape is identical to
  the legacy 3-arg constructor's output.
- **Residual untouched:** `git diff --name-only` is exactly `AbacContext.java` + `AbacDataObject.java`
  (+ the new `ParentRef.java` and two tests); `CompileResponseParser` / `ResidualSpecificationFactory` /
  `PartialResult` / the operator set never appear. `RoleDefinition` unchanged.
- **Module-layer separation:** `ParentRef` / `abacParent()` / `ancestors` are all in core; no SQL, no OPA
  wire format here (those land in spring-data / the rego in later tickets).

## Integration / e2e

N/A for T1 — pure core, unit-tested only (the resolver ITs / Testcontainers begin at T2; e2e at T7). The
ancestor chain has no runtime behavior yet beyond serialization.

## Decisions

- Confirmed the additive technique: `@JsonInclude(NON_EMPTY)` + a back-compat constructor + defensive
  copy is the project's standard for evolving a published OPA-input-serialized record (same shape as
  `RoleDefinition.requiredTags`/`match_mode`). No new decision needed — the design pinned it.

## Commit

`feat(core): add ParentRef + abacParent() + Resource.ancestors (additive, Spring-free)` — see the T1
commit on `feature/void3110/hierarchy-single-resource`.
