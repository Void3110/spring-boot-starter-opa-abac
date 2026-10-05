---
tags:
  - status/planned
  - type/architecture
  - area/abac
  - area/opa
  - area/spring-security
  - area/spring-data
---

# ENGINE-ERRORS — design

> The contract is [[0037-indeterminate-decision-distinct-from-deny|ADR 0037]]; this note is the
> implementation-facing half: every class that changes, in the order the failure travels, plus the forks
> as settled on 2026-10-04 (fourteen in the planning interview, three more after adversarial validation)
> and the gotchas the build must not rediscover.

## 1. The mechanism

A failure now travels as a **thrown member of one family** from the OPA edge to the HTTP response, and
every layer either propagates it or — where the authorization answer only decorates a response —
deliberately degrades and logs.

```
HttpOpaClient ──throws PolicyEngineException(Kind)──► ResilientOpaClient (retries transient kinds; breaker-open → CIRCUIT_OPEN)
RoleDefinitionSupplier ──throws RoleResolutionException──┐        │
                                                          ▼        ▼
                    gates: OpaPreAuthorizeAuthorizationManager / OpaAuthorizationManager
                           catch DecisionIndeterminateException → throw AuthorizationIndeterminateException
                    data:  AbacQueryService / HierarchicalAuthorizer / SubtreeSpecResolver → propagate
                    decor: ActionEnrichmentAdvice / CatalogProvenanceAdvice → omit + log (unchanged)
                    mcp:   tool call → denied(tool-gate-policy-unavailable); roster → empty (unchanged outcome)
                                                          │
                                                          ▼
          AbstractProblemAdvice  ─or─  IndeterminateDecisionProblemAdvice (starter, only without the base)
                                → 503 DEPENDENCY_UNAVAILABLE problem+json
```

### 1.1 `opa-abac-core` (package `dev.dmitriikonovalov.opaabac.core`)

| Class | Change |
|---|---|
| `DecisionIndeterminateException` | **new** — `public abstract`, `extends RuntimeException`, protected `(String)` / `(String, Throwable)` constructors |
| `PolicyEngineException` | **new** — `extends DecisionIndeterminateException`; nested `enum Kind { TRANSPORT, TIMEOUT, HTTP_STATUS, INTERRUPTED, MALFORMED_RESPONSE, UNDEFINED_DECISION, CIRCUIT_OPEN }`; `kind()`; `httpStatus()` (an `OptionalInt`, present only for `HTTP_STATUS`); one factory per kind so call sites stay one line |
| `RoleResolutionException` | re-parented to `extends DecisionIndeterminateException`; javadoc names the family |
| `HttpOpaClient` | `evaluate` / `readDecision` / `compile` / `allowAll` / `readBulkDecisions` throw per ADR 0037 §3 (`HttpTimeoutException`, incl. the connect variant → `TIMEOUT`; other `IOException` → `TRANSPORT`; a missing `result` key → `UNDEFINED_DECISION`, an explicit `null` → `MALFORMED_RESPONSE`); the unsafe path, the mixed-type batch, request serialization and a non-family `PolicyPathResolver` throw stay **deny** and are moved out of the throwing region so the catch cannot turn them into an engine error; WARN per attempt per §9 |
| `OpaClient` | javadoc contract per method: "throws `PolicyEngineException` when no decision could be obtained; never returns a fabricated deny for a failure"; the `decide` default is unchanged |
| `PartialResult` | `error()` / `fromError()` **re-documented, not deprecated** (T1 finding): a failed call throws now, but the client's refusal to send a compile request still answers `error()` — a plain `denyAll()` there would let a subtree widening survive beside it |

`CompileResponseParser` is **unchanged**: `{}` and no-queries stay `DENY_ALL` (blind spot, ADR §3a).

### 1.2 `opa-abac-spring-security`

| Class | Change |
|---|---|
| `AuthorizationIndeterminateException` | **new** — `extends org.springframework.security.access.AuthorizationServiceException`; constructor takes the `DecisionIndeterminateException` cause |
| `resilience/RetryableClassification` | `isRetryableError`: a `PolicyEngineException` is classified **by kind only** (transient: `TRANSPORT`, `TIMEOUT`, `HTTP_STATUS` whose status `retryableStatus` accepts); everything else keeps the `IOException` cause-chain rule |
| `resilience/ResilientOpaClient` | all four methods: `guard.call(body, retryableError, result -> false)` — no result predicate; `CallNotPermittedException` → `PolicyEngineException` `CIRCUIT_OPEN`, or `INTERRUPTED` when its cause is an `InterruptedException` (the guard's backoff interrupt); class javadoc rewritten (the "why retry on the sentinel" section goes) |
| `OpaPreAuthorizeAuthorizationManager` | the `RoleResolutionException` catch widens to `DecisionIndeterminateException` → throw; the ancestor-walk catch, `parentGovernedByRoleTarget` and `resolveAttributesOf` rethrow the family before their `catch (RuntimeException)`; the gate's own log drops to DEBUG |
| `OpaAuthorizationManager` | same catch change |
| `web/ActionEnrichmentAdvice` | behaviour unchanged (omit the group); the `lookupAll` catch at the role batch widens from `RoleResolutionException` to the family (a custom SPI subtype must not escape `beforeBodyWrite`); comments and logs name the family |
| `AbstractProblemAdvice` | `@ExceptionHandler({AuthorizationIndeterminateException.class, DecisionIndeterminateException.class})` → 503 `DEPENDENCY_UNAVAILABLE`, detail "Authorization is temporarily unavailable" |
| `resilience/Resilience4jCallGuard`, `MemoizingRoleDefinitionSupplier` | **no change** — the guard keeps recording every thrown fault (ADR §4's accepted consequence); the memo replays `RoleResolutionException`, now in the family |

### 1.3 `opa-abac-spring-data`

| Class | Change |
|---|---|
| `filter/AbacQueryService` | both `findAuthorized` overloads let the family propagate from the kill-switch `allow`, `compile` and the allowlist batch; the per-row ancestor catch stays `AncestorResolutionException`-only; the `fromError` branches stay for a custom client; the class javadoc's "Never fail-open" bullet rewritten |
| `hierarchy/HierarchicalAuthorizer` | rethrow the family from the role lookup and around `opaClient.allow`; `@return` / "never throws" javadoc rewritten |
| `hierarchy/SubtreeSpecResolver` | `catch (DecisionIndeterminateException e) { throw e; }` ahead of `catch (RuntimeException _)` |
| `hierarchy/LtreeAncestorResolver`, `hierarchy/RecursiveCteAncestorResolver` | the same one-line rethrow ahead of each wrap of a source-SPI throw into `AncestorResolutionException` (four sites); their own SQL classification is unchanged |

### 1.4 `opa-abac-spring-boot-starter`

| Class | Change |
|---|---|
| `IndeterminateDecisionProblemAdvice` | **new**, standalone `@RestControllerAdvice` (not a subclass), `@Order(Ordered.HIGHEST_PRECEDENCE)`, exactly the two handlers |
| `OpaAbacAutoConfiguration` | registers it for a servlet web app, `@ConditionalOnMissingBean(AbstractProblemAdvice.class)` (matches a concrete subclass bean; the MCP example has none, so it gets the fallback) |

### 1.5 Examples and e2e

- **MCP (lands with the core, T1 — its tests run a real `HttpOpaClient`):** `ToolCallAuthorizer` catches
  the family — `RoleResolutionException` keeps `CODE_CEILING_UNAVAILABLE`, any other member answers the new
  `CODE_POLICY_UNAVAILABLE` (`tool-gate-policy-unavailable`); `ToolRosterFilter` moves `allowAll` inside its
  try and maps the family to `RosterDecision.allowing(∅)` plus a WARN naming the outage (the empty roster,
  unchanged), and its other catches name the family; `RosterFilterInstaller`'s `catch (RuntimeException)`
  → `unfiltered()` is then never reached by an engine outage. The agent-tool collection's kill-drill call
  cells assert the new code; its roster cells and the restore folder are unchanged.
- **Catalog:** `CategoryListAuthorizer`, `ProductListAuthorizer` stop catching the outage into an empty
  page; `CatalogListAuthorizer.resolveRole` stops catching it — a membership outage propagates instead of
  degrading to the supervised-only page, while an authoritative no-role keeps that degrade;
  `CatalogProvenanceAdvice` keeps omitting `_provenance` (decoration), its catches name the family.
  `HttpGovernedScopeResolver` and `SupervisedScopeClient` are **unchanged** (the root-list blind spot).
- **e2e:** `resilience-matrix.postman_collection.json` E2 asserts 503 + `DEPENDENCY_UNAVAILABLE`, the
  runner's header narrative follows; E1 is unchanged.

## 2. Decided forks

### 2.1 The planning interview (2026-10-04)

| # | Fork | Settled | ADR 0037 |
|---|---|---|---|
| 1 | How core signals "could not decide" | **throw** (not a third value) | §1 |
| 2 | Core exception shape | **one abstract family**; `RoleResolutionException` re-parented | §2 |
| 3 | What the gates hand to Spring Security | throw an **`AuthorizationServiceException` subtype** (not a decision subclass, not an `AuthorizationDeniedException` subtype) | §5 |
| 4 | `{}` / result-without-`allow` / non-boolean | **split**: `{}` and non-boolean indeterminate; result-without-`allow` deny | §3 |
| 5 | What else in the gate is indeterminate | **narrow and typed**: only the family; SPIs opt in by subclassing | §6 |
| 6 | What the engine exception carries | a **`Kind`** enum; fault-only retry; breaker-open throws `CIRCUIT_OPEN` | §4 |
| 7 | List path | **propagate**, no property, one mode; `PartialResult.error()` narrowed to "refused to ask" (deprecation dropped at T1) | §4, §7 |
| 8 | Degraded-input paths | **keep** the degradations; **no decision-path catch swallows the family** | §7 |
| 9 | Decorating/hinting consumers | enrichment and `_provenance` omit; MCP tool call `tool-gate-policy-unavailable`; MCP roster — see 2.2 | §7 |
| 10 | HTTP rendering | base advice **+** standalone fallback; reuse 503 `DEPENDENCY_UNAVAILABLE`; no `Retry-After`; request-level gate 403 by default | §8 |
| 11 | Where it is recorded | **operational log** (WARN per attempt, with the kind); no audit event | §9 |
| 12 | Version | **1.4.0** + a new `CHANGELOG.md` with upgrade notes | §10 |
| 13 | Proof | tests per kind/layer + both live outage classes (resilience E2, agent-tool kill drill); zero SURVIVED PIT in the new code; Sonar clean | Proof obligations |
| 14 | Build shape | **collaborative** mini package, one adversarial validator, one multi-lens `/deep-review` | — |

### 2.2 After adversarial validation (same day)

| # | Fork | Settled | ADR 0037 |
|---|---|---|---|
| 15 | MCP roster on a dead policy engine | **keep the empty roster** — it is a documented, e2e-pinned decision whose reason (every call is denied too) survives the new distinction; fork 9's first answer (`unfiltered()`) rested on the false premise that it was accidental | §7 |
| 16 | Catalog list: membership-role outage while the subject supervises catalogs | **propagate** (503); the authoritative no-role case keeps the supervised-only degrade | §7 |
| 17 | Root list under a scope-resolver outage | **document as a blind spot + backlog**; the base-scope SPI contract (fail to empty, never throw) is not amended in this slice | §3a |

Also folded from validation, without a fork: the breaker records every thrown fault (the shared guard is
unchanged — ADR §4's accepted consequence); retry classifies a `PolicyEngineException` by kind only; the
guard's backoff interrupt maps to `INTERRUPTED`; the bulk `{}` also means "no `bulk` rule"; the built-in
ancestor resolvers rethrow the family from their source-SPI wraps.

## 3. Fail-closed posture

**Invariant: no error path widens the result, and no error path is reported as a policy answer.** The
first half is today's guarantee and is unchanged — a throw is never an allow, and every non-family
failure still lands exactly where it did. The second half is new, with the two blind spots of ADR §3a:

| Failure | Lands |
|---|---|
| OPA transport / timeout / non-200 / interrupt / malformed / `{}` | `PolicyEngineException` → gate: `AuthorizationIndeterminateException` → 503; list: propagates → 503 |
| breaker open | `PolicyEngineException(CIRCUIT_OPEN)` → as above |
| role source down | `RoleResolutionException` → as above (never the realm fallback — ADR 0014 unchanged) |
| policy `allow: false`, reasoned deny, result-without-`allow` | deny → 403 (step-up 401 unchanged) |
| unsafe path, mixed batch, unserializable request, non-family path-resolver throw | deny → 403 |
| SpEL error, resolver throw, `null` decision, wiring | deny → 403 (the catch-all, unchanged) |
| ancestor walk / attribute enrichment failure | designed degradation, unchanged — unless an SPI threw a family member, which passes through |
| enrichment's or `_provenance`'s lookup fails | the field is omitted, body intact |
| MCP: engine down | every call `tool-gate-policy-unavailable`; roster empty |
| root list: scope resolver's service down | empty 200 (blind spot, §3a) |
| compile on a missing package | deny-all (blind spot, §3a) |

## 4. Gotchas the build must not rediscover

- **Spring MVC advice ordering.** The first advice *in order* that can handle an exception wins, not the
  most specific handler across advices — and within one advice the cause chain is walked before the next
  advice is asked. Hence the starter's fallback is high-precedence, conditional on the absence of an
  `AbstractProblemAdvice` bean, and declares exactly the two family types.
- **The method interceptor only routes `AuthorizationDeniedException`.** Verified on spring-security-core
  7.0.7: any other exception from the manager propagates straight out (no denied-handler, no event).
  A test must pin that a masking `MethodAuthorizationDeniedHandler` does **not** see an indeterminate.
- **Retry tests invert.** "Never assert an attempt COUNT on a denied decision" (Mulch) was true because
  a deny used to be retried. After T1 a deny is called **exactly once** — assert it, it is the point.
- **The breaker counts deterministic faults too.** `Resilience4jCallGuard` records every thrown failure
  before classifying it; do not "fix" that here (the resolve and tag edges rely on it). A test pins that a
  sustained undefined decision opens the breaker.
- **Where the rig's engine outage is reachable.** For the catalog routes APISIX's `opa` plugin calls the
  same `http://opa:8181` first, so stopping OPA decides at the gateway. The `/mcp` route carries no `opa`
  plugin, and `run-agent-tool-matrix.sh` already stops the OPA container for its kill drill — that is the
  live engine-outage proof. The role-source outage is proven through the resilience stub.
- **Degrade-catches are the silent trap.** Every `catch (RuntimeException)` on a decision path that
  degrades (the gate's ancestor walk and both enrichments, `SubtreeSpecResolver`, `HierarchicalAuthorizer`'s
  OPA call, the two ancestor resolvers' source wraps) also catches the family. Each gets an explicit rethrow
  **and** a test that throws a family member through it — a missed one compiles, passes every existing
  test, and silently eats an SPI's opt-in.
- **The interrupt flag** is restored *before* throwing `INTERRUPTED`, as it is restored before denying today.

## 5. Considered & rejected

The options, with reasons, are in [[0037-indeterminate-decision-distinct-from-deny|ADR 0037]]
§Considered options. More that came up while grounding the design in the code:

| Option | Why rejected |
|---|---|
| A starter-shipped `AccessDeniedHandler` bean for the request-level gate | No example wires that gate, and the app owns its filter chain; the guide shows the three-line type check instead. Revisit on demand. |
| Re-classify the built-in ancestor resolvers' own SQL failures as indeterminate now | Rare in practice (the leaf resolve fails first when the database is down, and stays a deny per fork 5); it would make one request inconsistent with the app's own resolver. Backlog. |
| Make the ownership-discovery outage (`DiscoveryOwnershipResolver`) indeterminate too | Out of scope: it is a control-plane squat check, not a decision surface, and its javadoc deliberately never throws ("a throw a caller might catch-and-allow would re-open squatting"). It stays `false` on an outage. |
| Make the user-service's direct "is this role assignable" policy call (`RoleAssignableClient`) indeterminate | Out of scope: it calls the engine through its own HTTP client, not `OpaClient`, so this mechanism does not reach it; an outage there answers "not assignable" (422) — the same lie class. Backlog. |
| Change `Resilience4jCallGuard` to record only transient faults | The resolve and tag edges rely on record-everything; the accepted consequence is ADR §4's. |
