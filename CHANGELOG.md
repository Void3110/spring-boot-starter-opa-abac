# Changelog

All notable changes to the published `opa-abac-*` modules. The `example-*` services are demos and are not
published; their changes appear here only where they show an adopter what to do. Earlier releases
(1.0.0 – 1.3.0) are described by their git tags and the README's release notes.

## [1.4.0] — unreleased

### "Could not decide" is no longer reported as "no" ([ADR 0037](docs/architecture/adr/0037-indeterminate-decision-distinct-from-deny.md))

Until 1.3.0 every policy-engine failure — a timeout, a refused connection, a non-200, a malformed body, a
policy package that never loaded — and every role-source outage was fail-closed **as a deny**: a 403, an
empty list, an all-false batch. Nothing ever widened, but the caller could not tell "the policy said no"
from "we could not ask the policy". A service answered 403 where it should have answered 503, a durable
workflow recorded a final refusal where it should have retried, and the resilience layer retried every
genuine deny because a failure looked exactly like one.

1.4.0 keeps the guarantee — **nothing that was refused in 1.3.0 is allowed in 1.4.0** — and makes the
second state distinguishable by type:

- **`opa-abac-core`** — a Spring-free, abstract `DecisionIndeterminateException` family.
  `PolicyEngineException` (new) carries a `Kind`: `TRANSPORT`, `TIMEOUT`, `HTTP_STATUS` (with the status),
  `INTERRUPTED`, `MALFORMED_RESPONSE`, `UNDEFINED_DECISION`, `CIRCUIT_OPEN`. `RoleResolutionException` joins
  the family (source- and binary-compatible). An SPI implementation opts its own outage in by throwing a
  subtype.
- **`HttpOpaClient`** throws `PolicyEngineException` when it could not obtain a decision, from all four
  methods. A real `allow: false`, a reasoned deny, and a loaded package whose `allow` is undefined for the
  input (undefined-means-deny) are still denies. A request the client refuses to send (an unsafe policy path,
  a mixed-type batch) is still a deny.
- **Resilience** — `ResilientOpaClient` retries only thrown transient faults (transport, timeout, 5xx, 429),
  classified by kind. **A deny is now called exactly once**, and the breaker counts real faults. An open
  breaker throws `CIRCUIT_OPEN`.
- **`opa-abac-spring-security`** — both gates throw the new `AuthorizationIndeterminateException`, a Spring
  `AuthorizationServiceException`: still an `AccessDeniedException`, so existing handlers still refuse the
  call, but it bypasses masking `@HandleAuthorizationDenied` handlers. `AbstractProblemAdvice` renders it,
  and the raw core family, as **503 `DEPENDENCY_UNAVAILABLE`**.
- **`opa-abac-spring-data`** — `AbacQueryService.findAuthorized`, `HierarchicalAuthorizer.isAllowed`,
  `SubtreeSpecResolver` and the built-in ancestor resolvers propagate the family instead of answering an
  empty page, `false`, no widening or a degraded chain.
- **`opa-abac-spring-boot-starter`** — a fallback `IndeterminateDecisionProblemAdvice` gives the same 503 to
  a servlet application that does not extend `AbstractProblemAdvice`; it backs off when one exists.

### Upgrade notes — behaviour changes

1. **A direct `OpaClient` caller** gets `PolicyEngineException` where it used to get `false`, a deny, an
   all-false list or `PartialResult.error()`. It is never an allow; unhandled, it surfaces as an error. Catch
   `DecisionIndeterminateException` if you want to answer "not now".
2. **`@OpaPreAuthorize`** throws `AuthorizationIndeterminateException` on an engine failure or role-source
   outage instead of denying. With `AbstractProblemAdvice`, or the starter's fallback advice, the response is
   503 `DEPENDENCY_UNAVAILABLE` (it was 403 `ACCESS_DENIED`).
3. **The request-level `OpaAuthorizationManager`** throws the same type. In the filter chain it reaches your
   `AccessDeniedHandler`, so it is a **403 by default**. Check the type there to answer 503 (see the
   [ABAC guide](docs/guides/ABAC-AUTHORIZATION.md)).
4. **List queries, the hierarchical check and the subtree widening** propagate the family instead of
   returning an empty list, `false` or no widening.
5. **An OPA answer of `{}`** — no `result` at all — is now "could not decide" (503), not a deny. On a single
   decision it means the policy package is not loaded at that path. On a bulk call it can also mean the
   package defines no `bulk` rule. Check your policy path configuration, and give every type whose lists use
   the allowlist fallback a `bulk` rule.
6. **Retries and the breaker** — a deny is called once instead of up to twice. A sustained deterministic
   fault (e.g. the policy package never loaded) now opens the OPA breaker, and later calls answer
   `CIRCUIT_OPEN` until it half-opens. The real kind is in the WARN log of the attempts that opened it.
7. **`PartialResult.error()` / `fromError()`** now mean "no policy answer was obtained": the shipped client
   returns it only when it refuses to send a compile request. A custom client may still return it for a
   failure; `AbacQueryService` keeps treating it as an empty page with no widening.
8. **Logs** — an engine failure is logged once per attempt at WARN as `OPA decision indeterminate` with its
   kind (it was `OPA denied (fail-closed)`). The gates log the translation at DEBUG.

Two cases stay deny-shaped by design, documented in ADR 0037 §3a: a compile against a package that is not
loaded (it compiles to exactly what an unsatisfiable filter compiles to), and a root-type list whose
base-scope resolver fails to an empty scope before any decision is made.
