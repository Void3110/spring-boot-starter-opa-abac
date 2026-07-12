---
tags:
  - status/active
  - type/decision
  - area/abac
  - area/architecture
  - area/spring
---

# ADR 0017 — Cross-service HTTP resilience (retry / backoff / circuit-break)

**Status:** Accepted (planned — Slice B3, [[B3-HTTP-RESILIENCE]])
**Date:** 2026-06-18
**Context tags:** `HttpOpaClient`, `HttpRoleDefinitionSupplier`, `TagDefinitionClient`, resilience,
retry, circuit-breaker, fail-closed, Resilience4j, `CallGuard` seam, optional integration

> This ADR pins the **resilience forks** for **Slice B3**: a uniform retry/backoff/circuit-break posture
> across all three cross-service HTTP edges, designed to **soften the outage → hard-deny wall** that
> Slice B2 (ADR [[0014-supplier-outage-error-distinct|0014]]) introduced — **without** re-opening the
> realm-role fallback B2 closed. Scope was settled in a planning interview (grill-me 2026-06-18). Slice
> order in [[POC-ROADMAP]]: **B2 → 6.7 → Phase 6 → B3 → Phase 7**; B3 is now the front of the queue.

## Context

B2 made a role-source **outage** error-distinct from an authoritative **no-role**: an outage now throws
`RoleResolutionException` and every consumer fails closed, closing the one widening-on-failure path. The
deliberate cost (ADR 0014 §Consequences): an outage is now a **hard deny wall** — every fallback-eligible
request denies for the outage's duration. That availability sharpness is *correct* (the old behavior was
serving wider-than-authorized access), but it motivates a **resilience pass** so transient blips don't
become user-visible denials. B2 explicitly scoped retry/circuit-breaking **out** to this slice (ADR 0014
§Considered-options "Add retry/circuit-breaking here", §Consequences "Slice B3").

There are **three** cross-service HTTP edges, with **three different failure contracts** that B3 must
preserve exactly:

| Edge | Where | On failure today | Module |
|---|---|---|---|
| `HttpOpaClient` (`allow`/`compile`/`allowAll`) | the authorization gate, **every request** | `false` / `PartialResult.error()` / n×`false` | **`opa-abac-core`** (Spring-free, zero-extra-deps) |
| `HttpRoleDefinitionSupplier` | role resolve, on the gate path | **throws `RoleResolutionException`** → deny (B2) | example app (catalog) |
| `TagDefinitionClient` | tag-assignment validation | **throws `TagDefinitionFetchException`** → 503 | example app (catalog) |

The hard constraint that shapes everything: **`opa-abac-core` must stay free of any Spring dependency
and ideally zero-extra-deps** (CLAUDE.md). Resilience4j and Spring-Retry cannot live in core.

## Decision

### 1. OPA-edge resilience is a **decorator over the existing `OpaClient` interface**, in the Spring layer — not a pluggable transport in core

`OpaClient` is *already* an interface (`HttpOpaClient implements OpaClient`). A resilient `OpaClient`
decorator wraps the plain `HttpOpaClient` and is auto-configured by the starter. Core is **untouched** —
no new core types, zero-dep invariant intact.

**Why not a pluggable transport in core (the "D" route).** Extracting a swappable `HttpExchange`/
transport seam *into* core was considered and rejected: its only unique benefit — letting a caller supply
a resilient transport — **is already delivered by the pre-existing `OpaClient` interface** one level up.
A lower transport seam would be redundant *and* leaky: retry classification is response-aware (retry 5xx/
timeout, fail-fast 4xx), so a "generic transport" seam would either have to understand HTTP-status-vs-
authz semantics (leaky) or be unable to decide to retry (useless). The decorate-the-interface route costs
nothing in core and keeps all hand-rolled-concurrency risk out.

The two example edges (`HttpRoleDefinitionSupplier`, `TagDefinitionClient`) are **app code** by nature (a
real adopter writes their own supplier), so their resilience is necessarily example-app code either way.

### 2. The fail-closed contract is **identical** in every config/breaker state — pinned by test, not shared code

On retries-exhausted **and** on breaker-open (where the delegate is not called at all), the decorator
yields the *same* fail-closed value the plain delegate would:

- `allow` → `false`
- `compile` → **`PartialResult.error()`** — specifically the `error()` variant (`fromError == true`),
  **never** `denyAll()` (`fromError == false`) and **never** `allowAll()`. This is load-bearing: the
  `fromError` flag is what suppresses the 5.5-B hierarchy `subtreeSpec` widening composed alongside the
  residual in `AbacQueryService` (lines 179/257). A breaker-open path returning `denyAll()` would let a
  hierarchy widening survive next to it — a real fail-open hole. `allowAll()` ("match all rows") is the
  catastrophe value and is never synthesized.
- `allowAll(contexts)` → a list of `contexts.size()` × `false`

The decorator **owns** these values (breaker-open cannot consult the delegate), and a **contract test
pins decorator-fail-closed == delegate-fail-closed** for all three methods, so the two can never drift.
The decorator is a real `OpaClient` and inherits the interface's "no `default` fail-open" doctrine: it
implements all three methods, fail-closed, by hand.

### 3. Retry classification + the side-effect-free invariant

Uniform classification across all three edges:

| Failure | Retry? |
|---|---|
| connection refused / connect timeout | ✅ |
| read timeout (request sent, no response) | ✅ |
| 5xx | ✅ |
| 429 | ✅ (honor `Retry-After` when present) |
| 4xx (except 429) | ❌ fail fast |
| malformed-200 body (parse failure) | ❌ fail fast |

> **Side-effect-free invariant.** All three B3 edges are **read-only evaluations/queries** (OPA decisions
> are server-side-stateless; resolve and tag are GETs). Retrying any of them — **including after a
> read-timeout** — cannot cause a duplicate side effect. Despite the OPA calls being POSTs (non-idempotent
> by HTTP), they are *effectively idempotent*. **Any future edge that mutates state MUST opt out of retry.**

For the **role supplier**, retry slots *before* B2's throw: a 5xx now retries, and only an **exhausted**
5xx throws `RoleResolutionException`; a 4xx still throws **immediately, no retry**. B2's contract is
preserved — resilience only makes the throw *rarer*, never replaces it with a fallback.

### 4. Latency: asymmetric per-edge budgets, and no resilience under a DB lock

"Uniform posture" means **uniform classification + config shape + fail-closed contract — NOT uniform
numbers.** Per-edge defaults (all knobs configurable):

| Edge | Retries | Backoff base | Total ceiling | Rationale |
|---|---|---|---|---|
| OPA (gate, every request, local sidecar) | **1** | ~50ms | ~2–3s | failure ≈ restart blip, not network weather; the breaker handles sustained outage |
| resolve (`HttpRoleDefinitionSupplier`) | **2** | ~50ms | ~6s | cross-service hop, real transient weather, not every request |
| tag (`TagDefinitionClient`) | **2** | ~50ms | ~6s | same cross-service profile |

Backoff is exponential with **full jitter**. The total-time **ceiling is a named, configurable bound** so
retry latency can never surprise the gate path.

> **No-lock invariant.** No B3 resilience-wrapped call executes while a pessimistic DB lock is held. The
> team-row `FOR UPDATE` (`MembershipService.findByIdForUpdate`) is **server-side in user-management**,
> which makes **no** outbound B3 edge; the catalog edges run on the request thread, outside any write
> lock. Any future code calling a resilience-wrapped edge inside an open write transaction MUST be flagged
> in review (a 6s retry pinning a `FOR UPDATE` lock would cascade into a lock-wait pileup).

### 5. Circuit breaker: three breakers, one per edge — latency/load only, never a decision input

**Three breakers**, one per client/edge (**per-endpoint, not per-host**): one for OPA (shared across
`allow`/`compile`/`allowAll` — same OPA server), one for resolve, one for tag. Resolve and tag both call
the user-management host but keep **independent** breakers, so slowness/a bug in `/internal/tag-definitions`
cannot trip `/internal/effective-role`.

> **Breaker outcome-invariance (hard invariant).** The breaker is a **latency/load optimization over the
> fail-closed path, never a decision input.** Every breaker state — closed, open, half-open — yields an
> outcome **already reachable without the breaker**. An open breaker is *strictly more* fail-closed, never
> less: open OPA breaker → `allow` false / `compile` `error()` / `allowAll` all-false; open resolve breaker
> → throw `RoleResolutionException`; open tag breaker → throw `TagDefinitionFetchException`. The breaker
> changes *when* and *how fast* we fail closed, never *whether* the answer is fail-closed.

### 6. Library ships OPA resilience via **optional/conditional Resilience4j**

The published starter offers the resilient `OpaClient` decorator, auto-configured **`@ConditionalOnClass`
Resilience4j** — R4j is an **optional** starter dependency. An adopter who adds R4j (and the config) gets
retry/breaker on OPA calls; an adopter who does not gets today's plain `HttpOpaClient`, unchanged. This is
the standard Spring Boot "optional integration" pattern — the library *offers* resilience without
*forcing* R4j on every adopter, keeping the lean-starter promise.

The **example app** turns it on (adds R4j, sets config), so the rig demonstrates the real feature; the
resolve/tag clients use the same R4j for their (necessarily app-side) resilience. Net: **same R4j, same
knobs, same config shape across all three edges** — an honest "uniform posture," with the library
*optionally* providing the OPA decorator and the example providing the resolve/tag ones.

### 7. A thin `CallGuard` seam — backend-agnostic, R4j-backed today, native-Spring-backed later

The three edges depend on a thin **internal `CallGuard` abstraction** (≈2–3 methods: *execute a supplier
with retry + breaker*, *classify an exception as retryable*, with an **injectable clock/scheduler**), not
on Resilience4j directly. B3 ships **only** the R4j-backed `CallGuard` impl, on the current Java 21 /
Spring Boot 3.4 baseline.

The seam exists because a **Spring Framework 7 / Spring Boot 4** native resilience stack is coming
(`@Retryable` + `@ConcurrencyLimit` + `RetryTemplate`, **zero external deps**,
`@EnableResilientMethods` — [Spring Framework Resilience docs](https://docs.spring.io/spring-framework/reference/core/resilience.html)).
With the seam, that future backend is a **one-impl swap** (a `NativeCallGuard` behind the same interface),
not a three-edge rewrite. The injectable clock is *also* what makes the resilience tests deterministic
(§Proof). The seam's design is therefore a **pinned, known requirement**, not speculative future-proofing.

> **Addendum (2026-07-12, SB4-port T3).** On R4j 2.2.0 the Clock injection required the
> `internal.CircuitBreakerStateMachine(name, config, clock)` constructor — an accepted, contained
> internal coupling (one construction line in `Resilience4jCallGuard`). **Eliminated as of R4j
> 2.4.0**: upstream removed that constructor and the seam is now pure public API —
> `CircuitBreakerConfig.Builder.clock(Clock)` (public since 2.3.0) plus `CircuitBreaker.of(name,
> config)`. Same virtual-time testability; no `internal.*` import remains.

### 8. Keep both versions — design the seam now, **don't build the second line in B3**

The intended end state keeps **two baselines indefinitely** — Java 21 / Spring Boot 3.x (R4j-backed) *and*
Java 25-26 / Spring Boot 4 (native-backed) — because some adopters are pinned to Java 21 and cannot
migrate. The **probable shape is two separately-compiled artifact lines** (e.g. `opa-abac-*:1.x` on
Java 21, `opa-abac-*:2.x` on Java 25), each compiling cleanly against its own baseline — *not* one
runtime-adaptive jar with a reflection bridge.

**B3 builds none of that.** B3 ships the backend-agnostic seam + the R4j impl on Java 21 / Boot 3.4. The
second line, and the final one-artifact-vs-two-lines decision, are deferred to the Boot-4 slice, **with
Boot 4 actually in hand** — designing the dual-line machinery before feeling the migration would be
speculative. Because the future is two separately-compiled lines (not a runtime switch), the seam is a
**source-level** maintainability boundary; it needs no reflection-bridge or runtime backend-detection. The
Fork-6 `@ConditionalOnClass(R4j)` wiring is B3's *own* optional-R4j mechanism on the 3.x line, not a
cross-version switch.

### 9. A per-edge kill-switch — the principled inverse of B2's no-kill-switch

B2 shipped **no** kill-switch because *its* off-position **is** the vulnerability (re-enabling swallow-all
re-opens the widening hole). **B3 is the opposite case and therefore ships one.** Apply ADR 0014's own
test: *does the off-position revert to a safe baseline (✅ ship the switch) or to the vulnerability (❌
don't)?* B3 disabled = a one-shot call that **fails closed exactly as pre-B3** — a *safe baseline*,
exactly like the Phase-5 (`partialEval.enabled`) and 5.97 (`resource-resolution.enabled`) switches.
Disabling B3 makes outages *more frequent*, **never less fail-closed**.

Config surface — **per-edge** (matching the asymmetric budgets):

```
opa.abac.resilience.enabled                 # master, default true
opa.abac.resilience.opa.enabled             # per-edge, default true
opa.abac.resilience.opa.max-retries         # default 1
opa.abac.resilience.opa.backoff             # default 50ms
opa.abac.resilience.opa.ceiling             # default ~2-3s
opa.abac.resilience.opa.breaker.*           # failure-threshold, open-duration, half-open-probes
# resolve.* and tag.* — identical shape; defaults 2 / 50ms / ~6s
```

> **Kill-switch invariant.** `resilience.enabled=false` ⟺ the plain delegate (today's `HttpOpaClient` /
> supplier / tag client), **byte-identical to pre-B3**. The switch governs **retry/breaker only — never
> the fail-closed contract**, which holds in *every* config state.

## Proof obligations

| # | Invariant (fork) | Test | Level |
|---|---|---|---|
| P1 | Fail-closed identity (§2): decorator exhausted/open == delegate, all 3 OPA methods | contract test (assert `compile`→`error()` with `fromError=true`) | unit |
| P2 | `compile` → `error()` not `denyAll()`/`allowAll()` on breaker-open (the widening landmine) | assert `fromError==true` on breaker-open | unit |
| P3 | B2 preserved (§3): resolve exhausted-5xx → still throws `RoleResolutionException`; **tag** exhausted → still throws `TagDefinitionFetchException` → 503; 4xx on either → throws **immediately, no retry** | unit, assert attempt count | unit |
| P4 | Retry classification (§3): 5xx/timeout/connect-refused retry; 4xx/malformed-200 don't | parameterized per failure type | unit |
| P5 | Breaker outcome-invariance (§5): open breaker == real-failure outcome, all 3 edges | force-open → assert outcome | unit |
| P6 | Latency bound (§4): total ≤ ceiling; budget not exceeded | unit with **virtual clock / injectable scheduler** | unit |
| P7 | Kill-switch identity (§9): `enabled=false` ⟺ pre-B3 (1 attempt, same value) | unit | unit |
| P8 | Optional-R4j conditional (§6): R4j absent → plain client; present → decorated | `ApplicationContextRunner` slice test | unit |
| P9 | Headline soften: transient outage recovering **within budget** → request **succeeds**; sustained outage → still **denies** | fault-injecting stub through the gateway | e2e |

> **Deterministic-timing mandate.** All retry/backoff/breaker tests use **virtual-time / programmatic
> state transitions — zero `Thread.sleep`, zero wall-clock assertions.** The `CallGuard` seam **exposes
> the clock/scheduler as injectable** — a design constraint on the seam (§7), so testability is built in,
> not retrofitted. (Timing-flaky tests are the #2 autonomous-run pause cause; pre-resolving the strategy
> here is deliberate.)

## Considered options

| Option | Why not |
|--------|---------|
| **Pluggable transport seam in `opa-abac-core` (route "D")** | Its only unique benefit (swappable resilient transport) is already provided by the pre-existing `OpaClient` interface decorated one level up; a lower seam is redundant and leaks response-aware retry classification across the boundary. |
| **Hand-rolled retry + breaker in `HttpOpaClient`** | Keeps core zero-dep, but resurrects exactly the subtly-wrong concurrency code (half-open races, failure-window bookkeeping) that a battle-tested library exists to prevent — and nobody runs `HttpOpaClient` outside Spring in this repo, so core self-resilience is YAGNI. |
| **Resilience4j as a hard starter dependency** | Forces R4j on every adopter and grows the lean-starter's transitive deps; the optional `@ConditionalOnClass` integration offers the feature without imposing the dep. |
| **Depend on Resilience4j directly (no seam)** | A known Boot-4 native-resilience migration is coming and two baselines are intended; without the seam the migration is a three-edge rewrite, the recurring avoidable-rework the autonomous-run retro flags. |
| **Build the dual-line (Java 21 + Java 25) machinery in B3** | Speculative before the Boot-4 migration is felt; the seam future-proofs at no cost while the second line is decided later with Boot 4 in hand. |
| **No kill-switch (mirror B2)** | B2's off-position is the vulnerability; B3's off-position is a *safe baseline* (one-shot, fail-closed). The principled rule is "off reverts to safe ⇒ ship the switch." |
| **One uniform retry budget for all edges** | Either over-retries the hot OPA gate (lengthening the deny wall B3 exists to soften) or under-retries the cross-service hops; asymmetric per-edge budgets fit each profile. |
| **Synthesize `denyAll()`/empty residual on breaker-open** | `denyAll()` has `fromError==false`, so a 5.5-B hierarchy widening could survive next to it — a fail-open hole. Only `error()` (`fromError==true`) is safe. |
| **Load-testing rig inside B3** | Entangles "is the contract correct" (B3's job, deterministic tests) with "are the numbers right" (empirical tuning); deferred to Phase 7 polish. |

## Consequences

- **Good:** the B2 deny wall is softened — transient blips (pod restarts, GC pauses, brief network weather)
  recovering within budget no longer surface as denials, while sustained outages **still** fail closed
  (B2 intact). The breaker sheds load off a known-dead dependency and fails fast (~0ms) instead of waiting
  out the full retry budget. The fail-closed contract is now a *documented, tested* property in every
  config and breaker state.
- **Cost / sharp edges:** R4j enters the example app and, optionally, the published starter (conditional).
  The `CallGuard` seam adds one small interface + one impl wrapper — paid for by a one-file Boot-4
  migration instead of a three-edge one. Retry adds bounded latency on the gate path (capped by the
  per-edge ceiling), accepted as the price of softening the deny wall.
- **Additivity / safety:** `opa-abac-core` is **untouched** (zero new core types, zero-dep intact). Zero
  Rego. With R4j absent or `resilience.enabled=false`, behavior is **byte-identical to pre-B3**. The only
  behavior change is *fewer outages reach the fail-closed path* — never a wider grant.
- **Forward-looking:**
  - **Two baselines / two artifact lines** (Java 21 / Boot 3 R4j · Java 25-26 / Boot 4 native) — the seam
    is designed for it; the second line + the final artifact-shape decision land at the **Boot-4 slice**
    (§8). The Spring 7 / Boot 4 native-resilience stack is the intended second backend.
  - **Load-testing rig + empirical budget/breaker-threshold tuning** (p99 under a partial outage) is
    deferred to **Phase 7 polish**, joining the already-parked OPA-restart-hygiene + CI-runs-e2e items.

## Related

- ADR [[0014-supplier-outage-error-distinct|0014]] — the B2 fix B3 softens; its §Consequences hands off
  the resilience pass to B3, and its kill-switch reasoning (off = vuln) is the inverse of B3's (off = safe).
- ADR [[0013-attribute-rich-pre-authorization|0013]] — the realm-fallback semantics both B2 and B3
  protect; the "throws → deny" split-fail-closed precedent.
- ADR [[0010-hierarchy-aware-list-filter|0010]] — the 5.5-B `subtreeSpec` widening that `compile`'s
  `fromError` flag must suppress on an OPA outage (why breaker-open returns `error()`, not `denyAll()`).
- ADR [[0005-partial-eval-to-jpa-specification|0005]] — `PartialResult` and the `error()` vs `denyAll()`
  vs `allowAll()` boundary B3's `compile` fail-closed value relies on.
- [[B3-HTTP-RESILIENCE]] — the slice (00-DESIGN, the behavior matrix, the proof obligations) ·
  [[POC-ROADMAP]] (the route box and the B3 row).
