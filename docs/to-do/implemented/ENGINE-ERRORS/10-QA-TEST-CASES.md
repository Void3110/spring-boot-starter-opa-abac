---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/opa
  - area/spring-security
  - area/spring-data
---

# ENGINE-ERRORS — QA test cases

> Concrete cases; each becomes a ticket's *Acceptance*. U = unit, I = integration
> (Testcontainers Postgres — never H2; in-process HttpServer OPA stub — no WireMock),
> E = e2e (asserts the actual cut, not just response shape), P = a proof gate.
> "Indeterminate" below means **a thrown `DecisionIndeterminateException`** (core) or
> **`AuthorizationIndeterminateException`** (the Spring gates); "deny" means today's returned
> `false` / `OpaDecision` deny / `AuthorizationDecision(false)`. Contract: [[0037-indeterminate-decision-distinct-from-deny|ADR 0037]].

## Unit (U*)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| U1 | `HttpOpaClient` `allow` + `decide` against a refused connection | `PolicyEngineException`, kind `TRANSPORT`, cause is the `IOException`; never `false` | T1 |
| U2 | `allow` + `decide` against a stub slower than the configured timeout | kind `TIMEOUT` (`HttpTimeoutException`, the connect variant included) | T1 |
| U3 | `allow` + `decide` against `500`, `503`, `400` | kind `HTTP_STATUS`, `httpStatus()` = the status; present **only** for this kind | T1 |
| U4 | `allow` + `decide` on an interrupted thread | kind `INTERRUPTED`; the interrupt flag is still set after the throw | T1 |
| U5 | `allow` + `decide`: unparseable body; `allow: "yes"` (non-boolean); `"result": null` | kind `MALFORMED_RESPONSE` | T1 |
| U6 | `allow` + `decide`: `{}` (no `result` key) vs `{"result":{}}` (no `allow`) | `{}` → kind `UNDEFINED_DECISION`; `{"result":{}}` → **deny, no throw** (undefined-means-deny) | T1 |
| U7 | `allow: true`; `allow: false`; `allow: false` + a well-formed `deny_reason` | permit; plain deny; reasoned deny — all byte-identical to 1.3.0 | T1 |
| U8 | the input defects: an unsafe policy path on all four methods; a mixed-type `allowAll` batch; a `PolicyPathResolver` throwing `IllegalStateException` | deny (`false` / deny / all-`false`) and **no throw** — a caller defect is never an engine error | T1 |
| U9 | `compile`: refused connection, `500`, interrupt, unparseable body; *(review)* a Compile answer of the wrong shape — `{}`, `"result": null`, a non-object `result`, a non-array `queries`, *(round 2)* a query that is not an array of expression objects (`[{}]`, `[[1]]`, `[["x"]]`); and `{"result":{}}` | each throws with the matching kind (the wrong shapes `MALFORMED_RESPONSE`, the WARN naming the parser's exception, never the body); `{"result":{}}` → `DENY_ALL` with `fromError() == false` (the documented blind spot, pinned) | T1 + review |
| U10 | `allowAll`: refused connection, `503`, `{}` (no `result` — also what a package without a `bulk` rule answers), `"result": null`, a `result` of the wrong length, a non-boolean element; an empty input | `TRANSPORT` / `HTTP_STATUS` / `UNDEFINED_DECISION` / `MALFORMED_RESPONSE` ×3; empty input → empty list, no HTTP call | T1 |
| U11 | the family's shape | `PolicyEngineException` and `RoleResolutionException` are `DecisionIndeterminateException`s and `RuntimeException`s; the base is abstract with protected constructors; an existing `catch (RoleResolutionException)` still catches | T1 |
| U12 | `RetryableClassification.isRetryableError` over every `Kind`, and a `MALFORMED_RESPONSE` whose cause chain holds an `IOException` | retryable: `TRANSPORT`, `TIMEOUT`, `HTTP_STATUS` 5xx and 429; not: `HTTP_STATUS` 4xx, `MALFORMED_RESPONSE` (**even with the `IOException` cause** — kind only), `UNDEFINED_DECISION`, `INTERRUPTED`; a bare `IOException` cause chain on a non-family throwable still retryable (the other edges) | T1 |
| U13 | `ResilientOpaClient`, all four methods, delegate returns a deny (`false`; reasonless deny; reasoned deny; all-`false`; mixed) | the delegate is called **exactly once**; the value is returned unchanged, the reasoned deny's reason included | T1 |
| U14 | delegate throws a transient kind once, then answers | retried within budget; the real answer is returned | T1 |
| U15 | delegate keeps throwing a transient kind; delegate throws a non-transient kind | exhausted → the last `PolicyEngineException` rethrown, same kind; non-transient → **not** retried, rethrown at once | T1 |
| U16 | breaker open, all four methods | `PolicyEngineException` kind `CIRCUIT_OPEN`; the delegate is not called | T1 |
| U17 | a sustained run of genuine denies past the breaker's failure threshold | the breaker stays `CLOSED` — a decision is never a breaker input | T1 |
| U18 | `OpaPreAuthorizeAuthorizationManager`: `decide` throws `PolicyEngineException`; the supplier throws `RoleResolutionException` | `AuthorizationIndeterminateException` with the original as cause; on the role outage OPA is **not** called | T2 |
| U19 | `OpaAuthorizationManager`: the same two | `AuthorizationIndeterminateException`, cause preserved | T2 |
| U20 | the type itself | an `AuthorizationIndeterminateException` is an `AuthorizationServiceException` and an `AccessDeniedException`, **not** an `AuthorizationDeniedException`; a `catch (AccessDeniedException)` catches it | T2 |
| U21 | non-family failures in the method gate: a resolver throw, a `null` decision, a SpEL error | `DENY`, unchanged — the catch-all is untouched | T2 |
| U22 | gate decisions that are answers: `allow: false`; a reasoned deny | `DENY`; `StepUpRequiredDecision` — unchanged | T2 |
| U23 | the rethrow invariant in the gate: a family member thrown by the ancestor-chain supplier; by the resolver during root-attribute enrichment; during the placement-parent walk | `AuthorizationIndeterminateException` every time — never collapsed to an empty chain or "unproven" | T2 |
| U24 | `ActionEnrichmentAdvice`: `allowAll` throws `PolicyEngineException`; `lookupAll` throws `RoleResolutionException`; `lookupAll` throws a custom family subtype | the `_actions` group is omitted, the body intact, nothing propagates — all three (the third proves the widened catch) | T2 |
| U25 | `AbacQueryService`, both `findAuthorized` overloads: `compile` throws; the kill-switch `allow` throws; the allowlist `allowAll` throws | the family propagates; no empty list/page is returned | T3 |
| U26 | `AbacQueryService` with a custom client returning `PartialResult.error()` | empty list/page, as in 1.3.0 (`error()` keeps its "no policy answer, widen nothing" meaning) | T3 |
| U27 | `HierarchicalAuthorizer.isAllowed`: role outage; `allow` throws `PolicyEngineException`; `allow` throws a plain `RuntimeException`; the ancestor resolver throws `AncestorResolutionException` | throws `RoleResolutionException`; throws `PolicyEngineException`; `false`; decides on the direct grant — the last two unchanged | T3 |
| U28 | `SubtreeSpecResolver`: role outage; any other `RuntimeException` | the outage propagates; anything else → `Optional.empty()` (unchanged) | T3 |
| U29 | the rethrow invariant in the list batch: a family member thrown by the ancestor resolver for one row | propagates; an `AncestorResolutionException` there still degrades that row to its direct grant | T3 |
| U30 | MCP `ToolCallAuthorizer`: `allow` throws `PolicyEngineException`; the supplier throws `RoleResolutionException`; against the real `HttpOpaClient` with the stub answering 503 | `denied` with `tool-gate-policy-unavailable`; `denied` with `tool-gate-ceiling-unavailable` (unchanged); the existing real-client cells assert the new code | T1 |
| U31 | MCP `ToolRosterFilter`: `allowAll` throws `PolicyEngineException` (via the real client: a 503, a timeout, a refused connection); a short decision list; a genuine all-`false` vector | `allowing(∅)` + a WARN naming the outage — the empty roster, unchanged; `allowing(∅)` (unchanged); `allowing(∅)` (unchanged) | T1 |
| U32 | the catalog list authorizers: `Category`, `Product` with the supplier throwing `RoleResolutionException`; `Catalog` with a membership-role outage while the subject supervises catalogs, and with an authoritative no-role in the same position | `Category` / `Product`: the outage propagates, no `Page.empty()`; `Catalog`: the outage propagates (no supervised-only page), the no-role case keeps the supervised-only degrade | T5 |
| U33 | `LtreeAncestorResolver` / `RecursiveCteAncestorResolver`: a source SPI throws a family subtype; a source SPI throws a plain `RuntimeException` | the family member propagates unwrapped; the plain throw is still wrapped into `AncestorResolutionException` | T3 |
| U34 | `ResilientOpaClient` with a delegate answering a fail-fast kind — `UNDEFINED_DECISION`, `EVALUATION_ERROR` *(round 2)*, `MALFORMED_RESPONSE`, a 4xx, `INTERRUPTED` — past the breaker's failure threshold | *(amended by the review)* every call answers that kind; the breaker stays **closed** — it counts only what is retried (ADR 0037 §4). As first built this cell pinned the opposite (the breaker opened) | T1 + review |
| U35 | the guard's backoff is interrupted between two attempts | `PolicyEngineException` kind `INTERRUPTED` (not `CIRCUIT_OPEN`); the interrupt flag is set | T1 |
| U36 | *(review)* one method's deterministic fault — `allowAll` answering `UNDEFINED_DECISION` past the threshold — then a healthy `decide` | the decide reaches the policy and returns its answer, not `CIRCUIT_OPEN`. Guard level: a throw the caller does not count never opens the breaker and releases a half-open probe slot; the three-argument form still counts every throw (the resolve/tag edges) | review |
| U38 | *(review, rounds 2–3)* a `500` whose body lists only OPA's policy-local codes (`eval_conflict_error` — the OPA 1.10.1 body — `eval_type_error`, `eval_with_merge_error`), on decide, compile and bulk; then the operational codes (`eval_cancel_error`, `eval_internal_error`, `eval_builtin_error`, both `eval_http_send_*`), a `500` with an empty, mixed or non-code errors list, an unparseable body, an `eval_` code with other characters; and the conflict body on a `503` | `EVALUATION_ERROR` for the policy-local codes on all three methods (no status), the WARN naming the code but never the message or the policy file's location; every other case stays `HTTP_STATUS` with its status. `EVALUATION_ERROR` is neither retried nor counted (in U34's list) | review |
| U39 | *(review, round 2)* the guard's half-open probe **returns** a retryable value (a 503), then a 200 | the probe's slot is released, so the 200 probes and closes the breaker — proven by removing the release (the cell fails) | review |
| U40 | *(review, round 3)* a retryable returned value with retry budget, its backoff interrupted, on a single-probe half-open breaker | the interruption is re-thrown and the probe slot is settled once — exactly one more probe is admitted, not two (proven by inserting a second release: the cell fails) | review |
| U37 | *(review)* `OpaClientConfig` with `opa:8181`, `localhost:8181`, a relative path, `ftp://…`, a host-less `http://`, a URI with a space | each rejected at construction with an `IllegalArgumentException` naming the OPA base URL; an absolute `http`/`https` URL is accepted, trailing slashes dropped. *(Round 2)* a credential-bearing value (`ftp://reader:…@opa`, an unparseable `http://reader:…@op a`, `reader:…@opa`) — neither the message nor any cause in the chain echoes the user or password | review |

## Integration (I*)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| I1 | method-security slice: an `@OpaPreAuthorize` method whose client throws, behind the real interceptor, with a `MethodAuthorizationDeniedHandler` that masks denials | the caller sees `AuthorizationIndeterminateException`; the masking handler is **not** invoked; a plain deny still goes through the handler | T2 |
| I2 | `AbstractProblemAdvice` — the handler and MVC's handler resolution, driven directly (the real MVC path is proven by I3 and I6): `AuthorizationIndeterminateException`; a raw `PolicyEngineException`; a plain `AccessDeniedException`; a step-up deny | 503 problem+json `code = DEPENDENCY_UNAVAILABLE`, detail "Authorization is temporarily unavailable", no `Retry-After`; 503 the same; 403 `ACCESS_DENIED`; 401 + challenge — the last two unchanged | T2 |
| I3 | `SupplierOutageGateIT` (catalog, Testcontainers): role source down on a protected GET | **503 `DEPENDENCY_UNAVAILABLE`** where 1.3.0 answered 403; OPA never asked | T2 |
| I4 | starter auto-configuration: no `AbstractProblemAdvice` bean; an `AbstractProblemAdvice` subclass bean; a non-servlet app | `IndeterminateDecisionProblemAdvice` registered; not registered; not registered | T4 |
| I5 | the standalone advice in an app whose own advice handles `AccessDeniedException` → 403 | an indeterminate still answers 503 (the fallback is ordered ahead); a plain deny still answers the app's 403 | T4 |
| I7 | *(review round 2, outside the slice)* `HierarchyListFilterIT` I9: an `ALLOW_ALL` residual with a subtree widening on C, listed in catalog D's scope; and in C's own scope | D's rows (`[]` before the fix — `ALLOW_ALL OR subtree` had collapsed to the subtree); in C, every row but the denied one | review |
| I6 | `GET /catalogs` (Testcontainers) with the scope resolvers answering and the role supplier throwing for a subject who is a member and a supervisor — *(review round 2)* throwing for the **membership anchor only**, the case 1.3.0 answered with a supervised-only 200; a contrast cell shows the supervised leg runs | 503 `DEPENDENCY_UNAVAILABLE` — not the supervised-only page, not an empty 200 (the category and product lists already 503 at the gate from T2) | T5 |

## E2E (E*)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| E1 | resilience matrix, transient resolve outage (1 × 503, then the role) on the id'd category GET | 200 — the guard rides out the blip (unchanged) | T5 |
| E2 | resilience matrix, sustained resolve outage on the same GET, through the gateway | **503** + `code == DEPENDENCY_UNAVAILABLE` (was 403); the body is the problem document, not the resource | T5 |
| E3 | the main e2e suite (`scripts/postman/run-tests.sh`) on the rebuilt rig | green — no other cell pinned an outage as a 403 | T5 |
| E4 | agent-tool matrix (`run-agent-tool-matrix.sh`), the policy-engine kill drill: OPA stopped mid-suite, `/mcp` behind the gateway | every tool call answers `tool-gate-policy-unavailable` (was `tool-gate-denied`); the roster is empty for agent and human (unchanged); the restore folder returns the pre-kill cut exactly | T5 |

## Proof gates (P*)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| P1 | `./gradlew mutationTest` on the touched library modules | **zero SURVIVED** mutants in the new classification code: the `Kind` mapping in `HttpOpaClient`, the retry predicate, the gate catches, every rethrow guard | T6 |
| P2 | `./.sonar-local/sonar-local.sh` on the changed files | `CLEAN` (by-design false positives per the `quality-gate-sonar` Mulch domain) | T6 |
| P3 | `./gradlew build` | green, all modules + integration tests | T6 |

## Headline proof

**E2 + E4** — both outage classes, live through the real gateway: under a sustained role-source outage
1.3.0 answered "403, you may not" and 1.4.0 answers "503, authorization is temporarily unavailable"
(beside **E1**, which proves resilience still rides out a blip); with the policy engine stopped, an agent
is told `tool-gate-policy-unavailable` instead of `tool-gate-denied`. And **U13 + U17**: a genuine deny now
costs exactly one OPA call and can never open the breaker.
