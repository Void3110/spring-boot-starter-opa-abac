---
tags:
  - status/done
  - type/project
  - area/infra
  - area/architecture
---

# STATUS — T3: Partial-eval list scenario + the ceiling ramp (knee detection)

**Status:** ✅ DONE (2026-07-08)

## What shipped

- `scripts/load/scenarios/list-filter.js` — GET the load catalog's category list as `perf` (the PE
  path end to end: role resolve on the governing catalog, Compile, the tag-conjunct residual as a
  REAL SQL cut — live-verified `count=334` of 1000, the emea third). Steady mode carries the same
  validity gates as gate-overhead; `LADDER_STAGE=1` swaps them for the **auth-vs-saturation split**:
  saturation signals (slow p99, errors, dropped iterations) are the knee function's DATA and never
  exit red, while a 401/403 increments an `auth_failures` counter whose `count==0` threshold DOES —
  a broken rig/ACL chain lands red, never an instant fake knee.
- `scripts/load/knee.py` — the ADR 0021 §5 knee evaluation in one offline-testable function
  (p99 > 1 s OR >1% failed OR >1% dropped, drop rate computed from completed+dropped = offered);
  shell-parseable verdict line; exit 2 on an unreadable summary (invalid run — the caller goes red).
- Runner `ceiling` mode: guarded-posture asserted, seed (the post-seed count assert IS the
  fixture-dependency gate before stage 1), warm-up at the first stage rate, then LADDER stages of
  LADDER_DURATION seconds with per-stage knee evaluation, **early stop at the knee**, the honest
  "no knee within the ladder" verdict, and a structured `ceiling.json` (per-stage verdicts + ceiling
  + knee + breaking signal). New `LADDER_DURATION` knob (default 60, ADR-pinned for the official run).
- `scripts/load/tests/` — the offline (U*) harness: `test-offline.sh` + committed synthetic stage
  summaries (`knee-cases/`: clean, latency-knee, errors-knee, drop-knee). T4/T5 plug U3/U4 in here.
- Steady `list-filter` joined the guarded scenario set (`guarded` mode + `full`'s guarded pass;
  baseline stays gate-overhead-only — the list is a guarded-only scenario per the ticket).

## Tests

- **U2 green** (`tests/test-offline.sh`): latency-knee, errors-knee, drop-knee detected; clean
  ladder → honest no-knee; malformed summary → exit 2 (red). Plus U1 re-checks (help/mode/knob
  surface, LADDER + LADDER_DURATION validation red paths).
- **I3 green** (`LADDER=5,10 LADDER_DURATION=15 WARMUP=5 ceiling`, exit 0): per-stage summaries +
  verdict lines landed in `results/`; no-knee reported honestly (5→423ms p99, 10→848ms p99, zero
  errors/drops); the fixture-count assert fired before stage 1; `ceiling.json` structured.
- Live red proof: a poisoned-token ladder stage exits **99** via the `auth_failures` threshold.

## Architecture review + refactor

- **Validity:** the substantive design point was the ladder's threshold split (above) — ADR §5 makes
  drops/errors knee SIGNALS, ADR §8 demands no invalid number; the split honors both, and the
  poisoned-token proof pins it. An unreadable stage summary aborts red (knee.py exit 2, U2-tested).
  No further refactors needed after review — the knee logic went into its own testable file from the
  start (the SOLID check drove the file split).
- **Boundary/layering/patterns:** scripts/** + docs only; the scenario only generates load, knee.py
  only reads exports, the runner orchestrates; ADR criteria copied verbatim into knee.py with a
  "pinned — do not add criteria" note.
- **Observations for the record:** (1) the default list page carries its per-row `_actions` map
  (one bulk eval per page — enrichment is on by default on the rig), so the ceiling measures the
  endpoint as adopters hit it; the official ladder may knee early — that is a legitimate
  report-only finding for 7.3, not something this slice tunes. (2) One unreproduced anomaly: the
  first mini-ladder attempt stalled when run through an output-filtering pipe (killed at 5 min);
  the identical run redirected to a file completes green — watch for recurrence in T6.

## Decisions

- Stage summaries are judged **per stage in isolation** (the ADR definition needs only that stage);
  the ceiling/knee bookkeeping lives in the runner loop; `ceiling.json` carries the full ladder for
  PERFORMANCE.md.
- The list scenario requests the endpoint's **default page** (no perPage override): the ADR pins no
  page size, and the default is what an adopter measures first; T4 owns the explicit 100-row
  enriched page.

## Commit

_(this ticket's commit on feature/void3110/load-testing; see git log)_
