---
tags:
  - status/done
  - type/project
  - area/infra
  - area/architecture
---

# STATUS — T4: Enrichment fan-out scenario + `amplification.py` (the attributed ratio)

**Status:** ✅ DONE (2026-07-08)

## What shipped

- `scripts/load/scenarios/enrichment.js` — the `perPage=100` category page WITH `_actions` as
  `perf` (guarded-only), steady validity thresholds, body check pins `_actions` presence.
- `scripts/load/amplification.py` — samples one scenario's measured window from the Jaeger API
  (microsecond window, single-request traces only — exactly one catalog SERVER span), classifies
  outbound client spans by URL (resolve / governed-scope / tag / decide / batch-eval / compile),
  and emits the expected-vs-measured table (markdown + JSON) beside the ADR 0021 §6 pinned
  bounds. **Report-only semantics:** a measured count above its pinned bound is a recorded
  FINDING (exit 0 — valid, load-bearing data for 7.3); the exits are validity-only (exit 2 on a
  thin window below the MIN_TRACES floor — never extrapolates — or unreadable input). `--input`
  runs the same code path offline (U3).
- Runner integration: each guarded scenario's measured window is captured (epoch bounds around
  the reps) and attributed automatically right after the run; the floor self-scales
  (`min(500, offered/2)`, ≥20 — the ADR's ~500 for official windows without a new knob); a 5 s
  settle lets the OTEL batch exporters flush the window tail. `enrichment` joined the guarded
  scenario set (`guarded` mode + `full`'s guarded pass; baseline stays gate-overhead-only —
  amplification is a guarded-pass-only metric, ADR §6).
- `tests/amplification-cases/` — committed synthetic Jaeger-response fixtures + U3 assertions in
  `test-offline.sh`.

## Tests

- **U3 green**: clean gate-overhead fixture attributes 1 resolve + 1 decide (within); the
  markdown table renders; a per-row-scaling fixture lands as EXCEEDED findings; the MIN_TRACES
  floor exits 2 on a thin window.
- **I4 green as a harness** (`RATE=5 DURATION=15 WARMUP=5 guarded`, exit 0): all three guarded
  scenarios ran and attributed (76/79/71 traces); tables + JSON landed in `results/`.

## THE FINDING (report-only — the 7.3 headline input)

The attribution **refutes the resolve-side boundedness claim** the ADR pinned as the expected
bound. Measured, constant across every sampled trace (median == max):

| scenario | resolve | decide | compile | batch-eval |
|---|---|---|---|---|
| gate-overhead (single GET) | **2** (pinned 1) | 1 ✓ | — | 1 (unpinned — enrichment rides along) |
| list-filter (20-row default page) | **22** (pinned 1) | 1 | 1 ✓ | 2 |
| enrichment (100-row page) | **102** (pinned 1) | 1 | 1 | **2** (pinned 1) |

Every resolve in a request hits the IDENTICAL target (`effective-role` for the same user + the
same governing catalog) — **resolve scales ~1:1 with page rows** (gate + list-authorizer + one
per enriched row). The eval side IS bounded: **2 batch evals for a 100-row page, not 100** — the
"not N×rows" half of the claim holds; the pinned "1" does not. A request-scoped resolve memo
would collapse N identical lookups to 1 — that is 7.3's decision, not this slice's (zero
app/library change; the harness measures, it never fixes).

## Architecture review + refactor

- **Validity:** the thin-window floor never extrapolates (U3-tested); only single-request traces
  are attributed (a mid-scenario health-check or foreign trace can't skew counts — operation +
  window + service + single-SERVER-span filters); windows are per-scenario and sequential, so the
  shared server-span name between list-filter and enrichment cannot cross-contaminate. **The
  substantive review decision:** exceeded bounds are FINDINGS, not red runs — ADR §8's validity
  gates protect against invalid numbers, not unflattering ones; aborting on a true measurement
  would be the harness editorializing.
- **Boundary/layering:** scripts/** + docs only; the scenario only generates load, the analyzer
  only reads the Jaeger API, the runner orchestrates.
- **Wiring:** consumers — PERFORMANCE.md's amplification section (T6), 7.3 (the finding);
  non-happy paths U3-tested (thin window, EXCEEDED) and the runner reddens on analyzer exit 2.

## Decisions

- Expected bounds live as data in `amplification.py` (the ADR claims, verbatim); observed ops
  without a pinned bound render as `unpinned` — visible, never silently dropped.
- The MIN_TRACES floor self-scales rather than adding a knob (the U1 help surface stays the
  pinned set + LADDER_DURATION).

## Commit

_(this ticket's commit on feature/void3110/load-testing; see git log)_
