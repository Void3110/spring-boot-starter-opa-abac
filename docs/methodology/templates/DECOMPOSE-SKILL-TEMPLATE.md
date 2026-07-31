---
tags:
  - status/active
  - type/guide
  - area/docs
  - area/build
---

# DECOMPOSE skill — instantiation template (phase ②)

The portable form of the `/decompose` skill (formerly `slice-planner`) — the phase-②
automation of [`AUTONOMOUS-IMPLEMENTATION-FLOW.md`](../../guides/AUTONOMOUS-IMPLEMENTATION-FLOW.md).
Companion to [`DEEP-REVIEW-TEMPLATE.md`](DEEP-REVIEW-TEMPLATE.md), and the same
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
5a. **A named THIRD-PARTY seam is verified against the real artifact, never written from a
   mental model** — and the ticket says in one line how it was checked. This is the harder
   sibling of invariant 5: there the link was unnamed, here it is named *plausibly and
   wrongly*, which is worse because it reads as done. The 2026-07-31 instance: a decomposition
   named a `tools/list` filter hook, an `extract(Jwt)` signature, a rule-qualified policy path,
   and a descriptor field — four APIs that sounded right and did not exist as written; two
   stopped the run mid-flight. **Ground truth beats memory, and the check is minutes:**
   disassemble/inspect the artifact on the actual dependency path (JVM: `javap -p/-c/-v`
   against the jar in the local cache; Node/TS: the shipped `.d.ts` in `node_modules`; Python:
   `inspect`/`pydoc` against the installed package), read the spec or call the endpoint for a
   service API, and run the policy/query engine for a policy path. Config and framework
   behavior deserve special suspicion: a properties *field* default and the *conditional* that
   reads the property can disagree, and only the artifact tells you which wins. When a seam is
   load-bearing enough, spike it — a throwaway program proving the seam exists *before* the
   ticket is written is the cheapest insurance in the method.
5b. **Every OFF state and error state is pinned.** For each switch, degradation path, and
   failure edge, the docs state what happens — and where two failure *classes* exist (e.g.
   an install/startup failure vs a request-time failure), which lands where. "The design left
   a fail-open/contract semantic unpinned" is the most-recorded cause of a paused run across
   both instantiations; a switch documented only as "OFF disables X" is that gap in miniature.
6. **Ground boundaries in the call graph, not grep.** Use the `LSP` tool
   (`findReferences`, `workspaceSymbol`, `goToImplementation`) to scope Deliverables and
   What-NOT-to-touch; every implementation of an interface the slice widens is a
   build-breaker candidate for that ticket.
7. **Verify before declaring done — TWO gates, mechanical then adversarial.** The package is
   what an unattended agent executes, so it earns the same treatment as code.
   - **Mechanical** (deterministic, scripted — code is deterministic, language interpretation
     isn't): all files present; frontmatter valid; ticket count == STATUS-stub count; **no
     unfilled `«slots»`**; **acceptance citations cross-referenced** (every case id a ticket
     cites exists in the QA doc *and* that case's owning ticket agrees — plus the reverse
     direction, since a QA case nobody cites is a silently-shrunk definition of done); links
     resolve; plus the repo's own scans (e.g. clean-room).
   - **Adversarial** (a read-only fan-out, every finding verified before it is reported):
     **seam existence** (invariant 5a, re-checked against the artifacts), an
     **unpinned-semantics critic** (invariant 5b, primed on the repo's own run retrospectives),
     and **cross-doc consistency** (design ↔ tickets ↔ QA ↔ prompt telling one story, including
     whether the prompt's vocabulary can even *express* every failure class the design defines).
     Rank findings run-stopper / contradiction / nit; fix the first two and re-run the
     mechanical gate.

   Neither gate substitutes for the other: the mechanical one cannot tell whether a named seam
   exists, and the adversarial one is too expensive to re-run for a typo. Both green, then commit.
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
| 6 | Verify gates (mechanical) | `scripts/planning/verify-package.sh` — files, frontmatter, clean-room scan (private blocklist in a gitignored `.local`, fail-closed), no unfilled «slots», prompt invariants, count match, citation cross-reference (`check-citations.py`), wikilink resolution | Keep the base four + citations; swap clean-room for repo-specific scans; commit the script, keep the blocklist out of the public repo |
| 6a | Adversarial gate | `.claude/skills/decompose/decompose-validation-workflow.js` — 4 angles (library seams · internal/policy/config seams · unpinned semantics · cross-doc consistency), each finding adversarially verified, ranked run-stopper/contradiction/nit | Same four angles port unchanged; only the *ground-truth commands* differ (see slot 12) |
| 6b | Citation shape | QA cases are table rows `\| U1 \| … \| T2 \|`, cited **bolded** in a ticket's `**Acceptance.**` paragraph, ranges allowed (`I16–I28`) | Any convention works — the parser just needs one grammar for "case id" and "owning ticket". Pick it before the first package, not after |
| 12 | Ground-truth toolchain (what "verify the seam" means here) | JVM: `javap -p/-c/-v` + `unzip -p` against `~/.gradle/caches/modules-2/files-2.1/…`; Spring properties: the autoconfigure class's `@Conditional*` **and** the properties class's field defaults (they can disagree); rego: `opa eval`/`opa test`; service APIs: the OpenAPI spec under `example-*/…/openapi/` | Name the exact commands for the repo's stack — Node: the `.d.ts` in `node_modules`; Python: `inspect`/`pydoc` on the installed package; Go: `go doc`. A seam-verification rule with no named command degrades to "trust me" |
| 7 | Test conventions the QA cases honor | In-process OPA stub, Testcontainers Postgres, `opa test`, e2e asserts the *cut* | Name the repo's exact stack — the QA cases are only as good as the conventions they encode |
| 8 | Branch / identity / commit | `feature/void3110/<slice>`, identity pinned, `Co-Authored-By` welcome | Some repos forbid AI trailers — make it explicit either way |
| 9 | Mulch domains to prime | `ml prime opa-abac-methodology autonomous-runs --budget 8000`, then the slice's surface row from `CLAUDE.md` (the `opa-abac` catch-all was decomposed 2026-07-16) | List the domains the slice's surface touches. **`--budget 8000` is not optional** — `ml prime` silently truncates at 4000 tokens per domain |
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
allowed-tools: Read Grep Glob Bash Edit Write AskUserQuestion Workflow
model: opus
effort: high
---
# Decompose — produce the decomposition package (phase ②)
> The method is AUTONOMOUS-IMPLEMENTATION-FLOW.md §2–§6; this skill defers to it.
## Precondition check        ← slot 1: refuse-and-route, or run the ① interview here
## What this skill produces  ← slot 2: the package table
## Procedure
  1. Prime (slot 9) + read the guide §3–§4 + the most similar shipped package
  2. Decompose into tickets (the four fields; LSP-grounded boundaries;
     EVERY third-party seam verified against the artifact — slot 12 — and how, in one line)
  3. Write the QA cases (slot 7), each owned by exactly one ticket (slot 6b)
  4. Fill the prompt — §4 skeleton verbatim, every «SLOT» from the §6 slot table
  5. STATUS stubs + index + roadmap link
  6a. Verify MECHANICALLY (slot 6) — fix everything it prints
  6b. Verify ADVERSARIALLY (slot 6a) — fix every run-stopper + contradiction, re-run 6a
  7. Commit docs-only (slot 8); do NOT push
## Important rules           ← the invariant core, in this repo's words
```

**Two rules worth restating in the skill's own words**, because both were learned the expensive way:
*a finding is not a fix* (the validation workflow is report-only — the skill applies fixes and
re-runs), and *when a finding contradicts a decision already recorded in a shipped ticket's STATUS
note, **annotate** the stale plan text rather than rewriting it* — the decomposition is a record of
what was planned as much as a work list.

## Instantiation checklist (~30 minutes)

- [ ] 1. `mkdir -p .claude/skills/decompose` (gitignored or committed — decide deliberately);
      write `SKILL.md` from the skeleton above.
- [ ] 2. Fill the slots (1–12). If the deltas from this guide are numerous, write a
      `references/<repo>-conventions.md` documenting **every** override ("already applied —
      do not revert") — that file is what keeps the instantiation honest.
- [ ] 3. Bundle the scaffold + verify **scripts** (deterministic beats prose) and the
      validation **workflow** (slot 6a). Answer slot 12 *first* — the seam-audit angles are
      only as good as the ground-truth commands you hand them.
- [ ] 4. Dry-run: regenerate the most recent shipped package's skeleton and diff — the
      structure should match 1:1.
- [ ] 5. **Run both gates against an already-shipped package.** This is the honest calibration:
      a gate that reports nothing on a real package is untested, not clean. (Run here, the
      citation gate immediately found 17 broken references and 35 uncited QA cases in a package
      that had passed every prior gate — including a whole `P1–P10` series that never existed.)
- [ ] 6. Record the instantiation deltas to the repo's Mulch store.

## Related

- [`AUTONOMOUS-IMPLEMENTATION-FLOW.md`](../../guides/AUTONOMOUS-IMPLEMENTATION-FLOW.md) — the canonical method (§2 planning, §3 decomposition, §4 prompt template, §6 slots, §7.0 wording)
- [`AUTONOMOUS-IMPLEMENT-SKILL-TEMPLATE.md`](AUTONOMOUS-IMPLEMENT-SKILL-TEMPLATE.md) — the phase-③ runner counterpart
- [`DEEP-REVIEW-TEMPLATE.md`](DEEP-REVIEW-TEMPLATE.md) — the phase-④ counterpart, same instantiation model
