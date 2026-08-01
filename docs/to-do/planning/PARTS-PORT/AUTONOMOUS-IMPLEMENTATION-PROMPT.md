---
tags:
  - status/planned
  - type/project
  - area/methodology
---

# Parts port — autonomous implementation prompt

> **What this is.** A self-contained prompt for implementing the parts-orchestration port autonomously,
> ticket by ticket, with an architecture-review gate and a checkpoint after each ticket. The design and
> work list it refers to live alongside it in this folder.
>
> **Before you run it:** create the branch — `git checkout -b feature/void3110/parts-port` off a clean
> `main`. Confirm `git config --local user.email` is `void31102025@gmail.com`. Then paste the
> **PROMPT** section below to the agent.

---

## PROMPT

You are implementing **the parts-orchestration port** on branch `feature/void3110/parts-port`.

**The problem.** Phase ③ here is a bare-prompt paste into one session, so a slice's size is capped by
one context window — the operator notes already say "finish the ticket, resume in a fresh session,"
which is the parts model executed by hand. This slice gives the repo the machinery to run a declared
slice as **sequential, subagent-delegated parts** under an orchestrator that **collects from disk,
never from the reply**: a one-line partition declaration in `00-DESIGN.md`, a hard-fail gate
(`verify-package.sh [9]` via `check-parts.py`, the single authority), scaffold support, a three-mode
re-entrant `/autonomous-implement` runner skill with a delegate-and-collect loop, review-layer wiring
with two fixed greppable markers, and one live delegation proof on a scratch fixture. **The headline:**
a real part, delegated to a fresh-context subagent, closed entirely from on-disk evidence. **Explicitly
NOT in this slice:** any change to a shipped package; declaring parts on SUPERVISED-SCOPE (a follow-up);
nesting (a part spawning sub-subagents — untested everywhere, do not build on it); any `.java`, `.rego`,
Gradle or rig work. **Strictly additive:** a package with no declaration must behave byte-identically to
today at every layer — gate, scaffold, and runner.

Implement the core work directly. Do not delegate the implementation to a sub-agent. Sub-agents are
welcome for **read-only scouting** (e.g. "find every place across the suite that asserts X") and for
**log-noisy validation** (e.g. run a newman matrix / a long build and report back only the failure
summary) — their findings come back to you; the code, tests, and docs are written in this loop. **The
one exception is the deliverable itself:** T5's acceptance *is* a live `Agent` delegation of a fixture
part — that delegation is the product being tested, not a delegation of your work.

### Read before you start (in order)

1. `PARTS-PORT.md` (this folder's index) — what this slice delivers, the file glossary, the ticket
   status table, the critical path, conventions.
2. `00-DESIGN.md` — the mechanism (§1), the **ten decided forks** (§2 — seven ratified + three local),
   the **three failure classes** (§3), considered-&-rejected, and this package's own live `**Parts:**`
   declaration (§6).
3. `01-DECOMPOSITION.md` — the 5 tickets, each with Goal / Deliverables / Acceptance /
   What-NOT-to-touch, and the **two pinned semantics** (path resolution; exit-code contracts). **This
   is your work list.**
4. **The pinned decisions** — no ADR governs this slice (methodology/tooling; the flow guide is
   canonical, the `mx-850a80` precedent). The design's §2 forks are the binding decisions.
5. `10-QA-TEST-CASES.md` — the U*/E*/D* cases your work must satisfy, exit codes included.
6. **Context you will be checked against** in the review gate (step 5):
   `scripts/planning/verify-package.sh` (the check structure and [6]'s ticket regex you must mirror;
   line 18 is the cwd trap you replace), `scripts/planning/check-citations.py` (the dash character
   class), `scripts/planning/scaffold-package.py` (the stub emitter you extend),
   `.claude/skills/decompose/SKILL.md` §6a (the gate list), `.claude/skills/deep-review/SKILL.md`
   (the path-routing table T4 extends), and `docs/guides/AUTONOMOUS-IMPLEMENTATION-FLOW.md` §2a–§4
   (the canonical method; §4's prompt template is the skeleton §4a must never edit).
7. Root `CLAUDE.md` — the **IP boundary** (clean-room: original names only; no external workspace named
   in any committed file) and the **commit identity** rule (`Void3110 <void31102025@gmail.com>`).
8. `infra/README.md` — **not needed for this slice** (no rig); listed so its absence is a decision, not
   an oversight.
9. **Prime Mulch:** `ml prime opa-abac-methodology autonomous-runs --budget 8000`. Directly relevant
   records: **mx-4b9171** (the 2026-08-01 capability spike — every environment claim traces to it:
   `Skill` works inside a subagent, `Workflow` does not, `Agent` defaults to background, non-cwd
   `CLAUDE.md` does not inject, and the umbrella-cwd git trap), **mx-850a80** (the two-gate precedent —
   why the canonical prose lives in the guide), **mx-70582b** (the bare-prompt decision this slice
   additively supersedes), **mx-e621ea** (the clean-room scan's case-insensitivity trap — mind the
   wording in scripts and docs), **mx-d8a173** (`git restore --staged .` before `ml sync`).

### Per-ticket loop (tickets T1 → T5, IN ORDER — part 0 = T1–T2 is independently landable)

For each ticket do ALL of the following, in order, and **STOP at the checkpoint before the next**:

1. **Prime for the files you're about to touch.** `ml prime --files <path>`, and re-read that ticket's
   section in `01-DECOMPOSITION.md`.

2. **Build the deliverables** exactly as `01-DECOMPOSITION.md` lists them — exact scripts, flags,
   skill files, guide sections. Match the surrounding code's naming and idioms (the existing planning
   scripts are the house style). **Clean-room:** no proprietary names anywhere; no external workspace
   named in any committed file.

3. **Write/extend the tests** for the ticket (the relevant U*/E* cases from `10-QA-TEST-CASES.md`).
   For this slice these are **throwaway bash/python3 fixture drivers in a scratch dir** (`mktemp -d`)
   asserting **exact exit codes** — no Gradle, no Testcontainers, no rig. `opa test` is untouched
   (zero policy edits).

4. **Compile + run unit tests until green.** Here: run the fixture driver until every case passes.
   Fix-until-green.

5. **★ ARCHITECTURE REVIEW + REFACTOR — the gate before integration/e2e validation.** Once the cases
   pass, do NOT advance yet. Run a focused self-review, then refactor and re-test:
   - **Fail-closed check — the slice's load-bearing invariant: no missing, malformed, unverifiable or
     escalated state ever causes MORE tickets to run than a fully verified one.** The three classes
     land where `00-DESIGN` §3 says: gate-time → exit 1 at [9]; run-time → the orchestrator stops
     after the part; escalation (or an **errored** marker check) → halt for the maintainer.
   - **Security check — name the widening that would matter for this ticket** (a near-miss read as
     absent hiding a partition; a marker-check error read as "no escalation"; a part silently editing
     an earlier part's code; a fixture leaking into the real tree) **and state why it cannot happen.**
   - **Concurrency / idempotency check** — delegation is synchronous-only and the loop stops rather
     than parallelizes; re-running any gate or the scaffold on existing output converges (idempotent,
     no duplicate sections).
   - **Wiring check** — every seam this ticket adds (a flag, a check, a mode, a marker) has a **named
     consumer** and a case through its **non-happy path**; zero call sites = the ticket is not done.
   - **Boundary / additivity check — the OFF state is byte-identical**: [1]–[8] unchanged on every
     existing package, the scaffold's golden diff empty, the runner's single-session mode the bare
     prompt verbatim. Name the byte-for-byte-unchanged surfaces.
   - **Module-layer separation** — validation lives in `check-parts.py` alone; the scaffold writes and
     never checks; the skill consumes the gate and never re-parses; the guide states the *what*, the
     skill owns the *how*.
   - **Pattern-reuse check** — the existing planning scripts' conventions (arg style, output shape,
     the `[n]` check format) reused, not reinvented.
   - **SOLID / decomposition** — cohesive, small; anything to split/simplify?
   - **Static-analysis gate — N/A by construction for this slice** (no `.java` is touched; the local
     Sonar stack stays down). State it in the STATUS note rather than skipping silently.
   - **Apply** the refactoring the review surfaces, then **re-run the cases** to confirm green.
   - Write a short note of what the review found + what you refactored into `STATUS-0N.md`. If it found
     nothing substantive, say so explicitly — **do not invent churn.**

6. **Integration / e2e validation (MANDATORY for the relevant tickets).**
   - T1: the U16 calibration sweep across every real package — record the count. T2: the U21
     single-authority proof and U23 golden diff. T3: the E1 four-arm mode drill. T4: the E4/E5 marker
     drills incl. the live raw-grep error counter-case. T5: the E2/E3 live delegation and collect.
     Fix-until-green.

7. **Update documentation (after each ticket).** Tick the ticket in the `PARTS-PORT.md` status table;
   record real values/decisions in `STATUS-0N.md`. T1 and T3 carry the guide deltas (D1, D2) **in the
   same commit as their mechanism**; untracked skill edits are named file-by-file in the STATUS note
   (no diff will carry them). Root/project `CLAUDE.md` only if a new build/run step matters.

8. **Mulch expertise check (after each ticket).** `ml prime`/skim, then record any genuine reusable
   insight (`ml record <domain> --type <pattern|decision|failure|reference> …` — the domain table in
   root `CLAUDE.md`; method lessons → `opa-abac-methodology`, run ledger → `autonomous-runs`) and
   `ml sync`. **Before `ml sync`, `git restore --staged .`** (the swept-staged trap). Skip recording
   only if nothing is non-obvious.

9. **Commit** — one focused commit on this branch (tracked code + docs + the `STATUS-0N.md` note
   together; untracked skill files are working-tree deliverables named in the STATUS note). Identity
   `Void3110 <void31102025@gmail.com>`. Conventional subject `feat(parts-port): …` /
   `docs(parts-port): …`. A `Co-Authored-By: Claude` trailer is welcome.

10. **CHECKPOINT — STOP and report.** Summarize what shipped, paste the case-run summary, **summarize
    the review findings + the refactoring you applied** (step 5), list docs updated, and note any open
    question you resolved. Then proceed to the next ticket. **Do not batch tickets without a
    checkpoint.**

### Permissions / autonomy granted (do these WITHOUT asking)

- Create/modify `scripts/planning/*`, the flow guide, the decompose §6a line, the deep-review routing
  table, the new `.claude/skills/autonomous-implement/` skill + its references, docs in this folder,
  and Mulch — all on this branch.
- Create and destroy scratch fixture repos (`mktemp -d`, `git init`, local identity) at will; nothing
  from them is ever committed here.
- Spawn the T5 fixture delegation (`Agent`, `run_in_background: false`) — it is the deliverable under
  test.
- Fix any issue your own validation reveals. Iterate until green.
- Commit per ticket on this branch.

### Hard rules

- **Report at every checkpoint and continue.** Don't batch tickets.
- **The architecture-review + refactor step (5) is NOT optional and happens BEFORE integration/e2e
  validation** — cases green → review → refactor → re-test → then the heavier drills. Document what it
  found.
- **Fix-until-green within the ticket.** Only STOP mid-ticket if genuinely *blocked*: the same root
  cause survives ≥3 focused attempts, OR a design decision the docs don't cover is needed, OR a local
  prerequisite is unrecoverable.
- **Fail-closed is the load-bearing invariant: no missing, malformed, unverifiable or escalated state
  ever causes MORE tickets to run than a fully verified one.** Fail-closed does **not** always mean
  "deny and continue": this slice has **three** classes and each lands in its own place — **gate-time**
  (a malformed/near-miss/ambiguous declaration) fails the package at `[9]` before any run;
  **run-time** (an unconfirmable part end-state) stops the orchestrator after that part, never
  delegating the next; **escalation** (the cross-part marker, or the marker check itself **erroring**)
  halts for the maintainer. Never collapse the classes into one rule because the sentence reads
  cleaner — and never read a check error as "absent".
- **Verify a third-party seam before you build on it.** If a ticket names a script line, a regex, a
  flag, or a file location and reality disagrees — the line moved, the regex differs, the flag exists
  already — **that is not a blocker to work around and not a silent adaptation**: confirm against the
  artifact (read the script; run it), then record the deviation in the STATUS *Decisions* section, in
  the ticket's own words, before proceeding. A plan that named the seam from a mental model is a
  planning defect the run should surface, not absorb.
- **Slice-specific invariants — never trade these away:** **(1) Strictly additive** — no declaration
  means byte-identical behavior at gate, scaffold and runner; no existing package edited. **(2) One
  validation authority** — only `check-parts.py` parses declarations; the scaffold writes blind; the
  skill trusts [9]. **(3) Near-miss is an error, never "absent".** **(4) Synchronous delegation only**
  — parallel parts are forbidden, not degraded. **(5) Collect from disk** — a part is closed by
  commits + green gates + a **clean working tree** + filled STATUS (placeholder text counts as empty) +
  a layer-2 record in its three states; reply text is never evidence. **(6) Path resolution is script-relative or explicit, never cwd-derived.** **(7) The guide
  is canonical** — a rule stated in a skill must trace to §4a/§3; on conflict, fix the guide first.
- **Clean-room IP boundary** — never introduce proprietary names, package names, comments, or source;
  no external workspace named in any committed file.
- **`opa-abac-core` stays Spring-free** — untouched entirely; this slice ships no library code.
- **`ddl-auto: validate` must pass** — N/A (no schema); state it, don't silently skip it.
- **Do NOT push, open PRs, or touch `main`.** Local + this branch only. The maintainer pushes.

---

## Operator notes (not part of the prompt)

- **The headline tickets** — **T1** (the gate: everything downstream trusts [9], and the near-miss
  hard-fail is the single most load-bearing behavior in the slice) and **T5** (E2+E3: the one live
  round trip that proves the model rather than describes it).
- **The fail-closed edge to eyeball** — the **marker checks** in T4. A raw grep of either marker
  *errors* (they contain `*()`), and an error read as "not found" is a fail-open on the escalation
  channel itself; anchoring decides whether documentation prose false-halts the loop or a bulleted
  escalation is missed. E4 exercises all of it, including the error counter-case, live.
- **Standalone-value subset** — **part 0 (T1+T2)**: declarations become machine-checked planning
  output even while runs stay single-session, exactly the state this package itself was authored in.
- **Rig / e2e specifics** — none. No rig, no newman, no OPA restart. The only "e2e" is the T5 fixture
  delegation in a scratch repo.
- **First real consumer** — SUPERVISED-SCOPE: after this ships, its `00-DESIGN` gains
  `**Parts:** part 0 = T1–T2 · part 1 = T3–T4 · part 2 = T5` (its deployable boundary), the gate
  re-runs, and it becomes the first orchestrated slice. That is a **follow-up edit outside this
  slice** — deliberately, so the port is proven on a disposable fixture before touching a real
  package.
- **CI note** — the fixture drivers are throwaway by design; U16's calibration sweep is the check
  worth re-running whenever a new package ships.
- **Context management** — if the window grows long mid-run, finish the ticket, stop at its
  checkpoint, and resume in a **fresh session** (the ticket status table + STATUS notes are the
  handoff). This slice's own parts declaration (part 0 = T1–T2 · part 1 = T3–T5) exists for exactly
  that — though the run is expected to be maintainer-driven, since the runner it would use is what T3
  builds.
- **Workflow-as-artifact:** keep this prompt verbatim; the `STATUS-0N.md` notes record each ticket's
  outcome. Move the folder to `docs/to-do/implemented/` on ship.
