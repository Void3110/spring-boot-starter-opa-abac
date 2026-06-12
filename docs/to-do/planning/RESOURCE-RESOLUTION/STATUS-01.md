---
tags:
  - status/done
  - type/project
  - area/abac
  - area/spring
  - area/opa
---

# STATUS — T1: Core: split SPI (AbacResourceResolver / AncestorChainSupplier) + Versioned/VersionGuard (Spring-free, additive)

**Status:** ✅ DONE

## What shipped

Five additions in `opa-abac-core` (`dev.dmitriikonovalov.opaabac.core`), all framework-free:

- `AbacResourceResolver` — `Optional<AbacDataObject> resolve(String resourceType, String resourceId)`;
  Javadoc pins one app-implemented dispatching bean and the fail-closed semantics: empty/throw → the
  caller **denies** (and explicitly contrasts it with the supplier's collapse semantics).
- `AncestorChainSupplier` — `List<ParentRef> ancestorsOf(String resourceType, String resourceId)`;
  Javadoc pins root-first/leaf-excluded order, starter-bound (apps never implement it), and throw →
  the caller **collapses to the empty chain** (contrasted with the resolver's deny).
- `Versioned` — `Integer getVersion()`; null = unguardable, documented.
- `VersionConflictException` — message carries the resource reference + expected/actual versions,
  nothing else.
- `VersionGuard.requireUnchanged(Versioned snapshot, Versioned current)` — throws on drift **and on a
  `null` version on either side** (fail loud, never silently pass); message names `type/id` when the
  snapshot (or current) is an `AbacDataObject`, else the class name.

Plus the lone spring-data line: `BaseModel<ID> extends Versioned` (pure hierarchy statement — the
method already existed).

## Tests

`VersionGuardTest` (7 cases): U1 equal-versions pass; U2 drift both directions, exact message
asserted (`Resource category/c-1 version conflict: expected 3, found 4`); U3 null version on either
side throws; class-name message fallback for a bare `Versioned`; null-argument NPEs.
`./gradlew :opa-abac-core:test` green; **`./gradlew build` green** (U4 — the `BaseModel` change
recompiled nothing else differently; all example ITs pass against real Postgres).

## Architecture review + refactor

Review path: fail-closed semantics, module boundary (import-set proof), pattern reuse vs
`RoleDefinitionSupplier`, message-content security.

- **Boundary proof (U4):** the five types' combined import set is `java.util.{List,Objects,Optional}`
  — zero Spring/JPA/Jackson.
- **Split-semantics confusion guard:** each SPI's Javadoc explicitly names the *other's* failure
  semantics ("deny" vs "collapse") so an implementer or a future manager change can't conflate them.
- **No internal detail leaks:** `VersionConflictException` message = reference + two versions only.
- **Nothing substantive refactored** — the types came out minimal on the first pass; both SPIs are
  `@FunctionalInterface` matching the existing core seam idiom.

## Integration / e2e

Not applicable for T1 (contracts only; consumers arrive in T2–T4). Full `./gradlew build` green is
the additivity proof.

## Decisions

- **Null-version guard failure throws `VersionConflictException`**, not a distinct exception type —
  the decomposition's "throws too" reading. One exception type keeps the 409 advice mapping total:
  every guard failure is client-visible and retry-able, never a silent pass; the message
  distinguishes the case (`expected null, found …`).
- `VersionGuard.describe` prefers the snapshot's ABAC identity (the resolver always returns an
  `AbacDataObject`), falling back to the current instance, then the class name — so production
  messages always carry `type/id`.

## Commit

`feat(core): resource-resolution split SPI + version binding types (T1)` — see `git log` on
`feature/void3110/resource-resolution`.
