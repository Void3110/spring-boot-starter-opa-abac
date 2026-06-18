---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/spring
---

# B3 — Cross-service HTTP resilience — QA test cases

> Concrete cases; each becomes a ticket's *Acceptance*. **U** = unit, **I** = integration (Testcontainers
> Postgres — never H2; in-process `com.sun.net.httpserver.HttpServer` stub — no WireMock), **E** = e2e
> (asserts the actual **cut** — success vs deny — not just response shape). Maps to proof obligations
> P1–P9 in [[00-DESIGN]]. **Deterministic-timing mandate:** every retry/breaker case uses virtual-time /
> programmatic state transitions — **zero `Thread.sleep`, zero wall-clock assertions**.

## Unit (U*)

| ID | Case | Asserts | Proof | → Ticket |
|---|---|---|---|---|
| **U1** | `RetryableClassification` over each failure class: connect-refused, connect-timeout, read-timeout, 5xx, 429, 4xx≠429, malformed-200/parse | retry set = {refused, connect-timeout, read-timeout, 5xx, 429}; no-retry = {4xx≠429, malformed}; attempt counts match (retryable → `maxRetries+1` attempts, non-retryable → exactly 1) | P4 | T1 |
| **U2** | `Resilience4jCallGuard`: a retryable failure exhausts the budget | the **last cause is re-thrown unchanged** (the caller's fail-closed mapping sees the original exception, not an R4j wrapper) | P3 (enabler) | T1 |
| **U3** | Fail-closed identity: `ResilientOpaClient` exhausted-retry vs `HttpOpaClient` on the same total failure, all three methods | `allow`→`false`; `compile`→`PartialResult.error()` with **`fromError()==true`**; `allowAll(n)`→ n×`false` — decorator value **==** delegate value | P1 | T2 |
| **U4** | Breaker **open**: force the OPA breaker open, call all three methods, delegate is a **counting** stub | the fail-closed value is produced **without** invoking the delegate (call count = 0); value identical to U3 | P5 | T2 |
| **U5** | `compile` on breaker-open / exhausted is `error()`, **never** `denyAll()`/`allowAll()` (the widening landmine) | `decision==DENY_ALL` **and** `fromError==true`; explicitly `!= denyAll()` (fromError false) and `!= allowAll()` | P2 | T2 |
| **U6** | Latency bound under virtual time: a slow/failing OPA call with retries | total elapsed (virtual) ≤ configured `ceiling`; attempts ≤ `maxRetries+1`; backoff sum within budget | P6 | T1 |
| **U7** | Breaker lifecycle under virtual time: N consecutive failures open it; `openDuration` elapses → half-open; a probe success closes it | state transitions fire at the programmed virtual instants; open state short-circuits (no delegate call) | P5 | T1 |
| **U8** | `ApplicationContextRunner`: (a) R4j on classpath + `resilience.enabled=true` + `opa.enabled=true`; (b) R4j absent; (c) `enabled=false` | (a) the `OpaClient` bean is a `ResilientOpaClient`; (b)+(c) the bean is the plain `HttpOpaClient`, behavior **byte-identical to pre-B3** | P7, P8 | T2 |
| **U9** | Resolve wrapper (`HttpRoleDefinitionSupplier`) via in-process stub: (a) k<budget transient 5xx then 200+valid; (b) budget-exhausting 5xx; (c) a 4xx; (d) a `204`; (e) a `200`+valid | (a) → resolved `RoleDefinition` (recovered); (b) → throws `RoleResolutionException`, **OPA never reached**; (c) → throws **after exactly 1 attempt** (no retry); (d) → `Optional.empty()` **1 attempt** (terminal, not retried); (e) → resolved **1 attempt** | P3 | T3 |
| **U10** | Tag wrapper (`TagDefinitionClient`) via in-process stub: (a) k<budget transient then 200; (b) exhausting transient; (c) a 4xx | (a) → definitions (recovered); (b) → `TagDefinitionFetchException` (→ 503); (c) → throws **after exactly 1 attempt** (no retry) | P3 | T3 |

## Integration (I*)

| ID | Case | Asserts | Proof | → Ticket |
|---|---|---|---|---|
| **I1** | Catalog IT (real Postgres + in-process OPA stub) with resilience **on** at defaults, happy path | a normal protected read/write behaves exactly as pre-B3 (resilience is transparent on success) | P7 (no-regression) | T3 |
| **I2** | Catalog IT: the resolve edge stub injects a transient blip recovering within the resolve budget on an id'd member decision | the request **succeeds** with the resolved (narrowed) role — the blip did not deny, and did **not** ride the realm fallback | P9 (unit-level mirror of E1) | T3 |

## E2E (E*)

| ID | Case | Asserts the cut | Proof | → Ticket |
|---|---|---|---|---|
| **E1** | Through APISIX: the fault-injecting upstream returns N<budget transient failures then recovers, on a protected request | the request **SUCCEEDS** (200 / expected row set) — resilience rode out the blip | P9 | T4 |
| **E2** | Through APISIX: the upstream stays **down** (sustained outage) for the request's full budget | the request **STILL DENIES** (403 / fail-closed) — B2's deny wall intact, **no realm-fallback widening** (a realm `catalog-editor` does **not** widen during the outage) | P9 | T4 |
| **E3** | All existing matrices (catalog · tag · team · filter · permission-categories · control-plane) run with resilience **on** at defaults | every prior pinned assertion stays green — B3 changes nothing on the happy path | P7 (no-regression) | T4 |

## Headline proof

- **E1 + E2 together** are the slice's reason to exist: a transient outage **recovers to success** (B3
  does its job) while a sustained outage **still denies with no widening** (B2 not re-opened). The contrast
  is the whole point.
- **U3 + U4 + U5** pin the security contract: the decorator's fail-closed value is identical to the
  delegate's in *every* state, and `compile` is *always* `error()` (`fromError=true`) on failure — so no
  5.5-B hierarchy widening can outlive an OPA outage.
- **U9** pins B2-after-retry: an exhausted outage still throws, a 4xx never retries, OPA is never reached
  on a resolve outage.
