---
tags:
  - status/planned
  - type/design
  - area/methodology
---

# PARTS-PORT — 00-DESIGN

**Phase ① complete — 2026-08-01.** The model was validated end-to-end in a sibling private workspace
(shipped and review-looped to zero behavior findings); its forks were maintainer-decided there and are
**ratified** here unchanged, because this repo's own capability spike reproduced the two measurements the
whole design rests on. Three **local** forks are added where this repo's environment differs. No ADR: this
is methodology/tooling, not product architecture — the canonical home is the flow guide, exactly as the
two-gate decompose model (`mx-850a80`) landed.

> **The slice in one sentence:** make a decomposition package executable as **sequential
> subagent-delegated parts** under an orchestrator, so a large slice runs to full closure unattended —
> while a package with no declaration runs byte-identically to today.

## 1. The mechanism

```
 (planning side — part 0)                    (runner side — part 1)
 ────────────────────────                    ──────────────────────
 00-DESIGN.md "Parts:" line ───────────────► /autonomous-implement Phase 0: mode resolution
      │ T1                                        │
      ▼                                          ├─ no line        → SINGLE-SESSION (default, unchanged)
 verify-package.sh [9]                           ├─ line, no arg   → ORCHESTRATOR
   └─ check-parts.py (the ONE authority)         └─ line + "part N"→ PART-RUNNER (re-enters this skill)
      │ T1                                        │ T3
      ▼                                           ▼
 scaffold-package.py --parts                 Phase 1.5 for each part, in order:
   └─ writes, validates NOTHING  T2            Agent(general-purpose, run_in_background:false)
      │                                           │ collect FROM DISK: commits · green gates ·
      ▼                                           │ STATUS filled · layer-2 review recorded
 references/part-prompt-template.md               │ grep ESCALATION marker (anchored, escaped,
   └─ the per-part brief  T3                      │      three exit codes, error→HALT)  T4
                                                  └─ clean → part N+1 · else HALT
                                             layer 3: whole-delivery /deep-review (main session)  T4
                                             live proof on a fixture package  T5
```

### 1.1 The declaration grammar

One line in `00-DESIGN.md`, validated by `check-parts.py` as the **single** authority:

```
**Parts:** part 0 = T1–T4 · part 1 = T5–T8
```

Part numbers 0-based, contiguous, ascending in written order; ranges contiguous, ascending,
non-overlapping, covering exactly `T1..TN`; **two or more parts** (one part is what absence means);
en dash, em dash, hyphen all parse; the parser **skips fenced blocks** (any doc documenting the grammar
fences an example — like the one above) and rejects **two live declarations** rather than picking one;
a **near-miss** (`- **Parts:**`, `Parts:` unbolded, colon outside the bold) is a **hard error, never
"absent"** — absence is the green default, so a near-miss falling through would hide a partition that
drops tickets. **Near-miss detection applies the same discipline as the §1.4 marker checks: matched
against the start of a line's content, with inline-backticked mentions excluded** — this very paragraph
is the live counter-fixture (it names the near-miss forms in inline code spans and must parse clean),
while the same forms opening a line in a fixture must hard-fail. All measured failure modes in the
source implementation; ported with the mechanism.

### 1.2 The three runner modes (one skill, re-entrant)

| Arguments | Parts line | Mode | Behaviour |
|---|---|---|---|
| slice id | absent | **single-session** | T1..TN here — byte-identical to the bare-prompt run |
| slice id | present | **ORCHESTRATOR** | delegates each part; builds nothing itself |
| slice id + `part N` | present | **PART-RUNNER** | runs part N's range, then its layer-2 review, returns |

`part N` against a package with **no** declaration is a contradiction → stop and say so. The part
subagent **re-enters this same skill** (spike-verified: `Skill` works inside a subagent here) — the brief
stays short and points at the discipline instead of duplicating the ~150-line loop, which would drift.

### 1.3 Delegation and collection

Synchronous only — `run_in_background: false` is **load-bearing** (the tool default is background; parts
share one branch and one working tree; part N's brief names part N−1's STATUS files). Parallel delegation
is **forbidden, not degraded**. `subagent_type: "general-purpose"` (the part needs Edit/Write/Bash/Skill).

The orchestrator trusts **only on-disk state**: the subagent's final report is a summary written by the
thing being checked, and the harness never shows it to the maintainer. A part is closed by: commits on
the branch + the slice's verify command green + a **clean working tree** (`git status --porcelain` empty
except paths the part's brief declares as untracked deliverables — an uncommitted edit would otherwise
ride invisibly into part N+1 on the shared tree) + every owned `STATUS-0N.md` **filled** (its
`**Status:**` not `📋 TODO`, no empty `##` section — **and a section still holding template placeholder
text, the scaffold's italic fill-me block or a guillemet slot, counts as EMPTY**; the brief instructs
*replacing* placeholders, never appending under them) + a **layer-2 review record** in the part's last
STATUS note —
**three states**: recorded → pass · a `**LAYER-2 NOT RUN:** <why>` marker → the orchestrator reviews that
part itself before delegating on (**and a behavior-changing finding from that fallback review is an
unconfirmable end-state → the class-B stop** — the orchestrator builds nothing, so it never fixes;
doc-only findings are recorded in the part's last STATUS note and the loop proceeds) · absent/empty →
**stop**. Silence is never benign. Any check failing,
or a subagent erroring/returning nothing → **stop; part N+1 is not delegated.**

The **index status table is the orchestrator's file** under parts — a shared file two parts would
otherwise contend on, and a tightly-written What-NOT-to-touch forbids a part from touching it (measured
in the source's live proof: the part subagent correctly declined and reported the omission).

### 1.4 Three review layers · escalation

| Layer | Runs in | Scope | Path |
|---|---|---|---|
| 1 — per-ticket ★gate | part subagent | one ticket | as today, **capped at 2A** (see below) |
| 2 — per-part review | part subagent, fixes own findings | the part's tickets | **2A only** |
| 3 — whole-delivery `/deep-review` | the **main session**, all parts in | all parts vs `main` | **2B** — mandatory under parts |

The Path column is a **capability, not a preference**: path 2B invokes `Workflow`, which does not exist
inside a subagent (spike-measured here, twice). And **inside a part, "2A" is executed INLINE by the
part-runner itself** — the 2A lens set applied in its own context, **never a spawned review subagent**: a
part-runner is already a subagent, and one-level nesting is precisely the rejected fork (§4). "Capped at
2A" caps *scope and rigor*, not a tool invocation. A headline ticket inside a part takes inline-2A **and
records the downgrade** in STATUS; layer 3 re-covers it at the only scope that can see cross-part
composition —
the class where per-increment reviews all passed clean while a whole-branch review caught a Critical
(source-measured; the reason layer 3 is mandatory rather than belt-and-braces).

A finding whose blast radius reaches an **already-completed part** is **escalated, never fixed across the
boundary** (the part was never briefed on the earlier part's decided forks — a local fix is a silent
cross-part adaptation). It travels as a fixed line in the STATUS Decisions section:
`**ESCALATION (cross-part):** <what, and which part it reaches>`. The orchestrator greps for it across
**every `STATUS-*.md` in the package** — not only the part's own or last note, since an escalation can
land in any Decisions section — **before** delegating the next part, with the discipline both marker
checks share: **every metacharacter escaped**
(the marker contains `*()`; raw, grep exits with an *error* indistinguishable from "absent" if only
truthiness is tested — a fail-open on the escalation channel), **anchored to the start of a line's
content** (list/quote prefixes allowed — Decisions sections are bullet lists; mid-sentence and backticked
mentions excluded — unanchored, the check false-halts on documentation prose), and **three exit codes**:
found → HALT · absent → proceed · check-failed → **HALT too**.

## 2. Decided forks — ratified from the source; do NOT re-ask

1. **Strictly additive.** No declaration → byte-identical behavior; no existing package edited. This is
   also what keeps sizing check (a) green — drift from additive and the slice must split.
2. **Layer-2 fallback**: orchestrator reviews at the part boundary (never an inline copy of the lenses).
3. **The part re-enters the skill**; no duplicated loop in the brief.
4. **`check-parts.py` is the single validation authority**; the scaffold writes and checks nothing.
5. **A malformed declaration hard-fails** (coverage is mechanically decidable), where slice-size only
   warns (a judgement call) — the two checks sit adjacent deliberately.
6. **The declaration lives in `00-DESIGN.md`** — no new package file (would break the required-file gate
   for every package; non-additive).
7. **No new guide** — the canonical prose lands in the flow guide; skills instantiate it.

### Local forks (this repo's environment; decided this session)

8. **Every ported script and skill pins its location, never its cwd.** Spike-measured trap: sessions and
   subagents here start at the **umbrella workspace root, which is not a git repo** — a preamble deriving
   the repo root from `git rev-parse` fatals, and the main session masks it only because its persistent
   shell happens to sit in the repo. Tracked scripts resolve the repo root **from their own path**
   (`dirname "$0"`); the runner skill and every generated part brief open with an absolute `cd` into the
   repo as **step 0**; nothing derives the root from an assumed-repo cwd.
9. **The parts gate is check `[9]`** — this repo's `verify-package.sh` already owns [1]–[8].
10. **Supersedes the bare-prompt-only decision** (`mx-70582b`) **additively**: the bare-prompt path
    remains exactly the single-session mode; the skill wraps it without contradicting it. The prompt
    skeleton's "do not delegate the implementation to a sub-agent" sentence reads, under orchestration,
    as binding on **whoever is implementing** — the part-runner still implements directly; only the
    orchestrator delegates, and it implements nothing. The flow-guide §4a states this explicitly so the
    two texts cannot be read as contradicting.

## 3. Fail-closed posture (load-bearing)

**One sentence: no missing, malformed, unverifiable or escalated state ever causes MORE tickets to run
than a fully verified one.** Three classes, never collapsed:

| Class | Trigger | Lands |
|---|---|---|
| **A — gate-time** | Parts line present but malformed (near-miss, gap, overlap, out-of-range, <2 parts, two live lines) | `verify-package.sh [9]` exits 1; the runner's Phase 0 refuses the package |
| **B — run-time** | a part's end-state unconfirmable: red gates · an owned STATUS not filled · no layer-2 record · subagent errored/empty | orchestrator **stops after that part**; N+1 never delegated |
| **C — escalation** | the cross-part marker found (or the marker **check itself errors**) | orchestrator **halts and asks the maintainer**; never auto-fixes, never continues |

**OFF state:** no Parts line means the orchestrator path is **never entered** — not "orchestration with
one part". OFF delegates nothing, spawns nothing, greps nothing — strictly narrower than ON.

## 4. Considered & rejected

Inherited rejections, all still holding here: a self-contained per-part prompt (drift); declaration in
`01-DECOMPOSITION` (execution posture lives in the design doc); a separate parts file (non-additive);
parallel delegation (one working tree); inline review lenses in the brief (drift); a part fixing a
cross-part finding (silent adaptation); a separate `/orchestrate` skill (splits the runner discipline);
validating in the scaffold too (two implementations drift); warning instead of failing (decidable facts
fail); **nesting** — `Agent` exists inside subagents here (spike), but one-level-deep was never tested
anywhere: do not build on it.

Locally rejected: **porting the source scripts verbatim** — the parser's spec is ported (fence-skip,
near-miss hard-fail, exactly-one-live, range-check-before-expand, three exit codes), but docstrings,
calibration references and prose are written fresh against **this** repo's packages (clean-room, and the
citation/heading grammar must be calibrated against `docs/to-do/implemented/`, not assumed identical).

## 5. Slice sizing (the gate, answered)

**(a)** the runner and gates are load-bearing shared mechanisms — **green only because strictly
additive** (fork 1); **(b)** one repo (tracked scripts + guide) plus its local skills — no deployable
crossed; **(c)** one sentence at the top; **(d)** partitioned below; **(e)** consumers are a closed set:
`verify-package.sh` · `scaffold-package.py` · the decompose skill's gate list · the flow guide (§3 gate
enumeration + §4a) · the deep-review skill (path table) · the new runner skill + brief — plus every
future package, which is what fork 1 exists for.

**Slice size:** 5 tickets — median for this repo; one coherent deliverable.

## 6. Execution parts

**Parts:** part 0 = T1–T2 · part 1 = T3–T5

Part 0 is the planning side: after T2 a package **can declare parts, the gate validates the declaration,
and the scaffold writes it** — machine-checked planning output with standalone value even while runs stay
single-session. Part 1 is the runner side: the skill, the review wiring, and the live proof. The boundary
sits at the cleanest handoff, not an even split. This slice eats its own cooking: part 0 runs by hand
(nothing automates it yet); part 1 may be delegated only after T3 itself lands — the run is expected to
be maintainer-driven throughout, exactly as the source's own port run was.

## 7. Knowledge destination

The flow guide (`docs/guides/AUTONOMOUS-IMPLEMENTATION-FLOW.md`) — a new **§4a** (the execution model) +
the §2a sizing note gaining the partition test + the gate list gaining `[9]`. **No new guide.** Mulch:
the run ledger to `autonomous-runs`; the method lesson to `opa-abac-methodology`.
