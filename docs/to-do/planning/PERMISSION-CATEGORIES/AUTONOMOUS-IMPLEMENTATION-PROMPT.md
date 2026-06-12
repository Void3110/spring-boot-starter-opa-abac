---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/opa
  - area/user-service
---

# Permission categories + delegation (Phase 6.5) — autonomous implementation prompt

> **What this is.** A self-contained prompt for implementing the permission-categories slice
> autonomously, ticket by ticket, with an architecture-review gate and a checkpoint after each
> ticket. The design and work list it refers to live alongside it in this folder.
>
> **Before you run it:** create the branch — `git checkout -b feature/void3110/permission-categories`
> off a clean `main`. Confirm `git config --local user.email` is `void31102025@gmail.com`. Then
> paste the **PROMPT** section below to the agent.

---

## PROMPT

You are implementing **the permission-categories + delegation slice (Phase 6.5)** on branch
`feature/void3110/permission-categories`.

**The problem.** A `RoleDefinition.permissions` today is a flat `{type: ["read"|"write"]}` map —
too coarse to express edit-but-not-delete, tag-curation-vs-content, or safe delegation, and
ungrouped for any role editor. This slice makes ADR 0007 real: four coarse categories
(`READ`/`WRITE`/`TAG`/`GRANT`) expand to fine actions (`view`/`list`/`create`/`update`/`delete`/
`define-tags`/`assign-tags`/`assign-roles`) via an expansion table in OPA `data`, refined by
deny-overrides, bounded by a five-tier level ceiling (the new **senior 25** tier included), and
enforced for delegation through two assignment gates — Java level compares under the team-row lock
plus a senior-only OPA `data.role.assignable` subset verdict. The headline: a role can finally say
"write but never delete" and a senior can onboard juniors without being an admin — safely under
concurrency. **This is a clean cut** (the starter is unpublished): seeds, annotations, policies,
fixtures, and e2e payloads migrate non-additively in this one slice, and a stale flat token
expands to ∅ = deny. **NOT in scope** (deferred): the control-plane vocabulary (`team:*` verbs,
`TeamRoleCapabilities` categorization) — Phase 6.7; `define-tags` *enforcement* on the dictionary
endpoints (ships in the math only); action enrichment — Phase 6.

Implement the core work directly. Do not delegate the implementation to a sub-agent. Sub-agents are
welcome for **read-only scouting** (e.g. "find every place across the suite that asserts X") and for
**log-noisy validation** (e.g. run a newman matrix / a long build and report back only the failure
summary) — their findings come back to you; the code, tests, and docs are written in this loop.

### Read before you start (in order)

1. `PERMISSION-CATEGORIES.md` (this folder's index) — what this slice delivers, the file glossary,
   the ticket status table, the critical path, conventions.
2. `00-DESIGN.md` — the ten settled forks (§2/§7 — do not reopen), the behavior matrix (§4), the
   fail-closed posture, and what this slice does NOT change (§5).
3. `01-DECOMPOSITION.md` — the 8 tickets, each with Goal / Deliverables / Acceptance /
   What-NOT-to-touch, **plus the five pinned decomposition semantics in its preamble**. **This is
   your work list.**
4. **The pinned decisions** — ADR `0007-coarse-grained-permission-categories.md` **including its
   Phase-6.5 implementation addendum** (the clean cut, the hybrid gates, the deferral, the fallback
   mapping); constrained by ADR 0003 (role ≠ grant), ADR 0004 (the tag dictionary the `TAG`
   category fences), ADR 0006 (the three enforcement layers).
5. `10-QA-TEST-CASES.md` — the unit / policy / integration / e2e cases your work must satisfy,
   including the pinned-contract table.
6. **Context you will be checked against** in the review gate (step 5):
   `docs/guides/TEAM-BASED-AUTHORIZATION.md` (the gates this replaces, Rule 6 team-row locking),
   `docs/guides/CONCURRENCY-AND-LOCKING.md` (Rules 1–2, 6 — decide-under-protection, latch ITs),
   `docs/guides/PARTIAL-EVALUATION-FILTERING.md` (the `filter` contract the expansion must fold
   into), `docs/guides/ATTRIBUTE-RICH-PRE-AUTHORIZATION.md` (the manager seam + request cache the
   second decision reuses), `docs/guides/TAG-BASED-AUTHORIZATION.md`, and the shipped packages
   `docs/to-do/implemented/RESOURCE-RESOLUTION/` + `docs/to-do/implemented/TAG-DICTIONARY/`
   (structure + idiom).
7. Root `CLAUDE.md` — the **IP boundary** (clean-room: original names only) and the **commit
   identity** rule (`Void3110 <void31102025@gmail.com>`).
8. `infra/README.md` — the local rig (needed for the e2e ticket), incl. the **in-network token
   caveat** and the **"restart OPA after editing a policy"** gotcha.
9. **Prime Mulch:** `ml prime opa-abac` + the directly-relevant record ids: `mx-ed11cb` (the ten
   pinned forks), `mx-cff420` (the ADR 0007 model), `mx-e3e84f` (version binding), `mx-d7ca6d`
   (deterministic race ITs via aspect hooks), `mx-a10957` (in-method flush → 409), `mx-56d654`
   (the 5.97 resolution/cache shape), `mx-cbd39e` (the PE-friendly `filter` rule), `mx-8926d0`
   (the coarse list gate), `mx-c56e29` (suite-wide newman wire migration), `mx-cf2280`
   (decomposition-pinned contract semantics).

### Per-ticket loop (tickets T1 → T8, IN ORDER; T2 is independent of T1/T3/T4 and T6 of T3–T5, but
run in numbered order — there is **no independently-landable subset**: the clean cut ships whole)

For each ticket do ALL of the following, in order, and **STOP at the checkpoint before the next**:

1. **Prime for the files you're about to touch.** `ml prime --files <path>`, and re-read that ticket's
   section in `01-DECOMPOSITION.md`.

2. **Build the deliverables** exactly as `01-DECOMPOSITION.md` lists them — exact classes, packages
   (`dev.dmitriikonovalov.opaabac.core` for T1 only; `dev.dmitriikonovalov.example.usermgmt.*` for
   T3–T5; `dev.dmitriikonovalov.example.catalog.*` for T6; `infra/opa/policies/` for T2/T5),
   mappings, rego rules. Match the surrounding code's naming and idioms.
   **Clean-room:** no proprietary names anywhere.

3. **Write/extend the tests** for the ticket (the relevant U*/P*/I*/E* cases from
   `10-QA-TEST-CASES.md`). Core/client tests use an **in-process
   `com.sun.net.httpserver.HttpServer` stub** (no WireMock). Persistence/IT tests run against
   **real Postgres via Testcontainers** (never H2). Policies use `opa test` + the **P10
   `opa eval --partial` fold harness** (a T2 acceptance, not a discovery).

4. **Compile + run unit tests until green.** `./gradlew :<module>:test` (and `./gradlew build` for the
   example/IT tickets). Fix-until-green.

5. **★ ARCHITECTURE REVIEW + REFACTOR — the gate before integration/e2e validation.** Once unit tests
   pass, do NOT advance yet. Run a focused self-review, then refactor and re-test:
   - **Fail-closed check — the slice's load-bearing invariant, stated concretely: an unknown/stale
     permission token expands to ∅ and denies; a missing/non-numeric `role_level` at assignment
     time rejects; an OPA error/timeout during `assignable` rejects; the `filter` rule keeps no
     subject-roles fallback; a denied second decision leaves the entity untouched; denials only
     ever narrow — no error/timeout/missing-input path lands on a wider result.**
   - **Security check — name the widening that would matter for this ticket and state why it cannot
     happen: an assignment gate readable as satisfied by a pre-lock snapshot (escalation-by-race);
     the `assignable` client treating an OPA non-answer as yes; the delta computation classifying a
     content change as tags-only (gate bypass); a stale flat role still deciding through some
     unswept rule; the authoring API accepting a denial that re-widens via a second grant path
     (there is none — categories are the only grant shape); validation-error bodies leaking
     internal state.**
   - **Concurrency / idempotency check — every decision that gates a mutation is computed under the
     same lock or version guard that holds through the commit (`CONCURRENCY-AND-LOCKING.md` Rules
     1–2 — code that locks first but acts on a pre-lock decision is the defect): both assignment
     gates AND the `assignable` snapshots read after `lockTeam` in the same transaction; the
     catalog's second decision precedes any mutation and the 5.97 version-guard flow is unchanged;
     a retried/replayed bootstrap or assignment converges.**
   - **Wiring check** — every seam this ticket adds (the `permissions.rego` module, the
     `data.role.assignable` entrypoint, `RoleAssignableClient`, `TagDecisionGate`,
     `RoleDefinitionInvalidException` + its advice mapping, the bootstrap fields) has a **named
     consumer** and a test through its **non-happy path**; zero call sites = the ticket is not done.
   - **Boundary / additivity check — the waiver is bounded: non-additive change is licensed ONLY
     for the migration surfaces (seeds, annotations, policies, fixtures, payloads, the authoring
     API). `opa-abac-core` stays Spring-free; T1's record field is the only `opa-abac-*` change
     and a denial-free role serializes byte-for-byte as before; `AbacContext`, the manager, the
     cache, partial-eval machinery, `team.rego`, and `gateway.rego` are untouched.**
   - **Module-layer separation — expansion math lives in OPA (`data` + `permissions.rego`); the
     Java `PermissionCategories` table exists for 422-time validation only and is parity-pinned to
     the JSON; gates live in the service layer under the lock; the catalog's gate dispatch lives in
     the example app, never the library.**
   - **Pattern-reuse check — the named shipped patterns this must match, not reinvent: the
     colocated data-file mounting (`category_inheritable.json`), the in-process OPA stub, the
     latch-IT shape (`MembershipConcurrencyIT`), the annotated-gate manager seam (5.97), the
     runner hygiene of `run-resource-resolution-matrix.sh`.**
   - **SOLID / decomposition** — cohesive (SRP), depends on interfaces (DIP); anything to split/simplify?
   - **Apply** the refactoring the review surfaces, then **re-run the unit tests** to confirm green.
   - Write a short note of what the review found + what you refactored into `STATUS-0N.md`. If it found
     nothing substantive, say so explicitly — **do not invent churn.**

6. **Integration / e2e validation (MANDATORY for the relevant tickets).**
   - T2/T5: `opa test infra/opa/policies/` (and T2's P10 fold:
     `opa eval --partial -d infra/opa/policies …` per the QA card). Fix-until-green.
   - T3–T6: `./gradlew :example-user-management-service:test` /
     `:example-catalog-management-service:test` / `./gradlew build` — Testcontainers ITs against
     real Postgres asserting the seed rows, the gate matrix, the decision sequences.
     Fix-until-green.
   - T7 (e2e): bring the rig up (`./profile.sh up`; `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1
     ./deploy.sh up --pods 2`), **rebuild BOTH app images** (`./deploy.sh build` covers the
     catalog only — build the usermgmt image explicitly: `docker build -t opa-abac-usermgmt:local
     -f example-user-management-service/Dockerfile .` then recreate its pod), then `cd
     scripts/postman && ./run-permission-categories-matrix.sh` (twice — idempotency) **and every
     existing runner**. Honor the in-network token caveat and restart OPA after a rego edit (the
     new runner does it itself). Fix-until-green.

7. **Update documentation (after each ticket).** Tick the ticket in the `PERMISSION-CATEGORIES.md`
   status table; record real values/decisions in `STATUS-0N.md`. The ticket that finalizes a guide
   topic writes/reconciles `docs/guides/PERMISSION-MODEL.md` + the six reconciliations (T8).
   Root/project `CLAUDE.md` only if a new build/run step matters.

8. **Mulch expertise check (after each ticket).** `ml prime`/skim, then record any genuine reusable
   insight (`ml record opa-abac --type <pattern|decision|failure|reference> …`) and `ml sync`. **Before
   `ml sync`, `git restore --staged .`** so the sync commit touches `.mulch/` **only** (the
   swept-staged trap). Skip recording only if nothing is non-obvious.

9. **Commit** — one focused commit on this branch (code + tests + docs + the `STATUS-0N.md` note
   together). Identity `Void3110 <void31102025@gmail.com>`. Conventional subject
   `feat(permissions): …` / `feat(usermgmt): …` / `feat(catalog): …` / `feat(opa): …` /
   `docs(permission-categories): …` as fits the ticket. A `Co-Authored-By: Claude` trailer is
   welcome.

10. **CHECKPOINT — STOP and report.** Summarize what shipped, paste the unit + integration/e2e summary,
    **summarize the review findings + the refactoring you applied** (step 5), list docs updated, and
    note any open question you resolved. Then proceed to the next ticket. **Do not batch tickets
    without a checkpoint.**

### Permissions / autonomy granted (do these WITHOUT asking)

- Create/modify the example services' code, `infra/opa/policies/`, the one T1 core record field,
  tests, docs in this folder + the guides, the `scripts/postman/` suite, and Mulch — all on this
  branch.
- Stand up / tear down / reseed the local rig (`./profile.sh`, `./deploy.sh`,
  `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 …`); reset fixtures; rebuild images (both); restart OPA;
  drop/recreate the **local** schema if needed; direct psql against the local DBs (the E5 stale-row
  seed is by design a DB INSERT).
- Fix any issue your own validation reveals (compile, unit, IT, e2e, refactor). Iterate until green.
- Commit per ticket on this branch.

### Hard rules

- **Report at every checkpoint and continue.** Don't batch tickets.
- **The architecture-review + refactor step (5) is NOT optional and happens BEFORE integration/e2e
  validation** — unit green → review → refactor → re-test → then ITs/e2e. Document what it found.
- **Fix-until-green within the ticket.** Only STOP mid-ticket if genuinely *blocked*: the same root
  cause survives ≥3 focused attempts, OR a design decision the docs don't cover is needed, OR a local
  prerequisite is unrecoverable.
- **Fail-closed is the load-bearing invariant — for this slice: no token, level, verdict, or OPA
  response that cannot be positively interpreted ever widens access — stale tokens expand to ∅,
  unreadable levels reject, OPA non-answers reject, and the `filter` rule decides from the role
  definition alone.**
- **Slice-specific invariants — never trade these away:**
  - **The additive-only doctrine is consciously waived for this slice** (00-DESIGN §2.3) — but the
    waiver is **bounded**: it licenses the migration surfaces only. T1's record field is the sole
    `opa-abac-*` change; the manager, cache, `AbacContext`, partial-eval machinery, `team.rego`,
    and `gateway.rego` stay untouched. If you think you need a second library change, STOP and
    report.
  - **Decide under protection:** both assignment gates and the `assignable` snapshots read state
    after `lockTeam` in the same transaction — never act on pre-lock reads.
  - **Expansion lives in OPA `data`** — exactly one runtime home; the Java table is validation-only
    and parity-pinned.
  - **Match-in-Rego:** the subset verdict, tag matching, and deny-overrides are policy decisions;
    Java does plumbing and level compares only.
  - **The ten forks of 00-DESIGN §2 are settled** — do not reopen them; the five pinned
    decomposition semantics in `01-DECOMPOSITION.md` are equally binding.
  - **The whole existing newman suite green post-migration** is acceptance, not a nice-to-have —
    a flipped cell is a stop-and-investigate, never a silent re-pin.
- **Clean-room IP boundary** — never introduce proprietary names, package names, comments, or source.
- **`opa-abac-core` stays Spring-free** (T1 touches it).
- **`ddl-auto: validate` must pass** (T3 touches schema — a clean app boot is the proof).
- **Do NOT push, open PRs, or touch `main`.** Local + this branch only. The maintainer pushes.

---

## Operator notes (not part of the prompt)

- **The headline tickets** — **T2** (the policy clean cut: expansion table + shared module + the PE
  fold proving the residual survives), **T5** (the hybrid gates + `assignable` under the team-row
  lock — the delegation safety story), **T7** (the matrix + the nine-runner migration proving the
  cut regressed nothing).
- **The fail-closed edge to eyeball** — the `RoleAssignableClient` error path (an OPA non-answer
  must read as *not assignable*, never as yes) and the delta computation in T6 (a content change
  misclassified as tags-only would bypass the `update` decision — the I13/I14 decision-sequence
  asserts are the guard).
- **Standalone-value subset** — **none**: the clean cut is atomic; nothing before T7 should merge
  alone. Plan the window for the full run or pause at a checkpoint (the branch is the unit of
  shipping).
- **Rig / e2e specifics** — in-network token mint (issuer `keycloak:8888`); the new runner restarts
  OPA + health-polls (every policy changes in this slice); `./deploy.sh build` rebuilds the catalog
  image only — the usermgmt image needs an explicit build + pod recreate; the user-service DB
  persists across runs (the fixture-id registry is why the new matrix owns `9999…`); E5 seeds its
  stale row via direct psql INSERT (the API now rejects flat tokens by design).
- **CI does not run the rig yet** — the newman matrix is a local/manual gate; a compose-up→newman CI
  job is a tracked follow-up.
- **Context management** — if the window grows long mid-run, finish the ticket, stop at its checkpoint,
  and resume in a **fresh session** (the ticket status table + STATUS notes are the handoff); sub-agents
  are for scouting/validation only, never the implementation.
- **Workflow-as-artifact:** keep this prompt verbatim; the `STATUS-0N.md` notes record each ticket's
  outcome. Move the folder to `docs/to-do/implemented/` on ship.
