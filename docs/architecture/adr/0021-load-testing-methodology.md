---
tags:
  - status/shipped
  - type/decision
  - area/architecture
  - area/infra
---

# ADR 0021 — Load-testing methodology (the pre-publish performance baseline)

**Status:** Shipped (Phase 7.2, [[LOAD-TESTING]] — the baseline lives in the root `PERFORMANCE.md`)
**Date:** 2026-07-07
**Context tags:** k6, open-model load, gate-overhead delta, partial-eval ceiling, amplification ratio, fail-closed latency

> Pins the structural forks for the Phase-7.2 load-testing slice — the committed, re-runnable harness
> that produces `PERFORMANCE.md`. Settled in a planning interview (grill-me, 2026-07-07). Scope was
> pinned earlier (2026-07-06): four library hot paths through the example services + the cross-service
> amplification ratio; demo UI, Keycloak login throughput, and APISIX tuning are OUT.

## Context

The library is approaching publish (Phase 7). Adopters' first question about an authorization layer
that fronts every request is *what does it cost* — per-request gate overhead, list-filtering ceilings,
enrichment fan-out, and behavior under dependency failure. The repo has no performance evidence and no
harness to produce it. The numbers must come from the real rig (gateway → services → OPA →
user-service), be re-runnable from a clean clone, and be honest about their environment (a laptop).

## Decision

### 1. Tool — k6, host-installed; scenarios are committed JS under `scripts/load/`
k6 over Gatling: containerizes nothing into the build (no JVM harness module in a repo about to
publish its artifact story), scenarios are plain JS mirroring the `scripts/postman/` posture, and the
open-model executors + JSON summary export feed the deliverable directly. **Host-installed** (`brew
install k6`), driving `localhost:9085`: on Docker Desktop the VM boundary is crossed either way, but a
containerized generator competes for the rig's own VM CPU — measurement interference exactly where
p99s are read. Layout: `scripts/load/run-load.sh` (runner: preflight, in-network token minting, bulk
fixture seed, orchestration, summary extraction) + `scripts/load/scenarios/*.js` (one per hot path) +
`scripts/load/results/` (gitignored raw exports). Harness docs in `scripts/load/README.md`.

### 2. The unguarded baseline — a two-pass rig flip, no dedicated endpoint
The baseline reuses the existing `deploy.sh` mechanism: `ENABLE_OIDC=1 ENABLE_OPA=0` gives pods with
`OPA_ABAC_ENABLED=false` behind the **identical** gateway posture (bearer validation unchanged), so
the app-side library gate is the only variable. A dedicated unguarded endpoint is rejected — it would
ship a weakened path into the app to measure a number. `run-load.sh full` orchestrates both passes
(guarded → redeploy baseline → measure → restore), **asserting** each pass's actual pod state
(`docker exec` env probe, never trust-the-flag) and re-warming after every redeploy.

### 3. Traffic model — open, fixed-rate; the ceiling scenario ramps
`constant-arrival-rate` (open model — a closed VU loop lets a slow system delay its own load and
flatter its tail: coordinated omission), default **50 req/s**, env-overridable, identical across both
passes. Warm-up is a **separate discarded k6 invocation** (60 s at the measurement rate), then a
**120 s measured window** per scenario. `REPS=n` supported; the official baseline records `REPS=3`
medians. The one exception: the partial-eval scenario uses `ramping-arrival-rate` (§5).

### 4. Fixtures — bulk-seeded, registry-reserved, dedicated identity
One load catalog + **1,000 categories** (`FIXTURE_ROWS` overridable) with tags varied so the residual
discriminates; bulk-seeded via SQL (`generate_series`, deterministic ids — the one-row bootstrap API
would take minutes). The **`dddd…` id prefix** is reserved in the fixture registry
(`scripts/postman/README.md`); teardown-on-green with `KEEP_FIXTURES=1`, like every matrix. A
dedicated realm user **`perf`** (runner-bootstrapped profile + membership on the load team) so load
runs never perturb matrix fixtures or the demo roster.

### 5. The "ceiling" is operationally defined
`ramping-arrival-rate`, 60 s stages, default ladder `10→25→50→100→150→200` req/s. **Knee** = the first
stage sustaining either **p99 > 1 s** or **>1 % failed requests *or dropped iterations*** (k6's
`dropped_iterations` means the offered rate wasn't kept — in an open model that *is* saturation and
must count). **Ceiling = the last stage passing both**, reported with *which* signal broke at the
knee. The 1 s marker is a reporting definition, not a quality judgment.

### 6. Amplification ratio — Jaeger-scripted, attributed, expected-vs-measured
`scripts/load/amplification.py` queries the Jaeger API over the measurement window (~500 traces per
scenario) and computes **attributed** per-request outbound counts (which downstream, which
operation). Counters (actuator metric diffs) are rejected: aggregate-only, no per-scenario
attribution. `PERFORMANCE.md` states the **expected bound** per scenario (single GET → 1 resolve + 1
decide; list → 1 resolve + 1 compile; enriched page → 1 batch eval, not N×rows) beside the measured
value — the request-scoped caches are the thing under proof. Tracing overhead is identical in both
passes (the delta stays clean); the ratio is a guarded-pass-only metric.

### 7. Fault injection — the B3 stub for the supplier edge; `docker pause` for OPA
Supplier edge: the existing `ENABLE_RESILIENCE_STUB` rig, `transient` and `down` modes. OPA edge:
runner-orchestrated `docker pause`/`unpause` of the OPA container — a real hang (the nastier failure
mode: timeouts + breaker), zero new infra (toxiproxy rejected as a rig component for one scenario).
Both run a fixed three-phase timeline (60 s healthy → 60 s fault → 60 s recovery) at the standard
rate; analysis slices per phase: per-phase percentiles, denial counts, **fail-closed latency during
the fault** (a slow deny is the finding), **time-to-recovery** after it clears.

### 8. Result semantics — report-only numbers, validity-only gates, root `PERFORMANCE.md`, no CI
No committed performance thresholds — the rig is a laptop; absolute-millisecond gates flake across
machines, and the publishable story is the delta / knee / boundedness. k6 thresholds serve only as
**validity gates** (error-rate 0 during a measurement window or the runner exits red and records
nothing; the fault scenario's validity is per-phase: typed fast denials, completed recovery).
`PERFORMANCE.md` lives at the **repo root** (publish-facing, README-linked, no vault frontmatter):
environment + methodology, one section per hot path, the amplification table, rerun instructions.
**No CI performance runs** — laptop-rig numbers; deltas and ratios are the product.

## Consequences

- A **committed, one-command, re-runnable** performance harness in the same idiom as the e2e runners;
  `PERFORMANCE.md` becomes a publish artifact and the 7.3 tuning baseline.
- Two new rig-facing reservations (the `dddd…` prefix, the `perf` realm user) enter the fixture
  registry; one realm-export addition (Keycloak recreate on first use).
- The baseline flip temporarily reshapes the rig mid-`full`-run (OPA container absent during the
  baseline pass); the runner restores the guarded rig afterwards.
- Numbers are laptop-relative by design; the doc says so and reports environment alongside.

## Considered & rejected

| Option | Why rejected |
|---|---|
| Gatling (JVM-native) | A JVM harness module in the build right before publish; heavier container story; the HTML reports don't serve a committed-markdown deliverable (§1). |
| Containerized k6 in the compose network | The generator competes for the rig's VM CPU on a laptop — interference at the measurement point (§1). |
| A dedicated unguarded endpoint | Ships a weakened path into the app to measure a number; the `ENABLE_OPA=0` flip measures the same thing without touching code (§2). |
| Closed-model (fixed-VU loop) load | Coordinated omission flatters the tail — the headline is a p99 delta (§3). |
| Actuator counter diffs for amplification | Aggregate-only; cannot attribute per scenario or per downstream operation (§6). |
| Toxiproxy for OPA faults | A new rig component for one scenario; `docker pause` produces the harder failure mode for free (§7). |
| Committed perf thresholds / CI perf gate | Laptop-variance flake; report-only with validity gates keeps the numbers honest (§8). |

## Related

- [[LOAD-TESTING]] (the slice) · [[POC-ROADMAP]] (Phase 7.2)
- [[0017-cross-service-http-resilience|ADR 0017]] (the B3 edges + fault injector this reuses)
- [[0005-partial-eval-to-jpa-specification|ADR 0005]] (the PE path under ceiling test)
- [[0016-action-enrichment-affordance-metadata|ADR 0016]] (the batch-eval fan-out under proof)
