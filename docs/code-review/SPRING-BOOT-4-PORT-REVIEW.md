---
tags:
  - status/active
  - type/review
  - area/architecture
  - area/spring
---

# SPRING-BOOT-4-PORT — Code Review

> **Verdict**: **Approved** (zero Critical, zero Medium, zero Low — no fixes required)
> **Scope**: The full SB4-port autonomous-run branch — the single-line Boot 3.4 → 4.0.7 port
> (Framework 7 / Security 7 / Hibernate 7.2 / Jackson 3.1.4 / R4j 2.4.0 / Gradle 9.6.1 / Java 25),
> staged T1–T7 per ADR 0026 with a byte-identical-behavior acceptance frame. 116 files,
> +1,053/−394. · **Branch**: `feature/void3110/spring-boot-4-port` vs `main`
> (11 commits, linear: 7 ticket commits + 3 mulch + 1 interleaved)

## Summary

Review ran the **multi-lens adversarial workflow** (Path 2B): eight failure-mode lenses
(fail-closed/authz, security-audit, persistence/concurrency, core-boundary, rego, API-contract,
conflict/dead-code, infra/e2e) fanned out over the diff, every finding subject to adversarial
refutation, then a completeness critic to widen the net. 10 agents, ~619k tokens, 155 tool calls;
all structured results verified in the workflow journal (no crashes, no skips — every empty set is
a deliberate verdict, not a failure). **All eight lenses returned empty finding sets; the critic
added nothing.** The reviewer then independently spot-verified every load-bearing invariant from
source (below) rather than accepting the clean sweep on trust. The port is what it claims to be:
mechanical, behavior-preserving, and honestly documented — including where it deviated from ticket
letter (T2's bridges) and where it stopped short (T7's partial perf ledger).

## Critical Issues

None.

## Medium Issues

None.

## Low Issues

None.

## Fail-closed verification

Every port-touched decision path confirmed to land on deny/empty on error or missing input:

- **`OpaAuthorizationManager` / `OpaPreAuthorizeAuthorizationManager`**: the T2→T4 migration is a
  signature rename (`check(Supplier<Authentication>,…)` → `authorize(Supplier<? extends
  Authentication>,…)`); method bodies — including the `!(auth instanceof AbacAuthentication) →
  deny` guard and the broad `catch (Exception) → deny` tail — are character-identical in the diff
  context. The T2 `@Deprecated check()` bridges (added because `check()` was still abstract on
  Security 6.5, a documented ticket-letter deviation) were fully deleted in T4; no dead bridge
  survives on the branch.
- **Jackson 3's unchecked `JacksonException`** (replacing checked `JsonProcessingException`) opened
  no propagation hole: a sweep of every `catch` clause in library main source shows all fail-closed
  sites catch broad `Exception`/`RuntimeException` (which swallow the unchecked type), and
  `ResourceTagsConverter` catches `JacksonException` explicitly; there is **no**
  `JsonProcessingException`-only catch anywhere on the branch.
- **`ResidualSpecificationFactory`**: `DENY_ALL → cb.disjunction()` (always-false) untouched;
  only the `ALLOW_ALL` neutral element changed idiom (`Specification.where(null)` →
  `Specification.unrestricted()` — Data JPA 4 made `where(null)` ambiguous). The neutral element
  is only ever **AND-ed** with the caller's scope, so `unrestricted()` cannot widen a deny path.
- **`AbacQueryService`**: the composition remains `scopeOnly(scope).and(widened).and(notDenied())`;
  the T6 change `Specification.where(tagResidual).or(subtreeSpec)` → `tagResidual.or(subtreeSpec)`
  is semantically identical (`where()` was a no-op wrapper), and the `or` stays *inside* `widened`,
  which is AND-ed with scope — the residual still narrows, never replaces.

## Security audit

Lens returned empty; reviewer concurs. The diff introduces no new scope/ownership logic, no new
cache keyed on authz artifacts, no new injection surface (the Jackson swap changes the parser, not
what is parsed or where it flows), and no authn-edge default. The wire-parity pins are the port's
security backbone: **W1** pins the exact OPA request-body property sets (no field appears or
vanishes under Jackson 3's changed defaults), **W2** pins unknown-claim tolerance in
`JwtClaimsSubjectExtractor`, **W3** pins byte-level JSONB round-trip of a Jackson-2-written
literal (incl. non-ASCII keys) through the Jackson-3 converter. All three passed with **zero
Jackson-3 default-flip restores** — bare `JsonMapper.builder().build()` everywhere.

## Concurrency & idempotency

No lock, version-guard, or mutation-gating decision moved in this diff (Rules 1–2 untouched).
The one concurrency-adjacent change is `Resilience4jCallGuard` (T3): the breaker previously built
via R4j's *internal* `CircuitBreakerStateMachine(name, cbConfig, clock)` constructor now uses the
public `CircuitBreakerConfig.Builder#clock(Clock)` (API since 2.3.0) + `CircuitBreaker.of(name,
cbConfig)`. Same config object, same clock seam, one line of internal coupling deleted — breaker
semantics (including B3's denials-are-not-failures classification, which lives in the guard's
execute path, untouched) are unchanged; the deterministic-timing tests pass unmodified.

## Wiring & sibling sweep

No new seams introduced — the port re-plumbs existing ones. Sibling-sweep checks done by the
reviewer directly:

- **Hibernate 7.2 / Jackson split-brain**: Hibernate 7.2 has no Jackson-3 `FormatMapper`, so
  `com.fasterxml.jackson.core:jackson-databind` rides as `runtimeOnly` on `opa-abac-spring-data`
  solely as Hibernate's JSONB engine. Verified: that is the **only** databind reference in any
  build file, and **zero** `com.fasterxml` databind/core imports exist in main/test source — every
  `com.fasterxml` hit is `jackson.annotation.*`, which Jackson 3 intentionally keeps under that
  namespace. W3's parity pin is precisely the split-brain regression test.
- **Slice invariants**: `git diff main...HEAD` confirms **zero `*.rego` edits**, **zero
  `scripts/postman/` edits**; `opa-abac-core` main source remains Spring/JPA-free.
- **The one harness edit** (`scripts/load/run-load.sh`): a per-ladder-stage `mint_perf_token` call.
  Validity-preserving, not tuning — auth is never the measured quantity, and per ADR 0021's
  ladder-stage validity split an expired-token 401 is red (invalid run), never knee data. The
  comment explains why the need is new (pre-SB4 the knee stopped the ladder inside stage 1).

## Autonomous-run check

- **Laziness** — not found. Ticket letters were fully executed; where reality diverged (T2:
  `check()` still abstract on 6.5 made the letter uncompilable) the deviation is documented in
  STATUS-02 and cleaned up in T4. T7's perf re-baseline is *honestly partial*: the gate delta and
  ceiling are explicitly NOT recorded (host memory starvation crossed ADR 0021 validity
  thresholds), with re-run commands in PERFORMANCE.md — the opposite of declaring done on partial
  work.
- **Self-preferential bias** — not found. STATUS notes disclose the failures (junit-launcher crash,
  Boot-4 modularization traps, Hibernate FormatMapper surprise, OTEL 2.11 silence) rather than
  smoothing them; the review gates recorded in the notes match the diff.
- **Goal drift** — not found. Fail-closed edges byte-identical across T1–T7; core boundary held;
  the acceptance frame (zero rego, zero collection edits, W1–W3 parity) is verifiable from the
  diff and verified above.

## What's done right

- The **wire-parity pins (W1–W3) as tests, not assertions in prose** — the Jackson-3 migration's
  riskiest failure mode (silent default flips changing the OPA request wire shape) is pinned by an
  exact-property-set test that will fail on any future mapper-config drift.
- **Version-bump hygiene**: every mapping (test-slice artifacts, `spring-boot-restclient`,
  liquibase runtimeOnly, `HttpClientSettings`) was jar/POM-verified rather than guessed, and the
  deprecation sweep (D2) reached zero warnings instead of suppressing them.
- **ADR 0026 implementation addendum + ADR 0017 §7 addendum** written in the same slice — the
  decision record moved with the code.

## Test results

- `./gradlew test --rerun-tasks` (full suite, cache-bypassed, executed fresh by the reviewer):
  **green — 830 tests, 0 failures** (JDK 25, no `JAVA_HOME` override).
- `opa test infra/opa/policies/`: **228/228** (policies byte-untouched on the branch).
- newman fleet: **14/14 runners green, zero collection edits** (T7, on the ported images) — not
  re-run in review; no runtime-path changes were made during review (zero fixes).

## Verdict rationale

Zero findings across eight adversarial lenses plus a completeness critic, with the reviewer's
independent source-level verification of every high-risk anchor agreeing. The branch is
approved as-is; no review commits beyond this note. Push/PR remains a maintainer decision.
