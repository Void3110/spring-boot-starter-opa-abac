---
tags:
  - status/active
  - type/decision
  - area/abac
  - area/opa
  - area/spring-security
  - area/spring-data
---

# ADR 0037 — "Could not decide" is indeterminate, distinct from a deny, at every decision surface

**Status:** Accepted (planned — slice **ENGINE-ERRORS**, [[ENGINE-ERRORS]]; target release 1.4.0)
**Date:** 2026-10-04
**Context tags:** fail-closed, indeterminate vs deny, OPA engine error, role-source outage, `OpaClient` contract, resilience retry, 503 vs 403

> [[0014-supplier-outage-error-distinct|ADR 0014]] made a role-source **outage** error-distinct from an
> authoritative **no-role** at the `RoleDefinitionSupplier` SPI — and then every consumer collapsed it
> back into a deny. The OPA client never made the distinction at all: a timeout, a 5xx and a policy's
> `allow: false` all leave `HttpOpaClient` as the same `false`. This ADR extends ADR 0014's distinction
> from the SPI to the **decision surface**: a caller can tell *"the policy said no"* from *"we could not
> ask the policy"*. XACML names the second state **Indeterminate**. Settled in a planning interview
> (2026-10-04; fourteen forks, three more after adversarial validation; the slice index [[ENGINE-ERRORS]] links the design).

## Context

A service has to answer the two states differently. *The policy said no* is a 403: final, and
retrying it changes nothing. *We could not ask the policy* is a 503: nothing happened, try again
later. A durable workflow engine must **retry** the second and must never record it as a final
refusal. Today the starter makes the two indistinguishable at every entry point:

| Where (verified 2026-10-04 on `24bee89`) | On an engine failure / role-source outage today |
|---|---|
| `HttpOpaClient.allow` / `decide` | `false` / `OpaDecision.deny()` — non-200, interrupt, transport/timeout, missing or non-boolean `allow`, malformed body |
| `HttpOpaClient.compile` | `PartialResult.error()` — the list becomes an **empty page, 200** |
| `HttpOpaClient.allowAll` | all-`false` |
| `ResilientOpaClient` | breaker open → synthesizes the same deny values; **retries on the deny value itself**, so every genuine reasonless deny and every all-false bulk page pays an extra OPA hop plus backoff, and the breaker cannot count those failures (its own comment: the sentinel "is indistinguishable from a genuine policy DENY") |
| `OpaPreAuthorizeAuthorizationManager`, `OpaAuthorizationManager` | `RoleResolutionException` → `DENY` (403); any other throw → the catch-all `DENY` |
| `HierarchicalAuthorizer` | role outage → `false`; any OPA throw → `false` |
| `SubtreeSpecResolver` | role outage → "no widening" — a silently **partial** list |
| `AbacQueryService` | failed compile, failed kill-switch `allow`, failed allowlist batch → empty list/page |
| the operational log | `"OPA denied (fail-closed): …"` at WARN — the word for an outage is *denied* |

Every row is **fail-closed** — nothing ever widened — and that property stays. What is wrong is that
each row also *lies*: an outage reaches the user as "you have no access" or "there is nothing here",
and reaches the operator as a stream of 403s and 200s.

## Decision

### 1. `opa-abac-core` signals "could not decide" by **throwing**

`HttpOpaClient` throws from all four methods when it could not obtain a decision. It no longer returns
a fabricated deny for a failure. `OpaDecision` and `PartialResult` keep their shapes. A thrown failure
is never an allow, so fail-closed holds by construction; the difference from a returned deny is that a
caller who forgets to handle it fails **loudly**, where a forgotten third-state flag check would have
silently collapsed back to today's deny. This is the same choice ADR 0014 made for the role supplier
(an outage throws), so the throw/value pair now carries the meaning end to end.

### 2. One family, Spring-free, extensible

```
RuntimeException
└── DecisionIndeterminateException        (abstract, public, protected ctors — core)
    ├── PolicyEngineException             (new — core; carries a Kind, §4)
    └── RoleResolutionException           (ADR 0014 — re-parented, unchanged otherwise)
```

Re-parenting `RoleResolutionException` is source- and binary-compatible: it is still a
`RuntimeException`, and every existing `catch (RoleResolutionException)` keeps compiling and catching.
A consumer catches **one** type for "could not decide" and a subtype only when it cares why. The base is
abstract with protected constructors **so an SPI implementation can opt its own outage in** — a resource
resolver, an ancestor-chain supplier, a custom `OpaClient` — by throwing a subtype; the library never
guesses (§6). `opa-abac-core` stays free of Spring.

### 3. What `HttpOpaClient` classifies as indeterminate — and what stays a deny

| Response / failure | Classification | `Kind` |
|---|---|---|
| connection refused, reset, other transport `IOException` | indeterminate | `TRANSPORT` |
| request or connect timeout | indeterminate | `TIMEOUT` |
| any non-200 status | indeterminate | `HTTP_STATUS` (status carried) |
| thread interrupted (the flag is restored first) | indeterminate | `INTERRUPTED` |
| unparseable body; a non-boolean `allow`; an explicit `"result": null`; a bulk `result` that is not a boolean list of the input's length | indeterminate | `MALFORMED_RESPONSE` |
| **`{}` — no `result` key** (decide: the package/path is not loaded — a bundle not yet activated, a wrong path; bulk: the same, **or a loaded package that defines no `bulk` rule**, which is optional per type) | indeterminate | `UNDEFINED_DECISION` |
| **`{"result": {…}}` with no `allow`** (the package is loaded; `allow` is undefined for this input) | **deny** — undefined-means-deny, OPA's idiom for a policy without `default allow := false` | — |
| `allow: false`, with or without a `deny_reason` | deny (unchanged; a reasoned deny is unchanged) | — |
| an unsafe policy path; a mixed-type bulk batch; a request that cannot be serialized; a `PolicyPathResolver` that throws anything outside the family | **deny** — a caller/input defect, deterministic; never an engine error (a path resolver that throws a family subtype opts in, §2) | — |

### 3a. Two known blind spots (documented, not fixed)

- **The compile path cannot see a missing package.** Partially evaluating an undefined reference comes back
  as `{"result": {}}` — byte-identical to a legitimately unsatisfiable filter — so it stays a deny-all.
  Transport, status, interrupt and malformed failures on the compile path **are** indeterminate.
- **The root list fails to empty before any decision.** A root-type list (the example's `GET /catalogs`)
  is scoped by a `GovernedScopeResolver` and, in the example, a supervised-scope client — base-scope SPIs
  whose contract is *fail-closed to empty, never throw*. When the service behind them is down they answer
  an empty scope and the list returns an empty 200 before a role lookup or an OPA call happens. Fail-closed,
  but the same lie this ADR removes elsewhere; revisiting that SPI contract is backlog.

### 4. `PolicyEngineException` carries a `Kind`; resilience retries faults, never decisions

`Kind` = `TRANSPORT`, `TIMEOUT`, `HTTP_STATUS` (+ the status), `INTERRUPTED`, `MALFORMED_RESPONSE`,
`UNDEFINED_DECISION`, `CIRCUIT_OPEN`. **Amends [[0017-cross-service-http-resilience|ADR 0017]] §2:**

- **Retry on thrown transient faults only.** `RetryableClassification` classifies a `PolicyEngineException`
  **by its `Kind` alone** — never by its cause chain, because a JSON-parse failure can carry an `IOException`
  cause and would otherwise be retried — onto ADR 0017 §3's existing table: `TRANSPORT`, `TIMEOUT`, 5xx and
  429 retry; 4xx, `MALFORMED_RESPONSE`, `UNDEFINED_DECISION` and `INTERRUPTED` fail fast. The `IOException`
  cause-chain rule stays for the other edges' throwables. A bundle still loading will not heal within a
  ~50 ms in-call retry, so an undefined result goes straight out as a 503 and the *client* retries later.
- **No more sentinel retry.** A genuine deny, a reasoned deny, a mixed bulk page and an all-false bulk page
  are real answers and are never retried. (Measured cost of the old rule: one extra OPA hop + backoff on
  every deny; Slice 7.3 measured ~8× enrichment latency before the mixed-block exemption.)
- **The breaker records every thrown fault, never a returned decision** — ADR 0017 §5's "never a decision
  input", now true by construction rather than by exemption. The shared `Resilience4jCallGuard` records
  each thrown failure, transient or not, before classifying it (the resolve and tag edges rely on that), and
  it stays as it is. Consequence, accepted: a *sustained deterministic* fault — a policy package that never
  loaded — opens the breaker like an outage does, and later calls answer `CIRCUIT_OPEN` until it half-opens.
  The real kind is in the WARN log of the attempts that opened it, and every call answers 503 either way.
- **Breaker open → `PolicyEngineException(CIRCUIT_OPEN)`.** The decorator stops synthesizing deny values. An
  interrupt during the guard's backoff (which the guard reports as a not-permitted call with an
  `InterruptedException` cause) maps to `INTERRUPTED`, not `CIRCUIT_OPEN`.
- **The §2 identity holds in its new form:** in every config/breaker state the decorator and the plain
  delegate both *throw the family* for a failure and *return the same value* for a decision; the contract
  test pins that, not "identical deny values".
- `PartialResult.error()` / `fromError()` are **deprecated**: the shipped client and decorator no longer
  produce them. `AbacQueryService` keeps today's empty-list handling for a custom client that still returns
  `error()` — that client chose values over the throw.

### 5. The Spring gates throw `AuthorizationIndeterminateException extends AuthorizationServiceException`

In `opa-abac-spring-security`, both gate managers catch `DecisionIndeterminateException` and throw
`AuthorizationIndeterminateException` (the core exception as its cause). Spring Security's own
`AuthorizationServiceException` — "an authorization request could not be processed due to a system
problem" — is the exact semantic, and it `extends AccessDeniedException`, so **everything that catches
`AccessDeniedException` still denies**.

Verified against spring-security-core 7.0.7 bytecode: `AuthorizationManagerBeforeMethodInterceptor`
catches only an `AuthorizationDeniedException` thrown by the manager (routing it to the
`MethodAuthorizationDeniedHandler`); any other exception propagates straight out. Hence the choice over
the two alternatives:

- returning a denied `AuthorizationDecision` subclass (the `StepUpRequiredDecision` precedent) or throwing
  an `AuthorizationDeniedException` subtype would both route the outage **into an adopter's denied
  handler** — and a handler that masks a denial (a `null` or redacted value with 200) would silently mask
  the outage: today's bug in a different coat. The first is also not distinguishable by exception type.
- Cost, accepted: no Spring `AuthorizationDeniedEvent` is published for an indeterminate decision (§9
  covers the observability).

**Amends [[0014-supplier-outage-error-distinct|ADR 0014]] §3:** the two gate rows map a role-source outage
to this exception, not to `AuthorizationDecision(false)`. ADR 0014's core rule — an outage never falls back
to realm roles, OPA is never asked — is unchanged.

### 6. Narrow and typed: only the family is indeterminate

The gates' `catch (Exception) → DENY` stays exactly as it is for everything **outside** the family:
SpEL errors, a resource-resolver throw ([[0013-attribute-rich-pre-authorization|ADR 0013]]'s
resolver-throw → deny), a `null` decision from a broken client, wiring inconsistencies. A deterministic
programming error must not answer "503, retry later" forever, and a hostile input that trips an
exception must keep getting a 403. An adopter who wants their resolver's database outage to be
indeterminate throws a `DecisionIndeterminateException` subtype from it (§2).

### 7. Where the answer is the response, propagate; where it decorates, keep the designed degradation

- **Propagate the family** from: `HierarchicalAuthorizer.isAllowed`; `AbacQueryService.findAuthorized`
  (kill-switch `allow`, `compile`, the allowlist `allowAll` fallback — **no property, one mode**);
  `SubtreeSpecResolver` (rethrows ahead of its `catch (RuntimeException)`); the example's list
  authorizers (they stop returning `Page.empty()`). A list must not answer an empty 200 for items whose
  single GET answers 503 — except the root list's base scope, §3a. The example's catalog list also stops
  degrading a *membership-role outage* to its supervised-only page (a silently partial list): the outage
  propagates, while a role that authoritatively no longer resolves keeps that designed degrade.
- **Keep the designed degradation** where the authorization answer decorates or hints at an
  already-authorized response: `ActionEnrichmentAdvice` omits the `_actions` group, and the example's
  `_provenance` advice omits its field (both unchanged). The example MCP roster keeps its documented
  **empty roster** on a dead policy engine — now chosen on a distinguishable signal rather than inferred
  from an all-`false` vector, and for the reason it was always given: during that outage every call is
  denied too, so a roster advertising unusable tools would be the misleading answer. The example MCP tool
  call answers `denied(CODE_POLICY_UNAVAILABLE)` (`tool-gate-policy-unavailable`), a caller-visible code
  distinct from a policy deny.
- **The rethrow invariant — on every decision surface.** Designed degradations on *degraded input* stay as
  they are — the ancestor walk collapsing to direct-grant-only
  ([[0008-hierarchical-resource-authorization|ADR 0008]]), root and parent attribute enrichment landing on
  "unproven" (ADRs [[0032-root-attribute-enrichment-input-contract|0032]],
  [[0034-tag-gated-placement-input-contract|0034]]) — but **no catch on a decision path may swallow a
  `DecisionIndeterminateException`**: every degrade-catch rethrows the family first. Without this rule,
  §2's "an SPI opts in by throwing a subtype" would be silently eaten by the very catches it must pass.
  That includes the built-in ancestor resolvers' wrap of their path/parent/descendant source SPIs into
  `AncestorResolutionException`: they rethrow the family ahead of the wrap. Their own SQL failures keep
  classifying as `AncestorResolutionException` (degrade); splitting those into the family is backlog.
  Decorations (the two advices above) swallow by design and are outside the invariant.

### 8. Rendering: 503 `DEPENDENCY_UNAVAILABLE`, from the base advice and a standalone fallback

- `AbstractProblemAdvice` maps `AuthorizationIndeterminateException` and `DecisionIndeterminateException`
  to **503** with the existing `DEPENDENCY_UNAVAILABLE` code ("rejected, not served degraded") and the
  detail *"Authorization is temporarily unavailable"*. Within one advice class the most specific handler
  wins, so it reliably beats the base's own `AccessDeniedException` → 403 handler.
- The starter ships a **standalone** advice for adopters who do not extend the base (the
  `EntityNotFoundProblemAdvice` house style), ordered high-precedence and registered **only when no
  `AbstractProblemAdvice` bean exists** — Spring MVC picks the first advice *in order* that can handle
  an exception, so an adopter's own `AccessDeniedException` handler would otherwise turn it into a 403.
  Caveat, documented: within one advice Spring MVC also matches along the **cause chain** before moving
  to the next advice, so the high-precedence fallback also claims an adopter's exception that merely
  *wraps* a family member. That is why it declares exactly the two family types and nothing broader.
- **No `Retry-After`.** There is no truthful value to put in it.
- **The request-level gate stays 403 by default.** `OpaAuthorizationManager` runs in the filter chain;
  its exception reaches `ExceptionTranslationFilter` and the adopter's `AccessDeniedHandler`, which the
  starter never registers (a security starter exposes beans; the application owns its
  `SecurityFilterChain`). The guide shows the type check.

### 9. Observability: the operational log, not the audit channel

The `opa.abac.audit` channel carries oversight events about subjects (`STEP_UP_CHALLENGED`,
`PRIVILEGED_READ`); it has no deny event for an indeterminate one to be confused with, and stays as it
is. The confusion lives in the operational log, and is fixed there: `HttpOpaClient` logs **one** WARN per
attempt, worded as indeterminate and carrying the `Kind` (a retried call therefore logs once per attempt);
the decorator logs breaker-open once as `CIRCUIT_OPEN`; the gates and list paths drop to DEBUG, so one
failed attempt is not three WARNs.

### 10. Release: 1.4.0, with the behaviour changes in a new `CHANGELOG.md`

The API is source- and binary-compatible and **nothing that denied before allows now**; the change is to
error-path behaviour, which is what the release exists for. A new `CHANGELOG.md` starts at 1.4.0 with an
*Upgrade notes* section listing every behaviour change in §Consequences.

## Proof obligations

- Every `Kind` is produced by `HttpOpaClient` from a stubbed OPA (in-process HTTP server) on all four
  methods where it applies; `allow: false`, a reasoned deny and result-without-`allow` stay denies; the
  three input defects stay denies and are never thrown.
- The decorator retries thrown transient kinds, never a returned decision (a deny is called **once**), and
  throws `CIRCUIT_OPEN` when the breaker is open; decorator and delegate agree in every state.
- Each gate maps each family member to `AuthorizationIndeterminateException`; a `catch
  (AccessDeniedException)` still catches it; a non-family throw still returns `DENY`.
- Every degrade-catch has a test that a family member passes through it.
- Each list entry point throws; the advice renders 503 problem+json in both forms.
- **Live, both outage classes through the gateway:** the resilience matrix's sustained role-source outage
  cell flips from 403 to `503 DEPENDENCY_UNAVAILABLE`, beside the unchanged transient-recovers cell; and the
  agent-tool matrix's policy-engine kill drill (its `/mcp` route carries no gateway policy plugin, so a
  stopped engine reaches the starter) answers every tool call with `tool-gate-policy-unavailable` while the
  roster stays empty.
- PIT: zero SURVIVED mutants in the new classification code; the local Sonar gate is `CLEAN`.

## Considered options

- **A third value instead of a throw** (an `indeterminate` flag on `OpaDecision`, reusing
  `PartialResult.error()`). Rejected: `allow()` and `allowAll()` cannot carry a third state, so the
  decorator would keep retrying denies, and every caller that forgets the flag check silently reproduces
  today's collapse.
- **Two independent exceptions** with no common base. Rejected: every future indeterminate kind would add
  a type to every catch site.
- **Strict undefined** (any undefined `allow` is indeterminate). Rejected: an adopter whose policy relies on
  undefined-means-deny would see every legitimate deny become a 503 after upgrading.
- **Broad catch-all** (any exception in a gate is indeterminate). Rejected: §6.
- **An opt-in or opt-out property for the list behaviour.** Rejected: one mode; an empty page during an
  outage is the defect being fixed, and a switch is one more fail-open configuration to get wrong.
- **A new `AUTHORIZATION_UNAVAILABLE` error code.** Rejected: the existing 503 code already means exactly
  this; the detail text names the dependency.
- **A `DECISION_INDETERMINATE` audit event.** Rejected: §9.
- **2.0.0.** Rejected: §10.

## Consequences

Behaviour changes for adopters (the 1.4.0 upgrade notes):

1. A direct `OpaClient` caller gets `PolicyEngineException` where it used to get `false`, a deny, an
   all-false list or `PartialResult.error()`. Still never an allow; unmapped, it surfaces as an error.
2. `@OpaPreAuthorize` and the request-level gate throw `AuthorizationIndeterminateException` on an engine
   failure or role-source outage, instead of denying. With `AbstractProblemAdvice` or the starter's
   fallback advice, the method gate answers 503; the request-level gate answers 403 unless the
   application's `AccessDeniedHandler` checks the type.
3. `AbacQueryService.findAuthorized`, `HierarchicalAuthorizer.isAllowed` and `SubtreeSpecResolver` throw the
   family instead of returning an empty list, `false` or no widening.
4. `ResilientOpaClient` no longer retries denies: one OPA call per deny instead of up to two (a reasoned
   deny was already called once). A sustained deterministic fault now opens the breaker (§4).
5. An OPA answer of `{}` is now a 503, not a 403 — on a single decision the policy package is not loaded;
   on a bulk call, the package may also simply define no `bulk` rule. Check the policy path configuration,
   and give every type whose lists use the allowlist fallback a `bulk` rule.
6. `PartialResult.error()` / `fromError()` are deprecated.

Positive: an outage is visible to users, operators and retrying clients as what it is; the breaker
measures real faults; denies get cheaper. Negative: adopters with custom handling of a 403-on-outage must
move it to the new type; two documented blind spots remain (§3a).

## Related

- [[0014-supplier-outage-error-distinct|ADR 0014]] — the role-source distinction this extends (§3 amended)
- [[0017-cross-service-http-resilience|ADR 0017]] — resilience (§2 amended: fault-only retry, breaker-open throws)
- [[0013-attribute-rich-pre-authorization|ADR 0013]] — resolver-throw → deny, unchanged (§6)
- [[0008-hierarchical-resource-authorization|ADR 0008]] — the ancestor-collapse degradation, kept (§7)
- [[0011-error-contract-problem-json|ADR 0011]] — the problem+json contract and the error-code vocabulary
- [[ENGINE-ERRORS]] — the slice
