---
tags:
  - status/active
  - type/guide
  - area/docs
  - area/build
---

# Autonomous implementation flow

The repeatable **plan → decompose → autonomous-implement → test → review** process behind every shipped
slice in this repo ([[DOMAIN-MODEL-FOUNDATION]], [[LIBRARY-SPINE]], [[USER-MANAGEMENT-SERVICE]],
[[TAG-DICTIONARY]], [[DATA-FILTERING]], [[HIERARCHY-SINGLE-RESOURCE]], [[HIERARCHY-LIST-FILTER]],
[[REST-API-REFINEMENT]]). It is also a **first-class portfolio artifact**: each slice's
planning package + `AUTONOMOUS-IMPLEMENTATION-PROMPT.md` is kept verbatim and its `STATUS-0N.md` notes
record the outcome, so the *whole* artifact — how the work was reasoned about, then handed off, then
verified — is a studyable case study of high-autonomy AI-assisted engineering.

This guide documents the **end-to-end method** and is the **template + checklist** for producing the
next slice. The work happens in three phases:

1. **Planning** ([§2](#2-planning-phase-chat--grill-me--decisions)) — interactive: chat, an optional
   `grill-me` session, ending in pinned **ADRs**, **user stories**, and a `00-DESIGN`. *Resolve the forks
   here so the run has fewer reasons to stop.*
2. **Decomposition** ([§3](#3-decomposition-phase-design--tickets)) — turn the design + ADRs + stories
   into the ordered ticket list, the QA cases, and the self-contained prompt. *This is the bridge: still
   interactive/docs-only, but it produces the agent's marching orders.*
3. **Autonomous implementation** ([§4](#4-the-autonomous-implementation-promptmd-template) onward) — one
   agent runs the prompt, ticket by ticket, checkpoint-gated, fail-closed. *This is the autonomous half.*

The **tooling that powers each phase** — Mulch, `grill-me`, `deep-review`, and the Claude Code dynamic
workflows underneath — with upstream credits and the Anthropic orchestration patterns each one
instantiates, is documented in [§8](#8-the-tooling--skills-stack-what-powers-each-phase).

> **Why this shape.** Planning + decomposition front-load the thinking into immutable artifacts (ADRs)
> and an unambiguous work list; the autonomous prompt then turns that into a *self-contained,
> checkpoint-gated, fail-closed* hand-off that one agent executes end to end without drifting — because
> the architecture-review gate and the per-ticket checkpoint catch mistakes before they compound, and
> the hard rules pin the load-bearing invariants the agent must never trade away.

---

## 1. The lifecycle (one slice, start to finish)

```
        ┌─────────────────────────────────────────────────────────────┐
        │ ① PLANNING (interactive, with the maintainer)                │
        │   chat → optional /grill-me to resolve every fork            │
        │   end-results:  ADR note(s)  ·  USER-STORIES  ·  00-DESIGN    │
        │   docs-only — NOT the implementation                         │
        └───────────────────────────┬─────────────────────────────────┘
                                     │ forks resolved, decisions pinned
                                     ▼
        ┌─────────────────────────────────────────────────────────────┐
        │ ② DECOMPOSITION (interactive, docs-only)                     │
        │   design + ADRs + stories  →  the work list                  │
        │   end-results:  <SLICE>.md index · 01-DECOMPOSITION (T1…TN)   │
        │     · 10-QA-TEST-CASES · AUTONOMOUS-…-PROMPT · STATUS stubs   │
        └───────────────────────────┬─────────────────────────────────┘
                                     │ maintainer reviews the package
                                     ▼
        ┌─────────────────────────────────────────────────────────────┐
        │ ③ AUTONOMOUS IMPLEMENT (one agent, the §4 prompt)            │
        │   branch feature/void3110/<slice>                            │
        │   per-ticket loop T1…TN, IN ORDER, STOP at each checkpoint:  │
        │     prime → build → test → ★review+refactor → IT/e2e → docs  │
        │            → Mulch → one commit → CHECKPOINT & report         │
        └───────────────────────────┬─────────────────────────────────┘
                                     │ maintainer reads checkpoints
                                     ▼
        ┌─────────────────────────────────────────────────────────────┐
        │ ④ REVIEW / SHIP (maintainer-driven)                          │
        │   /deep-review the branch · push · PR · CI green · merge      │
        │   record the run retrospective → `autonomous-runs` Mulch      │
        │   git mv  planning/ → implemented/  + Shipped banner          │
        └─────────────────────────────────────────────────────────────┘
```

**Division of labour (a hard line, carried in every prompt):** in phases ① and ②, the maintainer and the
planning agent work *together* and produce only docs. In phase ③ the agent does **everything local on
the branch** — code, tests, docs, Mulch, one commit per ticket. The **maintainer pushes, opens the PR,
and merges** (phase ④). The prompt must say *"Do NOT push, open PRs, or touch `main`."* Pushing is a
separate, explicit "let's push and create pr and then merge" turn.

---

## 2. Planning phase (chat + grill-me → decisions)

**Goal:** reach *shared understanding* and pin every fork **before** any ticket exists — so the
autonomous run almost never has to stop and ask. This phase is conversational and docs-only.

**The flow.** Discuss the slice with the maintainer; when the design tree has real branches, run the
**`grill-me`** skill — it interviews the maintainer one question at a time, walking each branch and
recommending an answer, until the decisions are settled. (Phase 6.5's coarse-permission-categories ADR
came straight out of such a session.) Explore the codebase to answer anything the code can settle, rather
than asking.

**The three end-results (the planning deliverables):**

| Deliverable | Where it lives | What it pins |
|-------------|----------------|--------------|
| **ADR(s)** | `docs/architecture/adr/NNNN-*.md` | Each **structural** fork — a schema/authority shape, a module/service boundary, *where a check is evaluated* (app vs. policy), an additive-vs-breaking choice, a deliberate "we did **not** do X." Lightly-MADR: **Status · Context · Decision · Considered options (why-rejected) · Consequences.** Immutable once `Accepted`; superseded, never edited. |
| **USER-STORIES** | `docs/to-do/planning/USER-STORIES.md` | The **product lens** — "as a «persona» I can/can't …", each story **tagged to the phase that delivers it**. Keeps the project honest about *who the authorization is for* and gives each phase a user-visible acceptance lens beyond "the test is green." |
| **`00-DESIGN.md`** | the slice's planning folder | The **how-it-works** prose: the mechanism, the **fail-closed posture**, and a **considered-&-rejected** list. Links the ADR(s) for the *why*. This one is *living* (rewritten as the work evolves); the ADRs are not. |

**ADRs are written *up front, as part of planning* — not retroactively.** A feature's
`00-DESIGN`/`01-DECOMPOSITION` are living docs that get rewritten and `git mv`'d to `implemented/` on
ship, so a rationale buried in them drifts or moves; an ADR is an *immutable, dated snapshot* of the fork
and its rejected alternatives. Reach for one only when you catch yourself writing a "considered &
rejected" list worth keeping — routine choices (naming, file layout, test library) don't need one. (See
`docs/architecture/adr/README.md` → "When to write one.")

**Exit criterion for planning:** every fork that would otherwise make the autonomous agent *stop and ask*
is decided and recorded (in an ADR if structural, in `00-DESIGN`'s considered-&-rejected otherwise), and
the user stories for the slice exist and are phase-tagged. Only then move to decomposition.

### 2a. The slice-sizing gate (run before decomposition)

**Slice size is the lever.** The recurring reason an autonomous run *pauses and asks* is "design left a
fail-open/contract semantic unpinned" — and that gap is usually a **symptom of an oversized slice**, not
just shallow grilling. When a slice both **changes a load-bearing shared mechanism** *and* **adds new
surface that depends on it**, the phase-① blast-radius analysis can't stay complete in one pass: the
design enumerates the headline path and misses the secondary consumers — which then surface as
regressions in the e2e instead of being pinned in the design. **A slice's blast-radius must be small
enough to fully enumerate during `grill-me`.** If it isn't, split the slice — don't accept a partial
blast-radius and hope the run catches the rest.

**Split smells — if any hold, split before writing the autonomous prompt:**

- **(a) Remove-and-replace in one slice** — the slice *both* removes/changes a shared mechanism *and*
  adds new surface that depends on it. **Split the removal + its fix from the new feature.**
- **(b) Crosses more than one deployable** in a single prompt (library + a service + the gateway).
- **(c) Ticket count > ~5–6** — a strong smell the slice spans more than one coherent blast-radius.
- **(d) Phase ① can't name every consumer** of a mechanism the slice changes. If you can't enumerate
  them, you can't pin their contract — and an unpinned one becomes a mid-run regression.

> **Worked example — B4 (`MULTI-TENANT-ISOLATION`) was too big** (the cautionary case). One 9-ticket slice
> spanned a new library annotation seam (`@OpaPreAuthorize` role-on-parent), two services, the APISIX
> gateway, **and** a rewrite of a load-bearing policy clause (removing the realm-role fallback). The
> design's blast-radius saw the fallback removal as "the *list* rows lose the fallback"; it was actually
> load-bearing for **every type-level `@OpaPreAuthorize` gate** (list **and** create **and**
> tag-on-create) and **~3 existing test matrices seeded fixtures via it** — three regressions that only
> surfaced in the e2e. It hit smells (a), (b), (c), and (d) at once. It **should have been two slices:**
> **(1) isolation-only** = the filter entrypoint + fallback removal + the type-level-gate role-resolution
> fix, proven by the isolation matrix + a re-run of the existing suite; **(2) self-service** = the
> ownership SPI + `createTeam` + gateway routing. Each ~4–5 tickets, each blast-radius enumerable. (Mulch
> `autonomous-runs` `mx-76cf08`.)

**Exit criterion for sizing:** the slice trips none of (a)–(d), *or* it has been split into slices that
each pass. Only a slice that passes this gate goes to decomposition.

---

## 3. Decomposition phase (design → tickets)

**Goal:** turn the settled design + ADRs + stories into an **unambiguous, ordered work list** and the
**self-contained prompt** that drives it. Still interactive and docs-only — this is the bridge between
"we know what and why" and "an agent can now build it."

> **The `decompose` skill automates this phase** (formerly `slice-planner`). It is the checklist + scaffolding for producing the
> package below from a settled design; this guide stays canonical (the skill defers to it). See
> [§8](#8-the-tooling--skills-stack-what-powers-each-phase) for the tool; the rest of this section is the
> *method* it follows.

This phase produces the rest of the **planning package** — the folder `docs/to-do/planning/<SLICE>/`,
a **1:1 structural mirror** of every prior slice:

| File | Role |
|------|------|
| `<SLICE>.md` | `type/index` — what the slice delivers, file glossary, **ticket status table**, critical path, conventions (clean-room + commit identity). |
| `01-DECOMPOSITION.md` | `type/project` — the ordered tickets **T1…TN**, each with **Goal / Deliverables / Acceptance / What-NOT-to-touch**, + a cross-cutting acceptance block, + the **critical path** (which tickets are sequential, which parallel, which independently landable). **The work list.** |
| `10-QA-TEST-CASES.md` | `type/project` — concrete U*/I*/E* cases the implementation must satisfy (these become each ticket's *Acceptance*). |
| `AUTONOMOUS-IMPLEMENTATION-PROMPT.md` | The self-contained prompt (the [§4](#4-the-autonomous-implementation-promptmd-template) template). **Kept verbatim — a deliverable, not scaffolding.** |
| `STATUS-01.md … STATUS-0N.md` | One stub per ticket, filled at each checkpoint during the run: *What shipped · Tests · Architecture review + refactor · Integration/e2e · Decisions · Commit.* |

**The decomposition discipline — what makes a good ticket:**

- **One focused commit's worth of work**, with a clear **Goal**, exact **Deliverables** (classes,
  packages, rego rules, mappings — named), **Acceptance** (drawn from `10-QA-TEST-CASES.md` — the exact
  `:module:test` / `opa test` / e2e that proves it), and **What-NOT-to-touch** (the boundary, drawn from
  the ADRs and the fail-closed posture).
- **Ordered with an explicit critical path.** Name what's sequential (T1 → T3 → …), what runs in parallel
  (e.g. "T2 parallel with T1"), and which early subset is **independently landable** for standalone value
  if the window is short.
- **Each ticket carries its slice-invariants forward.** "What-NOT-to-touch" is where AND-don't-replace,
  additive-only, match-in-Rego, flat-verb-only, etc. get pinned per ticket so the agent can't trade them
  away mid-run.
- **Flag the build-breakers in the ticket that causes them.** If widening an interface breaks existing
  test stubs (it will — adding an abstract method un-functional-interfaces it), say so *in that ticket*,
  list the exact files, and require they land in the same commit. (DATA-FILTERING T1 is the model.)
- **Name the wiring — a seam with zero callers is not done.** For every new seam a ticket introduces
  (an SPI, a config property, a guard, an exception type + its advice mapping, a cache accessor, a rego
  entrypoint, a declared retry/recovery edge), the **Deliverables name its consumers/call sites** and the
  **Acceptance exercises at least one non-happy path through it** (the error mapping reached, the
  off-state behavior, the recovery edge fired). Completeness rules framed around the happy round-trip
  silently exclude exactly these — recovery and secondary paths are first-class links to trace, not an
  appendix.

**Conventions for every note:** valid frontmatter (one `status/`, one `type/`, ≥1 `area/`),
`UPPER-KEBAB-CASE.md`, `[[wikilinks]]`, links back to `[[POC-ROADMAP]]`. A structural decision surfaced
*during* decomposition still gets its own ADR up front (decomposition is exactly when these surface —
records 0005/0006 were written this way) — the decomposition references it, the rationale doesn't live
inside the decomposition.

On ship, `git mv` the folder to `docs/to-do/implemented/<SLICE>/`, flip `status/*` → `status/done` in the
index frontmatter, and add a past-tense **Shipped** banner — keeping the prompt-and-results record intact
alongside the prior slices for cross-run comparison.

---

## 4. The `AUTONOMOUS-IMPLEMENTATION-PROMPT.md` template

Copy this verbatim into the new slice folder and fill every `«SLOT»`. Everything **not** in a slot is
the invariant skeleton — keep it word-for-word, because it is the part that has been hardened across
five runs.

````markdown
---
tags:
  - status/planned
  - type/project
  - «area/… one or more»
---

# «Slice title» — autonomous implementation prompt

> **What this is.** A self-contained prompt for implementing «the slice» autonomously, ticket by
> ticket, with an architecture-review gate and a checkpoint after each ticket. The design and work
> list it refers to live alongside it in this folder.
>
> **Before you run it:** create the branch — `git checkout -b feature/void3110/«slice»` off a clean
> `main`. Confirm `git config --local user.email` is `void31102025@gmail.com`. Then paste the
> **PROMPT** section below to the agent.

---

## PROMPT

You are implementing **«the slice»** on branch `feature/void3110/«slice»`.

**The problem.** «2–5 sentences: the gap today, the mechanism this slice adds, and the one-line
headline of why it matters. State the scope boundary — what this is and what is explicitly NOT in it
(deferred to a later phase).»

Implement the core work directly. Do not delegate the implementation to a sub-agent. Sub-agents are
welcome for **read-only scouting** (e.g. "find every place across the suite that asserts X") and for
**log-noisy validation** (e.g. run a newman matrix / a long build and report back only the failure
summary) — their findings come back to you; the code, tests, and docs are written in this loop.

### Read before you start (in order)

1. `«SLICE».md` (this folder's index) — what this slice delivers, the file glossary, the ticket
   status table, the critical path, conventions.
2. `00-DESIGN.md` — «the design + the fail-closed posture + considered-&-rejected».
3. `01-DECOMPOSITION.md` — the «N» tickets, each with Goal / Deliverables / Acceptance /
   What-NOT-to-touch. **This is your work list.**
4. **The pinned decisions** — «ADR(s) this slice implements / is constrained by».
5. `10-QA-TEST-CASES.md` — the unit / integration / e2e cases your work must satisfy.
6. **Context you will be checked against** in the review gate (step 5): «the shipped slices / guides
   whose patterns this one reuses — name the exact files».
7. Root `CLAUDE.md` — the **IP boundary** (clean-room: original names only) and the **commit identity**
   rule (`Void3110 <void31102025@gmail.com>`).
8. `infra/README.md` — the local rig (needed for the e2e ticket), incl. the **in-network token caveat**
   and the **"restart OPA after editing a policy"** gotcha.
9. **Prime Mulch:** `ml prime opa-abac` «+ the directly-relevant record ids: mx-…».

### Per-ticket loop (tickets T1 → T«N», IN ORDER «; note any parallel/optional landings»)

For each ticket do ALL of the following, in order, and **STOP at the checkpoint before the next**:

1. **Prime for the files you're about to touch.** `ml prime --files <path>`, and re-read that ticket's
   section in `01-DECOMPOSITION.md`.

2. **Build the deliverables** exactly as `01-DECOMPOSITION.md` lists them — exact classes, packages
   («`dev.dmitriikonovalov.…`»), mappings, rego rules. Match the surrounding code's naming and idioms.
   **Clean-room:** no proprietary names anywhere.

3. **Write/extend the tests** for the ticket (the relevant U*/I*/E* cases from `10-QA-TEST-CASES.md`).
   Core/client tests use an **in-process `com.sun.net.httpserver.HttpServer` stub** (no WireMock).
   Persistence/IT tests run against **real Postgres via Testcontainers** (never H2). Policies use
   `opa test` «+ `opa eval --partial` where relevant».

4. **Compile + run unit tests until green.** `./gradlew :<module>:test` (and `./gradlew build` for the
   example/IT tickets). Fix-until-green.

5. **★ ARCHITECTURE REVIEW + REFACTOR — the gate before integration/e2e validation.** Once unit tests
   pass, do NOT advance yet. Run a focused self-review, then refactor and re-test:
   - **«Fail-closed check — the slice's load-bearing invariant, stated concretely: every error/timeout/
     missing-input path lands on deny/empty, never on a wider result».**
   - **«Security check — name the widening that would matter for this ticket (a weakened scope/ownership
     check, an authorization fallback engaging wider than designed, a cache serving an authz artifact
     across subjects/requests, an injection surface, a secret or internal detail reaching logs/error
     bodies) and state why it cannot happen».**
   - **«Concurrency / idempotency check — every decision that gates a mutation is computed under the same
     lock or version guard that holds through the commit (`CONCURRENCY-AND-LOCKING.md` Rules 1–2 — code
     that locks first but acts on a pre-lock decision is the defect); a gate-time snapshot is
     version-guarded in the mutating transaction; a retried/replayed request converges».**
   - **Wiring check** — every seam this ticket adds (an SPI, a property, a guard, an exception + advice
     mapping, a cache accessor, a rego entrypoint, a recovery edge) has a **named consumer** and a test
     through its **non-happy path**; zero call sites = the ticket is not done.
   - **«Boundary / additivity check — `opa-abac-core` stays Spring-free; the change is additive to the
     public API; name the byte-for-byte-unchanged surfaces and the one mechanical cost (e.g. widened
     test stubs)».**
   - **«Module-layer separation — which logic lives in which module; no layer reaches across».**
   - **«Pattern-reuse check — the named shipped patterns this must match, not reinvent».**
   - **SOLID / decomposition** — cohesive (SRP), depends on interfaces (DIP); anything to split/simplify?
   - **Apply** the refactoring the review surfaces, then **re-run the unit tests** to confirm green.
   - Write a short note of what the review found + what you refactored into `STATUS-0N.md`. If it found
     nothing substantive, say so explicitly — **do not invent churn.**

6. **Integration / e2e validation (MANDATORY for the relevant tickets).**
   - «Ticket X / Y: Testcontainers IT against real Postgres asserting <the row set / invariant>; `opa
     test` for the policy. Fix-until-green.»
   - «e2e ticket: bring the rig up (`ENABLE_OIDC=1 «…» ./deploy.sh up --pods 2`), then `cd
     scripts/postman && ./run-«matrix».sh`. Honor the in-network token caveat and restart OPA after a
     rego edit. Fix-until-green.»

7. **Update documentation (after each ticket).** Tick the ticket in the `«SLICE».md` status table;
   record real values/decisions in `STATUS-0N.md`. The ticket that finalizes a guide topic
   writes/reconciles «`docs/guides/…`». Root/project `CLAUDE.md` only if a new build/run step matters.

8. **Mulch expertise check (after each ticket).** `ml prime`/skim, then record any genuine reusable
   insight (`ml record opa-abac --type <pattern|decision|failure|reference> …`) and `ml sync`. **Before
   `ml sync`, `git restore --staged .`** so the sync commit touches `.mulch/` **only** (the
   swept-staged trap). Skip recording only if nothing is non-obvious.

9. **Commit** — one focused commit on this branch (code + tests + docs + the `STATUS-0N.md` note
   together). Identity `Void3110 <void31102025@gmail.com>`. Conventional subject «`feat(«scope»): …`».
   A `Co-Authored-By: Claude` trailer is welcome.

10. **CHECKPOINT — STOP and report.** Summarize what shipped, paste the unit + integration/e2e summary,
    **summarize the review findings + the refactoring you applied** (step 5), list docs updated, and
    note any open question you resolved. Then proceed to the next ticket. **Do not batch tickets
    without a checkpoint.**

### Permissions / autonomy granted (do these WITHOUT asking)

- Create/modify «library code, example code, rego, tests, docs in this folder + the guides, the
  `scripts/postman/` suite», and Mulch — all on this branch.
- Stand up / tear down / reseed the local rig («`./profile.sh`, `./deploy.sh`, `ENABLE_OIDC=1 …`»);
  reset fixtures; rebuild images; restart OPA; drop/recreate the **local** schema if needed.
- Fix any issue your own validation reveals (compile, unit, IT, e2e, refactor). Iterate until green.
- Commit per ticket on this branch.

### Hard rules

- **Report at every checkpoint and continue.** Don't batch tickets.
- **The architecture-review + refactor step (5) is NOT optional and happens BEFORE integration/e2e
  validation** — unit green → review → refactor → re-test → then ITs/e2e. Document what it found.
- **Fix-until-green within the ticket.** Only STOP mid-ticket if genuinely *blocked*: the same root
  cause survives ≥3 focused attempts, OR a design decision the docs don't cover is needed, OR a local
  prerequisite is unrecoverable.
- **«Fail-closed is the load-bearing invariant — restate it for this slice in one sentence: no path
  returns more/wider results on an error than on success».**
- **«Slice-specific invariants — e.g. AND-don't-replace; additive-only library change; match-in-Rego;
  flat-verb-only. List every one the agent must never trade away».**
- **Clean-room IP boundary** — never introduce proprietary names, package names, comments, or source.
- **`opa-abac-core` stays Spring-free** «if the slice touches core».
- **`ddl-auto: validate` must pass** «if the slice touches schema — a clean boot is the proof».
- **Do NOT push, open PRs, or touch `main`.** Local + this branch only. The maintainer pushes.

---

## Operator notes (not part of the prompt)

- **«The headline tickets»** — name the 2–3 tickets whose passing tests justify the whole design.
- **«The fail-closed edge to eyeball»** — where this slice would silently leak if done carelessly.
- **«Standalone-value subset»** — which early tickets deliver reusable value if the window is short.
- **«Rig / e2e specifics»** — in-network token, OPA restart, two-services, etc.
- **CI does not run the rig yet** — the newman matrix is a local/manual gate; a compose-up→newman CI
  job is a tracked follow-up.
- **Context management** — if the window grows long mid-run, finish the ticket, stop at its checkpoint,
  and resume in a **fresh session** (the ticket status table + STATUS notes are the handoff); sub-agents
  are for scouting/validation only, never the implementation.
- **Workflow-as-artifact:** keep this prompt verbatim; the `STATUS-0N.md` notes record each ticket's
  outcome. Move the folder to `docs/to-do/implemented/` on ship.
````

---

## 5. The invariant skeleton (what never changes)

These eleven elements appear, near-identically, in all five shipped prompts. They are the spine — keep
them; only the bracketed content varies per slice.

1. **Front matter + the "Before you run it" branch/identity preamble** — `git checkout -b
   feature/void3110/<slice>`, verify `git config --local user.email`, then paste the **PROMPT**.
2. **"You are implementing … on branch …"** + a **The problem** paragraph that names the gap, the
   mechanism, the headline, and the explicit scope boundary (what's deferred).
3. **"Implement the core work directly. Do not delegate the implementation to a sub-agent."** — with
   the standing carve-out (see §7): sub-agents may *scout* (read-only fan-outs) and *validate*
   (log-noisy runs, failure-summary back); they never write the code.
4. **Read-before-you-start, in order** — index, design, decomposition, the pinned ADR(s), QA cases, the
   patterns-you're-checked-against, root `CLAUDE.md` (IP boundary + commit identity), `infra/README.md`,
   `ml prime opa-abac`.
5. **The per-ticket loop, T1→TN, in order, STOP at each checkpoint** — the ten numbered steps:
   prime → build → test → **★review+refactor** → IT/e2e → docs → Mulch → commit → checkpoint.
6. **The ★ architecture-review gate sits BEFORE integration/e2e** — unit-green → review → refactor →
   re-test → *then* the heavier validation. Always with a fail-closed check, a **security check**, a
   **concurrency/idempotency check** (the decide-under-protection invariant, not just lock mechanics),
   a **wiring check** (every new seam has a consumer + a non-happy-path test), a boundary/additivity
   check, a pattern-reuse check, SOLID, "apply real refactoring, not ritual churn," and a written
   `STATUS-0N.md` note. *(Security + concurrency/idempotency elevated 2026-06-12 — see §7.)*
7. **Permissions / autonomy granted** — the explicit list of things to do *without asking* (edit code,
   stand up the rig, fix-until-green, commit per ticket).
8. **Hard rules** — report-at-checkpoints; the review gate is mandatory and ordered; fix-until-green
   with a ≥3-attempts blocked-exit; clean-room; core-stays-Spring-free; `ddl-auto: validate`;
   **do NOT push / PR / touch main**.
9. **The fail-closed invariant, stated as load-bearing** — every slice has a one-sentence version of
   "no error path widens the result."
10. **One focused commit per ticket**, identity `Void3110 <void31102025@gmail.com>`, conventional
    subject, `Co-Authored-By: Claude` welcome; **Mulch sync touches `.mulch/` only** (`git restore
    --staged .` first).
11. **Operator notes** (outside the prompt) — headline tickets, the fail-closed edge to eyeball,
    standalone-value subset, rig specifics, CI-doesn't-run-the-rig, workflow-as-artifact.

---

## 6. The per-slice slots (what you fill in)

| Slot | What it is | Drawn from |
|------|-----------|-----------|
| **Slice name + branch** | `feature/void3110/<slice>` | the planning folder name |
| **The problem** | gap → mechanism → headline → scope boundary | `00-DESIGN.md` |
| **Ticket count + order** | T1…TN, the critical path, any parallel/optional landings | `01-DECOMPOSITION.md` |
| **Packages** | `dev.dmitriikonovalov.opaabac.{core,security,data,autoconfigure}` / `…example.{catalog,usermgmt}.*` | the module the slice touches |
| **Pinned ADR(s)** | the decision(s) this slice implements or is bound by | `architecture/adr/` |
| **Patterns checked against** | the named shipped slices/guides whose idioms must be reused | prior `implemented/` slices |
| **Mulch record ids** | the directly-relevant `mx-…` to prime | `ml search` for the surface |
| **Fail-closed sentence** | the slice's concrete "no error widens the result" | `00-DESIGN.md` |
| **Slice-specific invariants** | AND-don't-replace · additive-only · match-in-Rego · flat-verb-only · `role ≠ grant` · etc. | the hard rules that matter here |
| **IT / e2e commands** | the exact `:module:test`, `deploy.sh` flags, and `run-*.sh` matrix | the e2e ticket |
| **Guides authored/reconciled** | the `docs/guides/*` the final ticket writes | the slice's docs deliverable |
| **Headline tickets / fail-closed edge** | the operator-note specifics | the design's risk surface |

---

## 7. Lessons baked in (why the skeleton looks the way it does)

Each run added a hard rule that is now permanent. When you write the next prompt, these are *already*
in the skeleton — this section is the rationale so you don't quietly drop one.

### 7.0 Prompt-language principles (how to phrase the skeleton)

These come from the prompt-engineering research distilled in Jaymin West's *Agentic Engineering Book*
(the "Prompt" chapter) — applied to *how* we word the autonomous prompt, not *what* it says. They are
guidelines, not a rewrite of the hardened skeleton; follow them when you fill the slots and when the
skeleton itself is next revised.

- **Positive constraints over negative, where a positive form exists ("pink-elephant" effect).** Research
  (InstructGPT / 16x.engineer) shows `NEVER do X` *backfires in long contexts* — naming the forbidden
  action semantically activates it, so over a long run the agent drifts *toward* it. Prefer the positive
  reframe: **"`opa-abac-core` stays Spring-free"** beats "NEVER import Spring into core"; **"residual is
  AND-ed with existing scope"** beats "never replace the scope". The autonomous run is *exactly* the
  long-context scenario this finding targets.
- **Reserve hard NEVER for true, non-negotiable boundaries.** Some rules have no safe positive form and
  must stay imperative-negative — **"Do NOT push, open PRs, or touch `main`"**, **"clean-room: no
  proprietary names"**. Keep these as `NEVER`; the discipline is to use that register *only* where a
  violation is unrecoverable, so it keeps its weight. Don't dilute it across stylistic preferences.
- **Critical constraints first (ordering matters).** The model weights earlier content more heavily, so
  the hard-rules block leads with the load-bearing ones (fail-closed, push-nothing, clean-room) before
  the slice-specific and stylistic ones.
- **Declarative for goals, imperative for steps (~23% reasoning gain, SatLM arxiv:2305.09656).** Frame
  *"The problem"* and the fail-closed invariant as **states the result must satisfy** ("no path returns a
  wider result on error than on success"), and keep the per-ticket loop as **imperative steps** ("prime →
  build → test → review"). Declarative goals encourage the model to build an internal model of the target
  state; imperative steps drive tool-calling.
- **Specificity where there's an objective right answer; flexibility elsewhere (DETAIL framework).** Be
  exact on output/format, the boundary, success criteria, and the exact `:module:test` / `run-*.sh` that
  proves a ticket (code-gen specificity gains are real); leave *implementation approach* free unless a
  shipped pattern must be matched. Over-specifying edge cases makes the prompt brittle — give core
  principles + the canonical example (e.g. "DATA-FILTERING T1 is the build-breaker model"), not 47 cases.

> **Net effect on the skeleton:** most of our hard rules already read as positive constraints; the ones
> that don't (the push/clean-room boundaries) are precisely the ones that *should* stay `NEVER`. So this
> is mostly a phrasing discipline to preserve, plus the critical-first ordering — not a structural change.

- **The ★ review gate goes BEFORE IT/e2e, not after.** Cheap self-review (fail-closed, boundaries,
  pattern-reuse, SOLID) and real refactoring *before* the expensive validation catches structural
  mistakes while they're still cheap to fix. Documented per ticket so it can't become ritual.
- **Fail-closed is stated as *the load-bearing invariant*, concretely per slice.** Generic "be safe"
  is uselessly vague. DATA-FILTERING's version — "no code path may return more rows on an error than on
  success; compile/parse failure → `DENY_ALL`; the `filter` rule has **no** subject-roles fallback" —
  is the model: name the exact failure modes and where each one lands.
- **AND, don't replace.** A residual/scope predicate is **AND-ed with** existing path scoping, never
  swapped for a bare `findAll(residual)` — else cross-scope rows leak. (DATA-FILTERING.) The general
  lesson: when adding a filter to an already-scoped query, prove you *narrowed*, never *widened*.
- **Additive-only library changes are called out and proven.** TAG-DICTIONARY's only library change
  (`RoleDefinition.requiredTags`) had to serialize byte-for-byte as before when absent, with every old
  test green. The hard rule: *"if you think you need a non-additive change, STOP and report."*
- **The Mulch swept-staged trap.** `ml sync` commits must touch `.mulch/` **only** — `git restore
  --staged .` before `ml sync`, or the sync commit sweeps in unrelated staged code (`mx-d8a173`).
- **Sub-agents scout and validate; the implementer implements.** Across the shipped runs the recorded
  failure modes were unpinned design semantics and rig gotchas — never context exhaustion — while
  **cross-ticket continuity** (T1's micro-decisions silently shaping T3/T4) is what keeps a run
  coherent; a fresh sub-agent per ticket re-derives that from docs alone and drifts at every seam, at
  roughly the same token cost (it must re-read the whole package + re-prime Mulch each time). So the
  core implementation stays in the main loop. Delegate **read-only scouting** (a fan-out like "which
  collections assert list bodies") and **log-noisy validation** (run the matrix, return only the
  failure summary). If the window genuinely grows long, prefer **stopping at a checkpoint and resuming
  in a fresh session** — the ticket status table + the `STATUS-0N.md` notes are a complete handoff (the
  method is doc-first precisely so the window is cache, not the source of truth) — over delegating
  implementation mid-ticket. (Decided 2026-06-11, planning Phase 5.95.)
- **Write invariants, not mechanisms (root-cause audit of the source platform's review stack, 2026-06).**
  A strong implementer behind a multi-gate review still shipped a cluster of race/recovery defects there;
  the audit found one root cause across guides, prompts, and review skills: every concurrency rule
  encoded the *mechanical fix of one past incident* ("lock first", "re-fetch fresh inside the tx"), not
  the *invariant that generates the defect class* — and **checklist-shaped rules get satisfied literally
  by defective code** (the code locked first, then acted on decisions made on unlocked state; the rule's
  own wording blessed the bug). What this repo bakes in as a consequence: the ★ gate and the review
  lenses state the **generating invariant** with mechanisms as named instances
  (`CONCURRENCY-AND-LOCKING.md` Rules — decide under the protection you act under; bind the decision
  version to the acted-on version); **security + concurrency/idempotency run as first-class review
  dimensions** on every non-docs diff, not as subsets of fail-closed; completeness rules must not be
  happy-path-framed — declared **recovery/secondary paths and off-states are first-class links to
  trace**, and a **seam with zero callers is not done** (the wiring check, §3); and a review fix in one
  handler is **swept across its mirrored siblings in the same commit**, because adversarial refutation
  can only narrow a finding set — widening is the completeness critic's job.
- **Match-in-Rego, decisions in the policy.** ANY_OF/ALL_OF via `some in` / `every` lives in Rego, not
  Java — it's the OPA-native expression and the bridge to later in-policy joins. The slice-invariant
  pattern: keep the *decision* in the policy, the *plumbing* in Java.
- **The headline pair is a test + an e2e contrast.** Every slice's proof is a passing Testcontainers
  IT (the mechanism works against real Postgres) **plus** an e2e matrix showing the decisive contrast
  (two subjects, same endpoint, different outcome). Make the e2e assert the *cut* (row counts /
  allow-vs-deny), not just response shape.
- **Rig truths that read like bugs but aren't:** APISIX validates the issuer as `keycloak:8888`, so a
  host-minted token is rejected — mint in-network; OPA `--watch` doesn't always reload — restart it
  after a rego edit; `deploy.sh` only rebuilds the image if it's absent — `./deploy.sh build` to force
  new code in. Name the ones the slice will hit in the operator notes.

### When a mid-implementation decision is genuinely the maintainer's

The loop is autonomous, but a fork the docs don't cover — and that changes externally-visible behavior
— is a **stop-and-ask**, not a guess. Precedent: DATA-FILTERING T6 paused to ask how the list filter
should treat array-valued tags (the answer — "CONTAINS via the `?` operator, full consistency with the
single-GET allow" — was load-bearing for correctness). Rule of thumb: if getting it wrong is a silent
*fail-open* or a contract change, ask; if it's an internal implementation detail with a sane default,
decide and record it in `STATUS-0N.md`. The `grill-me` skill is the planning-time version of this —
resolve the forks *before* the prompt is written so the run has fewer reasons to stop.

---

## 8. The tooling & skills stack (what powers each phase)

The flow above is a *method*; this section names the **tools** that make it repeatable, what each one
does, **who built it**, and — for the agentic ones — **which published orchestration pattern it is an
instance of**. The patterns are Anthropic's own (the *dynamic-workflows* feature + the "harness for
every task" thesis); naming the mapping is deliberate, so the process is recognizable to anyone who
knows that material rather than looking like bespoke ceremony.

> **This section is seeded, not finished.** It captures the stack as used through the slices shipped so
> far. Each future slice is expected to *refine* it — most concretely, to generalize the `deep-review`
> skill from its still-somewhat-project-specific form toward the portable template in
> [`docs/code-review/DEEP-REVIEW-TEMPLATE.md`](../code-review/DEEP-REVIEW-TEMPLATE.md). Treat the
> entries below as living.

### The stack at a glance

| Tool | What it is | Used in phase | Upstream | Pattern it instantiates |
|------|-----------|---------------|----------|--------------------------|
| **Mulch** (`ml`) | A CLI expertise store — durable team knowledge (patterns, decisions, failures) recorded per project in `.mulch/`, primed back into the agent before a task. | All phases (prime before, record after) | **Jaymin West** — [`@os-eco/mulch-cli`](https://github.com/jayminwest/mulch) (MIT). Installed globally, store is per-repo. | *Externalized memory* — the durable counter to **goal drift**: invariants live in a store the agent re-reads, not in a degrading context window. |
| **LSP code intelligence** (`jdtls`) | Eclipse JDT language server, exposed as the agent's `LSP` tool: real Java symbol resolution — `goToDefinition`, `findReferences`, `goToImplementation`, `documentSymbol`/`workspaceSymbol`, call hierarchy. *Symbol-accurate*, not text-grep. | All phases (precise navigation: scope a change in ①/②, trace blast-radius in ④) | **Anthropic** — the `jdtls-lsp` Claude Code plugin (+ `pyright-lsp` for Python). | *Ground-truth structural index* — answers "who calls this / what implements this" from the compiler's model, where ripgrep can only guess. The Java-native code intelligence layer. |
| **grill-me** | A skill that interviews the maintainer one question at a time, walking each branch of the design tree and recommending an answer, until every fork is resolved. | ① Planning | **Matt Pocock** — [`mattpocock/skills`](https://github.com/mattpocock/skills) (`productivity/grill-me`). | *Evaluator-driven elicitation* — front-loads decisions into ADRs **before** the autonomous run, so the run has fewer reasons to stop (the planning-time form of "stop and ask"). |
| **decompose** (formerly `slice-planner`) | A skill that turns a *settled* design (`00-DESIGN` + ADRs + user stories) into the rest of the planning package: the ordered tickets, QA cases, the verbatim §4 autonomous prompt, and STATUS stubs. Refuses to do phase-① work — if the design inputs are missing it stops and routes back to planning. Portable form: [`DECOMPOSE-SKILL-TEMPLATE.md`](DECOMPOSE-SKILL-TEMPLATE.md). | ② Decomposition | This repo's own skill (local, in `.claude/skills/` — **gitignored**). | *Deterministic template instantiation* — it is the **automation for §3–§4 of this very guide**; the guide is the single source of truth and the skill defers to it ("when they disagree, the guide wins"). Counters **goal drift** at the planning seam by keeping the prompt skeleton verbatim. |
| **deep-review** (`/deep-review`) | A full-lifecycle review skill: scope the diff → multi-lens analysis → adversarially refute each finding → fix → build + e2e → review note → commit. | ④ Review / ship | This repo's own skill (local, in `.claude/skills/` — **gitignored**); generalized in [`DEEP-REVIEW-TEMPLATE.md`](../code-review/DEEP-REVIEW-TEMPLATE.md). | **Fan-out → adversarial-verification → completeness-critic** — three of Anthropic's named harness shapes composed in one workflow (`deep-review-workflow.js`). |
| **Claude Code dynamic workflows** | The runtime that executes a JS orchestration script of many subagents in the background; the deep-review skill's heavy path (2B) *is* such a workflow. | ④ (the heavy review path) | **Anthropic** — [official docs](https://code.claude.com/docs/en/workflows) + the "[a harness for every task](https://claude.com/blog/a-harness-for-every-task-dynamic-workflows-in-claude-code)" blog. | The substrate the patterns run on — see the [vault distillation](#related) of the feature. |

### Why these three, mapped to the three failure modes

Anthropic's harness thesis names three failure modes that long, autonomous agent runs degrade into.
This flow's tooling is chosen to counter each — that's the *why* behind the stack, not just the *what*:

| Failure mode (Anthropic) | How it shows up in an autonomous slice run | The tool/discipline that counters it |
|--------------------------|---------------------------------------------|--------------------------------------|
| **Goal drift** — constraints erode as context summaries lose fidelity | A load-bearing invariant (fail-closed, additive-only, core-stays-Spring-free) quietly weakens across tickets | **Mulch** (invariants re-primed from a store) + the **per-ticket checkpoint** + the prompt's **hard rules** restated every slice |
| **Agentic laziness** — declares success on partial work | A ticket's acceptance only *shape*-asserted (200 vs 403) not the actual *cut* (row counts); 35 of 50 items done | The **headline pair** discipline (a real Testcontainers IT + an e2e that asserts the cut) + **deep-review**'s autonomous-run lens |
| **Self-preferential bias** — over-grades its own output against a rubric | A `STATUS-0N.md` "review found nothing" where the diff says otherwise; ritual refactor notes | **deep-review's adversarial verification** — *separate* skeptic agents try to refute each finding before it survives; they have no stake in the original work |

### The deep-review skill ↔ Anthropic patterns, concretely

The review skill is the clearest worked example of "assemble published patterns into a task-specific
harness." Its heavy path (`deep-review-workflow.js`) composes:

1. **Fan-out** — one *failure-mode specialist lens* per relevant dimension runs in parallel
   (fail-closed/authorization, core-boundary/additivity, rego/policy, persistence/concurrency,
   API/OpenAPI contract, infra/e2e). Each lens is blind to the others — diversity catches what a single
   pass misses.
2. **Adversarial verification** — every candidate finding is handed to a *separate skeptic* prompted to
   **refute** it; it survives only if re-confirmed from source. This is the direct counter to
   self-preferential bias and to plausible-but-wrong findings.
3. **Completeness critic** — a dedicated *widening* pass after refutation: it hunts what no lens
   reported (an unswept sibling of fixed code, a new seam with zero callers, an untested
   off-state/recovery path, a QA case with no test), and its candidates face the same refutation; only
   then are survivors deduped and severity-sorted for the maintainer. Refutation can only narrow a
   finding set — the critic is the counterweight.

The light path (single sub-agent, for small/low-risk diffs) is the same shape collapsed to one agent —
"start simple, scale intelligently": don't spin up a workflow to review a 20-line docs change. The size/
risk routing *is* Anthropic's "match architectural complexity to value" decision applied per review.

> **Skills vs. Workflows** (the distinction worth keeping straight, and worth teaching): a **skill** is
> *knowledge the agent follows*; a **workflow** is *orchestration the runtime executes*. `grill-me` and
> `decompose` are pure skills (phases ① and ②). `deep-review` is a skill that, on a large/high-risk
> diff, *reaches for* a workflow (phase ④). Mulch is neither — it's the external store all three lean on.

### Code intelligence: LSP over text-grep (and why not a code-DB here)

The `LSP` tool (Eclipse `jdtls`) is the **standing structural index** under every phase — it answers
*who calls this method, what implements this interface, where is this symbol defined* from the Java
compiler's own model, not a regex guess. Where it earns its keep in this flow:

- **Planning / decomposition (①/②)** — `workspaceSymbol` + `findReferences` to scope what a slice
  actually touches (which callers of `OpaClient` / `AbacContext` a change ripples to), so the ticket
  boundaries and "What-NOT-to-touch" lists are grounded in the real call graph.
- **Review (④)** — `findReferences` / `incomingCalls` to trace a finding's blast radius, and
  `goToImplementation` to confirm an SPI's every impl was considered (e.g. both `AncestorResolver`
  implementations). Symbol-accurate beats grep when verifying "did this change every call site."

> **Why a code-intelligence *database* (KotaDB et al.) is **not** in this stack.** We evaluated KotaDB
> (Jaymin West) as a pre-review/planning index. Its structural parsing is **JS/TS-only**
> (`@typescript-eslint/parser`); on a Java/Kotlin/Rego repo it degrades to SQLite FTS5 full-text search —
> no symbol graph, so its `analyze_change_impact` / `search_dependencies` have nothing structural to work
> with here. For **this** repo the LSP server *is* the code-intelligence layer; a JS/TS project would be
> a different calculus. (The fuller analysis is a private research note, not part of the public repo.)

### Credit & reuse

This process stands on others' work, and says so on purpose — both because it's right and because the
honesty is part of the consulting/education story (you can't teach a method while hiding its sources).
Two of the four tools are **upstream** (others' work we adopt); two are **this repo's own** skills built
*on top of* that work and this guide:

**Upstream (credit):**
- **Mulch** © Jaymin West — the expertise-store habit (`ml prime` before, `ml record`/`ml sync` after)
  is the single highest-leverage discipline here; it's what makes "the agent already knows this repo's
  hard-won lessons" true rather than aspirational.
- **grill-me** © Matt Pocock — the "interview relentlessly until shared understanding" framing is
  exactly what a planning phase needs to produce immutable ADRs.
- **Dynamic workflows / the harness thesis** © Anthropic — the vocabulary (fan-out, adversarial verify,
  loop-until-dry, completeness critic) and the three-failure-mode framing this whole flow is built
  around. The deep-review workflow is our application of it, not an invention of it.
- **LSP plugins** (`jdtls-lsp`, `pyright-lsp`) © Anthropic — the Claude Code plugins that surface the
  Eclipse JDT / Pyright language servers as the agent's `LSP` tool.

**Ours (this repo's local skills, gitignored in `.claude/skills/`):**
- **decompose** (formerly `slice-planner`) — the phase-② automation that instantiates §3–§4 of this
  guide; the guide stays the single source of truth, the skill is its checklist + scaffolding. Portable
  form: [`DECOMPOSE-SKILL-TEMPLATE.md`](DECOMPOSE-SKILL-TEMPLATE.md).
- **deep-review** — the phase-④ review harness; its portable form is
  [`DEEP-REVIEW-TEMPLATE.md`](../code-review/DEEP-REVIEW-TEMPLATE.md). Built by composing the Anthropic
  patterns above, tuned to this repo's invariants.
- **autonomous-implement** (template only, for now) — the phase-③ runner discipline as a skill wrapper,
  for repos that outgrow pasting the bare §4 prompt. Portable form:
  [`AUTONOMOUS-IMPLEMENT-SKILL-TEMPLATE.md`](AUTONOMOUS-IMPLEMENT-SKILL-TEMPLATE.md).

A reader who wants to adopt the *review* half of this flow in their own project should start from
[`docs/code-review/DEEP-REVIEW-TEMPLATE.md`](../code-review/DEEP-REVIEW-TEMPLATE.md) — a vendor-neutral
version with the project-specific parts marked as fill-in slots.

### Where each prompt sits on the maturity model

A useful lens (from the *Agentic Engineering Book*'s 7-level prompt-maturity model) — it explains why the
pieces are split the way they are, and where each could evolve:

| Artifact | Level | Why |
|----------|-------|-----|
| `AUTONOMOUS-IMPLEMENTATION-PROMPT.md` | **L4 — Contextual** | A self-contained one-shot prompt that reads external files (the design, ADRs, QA cases, `CLAUDE.md`) and primes Mulch. Everything-upfront, no follow-up assumed. |
| **deep-review** | **L5 — Composed** | Invokes other operations — it reaches for a *workflow* (sub-agents) on large diffs and runs `/rego-skill` on policies. Orchestration that coordinates specialists. |
| **decompose** | **L6 — Template metaprompt** | It *generates a new prompt* (the next slice's `AUTONOMOUS-IMPLEMENTATION-PROMPT.md`) from a settled design — a prompt that writes prompts, against the §4 template. |

**The L6→L7 principle we already follow: separate *Expertise* from *Workflow*; only Expertise updates.**
The book's rule for self-improving prompts is that the operational *workflow* stays stable while the
*expertise* grows. We implement that split across two stores: the **prompt skeleton (§5) is the stable
Workflow** — kept verbatim across runs — and **Mulch is the growing Expertise**, re-primed each run. That
is why "keep the skeleton word-for-word; record learnings to Mulch" is a hard rule and not a stylistic
preference: it *is* the Expertise/Workflow separation, mapped onto our two-store setup. (A true L7
meta-cognitive step — a prompt that rewrites *other* prompts in the system — we deliberately don't
automate; revising the skeleton is a human, ADR-worthy decision.)

---

## Related

**Phase ① Planning:**
- `docs/architecture/adr/README.md` — the ADR format + the "ADRs are part of the decomposition process,
  written up front" convention this flow depends on.
- [[USER-STORIES]] — the product-lens deliverable; each story phase-tagged.
- The `grill-me` skill — the fork-resolving interview that ends the planning conversation.

**Phase ② Decomposition & ③ Autonomous implementation:**
- `docs/README.md` → "`to-do/` lifecycle" and "Decisions vs. designs" — the folder mechanics + the
  ADR-vs-design split this flow assumes.
- [[POC-ROADMAP]] — the phase plan each slice implements one piece of.
- The shipped packages to copy from: [[DOMAIN-MODEL-FOUNDATION]], [[LIBRARY-SPINE]],
  [[USER-MANAGEMENT-SERVICE]], [[TAG-DICTIONARY]], [[DATA-FILTERING]], [[HIERARCHY-SINGLE-RESOURCE]],
  [[HIERARCHY-LIST-FILTER]], [[REST-API-REFINEMENT]] (each folder's
  `01-DECOMPOSITION.md` + `AUTONOMOUS-IMPLEMENTATION-PROMPT.md`).

**Phase ④ Review / ship:**
- `docs/code-review/CODE-REVIEW-WORKFLOW.md` — the `/deep-review` process that runs after the
  autonomous run, before push/PR/merge.
- `docs/code-review/DEEP-REVIEW-TEMPLATE.md` — the vendor-neutral, adaptable version of the review
  harness (for adopting this flow in another project).

**The tooling (§8) — upstream:**
- **Mulch** — [`github.com/jayminwest/mulch`](https://github.com/jayminwest/mulch) (Jaymin West).
- **grill-me** — [`github.com/mattpocock/skills`](https://github.com/mattpocock/skills) (Matt Pocock).
- **Claude Code dynamic workflows** — [official docs](https://code.claude.com/docs/en/workflows) and
  the "[a harness for every task](https://claude.com/blog/a-harness-for-every-task-dynamic-workflows-in-claude-code)"
  blog (Anthropic) — the orchestration patterns and the three-failure-mode framing §8 maps onto.
