---
tags:
  - status/planned
  - type/project
  - area/methodology
---

# PARTS-PORT — decomposition

> T1…T5, in order. Each ticket is one focused commit's worth of work (T3 and T4 touch untracked skill
> files whose "commit" is the working-tree edit plus the tracked guide/doc delta committed alongside).
> Design: [[00-DESIGN]] · Cases: [[10-QA-TEST-CASES]]

## Critical path

```
part 0:  T1 ──► T2          part 1:  T3 ──► T4 ──► T5
         (gate) (scaffold)           (runner) (review wiring) (live proof)
```

**Sequential within each part.** T2's single-authority proof (U21) needs T1's parser; T4's drills need
T3's runner and brief; T5 exercises everything before it. **Part 0 is independently landable**: after
T2 a package can declare parts, the gate validates the declaration hard-fail, and the scaffold writes
it — machine-checked planning output with standalone value while runs stay single-session.

**Two pinned semantics (so the run never stops to ask):**

1. **Path resolution is script-relative or explicit — never cwd-derived** (local fork 8). Tracked
   scripts resolve the repo root from `dirname "$0"`; the runner skill and every generated part brief
   open with an absolute `cd`; `verify-package.sh` additionally accepts an explicit package path (U19)
   for out-of-tree fixtures. The **current** `cd "$(git rev-parse --show-toplevel)"` at
   `verify-package.sh` line 18 is the measured trap and is what T1 replaces.
2. **Exit-code contracts are the API.** `check-parts.py`: 0 clean/absent · 1 problems · 2 usage. The
   marker checks: 0 found → HALT · 1 absent → proceed · >1 check-failed → **HALT too**. Every consumer
   (verify [9], the runner's Phase 1.5) distinguishes codes, never truthiness.

---

## T1 — `check-parts.py` + the `verify-package.sh` [9] hard-fail gate + self-locating scripts

**Goal.** A package can declare its partition and no malformed, near-miss or non-covering declaration
survives the mechanical gate — while absence stays byte-identically green.

**Deliverables.** Directory `scripts/planning/` (tracked):

- `check-parts.py` — usage `check-parts.py <00-DESIGN.md> <01-DECOMPOSITION.md>`, read-only, ported to
  the spec in [[00-DESIGN]] §1.1: fence-skipping; **near-miss hard-fail** (bulleted/unbolded/
  colon-outside-bold forms are errors, never "absent"); exactly-one-live; 0-based contiguous part
  numbers; contiguous, ascending, non-overlapping ranges covering exactly `T1..TN`; ≥2 parts;
  the dash character class `[–—-]` **matching `check-citations.py`'s existing CITE class** (verified by
  reading that file); the ticket count read with the **same regex `verify-package.sh` [6] uses** —
  `^#{2,4} T[0-9]+` (verified at line 91) — so the two gates can never disagree on N;
  **bounds-check before range expansion** (U8); the heading-without-declaration case (U13);
  fail-closed on an unreadable decomposition **only when a declaration exists** (U15); **near-miss
  detection anchored to the start of a line's content with inline-backticked mentions excluded** —
  the design's own §1.1 prose is the live counter-fixture (U3/U16). Docstrings
  written fresh for this repo; the calibration story references only this repo's packages.
- `verify-package.sh` — **[9] execution-parts coverage**, delegating to `check-parts.py` (single
  authority — the script never re-parses); a `[9]` ✓ line for the absent case naming it
  single-session. **Self-locating hardening**: line 18's `cd "$(git rev-parse --show-toplevel)"`
  replaced with resolution from `dirname "$0"`. The **explicit-path positional form already exists**
  (usage lines 6–8; the `*/*` case arm at lines 21–24 uses any path as-is — verified by reading): it is
  **preserved, not added**, and after the self-locating change a relative path resolves against the
  invocation cwd while absolute paths are unchanged; the bare-slice-name form stays byte-compatible.
- Gate-list deltas (D1): `docs/guides/AUTONOMOUS-IMPLEMENTATION-FLOW.md`'s verify-gate enumeration
  gains [9]; `.claude/skills/decompose/SKILL.md` §6a's check list gains [9], and its §6b/§7 gains the
  **`**Validated:**` index-line convention** — on both gates passing, the decompose step writes one line
  into the package index (`**Validated:** <date> — mechanical + adversarial clean`), which is the
  on-disk evidence T3's Phase 0 checks (the adversarial workflow itself is report-only and leaves no
  artifact; without this line "evidence the adversarial pass ran" is uncheckable). Both skill edits
  untracked — edited in place, reviewed by reading.
- **Calibration is a deliverable, not a courtesy** (U16): run the new gate across every package under
  `docs/to-do/implemented/` and `docs/to-do/planning/`; record the count and any surprises in
  `STATUS-01.md`.

**Acceptance.** QA **U1–U19**, **D1**. All fixture cases live in a scratch dir (`mktemp -d`), driven by
a throwaway bash script; exit codes asserted exactly. U17 runs `verify-package.sh` from the umbrella
root to prove the trap is closed.

**What NOT to touch.** Checks [1]–[8]'s behavior — byte-identical output on every existing package (the
additive guarantee; U16 doubles as its proof). `check-citations.py` (read for the dash class, never
edited). `scaffold-package.py` (that is T2). No skill file except the decompose §6a line. **Fail-closed
floor:** an unparseable, unreadable or ambiguous declaration always exits 1 — never a warning, never
"absent"; absence itself is always exit 0.

---

## T2 — `scaffold-package.py --parts` and `--planning-root`

**Goal.** The scaffold can write a declaration (validating nothing, by decided fork) and can build a
package skeleton outside the repo tree — the two things T5's fixture needs.

**Deliverables.** `scripts/planning/scaffold-package.py`:

- `--parts "<declaration text>"` — appends an "Execution parts" section with the `**Parts:**` line
  **verbatim** to the `00-DESIGN.md` stub (only with `--with-design`, matching where the design stub is
  produced; error otherwise). **No validation** — decided fork 4: `check-parts.py` is the single
  authority, and U21 proves the division by scaffolding a bad partition and watching the gate catch it.
- `--planning-root <dir>` — the package skeleton lands under the given directory instead of
  `docs/to-do/planning/`; nothing else changes. Existing invocations byte-identical (U23's golden
  diff).
- **Self-location (local fork 8 applies here too):** `repo_root()` currently shells out to
  `git rev-parse --show-toplevel` (cwd-derived — verified by reading), which fatals from the umbrella
  root. Re-derive it from `os.path.dirname(os.path.abspath(__file__))` (two levels up); U24 invokes the
  scaffold from the umbrella root to prove it.

**Acceptance.** QA **U20–U24**. `./gradlew` is never invoked; pure python3 against scratch dirs.

**What NOT to touch.** `check-parts.py` and `verify-package.sh` (T1's, frozen from here). The stub
*content* the scaffold emits today (U23 diffs it). No skill files. **Additive floor:** absent both new
flags, behavior is byte-identical.

---

## T3 — the `/autonomous-implement` runner skill + the part brief + the flow-guide §4a

**Goal.** The repo gains its phase-③ runner: three modes resolved from the declaration, a
delegate-and-collect loop that trusts only disk, and the canonical prose that keeps skill and guide
from drifting.

**Deliverables.**

- `.claude/skills/autonomous-implement/SKILL.md` (**untracked — named here because no diff will carry
  it**; reviewed by reading, D3): instantiated for this repo with the parts machinery included from day
  one. Phase 0: resolve the package (bare name against this repo, or an explicit path), run **both**
  phase-② gates — the mechanical re-run, plus the adversarial-pass evidence read from the **package
  index's `**Validated:**` line** (T1's convention; the workflow itself is report-only and leaves no
  artifact). **Absence of the line → report it and ask the maintainer once; never silently proceed and
  never re-run the token-hungry workflow uninvited.** Then resume-awareness from filled STATUS stubs,
  and **mode resolution** — single-session (no declaration;
  byte-identical to the bare-prompt run) / ORCHESTRATOR (declaration, no part argument; builds nothing
  itself) / PART-RUNNER (`part N`; runs only that range, then its layer-2 review, then returns);
  `part N` against an undeclared package is a **contradiction → stop** (E1). Phase 1 slots filled from
  this repo: branch `feature/void3110/<slice>`, identity `void31102025@gmail.com` checked pre-flight,
  `Co-Authored-By: Claude` welcome, rig commands as the package prompt names them, Mulch domains per
  root `CLAUDE.md` incl. `git restore --staged .` before `ml sync`. Phase 1.5 (ORCHESTRATOR only): for
  each part in order — build the brief · delegate **synchronously**
  (`Agent({subagent_type: "general-purpose", run_in_background: false, …})`; parallel delegation is
  **forbidden, not degraded** — if synchronous delegation is unavailable, stop) · **collect from disk**
  (commits · the slice's verify command green · a **clean working tree** — `git status --porcelain`
  empty except declared untracked deliverables · owned STATUS files **filled**, where a section still
  holding template placeholder text counts as EMPTY · the layer-2 record in its three states, a
  behavior-changing finding from the fallback review being a class-B stop) · run the **escalation
  grep over every `STATUS-*.md` in the package** (T4's discipline) · only then delegate part N+1 —
  and **tick the package index itself** (the index is the orchestrator's file; parts never touch it).
  Phase 2 unchanged from the package prompt's ten steps; Phase 3 close-out incl. the `autonomous-runs`
  retrospective with the **CONTEXT: compacted-or-not field**. **Step 0 of every phase: an absolute
  `cd` into the repo** (local fork 8 — the skill may be invoked from the umbrella cwd, where nothing
  git-derived works).
- `.claude/skills/autonomous-implement/references/part-prompt-template.md` (untracked): the short brief
  — it **points at the skill rather than duplicating the loop** (decided fork 3); slots for part
  number, ticket range, prior parts' STATUS filenames, the not-yours boundary, restated slice
  invariants + the fail-closed sentence, the layer-2 instructions with both fixed markers, the
  machine-checkable end state, do-NOT-push; **an absolute `cd` into the repo as its literal step 0**
  (a subagent's shell starts at the umbrella root — spike-measured).
- `docs/guides/AUTONOMOUS-IMPLEMENTATION-FLOW.md` (tracked): the new **§4a** (D2) — the model's
  canonical prose: grammar, modes, synchronous-only delegation, collect-from-disk, the three layers,
  both markers with the check discipline, the three failure classes, the OFF state, and the fork-10
  reconciliation ("do not delegate the implementation" binds the implementer; the part-runner still
  implements directly; the orchestrator implements nothing). §2a gains the partition test. The skill
  cites the guide; where they could drift, the guide wins.

**Acceptance.** QA **E1**, **D2**. E1's four arms driven against scratch fixture packages (a declared
and an undeclared one, built with T2's `--planning-root`).

**What NOT to touch.** The §4 prompt-template skeleton in the guide (the hardened invariant — §4a is a
**new** section beside it, not an edit inside it). The decompose and deep-review skills (T4's).
`scripts/planning/` (frozen after T2). **Additive floor:** with no declaration the skill's Phase 2 is
the bare prompt's loop verbatim — no behavioral delta for every existing package.

---

## T4 — review wiring: the deep-review ceiling, both markers, loop termination

**Goal.** The three-layer review model is enforceable: a part can never attempt the unreachable review
path, both fixed channels are found by checks that cannot fail open, and a review loop has a defined
end.

**Deliverables.**

- `.claude/skills/deep-review/SKILL.md` (untracked — named per D3): the path-routing table (currently
  lines 86–92) gains the row **"running inside a spawned subagent — any size, any risk → 2A applied
  INLINE (the lens set in the part-runner's own context — never a spawned review sub-agent, which would
  be the rejected one-level nesting); 2B is unreachable (`Workflow` does not exist there — capability
  spike 2026-08-01, Mulch `autonomous-runs` mx-4b9171)"**, with the record-the-downgrade instruction (a
  headline ticket inside a part takes inline-2A and says so in its STATUS note); a **loop-termination note** for repeated review rounds: dry = zero
  *behavior-changing* findings, and the terminal round must be a **no-fix round** — a round that still
  fixed something is not terminal.
- `.claude/skills/autonomous-implement/SKILL.md` Phase 1.5 (with T3 landed): the **two marker checks**,
  each with the full discipline — the fixed strings `**ESCALATION (cross-part):**` and
  `**LAYER-2 NOT RUN:**`; every metacharacter escaped; **anchored to the start of a line's content**
  with list/quote prefixes allowed and mid-sentence/backticked mentions excluded; **three exit codes
  with error → HALT**; the escalation grep scoped to **every `STATUS-*.md` in the package** (an
  escalation can land in any Decisions section, not only the note carrying the layer-2 record —
  E4's non-last-STATUS arm asserts exactly this). The exact grep lives in the skill (one home); the guide §4a states the *what*.
- The part brief's layer-2 section aligned with the deep-review row (E6's read-check).

**Acceptance.** QA **E4–E6**. The drills run against planted fixture STATUS files; E4 must also
demonstrate the raw-unescaped-grep error live (the counter-case that motivates the discipline).

**What NOT to touch.** The deep-review skill's existing 2A/2B risk routing for main-session use
(the new row is additive; size/risk routing is unchanged when not inside a subagent). The workflow
scripts (`deep-review-workflow.js`, `decompose-validation-workflow.js`). `scripts/planning/`.
**Fail-closed floor:** an errored marker check is a HALT, never "absent"; a missing layer-2 section is
a stop, never a pass.

---

## T5 — the live delegation proof on a fixture package

**Goal.** The whole chain runs once for real — a subagent-delegated part closed entirely from on-disk
evidence — with what could not be exercised live recorded honestly.

**Deliverables.**

- A **fixture package in a scratch git repo**: built with `scaffold-package.py --planning-root` into
  `mktemp -d`, `git init`, repo-local identity set, the package filled to **gate-green** (a blank
  scaffold cannot pass the gates — every scaffold placeholder filled with minimal real content), **2 parts over 2
  trivial-but-real tickets** (e.g. each ticket creates one file with pinned content and its STATUS
  note), a declaration written by `--parts`, `verify-package.sh <path>` green including [9].
- The **E2 live round trip**: the orchestrator (this repo's runner skill, ORCHESTRATOR mode against
  the fixture path) builds part 0's brief with every slot filled, delegates synchronously, and the
  subagent commits its ticket on the fixture branch.
- The **E3 collect**: all **five** disk checks pass (incl. the clean-tree check); the orchestrator
  ticks the fixture index itself.
- `STATUS-05.md`: the proven-live vs proven-by-drill split (E7), the D3 clean-room sweep result, and
  the file-by-file list of untracked skill files reviewed by reading.

**Acceptance.** QA **E2–E3**, **E7**, **D3**. The fixture repo is disposable; nothing from it is
committed here. The delegation is real (`Agent`, `run_in_background: false`), not simulated.

**What NOT to touch.** This repo's planning tree (the fixture lives in the scratch repo — U22/U19
exist precisely so nothing pollutes `docs/to-do/`). The SUPERVISED-SCOPE package (its parts declaration
is a **follow-up** after this slice ships, not part of it). **Honesty floor:** anything not exercised
live is a recorded limitation (E7), never an implied pass.

---

## Cross-cutting acceptance

- Every U case green with exact exit codes; the E1/E4/E5 drills reproducible from their fixture
  scripts.
- **The additive guarantee holds everywhere**: [1]–[8] byte-identical on existing packages (U16),
  the scaffold byte-identical without new flags (U23), the runner's single-session mode the bare
  prompt's loop verbatim.
- The clean-room sweep (D3) is empty across `scripts/planning/`, the guide, and the package.
- No Gradle build, no rig, no `.java`, no `.rego` — the local Sonar gate is N/A for this slice, and
  `opa test` is untouched.
- The flow guide remains canonical: every rule stated in a skill also traceable to §4a or §3, no
  contradiction between them.

## Related

- [[00-DESIGN]] · [[10-QA-TEST-CASES]] · [[PARTS-PORT]]
- [[AUTONOMOUS-IMPLEMENTATION-FLOW]] — the canonical method doc T1/T3 extend.
- [[SUPERVISED-SCOPE]] — the first real consumer once this ships.
