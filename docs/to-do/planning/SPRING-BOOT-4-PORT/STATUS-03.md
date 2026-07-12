---
tags:
  - status/implemented
  - type/project
  - area/architecture
  - area/spring
---

# STATUS — T3: Resilience4j 2.4.0: delete the internal coupling

**Status:** ✅ DONE

## What shipped

- `gradle/libs.versions.toml`: `resilience4j` 2.2.0 → **2.4.0**; the stale "managed by the Spring
  Boot BOM" comment replaced (the catalog pin is authoritative — the library modules resolve R4j
  from the catalog, and the note now names the 2.4.0 public-clock rationale).
- `Resilience4jCallGuard.buildBreaker(...)`: the Clock moved onto the config builder —
  **`.clock(clock)`** — and construction switched to the public factory
  **`CircuitBreaker.of(name, cbConfig)`**. The
  `io.github.resilience4j.circuitbreaker.internal.*` import is **deleted** (grep-clean repo-wide).
- API surface re-verified against the shipped 2.4.0 jar before editing (`javap`, not release
  notes): `CircuitBreakerConfig.Builder.clock(java.time.Clock)` public;
  `CircuitBreaker.of(String, CircuitBreakerConfig)` public; `CircuitBreakerStateMachine` has
  **zero** public constructors in 2.4.0 — the bump genuinely forces this migration.
- Javadoc updated: the class-level "Deterministic timing" section and the buildBreaker comment now
  name the public seam (Builder.clock, public since 2.3.0) instead of the contained internal one.
- ADR 0017 §7: dated **addendum** (not a rewrite) — the accepted internal coupling is "eliminated
  as of R4j 2.4.0."

## Tests

- R1: the existing B3 deterministic-timing suite passes **unchanged** on 2.4.0 —
  `Resilience4jCallGuardTest` (11: breaker opens after N window failures; virtual-clock advance
  moves open → half-open without sleeping; backoff intervals recorded by the stub sleeper;
  kill-switch byte-identical), `RetryableClassificationTest` (26), `ResilientOpaClientTest` (8).
  Module: 156 cases, 0 failures, fresh run verified by result timestamps.
- Grep-clean: zero `circuitbreaker.internal` references outside `build/`.
- Full `./gradlew build` green (the resolve/tag edges in the example services ride the same guard).

## Architecture review + refactor

Port-gate review: **nothing substantive to refactor.** The named security widening for this ticket —
"the R4j swap changing when the breaker opens" — cannot happen: the config block is byte-identical
apart from `.clock()` (same COUNT_BASED window, same thresholds, same no-automatic-transition), and
the 11 CallGuardTest cases pin open/half-open/backoff behavior through virtual time; they passed
without edits. Breaker semantics (never a decision input, sentinel-not-recorded) untouched — those
paths live in `call()`, which this ticket did not modify. The `CallGuard` seam is unchanged
(backend-agnostic, ADR 0017 §7); no new seams introduced.

## Integration / e2e

Full build green (Testcontainers ITs). The live resilience e2e (B3 stub rig) runs at T7 per the
fleet plan.

## Decisions

- None beyond the pinned design (F6). The 2.4.0 API shape matched the planning-time upstream
  verification exactly.

## Commit

`build(resilience): R4j 2.4.0 — Clock via public Builder.clock(), internal import deleted (T3)`
