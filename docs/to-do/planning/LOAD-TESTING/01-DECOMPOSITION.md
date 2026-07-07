---
tags:
  - status/planned
  - type/project
  - area/infra
  - area/architecture
---

# LOAD-TESTING — decomposition

> The ordered work list for [[LOAD-TESTING|Phase 7.2]], decomposed from [[00-DESIGN]] +
> [[0021-load-testing-methodology|ADR 0021]] (the pinned forks). **6 tickets, one focused commit
> each.** Each ticket's *Acceptance* references a case in [[10-QA-TEST-CASES]]. Everything lives in
> `scripts/load/` (+ the realm export, the fixture registry, and root `PERFORMANCE.md`) — **this
> slice ships zero app/library/rego code.**

## Critical path

```
T1 ──► T2 ──► T3 ──► T4 ──► T5 ──► T6
(skeleton) (headline) (ceiling) (fan-out+ratio) (faults) (official baseline + docs)
```

**Harness-first.** T1 is the runner + fixtures + identity (independently landable — the seed and
preflight are reusable alone). T2 delivers the **headline** (the two-pass gate-overhead delta) and is
the standalone-value subset together with T1. T3 and T4 are parallelizable after T2 (ordered here for
checkpoint discipline); T5 needs T2's scenario shape + the runner's redeploy orchestration; T6 is the
official measurement run + the deliverable. The ★ review + checkpoint after each ticket is mandatory.

---

## T1 — Harness skeleton: `run-load.sh` + the `perf` identity + bulk fixtures + registry entries

**Goal.** The committed runner in the postman-runner idiom, the dedicated load identity, and the
deterministic fixture population — everything every scenario needs, before any scenario exists.

**Deliverables.**
- `scripts/load/run-load.sh` — preflight (`command -v k6`; rig up with the expected flags; **pod-state
  probe** via `docker exec` env — asserts `OPA_ABAC_ENABLED` matches the pass, never trust-the-flag);
  in-network token minting for **`perf`** (the proven `docker run curlimages/curl` pattern);
  env knobs `RATE` (default 50), `DURATION` (default 120), `WARMUP` (default 60), `REPS` (default 1),
  `FIXTURE_ROWS` (default 1000), `KEEP_FIXTURES`; mode argument parsing (`guarded | baseline | full` +
  the fault modes arriving in T5); `scripts/load/results/` export plumbing (gitignored).
- **Bulk fixture seed**: 1 load catalog + `FIXTURE_ROWS` categories under the reserved **`dddd…`**
  prefix, bulk SQL (`generate_series`, deterministic ids, tags varied across a few values so the PE
  residual discriminates); the load team + the `perf` user's read/write membership via the internal
  bootstrap API; **post-seed count assertion**; teardown-on-green (`KEEP_FIXTURES=1` keeps).
- `infra/keycloak/realm-export.json` — the **`perf`** realm user (password `perf`, `catalog-viewer` +
  `catalog-editor` realm roles like the other demo users). Realm change → Keycloak recreate on first
  use (documented in the runner preflight error).
- `scripts/postman/README.md` — **two registry rows**: the `dddd…` prefix (load fixtures, granted) and
  the `perf` account reservation (no matrix may bind or assert on it).
- `scripts/load/README.md` — skeleton (usage, prerequisites, knobs; finalized in T6).
- Consumers named: every scenario ticket (T2–T5) runs through this runner; the registry rows are read
  by every future matrix author.

**Acceptance.** [[10-QA-TEST-CASES]] **U1** (offline: `bash -n`; runner `--help` lists modes/knobs)
+ **I1** (live rig: preflight aborts red with the actionable command on a pod-state mismatch; the
seed populates exactly `FIXTURE_ROWS` categories — psql count asserted; teardown removes only `dddd…`
fixtures; `KEEP_FIXTURES=1` keeps them).

**What NOT to touch.** No app/library/rego change (slice-wide invariant). Only `dddd…` ids — never
another matrix's prefix (the registry is the law). Don't touch the existing postman runners. The
`perf` user gets **no** team membership outside the load team.

---

## T2 — Gate-overhead scenario + the two-pass guarded/baseline orchestration (the headline)

**Goal.** The headline number: single-decision gate overhead p50/95/99 as a guarded-vs-baseline delta
through the identical gateway.

**Deliverables.**
- `scripts/load/scenarios/gate-overhead.js` — single `GET /api/v1/catalogs/{loadCatalogId}` as `perf`;
  `constant-arrival-rate` at `RATE` for `DURATION`; **validity thresholds only** (all-2xx, error rate
  0 — a violated threshold exits non-zero and the runner records nothing); `--summary-export` JSON.
- Runner orchestration: per scenario, a **separate discarded 60 s warm-up invocation** then the
  measured run; `guarded` and `baseline` modes assert pod state first; **`full`** = guarded pass →
  redeploy `ENABLE_OIDC=1 ENABLE_OPA=0` (all other flags identical) → warm-up → baseline pass →
  **restore the guarded rig**; `REPS` loops the measured run and reports medians.
- **Delta computation** from the two summary JSONs (p50/95/99 guarded, baseline, absolute + relative
  delta), printed and written to `results/`.
- Consumers named: `PERFORMANCE.md`'s headline table (T6); the T5 fault scenario reuses this
  scenario's request shape.

**Acceptance.** [[10-QA-TEST-CASES]] **I2** (a short smoke `full` run — `RATE=5 DURATION=15 WARMUP=5`
— completes both passes on the rig, produces both summaries + the delta block, and the rig ends
guarded; invoking `baseline` against a guarded rig aborts red **before** any load).

**What NOT to touch.** No latency thresholds (report-only, ADR 0021 §8). The gateway posture is
identical across passes — never flip `ENABLE_OIDC` or the SPA/directory flags between them. The delta
is computed from same-`RATE` passes only.

---

## T3 — Partial-eval list scenario + the ceiling ramp (knee detection)

**Goal.** The PE-filtered list's latency at the standard rate and its **operationally-defined
ceiling** — the knee per ADR 0021 §5.

**Deliverables.**
- `scripts/load/scenarios/list-filter.js` — `GET` the load catalog's category list (the PE residual +
  tag conjunct path) as `perf`; steady mode (`RATE`/`DURATION`, guarded pass only) with validity
  thresholds; parameterized `rate` for ladder stages.
- **The ladder loop in the runner** (`ceiling` mode): 60 s constant-rate invocations up the default
  ladder `10→25→50→100→150→200` (`LADDER=` overridable), evaluating the **knee between stages** from
  each summary JSON — knee = p99 > 1 s OR > 1 % failed/dropped, sustained for the stage — with
  **early stop** at the knee; reports the ceiling (last passing stage) + **which signal broke**; an
  honest "no knee within the ladder" result when nothing breaks.
- Fixture-dependency assert: the category count equals `FIXTURE_ROWS` before any stage.
- Consumers named: `PERFORMANCE.md`'s ceiling section (T6); the knee-evaluation function is offline-
  testable (U2).

**Acceptance.** [[10-QA-TEST-CASES]] **U2** (offline: the knee function against synthetic summaries —
latency-knee, drop-knee, no-knee) + **I3** (live: a mini-ladder `LADDER=5,10` short-stage run
completes, per-stage summaries land in `results/`, and the no-knee case is reported honestly).

**What NOT to touch.** The knee definition is ADR-pinned — no alternate criteria. Steady-mode
`RATE` stays identical to T2's (comparability). Never mutate the fixture population from a scenario.

---

## T4 — Enrichment fan-out scenario + `amplification.py` (the attributed ratio)

**Goal.** Prove the two boundedness claims with attributed evidence: the enriched page does **one
batch eval** (not N×rows), and the request-scoped caches bound cross-service chatter per request.

**Deliverables.**
- `scripts/load/scenarios/enrichment.js` — `GET` a `perPage=100` category page **with `_actions`**
  as `perf` (guarded pass only); validity thresholds; steady `RATE`/`DURATION`.
- `scripts/load/amplification.py` — queries the Jaeger API (`localhost:26686`) over a given scenario's
  measurement window; samples **~500 traces** (`MIN_TRACES` guard — **aborts red rather than
  extrapolate** below the floor); computes **attributed per-request outbound counts** (which
  downstream service, which operation: resolve / decide / compile / batch-eval / tag); emits the
  **expected-vs-measured** table (single GET → 1 resolve + 1 decide; list → 1 resolve + 1 compile;
  enriched page → 1 resolve + **1 batch eval**) as JSON + markdown to `results/`.
- Runner integration: after each guarded measured run, the amplification step runs automatically and
  its table is part of the scenario's recorded output.
- Consumers named: `PERFORMANCE.md`'s amplification section (T6); U3 exercises the script offline.

**Acceptance.** [[10-QA-TEST-CASES]] **U3** (offline: `amplification.py` against committed synthetic
trace fixtures — correct attribution; the `MIN_TRACES` abort fires on a thin window) + **I4** (live
smoke: the measured expected-vs-measured table matches the bounds for gate-overhead AND enrichment —
the enriched page shows **1** batch eval for a 100-row page, not 100).

**What NOT to touch.** No Prometheus/Grafana provisioning (out of scope, §5). No counter-diff
fallback. No batching/tuning changes — 7.3 owns tweaks; this slice only measures.

---

## T5 — Resilience-under-fault passes (the B3 stub + OPA `docker pause`)

**Goal.** The degradation story, measured: fail-closed latency during a dependency outage, per-phase
percentiles, and time-to-recovery — for both the supplier edge and the OPA edge.

**Deliverables.**
- `scripts/load/scenarios/resilience.js` — T2's request shape at `RATE` for the **three-phase
  timeline** (60 s healthy → 60 s fault → 60 s recovery; phase lengths env-overridable for smokes),
  streaming per-request points (`--out json`) for phase slicing.
- `scripts/load/phases.py` — slices the stream by the runner-recorded fault timestamps; per-phase
  p50/95/99, success/denial counts, **fail-closed latency during the fault** (denials must be fast and
  typed — a slow deny is the finding), **time-to-recovery** (first sustained-healthy window after the
  fault clears). **Per-phase validity checks** (fault phase expects typed 403s; recovery must
  complete) — red run otherwise.
- Runner fault modes: `fault-supplier-transient` / `fault-supplier-down` (redeploy with
  `ENABLE_RESILIENCE_STUB=1 STUB_MODE=…`, run, **restore the guarded rig**); `fault-opa`
  (`docker pause` the OPA container at the phase boundary, `unpause` at the next, restore verified).
- Consumers named: `PERFORMANCE.md`'s resilience section (T6); `phases.py` is offline-testable (U4).

**Acceptance.** [[10-QA-TEST-CASES]] **U4** (offline: `phases.py` against a synthetic stream —
correct phase attribution, recovery detection, and the fault-phase validity failure on a synthetic
slow-deny) + **I5** (live smoke with short phases: `fault-opa` pauses/unpauses and the rig ends
guarded-and-healthy; the supplier modes redeploy the stub rig and restore; per-phase tables land in
`results/`).

**What NOT to touch.** The three-phase timeline shape is ADR-pinned. No breaker/timeout tuning (7.3).
**Never leave the rig faulted** — restore (unpause / redeploy guarded) is part of the ticket's
definition of done, including on a red run (trap on exit).

---

## T6 — The official baseline → `PERFORMANCE.md` + docs + folder move

**Goal.** Run the official `REPS=3` measurement suite on a quiesced rig, write the publish-facing
deliverable, and close the slice.

**Deliverables.**
- The **official run**: all scenarios at defaults (`REPS=3`, medians), the full ladder, all three
  fault passes — on a quiesced laptop (no competing workloads; note the machine + Docker Desktop
  resources + versions + date).
- **Root `PERFORMANCE.md`**: environment + methodology (from ADR 0021, condensed); the headline
  guarded-vs-baseline table; the ceiling + breaking signal; the enrichment fan-out result; the
  amplification expected-vs-measured table; the per-phase fault tables; **the one-command rerun
  instructions**; the laptop-relative caveat (deltas and ratios are the durable story).
- `README.md` — a Performance section linking `PERFORMANCE.md`.
- `scripts/load/README.md` finalized (usage, all knobs, the registry reservations, troubleshooting).
- Housekeeping: tick the [[LOAD-TESTING]] status table; ADR 0021 status → shipped; roadmap row →
  shipped; `git mv docs/to-do/planning/LOAD-TESTING docs/to-do/implemented/` + the Shipped banner +
  frontmatter flip.
- Consumers named: adopters (the publish story) and Phase 7.3 (the tuning baseline).

**Acceptance.** [[10-QA-TEST-CASES]] **E1–E5** (the official numbers recorded with every validity
gate green; `PERFORMANCE.md` complete incl. environment + rerun command; the README links it;
`scripts/planning/verify-package.sh docs/to-do/implemented/LOAD-TESTING` green after the move).

**What NOT to touch.** No performance thresholds sneak in (report-only stays). No tuning — findings
are *listed for 7.3*, not applied. The official run happens on the restored guarded rig with the
standard flags — never on a leftover stub/faulted rig.

---

## Cross-cutting acceptance

- **Zero app/library/rego diff** — `git diff main -- '*.java' '*.kt' '*.rego' 'opa-abac-*' 'example-*'`
  is empty for the whole slice (the realm export and `scripts/**` + docs are the entire surface).
- **The validity posture holds** (00-DESIGN §3): no invalid number is ever recorded — every failure
  mode lands on a red run (preflight, pod-state probe, k6 validity thresholds, per-phase checks,
  `MIN_TRACES`, post-seed count).
- **The rig always ends guarded** — after any mode, including red runs (trap on exit): no paused OPA,
  no stub role-source, `ENABLE_OPA=0` never left behind.
- **Registry discipline** — only `dddd…` ids and the `perf` identity; both registered; teardown-on-green.
- **Reproducibility** — a fresh clone + `brew install k6` + the documented commands reproduce every
  table in `PERFORMANCE.md`.
