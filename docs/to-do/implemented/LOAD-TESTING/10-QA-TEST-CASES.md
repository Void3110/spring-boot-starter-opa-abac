---
tags:
  - status/planned
  - type/project
  - area/infra
  - area/architecture
---

# LOAD-TESTING — QA test cases

> Concrete cases; each becomes a ticket's *Acceptance*. **This slice ships a harness, not app code**,
> so the usual ladder adapts honestly: **U = offline** (no rig — script syntax, and the analysis
> functions against committed synthetic fixtures), **I = live smoke** (the rig up, short
> rates/windows — proves orchestration and wiring, not numbers), **E = the official measurement run**
> (full windows, `REPS=3` — the numbers that land in `PERFORMANCE.md`). The e2e asserts the **actual
> cut** — deltas computed, knee signals named, bounds matched, phases sliced — never just "it ran".

## Unit / offline (U*)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| U1 | `bash -n` every `scripts/load/*.sh`; `run-load.sh --help` | scripts parse; help lists every mode (`guarded/baseline/full/ceiling/fault-*`) and knob (`RATE/DURATION/WARMUP/REPS/FIXTURE_ROWS/LADDER/KEEP_FIXTURES`) | T1 |
| U2 | The knee function fed **synthetic per-stage summaries** | latency-knee (p99 > 1 s) detected; drop-knee (>1 % dropped iterations) detected; clean ladder → honest "no knee within the ladder"; ceiling = last passing stage + the breaking signal named | T3 |
| U3 | `amplification.py` against **committed synthetic trace fixtures** | attributed counts correct per downstream + operation; the expected-vs-measured table renders; `MIN_TRACES` guard **aborts red** on a thin window (never extrapolates) | T4 |
| U4 | `phases.py` against a **synthetic request stream** | per-phase attribution correct at the boundaries; time-to-recovery detected; the fault-phase validity check **fails red** on a synthetic slow-deny | T5 |

## Integration / live smoke (I*) — rig up, short rates/windows (`RATE=5 DURATION=15 WARMUP=5`)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| I1 | Preflight + seed + teardown | `baseline` mode against a guarded rig **aborts red before any load** (the pod-state probe, not trust-the-flag); post-seed the `dddd…` category count == `FIXTURE_ROWS` (psql-asserted); teardown removes **only** `dddd…` fixtures; `KEEP_FIXTURES=1` keeps them | T1 |
| I2 | A smoke `full` run (gate-overhead) | both passes complete; two summary JSONs + the **delta block** land in `results/`; each pass's pod state was asserted; **the rig ends guarded** | T2 |
| I3 | A mini-ladder run (`LADDER=5,10`, short stages) | per-stage summaries land in `results/`; no-knee reported honestly; the fixture-count dependency assert fires before stage 1 | T3 |
| I4 | Smoke amplification on gate-overhead + enrichment | the measured table matches the expected bounds — single GET → 1 resolve + 1 decide; the 100-row enriched page → **1 batch eval, not 100**; attribution names the downstream + operation | T4 |
| I5 | Short-phase fault smokes | `fault-opa`: pause → fault-phase denials observed → unpause → recovery detected → **rig healthy and guarded after**; `fault-supplier-{transient,down}`: stub rig deployed, run, **guarded rig restored**; per-phase tables in `results/` | T5 |

## E2E / the official run (E*) — full windows, `REPS=3`, quiesced laptop

| ID | Case | Asserts (the actual cut) | → Ticket |
|---|---|---|---|
| E1 | The headline: guarded vs baseline gate overhead | p50/95/99 for both passes + absolute/relative delta recorded from `REPS=3` medians; identical `RATE` and gateway posture across passes; every validity gate green | T6 (proves T2) |
| E2 | The PE ceiling | the full ladder runs; the knee stage + **which signal broke** (latency vs drops vs errors) recorded; ceiling = last passing stage | T6 (proves T3) |
| E3 | The amplification table | expected-vs-measured for all guarded scenarios, all within bounds — the request-scoped-caches claim proven with attributed counts | T6 (proves T4) |
| E4 | The fault story | per-phase p50/95/99 + denial counts for all three fault modes; **fail-closed latency during the fault is bounded** (no slow-deny finding — or it is *recorded as a 7.3 finding*); time-to-recovery reported | T6 (proves T5) |
| E5 | The deliverable | `PERFORMANCE.md` at the repo root: environment + methodology + every section + **the one-command rerun**; README links it; `verify-package.sh` green on the moved folder | T6 |

## Headline proof

**E1** — the guarded-vs-baseline delta from the identical gateway: the number an adopter asks for
first, produced by a mechanism (the `ENABLE_OPA=0` flip) that never shipped a weakened path.
**I4/E3** (one batch eval per enriched page; bounded per-request chatter) and **E4** (fast, typed
denials under outage) are the library's design claims turned into measured evidence. **U2/U3/U4**
keep the analysis honest offline — the knee, attribution, and phase logic are testable without a rig,
so a wrong number can never hide behind "the rig was busy".
