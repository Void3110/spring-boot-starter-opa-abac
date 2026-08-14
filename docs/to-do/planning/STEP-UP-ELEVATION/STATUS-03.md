---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/spring
---

# STATUS — T3: library envelope: `decide()`, the typed reason, and the resilient override

**Status:** ✅ DONE

## What shipped

**`opa-abac-core`** (still Spring-free — the two records are pure Java + Jackson annotations):

- **`DenyReason(String type, String requiredAcr, Integer maxAge)`** — Jackson-mapped from the wire
  names (`type`, `required_acr`, `max_age`), unknown fields ignored. Carries the constant
  `INSUFFICIENT_USER_AUTHENTICATION` and one behaviour, `isComplete()` — the guard T4's advice
  consults so a challenge is never emitted without its window (ADR 0030 §7's loop).
- **`OpaDecision(boolean allow, DenyReason denyReason)`** — `denyReason == null` is a plain decision.
  Factories `permit()` / `deny()` / `of(boolean)` and `hasCompleteReason()`. **`deny()` is named so
  the fail-closed sites read as a deliberate choice** rather than an incidental `new OpaDecision(false,
  null)`.
- **`OpaClient.decide(AbacContext)`** — a **`default`** method returning `OpaDecision.of(allow(ctx))`.
  Its javadoc carries the decorator warning that T3's own seam check turned up (below).
- **`HttpOpaClient.decide`** — reads `result.allow` **and** `result.deny_reason` from the *same*
  response (a test asserts exactly one HTTP call). Four wire shapes, four answers: a complete reason
  is typed through; an absent one yields `null`; an allow yields `null`; and an allow *carrying* a
  reason — a contradictory document — yields the allow with the reason **dropped**.

**`opa-abac-spring-security`**:

- **`ResilientOpaClient.decide` — overridden, and the override is the deliverable.** Guards the
  delegate's `decide()` with the same `CallGuard` discipline and the same retry-on-fail-closed-sentinel
  semantics `allow()` uses. Breaker-open synthesizes `OpaDecision.deny()` by hand; transport failures
  and exhausted retries need no special case because the delegate has already swallowed them into its
  own `(false, null)`. **No fail-closed path can produce a reason**, so a reason only ever reaches a
  caller when OPA actually answered 200 with one.

**Doc delta** — `docs/guides/ABAC-AUTHORIZATION.md` gains *The decision envelope — an optional,
structured `deny_reason`*: the three wire shapes, the `decide()` call form, the four fail-closed rules
as a table, the decorator-must-override rule, and why `compile`/`allowAll` stay boolean.

## Tests

`./gradlew build` green (all modules, Testcontainers ITs included). Local Sonar **CLEAN** on changed
files. Three new test classes, 15 cases:

| Case | Class | What it pins |
|---|---|---|
| **U12** | `OpaClientDecideDefaultTest` | a `LegacyOpaClient` implementing **only** the three pre-ADR-0030 methods compiles, and its inherited `decide` returns `(allow(), null)` — calling `allow` exactly once |
| **U13** | `HttpOpaClientDecideTest` | all four wire shapes — complete reason / no reason / allow / **contradictory** allow+reason — with the `allow()` half asserted alongside every one of them (the additivity proof at the call level) |
| **U14** | `HttpOpaClientDecideTest` | **eight** malformed shapes each drop the reason and keep the deny: `max_age` as a string, `required_acr` as a number, either field missing, an empty object, a bare string, an array, and a **float** window; plus non-JSON, a 503, a missing decision field and a dead endpoint → `OpaDecision.deny()`, no throw |
| — | `HttpOpaClientDecideTest` | unknown fields *inside* a well-formed reason are tolerated; `decide` costs exactly **one** round-trip |
| **U15** | `ResilientOpaClientDecideTest` | the happy path passes the delegate's reason through; **the override exists** — a counting guard plus a recording delegate assert the call reached the *delegate's* `decide` and never the inherited default's `allow` path; breaker-open, transport failure, exhausted retry and a `null` result each → `deny()` with a **null** reason |

**The additivity proof:** `git diff --stat` over `src/test` shows **zero** changes to any pre-existing
library test file — the only test-side diffs are the three new classes and the two renames below.

## Architecture review + refactor

Path: **inline self-review** (the ★ gate).

- **Fail-closed.** Four failure classes, each landing where it arises and each tested: a malformed
  reason drops at the parse; an outage/breaker denies at the wrapper **without a reason**; a missing
  decision field denies as before; a partial reason is left for T4's advice to reject
  (`isComplete()` exists for it). Nothing on any path fabricates a reason — asserted, not asserted-ish:
  the resilient test's delegate *has* a reason to hand back, and the breaker-open case still returns
  `deny()`.
- **The widenings that would matter, and why they cannot happen.** (a) *A fabricated reason during an
  OPA outage sending users into a TOTP treadmill* — the only place a reason can be constructed is the
  wire parse; every synthesized value in the library is `OpaDecision.deny()`. (b) *`allow()` behaviour
  drifting* — it now delegates to the shared evaluator, and every pre-existing test passes unmodified,
  plus each U13 case asserts the `allow()` half on the same wire shape. (c) *A coerced window* — the
  reason's fields are checked by hand, and a string/float `max_age` is dropped (Jackson's
  `convertValue` would have coerced `"300"` into a window the library then advertises).
- **Refactor applied — one, and it is a fail-closed fix rather than a tidy-up.** The obvious shape for
  `HttpOpaClient` was `allow()` delegating to `decide()`. That would have created a **mutual
  delegation cycle** with the interface's `decide` default (`decide → allow → decide`): harmless while
  the override exists, and an infinite recursion the moment someone removed it — and a
  `StackOverflowError` is an `Error`, so it escapes this class's `catch (Exception)` fail-closed
  handlers and propagates uncaught instead of denying. This is precisely the trap `isSafePath` already
  documents for the regex it refuses to use. Both public methods now call a private `evaluate(...)`,
  which cannot form a cycle, and the reasoning is in the javadoc where the next reader will meet it.
- **Wiring.** Every new seam has a named consumer and a non-happy-path test: `decide()` → T4's manager
  (and, today, U12–U15); `DenyReason.isComplete()` → T4's advice (its own U18 there) and asserted here;
  `OpaDecision.deny()` → all four fail-closed sites; the resilient override → the counting-guard test.
- **Boundary / additivity.** `opa-abac-core` gained no dependency (the records use the
  `com.fasterxml.jackson.annotation` annotations already on the module's compile path). `allow`,
  `allowAll`, `compile` and `PartialResult` are unchanged in signature and behaviour;
  `opa-abac-spring-data`, `HierarchicalAuthorizer`, both managers, the SPA and the policy corpus are
  untouched. `opa test infra/opa/policies/` still 367/367.
- **Static analysis.** Two genuine findings on the first run, neither a documented FP class
  (`ml prime quality-gate-sonar` checked): **S1845** — the constant `ALLOW` clashing with the record's
  `allow()` accessor (renamed to `PERMIT`), and **S5411** — a boxed `Boolean` used directly in an `if`
  (now `Boolean.TRUE.equals(...)`). Both fixed in this ticket's commit; the gate is **CLEAN**.

## Integration / e2e

None owned by this ticket (T4 owns I1–I4). The existing example ITs run as part of `./gradlew build`
and pass — including the two whose stubs are described below.

## Decisions

- **Seam deviation, found by the compiler and worth recording: a new interface method can break an
  existing implementor at *compile* time, and it did.** Two pre-existing example ITs
  (`SupervisedListIT`, `ProductionTierEnrichmentIT`) have stub `OpaClient` implementations carrying a
  **`private static boolean decide(AbacContext)`** helper. Java forbids a static method with the same
  signature as an inherited instance method, so adding `decide` to the interface made both test classes
  fail to compile. The fix is a 3-line rename per file (`decide` → `verdictFor`) — the helpers are
  test-local and nothing else refers to them. Two honest consequences: (1) the additivity claim is
  precisely *"no behaviour changes and no library test was modified"*, **not** *"no file needed
  touching"*; (2) an adopter with a static `decide(AbacContext)` on their own `OpaClient` will hit the
  same compile error on upgrade — loud and immediate, which is the acceptable failure mode for adding a
  default method, but it belongs in release notes rather than being discovered by a user.
- **The mutual-delegation cycle is avoided by construction, not by convention** — see the review note.
  It is the one place where the "obvious" implementation is a latent fail-open.
- **The reason's fields are validated by hand rather than by `objectMapper.convertValue`.** Jackson
  coerces `"300"` to `300`; the library must not advertise a window it inferred. The record still
  carries the `@JsonProperty` wire names the decomposition specifies, so it round-trips for any consumer
  that wants Jackson to do the work — but the client's own parse is strict.
- **An `allow: true` carrying a reason drops the reason** rather than propagating a mixed signal (U13's
  fourth shape). An allow needs no explanation, and a caller that saw both would have to invent a
  precedence rule of its own.

## Commit

`feat(step-up-elevation): the additive decide() envelope, the typed DenyReason, and the resilient
override (T3)` — the two core records, the `decide` default + `HttpOpaClient` parse, the mandatory
`ResilientOpaClient` override, three new test classes, the ABAC-AUTHORIZATION envelope section, and the
two stub-helper renames, on `feature/void3110/step-up-elevation`.
