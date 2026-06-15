---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/security
  - area/spring
---

# STATUS — T3: spring-data — `HierarchicalAuthorizer` catches; `SubtreeSpecResolver` proven

**Status:** ✅ DONE

Hardening + proof — neither data-layer consumer has a realm fallback, so this is not a behavior change;
it stops the new exception escaping uglily and pins the one that already collapses it.

## What shipped

- **`HierarchicalAuthorizer.isAllowed(...)`** — the role lookup (governing-root) is now wrapped in
  `try { … } catch (RoleResolutionException e) → return false`, with the comment "B2: role-source outage
  → deny (no fallback in this seam …)" + a DEBUG log (class name only). Added a `static final Logger`
  field (none existed) + `RoleResolutionException` / SLF4J imports. The catch is **scoped to the role
  lookup only** — kept separate from the existing `catch (AncestorResolutionException)` (chain-collapse,
  a different axis that degrades to direct-grant-only, not deny).
- **`SubtreeSpecResolver.subtreeSpec(...)`** — **no code change**. The role lookup already sits inside
  its `catch (RuntimeException e) → Optional.empty()`, which collapses a `RoleResolutionException` to no
  widening. Added: a javadoc paragraph stating an outage is covered fail-closed by that catch, a one-line
  note in the catch comment, and the `RoleResolutionException` import (for the `{@link}`).

## Tests

- `HierarchicalAuthorizerTest` (QA **U5**): `roleSourceOutageDenies_neverCallsOpa` — supplier throws →
  `isAllowed==false`, `verify(opaClient, never()).allow(...)`.
- `SubtreeSpecResolverTest` (QA **U6**): `roleSourceOutage_isEmpty_noWidening` — supplier throws →
  `Optional.empty()`, `verify(ancestorResolver, never()).subtreeOf(...)`. (Complements the pre-existing
  generic `lookupThrows_isEmpty`; this one pins the **specific** `RoleResolutionException` type so a
  refactor can't silently widen it.)
- **2 new, all PASS**; full `:opa-abac-spring-data:test` green (all hierarchy + filter + residual tests).

## Architecture review + refactor

**Nothing substantive to refactor.** The one design check made hard: I considered whether the new
`HierarchicalAuthorizer` catch should also wrap `ancestorsOf` — **no**, that would entangle the two
failure axes (a chain collapse must degrade to direct-grant-only, *not* deny; a role outage must deny).
The new catch is correctly scoped to the role lookup only; the existing `AncestorResolutionException`
catch is untouched (the slice invariant "keep the axes separate" holds). Checks: **fail-closed** —
outage → `false` (U5, OPA never called) / no widening (U6, `subtreeOf` never called), no fallback in
either seam. **security** — the throw no longer escapes `HierarchicalAuthorizer` uncaught; no over-widen
in `SubtreeSpecResolver`; DEBUG logs class name only. **additivity** — `SubtreeSpecResolver` has zero
behavior change (javadoc/comment only); `HierarchicalAuthorizer` is additive (logger + catch), every
other path byte-identical. **module-layer** — both stay in spring-data; no reach across.

## Integration / e2e

N/A at the unit gate; covered by T5's whole-suite green build. The two unit cells are the deterministic
proof.

## Decisions

None reopened. Implements ADR 0014 §3 for the two data consumers (`HierarchicalAuthorizer` → `false`;
`SubtreeSpecResolver` → no widening, test-only). The two failure axes (role-source vs ancestor-collapse)
stay deliberately separate.

## Commit

`feat(spring-data): hierarchy consumers fail closed on role-source outage` — branch
`feature/void3110/supplier-outage`.
