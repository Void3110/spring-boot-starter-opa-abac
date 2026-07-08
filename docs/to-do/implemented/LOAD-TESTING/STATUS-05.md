---
tags:
  - status/done
  - type/project
  - area/infra
  - area/architecture
---

# STATUS — T5: Resilience-under-fault passes (the B3 stub + OPA `docker pause`)

**Status:** ✅ DONE (2026-07-08)

## What shipped

- `scripts/load/scenarios/resilience.js` — T2's request shape at `RATE` for the fixed three-phase
  timeline (`PHASE` s each — new knob, ADR-pinned 60, env-overridable for smokes), streaming
  per-request points (`--out json`). No all-200 gate (fault-phase denials are the point); the one
  k6-side validity gate is `dropped_iterations == 0`, with the VU pool **fully pre-allocated**
  (`max(100, RATE×5)`).
- `scripts/load/phases.py` — slices the stream by the runner-recorded REAL fault boundaries;
  per-phase counts by class (2xx / typed 403 / other) + success/deny percentiles, fail-closed
  latency during the fault, time-to-recovery (first sustained all-2xx window). **Mode-aware
  per-phase validity** (red, exit 2): healthy must be clean; fault-phase failures must be typed
  403s (never 5xx/network); `opa`/`down` modes demand denials present and deny-p99 under the 5 s
  ceiling (a deny at the ceiling is a hang wearing a status code); recovery must complete.
  Report-only finding: deny p95 > 3× healthy success p95 (the E4 "recorded for 7.3" marker).
- Runner fault modes: `fault-opa` (canonical guarded rig; `docker pause`/`unpause` mid-run),
  `fault-supplier-{transient,down}` (the B3 stub rig deployed guarded with `STUB_MODE=up`; the
  fault phase **recreates the stub into the fault mode** and back — the same committed compose).
  Boundaries stamped conservatively (fault-start before injection, fault-end after clear) so the
  healthy bucket stays provably clean. The EXIT trap now restores unconditionally: unpause OPA +
  stub down + guarded redeploy; `assert_pod_state` gained the role-source parameter (the stub rig
  is a DELIBERATE `resolve-stub` posture — everywhere else it stays a red flag).

## Tests

- **U4 green** (offline, synthetic streams): phase attribution exact at the boundaries;
  time-to-recovery detected; the synthetic slow-deny fails the fault-phase validity red (exit 2);
  incomplete recovery red.
- **I5 green, all three modes** (PHASE=12 RATE=5 smokes; per-phase tables in `results/`):
  - `fault-opa`: healthy clean → fault all-typed 403s → TTR 0.96 s → rig guarded + unpaused.
  - `fault-supplier-down`: 65 typed 403s during fault, **fast denials** (p50 15 ms once the
    breaker opens; max ~515 ms while the guard exhausts) → TTR 8.15 s → stub torn down, guarded
    restored.
  - `fault-supplier-transient`: 73/74 fault-phase requests SUCCEED (the guard's retries absorb the
    blip; 1 deny at 157 ms) → TTR 0.38 s.
- Fix-until-green: attempt 1 reddened on `dropped_iterations` — k6's lazy VU initialization
  dropped iterations at the fault onset (an artifact, not a rate failure). Fixed by
  pre-allocating the pool; the strict gate stays.

## Empirical discoveries (the numbers PERFORMANCE.md will narrate)

1. **A paused OPA fails closed END-TO-END as typed 403s** — the gateway's opa plugin answers 403
   after its ~3 s timeout (no 5xx, no hangs). The fail-closed story holds at the outer layer too,
   but at ~3.0 s per denial — the **slow-deny finding** (report-only, 7.3: the gateway plugin
   timeout budget).
2. **The B3 wall under load denies fast**: supplier-down denials are p50 15 ms once the resolve
   breaker opens (CallNotPermitted), never a hang.
3. **Recovery is breaker-paced**: TTR 8.15 s for the supplier edge (the half-open probe lag after
   the dependency returns) vs 0.96 s for the OPA edge — a real, useful operational number.

## Architecture review + refactor

- **Validity:** every failure mode lands red — untyped fault-phase failures (proven meaningful:
  a paused OPA COULD have produced status-0 timeouts; the gate would catch them), hangs wearing
  403s (the deny ceiling, U4-tested), missing denials in opa/down fault phases ("was the fault
  actually injected?"), incomplete recovery, dropped iterations, unreadable streams. **Applied
  refactor:** the VU pre-allocation (above). The timeline shape is ADR-pinned and untouched.
- **Security / rig-ends-guarded:** the trap's restore is unconditional-cleanup shaped and was
  proven in anger again (attempt 1's red run restored the rig unaided); the stub rig never
  outlives its run; every mode ends with `assert_pod_state true usermgmt` + gateway posture.
- **Layering / patterns:** the scenario only generates load; phases.py only reads the stream;
  the runner orchestrates; the stub reuse is the committed B3 compose (no new infra, ADR §7).

## Decisions

- The supplier fault is injected by **recreating the stub container into the fault mode mid-run**
  (STUB_MODE is boot-time env) — the same committed mechanism the B3 matrix uses per pass,
  extended to a timeline; the DNS name survives, the connection blip lands in the fault bucket by
  the conservative boundary stamps.
- The supplier rig deploys WITH the user-service (uniform seed/bootstrap/canary) — the pods
  resolve from the stub regardless (deploy.sh's stub branch wins), and the posture is asserted.
- The deny-latency ceiling (5 s default, documented in phases.py) separates "fast typed deny" from
  "hang wearing a status code" — validity, not a perf threshold; the 3× marker stays report-only.

## Commit

_(this ticket's commit on feature/void3110/load-testing; see git log)_
