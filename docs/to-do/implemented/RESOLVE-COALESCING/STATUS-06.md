---
tags:
  - status/done
  - type/project
  - area/abac
  - area/spring
---

# STATUS — T6: Gateway timeout + adopter notes + re-measurement + e2e

**Status:** ✅ DONE — the slice's proof is measured and recorded: **resolve wire calls 2/22/102/51 →
1/1/1/2**, the gate's p99 tail collapsed, OPA survives the 50 req/s stage, the gateway outage-deny
wall dropped 3× — and the re-measurement surfaced (and fixed) a **second latent library defect**
plus two honest bound corrections.

## What shipped

- **Gateway `opa` plugin `timeout` bounded** in `infra/apisix/init-routes.sh` — final value
  **1000 ms** (integer milliseconds, live-schema-verified). The planned 500 ms was measured and
  **rejected**: this OPA also serves the app's compile + bulk evals, and its *loaded* tail crosses
  500 ms even at 10 req/s of list traffic → steady-state 403s (2997 `err: timeout` gateway lines
  attributed). 1000 ms keeps the outage-deny wall bounded (measured p50 **1005 ms**, was 3005) with
  zero steady-state denies. `infra/README.md` documents the knob with the loaded-p99-not-idle rule.
- **`PERFORMANCE.md` rewritten in place** — the 7.3 re-baseline: headline delta (+2.28 ms p50, p99
  tail 36.8→11.0 ms), the re-measured amplification table (all four scenarios within the re-pinned
  bounds, 282–650 traces each, median==max), the ceiling re-run, steady deltas, the multi-root
  before/after (**51 → 2**), the fault table (deny wall 3005→1005 ms, recovery 0.36 s), the
  comparability fine-print (the two latent defects), and the adopter notes (memo flag, batch SPI,
  `parentbased_traceidratio` sampling, breaker knobs `open-duration`/`half-open-probes` —
  document-only, no config change).
- **The `ResilientOpaClient.allowAll` sentinel fix** (own commit, `fix(resilience):`): it retried
  on *any* `false` instead of the all-false transport pad — invisible pre-7.3 because the advisor
  wiring hole (T2) left every consumer on the unwrapped client; once the wrap became real it
  doubled every mixed-verdict page's bulk load (+50 ms backoff), blew the steady p50 to 58 ms, and
  OOM-killed OPA at 50 req/s. Predicate now mirrors `compile`'s `fromError` principle: retry the
  exact failure signal only. Regression tests pin mixed-never-retried / all-false-recovers.
- **Harness fixes** (measurement infrastructure, found by the runs): `amplification.py`'s chunked
  fetch could never reach the official 500-trace floor (30×10=300 cap — the published 7.2 numbers
  came from the pre-chunking single query); the per-slice limit now scales to the target. The
  ceiling ladder's `auth_failures==0` stage gate is replaced by the canary-probe guard + the
  `>1 % failed` knee signal — with a bounded gateway timeout, saturation-adjacent stalls surface
  as timeout-*denies* and are knee **data** (`wrong_count==0` stays). `scripts/load/README.md`
  reconciled (posture + official protocol + the multi-root step).
- **Guides reconciled:** [[ACTION-ENRICHMENT]] (two-pass flow, the batch, the whole-group outage
  rung), [[HTTP-RESILIENCE]] (memo-outside-guard; the batch as ONE guarded call; the deferred
  manager making the gate share decorated beans), [[ABAC-AUTHORIZATION]] (the `lookupAll` contract
  + the memo + the flag), `infra/README.md` (the timeout knob).
- **e2e:** the catalogs-list `_actions` cut cell was **absent** — added as **E7a/E7b** to the
  action-enrichment matrix (writer's row all-true vs reader's view-only cut on `GET /catalogs`, the
  multi-root batch path live through APISIX). Also swept: the pre-existing **E6 staleness** (the
  catalog verb set gained `assign-tags` with taggable catalogs, ADR 0022/PR #65, and the fleet was
  never re-run after it).
- `[[USER-STORIES]]` **F5 ticked**; index + roadmap finalized.

## The measured proof (QA P1–P4, E1–E2)

- **P1 (amplification, trace-attributed, constant across samples):** single GET resolve **2→1**;
  20-row list **22→1**; 100-row page **102→1**; multi-root 50-row **51→2** (re-pinned: the
  authorizer's query-time coarse single + ONE response-time batch — two questions, two lifecycle
  points); batch-eval 2/2 on category lists (re-pinned, finisher+affordance), 1 on the catalogs
  list (its residual fully reduces — no finisher bulk); compile 1; decide 1.
- **P2 (ceiling): half-and-half, honestly.** OPA **survives the 50 req/s stage un-OOM-killed**
  (7.2: killed; the saturation shape is now fail-closed page-shrinkage + typed timeout-denies, and
  the rig self-recovers) — but **the knee is still at 10 req/s** by the pinned p99>1s definition
  (p99 at the knee 3.59 s → **1.37 s**, fails 0.17 %). The 25-req/s-passes half of the pinned claim
  is **disproven**: with the resolve fan-out gone the list is now bound by **OPA bulk-eval latency**
  (2 bulks/page; loaded bulk answers reach ~0.4 s) — recorded as the next tuning frontier
  (an OPA/policy cost, not a per-row library fan-out; out of 7.3's scope by design).
- **P3 (multi-root before/after):** resolve 51 → 2 per page; steady p50 136.7 → **117.1 ms**
  (artifacts: `results/20260710-145528-multi-root/` vs `results/20260710-185735-multi-root/`).
- **P4 (fault-opa):** deny p50/p95 **1005/1010 ms** (was 3005/3010), all typed 403s, recovery
  0.36 s.
- **Headline delta (full mode, 50 req/s, REPS=3):** guarded p50 6.91 / baseline 4.63 → **+2.28 ms**
  (7.2: +2.68); guarded p99 10.96 (7.2: 36.76). Steady: list p50 173.5→**155.2**, enrichment p50
  266→**174.4**.
- **E1/E2 (the fleet):** all 14 runners green, each on its documented rig posture — 12 on the
  directory rig (`ENABLE_DIRECTORY=1`; the team matrix's own preflight demands it), `run-tests` +
  `run-matrix` on the basic demo-supplier rig (their Phase-3 prereq — realm-role creates are
  demo-supplier semantics; on the http rig an unprovisioned `editor` correctly resolves no role),
  resilience on the B3 stub rig, the SPA smoke on the bearer-only posture. `opa test`: **212/212 +
  32/32, zero Rego changed** (git-verified).

## Architecture review + refactor

This ticket's review WAS the re-measurement — three substantive findings, all addressed:

1. **The `allowAll` any-false retry (library defect, fixed).** See above. The chain of discovery is
   the story: T2's deferral made B3's wrap real on the gate path → the wrap's latent
   retry-the-sentinel conflation became measurable → the first honest full run exposed it.
2. **500 ms was the wrong timeout for this rig (config corrected before shipping).** The knob's
   comment now encodes the rule that matters: tune against the *loaded* p99 of the OPA that also
   serves your app's evals — never its idle latency.
3. **Demo-data contamination + rig-posture mismatches in the fleet** (not 7.3 regressions,
   documented): leftover interactive-demo teams (`Something New`, `Directory slice demo`) polluted
   the isolation matrix's membership cut (wiped — recreatable through the SPA); the basic matrices
   belong to the demo rig; E6 was stale since PR #65.

## Decisions

- Timeout 1000 ms over 500 (measured steady-state 403s at 500 — see above).
- Ladder auth-failure posture: canary-probe guards the broken chain; stage 403s are knee data.
- The disproven 25-req/s half of P2 is **recorded, not hidden** — report-only posture (ADR 0021);
  the durable claims that DID land: no OOM at 50, knee-signal p99 2.6× better, fail-closed under
  saturation.
- The `wrong_count` gate stays in ladder stages (a fail-closed shrinking page trips it at deep
  saturation — that red aborted the manual 50-req/s probe after its data was captured; acceptable
  for a probe, and the official ladder early-stops at the knee before it matters).

## Commit

`perf(rig): gateway OPA timeout + 7.3 re-baseline (PERFORMANCE.md) + fleet green (T6)` — see git.
The `fix(resilience)` commit precedes it.
