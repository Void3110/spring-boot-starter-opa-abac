---
tags:
  - status/planned
  - type/design
  - area/abac
  - area/architecture
  - area/spring
---

# B3 — Cross-service HTTP resilience — 00-DESIGN

> Phase-① settled design (grill-me 2026-06-18). Pinned by ADR
> [[0017-cross-service-http-resilience|0017]]; slice note [[B3-HTTP-RESILIENCE]]. **Ready for
> `/decompose`.** This doc carries the full decision tree, the behavior matrix, the all-edges sweep, and
> the proof obligations decomposition consumes.

## Problem

Slice B2 (ADR [[0014-supplier-outage-error-distinct|0014]]) made a role-source **outage** error-distinct
from **no-role**, closing the one widening-on-failure path — at the cost of a **hard deny wall**: every
fallback-eligible request denies for an outage's duration. B3 is the deliberate **availability** answer:
soften that wall for **transient** failures **without** weakening B2's outage→deny contract. B2 explicitly
scoped retry/circuit-breaking out to B3 (ADR 0014 §Considered-options, §Consequences).

## The three edges (and their existing fail-closed contracts — B3 preserves all three)

| Edge | Class | Path | On failure today | Module |
|---|---|---|---|---|
| OPA | `HttpOpaClient` (`allow`/`compile`/`allowAll`) | authorization gate, **every request** | `false` / `PartialResult.error()` / n×`false` | `opa-abac-core` (Spring-free, **zero-extra-deps**) |
| resolve | `HttpRoleDefinitionSupplier` | role resolve, on the gate path | **throws `RoleResolutionException`** → deny (B2) | example app (catalog) |
| tag | `TagDefinitionClient` | tag-assignment validation | **throws `TagDefinitionFetchException`** → 503 | example app (catalog) |

**Constraint that shapes everything:** `opa-abac-core` must stay Spring-free and ideally zero-extra-deps
(CLAUDE.md). Resilience4j / Spring-Retry cannot live in core.

## The ten settled forks

### Fork 1 — OPA-edge resilience placement: decorator over `OpaClient`, in the Spring layer

`OpaClient` is **already an interface** (`HttpOpaClient implements OpaClient`). A resilient `OpaClient`
**decorator** wraps the plain `HttpOpaClient`; the starter auto-configures it. **Core is untouched** — no
new core types, zero-dep intact.

**Rejected — a pluggable transport seam in core (route "D").** Its only unique benefit (swap in a resilient
transport) is *already* delivered by the pre-existing `OpaClient` interface decorated one level up. A lower
transport seam is redundant **and** leaks: retry classification is response-aware (5xx-vs-4xx), so a
"generic transport" would either understand authz semantics (leaky) or be unable to decide to retry
(useless). `D1` (extract a private `send()` helper) is just an internal cleanup of A; `D2` (a real
transport interface) collapses toward this decorator because the resilient impl uses R4j and lives in the
Spring layer anyway.

The two example edges are app code by nature (an adopter writes their own supplier) — their resilience is
necessarily example-app code either way. **The only "library vs example" choice is the OPA edge** (Fork 6).

### Fork 2 — Fail-closed contract: identical in every config/breaker state, pinned by test

On **retries-exhausted** and on **breaker-open** (delegate not called at all):

| Method | Fail-closed value | Note |
|---|---|---|
| `allow` | `false` | |
| `compile` | **`PartialResult.error()`** | `fromError == true` — **never** `denyAll()` (`fromError==false`) or `allowAll()` |
| `allowAll(ctx)` | list of `ctx.size()` × `false` | |

The **`error()`-not-`denyAll()` distinction is load-bearing**: `fromError` is what `AbacQueryService`
(lines 179/257) reads to suppress the 5.5-B hierarchy `subtreeSpec` widening composed alongside the
residual. A breaker-open path returning `denyAll()` would let a hierarchy widening survive an OPA outage —
a real fail-open hole. `allowAll()` ("match all rows") is the catastrophe value, never synthesized.

The decorator **owns** these values (breaker-open can't consult the delegate). **One source of truth by
test:** a contract test asserts decorator-fail-closed == delegate-fail-closed for all three methods. The
decorator is a real `OpaClient` and honors the interface's "no `default` ⇒ no silent fail-open"
doctrine — it implements all three by hand.

### Fork 3 — Retry classification + the side-effect-free invariant

| Failure | Retry? | Why |
|---|---|---|
| connection refused / connect timeout | ✅ | server starting/restarting (the OPA-restart-hygiene case) |
| read timeout (sent, no response) | ✅ | safe — see invariant |
| 5xx | ✅ | transient server-side |
| 429 | ✅ (honor `Retry-After`) | backpressure |
| 4xx (≠429) | ❌ fail fast | permanent — a bug/contract violation |
| malformed-200 (parse failure) | ❌ fail fast | deterministic — same bad body returns |

> **Side-effect-free invariant (PIN).** All three B3 edges are **read-only evaluations/queries** (OPA
> decisions are server-side-stateless; resolve and tag are GETs). Retrying any of them — **including after
> a read-timeout** — cannot cause a duplicate side effect. The OPA calls are POSTs (HTTP-non-idempotent)
> but *effectively idempotent*. **Any future edge that mutates state MUST opt out of retry.**

**Interaction with B2 (resolve edge):** retry slots **before** B2's throw. A 5xx now retries; only an
**exhausted** 5xx throws `RoleResolutionException`. A 4xx still throws **immediately, no retry**. B2's
strict HTTP classification (only 204→empty, only 200+valid→resolved, else throw) is *unchanged* — B3 only
inserts a bounded retry loop ahead of the "else throw".

### Fork 4 — Latency: asymmetric per-edge budgets; no resilience under a DB lock

"Uniform posture" = **uniform classification + config shape + fail-closed contract, NOT uniform numbers.**

| Edge | Retries | Backoff base | Total ceiling | Rationale |
|---|---|---|---|---|
| OPA (gate, every request, local sidecar) | **1** | ~50ms | ~2–3s | failure ≈ restart blip; the breaker handles sustained outage; more retries lengthen the deny wall |
| resolve | **2** | ~50ms | ~6s | cross-service hop, real transient weather, not every request |
| tag | **2** | ~50ms | ~6s | same cross-service profile |

Exponential backoff + **full jitter**; the total-time **ceiling is a named, configurable bound**.

> **No-lock invariant (PIN).** No B3 resilience-wrapped call runs while a pessimistic DB lock is held.
> Verified: the team-row `FOR UPDATE` (`MembershipService.findByIdForUpdate`) is **server-side in
> user-management**, which makes **no** outbound B3 edge; `TagAssignmentService` (which calls
> `fetchApplicable`) is **not** `@Transactional`. The catalog edges run on the request thread, outside any
> write lock. Future code calling a resilience-wrapped edge inside an open write transaction MUST be
> flagged in review.

### Fork 5 — Circuit breaker: three breakers, one per edge; latency/load only

**Three breakers**, **per-endpoint not per-host**: OPA (shared across allow/compile/allowAll — one OPA
server), resolve, tag. Resolve and tag both hit the user-mgmt host but stay independent, so a fault in
`/internal/tag-definitions` can't trip `/internal/effective-role`.

> **Breaker outcome-invariance (HARD INVARIANT).** The breaker is a **latency/load optimization over the
> fail-closed path, never a decision input.** Every state — closed, open, half-open — yields an outcome
> **already reachable without the breaker**: open OPA → `allow` false / `compile` `error()` / `allowAll`
> all-false; open resolve → throw `RoleResolutionException`; open tag → throw `TagDefinitionFetchException`.
> Open = strictly *more* fail-closed, never less.

### Fork 6 — Library vs example: OPA resilience shipped via optional/conditional R4j

The starter offers the resilient OPA decorator, auto-configured **`@ConditionalOnClass` Resilience4j** —
R4j is an **optional** starter dependency. R4j absent (or `enabled=false`) → today's plain `HttpOpaClient`.
The **example app** adds R4j + config (demonstrating the feature) and uses the same R4j for the (app-side)
resolve/tag wrappers. **Same R4j, same knobs, same config shape** across all three — honest "uniform
posture," library *optionally* providing the OPA decorator.

### Fork 7 — A thin `CallGuard` seam: backend-agnostic, R4j-backed today

The three edges depend on an internal **`CallGuard`** (≈2–3 methods: *execute supplier with retry +
breaker*, *classify exception as retryable*, **injectable clock/scheduler**), not on R4j directly. B3 ships
**only** the R4j-backed impl. The seam exists for a **known** migration — Spring Framework 7 / Spring Boot
4 native resilience (`@Retryable`/`@ConcurrencyLimit`/`RetryTemplate`, zero external deps) — making that
backend a **one-impl swap**, not a three-edge rewrite. The injectable clock is also what makes the tests
deterministic (Proof). *Where `CallGuard` lives:* a Spring module (starter / spring-security) — **not**
core (it would pull R4j into a zero-dep module). The decorator and the app wrappers both call through it.

### Fork 8 — Keep both versions: seam now, second line later

End state keeps **two baselines** (Java 21 / Boot 3.x R4j · Java 25-26 / Boot 4 native) indefinitely — some
adopters are pinned to Java 21. **Probable shape: two separately-compiled artifact lines** (`*:1.x`,
`*:2.x`), each compiling against its own baseline — *not* one runtime-adaptive jar with a reflection
bridge. **B3 builds none of that** — only the Java-21/Boot-3.4 seam + R4j impl. Because the future is two
compiled lines (not a runtime switch), the seam is a **source-level** boundary needing no reflection/
runtime backend-detection; Fork-6 `@ConditionalOnClass(R4j)` is B3's own optional-R4j mechanism on the 3.x
line, not a cross-version switch. The second line + final artifact-shape decision = the **Boot-4 slice**.

### Fork 9 — Per-edge kill-switch: the principled inverse of B2's no-switch

B2 shipped no switch because *its* off = the vulnerability. **B3 ships one** because *its* off = a **safe
baseline** (one-shot call, fails closed as pre-B3) — like Phase-5 (`partialEval.enabled`) and 5.97
(`resource-resolution.enabled`). ADR 0014's own test: off→safe-baseline ⇒ ship; off→vuln ⇒ don't.

```
opa.abac.resilience.enabled            # master, default true
opa.abac.resilience.opa.enabled        # per-edge, default true
opa.abac.resilience.opa.max-retries    # default 1
opa.abac.resilience.opa.backoff        # default 50ms
opa.abac.resilience.opa.ceiling        # default ~2-3s
opa.abac.resilience.opa.breaker.*      # failure-threshold, open-duration, half-open-probes
# resolve.* and tag.* — identical shape; defaults 2 / 50ms / ~6s
```

> **Kill-switch invariant (PIN).** `resilience.enabled=false` ⟺ the plain delegate, **byte-identical to
> pre-B3**. The switch governs **retry/breaker only — never the fail-closed contract**, which holds in
> every config state.

### Fork 10 — Proof obligations + the deterministic-timing mandate

(see the matrix below)

## Behavior matrix

`R` = resilience on (default). Each cell is the **observed outcome**; the fail-closed value is constant
across the failure column — resilience changes *frequency and latency*, never the answer.

| Scenario | OPA `allow` | OPA `compile` | OPA `allowAll` | resolve | tag |
|---|---|---|---|---|---|
| Healthy | policy answer | residual | n booleans | `Optional.of`/`empty` | definitions |
| **Transient** blip, recovers **within budget** (R on) | **policy answer** (retried) | **residual** (retried) | **n booleans** (retried) | **resolved/empty** (retried) | **definitions** (retried) |
| Transient blip (R **off** / R4j absent) | `false` | `error()` | all-false | **throws** `RoleResolutionException` | **throws** → 503 |
| 4xx (permanent) | `false` (no retry) | `error()` (no retry) | all-false (no retry) | **throws immediately** (no retry) | **throws** → 503 (no retry) |
| **Sustained** outage, retries exhausted (R on) | `false` | **`error()`** (`fromError`) | all-false | **throws** `RoleResolutionException` | **throws** → 503 |
| **Breaker open** (R on) | `false` (fast) | **`error()`** (`fromError`, fast) | all-false (fast) | **throws** (fast) | **throws** → 503 (fast) |
| Breaker half-open, probe succeeds | policy answer | residual | n booleans | resolved/empty | definitions |

Key reads: (a) the **transient-recovers** row is the *only* place B3 changes a denial into a success — the
whole point; (b) every other failure row is **identical to pre-B3** (B2 intact, fail-closed intact); (c)
`compile` is **always `error()`** (never `denyAll()`) on the failure rows, so no hierarchy widening
survives.

## Proof obligations

| # | Invariant (fork) | Test | Level |
|---|---|---|---|
| P1 | Fail-closed identity (F2): decorator exhausted/open == delegate, 3 OPA methods | contract test — `compile`→`error()`, `fromError=true` | unit |
| P2 | `compile`→`error()` not `denyAll()`/`allowAll()` on breaker-open (widening landmine) | assert `fromError==true` | unit |
| P3 | B2 preserved (F3): resolve exhausted-5xx → throws `RoleResolutionException`; **tag** exhausted → throws `TagDefinitionFetchException`→503; 4xx on either → throws **immediately, no retry** (assert attempt count) | unit | unit |
| P4 | Retry classification (F3): 5xx/timeout/connect-refused retry; 4xx/malformed-200 don't | parameterized per failure | unit |
| P5 | Breaker outcome-invariance (F5): open == real-failure outcome, 3 edges | force-open → assert outcome | unit |
| P6 | Latency bound (F4): total ≤ ceiling; budget respected | **virtual clock / injectable scheduler** | unit |
| P7 | Kill-switch identity (F9): `enabled=false` ⟺ pre-B3 (1 attempt, same value) | unit | unit |
| P8 | Optional-R4j conditional (F6): R4j absent → plain client; present → decorated | `ApplicationContextRunner` | unit |
| P9 | **Headline:** transient outage recovering **within budget** → request **SUCCEEDS**; sustained → still **DENIES** | fault-injecting stub through the gateway (newman matrix) | e2e |

> **Deterministic-timing mandate (PIN).** All retry/backoff/breaker tests use **virtual-time / programmatic
> state transitions — zero `Thread.sleep`, zero wall-clock assertions.** `CallGuard` **exposes the
> clock/scheduler as injectable** — a design constraint on the seam (Fork 7). Timing-flaky tests are the #2
> autonomous-run pause cause; the strategy is pinned here, not discovered mid-run.

## All-edges sweep (the keystone — every edge accounted for, like B2's five-consumer sweep)

| Edge | Wrap point | Fail-closed (exhausted/open) | Retry classification source | Breaker | Kill-switch key |
|---|---|---|---|---|---|
| `HttpOpaClient.allow` | `OpaClient` decorator (starter) | `false` | shared `CallGuard` | OPA breaker | `resilience.opa.*` |
| `HttpOpaClient.compile` | `OpaClient` decorator (starter) | **`error()`** | shared `CallGuard` | OPA breaker | `resilience.opa.*` |
| `HttpOpaClient.allowAll` | `OpaClient` decorator (starter) | n×`false` | shared `CallGuard` | OPA breaker | `resilience.opa.*` |
| `HttpRoleDefinitionSupplier.lookup` | app-side wrapper (catalog) — retry **before** B2 throw | **throws `RoleResolutionException`** | shared `CallGuard` | resolve breaker | `resilience.resolve.*` |
| `TagDefinitionClient.fetchApplicable` | app-side wrapper (catalog) | **throws `TagDefinitionFetchException`** → 503 | shared `CallGuard` | tag breaker | `resilience.tag.*` |

## Out of scope (explicit)

- **Boot-4 / Java-25-26 native-resilience backend + the second artifact line** → the **Boot-4 slice**
  (the seam is designed for it; B3 ships R4j-on-Java-21 only).
- **Load-testing rig + empirical budget/breaker-threshold tuning** (p99 under a partial outage) →
  **Phase 7 polish**, with OPA-restart hygiene + CI-runs-e2e.
- **`opa-abac-core`, Rego, B2's contract, the realm fallback** — untouched.

## Open decomposition notes (for `/decompose`)

- Decide `CallGuard`'s exact home module (`opa-abac-spring-security` vs the starter) — it must be a module
  the OPA decorator *and* the example app can both depend on, and that may take R4j (so **not** core).
- The R4j config → `CallGuard` config mapping (per-edge `Retry` + `CircuitBreaker` instances) is one ticket;
  the OPA decorator + its `@ConditionalOnClass`/`@ConditionalOnProperty` auto-config is another.
- The two app-side wrappers (resolve, tag) are a ticket each (or one if they share a helper) — note the
  resolve wrapper must keep B2's *exact* HTTP classification, retrying only the "else throws" branch.
- e2e (P9) needs a **fault-injecting stub** (a toggleable-failure user-mgmt/OPA) in the rig — confirm
  whether the existing newman harness can drive a flaky upstream or whether a small stub is needed.
