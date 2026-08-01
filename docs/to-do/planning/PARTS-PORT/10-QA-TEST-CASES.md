---
tags:
  - status/planned
  - type/project
  - area/methodology
---

# PARTS-PORT — QA test cases

> Concrete cases; each becomes a ticket's *Acceptance*. U = unit (bash/python3 against fixture files —
> no Gradle, no rig, no JVM), E = end-to-end (the runner driven live, incl. one real subagent
> delegation), D = docs. Every U case states its **exit code**, because the gates' contract *is* their
> exit discipline.

## Unit (U*)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| U1 | A valid two-part declaration over a 5-ticket fixture (`part 0 = T1–T2 · part 1 = T3–T5`) | exit **0**; the summary names 2 parts covering 5 of 5 tickets | T1 |
| U2 | No declaration at all | exit **0** with the explicit "single-session (the default)" message — absence is green, never a warning | T1 |
| U3 | Near-miss forms, each in its own fixture: `- **Parts:** …`, unbolded `Parts: …`, `**Parts**: …` (colon outside the bold) — **at the start of a line's content** | exit **1** each, with the near-miss lines printed — a near-miss is an ERROR, never "absent" (absence is the green default, so falling through would hide a partition that drops tickets). **Inline-backticked mentions are NOT near-misses**: detection is anchored to the start of a line's content, same discipline as the marker checks | T1 |
| U4 | A fenced example declaration + one live line | the live line wins; exit **0**. A fixture with ONLY a fenced example → treated as absent, exit **0** | T1 |
| U5 | Two live declarations | exit **1** naming both line numbers — never "last one wins" | T1 |
| U6 | A coverage gap (`part 0 = T1–T2 · part 1 = T4–T5`) | exit **1** naming **T3** as assigned to no part ("would never run") | T1 |
| U7 | An overlap (`part 0 = T1–T3 · part 1 = T3–T5`) | exit **1** naming T3 and both claiming parts | T1 |
| U8 | An absurd range (`part 1 = T4–T900000000`) | exit **1** in **under 2 seconds** — bounds-checked **before** expansion, never expanded first (the measured multi-GB hang) | T1 |
| U9 | A one-part declaration (`part 0 = T1–T5`) | exit **1** — one part is what absence already means; a probable authoring error | T1 |
| U10 | A non-contiguous part boundary (part 0 ends at T2, part 1 starts at T4 while T3 is claimed by neither… or starts before the previous end) | exit **1** naming the boundary | T1 |
| U11 | En dash, em dash and hyphen variants of the same range | all three parse identically — the same character class `check-citations.py` uses | T1 |
| U12 | A single-ticket part (`part 1 = T5`) | parses; covered in the summary | T1 |
| U13 | An "Execution parts" heading present but no parseable declaration under it | exit **1** — a decidable authoring defect, not absence | T1 |
| U14 | Ticket headings not numbered `T1..TN` (a gap in the decomposition numbering) | exit **1** telling the author to renumber before declaring parts | T1 |
| U15 | A live declaration + an unreadable `01-DECOMPOSITION.md` | exit **1** — a declaration that cannot be validated fails closed. With NO declaration the decomposition file is **never opened**, so a package that merely documents the grammar stays clean | T1 |
| U16 | **Calibration — what makes the parser trustworthy.** Run against **every** package under `docs/to-do/implemented/` and `docs/to-do/planning/` | every package reports clean — "no parts declaration" or a valid parse (PARTS-PORT's own live line parsed; its fenced example **and its inline-backticked near-miss mentions** ignored). A parser that has never seen real input is untested, not clean. Record the package count in `STATUS-01.md` | T1 |
| U17 | `verify-package.sh <SLICE>` invoked **from the umbrella workspace root** — NOT the repo (the spike-measured trap: the current resolver is cwd-derived and fatals there) | the script self-locates from its own path; all checks run; the exit code is identical to a repo-cwd invocation | T1 |
| U18 | Check **[9]** wired: a fixture package with a malformed declaration → `verify-package.sh` **fails** with a `[9]` ✗; the same package with no declaration → `[9]` ✓ reporting single-session | exit non-zero / zero respectively; `[9]` output present in both runs | T1 |
| U19 | `verify-package.sh /absolute/path/to/scratch/PKG` — the **existing** explicit-path form (shipped today at lines 21–24; **preserved, not added**) | after the self-locating change the gates still run against that directory from ANY cwd — a regression + hardening proof, and the form the T5 scratch fixture needs; a bare slice name still resolves against this repo exactly as today | T1 |
| U20 | `scaffold-package.py --parts "part 0 = T1–T2 · part 1 = T3–T5"` | writes the "Execution parts" section + the declaration **verbatim** into the design stub; **validates nothing** | T2 |
| U21 | The scaffold is given a **deliberately bad** partition (a coverage gap); then `check-parts.py` runs on its output | the scaffold exits **0** (it checks nothing, by decided fork); `check-parts.py` exits **1** naming the dropped ticket — the single-validation-authority proof | T2 |
| U22 | `scaffold-package.py --planning-root <scratch-dir>` | the package skeleton lands under the external dir; **nothing** is written inside this repo's tree | T2 |
| U23 | The scaffold with **no** `--parts` flag | output **byte-identical** to a pre-change golden run (`diff -r`) — the additive guarantee, proven not asserted | T2 |
| U24 | `scaffold-package.py` invoked **from the umbrella workspace root** with `--planning-root` | works — `repo_root()` is derived from the script's own `__file__`, never from git/cwd (local fork 8; the current git-subprocess form fatals there) | T2 |

## End-to-end (E*)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| E1 | **Mode resolution, all four arms.** The runner invoked against: a no-declaration package · a declared package, no part argument · a declared package + `part 1` · an **undeclared** package + `part 1` | announces single-session / ORCHESTRATOR / PART-RUNNER respectively; the fourth arm **stops on the contradiction** rather than guessing which tickets were meant | T3 |
| E2 | **The live delegation (headline).** On the T5 fixture — a gate-green package in a scratch **git** repo, one trivial real ticket per part — the orchestrator builds part 0's brief (absolute `cd` into the fixture repo as step 0, the prior-STATUS list, the boundary, the fail-closed sentence — every slot filled) and delegates with `run_in_background: false`, `subagent_type: "general-purpose"` | the subagent runs its ticket, **commits on the fixture branch**, and returns; the orchestrator built **nothing** itself | T5 |
| E3 | **Collect from disk, never from the reply (headline).** After E2 the orchestrator confirms: commits present (`git log`) · the fixture's verify command green · a **clean working tree** (`git status --porcelain` empty except declared untracked deliverables) · every owned `STATUS-0N.md` **filled** (no `📋 TODO`, no empty `##` section — a section still holding template placeholder text counts as EMPTY) · a `## Part review (layer 2)` record present — then **ticks the fixture index itself** (the index is the orchestrator's file; a part's What-NOT-to-touch forbids it) | all **five** checks pass from on-disk state alone; the subagent's reply text is never cited as evidence | T5 |
| E4 | **The escalation drill — both measured failure modes of the check.** Plant `**ESCALATION (cross-part):** …` in fixture STATUS files five ways: at line start · as a bullet (`- **ESCALATION …`) · **in a non-last owned STATUS note** (the grep scope is every `STATUS-*.md` in the package, not the note carrying the layer-2 record) · mid-sentence prose · behind a backtick | the anchored, escaped grep finds the first three (exit 0 → HALT) and ignores the last two; a **raw unescaped grep of the same marker errors** (exit ≥ 2) — demonstrated live as the counter-case — and the check treats that error as **HALT, never as "absent"** (three exit codes: 0 → HALT · 1 → proceed · >1 → HALT too) | T4 |
| E5 | **The layer-2 three-state drill.** A fixture part's last STATUS in three variants: a filled `## Part review (layer 2)` section → pass · the section carrying `**LAYER-2 NOT RUN:** <why>` → the fallback engages (the orchestrator reviews that part itself; a **behavior-changing finding from that fallback review is a class-B stop** — the orchestrator never fixes; doc-only findings are recorded and the loop proceeds) · the section absent → **stop; part N+1 is not delegated** | three distinct outcomes, driven only by on-disk state; silence is never read as the benign case | T4 |
| E6 | **The review ceiling.** The deep-review skill's path-routing table carries the inside-a-subagent row (**2A applied INLINE** — the lens set in the part-runner's own context, never a spawned review sub-agent, which would be the rejected one-level nesting — regardless of size or risk, with the record-the-downgrade instruction), and the runner's part brief points at it | read-check of both files; the row and the brief agree; no path lets a part attempt the multi-lens workflow it cannot reach — and none spawns a nested reviewer | T4 |
| E7 | **Honest limitations.** Whatever E2–E5 could not genuinely exercise live (e.g. a chain of more than one delegated part, a real maintainer-halting escalation) is **recorded as a limitation in `STATUS-05.md`** | the STATUS note separates proven-live from proven-by-drill — a proof that overstates itself is the vacuous-pass failure mode this port exists to avoid | T5 |

## Docs (D*)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| D1 | The gate enumerations name **[9]**: the flow guide's verify-gate list and the decompose skill's §6a text | both enumerate [9] beside [1]–[8]; no stale check-count phrasing survives in either file | T1 |
| D2 | The flow guide gains **§4a** — the execution model (declaration grammar, three modes, synchronous delegation, collect-from-disk, the three review layers, both fixed markers, the three failure classes, the OFF state) — and §2a gains the **partition test** ("a slice that fails on context alone partitions; it does not have to shrink"). §4a explicitly reconciles the prompt skeleton's "do not delegate the implementation" sentence with orchestration (local fork 10) | the guide stays the single canonical source; the skills point at it; a reader could run the model from the guide alone | T3 |
| D3 | Clean-room + review-by-reading sweep over everything shipped | no external workspace or private source named in any committed file (the package is covered by `verify-package.sh [3]`; `scripts/planning/` and the guide swept by grep); the **untracked** skill files (runner SKILL.md, part brief, the deep-review edit) reviewed **by reading** and named file-by-file in `STATUS-05.md`, since no git diff carries them | T5 |

## Headline proof

**E2 + E3.** One real part, delegated synchronously to a fresh-context subagent, closed **entirely from
on-disk evidence** — commits, green gates, filled STATUS, a recorded review — with the orchestrator
building nothing and trusting no reply text. That is the whole model in one round trip. Everything else
is the discipline that keeps it safe when a part misbehaves: U3/U6 are the gate, E4/E5 are the channels.
