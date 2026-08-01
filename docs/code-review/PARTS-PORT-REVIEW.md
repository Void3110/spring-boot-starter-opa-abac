---
tags:
  - status/active
  - type/review
  - area/methodology
  - area/docs
---

# PARTS-PORT — Code Review

> **Verdict**: Approved with fixes
> **Scope**: The parts-orchestration port — `check-parts.py` + the `verify-package.sh` [9] gate,
> scaffold `--parts`/`--planning-root`, flow-guide §4a/§2a, the untracked runner + review-wiring
> skills, and the live two-part delegation proof. · **Branch**: `feature/void3110/parts-port` vs
> `main` (12 files, +840/−41; no `.java`, no `.rego`, no runtime path).

## Summary

Path 2B (multi-lens adversarial workflow): 8 lenses → adversarial refutation → completeness critic;
19 agents. **Zero Critical. 6 confirmed (2 Medium, 4 Low), all fixed on the branch; 3 refuted.**
The two load-bearing mechanisms (the [9] gate's fail-closed exit discipline and the additive
guarantee) survived every lens — all confirmed findings are documentation-drift and test-durability
classes around them. This review round also carries a **maintainer-directed flow change**: the
whole-delivery `/deep-review` (layer 3) now runs **automatically at every run's close-out** (it was
never skipped in practice); phase ④ becomes read-the-review → push → PR → merge.

## Critical Issues

None.

## Medium Issues

1. **Guide self-contradiction** — the new §4a declared the runner skill canonical while §8 still
   said "autonomous-implement (template only, for now)"; a reader landing on §8 first would conclude
   parts orchestration doesn't exist and paste the bare prompt. **Fixed**: §8 bullet rewritten (the
   skill exists, three modes, §4a canonical); the portable template gains an explicit
   **pre-parts scope banner** ("port from §4a, not from memory").
2. **No committed regression test** — all 31 QA cases for the new gates ran in throwaway scratch
   drivers; the two review-caught fail-closed holes (UnicodeDecodeError, fullwidth colon) had no
   durable lock-down. **Fixed**: `scripts/planning/test-parts-gates.sh` committed — 36 assertions,
   self-locating, repo-state-independent, scratch-fixture-only, exact exit codes; green.
   (`check-citations.py` has the same pre-existing gap — noted, not expanded here.)

## Low Issues

3. **Home-dir path in the public `.mulch` store** — the pre-existing mx-e621ea record (2026-07-06,
   not written by this branch) contained the literal maintainer home path its own lesson warns
   about. **Fixed**: record reworded to `/Users/<name>`; a committed-tree sweep found no other
   occurrence. The finding's suggestion to extend the [3] scan beyond the package dir was
   **deliberately not taken this round**: [3] is a *package* gate and widening its scope would
   invalidate the slice's byte-identical additive proof; the repo-wide sweep belongs to the
   standing pre-publish secret sweep (`opa-abac-publish` discipline) — recorded as a follow-up
   candidate, not a silent skip.
4. **`DECOMPOSE-SKILL-TEMPLATE.md` stale slots** — no `[9]`/`check-parts.py` in the gate slot, no
   `--parts`/`--planning-root` in the scaffold slot. **Fixed**: both slots updated.
5. **`AI-ENGINEERING-METHODOLOGY.md` stale gate enumeration** — mirrors the §3 list the branch
   updated. **Fixed**: execution-parts coverage appended.
6. **The `**Validated:**` convention documented in no committed file** — the runner's Phase 0 gates
   on it, but it lived only in untracked skills. **Fixed**: documented in guide §3 (phase-②
   close-out).

## Refuted (skeptic outcomes, sanity-checked)

- *check-parts.py non-executable*: functionally irrelevant (always invoked via `python3`), but the
  exec bit was aligned anyway — the other three planning scripts carry it (house style, my call
  with context the skeptic lacked).
- *Portable runner template / deep-review template as unswept siblings*: correctly refuted as
  pre-existing template scope, not branch regressions; the pre-parts banner (fix 1) closes the
  misroute risk regardless. `DEEP-REVIEW-TEMPLATE.md` left untouched by design — the local skill is
  the live authority and the template is explicitly seeded-not-finished.

## Fail-closed verification

Re-verified at review time via the new committed test: near-miss/malformed/unreadable/binary inputs
all exit 1 (never "absent"); absence exits 0 without opening the decomposition; the [9] wiring
distinguishes exit codes (a checker that cannot run fails the package); bounds are checked without
range materialization. The marker checks' error→HALT discipline was drill-proven in T4 (raw-grep
ERE error demonstrated live) and used live in T5's two collections.

## Security audit

No runtime/authz surface in the diff. The slice's own widening classes were each re-checked: a
near-miss read as absent (closed, tested incl. fullwidth colon); a marker-check error read as
no-escalation (closed, three exit codes); a fixture leaking into the real tree (repo
`git status --porcelain` clean end-to-end); a part editing an earlier part (live-proven boundary:
part 1 left part 0's artifact byte-identical and refused the orchestrator's index). One real
find: the pre-existing home-path leak in the public `.mulch` file (fix 3).

## Concurrency & idempotency

No locks/transactions in scope. The relevant invariants: synchronous-only delegation
(`run_in_background: false` load-bearing, parallel forbidden-not-degraded), gates read-only and
re-runnable, the scaffold convergent on re-run (IDEM case in the committed test).

## Wiring & sibling sweep

Every new seam has a consumer and a non-happy-path case (now durably: the committed test).
Sweeps run this round: gate-enumeration carriers (guide §3/§9, methodology, decompose template —
all now name execution-parts); "template only" phrasing (zero left); committed-tree username/home
path (zero left); exec bits (aligned).

## Autonomous-run check

The five STATUS notes were audited against the diff by the workflow's lenses: no laziness or
self-preferential bias found — STATUS-01/04 record their own gate misses (the [8] wikilink trips,
the amend) rather than glossing them, and the E7 split in STATUS-05 explicitly separates
proven-live from drill-only, including the skill-invocation attestation caveat. No goal drift: the
additive guarantee was byte-diff-proven, not asserted.

## What's done right

The exit-code contracts as the API (0/1/2 everywhere, error ≠ absent); the corpus calibration
sweep + old-vs-new byte-diff as the additive proof; the live two-part delegation closed entirely
from disk; honest limitation records (planted `**Validated:**` line, `/private/tmp` deviation).

## Flow change shipped with this review (maintainer decision, 2026-08-01)

Layer 3 (`/deep-review`) now runs **automatically at every run's close-out** — single-session and
ORCHESTRATOR alike (never inside a PART-RUNNER, where 2B is unreachable) — followed by the
`autonomous-runs` retrospective (its QA field filled in-run). Changed: guide §1 (lifecycle +
division of labour), §4a layer-3 row, §9 gate table; the runner skill's Phase 3; root `CLAUDE.md`
retro timing. The §4 prompt skeleton is untouched — the skill is the automation home. Phase 2's
single-session loop remains the bare prompt verbatim (the additive floor binds the loop; the
auto-review is close-out).

## Test results

- `scripts/planning/test-parts-gates.sh` (new, committed): **36/36 PASS**
- `verify-package.sh PARTS-PORT`: green ([1]–[9])
- `./gradlew build` / sonar-local / `opa test` / newman: **N/A by construction** — the diff touches
  no Gradle source set, no `.java`, no `.rego`, no runtime path (scope report confirmed 0 files
  under `src/`, `infra/`, build files)
- Review rounds (per the loop-termination rule): **round 1** — the 19-agent workflow (6 confirmed →
  fixed); **round 2** — a fresh-eyes verification pass over the fix diff (10/10 confirmed-clean;
  2 residual nits — an unscoped byte-identity claim in the runner skill's frontmatter, three coarse
  phase-④ mappings in §8 — → fixed); **round 3 (terminal)** — narrow mechanical re-verification:
  **no fixes**, gate green, 36/36.
