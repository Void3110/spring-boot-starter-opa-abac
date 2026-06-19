---
tags:
  - status/active
  - type/review
  - area/spring-security
  - area/abac
---

# B3 — Cross-service HTTP resilience — Code Review

> **Verdict**: Approved with fixes (1 Medium + 1 Low found and fixed on the branch).
> **Scope**: the B3 resilience slice (T1–T4) — the `CallGuard` seam + R4j impl, the `ResilientOpaClient`
> decorator + starter auto-config, the app-side resolve/tag wrappers, and the fault-injecting e2e.
> **Branch**: `feature/void3110/http-resilience` vs `main` (40 files, +2907 / −73).

## Summary

A multi-lens adversarial review (8 lenses — fail-closed-authz, core-boundary, rego-policy,
persistence-concurrency, security-audit, api-contract, conflict-ci-deadcode, infra-e2e — + an
adversarial refutation pass + a completeness critic; 12 agents) found **zero fail-open / widening /
concurrency defects** and **zero Critical**. It surfaced **one genuine Medium** (a self-inflicted
availability regression that contradicts the slice's own ADR 0017 §5 invariant) and the matching **Low**
test gap. Both are fixed on the branch. The load-bearing security invariants all hold (verified by hand,
below).

## Critical Issues

None.

## Medium Issues

**M1 — The OPA breaker counted genuine policy denials as failures (decision-driven self-open).** *(Fixed)*

`Resilience4jCallGuard.call` recorded a breaker failure whenever the **retryable-result** predicate fired
— and on the OPA edge the decorator's predicate is `denied -> denied == false` (and
`decisions.contains(false)`). So every genuine policy **DENY** (returned as `false`) recorded a failure on
the long-lived, shared OPA breaker. With the OPA edge defaults (`maxRetries=1` → 2 attempts/request,
`failureThreshold=5`), ~3 consecutive deny requests **self-open** the OPA breaker, after which every OPA
call — *including ones that would be ALLOWED* — fails closed for the 10s open window. This is **not a
fail-open** (the outcome only ever denies *more*), but it is a self-inflicted availability regression that
directly contradicts ADR 0017 §5 ("the breaker is a latency/load optimization … **never a decision
input**") and the method's own comment.

**Fix** (`Resilience4jCallGuard.call`): a breaker failure is now recorded **only on the thrown
`retryableError` path** — an unambiguous fault — never on a returned sentinel. A returned retryable result
is still **retried** (transient recovery preserved) but never feeds the breaker. Consequence, made explicit
in the guide: the **resolve/tag breakers still open** (those edges surface a real outage as a thrown
`Transient*Exception`), while the **OPA breaker is effectively a no-op** (the plain `HttpOpaClient` swallows
faults into the sentinel and never throws) — the honest price of a swallow-everything delegate, and
strictly better than decision-driven self-opening. Removed the now-dead `TransientResult` marker type.

## Low Issues

**L1 — No test pinned the decision path of the breaker invariant.** *(Fixed)*

The only breaker-open test opened it via the deny sentinel (the very path M1 is about), so P5
("never a decision input") was verified only for the fault path. Added
`genuineDenials_doNotOpenTheBreaker`: a delegate returning a genuine `allow=false` for 20 consecutive
calls (≫ `failureThreshold`) — asserts the OPA breaker stays **CLOSED** and the delegate is reached every
time. Also reworked `breakerOpen_synthesizesFailClosed_withoutDelegate` to open the breaker via a **thrown
fault** (the only thing that may now), keeping the fail-closed-synthesis assertions.

## Fail-closed verification

Every error / breaker-open / exhausted path lands on deny/empty — verified by hand:
- **OPA decorator** — `allow`→`false`; `compile`→**`PartialResult.error()`** (`fromError=true`), asserted
  `!= denyAll()` **and** `!= allowAll()` (the 5.5-B widening landmine); `allowAll`→n×`false`; on breaker-open
  the decorator synthesizes these by hand (delegate never called). On exhausted retry the guard returns the
  delegate's last (already fail-closed) value. **Never widens.**
- **resolve wrapper** — 204→empty, 200+valid→resolved (terminal, un-retried); 200-blank / malformed /
  4xx → `RoleResolutionException` immediately (permanent); exhausted transient / breaker-open →
  `RoleResolutionException`. The set that throws is **unchanged from B2**; retry only slots ahead of the
  throw, so the realm fallback is never reached on an outage. An `InterruptedException` is permanent (no
  retry) and restores the interrupt flag.
- **tag wrapper** — 200+valid→definitions; permanent → `TagDefinitionFetchException` (→503) immediately;
  exhausted / breaker-open → `TagDefinitionFetchException`.

## Security audit

- No weakened scope/ownership check; the wrappers don't touch the `catalogId`-scope rule (still the
  handler's 404). No new IDOR surface.
- **The realm-fallback interplay is intact**: an outage (now: an *exhausted* outage) still throws → deny,
  never falls back wider (B2 preserved). The retry makes the throw *rarer*, never replaces it.
- No cache serves an authz artifact across subjects (no cache introduced).
- No SpEL/SQL/JSONB/ltree built from user input in the diff. The OPA path interpolation is unchanged
  (`opa-abac-core` untouched).
- **No secrets/PII in logs**: the wrappers WARN with status/exception-class only — never the `userId`,
  token, or body (the B2 no-PII discipline preserved; `outageThrow_carriesNoPii` still green).
- No authn edge defaults to a subject.

## Concurrency & idempotency

- **No-lock invariant holds**: both wrapped edges run on the request thread, outside any write tx
  (`TagAssignmentService` is not `@Transactional`; the team-row `FOR UPDATE` is server-side in
  user-mgmt and makes no outbound B3 edge). Documented at each wrap point. No resilience-wrapped call is
  inside an open write transaction.
- **Side-effect-free retry**: all three edges are read-only (OPA decision / GET resolve / GET tag), so a
  retry — incl. after a read-timeout — cannot double-execute. Documented; future mutating edges must opt out.
- The breaker state machine is R4j's (thread-safe); the guard is otherwise stateless per call. The BPP's
  lazy `volatile` guard init has a benign double-init race (BPP runs single-threaded at context refresh;
  last-writer-wins is harmless).

## Wiring & sibling sweep

- Every new seam has a non-test caller + a non-happy-path test: `CallGuard`←decorator/wrappers;
  `ResilientOpaClient`←the BPP; resolve/tag `CallGuard` beans←`@Qualifier` ctor injection (the two-ctor
  `@Autowired` build-breaker fixed in T3); `OpaResilienceAutoConfiguration`←`@Import`; `U8` proves both
  classpath states.
- **Sibling sweep for M1**: grepped every `recordFailure` / `breaker.onError` call site — after the fix
  there is exactly **one**, on the exception path. The resolve/tag edges drive their breakers via thrown
  `Transient*Exception` (the exception path), so they record correctly and are *not* affected by the fix —
  the asymmetry is consistent and intended across all three edges. Siblings clean.

## Autonomous-run check

- **Laziness**: I2 (the resolve-blip-recovers IT) was folded into T4's live e2e + the U9a unit recovery
  rather than built as a separate `role-source=http` Spring IT. Reviewed: honest — the live E1/E2 e2e
  passed and proves the same cut through real Postgres + the gateway; a separate IT would duplicate
  coverage. Flagged in STATUS-03, not silently dropped.
- **Self-preferential bias**: the M1 defect is exactly the kind a single-pass self-review misses — it sits
  *inside* the design decision the run made (retry-on-fail-closed-sentinel) and the run's own ★ gate +
  green tests did not catch it (the only breaker test drove it via the deny sentinel, masking the issue).
  The adversarial completeness critic found it. STATUS notes were otherwise accurate.
- **Goal drift**: none — `opa-abac-core` is byte-for-byte unchanged (zero diff), no Spring import leaked,
  B2's contract is preserved exactly, zero Rego (`opa test` 183/183).

## What's done right

- The fail-closed contract is identical in every breaker/config state, pinned by a contract test.
- B2 preserved exactly, proven by attempt-count assertions (terminal-signals-un-retried, 4xx-immediate).
- `opa-abac-core` untouched; R4j confined to the Spring layer behind a backend-agnostic seam.
- Virtual-time tests throughout (zero `Thread.sleep`); the live e2e two-pass headline passed.

## Test results

- `./gradlew build`: **green** (all modules + both example apps + Testcontainers ITs + `ddl-auto: validate`).
- Resilience unit suites: `:opa-abac-spring-security` resilience tests green incl. the 2 new/-reworked
  breaker tests; `:example-catalog-management-service` 88 green.
- `opa test infra/opa/policies/`: **183/183** unchanged (zero Rego).
- Live e2e (`run-resilience-matrix.sh`, prior to this fix): E1 → 200, E2 → 403, 0 assertions failed. The
  fix touches only the breaker-recording path (not the retry/fail-closed outcome the e2e asserts), so the
  e2e cut is unchanged.
