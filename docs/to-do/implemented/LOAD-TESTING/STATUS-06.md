---
tags:
  - status/done
  - type/project
  - area/infra
  - area/architecture
---

# STATUS — T6: The official baseline → `PERFORMANCE.md` + docs + folder move

**Status:** ✅ DONE (2026-07-08)

## What shipped

- **Root `PERFORMANCE.md`** — environment + methodology, the headline guarded-vs-baseline table
  (p50 **+2.68 ms / +64.5 %** at 50 req/s, REPS=3 medians), the ceiling (knee at the FIRST 10 req/s
  stage, latency signal — no passing stage), the attributed amplification tables (resolve 2/22/102
  vs the pinned 1 — EXCEEDED; eval bounded at 2 bulks/page), the three per-phase fault tables
  (all-typed denials; the ~3 s gateway-plugin deny vs the ~5 ms breaker deny; TTR 0.5/9/10 s), the
  one-command rerun, and the laptop-relative caveat.
- `README.md` — the Performance section linking it. `scripts/load/README.md` finalized (usage,
  all knobs, reservations, artifacts, the **official-run protocol**: quiesced machine, fresh
  images/rig/trace store, below-knee steady rates labeled).
- Housekeeping: [[LOAD-TESTING]] status table all-✅ + Shipped banner; ADR 0021 → shipped; the
  roadmap row → shipped; the folder moved to `docs/to-do/implemented/`.

## The official run — what happened (fix-until-green, all recorded)

1. **`REPS=3 full` at 50 req/s**: the headline delta + gate amplification (1500 traces) recorded
   green; then the list warm-up **collapsed the rig** (p99 59 s, 45 % errors) — and took **OPA down
   with it (OOM-killed)** under the bulk-eval backlog. The reorder (T6 prep) had already protected
   the delta. Runner hardened: a universal EXIT heal-trap restarts a dead OPA in every mode.
2. **Ceiling**: attempt on the degraded rig was REFUSED by the validity gates (auth_failures red) —
   the posture working as designed; on a fresh rig: **knee at the first stage** (10 req/s, p99
   3.59 s, zero errors/drops). The list is resolve-amplification-bound, not residual-bound.
3. **Steady list/enrichment**: sustained REPS=3 at 5 req/s degrades progressively (reps 1–2 valid,
   rep 3 collapse) — traced to the **span-volume amplification**: a 20-row list request is a
   ~2,000-span trace; sustained list load OOMs Jaeger and the export backpressure drags the app.
   Protocol fix (documented): fresh trace store before official runs. The amplification query
   itself then killed Jaeger (a single 150-giant-trace API response) — `amplification.py` now
   fetches in **chunked sub-windows** (limit 10, dedup by traceID). Final pass green: steady
   numbers at 5 req/s (labeled below-knee) + amplification tables for all three scenarios
   (95/95/94 traces, counts constant).
4. **The three fault timelines at 50 req/s**: all green first try, rig ended guarded every time.

## The findings ledger (all report-only, 7.3's input)

1. **Per-row resolve amplification** (the dominant finding): 2/22/102 identical resolve calls per
   single-GET/20-row/100-row request — a request-scoped resolve memo collapses them to 1.
2. **Span-volume amplification** (corollary): ~100 spans/row under `always_on` — saturates the
   trace pipeline; adopters should sample.
3. **The list ceiling** is an order of magnitude below the single-GET path (knee < 10 req/s vs
   healthy 50 req/s singles).
4. **OPA-outage denials are typed but slow** (~3.0 s — the gateway plugin timeout); the library's
   own breaker wall denies in ~5 ms.
5. **Recovery is breaker-paced** (~9–10 s half-open lag on the supplier edge).
6. Saturation can OOM OPA (bulk backlog) and Jaeger (span flood) — the heal-trap + fresh-rig
   protocol keep the harness honest about it.

## Acceptance (E1–E5)

- **E1** ✓ the delta from REPS=3 medians, identical RATE + gateway posture asserted per pass.
- **E2** ✓ the full ladder ran with the knee stage + signal recorded (first-stage knee is the
  honest result; the "no passing stage" ceiling is stated, not fudged).
- **E3** ✓ expected-vs-measured for all three guarded scenarios — with the resolve rows EXCEEDED
  recorded as findings (the caches claim is *disproven* on the resolve edge and *proven* on the
  eval edge; the report says exactly that).
- **E4** ✓ per-phase tables for all three fault modes; fail-closed latency bounded (typed 403s,
  no hangs) with the slow-deny recorded as a 7.3 finding; TTR reported.
- **E5** ✓ `PERFORMANCE.md` complete incl. environment + rerun; README links it;
  `verify-package.sh` green on the moved folder (run at the folder move).

## Commit

_(this ticket's commit on feature/void3110/load-testing; see git log)_
