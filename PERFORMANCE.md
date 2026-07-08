# Performance baseline

The measured cost of this library's authorization layer, end to end through the real example rig
(APISIX gateway → catalog service pods → OPA → user-management service), produced by the committed
k6 harness in [`scripts/load/`](scripts/load/). **Report-only numbers with validity-only gates**
(methodology pinned in [ADR 0021](docs/architecture/adr/0021-load-testing-methodology.md)): no
latency thresholds anywhere — a violated *validity* gate (errors, dropped load, thin trace samples,
wrong rig state) aborts the run red and records nothing.

These are **laptop numbers, relative by design**. The durable story is the deltas, the attributed
per-request call counts, and the shape of the curves — not the absolute milliseconds.

## Environment (official run, 2026-07-08)

| | |
|---|---|
| Machine | MacBook Pro, Apple M4 Pro (14 cores), 48 GB RAM, macOS 26.5 |
| Docker Desktop | VM: 8 CPUs / 7.65 GiB · Docker 29.5.2 |
| Load generator | k6 v2.0.0, host-run (never containerized — it would compete with the rig's VM CPU) |
| Rig | APISIX 3.11.0 · OPA 1.10.1 · Keycloak 26.3.2 · Postgres 16 · Jaeger 2.17.0 |
| App | Java 21 · Spring Boot 3.4 · 2 catalog pods (512 MB heap) + 1 user-service pod |
| Fixtures | 1 load catalog + 1,000 categories (tags cycling 3 values), the reserved `perf` identity |
| Tracing | OTEL Java agent, `always_on` sampling (identical in both passes — the delta stays clean) |

## 1. The headline: what the gate costs per request

Two passes of the **same single id'd `GET /api/v1/catalogs/{id}`** through the **byte-identical
gateway** (bearer validation + the coarse gateway OPA hop present in both): guarded
(`OPA_ABAC_ENABLED=true` — the library resolves the caller's role and asks OPA per request) vs
baseline (`false` — the library gate absent, everything else identical). Open model
(`constant-arrival-rate`), **50 req/s**, 3 × 120 s measured windows per pass, medians:

| | guarded | baseline | delta | delta % |
|---|---|---|---|---|
| p50 | 6.84 ms | 4.16 ms | **+2.68 ms** | +64.5 % |
| p95 | 9.52 ms | 7.20 ms | **+2.32 ms** | +32.3 % |
| p99 | 36.76 ms | 8.68 ms | +28.08 ms | +323.5 % |

**The library gate costs ~2.7 ms at the median** on this rig — one role resolve + one OPA decision
(plus the response-enrichment eval, see §3) per request. The p99 delta is tail noise from the
resolve/eval hops' own tails; the p50/p95 deltas are the durable signal.

## 2. The partial-eval list ceiling

`ramping-arrival-rate` ladder over the filtered category list (the OPA Compile → SQL-residual
path), 60 s stages at 10→25→50→100→150→200 req/s. Knee = the first stage sustaining p99 > 1 s
or > 1 % failed/dropped (an operational definition, not a quality judgment):

> **Knee at the FIRST stage (10 req/s), latency signal: p99 3.59 s, zero errors, zero drops.**
> Ceiling: none within the ladder — no stage passed.

The list is **not** residual-bound — it is bound by the per-row resolve amplification (§3): the
default 20-row page makes 22 sequential role-resolve calls, so the endpoint saturates an order of
magnitude below the single-GET path. At 50 req/s the list path collapses outright (observed:
multi-second queues, then OPA OOM-killed by the bulk-eval backlog). **This is the 7.3 tuning
target, measured.**

Steady list latency below the knee (5 req/s, 30 s window): p50 173.5 ms · p95 206.9 ms ·
p99 316.2 ms.

## 3. Cross-service amplification — attributed per request

From Jaeger traces of each scenario's measured window (~95–1500 single-request traces attributed
per scenario; counts were **constant across every sampled trace** — median = max). The ADR-pinned
expected bounds beside the measured truth:

| scenario | outbound op | pinned bound | measured | verdict |
|---|---|---|---|---|
| single GET | resolve | 1 | **2** | EXCEEDED |
| single GET | decide | 1 | 1 | within |
| single GET | batch-eval | — | 1 | (enrichment rides along) |
| 20-row list | resolve | 1 | **22** | EXCEEDED |
| 20-row list | compile | 1 | 1 | within |
| 20-row list | batch-eval | — | 2 | |
| 100-row enriched page | resolve | 1 | **102** | EXCEEDED |
| 100-row enriched page | batch-eval | 1 | **2** | EXCEEDED |
| 100-row enriched page | compile | — | 1 | |

Two findings, one per direction:

- **The eval side is bounded as designed.** A 100-row `_actions` page costs **2 batch evals, not
  100** — the batch primitive works; per-row affordance maps never fan out into per-row OPA calls.
- **The resolve side scales with page rows.** Every resolve call in a request hits the *identical*
  target (same user, same governing catalog): the gate, the list authorizer, and **one call per
  enriched row**. A request-scoped resolve memo would collapse 102 identical lookups to 1. This is
  the dominant cost of every list-shaped endpoint (§2) and the **first item for 7.3**.
- Corollary: **span volume amplifies identically** (a 20-row list request is a ~2,000-span trace
  under `always_on` sampling), which under sustained list load overwhelmed the trace pipeline
  (Jaeger OOM; app-side export backpressure). The official protocol runs on a fresh trace store;
  production adopters should sample.

Steady enrichment latency (100-row page, 5 req/s, 30 s window): p50 266 ms · p95 375 ms ·
p99 413 ms — ~102 sequential ~1 ms resolves account for most of it.

## 4. Resilience under fault

The single-GET scenario at **50 req/s** across a fixed timeline: 60 s healthy → 60 s fault → 60 s
recovery. Per-phase validity: every failure must be a **typed 403** (never a 5xx, never a hang) —
all three modes passed.

| fault | fault-phase denials | deny latency p50/p95 | fail-closed story | time-to-recovery |
|---|---|---|---|---|
| **OPA paused** (hard hang) | 2,834 of 2,851 | 3,005 / 3,010 ms | the gateway's OPA plugin times out (~3 s) then denies — typed, but **slow** | **0.48 s** |
| **supplier outage** (role source down) | 3,067 of 3,151 | **5.2 / 7.7 ms** | the resolve breaker opens → instant typed denials (B2's wall, un-breached) | 9.0 s |
| **supplier transient** (blip + churn) | 757 of 3,146 | 5 / 7 ms | most requests ride the guard's retries; the rest fail fast | 10.3 s |

The contrast is the story: the **library's** fail-closed edge denies in single-digit milliseconds
once its breaker opens (~600× faster than the gateway plugin's timeout-bound deny), and recovery
is breaker-paced (~9–10 s half-open lag) on the supplier edge vs sub-second on the OPA edge.
Recorded 7.3 findings: the ~3 s gateway-plugin deny latency under OPA outage; the breaker's
recovery lag.

## Rerun it

```bash
brew install k6
ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2   # fresh rig (build images first: ./deploy.sh build)
cd scripts/load
REPS=3 ./run-load.sh full        # the two-pass headline delta (+ steady scenarios)
./run-load.sh ceiling            # the list ladder (early-stops at the knee)
./run-load.sh fault-opa && ./run-load.sh fault-supplier-transient && ./run-load.sh fault-supplier-down
```

Every number above is re-derivable from `scripts/load/results/<run>/` artifacts (summary exports,
delta/ceiling/amplification/phase JSON). Protocol notes: quiesce the machine; start official runs
from a **freshly deployed rig and a fresh trace store** (`docker compose -p opa-abac-example -f
infra/compose.jaeger.yaml down -v` first) — the harness's validity gates refuse degraded-rig
windows rather than record them. Steady list/enrichment numbers are taken below the measured knee
(5 req/s) because the standard 50 req/s exceeds those endpoints' current ceiling — see §2/§3.
