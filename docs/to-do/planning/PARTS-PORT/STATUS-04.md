---
tags:
  - status/done
  - type/project
  - area/methodology
---

# STATUS — T4: review wiring: the deep-review ceiling, both markers, loop termination

**Status:** ✅ DONE

## What shipped

- `.claude/skills/deep-review/SKILL.md` (**untracked** — named here; no diff carries it): the
  Phase-2 path-routing table gains the **inside-a-subagent row** — a parts-mode PART-RUNNER's
  layer-1 ★gate or layer-2 review, **any size, any risk** → **2A applied INLINE** (the lens set in
  the part-runner's own context; never a spawned review sub-agent, which would be the rejected
  one-level nesting), **2B unreachable** (`Workflow` does not exist inside a subagent — capability
  spike 2026-08-01, Mulch `autonomous-runs` mx-4b9171), with the record-the-downgrade instruction
  for headline tickets. Plus the **loop-termination note**: dry = zero *behavior-changing*
  findings, and the terminal round must be a **no-fix round** — a round that still fixed something
  is not terminal.
- `.claude/skills/autonomous-implement/SKILL.md` (**untracked**): the `§Marker checks` placeholder
  replaced with the **exact commands** — the escalation grep (the escaped marker
  `\*\*ESCALATION \(cross-part\):\*\*` behind a line-start prefix allowlist, run across **every**
  `STATUS-*.md` in the package) and the layer-2 state check (heading grep + NOT-RUN marker grep +
  awk body extraction), each with the shared discipline: every ERE metacharacter escaped, anchored
  to the start of a line's content via a prefix-character allowlist (mid-sentence and backticked
  mentions cannot match), and **three exit codes distinguished — found → HALT · absent → proceed ·
  check errored → HALT too, never "absent"**.
- `references/part-prompt-template.md` (**untracked**): the layer-2 section now explicitly points
  at the deep-review routing row (inline-2A, any size, any risk; 2B unreachable) — E6's alignment.

## Tests

Drill script (fixtures in `mktemp -d`; the grep/awk commands copied **verbatim** from the skill):
**13/13 PASS.**

- **E4** — five plantings across three STATUS notes: line-start, bullet, and **non-last-note**
  found (3 hits, exit 0 → HALT — the non-last hit proves the every-STATUS scope); mid-sentence and
  backticked mentions ignored. Absent → exit 1 → proceed. **The live counter-case:** the raw
  unescaped marker as an ERE made grep error (exit 2) — demonstrated live — and the three-exit-code
  handler mapped it to **HALT, not "absent"**. Bonus arm: an unreadable STATUS note → exit 2 →
  HALT.
- **E5** — the layer-2 three-state drill: filled → pass · `**LAYER-2 NOT RUN:**` → fallback
  engages · absent → **STOP** · (bonus) present-but-empty → STOP · (bonus) unreadable note → HALT.
  All outcomes driven by on-disk state alone.
- **E6** — read-check: the deep-review row, the part brief, and the loop-termination note all
  present and agreeing; no path lets a part attempt the multi-lens workflow, and none spawns a
  nested reviewer.

## Architecture review + refactor

One item found and applied: the awk body-extraction used an ERE interval (`/^#{1,4} /`) — it works
on this machine's awk (E5d proved the empty-section STOP, which depends on it), but `/^#+ /` is
simpler and unconditionally portable; switched in both the skill and the drill, re-run green
(13/13). Also reviewed: resolution order in the layer-2 check (the NOT-RUN marker is tested
*before* the content check, so a section carrying both marker and prose resolves to the fallback —
the fail-closed direction); the false-HALT direction (a backticked/mid-sentence mention must not
halt a healthy run — E4a's ignore arm covers it); additivity (the deep-review table's existing
size/risk rows and both workflow scripts untouched — main-session reviews behave identically; the
new row and note are additive). Static-analysis gate: **N/A by construction** — no `.java`
touched.

## Integration / e2e

E4/E5 above are this ticket's mandated drills, including the live raw-grep error counter-case. The
markers' end-to-end use inside a real delegation is T5's.

## Decisions

- **Prefix allowlist over prefix grammar:** the anchor admits any run of whitespace, `>`, `*`, `+`
  and `-` characters before the marker (rather than a strict bullet grammar). Anything word-like or
  a backtick breaks the anchor, which is what excludes prose and documentation; an over-permissive
  prefix (e.g. `---- `) can only cause a HALT on a real marker variant — the fail-closed direction.
  (This note spells the class out in words because the package's own link gate, [8], reads any
  double-bracket sequence in prose — including a literal POSIX bracket-class — as a vault link;
  found live when [8] went red on this file, twice.)
- **The layer-2 body check is mechanical-first:** the drillable procedure tests non-empty after
  blank-stripping; the skill text additionally treats template-placeholder-only bodies as empty
  (the scaffold never stubs a layer-2 section, so placeholder text there is a degenerate case the
  orchestrator judges by reading).
- A sub-heading *inside* a layer-2 section stops the awk extraction early; the worst case is a
  false STOP (never a false pass) — accepted, fail-closed.

## Commit

`feat(parts-port): T4 — review-layer wiring (untracked skills; STATUS + index tick tracked)` (this
branch). Untracked working-tree deliverables named above: the deep-review `SKILL.md` edit, the
runner `SKILL.md` §Marker-checks completion, the brief's E6 alignment — reviewed by reading.
