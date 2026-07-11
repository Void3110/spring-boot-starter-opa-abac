---
tags:
  - status/active
  - type/guide
  - area/abac
  - area/spring
  - area/opa
  - area/user-service
---

# Cross-service HTTP resilience — retry, backoff, and circuit-breaking

> Slice B3 (ADR [[0017-cross-service-http-resilience|0017]]). A uniform retry/backoff/circuit-break
> posture over the three cross-service HTTP edges, so a **transient** outage recovering within a bounded
> budget no longer surfaces as a denial — **without** re-opening the realm-role fallback Slice B2 closed.
> Resilience makes outages **rarer, never wider.** This guide is the shipped contract; the design record
> (the ten settled forks, the behavior matrix) lives in the B3-HTTP-RESILIENCE package under
> `docs/to-do/implemented/`.

## Why this slice exists

Slice B2 (ADR [[0014-supplier-outage-error-distinct|0014]], [[B2-SUPPLIER-OUTAGE]]) made a role-source
**outage** error-distinct from an authoritative **no-role** and forced every consumer to fail closed —
closing the one widening-on-failure path, but at the cost of a **hard deny wall**: during a transient blip
(a pod restart, a GC pause, brief network weather) every fallback-eligible request denied. B3 softens that
wall for *transient* failures while keeping B2's outage→deny contract exactly: an **exhausted-retry outage
still fails closed**.

## The three edges

| Edge | Where | On (exhausted) failure — unchanged by B3 | Resilience provided by |
|---|---|---|---|
| `HttpOpaClient` (`allow`/`compile`/`allowAll`) | the gate, **every request** | `false` / `PartialResult.error()` / n×`false` | the **library** (a decorator, optional R4j) |
| `HttpRoleDefinitionSupplier` (resolve) | role resolve, on the gate path | **throws `RoleResolutionException`** → deny | the **example app** (a wrapper) |
| `TagDefinitionClient` (tag) | tag-assignment validation | **throws `TagDefinitionFetchException`** → 503 | the **example app** (a wrapper) |

"Uniform posture" means **uniform classification + config shape + fail-closed contract — NOT uniform
numbers.** Each edge keeps its own asymmetric budget.

## The `CallGuard` seam

Every edge calls through a thin, backend-agnostic **`CallGuard`** (in `opa-abac-spring-security`,
`…security.resilience`): *execute a body with retry + circuit-breaker*, classifying both thrown exceptions
and **returned results** as retryable or terminal.

```java
<T> T call(Supplier<T> body, Predicate<Throwable> retryableError, Predicate<T> retryableResult);
```

- **Two predicates** because the edges classify an HTTP failure *after* a successful `send()` (a 5xx is a
  normal response object; only a transport fault throws), and the plain `HttpOpaClient` swallows failures
  and never throws.
- **Backend-agnostic** — no Resilience4j type appears in the seam. B3 ships only the
  `Resilience4jCallGuard` impl; a Spring-Framework-7 / Spring-Boot-4 native backend (`@Retryable`,
  `RetryTemplate`, `@ConcurrencyLimit`, zero external deps) is a later **one-impl swap**, not a three-edge
  rewrite.
- **Injectable clock + sleeper** so every retry/backoff/breaker test runs at virtual time — zero
  `Thread.sleep`, zero wall-clock assertions.

## Retry classification

| Failure | Retry? | Why |
|---|---|---|
| connection refused / connect timeout | ✅ | the server is starting/restarting |
| read timeout (request sent, no response) | ✅ | safe — every B3 edge is read-only |
| 5xx | ✅ | transient server-side |
| 429 | ✅ | backpressure |
| 4xx (≠429) | ❌ fail fast | permanent — a bug / contract violation |
| malformed-200 body (parse failure) | ❌ fail fast | deterministic — the same bad body returns |

> **Side-effect-free invariant.** All three edges are read-only (OPA decisions are server-side-stateless;
> resolve and tag are GETs), so retrying — *including after a read-timeout* — cannot double-execute. **Any
> future edge that mutates state MUST opt out of retry.**

## Asymmetric per-edge budgets

| Edge | Retries | Backoff base | Ceiling | Rationale |
|---|---|---|---|---|
| OPA (gate, every request, local sidecar) | **1** | ~50ms | ~2.5s | failure ≈ a restart blip; the breaker handles a sustained outage; more retries lengthen the deny wall |
| resolve | **2** | ~50ms | ~6s | a cross-service hop, real transient weather, not every request |
| tag | **2** | ~50ms | ~6s | same cross-service profile |

Backoff is exponential with **full jitter**; the total-time **ceiling is a named, configurable bound**.

> **No-lock invariant.** No resilience-wrapped call runs while a pessimistic DB lock is held — the team-row
> `FOR UPDATE` is server-side in user-management (makes no outbound B3 edge), and `TagAssignmentService` is
> not `@Transactional`. The catalog edges run on the request thread, outside any write transaction. A future
> resilience-wrapped call inside an open write transaction MUST be flagged in review (a 6s retry pinning a
> `FOR UPDATE` lock would cascade into a lock-wait pileup). See [[CONCURRENCY-AND-LOCKING]].

## Circuit breakers — latency/load only, never a decision input

**Three breakers, one per edge** (per-endpoint, not per-host): resolve and tag both hit the user-management
host but stay independent, so a fault in `/internal/tag-definitions` cannot trip `/internal/effective-role`.

> **The Slice-7.3 request memo sits OUTSIDE the guard** ([[0023-request-scoped-resolution-memoization|ADR
> 0023]]): the decoration order is `memo(app supplier(CallGuard inside))`, so a memo hit never touches the
> guard — the resolve breaker samples **real calls only** (at most one per key per request), strictly fewer
> breaker events with no semantic change. A memoized outage replays as the outage *without* re-hammering a
> struggling source through the guard. The **batch** resolve (`lookupAll`, [[0024-batch-role-resolution|ADR
> 0024]]) rides the same resolve guard as **one guarded call** — one breaker event per page instead of one
> per row; the whole exchange is the retry unit (safe: a read-only GET on the request thread). Since 7.3
> the method-security advisor also resolves its manager lazily, so the gate path genuinely shares these
> decorated beans (an eagerly-injected manager used to skip every bean-level wrapper, the OPA edge's
> `ResilientOpaClient` included — with the side effect that a gate *deny* now costs the documented one
> extra fast sidecar hop, because the OPA-edge guard deliberately retries the fail-closed `false`).

> **Breaker outcome-invariance.** The breaker is a load/availability optimization over the fail-closed path,
> **never a decision input.** Every state — closed, open, half-open — yields an outcome *already reachable
> without the breaker*. An open breaker is *strictly more* fail-closed, never less: open OPA → `allow`
> false / `compile` `error()` / `allowAll` all-false; open resolve → throw `RoleResolutionException`; open
> tag → throw `TagDefinitionFetchException`. It changes *when* and *how fast* we fail closed, never
> *whether* the answer is fail-closed.

> **What opens a breaker: a thrown fault, never a returned sentinel.** A breaker counts a failure **only on
> a thrown `retryableError`** — an unambiguous transport/timeout fault — never on a returned fail-closed
> *value*. This is load-bearing for "never a decision input": on the OPA edge the only failure signal is the
> returned sentinel (`false` / `error()` / all-false), which is *indistinguishable from a genuine policy
> deny*; counting it would let a stream of legitimate denials self-open the OPA breaker and then force-deny
> otherwise-allowable requests. So the **resolve/tag breakers open** (those edges surface a real outage as a
> thrown `Transient*Exception`), while the **OPA breaker is effectively a no-op** (the plain `HttpOpaClient`
> swallows every fault into the sentinel and never throws). That is the honest price of a swallow-everything
> delegate — the OPA edge gets retry-driven transient recovery without a decision-driven breaker. A breaker
> the OPA edge *could* legitimately open would require the delegate to surface faults distinctly from
> denies, which it deliberately does not (fail-closed-by-construction).

## The fail-closed contract — identical in every state

On **retries-exhausted** *and* **breaker-open** (the delegate is not called at all), each edge yields the
*same* value the plain delegate would on a failure:

| Method / edge | Fail-closed value |
|---|---|
| `allow` | `false` |
| `compile` | **`PartialResult.error()`** — `fromError == true`, **never** `denyAll()` (`fromError == false`) and **never** `allowAll()` |
| `allowAll(n)` | `n` × `false` |
| resolve | throws `RoleResolutionException` |
| tag | throws `TagDefinitionFetchException` → 503 |

> **The `error()`-not-`denyAll()` distinction is load-bearing.** `fromError` is what suppresses the 5.5-B
> hierarchy `subtreeSpec` widening composed alongside the residual (see
> [[PARTIAL-EVALUATION-FILTERING]] §the-from-error-flag). A breaker-open path returning `denyAll()` would
> let a hierarchy widening survive an OPA outage — a real fail-open hole. `allowAll()` ("match all rows") is
> the catastrophe value, never synthesized. A **contract test** pins decorator-value == delegate-value for
> all three OPA methods.

### How the OPA decorator detects a failure to retry

The plain `HttpOpaClient` never throws — it swallows failures into the fail-closed value. So the
`ResilientOpaClient` decorator retries on the **returned sentinel**: `compile` on `fromError()` (the exact
failure flag, distinct from a real `denyAll()`); `allow` on `false`; `allowAll` on any-`false`. A genuine
policy deny *also* retries — accepted because the OPA gate is a local sidecar at 1 retry / ~50ms and an OPA
decision is deterministic (a real deny stays `false`, never widens): one extra fast hop on a deny, fully
fail-closed, while a transient blip recovers the real answer.

### How the resolve/tag wrappers preserve B2

The wrappers retry the *transient subset* **before** B2's throw fires. B2's strict classification is
**unchanged**: `204`→empty and `200`+valid→resolved stay **terminal, un-retried**; a `4xx` (and a
`200`-blank / malformed-`200`) is **permanent — thrown immediately, no retry**; only an **exhausted**
transient throws `RoleResolutionException` / `TagDefinitionFetchException`. Retry only slots ahead of the
throw — it never replaces a throw with a fallback, so the realm fallback is never reached on an outage.

## Configuration — the per-edge kill-switch

B3 ships a kill-switch (the principled inverse of B2's no-switch: B3's *off* is a **safe baseline** — a
one-shot call, fail-closed as pre-B3 — not the vulnerability).

```yaml
opa:
  abac:
    resilience:
      enabled: true            # master; off ⇒ all three edges run one-shot, byte-identical to pre-B3
      opa:                     # the gate, every request, local sidecar
        enabled: true
        max-retries: 1
        backoff: 50ms
        ceiling: 2500ms
        breaker: { failure-threshold: 5, open-duration: 10s, half-open-probes: 1 }
      resolve: { max-retries: 2, ceiling: 6s, ... }   # the cross-service hops
      tag:     { max-retries: 2, ceiling: 6s, ... }
```

> **Kill-switch invariant.** `resilience.enabled=false` (or an edge's own `enabled=false`) ⟺ the plain
> delegate, **byte-identical to pre-B3**. The switch governs retry/breaker only — **never** the fail-closed
> contract, which holds in every config state.

## How the library ships it (optional Resilience4j)

The starter auto-configures the resilient OPA decorator **`@ConditionalOnClass` Resilience4j** — R4j is an
**optional** dependency. An adopter who adds R4j (and leaves the defaults) gets retry/breaker on OPA calls;
an adopter who does not — or who disables resilience — gets today's plain `HttpOpaClient`, unchanged. The
example app turns it on and provides the (necessarily app-side) resolve/tag wrappers, so the rig
demonstrates the real feature with the *same* R4j, the *same* knobs across all three edges.

## Proof

- **Unit / integration** (deterministic, virtual time): the OPA decorator's fail-closed identity +
  breaker-open `error()` (the widening landmine) + a transient-recovers-to-success case; the resolve/tag
  wrappers' transient-recovers / exhausted-throws / 4xx-immediate / 204-200-terminal (proven by attempt
  counts); the `ApplicationContextRunner` both-classpath-states + kill-switch.
- **End-to-end** (the headline, through the gateway — `scripts/postman/run-resilience-matrix.sh`): a
  fault-injecting resolve stub (`infra/compose.resilience-stub.yaml`) returns N transient 503s then
  recovers (E1: the protected request **succeeds**) or stays down (E2: it **still denies**, 403 — B2's wall
  un-breached, no realm-fallback widening). The contrast is the slice's reason to exist.

## Forward note — the Boot-4 native backend

The `CallGuard` seam is the boundary for a future second backend: Spring Framework 7 / Spring Boot 4 ship
native resilience (`@Retryable`, `@ConcurrencyLimit`, `RetryTemplate`, `@EnableResilientMethods`, zero
external deps). Because two baselines are intended (Java 21 / Boot 3 R4j · Java 25-26 / Boot 4 native), that
backend is a `NativeCallGuard` behind the same seam — a one-impl swap, decided **with Boot 4 in hand** (a
later slice). B3 ships only the Java-21 / Boot-3.4 R4j impl. The **load-testing rig** + empirical
budget/breaker-threshold tuning is deferred to Phase 7.

## Related

- ADR [[0017-cross-service-http-resilience|0017]] — the structural decisions.
- ADR [[0014-supplier-outage-error-distinct|0014]] + [[B2-SUPPLIER-OUTAGE]] — the deny wall B3 softens;
  B3's kill-switch is the principled inverse of B2's.
- [[PARTIAL-EVALUATION-FILTERING]] — the `fromError` flag the OPA decorator must preserve on a breaker-open
  `compile`.
- ADR [[0005-partial-eval-to-jpa-specification|0005]] · [[0010-hierarchy-aware-list-filter|0010]] —
  `error()` vs `denyAll()`/`allowAll()` and the `subtreeSpec` widening it suppresses.
- [[CONCURRENCY-AND-LOCKING]] — the no-lock invariant.
