---
tags:
  - status/active
  - type/guide
  - area/docs
  - area/build
---

# AUTONOMOUS-IMPLEMENT skill — instantiation template (phase ③)

The portable form of a `/autonomous-implement` skill — the **runner discipline** around the
phase-③ loop of [`AUTONOMOUS-IMPLEMENTATION-FLOW.md`](../../guides/AUTONOMOUS-IMPLEMENTATION-FLOW.md).
Companion to [`DECOMPOSE-SKILL-TEMPLATE.md`](DECOMPOSE-SKILL-TEMPLATE.md) (phase ②) and
[`DEEP-REVIEW-TEMPLATE.md`](DEEP-REVIEW-TEMPLATE.md) (phase ④).

> **Do you need this at all?** This repo runs phase ③ by **pasting the prepared
> `AUTONOMOUS-IMPLEMENTATION-PROMPT.md` into a fresh session** — the prompt is self-contained
> (L4), the skeleton is hardened, and for a single-repo slice that is enough. The skill
> wrapper earns its keep when any of these hold:
> - **Multi-repo slices** — coordinated branches and a cross-service order the runner must
>   re-derive each ticket;
> - **Resume/re-entry** — a run interrupted mid-package needs the "where was I" discipline
>   (read STATUS stubs, re-verify, continue) that a bare prompt doesn't carry;
> - **Escalation enforcement** — the ★gate's review path varies by ticket risk and you want
>   that table outside the prompt, applied uniformly across packages;
> - **Pre-flight verification** — the skill refuses to start on an incomplete package
>   (verify script red → back to `/decompose`), where a pasted prompt would improvise.
>
> The prompt stays authoritative either way: **the skill is runner discipline around the
> prompt, not a replacement for it.**

---

## The invariant core (keep in every instantiation)

1. **Prerequisite gate.** A complete package that passed **both** phase-② gates — the mechanical
   verify script *and* the adversarial validation pass (seam existence · unpinned semantics ·
   cross-doc consistency). Re-run the script here; for the adversarial gate, confirm it was run and
   its run-stoppers closed, because that is the one that establishes the tickets name APIs which
   actually exist. No package, red gates, or an adversarial gate that never ran → STOP and route to
   `/decompose`; never improvise the plan here. **A green script alone is not a verified package** —
   it checks shape, not claims.
2. **The three failure modes are the design brief.** Every structural element counters one:

   | Failure mode | The counter in the loop |
   |---|---|
   | Agentic laziness | fix-until-green within the ticket; the per-ticket **checkpoint report**; one ticket = one objective |
   | Self-preferential bias | the ★gate's heavy path is **adversarial review by separate agents** (the repo's deep-review), never self-grading |
   | Goal drift | **the plan is the package, not the conversation** — re-read the ticket's decomposition section + What-NOT-to-touch at the start of every ticket |

3. **The per-ticket loop, in order, no skipping**: prime → build → tests → compile + unit
   green → **★ architecture review + refactor (BEFORE integration/e2e)** → integration/e2e →
   docs (tick the index, fill the STATUS stub) → Mulch → **one focused commit** →
   **CHECKPOINT: stop and report**. Do not batch tickets.
4. **The ★gate escalates by ticket risk** (slot 3): low-risk → focused manual self-review
   against the named template; domain-surface → the repo's targeted review skill; headline /
   high-risk → commit first, then the adversarial multi-lens review workflow on the committed
   diff — and spot-verify every Critical yourself before trusting an empty result.
5. **Fix-until-green, with a defined blocked-exit**: stop mid-ticket only when the same root
   cause survives ≥3 focused attempts, a design fork the docs don't cover changes
   externally-visible behavior, or a local prerequisite is unrecoverable. Report the block;
   never silently scope-cut.
5a. **A ticket that names a seam reality disagrees with is a REPORTED deviation, never a silent
   adaptation.** When a named third-party class/method/property/endpoint/policy path turns out not
   to exist, to have a different signature, or to behave differently: confirm against the artifact
   (disassemble the jar, read the shipped types, run the policy engine, call the endpoint), then
   **write the deviation into the ticket's STATUS *Decisions* section in your own words** before
   proceeding — and escalate to a stop-and-ask if the correction changes externally-visible
   behavior. This is the failure mode `fix-until-green` most easily swallows: the agent quietly
   adapts, the run stays green, and a **planning defect that would recur in every future slice
   disappears without a trace**. The whole outer loop depends on it surfacing.
6. **The decided forks are binding** ("do NOT re-ask"); a *new* behavior-changing fork is a
   stop-and-ask, an internal detail with a sane default is decide-and-record-in-STATUS.
7. **Never push, open PRs/MRs, or touch the default branch.** Local + the named branch(es)
   only. Phase ④ is the maintainer's.
8. **Close-out**: final summary (all tickets, the headline proof, the full-suite result), the
   maintainer-only handoff list, offer the phase-④ review, move the package to
   `implemented/` — and **record a run retrospective to the repo's DEDICATED retrospective domain.**

   That domain is an **invariant of the method, not a per-repo slot**: it is the outer loop's memory,
   the only artifact that survives a context window, and the substrate the phase-② unpinned-semantics
   critic reads. Two properties are load-bearing and both fail quietly:
   - **Dedicated, never folded into a general store.** Mixed in with technical records it becomes
     unprimeable in practice — you cannot pull "the pause history" without dragging in everything
     else, so nobody does.
   - **Read as well as written.** If phase ② doesn't prime it (decompose invariant 1b), records
     accumulate that nothing consumes, and the loop *looks* closed while nothing flows back.
     Verify both directions at instantiation, not once the packages exist.

   One record per run: what the
   structure caught, where the run stalled or asked, which invariant earned its keep, what
   the next package should pin earlier). **Classify every pause and every reported deviation (5a)
   into a named class**, so runs are comparable and a class recurring twice can be converted into a
   gate rather than restated as advice. The classes this method has accumulated: *blast radius too
   large to enumerate* · *a fail-open/contract semantic left unpinned* · *a third-party seam named
   from a mental model* · *a rig/test-harness gotcha discovered mid-run*. An unclassified
   retrospective is a diary entry; a classified one is an input to the next package's gates.

## The slots (what each repo decides)

| # | Slot | What to pin | Notes |
|---|------|-------------|-------|
| 1 | Branch pattern + commit identity | e.g. `feature/<owner>/<slice>`, the `git config --local user.email` check, trailer policy (`Co-Authored-By` welcome vs forbidden) | Verify identity in pre-flight, not after the first commit |
| 2 | Build / test / e2e commands | the exact unit, integration, and e2e invocations + the rig bring-up script | Exactness here is what makes "green" falsifiable |
| 3 | ★gate escalation table | risk tier → review path, naming the repo's actual review skills/workflows | The heavy path must be *separate agents* (bias counter), and it only sees **committed** diff — commit first |
| 4 | Rig prerequisites | containers/services that must be up; the local-vs-rig assertion split (record rig-only checks as **pending**, never fake them) | |
| 5 | Multi-repo rules | per-repo branches, the cross-service build/publish order, which repo's package owns the STATUS stubs | Single-repo: N/A |
| 6 | Docs to reconcile per ticket | the index status table, STATUS stub shape, which guides a ticket must update | |
| 7 | Mulch domains | what to prime per ticket (`ml prime --files …`) + the **run-retrospective** domain for close-out | Stage-hygiene: `git restore --staged .` before `ml sync` |

## SKILL.md skeleton

```markdown
---
name: autonomous-implement
description: Execute a <repo> decomposition package ticket-by-ticket on a feature branch,
  with a per-ticket architecture-review gate and a checkpoint after each ticket; never
  pushes or merges. Use when a planning package is ready to build, or the user says
  implement the tickets / run the autonomous prompt.
argument-hint: [package folder or slice/ticket id]
allowed-tools: Read Grep Glob Bash Edit Write Agent Workflow Skill AskUserQuestion
model: opus
effort: high
---
# Autonomous implement — run a decomposition package (phase ③)
> The package's AUTONOMOUS-IMPLEMENTATION-PROMPT.md is the marching orders; this skill is
> the runner discipline around it.
## The three failure modes this structure defeats   ← the table, verbatim
## Phase 0 — Load & verify    ← resolve package; BOTH phase-② gates green or STOP → /decompose
## Phase 1 — Set up           ← slot 1 branch/identity; decided forks binding; slot 4 rig
## Phase 2 — The per-ticket loop  ← the 10 steps; slot 2 commands; slot 3 ★gate table
## Phase 3 — Close out        ← summary, handoff list, retrospective (slot 7), move package
## Hard rules                 ← critical-first ordering; positive constraints (§7.0)
```

## Instantiation checklist

- [ ] 1. Write `SKILL.md` from the skeleton; fill the 7 slots.
- [ ] 2. Keep the loop and hard rules aligned with the §4 prompt skeleton **word-for-word
      where they overlap** — the skill must never contradict the prompt it runs.
- [ ] 3. Add the repo's review skills to the ★gate table (slot 3); confirm the heavy path
      reviews committed diff.
- [ ] 4. **Confirm a DEDICATED retrospective domain exists, and create one if it does not** — the
      name varies per repo (this repo's is `autonomous-runs`), but a general-purpose domain does
      **not** satisfy this: "we file retrospectives into the main store" is the failure, not the
      workaround. Then verify the loop is closed in **both** directions — the phase-② skill primes
      it (decompose invariant 1b) *and* close-out writes to it (invariant 8). A repo that only writes
      has half a loop and will re-earn lessons it already paid for.
- [ ] 5. Dry-run against the most recently shipped package: Phase 0 must pass its verify
      gates; the loop's commands must be copy-paste runnable.
- [ ] 6. Check the alignment the loop depends on in **both** directions: the ★gate's escalation
      table names review skills that exist, and invariant 5a's deviation rule appears in the §4
      prompt's hard rules too. A runner that reports wrong-seam deviations while the prompt tells
      the agent to work around them will follow whichever it read last.

## Related

- [`AUTONOMOUS-IMPLEMENTATION-FLOW.md`](../../guides/AUTONOMOUS-IMPLEMENTATION-FLOW.md) — §4 (the prompt this skill runs), §5 (the invariant skeleton), §7 (lessons), §7.0 (wording rules)
- [`DECOMPOSE-SKILL-TEMPLATE.md`](DECOMPOSE-SKILL-TEMPLATE.md) — produces the package this skill executes
- [`DEEP-REVIEW-TEMPLATE.md`](DEEP-REVIEW-TEMPLATE.md) — the review authority the ★gate's heavy path reuses
