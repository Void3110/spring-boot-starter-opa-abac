---
tags:
  - status/active
  - type/review
  - area/infra
---

# LOAD-TESTING (Phase 7.2) — Code Review

> **Verdict**: Approved with fixes
> **Scope**: The k6 load-testing harness slice — `scripts/load/**` (runner, 4 scenarios, 3 analysis
> scripts, offline test suite), the `perf` realm user, 2 fixture-registry rows, root
> `PERFORMANCE.md` + the package docs. **Zero app/library/rego code** (the app-side unguarded-boot
> fix went separately via PR #61, merged to `main` before this branch's T2).
> · **Branch**: `feature/void3110/load-testing` vs `main` (44 files, +5,732/−161)

## Summary

Path 2B multi-lens adversarial review (16 agents: 8 failure-mode lenses → adversarial refutation →
completeness critic → synthesis). **No Critical findings.** The high-risk surfaces — the
rig-ends-guarded trap chain, the validity posture (no-invalid-number), fail-closed, security,
clean-room, docs-vs-code truth, and the autonomous-run lens — all came back clean. Four findings
confirmed (2 Medium test gaps, 2 Low), all fixed in this commit; two plausible findings were
refuted by the skeptics.

## Critical Issues

None.

## Medium Issues

| # | Issue | Status |
|---|---|---|
| 1 | `list-filter.js` asserted only response *shape* (`"items"` present), never the authorized row **count** — a silently non-discriminating residual would have recorded latencies for the wrong query as valid partial-eval numbers. | **Fixed**: the runner passes `EXPECTED_COUNT` (the emea third, `ceil(FIXTURE_ROWS/3)`); the scenario asserts the envelope's subject-relative `count` against it via a `wrong_count` counter with a `count==0` threshold **in both steady and ladder modes** (a wrong measurement subject is a broken-rig signal, never a saturation datum). |
| 2 | `phases.py`'s mode-divergent `supplier-transient` validity branch (an all-2xx zero-denial fault phase must be VALID there, INVALID under `opa`) had no offline fixture — U4 tested only `--mode opa`. | **Fixed**: `tests/phases-cases/transient-clean.ndjson` + U4b assertions (exit 0 under `supplier-transient`, exit 2 under `opa`, same stream). |

## Low Issues

| # | Issue | Status |
|---|---|---|
| 3 | `amplification.py --limit` was a dead argument (declared, never read; the chunked fetch caps per-slice at 10). | **Fixed**: removed. |
| 4 | QA case U1's "`--help` lists every mode and knob" clause was verified manually at T1 but never committed into the offline suite. | **Fixed**: U1b asserts all 7 modes + 9 knobs in the help output + the unknown-mode red exit. |

## Sibling sweep

Finding 1's pattern (shape-only body check) swept across the other scenarios: **`enrichment.js` had
the same gap** — fixed in the same commit (the `count` check joined its `checks` set; steady-only
scenario, so the `checks rate==1` threshold enforces it). `gate-overhead.js` already asserts its
cut (the body contains the requested catalog id); `resilience.js` deliberately has no body gate
(phase-level validity lives in `phases.py`). Siblings clean otherwise.

## Fail-closed verification

The harness's fail-closed analogues verified clean by the dedicated lens + refutation: every
failure mode lands red (preflight aborts, the docker-exec pod-state probe incl. stub role-source
and paused-OPA, the gateway-posture probe via the APISIX admin API, post-seed count assert, the
seed-time canary, k6 validity thresholds, `knee.py` exit 2 on unreadable summaries,
`amplification.py` MIN_TRACES floor, `phases.py` mode-aware per-phase checks). The trap chain
(`restore_guarded_on_exit` armed across every rig mutation, `heal_rig_on_exit` armed globally,
re-armed — never cleared — on success paths) leaves no exit path that abandons an unguarded,
stub-wired, paused-OPA, or dead-OPA rig; both traps fired in anger during the run and restored the
rig unaided.

## Security audit

Clean. The one rig-visible addition (the `perf` realm user) matches the existing demo users' shape
and holds a single tag-gated membership; no secret material beyond the demo-convention passwords
already in the realm export; `/internal/**` stays un-routed (the harness reaches bootstrap only
via `localhost:28090` like every matrix runner); the baseline flip reuses committed deploy
mechanisms only and asserts posture per pass; clean-room scan green (`verify-package.sh`).

## Concurrency & idempotency

The harness only reads the services under measurement; the seed is deterministic + idempotent
(catalog upsert, categories delete+regen, load-team self-reset); teardown is scoped to the
registry-owned `dddd…` ids; re-runs converge. No gated mutations — Rules 1/2 not in play.

## Wiring & sibling sweep (seams)

Every seam has a consumer and a non-happy-path test: modes/knobs (U1/U1b + red exits), the knee
function (U2 + live ladder), amplification (U3 + live EXCEEDED findings), phases (U4/U4b + three
live modes), the count gates (live smoke green; a wrong count REDs by construction). The one
dead seam found (`--limit`) is removed.

## Autonomous-run check

Applied (the branch is an autonomous run's output): no laziness found (every ticket's
deliverables/acceptance traced to code + tests; the review-gate notes match real diffs — e.g. the
T2 gateway-plugin rewire and T5 VU-pool fix are real, verifiable changes); no self-preferential
bias (STATUS notes record the run's own failures candidly — the OPA OOM, the Jaeger deaths, the
refuted acceptance expectations); no goal drift (the zero-app-code boundary held for the whole
branch — the one app change was escalated to the maintainer and landed as PR #61 on `main`;
report-only held — exceeded bounds became findings, not fudged tables).

## What's done right

The validity posture is real, not aspirational — it refused two degraded-rig windows during the
official run; the trap restores were proven under genuine failures; the amplification measurement
*disproved* one of the library's own pinned claims and the report says so plainly (the per-row
resolve finding is 7.3's headline input); the offline suite makes the analysis functions testable
without a rig.

## Test results

- `./gradlew build` — **green** (all modules; no app code changed on this branch).
- `scripts/load/tests/test-offline.sh` — **green** (U1/U1b/U2/U3/U4/U4b, 30 checks).
- Live guarded smoke with the new count gates — **green** (3 scenarios + attributions).
- The official suite ran during T6 (see `PERFORMANCE.md` + STATUS-06); no rego touched → no
  `opa test`; the newman suites are untouched by this branch.
