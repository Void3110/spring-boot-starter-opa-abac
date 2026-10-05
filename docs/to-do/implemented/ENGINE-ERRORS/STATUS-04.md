---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/spring-security
  - area/spring-data
---

# STATUS — T4: The starter's fallback advice

**Status:** ✅ DONE (2026-10-05, collaborative)

## What shipped

- **`IndeterminateDecisionProblemAdvice`** (new, `autoconfigure` package) — a standalone `@RestControllerAdvice`
  (not an `AbstractProblemAdvice` subclass, the house style of the starter's other mappings),
  `@Order(Ordered.HIGHEST_PRECEDENCE)`, exactly the two family handlers: `AuthorizationIndeterminateException`
  and `DecisionIndeterminateException` → 503 `DEPENDENCY_UNAVAILABLE`, the same detail as the base advice, no
  `Retry-After`. The class doc carries the two ordering facts (first advice in order wins; the cause chain is
  matched within an advice) and why it declares nothing broader.
- **`OpaAbacAutoConfiguration.IndeterminateDecisionAdviceAutoConfiguration`** — servlet web apps only;
  `@ConditionalOnMissingBean({AbstractProblemAdvice.class, IndeterminateDecisionProblemAdvice.class})`, so it
  backs off when the application extends the base (no duplicate handlers) or supplies its own.

## Tests

- **I4** — `OpaAbacAutoConfigurationTest`: present in a servlet app without the base advice, mapping both types
  to 503 + code + detail; absent when an `AbstractProblemAdvice` bean exists (an anonymous subclass — proves the
  condition matches a subclass of the abstract type); absent without web; a user-supplied fallback wins.
- **I5** — new `IndeterminateDecisionProblemAdviceTest` (MockMvc standalone, Spring MVC's real exception
  resolution): with an application advice mapping every `AccessDeniedException` to a bare 403 **registered first**,
  the gate's type still answers 503 problem+json (no `Retry-After`); a raw engine failure answers 503, not 500; a
  plain denial still reaches the application's 403.
- **The ordering is load-bearing, measured:** with `@Order` removed, `gateIndeterminate_answers503_notTheAppsDenial`
  fails (the app's 403 claims the outage); restored, green. The cell pins the annotation, not just the mapping.
- `./gradlew build`: **green** (1m10s); starter 87 tests, 0 failures. The MCP example — the one example app
  without an `AbstractProblemAdvice` — now gets the fallback bean; its suite is unaffected (tool-gate denials are
  `CallToolResult`s, not MVC exceptions).

## Architecture review + refactor

Self-review: nothing substantive.

## Integration / e2e

None at T4. An adopter that does not extend the base advice now gets 503 from a list or a method gate on an
outage with zero configuration; the request-level gate still answers through the filter chain (403 by default).

## Decisions

- The missing-bean condition names **both** the base type and the fallback type: an application that extends the
  base, or that defines its own fallback, turns the starter's off — one condition, both escape hatches.

## Commit

`db3c221` — feat(starter): a fallback advice answers 503 for adopters without AbstractProblemAdvice
(ENGINE-ERRORS T4).
