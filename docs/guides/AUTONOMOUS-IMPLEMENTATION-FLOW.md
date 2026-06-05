---
tags:
  - status/active
  - type/guide
  - area/docs
  - area/build
---

# Autonomous implementation flow

The repeatable **plan → autonomous-implement → test → review** process behind every shipped slice in
this repo ([[DOMAIN-MODEL-FOUNDATION]], [[LIBRARY-SPINE]], [[USER-MANAGEMENT-SERVICE]],
[[TAG-DICTIONARY]], [[DATA-FILTERING]]). It is also a **first-class portfolio artifact**: each slice's
`AUTONOMOUS-IMPLEMENTATION-PROMPT.md` is kept verbatim and its `STATUS-0N.md` notes record the outcome,
so the prompt + results form a studyable case study of high-autonomy AI-assisted engineering.

This guide is the **template + checklist** for producing the next one. It distills the invariant
skeleton common to all five shipped prompts, marks the per-slice fill-in slots, and folds in the
lessons each run added. When you start a new slice, copy the skeleton in
[§3](#3-the-autonomous-implementation-promptmd-template), fill the slots, and you have a self-contained
prompt a fresh agent can execute.

> **Why this shape.** A doc-first plan turned into a *self-contained, checkpoint-gated, fail-closed*
> prompt lets one agent implement a multi-ticket slice end to end at high autonomy without drifting —
> because the architecture-review gate and the per-ticket checkpoint catch mistakes before they
> compound, and the hard rules pin the load-bearing invariants the agent must never trade away.

---

## 1. The lifecycle (one slice, start to finish)

```
        ┌─────────────────────────────────────────────────────────────┐
        │ PLAN (interactive, with the maintainer)                      │
        │  • scope the slice; resolve forks (grill-me) → confirm        │
        │  • write the planning package (§2) + any up-front ADR         │
        │  • this is a docs-only turn — NOT the implementation          │
        └───────────────────────────┬─────────────────────────────────┘
                                     │ maintainer reviews the package
                                     ▼
        ┌─────────────────────────────────────────────────────────────┐
        │ AUTONOMOUS IMPLEMENT (one agent, the §3 prompt)               │
        │  branch feature/void3110/<slice>                              │
        │  per-ticket loop T1…TN, IN ORDER, STOP at each checkpoint:    │
        │    prime → build → test → ★review+refactor → IT/e2e → docs    │
        │           → Mulch → one commit → CHECKPOINT & report          │
        └───────────────────────────┬─────────────────────────────────┘
                                     │ maintainer reads checkpoints
                                     ▼
        ┌─────────────────────────────────────────────────────────────┐
        │ REVIEW / SHIP (maintainer-driven)                            │
        │  • /deep-review the branch  • push  • PR  • CI green  • merge │
        │  • git mv the folder planning/ → implemented/ + Shipped banner│
        └─────────────────────────────────────────────────────────────┘
```

**Division of labour (a hard line, carried in every prompt):** the agent does **everything local on the
branch** — code, tests, docs, Mulch, one commit per ticket. The **maintainer pushes, opens the PR, and
merges.** The prompt must say *"Do NOT push, open PRs, or touch `main`."* Pushing is a separate,
explicit "let's push and create pr and then merge" turn.

---

## 2. The planning package (produced in the PLAN turn)

Every slice gets a folder `docs/to-do/planning/<SLICE>/` that is a **1:1 structural mirror** of every
prior slice. The package is the *input* the autonomous prompt reads; the prompt is one file inside it.

| File | Role |
|------|------|
| `<SLICE>.md` | `type/index` — what the slice delivers, file glossary, **ticket status table**, critical path, conventions (clean-room + commit identity). |
| `00-DESIGN.md` | `type/architecture` — the design, the fail-closed posture, and a **considered-&-rejected** list. |
| `01-DECOMPOSITION.md` | `type/project` — the ordered tickets **T1…TN**, each with **Goal / Deliverables / Acceptance / What-NOT-to-touch**, + a cross-cutting acceptance block. **The work list.** |
| `AUTONOMOUS-IMPLEMENTATION-PROMPT.md` | The self-contained prompt (the §3 template). **Kept verbatim — a deliverable, not scaffolding.** |
| `10-QA-TEST-CASES.md` | `type/project` — concrete U*/I*/E* cases the implementation must satisfy. |
| `STATUS-01.md … STATUS-0N.md` | One stub per ticket, filled at each checkpoint: *What shipped · Tests · Architecture review + refactor · Integration/e2e · Decisions · Commit.* |

**Conventions for every note:** valid frontmatter (one `status/`, one `type/`, ≥1 `area/`),
`UPPER-KEBAB-CASE.md`, `[[wikilinks]]`, links back to `[[POC-ROADMAP]]`. A **structural decision** taken
while planning (an authority shape, a module boundary, a match-here-not-there, an additive-vs-breaking
fork) does **not** live in the decomposition — it gets its own dated **ADR** in `architecture/adr/` and
the design links it. (See `docs/README.md` → "Decisions vs. designs".)

On ship, `git mv` the folder to `docs/to-do/implemented/<SLICE>/`, flip `status/*` → `status/done` in
the index frontmatter, and add a past-tense **Shipped** banner — keeping the prompt-and-results record
intact alongside the prior slices for cross-run comparison.

---

## 3. The `AUTONOMOUS-IMPLEMENTATION-PROMPT.md` template

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

Implement the core work directly. Do not delegate the implementation to a sub-agent.

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
- **Workflow-as-artifact:** keep this prompt verbatim; the `STATUS-0N.md` notes record each ticket's
  outcome. Move the folder to `docs/to-do/implemented/` on ship.
````

---

## 4. The invariant skeleton (what never changes)

These eleven elements appear, near-identically, in all five shipped prompts. They are the spine — keep
them; only the bracketed content varies per slice.

1. **Front matter + the "Before you run it" branch/identity preamble** — `git checkout -b
   feature/void3110/<slice>`, verify `git config --local user.email`, then paste the **PROMPT**.
2. **"You are implementing … on branch …"** + a **The problem** paragraph that names the gap, the
   mechanism, the headline, and the explicit scope boundary (what's deferred).
3. **"Implement the core work directly. Do not delegate the implementation to a sub-agent."**
4. **Read-before-you-start, in order** — index, design, decomposition, the pinned ADR(s), QA cases, the
   patterns-you're-checked-against, root `CLAUDE.md` (IP boundary + commit identity), `infra/README.md`,
   `ml prime opa-abac`.
5. **The per-ticket loop, T1→TN, in order, STOP at each checkpoint** — the ten numbered steps:
   prime → build → test → **★review+refactor** → IT/e2e → docs → Mulch → commit → checkpoint.
6. **The ★ architecture-review gate sits BEFORE integration/e2e** — unit-green → review → refactor →
   re-test → *then* the heavier validation. Always with a fail-closed check, a boundary/additivity
   check, a pattern-reuse check, SOLID, "apply real refactoring, not ritual churn," and a written
   `STATUS-0N.md` note.
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

## 5. The per-slice slots (what you fill in)

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

## 6. Lessons baked in (why the skeleton looks the way it does)

Each run added a hard rule that is now permanent. When you write the next prompt, these are *already*
in the skeleton — this section is the rationale so you don't quietly drop one.

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

## Related

- `docs/README.md` → "`to-do/` lifecycle" and "Decisions vs. designs" — the folder mechanics + the
  ADR-vs-design split this flow assumes.
- [[POC-ROADMAP]] — the phase plan each slice implements one piece of.
- The shipped prompts to copy from: [[DOMAIN-MODEL-FOUNDATION]], [[LIBRARY-SPINE]],
  [[USER-MANAGEMENT-SERVICE]], [[TAG-DICTIONARY]], [[DATA-FILTERING]] (each folder's
  `AUTONOMOUS-IMPLEMENTATION-PROMPT.md`).
- `docs/code-review/CODE-REVIEW-WORKFLOW.md` — the `/deep-review` process that runs in the REVIEW/SHIP
  stage after the autonomous run.
