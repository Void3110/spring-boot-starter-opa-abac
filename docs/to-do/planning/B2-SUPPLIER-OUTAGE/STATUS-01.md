---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/security
  - area/spring
---

# STATUS — T1: Core — `RoleResolutionException` + the tri-state SPI contract

**Status:** ✅ DONE

## What shipped

- **`RoleResolutionException`** (`opa-abac-core`, `dev.dmitriikonovalov.opaabac.core`) — a new
  `extends RuntimeException` with `(String)` + `(String, Throwable)` constructors. Javadoc: the
  fail-closed signal for the role-source seam (outage → result *unknown*); distinct from an
  authoritative no-role (`Optional.empty()`); consumers MUST fail closed, never fall back; an in-process
  supplier never throws; unchecked by design (keeps `@FunctionalInterface` lambdas); a **separate
  failure axis** from `AncestorResolutionException` (do not conflate); the cause is for logs only.
  Mirrors `AncestorResolutionException`'s shape verbatim.
- **`RoleDefinitionSupplier`** — javadoc-only change (no signature change, `@FunctionalInterface` kept):
  a **Tri-state contract** section + an `@throws RoleResolutionException` on `lookup` documenting
  `Optional.of` = resolved · `Optional.empty()` = authoritative no-role (designed, may fall back) ·
  **throw** = outage (caller MUST fail closed).
- **`NoOpRoleDefinitionSupplier`** — javadoc note: an in-process, deterministic supplier never throws
  `RoleResolutionException`.

## Tests

- `RoleResolutionExceptionTest` (QA **U1**): message-only ctor; message+cause ctor (cause is `isSameAs`);
  `instanceof RuntimeException` (unchecked). **3 new, all PASS.**
- The "core import set carries no Spring/JPA" half of U1 is enforced by a repo-wide grep
  (`grep -rl "import org.springframework\|import jakarta.persistence\|import javax.persistence"
  opa-abac-core/src/main/java` → NONE) rather than a brittle per-class reflective test; core has no
  Spring on its compile classpath, so a Spring import would not even compile.

## Architecture review + refactor

**Nothing substantive to refactor.** The exception is a verbatim shape-match to the existing
`AncestorResolutionException` family (same `extends RuntimeException`, same two ctors, same
fail-closed-signal javadoc shape) — inventing churn would be wrong. Checks: **boundary/additivity** —
core stays Spring-free (the new class has zero imports; grep clean; full `./gradlew build` green proves
nothing recompiles differently and no test stub widens); `lookup(...)` signature unchanged. **Security**
— the contract javadoc is explicit that an outage is *not* no-role and forbids fallback, closing the
mis-read that would re-open the hole at a consumer. **Pattern-reuse / module-layer** — exception +
contract live in core with the SPI; the javadoc flags the *separate failure axis* from the
ancestor-collapse exception so T3 keeps them distinct.

## Integration / e2e

N/A for T1 (no consumer behavior yet). Additivity proven by the **full `./gradlew build` green**
(all library modules + both example services + their ITs) on a pure-additive change.

## Decisions

None reopened. T1 implements ADR 0014 §1–§2 + §7 (NoOp note) exactly. No `lookup` signature change; the
tri-state is the unchecked-throw convention layered on the existing `Optional` return, documented only.

## Commit

`feat(core): add RoleResolutionException + tri-state RoleDefinitionSupplier contract` — see branch
`feature/void3110/supplier-outage`.
