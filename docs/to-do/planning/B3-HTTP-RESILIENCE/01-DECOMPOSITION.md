---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/spring
---

# B3 — Cross-service HTTP resilience — 01-DECOMPOSITION

> The ordered work list for Slice B3. Design: [[00-DESIGN]] (ten forks, behavior matrix, all-edges sweep,
> proof obligations). Pinned by ADR [[0017-cross-service-http-resilience|0017]]. Slice note:
> [[B3-HTTP-RESILIENCE]]. QA cases: [[10-QA-TEST-CASES]]. **Four tickets, T1→T4.**

## Slice invariants (every ticket carries these forward)

1. **`opa-abac-core` stays Spring-free + zero-extra-deps.** The `CallGuard` seam and the R4j impl live in
   `opa-abac-spring-security`; the decorator wraps the existing `OpaClient` interface. **No B3 type, and
   no Resilience4j import, enters `opa-abac-core`.**
2. **Fail-closed contract identical in every breaker/config state.** Exhausted-retry **and** breaker-open
   yield the delegate's fail-closed value: `allow`→`false`, `compile`→**`PartialResult.error()`**
   (`fromError=true` — *never* `denyAll()`/`allowAll()`), `allowAll`→n×`false`. Pinned by a contract test
   (decorator == delegate).
3. **B2 preserved exactly.** Retry wraps *around* B2's classification; it never replaces a throw with a
   fallback. Resolve: only an **exhausted** 5xx/transient throws `RoleResolutionException`; **4xx throws
   immediately, no retry**. Tag: exhausted → `TagDefinitionFetchException` → 503.
4. **Side-effect-free retry.** All three edges are read-only; retrying (incl. read-timeout) can't
   double-execute. Any *future* mutating edge MUST opt out of retry (documented, not enforced here).
5. **"Uniform" = classification + config shape + fail-closed contract, NOT numbers.** Asymmetric per-edge
   budgets; three **per-endpoint** breakers; breaker is latency/load only, never a decision input.
6. **Per-edge kill-switch; `enabled=false` ⟺ byte-identical to pre-B3.** The switch governs retry/breaker
   only, never the fail-closed contract.
7. **Deterministic timing.** `CallGuard` exposes an injectable clock/scheduler; all retry/breaker tests use
   virtual-time — **zero `Thread.sleep`, zero wall-clock assertions.**
8. **Zero Rego change. Java 21 / Spring Boot 3.4 baseline.** The second artifact line + the load-testing
   rig are OUT of scope (Boot-4 slice / Phase 7).

## Critical path

```
T1 (CallGuard seam + R4j impl + config, spring-security)   ── the foundation; the seam + its first
  │                                                            consumer (T2) land before it has value
  ▼
T2 (resilient OpaClient decorator + starter auto-config + fail-closed-identity/conditional tests)
  │   └── headline #1: P1/P2/P5/P8 — the library feature
  ▼
T3 (app-side resolve + tag wrappers in catalog, B2-preserving)   ── depends on T1's CallGuard
  │   └── headline #2: P3 — B2 intact after retry
  ▼
T4 (e2e fault-injecting stub + headline matrix + docs/guide + slice record)
      └── headline #3: P9 — transient-recovers→succeeds, sustained→still-denies
```

- **Sequential:** T1 → T2 (decorator needs the seam) and T1 → T3 (wrappers need the seam). T4 last (proves
  the whole through the rig).
- **Parallel:** T2 and T3 are independent once T1 lands (different modules — library vs example app) and
  may be built in either order / concurrently.
- **Independently landable:** **T1+T2** alone deliver the published-library resilience feature (the OPA
  decorator, optional/conditional R4j) with full unit proof — standalone value if the window is short. T3
  adds the example edges; T4 adds the e2e headline.
- **Build-breaker watch:** T1 adds R4j as a dependency of `opa-abac-spring-security` (and an *optional*
  dependency of the starter). The starter's existing auto-config must still construct a **plain**
  `OpaClient` when R4j is absent — T2's `ApplicationContextRunner` test proves both classpath states in the
  **same** commit as the conditional wiring.

---

## T1 — `CallGuard` seam + Resilience4j impl + per-edge config (spring-security)

**Goal.** Introduce the backend-agnostic resilience seam every edge calls through, with the only B3 impl
(Resilience4j) and an injectable clock so tests are deterministic. No edge is wired yet — T1 is the
foundation T2/T3 consume.

**Deliverables** (package `dev.dmitriikonovalov.opaabac.security.resilience`, in `opa-abac-spring-security`):
- `CallGuard` — the seam interface: `<T> T call(Supplier<T> body, Predicate<Throwable> retryable)` (or
  equivalent execute-with-retry+breaker), backend-agnostic, **no Resilience4j types in the signature**.
  Javadoc names the two impls (R4j now, Boot-4 native later) and the injectable-clock contract.
- `ResilienceConfig` — an immutable per-edge config record: `maxRetries`, `backoff` (base `Duration`),
  `ceiling` (`Duration`), breaker params (`failureThreshold`, `openDuration`, `halfOpenProbes`), `enabled`.
- `Resilience4jCallGuard implements CallGuard` — wraps an R4j `Retry` + `CircuitBreaker` built from a
  `ResilienceConfig`; **takes an injectable `Clock`/scheduler** (R4j virtual-time test support) so
  backoff/breaker timing is driven in tests without sleeping. Retry honors the `retryable` predicate;
  exhaustion **re-throws the last cause** (so the caller's fail-closed mapping sees the original
  exception — load-bearing for T3's B2 mapping).
- `RetryableClassification` (a static predicate factory) — the shared classification of Fork 3: retry
  {connect-refused, connect-timeout, read-timeout, 5xx, 429}; do-not-retry {4xx≠429, malformed-200/parse}.
  Expressed against the JDK `HttpClient` exception / `HttpResponse` shapes the edges use. **Named
  consumers:** T2 (OPA decorator) and T3 (both app wrappers).

**Acceptance** (from [[10-QA-TEST-CASES]]): **U1** (retry classification table — each failure class →
retry/no-retry, attempt count asserted), **U2** (exhaustion re-throws the last cause unchanged), **U6**
(latency bound — total ≤ ceiling under virtual time), **U7** (breaker opens after threshold, half-open
probe, all under virtual time). All **virtual-time, zero `Thread.sleep`**.

**What NOT to touch.** No `opa-abac-core` change (invariant 1) — `CallGuard` is **not** in core. No edge
wired yet (that's T2/T3). No Rego. Don't leak R4j types through `CallGuard`'s signature (invariant 1/7,
the Boot-4-swap seam). The clock MUST be injectable — a hard-coded system clock fails invariant 7.

---

## T2 — Resilient `OpaClient` decorator + starter auto-config (the library feature)

**Goal.** Ship the published-library resilience feature: a resilient `OpaClient` that decorates the plain
`HttpOpaClient` through the OPA-edge `CallGuard`, auto-configured **only when Resilience4j is on the
classpath** and not disabled — fail-closed-identical to the plain client in every breaker/config state.

**Deliverables:**
- `ResilientOpaClient implements OpaClient` (package `…security.resilience`) — wraps a delegate
  `OpaClient` + the OPA-edge `CallGuard`. `allow`/`compile`/`allowAll` each run the delegate call through
  the guard with the shared retryable predicate. **On exhausted-retry AND breaker-open it returns the
  delegate's fail-closed value by hand:** `false` / **`PartialResult.error()`** / n×`false` — it owns
  these (breaker-open never calls the delegate). It is a real `OpaClient` (implements all three; no
  `default` fail-open).
- Starter wiring in `opa-abac-spring-boot-starter` (`autoconfigure` package): a
  `@Configuration` with `@ConditionalOnClass` (R4j `CircuitBreaker`) + `@ConditionalOnProperty`
  (`opa.abac.resilience.enabled`, `opa.abac.resilience.opa.enabled`, both default true) that wraps the
  primary `OpaClient` with `ResilientOpaClient`. **R4j absent OR disabled → the plain `HttpOpaClient` bean
  is used unchanged** (invariant 6).
- `OpaAbacProperties` extension: the `resilience` config tree (master + `opa`/`resolve`/`tag` sub-blocks;
  OPA defaults **1 retry / ~50ms / ~2-3s ceiling** + breaker defaults). The resolve/tag blocks are defined
  here too (consumed by T3's example wrappers via the same property names).
- R4j declared as an **optional** dependency of the starter (`optional`/`compileOnly`), and a real
  dependency of `opa-abac-spring-security`.

**Acceptance** (from [[10-QA-TEST-CASES]]): **U3** (fail-closed identity — `ResilientOpaClient` exhausted
== `HttpOpaClient` failure, all three methods; `compile`→`error()` with **`fromError==true`** asserted),
**U4** (breaker-open synthesizes the same fail-closed value **without** calling the delegate — verified via
a counting delegate), **U5** (`compile` on breaker-open is `error()` **not** `denyAll()`/`allowAll()` — the
widening landmine), **U8** (`ApplicationContextRunner`: R4j on classpath + enabled → `ResilientOpaClient`
bean; R4j absent OR `enabled=false` → plain `HttpOpaClient`, byte-identical behavior). All virtual-time.

**What NOT to touch.** `HttpOpaClient` itself is **unchanged** (the decorator wraps it; no retry logic
leaks into core — invariant 1). `OpaClient` interface unchanged (it's already the seam). No Rego. The
decorator must **never** return `denyAll()`/`allowAll()` on any failure path (invariant 2). When disabled,
behavior is byte-identical to pre-B3 (invariant 6). **Build-breaker:** the optional-R4j dependency must not
make the plain-client path fail to wire — land the `ApplicationContextRunner` both-states test (U8) in this
commit.

---

## T3 — App-side resolve + tag wrappers (catalog) — B2-preserving

**Goal.** Wrap the two example HTTP edges (`HttpRoleDefinitionSupplier`, `TagDefinitionClient`) with the
resolve/tag `CallGuard`s, retrying transients **before** their existing fail contracts fire — preserving
B2 exactly.

**Deliverables** (in `example-catalog-management-service`):
- `HttpRoleDefinitionSupplier` — its `lookup(...)` HTTP exchange runs through the **resolve** `CallGuard`.
  The retry wraps **only the transient / "else throws" branch** of B2's strict classification: a 5xx /
  timeout / connect-refused retries; on exhaustion it throws `RoleResolutionException` (unchanged
  outcome). **204 → `Optional.empty()` and 200+valid → resolved are NOT retried** (terminal,
  authoritative). **A 4xx throws immediately, no retry** (B2 invariant): the classification predicate must
  treat 4xx as non-retryable so the loop never spins on a permanent error.
- `TagDefinitionClient` — its `fetchApplicable(...)` runs through the **tag** `CallGuard`; transient →
  retry; exhausted → `TagDefinitionFetchException` → 503 (unchanged). 4xx → immediate throw, no retry.
- Both read budgets from `opa.abac.resilience.resolve.*` / `…tag.*` (defaults **2 retries / ~50ms / ~6s
  ceiling**); each has its **own** breaker (per-endpoint — invariant 5).
- A short construction comment at each wrap point: the **no-lock invariant** (these run on the request
  thread, outside any write transaction) and the **side-effect-free** retry rationale.

**Acceptance** (from [[10-QA-TEST-CASES]]): **U9** (resolve: transient ×k<budget → recovers to
resolved/empty; transient exhausted → throws `RoleResolutionException`, **OPA never reached**; **4xx →
throws after exactly 1 attempt** — assert attempt count via the in-process stub), **U10** (tag: transient
recovers → definitions; exhausted → `TagDefinitionFetchException`→503; 4xx → 1 attempt). In-process
`com.sun.net.httpserver.HttpServer` stub (no WireMock); virtual-time for budget assertions.

**What NOT to touch.** B2's HTTP classification logic is **preserved** — the only legitimate no-role
signals (204, 200+valid) stay terminal and un-retried (invariant 3). Don't retry 4xx (invariant 3). Don't
move either call inside a transaction/lock (invariant 5, no-lock). The realm fallback and
`RoleResolutionException`/`TagDefinitionFetchException` contracts are unchanged — resilience only makes the
throw rarer.

---

## T4 — e2e fault-injecting headline + docs + slice record

**Goal.** Prove the slice's reason to exist through the rig: a **transient** outage that recovers within
budget yields a **successful** request; a **sustained** outage still **denies** (B2 intact). Then write the
resilience guide and finalize the slice record.

**Deliverables:**
- A **fault-injecting upstream** for the rig: a toggleable-failure stand-in for the OPA and/or
  user-management edge (a flag/route that returns N transient failures then recovers, plus a "stay down"
  mode). **Resolve-in-T4 (00-DESIGN open note):** first check whether the existing newman / `deploy.sh`
  harness can drive a flaky upstream (an env-toggled failure count on a stub container); if not, add a
  **small** stub service to the compose rig (the smallest thing that injects N-then-recover + stay-down).
  Document whichever in `infra/README.md`.
- `scripts/postman/run-resilience-matrix.sh` + a collection asserting **P9**: (a) transient blip recovering
  within budget → the protected request **succeeds** (the cut: 200 / expected row set, not a 403);
  (b) sustained outage → the request **still denies** (403 / fail-closed — B2 wall intact, no
  realm-fallback widening). Asserts the **actual cut**, not just response shape.
- `docs/guides/HTTP-RESILIENCE.md` (new) — the mechanism: the three edges, the `CallGuard` seam, the
  asymmetric budgets, breaker outcome-invariance, the kill-switch, the optional-R4j wiring, the Boot-4-swap
  forward note. Reconcile cross-refs in [[PARTIAL-EVALUATION-FILTERING]] (the `fromError` suppression) +
  [[B2-SUPPLIER-OUTAGE]] (the wall this softens).
- Finalize STATUS notes, tick the index table, and (on ship) the folder move + roadmap flip.

**Acceptance** (from [[10-QA-TEST-CASES]]): **E1** (transient-recovers → success through APISIX), **E2**
(sustained-outage → still-denies through APISIX), **E3** (the existing matrices —
catalog/tag/team/filter/permission-categories/control-plane — stay green with resilience **on** at
defaults: B3 changes nothing on the happy path). Honor the in-network token caveat + restart-OPA-after-
rego-edit (no rego edit here, but rig hygiene applies).

**What NOT to touch.** No production-code behavior change in T4 (it's proof + docs). The matrix must assert
the **cut** (success vs deny), not merely 200-vs-non-200 shape. No load testing here (Phase 7). No second
artifact line (Boot-4 slice).

---

## Cross-cutting acceptance (the whole slice)

- **The build is green** with resilience **on** (`./gradlew build`) and the new unit suites pass:
  `:opa-abac-spring-security:test` (T1, T3 classification), `:opa-abac-spring-boot-starter:test` (T2
  conditional), example ITs (T3).
- **Fail-closed identity holds in every state** — U3/U4/U5 pin decorator == delegate; `compile` is always
  `error()` (`fromError=true`) on failure, never `denyAll()`/`allowAll()`.
- **B2 intact** — U9/U10: an exhausted outage still throws; a 4xx never retries; the headline E2 shows the
  deny wall un-breached (no realm-fallback widening on a sustained outage).
- **Kill-switch identity** — U8: `enabled=false` (or R4j absent) ⟺ pre-B3, byte-identical.
- **Deterministic** — every retry/breaker test is virtual-time; the suite has **zero `Thread.sleep`** and
  no wall-clock flakiness.
- **Boundary** — `opa-abac-core` untouched (zero new types, zero R4j); zero Rego (`opa test` count
  unchanged); Java 21 / Boot 3.4.
- **Headline tickets:** T2 (the library feature, P1/P2/P5/P8), T3 (B2 intact after retry, P3), T4 (the e2e
  soften, P9).
