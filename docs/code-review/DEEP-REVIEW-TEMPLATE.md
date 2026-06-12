---
tags:
  - status/active
  - type/review
  - area/docs
---

# Deep-review template — a portable, adaptable review harness

A **vendor-neutral template** for a full-lifecycle, agent-driven code review: scope a change → analyze
it through several independent lenses → **adversarially refute every finding** → fix → validate → write
a review note → commit. It is the generalized form of the `deep-review` skill this project runs (which
is project-tuned and lives in `.claude/skills/`, **gitignored** — so this doc is how the method travels).

> **Why a template and not the skill itself.** The skill is wired to this repo (its module names, its
> build command, its e2e rig). The *method* underneath is portable. Everything project-specific below is
> marked **«FILL IN»** — copy this file into your own repo, replace the slots, and you have your own
> review skill or `/command`. Nothing here is specific to authorization or Spring; the worked examples
> are, because concrete beats abstract — swap them for your domain's load-bearing invariant.

> **Provenance.** The orchestration shapes this template uses — *fan-out*, *adversarial verification*,
> *completeness critic*, *match complexity to value* — are **Anthropic's** dynamic-workflows patterns
> ([docs](https://code.claude.com/docs/en/workflows), the
> "[a harness for every task](https://claude.com/blog/a-harness-for-every-task-dynamic-workflows-in-claude-code)"
> blog). This template is an application of them to code review, not an invention of them. The
> per-project knowledge store referenced throughout is **Mulch** ([Jaymin West](https://github.com/jayminwest/mulch));
> the planning-time elicitation it pairs with is **grill-me** ([Matt Pocock](https://github.com/mattpocock/skills)).
> See [`../guides/AUTONOMOUS-IMPLEMENTATION-FLOW.md`](../guides/AUTONOMOUS-IMPLEMENTATION-FLOW.md) §8 for
> how the three fit together.

---

## The one idea that makes this worth more than "read the diff"

A single agent reviewing its own (or anyone's) change has three well-documented weaknesses: it **misses
things** on a confident single pass, it **over-grades plausible-but-wrong findings**, and — subtler — a
refute-only pipeline **can only shrink the finding set, never grow it**, so whole defect classes no lens
generated (an unswept sibling, a seam nobody wired) stay invisible. This harness counters all three
structurally:

- **Independent lenses** (fan-out) — several reviewers, each blind to the others, each hunting a *specific
  failure class*. Diversity surfaces what one pass overlooks.
- **Adversarial verification** — every candidate finding is handed to a *separate skeptic* whose job is
  to **refute** it; it survives only if re-confirmed from the source. This is the filter that keeps
  noise out of the final report.
- **A completeness critic that widens** — after refutation, one pass hunts what no lens reported:
  the mirrored sibling of fixed code, a new seam with zero callers, an untested off-state or recovery
  path. Its candidates face the same refutation.

One more, about how the lens prompts themselves are written: **state the invariant that generates the
defect class, not the mechanical fix of one past incident**. A rule written from one bug's mechanics
("acquire the lock first", "re-fetch inside the transaction") gets satisfied *literally* by code that
still carries the defect (it locked first — then acted on a decision made on unlocked state). Checklist-
shaped rules are load-bearing for an LLM reviewer: write them as invariants, keep mechanisms as named
instances.

If you take nothing else from this template, take those. The phases below are the scaffolding around
them.

---

## Adapt-me slots (fill these in once, per project)

| Slot | What it is | Example (this repo) |
|------|-----------|---------------------|
| **«BUILD»** | The one command that compiles + runs the full test suite | `./gradlew build` |
| **«UNIT TEST»** / **«IT»** | How unit vs integration tests run; the *real* dependency they hit | unit: module test; IT: Testcontainers **real Postgres** (never a substitute DB) |
| **«E2E»** | The end-to-end suite + how to bring the rig up | newman suites under `scripts/postman/`; rig via `./deploy.sh up` |
| **«MODULES»** | The module/layer map + any one-way dependency rule | `core ← spring-security/spring-data ← starter`; **core stays framework-free** |
| **«LOAD-BEARING INVARIANT»** | The one bug class that matters most in *this* codebase | **fail-closed**: no path returns *more* access on error than on success |
| **«BRANCH / IDENTITY»** | Branch naming + the commit identity to verify | `feature/<user>/<slice>`; `git config --local user.email` is the personal one |
| **«GUIDES»** | The docs a reviewer reads per touched surface | `docs/guides/*`, the ADRs in `docs/architecture/adr/` |
| **«EXPERTISE STORE»** | The prime-before / record-after knowledge store | Mulch: `ml prime <domain>` / `ml record … && ml sync` |
| **«REVIEW NOTE HOME»** | Where the written review lands | `docs/code-review/<CHANGE>-REVIEW.md` |

Everything below references these slots by name.

---

## Phase 0 — Load expertise

Before reading any code, prime accumulated review knowledge so the reviewer starts with the project's
hard-won lessons, not a blank slate.

- **«EXPERTISE STORE»**: prime the review domain + search the touched surface. *(This is the single
  highest-leverage step — it's the difference between "an agent that knows this codebase's traps" and
  "a clever stranger.")*
- Skim the relevant **«GUIDES»** and any **ADRs** that govern the touched area. If the change came from
  an autonomous run, read the flow doc whose hard rules you'll verify held.

## Phase 1 — Intake & context

### 1.1 Understand the change
```
git branch --show-current
git log --oneline «BASE»..HEAD
git diff «BASE»...HEAD --stat
```

### 1.2 Assess scope & risk — this drives everything downstream
- **Size**: Small (<100 lines) · Medium (100–500) · Large (500+).
- **Type**: new capability · adoption/integration · policy/security · infra · docs · a full slice.
- **Risk areas**: which **«MODULES»** / surfaces are touched?

### 1.3 Select the lenses
Map changed files → the failure classes worth a dedicated lens. A starting matrix (replace rows with
your surfaces):

| Changed surface | Lens to apply |
|-----------------|---------------|
| **Any main-source change** | **security audit** (always on): weakened scope/ownership checks, a fallback engaging wider than designed, caches serving authz artifacts across subjects/requests, injection surfaces (expression languages, string-built queries), secrets/internal state in logs or error bodies, authn edges defaulting to a subject |
| **Any mutating handler/service** | **concurrency & idempotency** (always on): every decision that gates a mutation computed under the lock/version-guard that holds through the commit — *locking first but acting on a pre-lock decision is the defect*; out-of-tx decisions version-bound to the acted-on row; retried/replayed requests converge |
| Core / framework-agnostic module | **boundary**: does it stay free of the deps it forbids? Is the public API change *additive*? |
| The security / decision path | **«LOAD-BEARING INVARIANT»**: trace every error/empty branch to its safe terminal |
| Policy / rules | default-deny explicit? no unconditional allow? a test per new rule? |
| Data / query layer | does a new filter *narrow* (AND-ed with existing scope), never *widen* (a bare re-query)? |
| Schema / migrations | entity ↔ migration ↔ real DB agree (a clean boot/validate is the proof) |
| API contract | spec ↔ handlers ↔ generated types in sync |
| Infra / e2e rig | the rig's known gotchas (token issuance, cache reloads, image rebuilds) |

---

## Phase 2 — Deep review (route by size/risk: *match complexity to value*)

| Size / risk | Path |
|-------------|------|
| Small/Medium, low-risk, single module | **2A — single sub-agent** (cheap, fast) |
| **Large OR high-risk** (security, fail-closed, multi-module, a full slice) | **2B — multi-lens adversarial workflow** |

> Don't spin up a fan-out workflow to review a 20-line docs change, and don't single-pass a
> 600-line security change. The routing *is* the "match architectural complexity to value" decision
> applied per review.

**The invariant rule (both paths).** For this codebase the bug class that matters most is
**«LOAD-BEARING INVARIANT»**. Whenever the diff touches it, explicitly trace *every* error / missing-input
/ timeout branch and confirm it lands on the safe terminal. A violation is always **Critical**.

**Autonomous-run lens (both paths, if the change came from an autonomous run).** Three failure modes such
a run is exposed to — check each:
- **Agentic laziness** — declared done on partial work (acceptance asserted *shape* but not the actual
  *effect*; N of M items done).
- **Self-preferential bias** — a status note's "review found nothing" where the diff says otherwise;
  ritual refactor notes with no real change (or a real issue glossed).
- **Goal drift** — a load-bearing invariant eroded across commits (the safe-terminal weakened, a
  forbidden dependency crept into the pure module, an "additive-only" change turned breaking).

### Path 2B — Multi-lens adversarial workflow (the harness)

Run as a dynamic workflow (or, without that feature, as several sequential sub-agents). The shape:

1. **Fan-out** — one **specialist lens per dimension** from Phase 1.3, in parallel. Each lens is
   prompted to hunt *one* failure class and is **blind to the others**. Each reports only
   evidence-backed findings (quote the code that proves it); an empty list is a valid result.
2. **Adversarial verification** — hand **every** candidate finding to a *separate skeptic* agent
   prompted to **refute** it ("default to *refuted* unless you can re-confirm from source"). For a
   finding that can fail in more than one way, give skeptics *distinct lenses* (correctness / security /
   does-it-actually-reproduce) rather than N identical ones. A finding survives only on re-confirmation.
3. **Completeness critic** — the *widening* pass refutation cannot provide. Given the diff and the
   survivors so far, it hunts only what the per-file lenses are structurally blind to: **unswept
   siblings** (the mirrored operation / same base class / the equivalent handler elsewhere, carrying the
   same defect a lens found in one place); **zero-caller seams** (a new SPI, property, guard, exception
   + advice mapping, accessor, or declared recovery edge with no non-test consumer — shipped but inert);
   **untested off-states** (a kill-switch state, an error mapping, a retry path nobody exercises); and
   acceptance cases with no corresponding test. Its candidates face the same adversarial refutation.
4. **Synthesis** — dedupe, severity-sort, output `{ confirmed, refuted }`.

Then **spot-verify the Criticals yourself** by reading the files. The harness cuts false positives; you
still own the verdict — and you may hold context (from the change's description or the **«EXPERTISE
STORE»**) the agents lack.

> A reference implementation of this exact shape (fan-out → refute → synthesize) ships next to the
> project skill as `deep-review-workflow.js`. It is **gitignored** with the rest of `.claude/skills/`;
> this template is the committed description of what it does so the method is reproducible without it.

### Path 2A — Single sub-agent (Small / Medium)

Delegate the analysis to one general-purpose sub-agent. Prompt template:

```
Review branch «BRANCH» vs «BASE».
<N> files changed, <type>. Changed modules: <from git diff --stat>.

Check, with evidence (quote the code that proves each — empty list is valid):
- «LOAD-BEARING INVARIANT»: does any path widen/weaken it on error or missing input?
- Security: any weakened scope/ownership check? a fallback engaging wider than designed? a cache that
  could serve an authz artifact across subjects/requests? user input reaching an expression language or
  string-built query? secrets/internal state in logs or error bodies?
- Concurrency/idempotency: is every decision that gates a mutation computed under the lock/version-guard
  that holds through the commit (locking first but deciding pre-lock is the defect)? do retries converge?
- Wiring: does every NEW seam (SPI, property, guard, advice, accessor, recovery edge) have a non-test
  caller and a test through its non-happy path?
- Boundary: does «pure module» stay free of «forbidden deps»?
- Additivity: are public APIs changed additively (old tests unchanged-green)?
- Narrow-not-widen: is any new filter AND-ed with existing scope, never a bare re-query?
- Policy: default-deny explicit? no unconditional allow?
- Schema: entity ↔ migration ↔ real DB agree?

Focus on: <highest-risk areas from Phase 1>.
```

Then verify the Criticals yourself.

---

## Phase 3 — Fix

Fix on the branch, in priority order:
1. **Critical** — anything that violates **«LOAD-BEARING INVARIANT»**, compile breaks, schema drift, a
   forbidden-dependency leak.
2. **Medium** — wrong behavior, missing validation, a change that should be additive but isn't, a filter
   that replaces instead of narrowing.
3. **Low** — style, docs, optional cleanup.

For each fix: add/adjust the test that would have caught it (the data-layer fix gets an **«IT»** against
the *real* dependency; a policy gets a policy-test case). Delete dead code rather than deprecating it.
Keep any clean-room / IP boundary the project has (no proprietary names, paths, ids, tokens).

**Sibling sweep (mandatory per fix).** When a fix lands in X, grep the same pattern across X's mirrored
siblings — same base class, same package, the mirrored operation (create/delete, assign/unassign, the
equivalent handler in another service or module) — and fix them **in the same commit**. The skeptics can
only narrow the finding set; the sweep is how a confirmed defect class gets applied to every instance.
Record hits or "siblings clean" in the review note.

---

## Phase 4 — Validate

1. **«BUILD»** → green (all modules + integration tests + any codegen + a clean schema validate/boot).
2. Policy/rule change → run the policy test suite too.
3. **«E2E»** when the diff touches the runtime path → bring the rig up and run the relevant matrix.
   Make the e2e assert the **effect** (the cut — row counts, allow-vs-deny), not just status codes.

Keep a **failure → likely-cause** table for *your* rig (the gotchas that read like bugs but aren't —
stale caches, image reuse, token issuance, environment-not-code). The project's lives in
[`CODE-REVIEW-WORKFLOW.md`](CODE-REVIEW-WORKFLOW.md) / the e2e guide; mirror that here per project.

---

## Phase 5 — Document & commit

### 5.1 Write the review note (mandatory) → **«REVIEW NOTE HOME»**
```markdown
---
tags: [ status/active, type/review, area/«…» ]
---
# «Change» — Code Review
> **Verdict**: Approved with fixes / Needs fixes / Rejected
> **Scope**: <1–2 sentences> · **Branch**: «BRANCH» vs «BASE»
## Summary
## Critical Issues
## Medium Issues
## «LOAD-BEARING INVARIANT» verification   (every error/empty path → safe terminal)
## Security audit                          (scope checks · fallback interplay · cache safety · injection · secrets/leaks)
## Concurrency & idempotency               (decide-under-protection · version binding · retry convergence)
## Wiring & sibling sweep                  (new seams have callers + non-happy-path tests; fixes swept across mirrors)
## Autonomous-run check                    (if applicable — laziness / self-pref bias / goal drift)
## What's done right
## Test results                            («BUILD» · policy tests · «E2E» matrix)
```
The review note ships **in the fix commit** — it's part of the deliverable, the artifact that makes the
review studyable later.

### 5.2 Update docs / write an ADR
If behavior changed, update the affected **«GUIDES»**. If the fix took a *structural* fork, write an ADR
— don't bury rationale in the review note.

### 5.3 Commit
Verify **«BRANCH / IDENTITY»** first. Conventional subject (`fix(<scope>): …`; a separate
`refactor(<scope>): …` for dead-code removal). **Do not push / open a PR / merge** unless that's
explicitly the maintainer's call — review and ship are separate, deliberate steps.

### 5.4 Record to the **«EXPERTISE STORE»**
Record any genuinely reusable pattern / failure / decision the review surfaced, so the *next* review
starts ahead. (With Mulch: `ml record … ` then sync — and if your store shares the repo, make the sync
commit touch only the store, not swept-in staged code.)

---

## What to keep when you adapt this

The phases are negotiable; **these are not** — they're what make the harness better than a careful read:

- **Independent lenses, then adversarial refutation, then a critic that widens.** Fan-out for coverage,
  skeptics for precision, the critic for the classes no lens generated (siblings, wiring, off-states) —
  refutation alone can only shrink the finding set.
- **Name the one load-bearing invariant and trace every failure branch to its safe terminal.** Generic
  "be careful" is useless; "no path returns more access on error than on success — here are the exact
  branches" is the review.
- **Write lens rules as invariants, not as one incident's mechanics.** A mechanism rule gets satisfied
  literally by defective code; the invariant ("decide under the protection you act under") covers the
  class. Keep security and concurrency/idempotency as first-class lenses, not subsets of the main invariant.
- **Route by size/risk.** Cheap path for cheap diffs; the full harness only where the value justifies it.
- **The review note is a deliverable**, committed with the fix — so the *why* survives.
- **Prime before, record after.** The store is what compounds review quality across changes.

## Related
- [`../guides/AUTONOMOUS-IMPLEMENTATION-FLOW.md`](../guides/AUTONOMOUS-IMPLEMENTATION-FLOW.md) §8 — the
  full tooling stack (Mulch / grill-me / deep-review / dynamic workflows) and upstream credits.
- [`CODE-REVIEW-WORKFLOW.md`](CODE-REVIEW-WORKFLOW.md) — this project's concrete (non-templated) review lifecycle.
- [`CODE-REVIEW-CHECKLIST.md`](CODE-REVIEW-CHECKLIST.md) — this project's per-finding checklist.
- Anthropic dynamic workflows — [docs](https://code.claude.com/docs/en/workflows) ·
  [harness blog](https://claude.com/blog/a-harness-for-every-task-dynamic-workflows-in-claude-code).
