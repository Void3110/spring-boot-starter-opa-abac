---
tags:
  - status/planned
  - type/index
  - area/methodology
---

# PARTS-PORT — the runner learns to delegate a slice as parts

> **Status: Planning.** Gives the repo a **phase-③ runner skill** (`/autonomous-implement`) whose
> execution model can run a large slice as **sequential, subagent-delegated PARTS** under an
> orchestrator — so a multi-part slice reaches full closure unattended instead of being babysat through
> pause-and-prepare cycles. Strictly additive: a package with no parts declaration runs byte-identically
> to today's bare-prompt paste.

## Why this slice exists

Phase ③ here has always been **paste the prepared prompt into a fresh session** — deliberately
(`mx-70582b`: bare-prompt sufficed for single-repo slices). Two things changed. The supervisor epic
produced the first slice family large enough that the *context window*, not the slice boundary, is the
limiting resource — the operator notes already instruct "finish the ticket, resume in a fresh session,"
which is the parts model executed by hand. And the orchestration model itself is now **validated
elsewhere**: shipped, review-looped to dry, with its expensive lessons (near-miss parsing, marker-check
mechanics, collect-from-disk) already paid for. A **capability spike in this repo's own session
environment (2026-08-01, Mulch `autonomous-runs`)** confirmed the two load-bearing capabilities hold
here — `Skill` is invocable inside a spawned subagent; `Workflow` is not — so the design ports without
structural change. This slice supersedes the bare-prompt-only decision, additively.

**First real consumer:** slice A of the supervisor epic ([[SUPERVISED-SCOPE]]) runs as this model's
first orchestrated slice once the port ships.

## The model in one paragraph

A package declares its partition on one line in `00-DESIGN.md` (`**Parts:** part 0 = T1–T2 · part 1 =
T3–T5`); a **hard-fail** gate validates it (coverage is mechanically decidable — a ticket in no part
would silently never run). The runner resolves one of three modes: **single-session** (no declaration —
the default, unchanged), **ORCHESTRATOR** (declaration present — delegates each part to a fresh-context
subagent, synchronously, and builds nothing itself), **PART-RUNNER** (spawned with `part N` — runs only
its range, re-entering the same skill). The orchestrator **collects from disk, never from the reply**:
commits, green gates, filled STATUS notes, a recorded per-part review. Three review layers at three
scopes; cross-part findings **escalate via a fixed greppable marker** and halt the loop — never fixed
across a part boundary.

## Tickets (status table)

| # | Title | Status |
|---|---|---|
| T1 | `check-parts.py` + the `verify-package.sh` **[9]** hard-fail gate + self-locating scripts | ✅ DONE |
| T2 | `scaffold-package.py --parts` (writes the declaration, validates nothing) | ✅ DONE |
| T3 | the `/autonomous-implement` runner skill (three modes, Phase 1.5 delegate-and-collect, the part brief) + the flow-guide §4a canonical section | ✅ DONE |
| T4 | review-layer wiring: the deep-review subagent ceiling row + both markers + loop termination | 📋 TODO |
| T5 | the live delegation proof: a gate-green fixture package in a scratch git repo, part 0 delegated and collected from disk | 📋 TODO |

**Validated:** 2026-08-01 — mechanical [1]–[8] green · adversarial pass clean (1 run-stopper + 11 contradictions found, fixed, re-gated).

## Files in this folder

| File | What it is |
|---|---|
| [[00-DESIGN]] | The mechanism, the decided forks (ratified + local), three failure classes, sizing. |
| [[01-DECOMPOSITION]] | The ordered work list T1…T5 + the critical path. |
| [[10-QA-TEST-CASES]] | Concrete U*/E* cases → each ticket's Acceptance. |
| AUTONOMOUS-IMPLEMENTATION-PROMPT | The self-contained prompt for the run. |
| STATUS-01 … STATUS-05 | One stub per ticket. |

## Conventions

- **Additive only** — no existing package is edited; absence of a declaration is the green default.
- Tracked deliverables: `scripts/planning/*` and `docs/guides/AUTONOMOUS-IMPLEMENTATION-FLOW.md`.
  Untracked-but-local: `.claude/skills/autonomous-implement/` (+ a deep-review edit) — reviewed by
  reading, since no git diff carries them.
- Clean-room: methodology text is written fresh for this repo; no external workspace is named in any
  committed file.

## Related

- [[AUTONOMOUS-IMPLEMENTATION-FLOW]] — the canonical method doc this slice extends (§4a).
- [[SUPERVISED-SCOPE]] — the first slice that will run under the model.
- [[POC-ROADMAP]] — tooling/methodology track.
