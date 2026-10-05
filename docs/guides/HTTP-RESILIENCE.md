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
>
> **Since 1.4.0 ([[0037-indeterminate-decision-distinct-from-deny|ADR 0037]]).** An exhausted outage still
> fails closed, but it no longer *poses as a deny*: the OPA edge throws `PolicyEngineException` and a
> role-source outage stays `RoleResolutionException` all the way out, so the HTTP answer is **503
> `DEPENDENCY_UNAVAILABLE`** ("not now") instead of 403 ("no"). The OPA decorator now retries **thrown faults
> only** — a genuine deny is called exactly once — and its breaker counts real faults. The sections below
> describe the 1.4.0 behaviour; the B3-era sentinel retry is kept as history where it explains a choice.

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
| `HttpOpaClient` (`allow`/`compile`/`allowAll`) | the gate, **every request** | **throws `PolicyEngineException`** (with its `Kind`) → 503 — since 1.4.0; before: `false` / `PartialResult.error()` / n×`false` | the **library** (a decorator, optional R4j) |
| `HttpRoleDefinitionSupplier` (resolve) | role resolve, on the gate path | **throws `RoleResolutionException`** → 503 since 1.4.0 (a 403 deny before) — never the realm fallback | the **example app** (a wrapper) |
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

- **Two predicates** because the resolve and tag edges classify an HTTP failure *after* a successful
  `send()` (a 5xx is a normal response object; only a transport fault throws). The OPA edge uses only the
  exception predicate since 1.4.0 — its client throws every failure, status included, as a
  `PolicyEngineException`, and passes a result predicate that never retries a value.
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
> `ResilientOpaClient` included). Until 1.4.0 that meant a gate *deny* cost one extra fast sidecar hop,
> because the OPA-edge guard retried the fail-closed `false`; since ADR 0037 a deny is called exactly once.

> **Breaker outcome-invariance.** The breaker is a load/availability optimization over the fail-closed path,
> **never a decision input.** Every state — closed, open, half-open — yields an outcome *already reachable
> without the breaker*. An open breaker is *strictly more* fail-closed, never less: open OPA → throw
> `PolicyEngineException` of kind `CIRCUIT_OPEN` (since 1.4.0; it synthesized `false` / `error()` /
> all-false before); open resolve → throw `RoleResolutionException`; open tag → throw
> `TagDefinitionFetchException`. It changes *when* and *how fast* we fail closed, never *whether* the answer
> is fail-closed.

> **What opens a breaker: a thrown fault, never a returned value.** A breaker counts a failure **only on a
> thrown exception** — never on a returned *value*, which on the OPA edge is always a decision. This is
> load-bearing for "never a decision input": counting a returned deny would let a stream of legitimate
> denials self-open the OPA breaker and then refuse otherwise-allowable requests. Until 1.4.0 the plain
> `HttpOpaClient` swallowed every fault into a deny value, so the **OPA breaker was effectively a no-op**;
> since ADR 0037 it throws `PolicyEngineException` for every failure, and the OPA breaker **counts real
> faults — exactly the ones it retries**: `TRANSPORT`, `TIMEOUT`, 5xx, 429. A fail-fast kind
> (`UNDEFINED_DECISION`, `EVALUATION_ERROR`, `MALFORMED_RESPONSE`, a 4xx, `INTERRUPTED`) answers "could not
> decide" for that call and never opens the breaker. One breaker serves every type and all four methods, and a
> fail-fast fault is often local — a package that loads late for one type, an enrichable type with no `bulk`
> rule, one product whose data makes a rule produce two outputs (OPA's `500` with `eval_*` codes) — so
> counting it would let one type's defect refuse every healthy type; and a fault that is never retried
> costs no latency for the breaker to shed. The decorator passes its retry predicate as the guard's
> `recordableError`; the resolve and tag edges keep counting every thrown failure.

> **The OPA breaker is live for the first time in 1.4.0 — mind its window.** With the defaults below
> (`failure-threshold` 5, one retry per call) roughly three fast-failing requests open it — a refused
> connection or a 5xx records two attempts per request; a timeout records one, since the default 5 s timeout
> outlasts the 2.5 s retry ceiling, so it takes five — and every OPA-backed call then answers `CIRCUIT_OPEN`
> (503) until a half-open probe
> succeeds, up to `open-duration` (10 s) after OPA is back. A sidecar restart that cost a few hundred
> milliseconds of 403s in 1.3.0 can now cost ~10 s of 503s under load. Tune
> `opa.abac.resilience.opa.breaker.failure-threshold` / `open-duration` to your restart profile; a measured
> default is backlog (a load ceiling with OPA killed).

## The fail-closed contract — identical in every state

On **retries-exhausted** *and* **breaker-open** (the delegate is not called at all), each edge fails closed
the *same way* the plain delegate does on a failure:

| Method / edge | Fail-closed outcome (1.4.0) |
|---|---|
| `allow` / `decide` / `compile` / `allowAll` | throws **`PolicyEngineException`** — the delegate's own kind on an exhausted retry, `CIRCUIT_OPEN` on an open breaker (`INTERRUPTED` if the backoff was interrupted). A returned value is always a policy decision. |
| resolve | throws `RoleResolutionException` |
| tag | throws `TagDefinitionFetchException` → 503 |

> **Nothing widens on a throw.** A thrown failure carries no residual, so no hierarchy `subtreeSpec` widening
> can survive next to it — the property the B3-era `error()`-not-`denyAll()` rule protected by value is now
> true by construction. (`PartialResult.error()` keeps that meaning for the one case it is still produced —
> the client *refusing* to send a compile request, e.g. an unsafe path; see
> [[PARTIAL-EVALUATION-FILTERING]].) A **contract test** pins that decorator and delegate throw the same kind
> on a sustained failure and return the same value for a decision.

### How the OPA decorator decides what to retry

The plain `HttpOpaClient` throws `PolicyEngineException` when it could not obtain a decision and returns
only what the policy answered. So `ResilientOpaClient` retries a **thrown** fault whose **kind** is
transient — `TRANSPORT`, `TIMEOUT`, or an `HTTP_STATUS` of 5xx or 429 — and fails fast on every other kind
(`MALFORMED_RESPONSE`, `UNDEFINED_DECISION`, `EVALUATION_ERROR`, 4xx, `INTERRUPTED`). The kind is the whole
classification:
never the cause chain, because a malformed body can carry the JSON parser's `IOException` and would come
back unchanged on a retry. A **returned value is never retried** — a deny, a reasoned deny, a mixed or an
all-false bulk page are real answers. (Until 1.4.0 the decorator had to retry the deny *value*, since a
swallowed failure looked exactly like it: every genuine deny paid an extra hop plus backoff, and Slice 7.3
had to exempt mixed bulk blocks after measuring ~8× enrichment latency.)

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

## Forward note — the native-resilience backend (updated at the SB4 port)

The `CallGuard` seam remains the boundary for a possible second backend: Spring Framework 7 ships
native resilience (`@Retryable`, `@ConcurrencyLimit`, `RetryTemplate`, `@EnableResilientMethods`,
zero external deps). **The SB4 port (ADR 0026) settled the packaging question the other way** — a
single Boot-4/Java-25 line, no dual 3.x/4.x baseline — and kept R4j: SF7's resilience core has
**no circuit breaker**, so the R4j-backed impl (on R4j 2.4.0, pure public API since T3) stays
necessary regardless. A `NativeCallGuard` remains backlog behind the same seam if SF7 ever grows
one. The load-testing rig shipped in Phase 7.2 (`scripts/load/`, ADR 0021).

## Related

- ADR [[0017-cross-service-http-resilience|0017]] — the structural decisions.
- ADR [[0014-supplier-outage-error-distinct|0014]] + [[B2-SUPPLIER-OUTAGE]] — the deny wall B3 softens;
  B3's kill-switch is the principled inverse of B2's.
- [[PARTIAL-EVALUATION-FILTERING]] — the `fromError` flag the OPA decorator must preserve on a breaker-open
  `compile`.
- ADR [[0005-partial-eval-to-jpa-specification|0005]] · [[0010-hierarchy-aware-list-filter|0010]] —
  `error()` vs `denyAll()`/`allowAll()` and the `subtreeSpec` widening it suppresses.
- [[CONCURRENCY-AND-LOCKING]] — the no-lock invariant.
