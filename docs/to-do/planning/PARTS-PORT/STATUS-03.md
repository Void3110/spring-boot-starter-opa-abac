---
tags:
  - status/done
  - type/project
  - area/methodology
---

# STATUS — T3: the `/autonomous-implement` runner skill + the part brief + the flow-guide §4a

**Status:** ✅ DONE

## What shipped

- `.claude/skills/autonomous-implement/SKILL.md` (**untracked** — named here because no diff
  carries it). Step 0 of every phase: an **absolute `cd`** into the working repo (with the
  out-of-tree-package variant: walk up from the package path to `.git`, never from the shell cwd).
  Phase 0: package resolution (bare name → this repo; explicit path as-is) → mechanical gate re-run
  (class A refusal at [9]) → adversarial evidence read from the index's `**Validated:**` line
  (absent → report and ask **once**; never silently proceed, never re-run the workflow uninvited) →
  resume-awareness from filled STATUS stubs → **mode resolution as a mechanical, drillable table**
  keyed only on `check-parts.py`'s exit code + first line + the `part N` argument (single-session /
  ORCHESTRATOR / PART-RUNNER / contradiction-stop; checker-failed → stop, never "absent").
  Phase 1: branch, identity, Mulch (incl. the staged-sweep guard), rig commands only as the package
  prompt names them. Phase 1.5 (ORCHESTRATOR only): brief-build → **synchronous** delegation
  (`Agent`, `general-purpose`, `run_in_background: false` load-bearing; parallel forbidden, not
  degraded — unavailable → stop) → **five-check collect from disk** (commits since baseline · gates
  green · clean tree modulo declared untracked deliverables · owned STATUS filled with
  placeholder-counts-as-empty · layer-2 record in three states, a behavior-changing fallback
  finding = class-B stop) → both marker checks (three exit codes, error → HALT; exact commands land
  in §Marker checks with T4) → the orchestrator ticks the index itself. Phase 2: single-session =
  the package prompt's loop **verbatim**; PART-RUNNER = the loop for its range + the inline-2A
  layer-2 review + the two markers; ORCHESTRATOR implements nothing. Phase 3: single-session adds
  nothing (layer 3 stays maintainer-driven phase ④); ORCHESTRATOR runs layer 3 (`/deep-review`, 2B,
  mandatory under parts) + the `autonomous-runs` retrospective **with the `CONTEXT:`
  compacted-or-not field**.
- `.claude/skills/autonomous-implement/references/part-prompt-template.md` (**untracked**): the
  short brief — points at the skill (fork 3: no duplicated loop); slots for part number/range,
  prior parts' STATUS filenames, the not-yours boundary (index always included), restated repo
  rules (identity, clean-room, staged-sweep, do-NOT-push — restated because repo-local context does
  not auto-inject, mx-4b9171), slice invariants + fail-closed sentence, layer-2 instructions with
  both fixed markers, the five-point machine-checkable end state, and the absolute `cd` as literal
  step 0.
- `docs/guides/AUTONOMOUS-IMPLEMENTATION-FLOW.md` (tracked, D2): new **§4a** — grammar, the three
  modes + OFF state, the fork-10 reconciliation ("do not delegate the implementation" binds the
  implementer; the part-runner still implements directly; the orchestrator implements nothing),
  synchronous-only delegation, the five-check collect, the three review layers with the
  capability-not-preference note, both markers + the shared check discipline (exact commands
  delegated to the skill — one home), and the three failure classes. **§2a gains the partition
  test** ("a slice that fails on context alone partitions; it does not have to shrink"). The §4
  prompt-template skeleton untouched. The §3/§9 cross-references to §4a completed (deferred from
  T1 so no reference dangled).

## Tests

E1 drill (throwaway script over two scaffold-built fixture packages via `--planning-root`,
implementing the skill's Phase-0 step-5 table **verbatim**): **5/5 PASS** — undeclared+no-arg →
single-session · declared+no-arg → ORCHESTRATOR · declared+`part 1` → PART-RUNNER ·
undeclared+`part 1` → **stop on the contradiction** · declared+`part 7` (not a declared part) →
stop. The drill consumes only `check-parts.py`'s exit code and summary line — proving the mode
contract is mechanical and the skill never needs to re-parse the declaration.

## Architecture review + refactor

One substantive finding, fixed: the PART-RUNNER row said "announce part N and its ticket range"
without pinning the range's **source** — an implementer could plausibly re-read `00-DESIGN.md`,
violating the single-authority invariant. The row now names the checker's summary echo as the only
source. Re-drilled green. Also reviewed: fail-closed (every Phase-0/1.5 non-green lands on a named
stop class; checker-error is never "absent"); the widening set for this ticket (a part editing an
earlier part → clean-tree check + boundary + escalation channel; index contention → orchestrator-
only ticking; marker-check error → HALT — exact commands are T4's); additivity (single-session
Phase 2 is the prompt verbatim and Phase 3 adds nothing; the skill is opt-in — pasting the bare
prompt remains exactly today's path); module separation (guide = what, skill = how, brief = slots);
house-style frontmatter matches decompose/deep-review. Static-analysis gate: **N/A by
construction** — no `.java` touched.

## Integration / e2e

E1 is this ticket's mandated drill (above, 5/5). The full live path — a real `Agent` delegation
driven by this skill in ORCHESTRATOR mode — is deliberately T5's E2/E3, not duplicated here.

## Decisions

- **§Marker checks is an explicitly-marked open seam**: the section names the two fixed strings and
  the discipline, and states that the exact anchored/escaped/three-exit-code commands land with T4
  (the review-wiring ticket). Structure now, hardening next ticket — matching the decomposition's
  T3/T4 split.
- **Byte-identity scope**: the additive floor binds Phase 2 (the loop verbatim); Phase 0's gate
  re-run is the skill's specified added value for all modes, per the decomposition's own wording.
- **Out-of-tree packages**: the working repo is resolved by walking up from the *given package
  path* to `.git` — deterministic from the argument, never from the shell's cwd (fork 8 applied to
  the one case where "this repo" isn't the answer; what T5's fixture needs).
- The guide §4a cross-references were the T1-deferred ones; no dangling reference existed at any
  commit boundary.

## Commit

`feat(parts-port): T3 — flow-guide §4a + §2a partition test (runner skill untracked)` (this
branch). Untracked working-tree deliverables named above: the runner `SKILL.md` + its
`references/part-prompt-template.md` — reviewed by reading.
