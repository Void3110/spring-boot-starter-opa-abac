---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/spring-security
  - area/spring-data
---

# STATUS — T1: Core throws; resilience retries faults, never decisions; the MCP adapters catch the family

**Status:** ✅ DONE (2026-10-04, collaborative)

## What shipped

- **Core.** New `DecisionIndeterminateException` (abstract, protected constructors) and `PolicyEngineException`
  (`Kind` × 7, `httpStatus()`, one factory per kind); `RoleResolutionException` re-parented under the base.
  `HttpOpaClient` rewritten around three phases — *prepare* (a refusal: unsafe path, mixed-type batch,
  unserializable request, a non-family path-resolver throw → deny / all-false / `error()`, no HTTP call; a
  family member from the resolver propagates), *post* (every exchange failure → its kind: `HttpTimeoutException`
  → `TIMEOUT`, other `IOException` → `TRANSPORT`, interrupt → `INTERRUPTED` with the flag restored, a request
  the `HttpClient` rejects → `TRANSPORT`), *read* (non-200 → `HTTP_STATUS`; unparseable / not an object /
  `"result": null` / non-boolean / wrong-shaped bulk → `MALFORMED_RESPONSE`; no `result` key →
  `UNDEFINED_DECISION`; `{"result":{…}}` without `allow` → **deny**). One WARN per failed attempt, worded as
  indeterminate, carrying the kind. `readDecision` moved from a `Map` record to `JsonNode` to tell a missing
  `result` from an explicit `null`; the deny-reason type checks are unchanged in meaning.
- **`OpaClient` javadoc** — the per-method `@throws PolicyEngineException` contract.
- **Resilience.** `RetryableClassification` classifies a `PolicyEngineException` by kind only;
  `ResilientOpaClient` is now one `guarded(...)` helper — no result predicate on any method, breaker-open →
  `CIRCUIT_OPEN`, a backoff interrupt → `INTERRUPTED`. `CallNotPermittedException`'s javadoc follows.
- **MCP example.** `ToolCallAuthorizer` → `tool-gate-policy-unavailable` for a non-role family member;
  `ToolRosterFilter` catches the family on both sides (ceiling → unfiltered; batch → the empty roster + a WARN
  naming the outage). The agent-tool collection's two kill-drill call cells and the runner header follow.

## Tests

- Core: `HttpOpaClientTest`, `HttpOpaClientDecideTest`, `HttpOpaClientCompileTest`, `HttpOpaClientAllowAllTest`
  — every failure cell now asserts the thrown kind (on `allow` **and** `decide`); new cells for interrupt,
  `"result": null`, `{"result":{}}`-is-a-deny, a throwing path resolver (deny, no call) and an opting-in one
  (propagates), the compile blind spot pinned; new `PolicyEngineExceptionTest` (the family's shape). **U1–U11.**
- Resilience: `ResilientOpaClientTest` rewritten (identity per method against a real client, recovery,
  non-transient not retried, decisions called once, breaker-open `CIRCUIT_OPEN` without the delegate, genuine
  denies never open the breaker, a sustained `UNDEFINED_DECISION` does, the production sleeper's interrupt);
  `ResilientOpaClientDecideTest` (breaker-open throws; neither deny retried, the reason survives);
  `RetryableClassificationTest` (+ kind cells, incl. a `MALFORMED_RESPONSE` with an `IOException` cause not
  retried). **U12–U17, U34, U35.**
- MCP: `ToolCallGateTest` I10/I11 now pin the **code** (they passed vacuously on `isError` alone):
  failures → `tool-gate-policy-unavailable`, `{"result":{}}` → `tool-gate-denied`; `ToolRosterFilterTest` I18
  pins the empty roster **and** every call's new code during the outage. **U30, U31.**
- `./gradlew build`: **green — 1,290 tests, 0 failures, 63 Testcontainers IT classes**; collection conformance
  (18 clean) and shell guards (27 clean) green.

## Architecture review + refactor

Self-review against ADR 0037 and the validator's nits, one finding folded:

- **`PartialResult.error()` is not dead — the deprecation was wrong.** A compile request the client
  *refuses* to send (an unsafe path) must still deny everything **and suppress widening**; `denyAll()`
  (`fromError=false`) would let a Java-side subtree widening survive beside it. So `error()` / `fromError()`
  were re-documented ("no policy answer was obtained, widen nothing") instead of deprecated; ADR 0037 §4 +
  §Consequences 6, 00-DESIGN §1.1 / fork 7, the decomposition and U26 amended.
- Nothing else substantive; the `post` helper absorbs the four previously duplicated exchange blocks.

## Integration / e2e

No rig run at T1 (the live proof is T5's: E4 runs the amended kill-drill cells). Intermediate HTTP state,
true and untested by design: until T2's advice handler, a list over a failing OPA answers 500; the gates'
catch-all still turns the throw into 403.

## Decisions

- `PartialResult.error()` re-documented, not deprecated (above).
- A request the JDK `HttpClient` rejects outright (e.g. a misconfigured base URL) is `TRANSPORT` — "the engine
  could not be asked" — not a refusal: it is configuration, not caller input, and it should be loud.
  *(Review: louder still — `OpaClientConfig` now rejects a base URL that is not an absolute http(s) URL with a
  host, so the misconfiguration fails at startup; `TRANSPORT` stays the runtime classification. U37.)*
- An `{"result": …}` that is not an object (decide) is `MALFORMED_RESPONSE`; a JSON body that is not an object
  on any path is `MALFORMED_RESPONSE`, including on compile (the blind spot is only `{"result":{}}`).
  *(Review: as built, compile still turned a missing, null or non-object `result` into a silent `DENY_ALL` —
  the parser's own fallbacks. Now those shapes, and a non-array `queries`, are `MALFORMED_RESPONSE`.)*
- Local Sonar (P2) is deferred to T6 as planned — the repo's own stack is not running on this machine.

## Commit

`ff83088` — feat(core): an OPA engine failure throws PolicyEngineException; resilience retries faults, never
decisions (ENGINE-ERRORS T1). The maintainer confirmed the `error()` call at the checkpoint ("keep error()").
