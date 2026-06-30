---
tags:
  - status/active
  - type/guide
  - area/docs
  - area/build
---

# DECOMPOSE skill — instantiation template (phase ②)

The portable form of the `/decompose` skill (formerly `slice-planner`) — the phase-②
automation of [`AUTONOMOUS-IMPLEMENTATION-FLOW.md`](AUTONOMOUS-IMPLEMENTATION-FLOW.md).
Companion to [`DEEP-REVIEW-TEMPLATE.md`](../code-review/DEEP-REVIEW-TEMPLATE.md), and the same
model: **the method lives in the flow guide; each repo gets a thin, honest instantiation** —
a local `.claude/skills/decompose/` skill plus a documented conventions delta. Two
instantiations exist as of this writing (this repo, and a private multi-repo corporate
monolith); this template is distilled from both.

> **Why not one global skill?** Phase ② is convention-saturated: package layout, verify
> gates, branch/identity, test commands, ADR-vs-design-note policy, multi-repo coordination
> all differ per repo. A global skill becomes a branching generalist exactly where the
> autonomous run needs precision. Keep the method central (the flow guide), the skill local.

---

## The invariant core (keep in every instantiation)

1. **Docs-only.** The skill writes the *plan*, never code, never a branch. Implementation is
   phase ③'s job.
1a. **Size the slice before decomposing (flow guide §2a).** An oversized slice yields a partial
   blast-radius that decomposition can't recover; the gaps surface as e2e regressions. The skill
   **checks the split smells first and STOPS** if any fire: (a) removes/changes a shared mechanism
   *and* adds new surface depending on it; (b) crosses >1 deployable; (c) ticket count > ~5–6; (d)
   can't name every consumer of a mechanism it changes. On a hit, propose the 2–3 smaller slices and
   route back to phase ①. (B4 tripped all four — see §2a.) This gate is itself an invariant: every
   instantiation enforces it, however its phase boundary is drawn.
2. **Phase boundary is explicit.** Either the skill *refuses* phase-① work and routes to the
   planning step (this repo's choice), or it *folds ① in* with an interview at the top (the
   other instantiation's choice) — but the exit criterion is identical: **every fork that
   would make the autonomous agent stop and ask is decided and pinned** ("do NOT re-ask").
   `grill-me` drives the interview either way.
3. **The package shape**: an index note, the design note (mechanism with **named integration
   links**, fail-closed posture, decided forks, considered-&-rejected), `01-DECOMPOSITION.md`
   (tickets T1…TN), `10-QA-TEST-CASES.md` (U*/I*/E* cases), the
   `AUTONOMOUS-IMPLEMENTATION-PROMPT.md` (the §4 skeleton, **kept verbatim**, every `«SLOT»`
   filled), and one STATUS stub per ticket.
4. **The good-ticket discipline** (flow guide §3): one focused commit's worth; the four
   fields (Goal / *named* Deliverables / Acceptance drawn from the QA cases /
   What-NOT-to-touch carrying the slice invariants forward); explicit critical path;
   build-breakers flagged in the ticket that causes them.
5. **Every integration point is a named link.** The recurring planning failure across both
   instantiations is a plan-*asserted* integration that was never wired ("the pipeline
   handles the rest" — nothing did). If a link can't be named, the design isn't done.
6. **Ground boundaries in the call graph, not grep.** Use the `LSP` tool
   (`findReferences`, `workspaceSymbol`, `goToImplementation`) to scope Deliverables and
   What-NOT-to-touch; every implementation of an interface the slice widens is a
   build-breaker candidate for that ticket.
7. **Verify before declaring done** — deterministic gates, not prose: all files present;
   frontmatter valid; ticket count == STATUS-stub count; **no unfilled `«slots»`** in the
   prompt; plus the repo's own gates (e.g. this repo's clean-room scan). Code is
   deterministic; language interpretation isn't — script the gates.
8. **The flow guide is canonical.** When the skill and the guide disagree, the guide wins —
   and fix the skill.
9. **Prompt-wording rules** (flow guide §7.0) apply when filling slots: positive constraints
   over negative, hard NEVER reserved for non-negotiables, critical constraints first,
   declarative goals / imperative steps, specificity only where there's an objective right
   answer.

## The slots (what each repo decides)

| # | Slot | This repo's answer | Notes for a new repo |
|---|------|--------------------|----------------------|
| 1 | Phase-① coverage | Separate: `grill-me` + ADRs first; skill refuses ① work | Folding ① into the skill works for repos without an ADR practice — pin forks in the design note instead |
| 2 | Package root + layout | `docs/to-do/planning/<SLICE>/` → `implemented/` when shipped | Any layout; keep the planning→implemented lifecycle move |
| 3 | Structural-decision record | Immutable ADRs in `docs/architecture/adr/` | A "considered & rejected" section in the design note is the lighter substitute |
| 4 | User stories | Required, phase-tagged (`USER-STORIES.md`) | Optional where stories live upstream (a ticket tracker) |
| 5 | Scaffold | `scripts/planning/scaffold-package.py` — idempotent, `--force` to overwrite, `--with-design` for the phase-① stubs | A deterministic scaffold script (folder + stubs + valid frontmatter) beats copying a shipped package by hand |
| 6 | Verify gates | `scripts/planning/verify-package.sh` — files, frontmatter, clean-room scan (private blocklist in a gitignored `.local`, fail-closed), no unfilled «slots», prompt invariants, count match | Keep the base four; swap clean-room for repo-specific scans; commit the script, keep the blocklist out of the public repo |
| 7 | Test conventions the QA cases honor | In-process OPA stub, Testcontainers Postgres, `opa test`, e2e asserts the *cut* | Name the repo's exact stack — the QA cases are only as good as the conventions they encode |
| 8 | Branch / identity / commit | `feature/void3110/<slice>`, identity pinned, `Co-Authored-By` welcome | Some repos forbid AI trailers — make it explicit either way |
| 9 | Mulch domains to prime | `ml prime opa-abac` (+ `autonomous-runs`) | List the domains the slice's surface touches |
| 10 | Multi-repo handling | N/A (single repo) | Multi-repo slices need per-repo branches + a cross-service release order in the prompt's operator notes |
| 11 | Review authority the prompt's ★gate names | `/deep-review` (this repo's instantiation) | Name whatever review skills exist; the gate escalates by ticket risk |

## SKILL.md skeleton

```markdown
---
name: decompose
description: Produce the decomposition package for a <repo> slice — ordered tickets, QA
  cases, the verbatim autonomous-implementation prompt, STATUS stubs — from a settled
  design. Use when the user mentions decompose, decomposition, or a planning package.
argument-hint: [slice/ticket + one-line goal]
allowed-tools: Read Grep Glob Bash Edit Write AskUserQuestion
model: opus
effort: high
---
# Decompose — produce the decomposition package (phase ②)
> The method is AUTONOMOUS-IMPLEMENTATION-FLOW.md §2–§6; this skill defers to it.
## Precondition check        ← slot 1: refuse-and-route, or run the ① interview here
## What this skill produces  ← slot 2: the package table
## Procedure
  1. Prime (slot 9) + read the guide §3–§4 + the most similar shipped package
  2. Decompose into tickets (the four fields; LSP-grounded boundaries)
  3. Write the QA cases (slot 7)
  4. Fill the prompt — §4 skeleton verbatim, every «SLOT» from the §6 slot table
  5. STATUS stubs + index + roadmap link
  6. Verify (slot 6 gates) — fix anything flagged before declaring done
  7. Commit docs-only (slot 8); do NOT push
## Important rules           ← the invariant core, in this repo's words
```

## Instantiation checklist (~30 minutes)

- [ ] 1. `mkdir -p .claude/skills/decompose` (gitignored or committed — decide deliberately);
      write `SKILL.md` from the skeleton above.
- [ ] 2. Fill the 11 slots. If the deltas from this guide are numerous, write a
      `references/<repo>-conventions.md` documenting **every** override ("already applied —
      do not revert") — that file is what keeps the instantiation honest.
- [ ] 3. Bundle the scaffold + verify **scripts** (deterministic beats prose).
- [ ] 4. Dry-run: regenerate the most recent shipped package's skeleton and diff — the
      structure should match 1:1.
- [ ] 5. Record the instantiation deltas to the repo's Mulch store.

## Related

- [`AUTONOMOUS-IMPLEMENTATION-FLOW.md`](AUTONOMOUS-IMPLEMENTATION-FLOW.md) — the canonical method (§2 planning, §3 decomposition, §4 prompt template, §6 slots, §7.0 wording)
- [`AUTONOMOUS-IMPLEMENT-SKILL-TEMPLATE.md`](AUTONOMOUS-IMPLEMENT-SKILL-TEMPLATE.md) — the phase-③ runner counterpart
- [`DEEP-REVIEW-TEMPLATE.md`](../code-review/DEEP-REVIEW-TEMPLATE.md) — the phase-④ counterpart, same instantiation model
