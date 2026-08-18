---
tags:
  - status/done
  - type/project
  - area/abac
  - area/spring
---

# STATUS — T3: lookupAll SPI + ResolveTarget (core) + memo batch integration

**Status:** ✅ DONE — the batch seam exists as an additive default method; the memo serves hits and
forwards misses as one batch; the **full `./gradlew build` is the `@FunctionalInterface` proof**
(every existing lambda/impl/test stub compiles unchanged).

## What shipped

- `ResolveTarget` (`opa-abac-core`, pure JDK) — a `(resourceType, resourceId)` record with value
  semantics; **both parts required** (`Objects.requireNonNull`): the batch exists for pages of
  instances, a type-level check (`resourceId == null`) stays on `lookup()` — a null id has no
  `target=<type>:<id>` wire encoding and would poison strict completeness.
- `RoleDefinitionSupplier.lookupAll(String, Set<ResolveTarget>)` — **default** method returning
  `Map<ResolveTarget, Optional<RoleDefinition>>`; javadoc carries the ADR 0024 contract verbatim
  (two-state entries; whole-batch outage; exactly one entry per requested target; empty set → empty
  map, no lookup; the loop default aborts on any single throw) and **references** `lookup()`'s
  tri-state — never redefines it. Returns `Map.copyOf(...)` (immutable). Known consumers named in
  the javadoc: T4's HTTP override, T5's advice pass, the memo decorator.
- `MemoizingRoleDefinitionSupplier.lookupAll` override — hits served from the request memo and
  **excluded** from the delegated set; misses forwarded as **one** `delegate.lookupAll`; every
  returned entry memoized; a whole-batch outage memoizes the **marker for every missed target**
  (a later single `lookup` replays the throw, delegate untouched); a **memoized outage hit inside a
  batch re-throws** (a batch never yields partial roles); a delegate return violating strict
  completeness (short/extra map) is synthesized into a whole-batch `RoleResolutionException`,
  memoized the same way — the memo never launders a partial map. No request → pass-through.

## Tests

- `RoleDefinitionSupplierLookupAllTest` (core) — U8: strict completeness, order-independence,
  empty-set zero-lookups, single-throw whole-batch abort, immutable return, `ResolveTarget` value
  semantics + null rejection. Every supplier in the suite is a **lambda** (the functional-interface
  proof in miniature).
- `MemoizingRoleDefinitionSupplierTest` — U9 (7 new tests): hits-excluded/one-batch, batch feeds
  the single-lookup memo, batch outage marks every miss, memoized-outage-hit fails the whole batch
  **before any delegation**, incomplete-delegate-batch → contract-violation outage, empty-set
  short-circuit, no-request pass-through.
- **Full `./gradlew build` GREEN** — the build-breaker watch: no existing supplier lambda or test
  stub anywhere in the repo needed a change.

## Architecture review + refactor

Nothing substantive to refactor. Points reviewed and held:

- **Strict completeness enforced at two layers**: the contract (default loop constructs it) and the
  memo merge (size + `containsAll` guard) — so even a misbehaving custom impl cannot slip a partial
  map past the memo to its callers (the `allowAll` length-mismatch idiom applied to a keyed map).
- A delegate returning garbage that is not the contractual exception (e.g. a null `Optional`)
  propagates as the bug it is — un-memoized, consistent with T2's "a bug is not an outcome" stance.
- Core stays Spring/Jackson-free: the two new types import only `java.util.*`.
- `lookup()`'s javadoc is byte-identical (verified in the diff) — invariant 3.

## Integration / e2e

Not applicable beyond the full build (no wire yet — T4); `ResolveBudgetIT` still green with the
memo's batch override present but unexercised by the same-root list path.

## Decisions

- `ResolveTarget` rejects nulls in the compact constructor (see "What shipped") — type-level
  lookups are deliberately not batchable.
- The memo classifies a strict-completeness violation as a **whole-batch outage** rather than
  letting it escape as a partial map or an `IllegalStateException` — fail-closed over diagnostic
  precision, with the violation message preserved in the exception.

## Commit

`feat(resolve): batch lookupAll SPI + ResolveTarget in core, memo batch integration (T3)` — see git.
