# Performance baseline

The measured cost of this library's authorization layer, end to end through the real example rig
(APISIX gateway → catalog service pods → OPA → user-management service), produced by the committed
k6 harness in [`scripts/load/`](scripts/load/). **Report-only numbers with validity-only gates**
(methodology pinned in [ADR 0021](docs/architecture/adr/0021-load-testing-methodology.md)): no
latency thresholds anywhere — a violated *validity* gate (errors, dropped load, thin trace samples,
wrong rig state) aborts the run red and records nothing.

These are **laptop numbers, relative by design**. The durable story is the deltas, the attributed
per-request call counts, and the shape of the curves — not the absolute milliseconds.

**Two baselines live here:** the 7.2 originals (2026-07-08, pre-coalescing) and the **Slice 7.3
re-measurement (2026-07-10)** after the resolve-coalescing work
([RESOLVE-COALESCING](docs/to-do/implemented/RESOLVE-COALESCING/RESOLVE-COALESCING.md); ADRs
[0023](docs/architecture/adr/0023-request-scoped-resolution-memoization.md) +
[0024](docs/architecture/adr/0024-batch-role-resolution.md)). Where a 7.2 number is kept it is
labeled; everything else is the 7.3 truth.

> **Comparability fine print.** Slice 7.3 also fixed two latent defects that change what is being
> measured, both found by this re-measurement: (1) the method-security advisor eagerly injected its
> manager, so the `@OpaPreAuthorize` gate silently held **undecorated** collaborators — the 7.2
> guarded numbers never exercised the B3 resilience wrap on the gate path; 7.3's do. (2)
> `ResilientOpaClient.allowAll` retried on *any* `false` instead of the all-false transport
> sentinel — invisible in 7.2 (see 1), it taxed every honest affordance page once the wrap became
> real, and was fixed before these numbers were taken.

## Environment (7.3 official run, 2026-07-10)

| | |
|---|---|
| Machine | MacBook Pro, Apple M4 Pro (14 cores), 48 GB RAM, macOS 26.5 |
| Docker Desktop | VM: 8 CPUs / 7.65 GiB · Docker 29.5.2 |
| Load generator | k6 v2.0.0, host-run (never containerized — it would compete with the rig's VM CPU) |
| Rig | APISIX 3.11.0 · OPA 1.10.1 · Keycloak 26.3.2 · Postgres 16 · Jaeger 2.17.0 |
| App | Java 21 · Spring Boot 3.4 · 2 catalog pods (512 MB heap) + 1 user-service pod |
| Fixtures | 1 load catalog + 1,000 categories (tags cycling 3 values); multi-root: 50 catalogs, one team each; the reserved `perf` identity |
| Tracing | OTEL Java agent, `always_on` sampling (identical in both passes — the delta stays clean) |

## SB4-port re-baseline (2026-07-12) — complete (gate delta + ceiling recorded telemetry-off)

The Boot 4.0.7 / Java 25 / Jackson 3 port (ADR 0026) re-ran the harness on rebuilt images
(Temurin 25, OTEL agent 2.29.0 — 2.11.0 emits nothing on Framework 7). Per ADR 0021, **only
runs that passed every validity gate are recorded**; the late-night measurement window suffered
host memory starvation (contradictory stage results within the hour), so several modes never
produced a valid run. What follows is exactly what was validly measured — and what was not.

**Valid and recorded:**

- **Per-request call attribution — every pinned bound HOLDS on the ported stack** (the ADR
  0023/0024 efficiency contract is byte-identical): gate-overhead resolve **1**/decide **1**;
  list-filter resolve **1**/batch-eval **2**/compile **1**; enrichment resolve **1**/batch-eval
  **2**; multi-root resolve **2** (the 7.3 two-lifecycle-points bound). 281–284 traces attributed
  per scenario (multi-root 282), REPS=3, floors met.
- **Steady guarded latency** (RATE=5, DURATION=30, REPS=3): list p99 ≈ 33–37 ms — consistent with
  the 7.3 ledger's steady posture.
- **Ladder stages 10 and 25 rps passed cleanly twice** (p99 177/217 ms — the 7.3 knee was AT
  10 rps; the ported list path is materially faster at the low stages). See the caveat below on
  why the ladder still has no official knee row.
- **All three fault timelines green** (fault-opa, fault-supplier-transient, fault-supplier-down):
  typed denials only, fail-closed walls intact on the new stack.

**Completed 2026-07-12 (telemetry-off re-run) — both formerly-partial modes now validly measured:**

The gate delta and ceiling that the initial re-baseline left partial were **not** a host-memory
problem after all. Root cause (found by driving the failures to the APISIX log): the gateway
`opentelemetry` plugin was configured `sampler: always_on` (100% sampling), and Jaeger's Badger
store — carrying accumulated spans from the whole implementation history — saturated under load
(`Block cache too small, hit-ratio 0.03`). Its collector stalled, and APISIX's **synchronous** OTEL
exporter blocked for its 10 s retry timeout **inside the request-serving worker**, producing 40 s
tail latencies that exhausted k6's VU pool and RED-ed every run at the third gate-overhead rep —
regardless of host quietness (reproduced at load-avg 0.7 and 12 alike). Re-running with
**`ENABLE_TRACING=0`** (no OTEL plugin, no Jaeger, no back-pressure path) climbed cleanly:

- **The two-pass gate delta — RECORDED** (`gate-overhead`, RATE=50, REPS=3 medians, tracing off,
  identical gateway posture): guarded p50 **5.93 ms** vs unguarded baseline p50 **5.14 ms** →
  the ABAC gate costs **+0.79 ms (+15 %) at p50**, and is statistically flat at the tail
  (p90 +0.01 ms, p95 −0.22 ms, p99 +0.60 ms/+7 %). Tightly reproducible (guarded 5.84–5.96,
  baseline 5.11–5.18). This supersedes the earlier "+1.49 ms" non-ledger figure — that run was
  distorted by the tracing back-pressure, not the gate.
- **The list ceiling — RECORDED** (`ceiling`, ladder 10/25/50 rps × 60 s, tracing off): a genuine
  **latency knee at 50 rps** (p99 156 ms → 170 ms → 5277 ms; fail 0 %/0 %/5.8 %); **ceiling = 25
  rps**. With tracing removed the earlier "fail-closed empty page" cliff did not recur inside the
  measured stages — the knee is ordinary app/OPA latency saturation, cleanly detected by the
  standard rule. (The fail-closed-empty-page knee-definition question from the tracing-on run
  remains an ADR 0021 methodology note, but is no longer what gates *this* ceiling.)

> **Methodology note (ADR 0021):** latency/throughput runs on this local rig must set
> `ENABLE_TRACING=0` — full-sample tracing into the accumulated Badger store is itself the
> bottleneck. The amplification analysis (which *needs* traces) is unaffected: its per-request call
> bounds were already validly attributed above with tracing on, over ~500-trace windows. A standing
> fix (fractional sampler + Badger retention) is tracked separately.

**Fine print (double attribution, F8/ADR 0026):** any delta vs the 7.3 rows commingles the new
stack (Tomcat 11 / Hibernate 7 / Jackson 3 / JDK 25) with PR #68's product-list plain→filtered
change, whose own re-baseline was deferred to this one by design. Not separably attributable.

**Harness changes in this run:** the ceiling ladder now re-mints the perf token per stage (a full
6×60 s ladder outlives one ~5-minute access token; pre-port the knee always stopped the ladder in
stage 1, masking it). Environment vs the table below: App = Java 25 · Spring Boot 4.0; OTEL agent
2.29.0. Everything else unchanged.

## 1. The headline: what the gate costs per request

Two passes of the **same single id'd `GET /api/v1/catalogs/{id}`** through the **byte-identical
gateway** (bearer validation + the coarse gateway OPA hop present in both): guarded
(`OPA_ABAC_ENABLED=true`) vs baseline (`false`). Open model (`constant-arrival-rate`), **50 req/s**,
3 × 120 s measured windows per pass, medians:

| | guarded | baseline | delta | delta % | (7.2 delta) |
|---|---|---|---|---|---|
| p50 | 6.91 ms | 4.63 ms | **+2.28 ms** | +49.2 % | +2.68 ms |
| p95 | 9.14 ms | 6.87 ms | **+2.28 ms** | +33.1 % | +2.32 ms |
| p99 | 10.96 ms | 8.23 ms | +2.73 ms | +33.2 % | +28.08 ms |

**The library gate now costs ~2.3 ms at the median** — one role resolve (**down from two**, the
request memo), one OPA decision, one enrichment batch eval. The 7.2 p99 tail noise (+28 ms) is
gone: with half the cross-service hops per request there is simply less tail to compound — the
guarded p99 fell from 36.8 ms to 11.0 ms.

## 2. The partial-eval list ceiling

`ramping-arrival-rate` ladder over the filtered category list (the OPA Compile → SQL-residual
path), 60 s stages at 10→25→50→100→150→200 req/s. Knee = the first stage sustaining p99 > 1 s
or > 1 % failed/dropped (an operational definition, not a quality judgment):

> **Knee still at the FIRST stage (10 req/s) — but the signal shrank: p99 1.37 s (was 3.59 s),
> 0.17 % failed, zero drops.** Ceiling: none within the ladder.

The 7.3 slice removed the resolve amplification (§3) and the list's cost profile moved with it:
**the list is now bound by OPA batch-eval latency, not resolve chatter** — each page still costs
two bulk evaluations (query-time allowlist finisher + response-time affordance batch, the designed
bound), and under load OPA's bulk answers stretch into the hundreds of milliseconds on this shared
VM. That is the next tuning frontier, and it is an OPA/policy cost, not a per-row library fan-out.

> **Stale as of 2026-08-06 (foreign-type folding) — re-measure.** The query-time allowlist finisher
> in these runs was triggered by the perf role's **multi-type** shape poisoning the residual — i.e.
> these numbers measured the category list on the **allowlist-batch** path, not the SQL-residual
> path this section names. Since the fold, that role's residual is fully supported: the finisher
> bulk no longer runs, the designed bound for these list scenarios is **one** bulk evaluation (the
> response-time affordance batch), and the "OPA batch-eval latency" share of the ceiling should
> shrink accordingly. The queued quiet-host re-run should re-pin this section's numbers.

- **The 7.2 disaster mode is gone: OPA survives the 50 req/s stage.** A dedicated 60 s stage at
  50 req/s saturates hard (p50 3.8 s, 27 % failed, dropped iterations) but OPA is **not**
  OOM-killed (7.2: killed), and the failure shape is **fail-closed**: bulk evals that time out
  produce all-false verdicts, so pages shrink or omit `_actions` — nothing ever widens, and the rig
  recovers by itself.
- Steady list latency below the knee (5 req/s, 3 × 30 s, medians): p50 **155.2 ms** ·
  p95 311.0 ms · p99 599.7 ms (7.2: 173.5 / 206.9 / 316.2 — the median carries the removed
  resolves; the upper tail is OPA-eval variance on this rig and now dominates the profile).
- Ladder validity note (harness change, 7.3): with the gateway OPA timeout now **bounded** (§4), a
  saturation-adjacent OPA stall surfaces as a timeout-**deny** — so ladder stages count 403s as
  knee *data* (the `>1 % failed` signal) instead of aborting; the broken-ACL-chain guard is the
  seed-time canary probe, which reds before any stage runs.

## 3. Cross-service amplification — attributed per request

From Jaeger traces of each scenario's measured window (282–650 single-request traces attributed
per scenario; counts were **constant across every sampled trace** — median = max). The re-pinned
bounds beside the measured truth:

| scenario | outbound op | pinned bound | 7.2 measured | **7.3 measured** | verdict |
|---|---|---|---|---|---|
| single GET | resolve | 1 | 2 | **1** | within |
| single GET | decide | 1 | 1 | 1 | within |
| single GET | batch-eval | — | 1 | 1 | (enrichment rides along) |
| 20-row list | resolve | 1 | 22 | **1** | within |
| 20-row list | compile | 1 | 1 | 1 | within |
| 20-row list | batch-eval | **2** (re-pinned) | 2 | 2 | within |
| 100-row enriched page | resolve | 1 | 102 | **1** | within |
| 100-row enriched page | batch-eval | **2** (re-pinned) | 2 | 2 | within |
| 50-row multi-root catalogs list (NEW) | resolve | **2** | 51 (pre-change baseline) | **2** | within |
| 50-row multi-root catalogs list | governed-scope | — | 1 | 1 | |
| 50-row multi-root catalogs list | batch-eval | 1 | 1 | 1 | (no finisher bulk: the residual fully reduces) |

The three re-pins, each "two questions at two lifecycle points":

- **List batch-eval = 2, not 1** (a wrong pin, not wrong code): the query-time allowlist finisher
  decides *row inclusion*; the response-time affordance batch decides *verb maps* on web DTOs the
  data layer must not see. ADR 0024 rejected merging them on layering.
- **Multi-root resolve = 2, not 1**: the list authorizer resolves the *coarse* filter-residual role
  at query time (one single-target call — fail-closed load-bearing), and the enrichment resolves
  the page's distinct roots at response time (**one** batch `lookupAll`; the memo dedups the shared
  root out of it). 51 sequential wire calls became **2**, constant in the page size.
- Same-root scenarios stay at **1**: every caller shares one `(user, root)` key, the request memo
  collapses them, and a fully-memoized batch never even delegates.

Steady enrichment latency (100-row page, 5 req/s, 3 × 30 s, medians): p50 **174.4 ms** ·
p95 210.6 ms · p99 255.3 ms (7.2: 266 / 375 / 413 — the ~100 removed sequential resolves were most
of the difference). The new multi-root scenario: p50 **117.1 ms** · p95 122.8 · p99 129.2
(pre-change baseline on the same fixtures: 136.7 / 179.9 / 226.2).

Span-volume corollary: a list-request trace shrank with its call graph (the ~2,000-span 7.2 traces
were mostly resolve chains). Production adopters should still sample — see the adopter notes.

## 4. Resilience under fault

The single-GET scenario at **50 req/s** across a fixed timeline: 60 s healthy → 60 s fault → 60 s
recovery. Per-phase validity: every failure must be a **typed 403** (never a 5xx, never a hang) —
passed.

| fault | fault-phase denials | deny latency p50/p95 | fail-closed story | time-to-recovery |
|---|---|---|---|---|
| **OPA paused** (hard hang) | 2,942 of 2,950 | **1,005 / 1,010 ms** (7.2: 3,005 / 3,010) | the gateway's OPA plugin times out (**now bounded at 1,000 ms**, was the 3,000 default) then denies — typed, 3× faster | **0.36 s** |
| **supplier outage** (7.2 rows — config unchanged) | 3,067 of 3,151 | 5.2 / 7.7 ms | the resolve breaker opens → instant typed denials (B2's wall) | 9.0 s |
| **supplier transient** (7.2) | 757 of 3,146 | 5 / 7 ms | most requests ride the guard's retries; the rest fail fast | 10.3 s |

The 7.2 findings, closed or documented:

- **Gateway deny wall: tuned.** `infra/apisix/init-routes.sh` pins the `opa` plugin `timeout` to
  **1000 ms** (integer milliseconds; APISIX default 3000). Why not lower: this OPA also serves the
  app's compile + bulk evals, and its **loaded** tail crosses 500 ms at even 10 req/s of list
  traffic — a 500 ms timeout produced steady-state 403s (measured). Tune against your OPA's loaded
  p99, never its idle latency.
- **Breaker recovery lag (~9–10 s): document-only, deliberate.** The resolve breaker's reopen pace
  is `opa.abac.resilience.resolve.breaker.open-duration` (default 10 s) + the half-open probe count
  (`half-open-probes`); shortening trades recovery latency for flap-under-instability. The B2 wall
  itself is not configurable — an open breaker always denies typed and instantly.

## Adopter notes (7.3)

- **The request memo is default-on** (`opa.abac.resolve-memo.enabled`): one request sees one
  resolve answer per target — all three tri-state outcomes replayed, including the outage. The
  staleness window is one request: a revocation lands at the next request boundary. Disable only
  if you need per-call freshness and can pay the measured per-row amplification.
- **Batch resolution is unconditional**: implement
  `RoleDefinitionSupplier.lookupAll` (one exchange per page) or inherit the default per-target
  loop — semantics identical, round-trips fewer.
- **Sample traces in production.** `always_on` is the rig's attribution instrument, not a
  production posture — under sustained list load it can overwhelm the pipeline. Concretely:
  `OTEL_TRACES_SAMPLER=parentbased_traceidratio` + `OTEL_TRACES_SAMPLER_ARG=0.05`.
- **The gateway OPA timeout is a deny wall, not a retry** — bound it to your OPA's loaded p99
  (see §4).

## Rerun it

```bash
brew install k6
./deploy.sh build                                             # fresh images (both services)
ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2   # the guarded rig
cd scripts/load
REPS=3 ./run-load.sh full                 # the two-pass headline delta (50 req/s)
RATE=5 DURATION=30 REPS=3 ./run-load.sh guarded   # steady list/enrichment below the knee + attribution
./run-load.sh ceiling                     # the list ladder (early-stops at the knee)
DURATION=30 REPS=3 ./run-load.sh multi-root       # the 7.3 multi-root scenario (50 own-root rows)
./run-load.sh fault-opa && ./run-load.sh fault-supplier-transient && ./run-load.sh fault-supplier-down
```

Every number above is re-derivable from `scripts/load/results/<run>/` artifacts (summary exports,
delta/ceiling/amplification/phase JSON). Protocol notes: quiesce the machine; start official runs
from a **freshly deployed rig and a fresh trace store** (`docker compose -p opa-abac-example -f
infra/compose.jaeger.yaml down -v` first) — the harness's validity gates refuse degraded-rig
windows rather than record them, and a rig that has been through a saturation probe needs a pod
restart before the next official window. Steady list/enrichment numbers are taken below the
measured knee (5 req/s) — see §2/§3.
