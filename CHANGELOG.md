# Changelog

All notable changes to the published `opa-abac-*` modules. The `example-*` services are demos and are not
published; their changes appear here only where they show an adopter what to do. Earlier releases
(1.0.0 – 1.3.0) are described by their git tags and the README's release notes.

## [1.4.0] — 2026-10-05

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
  `INTERRUPTED`, `MALFORMED_RESPONSE`, `UNDEFINED_DECISION`, `EVALUATION_ERROR`, `CIRCUIT_OPEN`. `RoleResolutionException` joins
  the family (source- and binary-compatible). An SPI implementation opts its own outage in by throwing a
  subtype.
- **`HttpOpaClient`** throws `PolicyEngineException` when it could not obtain a decision, from all four
  methods. A real `allow: false`, a reasoned deny, and a loaded package whose `allow` is undefined for the
  input (undefined-means-deny) are still denies. A request the client refuses to send (an unsafe policy path,
  a mixed-type batch) is still a deny. `OpaClientConfig` now rejects a base URL that is not an absolute
  `http`/`https` URL with a host, so a misconfiguration fails at startup.
- **Resilience** — `ResilientOpaClient` retries only thrown transient faults (transport, timeout, 5xx, 429),
  classified by kind. **A deny is now called exactly once**. The OPA breaker counts exactly the faults it
  retries, so it opens on a real outage — for the first time — and never on a fail-fast one. An open breaker
  throws `CIRCUIT_OPEN`. `CallGuard` gains an optional fourth argument, `recordableError`, for that.
- **`opa-abac-spring-security`** — both gates throw the new `AuthorizationIndeterminateException`, a Spring
  `AuthorizationServiceException`: still an `AccessDeniedException`, so existing handlers still refuse the
  call, but it bypasses masking `@HandleAuthorizationDenied` handlers. `AbstractProblemAdvice` renders it,
  and the raw core family, as **503 `DEPENDENCY_UNAVAILABLE`**.
- **`opa-abac-spring-data`** — `AbacQueryService.findAuthorized`, `HierarchicalAuthorizer.isAllowed` and
  `SubtreeSpecResolver` propagate the family instead of answering an empty page, `false` or no widening. The
  built-in ancestor resolvers propagate it when it comes from an ancestor-source SPI that opts in; their own
  SQL failures still degrade, as before (ADR 0037 §7).
- **`opa-abac-spring-boot-starter`** — a fallback `IndeterminateDecisionProblemAdvice` gives the same 503 to
  a servlet application that does not extend `AbstractProblemAdvice`; it backs off when one exists. The
  request memo also replays an outage an SPI opts in with, not only `RoleResolutionException`.

### Fixed

- **`opa-abac-spring-data`** — a list whose residual is `ALLOW_ALL` (an unconditional `filter`) and that passes a
  subtree widening returned **only the subtree's rows**, not every row in scope. `ALLOW_ALL` is
  `Specification.unrestricted()`, a null predicate, and Spring Data's `or()` drops a null side, so
  `ALLOW_ALL OR subtree` collapsed to the subtree. Fail-closed (rows went missing, nothing extra showed) but
  wrong — e.g. the example's catalog member who also supervised another catalog saw only the supervised one.
  `AbacQueryService` now skips the OR for an `ALLOW_ALL` residual. Present since the 4-argument
  `findAuthorized` (1.0.0).

### Dependencies

- **`opa-abac-core`** declares Jackson 3 (`tools.jackson.core:jackson-databind`) **3.1.7**, up from 3.1.5. Seven
  Jackson advisories published 2026-09-28 – 10-01 affect 3.1.5; the library uses none of the affected features
  (polymorphic typing, object identity, `Path`, `XMLGregorianCalendar`, `DataInput` parsing — it only reads
  OPA's response as a tree). The modules that import the Spring Boot BOM still resolve **Boot's** managed
  Jackson (3.1.5 and 2.21.5 under Boot 4.0.8), as does every Boot application: to move ahead of Boot, override
  Jackson in your own build, or take the Boot patch that manages a fixed version.

### Upgrade notes — behaviour changes

1. **A direct `OpaClient` caller** gets `PolicyEngineException` where it used to get `false`, a deny, an
   all-false list or `PartialResult.error()`. It is never an allow; unhandled, it surfaces as an error. Catch
   `DecisionIndeterminateException` if you want to answer "not now".
2. **`@OpaPreAuthorize`** throws `AuthorizationIndeterminateException` on an engine failure or role-source
   outage instead of denying. With `AbstractProblemAdvice`, or the starter's fallback advice, the response is
   503 `DEPENDENCY_UNAVAILABLE` (it was 403 `ACCESS_DENIED`). The fallback advice registers only when no
   `AbstractProblemAdvice` bean exists, and runs **first** (`@Order(HIGHEST_PRECEDENCE)`), so:
   - your own `@ExceptionHandler(AccessDeniedException)` no longer answers these two types — the fallback
     does, with problem+json;
   - an exception of yours that wraps a family member is answered by the fallback too (MVC walks the cause
     chain within an advice before asking the next);
   - to keep your own envelope, extend `AbstractProblemAdvice` (the fallback then backs off), or declare your
     own `IndeterminateDecisionProblemAdvice` bean — a subclass overriding `handleIndeterminate`. Scoping an
     `AbstractProblemAdvice` subclass to some controllers (`basePackages`, `assignableTypes`) still turns the
     fallback off application-wide, so controllers outside that scope get your other handlers' answer.
3. **The request-level `OpaAuthorizationManager`** throws the same type. In the filter chain it reaches your
   `AccessDeniedHandler`, so it is a **403 by default**. Check the type there to answer 503 (see the
   [ABAC guide](docs/guides/ABAC-AUTHORIZATION.md)).
4. **List queries, the hierarchical check and the subtree widening** propagate the family instead of
   returning an empty list, `false` or no widening.
5. **An OPA answer of `{}`** — no `result` at all — is now "could not decide" (503), not a deny. On a single
   decision it means the policy package is not loaded at that path. On a bulk call it can also mean the
   package defines no `bulk` rule. Check your policy path configuration, and give a `bulk` rule to every type
   whose lists use the allowlist fallback (else the list answers 503) and every enrichable type (else
   `_actions` is omitted, as in 1.3.0). On compile, a missing or non-object `result` is now a malformed answer
   (503); `{"result": {}}` is still a deny-all.
6. **Retries and the breaker** — a deny is called once instead of up to twice. **The OPA breaker now
   opens**, for the first time: in 1.3.0 the client never threw, so it never counted anything. It counts the
   faults it retries (transport, timeout, 5xx, 429); a fail-fast fault — a package that never loaded, a
   policy evaluation error, a malformed body, a 4xx — answers 503 on each call but never opens it, so one
   type's defect cannot refuse every other type. With the defaults (`failure-threshold` 5, one retry per call)
   about three fast-failing requests open it (five for timeouts, which are not retried under the default 5 s
   timeout and 2.5 s retry ceiling), and every OPA-backed call answers `CIRCUIT_OPEN` (503) until a half-open probe succeeds —
   up to `open-duration` (10 s) after OPA is back. A sidecar restart that cost a few hundred milliseconds of
   403s can now cost ~10 s of 503s under load; tune `opa.abac.resilience.opa.breaker.failure-threshold` and
   `open-duration` to your restart profile.
7. **`PartialResult.error()` / `fromError()`** now mean "no policy answer was obtained": the shipped client
   returns it only when it refuses to send a compile request. A custom client may still return it for a
   failure; `AbacQueryService` keeps treating it as an empty page with no widening.
8. **Logs** — an engine failure is logged once per attempt at WARN as `OPA decision indeterminate` with its
   kind (it was `OPA denied (fail-closed)`). A malformed body is named by the parser's exception class only;
   the full cause is at DEBUG. The gates log the translation at DEBUG.
9. **Startup** — `OpaClientConfig` (and so `opa.abac.base-url`) rejects anything but an absolute `http`/`https`
   URL with a host. A value such as `opa:8181` used to fail every request; it now fails the application
   context. The message never echoes the value (a URL can carry credentials).
10. **A policy evaluation error** — OPA answers a policy that itself fails on this input (a complete rule
    producing two outputs: `eval_conflict_error`; `eval_type_error`; `eval_with_merge_error`) with HTTP 500. In
    1.3.0 that was a 403 on a decision, an empty 200 on a list, and omitted `_actions` on enrichment; on a
    decision or a list it is now `EVALUATION_ERROR` (503), and enrichment still omits `_actions`. It is not
    retried and not counted on the breaker, and the WARN names the code. OPA's
    other `eval_*` codes (cancellation, internal, built-in and `http.send` failures) stay a retryable,
    counted status. Fix the policy — `opa test` with the input that triggered it.

Two cases stay deny-shaped by design, documented in ADR 0037 §3a: a compile against a package that is not
loaded (it compiles to exactly what an unsatisfiable filter compiles to), and a root-type list whose
base-scope resolver fails to an empty scope before any decision is made — an empty 200, or a
membership-only 200 for a member who also supervises when only the supervised source is down.
