---
tags:
  - status/done
  - type/project
  - area/abac
  - area/spring
---

# STATUS — T1: `CallGuard` seam + Resilience4j impl + per-edge config (spring-security)

**Status:** ✅ DONE

## What shipped

The backend-agnostic resilience seam every B3 edge calls through, plus its only B3 impl. All in
`opa-abac-spring-security`, package `dev.dmitriikonovalov.opaabac.security.resilience`:

- **`CallGuard`** — the seam interface: `<T> T call(Supplier<T> body, Predicate<Throwable> retryableError,
  Predicate<T> retryableResult)`. Backend-agnostic — **no Resilience4j type in the signature** (the Boot-4
  native backend is a later one-impl swap, ADR 0017 §7). It classifies on **both** a thrown exception and a
  **returned result**, because the edges classify an HTTP failure *after* a successful `send()` (a 5xx is a
  normal response, only a transport fault throws). Javadoc names both impls + the injectable-clock contract.
- **`ResilienceConfig`** — the immutable per-edge budget record: `enabled`, `maxRetries`, `backoff`,
  `ceiling`, `failureThreshold`, `openDuration`, `halfOpenProbes` (+ `maxAttempts()` = `maxRetries+1`).
  Validates in the compact constructor.
- **`Resilience4jCallGuard implements CallGuard`** — wraps an R4j `CircuitBreaker` + an exponential
  full-jitter `IntervalFunction`. **Injectable `Clock` + `sleeper`** so all timing is virtual in tests.
  Exhaustion **re-throws the last cause unchanged** (load-bearing for T3's B2 mapping) / returns the last
  value unchanged; `enabled=false` → a **single unguarded attempt** (byte-identical to pre-B3); breaker-open
  → `CallNotPermittedException` **without** invoking the body.
- **`CallNotPermittedException`** — the backend-agnostic breaker-open signal (callers catch *this*, not
  R4j's, so the backend can swap).
- **`RetryableClassification`** — the shared Fork-3 classification: `retryableError()` (IOException &
  subclasses — connect-refused / connect-timeout / read-timeout / reset; unwraps the cause chain) +
  `retryableStatus(int)` (5xx/429 retry; 4xx≠429 fail-fast). The single source both edges fold in.

R4j added as a real `api` dependency of `opa-abac-spring-security` (catalog: `resilience4j-circuitbreaker`
+ `resilience4j-core`, BOM-managed). No edge is wired yet — T1 is the foundation T2/T3 consume.

## Tests

`:opa-abac-spring-security:test --tests '*resilience*'` → **37 passed, 0 failed** (11
`Resilience4jCallGuardTest` + 26 `RetryableClassificationTest`). All **virtual-time, zero `Thread.sleep`**
(a `MutableClock` the no-op `sleeper` advances).

- **U1** — classification table (each failure class → retry/no-retry) + attempt counts (retryable →
  `maxRetries+1`; non-retryable → exactly 1; retryable-result → exhausts then returns last value).
- **U2** — exhaustion re-throws the **exact last instance** (asserted `isSameAs`), no backend wrapper.
- **U6** — latency bound: total virtual time ≤ ceiling; a tight ceiling stops retries early.
- **U7** — breaker lifecycle: N failures open it → short-circuits without invoking the body; open →
  (virtual openDuration elapses) → half-open probe success → closed.
- Plus: happy-path single attempt; kill-switch-off single unguarded attempt.

## Architecture review + refactor

_Filled at the ★ gate._ Findings:
- **One refactor applied:** the call loop computed an `elapsed` nanos value (`clock.millis()*1e6 - start`)
  to feed `breaker.onError/onSuccess` — but against a synchronous body the clock doesn't advance, so it was
  always `0`: dead complexity pretending to measure latency. Removed it; extracted `recordFailure(...)` that
  reports `0` with a comment pinning **why** — this guard's breaker is **failure-count** based
  (`failureRateThreshold=100%` over a count window), not latency based, so the call duration is irrelevant
  to the open/close decision (ADR 0017 §5: the breaker is a load optimization, never a decision input).
- **Boundary:** `opa-abac-core` untouched; `CallGuard` signature is R4j-free (verified). The only R4j
  `internal` coupling is one line — `new CircuitBreakerStateMachine(name, cfg, clock)` — the *only* seam R4j
  exposes to inject a `Clock` (its `CircuitBreaker.of` factory has no clock overload); contained + commented.
- **Wiring:** the seam has no production consumer yet (correct for T1 — consumers are T2/T3); every
  non-happy path is exercised at the seam (4xx-no-retry, exhausted-throw, breaker-open synth, disabled,
  result-retry).
- Nothing else substantive — no invented churn.

## Integration / e2e

N/A for T1 (no edge wired; the seam is unit-proven). ITs/e2e land in T3/T4.

## Decisions

- **Retry-on-result, not just on-exception.** The edges hold the `HttpResponse` and classify a 5xx as a
  *value*, not a throw — so `CallGuard.call` takes **two** predicates (error + result). The design's
  "`call(Supplier, Predicate<Throwable>)`" was the "or equivalent" minimum; the result predicate is required
  to wrap the OPA decorator (whose delegate never throws) and to retry a 5xx the edges read as a status.
- **Clock injection via R4j's `internal.CircuitBreakerStateMachine(name, cfg, clock)`** — the only public
  seam for a breaker clock in R4j 2.x. Isolated to one line in `buildBreaker`.
- **Backoff determinism** via an injectable `sleeper` (`LongConsumer` of millis) the tests stub to advance
  the virtual clock — so full-jitter exponential backoff is real in production yet instant + observable in
  tests, with assertions on bounds (`≤ ceiling`), never exact waits.

## Commit

`feat(resilience): add CallGuard seam + Resilience4j impl + per-edge config (T1)`
