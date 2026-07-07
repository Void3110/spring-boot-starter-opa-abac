---
tags:
  - status/planned
  - type/project
  - area/api
  - area/spring
---

# DIRECTORY-QUERY-FILTERS — autonomous implementation prompt

> **What this is.** A self-contained prompt for implementing the server-side directory / query filters
> slice autonomously, ticket by ticket, with an architecture-review gate and a checkpoint after each
> ticket. The design and work list it refers to live alongside it in this folder.
>
> **Before you run it:** create the branch — `git checkout -b feature/void3110/directory-query-filters`
> off a clean `main`. Confirm `git config --local user.email` is `void31102025@gmail.com`. **Export
> `JAVA_HOME` to Corretto 21** (`export JAVA_HOME="$(/usr/libexec/java_home -v 21)"`) — the machine
> default `java` is now JDK 25 and Gradle 8.12 fails under it with a bare `25.0.3` error. Then paste the
> **PROMPT** section below to the agent.

---

## PROMPT

You are implementing **the server-side directory / query filters slice** on branch
`feature/void3110/directory-query-filters`.

**The problem.** `example-user-management-service`'s `GET /users` and `GET /teams` support only offset
pagination, so the demo SPA emulates its single-resource lookups (user-by-subject, team-by-target) with
client-side `listAll*` **page-walks** that are O(collection) and **silently truncate** past page 0 — a
lookup can wrongly report "not found" and provision a duplicate. This slice adds **exact-match query
filters** (`?subject` on `/users`; `?targetType`+`?targetId` on `/teams`), each backed by a repository
finder that **already exists**, returning the single match as a one-item page; plus two correctness fixes
in the same service — a `produces` spec fix so the four 204-only endpoints stop 406-ing a bare
`Accept: application/json`, and a bootstrap `displayName` **upsert**. **Explicitly NOT in scope:** the
Keycloak-admin `UserDirectory` port and its `search` endpoint under `/api/v1/users` (that is the
separate Slice 2), any change to
the public `POST /users` create path, the SPA directory *picker* rewrite, and any OPA/library/schema
change. This slice is purely additive to one deployable.

Implement the core work directly. Do not delegate the implementation to a sub-agent. Sub-agents are
welcome for **read-only scouting** (e.g. "find every caller of `listAllUsers`/`listAllTeams` across the
SPA") and for **log-noisy validation** (e.g. run the newman matrix / a long build and report back only
the failure summary) — their findings come back to you; the code, tests, and docs are written in this loop.

### Read before you start (in order)

1. `DIRECTORY-QUERY-FILTERS.md` (this folder's index) — what this slice delivers, the file glossary, the
   ticket status table, the critical path, conventions.
2. `00-DESIGN.md` — the mechanism, the five decided forks, the fail-closed / back-compat posture, and
   considered-&-rejected.
3. `01-DECOMPOSITION.md` — the 5 tickets, each with Goal / Deliverables / Acceptance / What-NOT-to-touch.
   **This is your work list.**
4. **The pinned decisions** — [[0012-pagination-envelope|ADR 0012]] (the one-item-page envelope) and
   [[0011-error-contract-problem-json|ADR 0011]] (the 400 body for the half-specified `/teams` pair).
5. `10-QA-TEST-CASES.md` — the unit / integration / e2e cases your work must satisfy.
6. **Context you will be checked against** in the review gate (step 5): the user-service structure
   (`example-user-management-service/src/main/java/dev/dmitriikonovalov/example/usermgmt/web/`
   `UserController`, `TeamController`, `InternalBootstrapController`, `UserMgmtMapper`, `PageDefaults`,
   `ApiExceptionHandler`; `domain/UserRepository`, `TeamRepository`), the spec
   `example-user-management-service/src/main/resources/openapi/user-mgmt-api.yaml`, and the
   REST-API-REFINEMENT shipped slice under `docs/to-do/implemented/REST-API-REFINEMENT/` (the closest
   additive-API-shape model). The SPA consumers: `example-demo-ui/src/api.ts` (`listAllUsers`,
   `listAllTeams`, `ensureUser`) and `teams.tsx:42`.
7. Root `CLAUDE.md` — the **IP boundary** (clean-room: original names only) and the **commit identity**
   rule (`Void3110 <void31102025@gmail.com>`).
8. `infra/README.md` — the local rig (needed for the T5 e2e), incl. the **in-network token caveat** and
   the **"restart OPA after editing a policy"** gotcha (no rego changes here, so no OPA restart needed).
9. **Prime Mulch:** `ml prime opa-abac` (+ the directly-relevant records: the user-directory-slice split
   decision recorded 2026-07-06; `mx-b17da2` user-management-service layered structure; the
   pagination-envelope records).

### Per-ticket loop (tickets T1 → T5, IN ORDER; T1–T4 are mutually independent and may reorder, T5 is last)

For each ticket do ALL of the following, in order, and **STOP at the checkpoint before the next**:

1. **Prime for the files you're about to touch.** `ml prime --files <path>`, and re-read that ticket's
   section in `01-DECOMPOSITION.md`.

2. **Build the deliverables** exactly as `01-DECOMPOSITION.md` lists them — exact classes, packages
   (`dev.dmitriikonovalov.example.usermgmt.{web,domain}`), the OpenAPI param/response edits, mappings.
   Match the surrounding code's naming and idioms. **Clean-room:** no proprietary names anywhere.

3. **Write/extend the tests** for the ticket (the relevant U*/I*/E* cases from `10-QA-TEST-CASES.md`).
   Persistence/IT tests run against **real Postgres via Testcontainers** (never H2). (No OPA client stub
   or `opa test` is needed — this slice touches no policy and no OPA call.)

4. **Compile + run unit tests until green.** `./gradlew :example-user-management-service:test` (and
   `./gradlew build` for the T5 / codegen-touching work). Fix-until-green.

5. **★ ARCHITECTURE REVIEW + REFACTOR — the gate before integration/e2e validation.** Once unit tests
   pass, do NOT advance yet. Run a focused self-review, then refactor and re-test:
   - **Fail-closed / no-widening check (this slice's load-bearing invariant):** the absent-filter path is
     **byte-for-byte** today's paged `findAll` (a new branch, never a replacement); a present-but-unmatched
     filter returns an **empty page** (`count=0`), never a fallthrough to the full list; a half-specified
     `/teams` pair is a **400**, never "return everything." State why each holds for the ticket.
   - **Security check:** name the widening that would matter here — a filter that silently degrades to the
     full collection, or the bootstrap upsert re-pointing a subject / touching memberships/roles — and
     state why it cannot happen (the upsert writes only `displayName` on the same-subject row; the
     internal seed endpoint stays `/internal/**`, never gateway-exposed).
   - **Concurrency / idempotency check:** the bootstrap upsert (T4) is `@Transactional` and idempotent —
     an identical re-post is a no-op; a changed-displayName re-post converges to the new value with no
     duplicate row. The read filters (T1/T2) are pure reads, no mutation to guard.
   - **Wiring check** — every seam this ticket adds has a **named consumer** and a test through its
     **non-happy path**: T1 the unmatched-subject empty page (U1b/I1); T2 the exactly-one-param **400**
     (U2c/I2) and the unmatched pair (U2b); T3 the `Accept: application/json` → 204-not-406 path (I3);
     T4 the existing-subject update path (I4). A branch with only its happy case tested is not done.
   - **Boundary / additivity check** — `opa-abac-core` is **not touched** (no core/library change at all);
     the change is additive to the two list endpoints' contracts (new optional params) and to the seed
     endpoint; name the byte-for-byte-unchanged surfaces (the `findAll` paged path; the 204 status+empty
     body) and the one mechanical cost (the regenerated `UserApi`/`TeamApi` signatures gain the new params,
     so the `@Override` controller methods must land in the same commit).
   - **Module-layer separation** — controller-level branching only; repositories already carry the
     finders; the mapper builds the envelope. No new query, no service-layer change beyond T4's upsert.
   - **Pattern-reuse check** — match REST-API-REFINEMENT's spec-first + `ApiExceptionHandler` idioms and
     the existing `PageDefaults`/`UserMgmtMapper` envelope shape (ADR 0012); do not reinvent them.
   - **SOLID / decomposition** — cohesive (SRP), depends on the existing repository interfaces (DIP);
     anything to split/simplify?
   - **Apply** the refactoring the review surfaces, then **re-run the unit tests** to confirm green.
   - Write a short note of what the review found + what you refactored into `STATUS-0N.md`. If it found
     nothing substantive, say so explicitly — **do not invent churn.**

6. **Integration / e2e validation (MANDATORY for the relevant tickets).**
   - **T1–T4:** the Testcontainers ITs against real Postgres (I1–I4) asserting the row sets / status codes
     in `10-QA-TEST-CASES.md`. Fix-until-green.
   - **T5 (e2e):** bring the rig up (`ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2`), then
     `cd scripts/postman && ./run-tests.sh` (extend the existing user-service matrix — no new collection).
     Honor the **in-network token caveat** (mint tokens inside the compose network). No rego changed, so
     no OPA restart. Assert the **actual cut** (row counts, status codes), not just shape. Fix-until-green.

7. **Update documentation (after each ticket).** Tick the ticket in the `DIRECTORY-QUERY-FILTERS.md`
   status table; record real values/decisions in `STATUS-0N.md`. The T5 ticket reconciles
   `docs/guides/REST-API-DESIGN.md` (the filtered-list convention + the both-target-params 400 + the
   204/`Accept` fix). Root/project `CLAUDE.md` only if a new build/run step matters (none expected).

8. **Mulch expertise check (after each ticket).** `ml prime`/skim, then record any genuine reusable
   insight (`ml record opa-abac --type <pattern|decision|failure|reference> …`) and `ml sync`. **Before
   `ml sync`, `git restore --staged .`** so the sync commit touches `.mulch/` **only** (the swept-staged
   trap). Skip recording only if nothing is non-obvious.

9. **Commit** — one focused commit on this branch (code + tests + docs + the `STATUS-0N.md` note
   together). Identity `Void3110 <void31102025@gmail.com>`. Conventional subject
   (`feat(usermgmt): …` for T1/T2/T4, `fix(usermgmt): …` for T3, `test(e2e)/docs(directory-query-filters): …`
   for T5). A `Co-Authored-By: Claude` trailer is welcome.

10. **CHECKPOINT — STOP and report.** Summarize what shipped, paste the unit + integration/e2e summary,
    **summarize the review findings + the refactoring you applied** (step 5), list docs updated, and note
    any open question you resolved. Then proceed to the next ticket. **Do not batch tickets without a
    checkpoint.**

### Permissions / autonomy granted (do these WITHOUT asking)

- Create/modify example-service code, the OpenAPI spec (+ regenerated sources), tests, the SPA
  (`example-demo-ui/src/*`, T5 only), docs in this folder + `docs/guides/REST-API-DESIGN.md`, the
  `scripts/postman/` suite, and Mulch — all on this branch.
- Stand up / tear down / reseed the local rig (`./profile.sh`, `./deploy.sh`,
  `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 …`); reset fixtures; rebuild images; drop/recreate the **local**
  schema if needed.
- Fix any issue your own validation reveals (compile, unit, IT, e2e, refactor). Iterate until green.
- Commit per ticket on this branch.

### Hard rules

- **Report at every checkpoint and continue.** Don't batch tickets.
- **The architecture-review + refactor step (5) is NOT optional and happens BEFORE integration/e2e
  validation** — unit green → review → refactor → re-test → then ITs/e2e. Document what it found.
- **Fix-until-green within the ticket.** Only STOP mid-ticket if genuinely *blocked*: the same root cause
  survives ≥3 focused attempts, OR a design decision the docs don't cover is needed, OR a local
  prerequisite is unrecoverable.
- **Back-compat + no widening is the load-bearing invariant:** no absent-filter call changes behavior,
  and no filter ever returns a wider result on a miss than a full-list scan would — an unmatched filter is
  an **empty page**, a half-specified `/teams` pair is a **400**, never a fallthrough to the whole table.
- **Slice-specific invariants the agent must never trade away:** the filter is an **additive branch**, not
  a replacement of `findAll`; the `produces` fix keeps the **204 status + empty body** (only the negotiable
  produced-type widens); the bootstrap upsert writes **only `displayName`** on the same-subject row and
  stays on the **internal** seed endpoint; the public `POST /users` create path stays create-only.
- **Clean-room IP boundary** — never introduce proprietary names, package names, comments, or source.
- **`opa-abac-core` stays Spring-free** — and in fact this slice does not touch core or any library module
  at all.
- **`ddl-auto: validate` must pass** — no schema change here, so a clean app boot is the proof it stayed clean.
- **Do NOT push, open PRs, or touch `main`.** Local + this branch only. The maintainer pushes.

---

## Operator notes (not part of the prompt)

- **The headline tickets** — **T1** (`?subject`) and **T2** (`?targetType`+`?targetId`); their passing
  ITs (I1, I2) justify the whole slice — they replace the SPA's truncating `listAll*` walks with a
  correct single request. T3/T4 are small correctness riders.
- **The fail-closed edge to eyeball** — the **both-params-required 400 on `/teams`** (§2.2): if an agent
  lets one param fall through to `findAll`, it silently re-introduces the whole-collection scan wearing a
  filter mask. That is the one place this slice could quietly leak its own purpose. Check I2's
  one-param-present case returns 400, not a full list.
- **Standalone-value subset** — T1 and T2 each land independently (a filter + IT, no SPA, no rig); either
  alone kills one `listAll*` walk. If the window is short, T1+T2 are the ship-worthy core; T3/T4/T5 follow.
- **Rig / e2e specifics** — mint tokens **in-network** (APISIX validates issuer `keycloak:8888`); the
  user-service must be up (`ENABLE_USER_SERVICE=1`); **no rego changes**, so no OPA restart. `JAVA_HOME`
  must be Corretto 21 for every `./gradlew` invocation (default `java` is 25 → bare `25.0.3` failure).
- **CI does not run the rig yet** — the newman matrix is a local/manual gate; a compose-up→newman CI job
  is a tracked follow-up.
- **Context management** — if the window grows long mid-run, finish the ticket, stop at its checkpoint,
  and resume in a **fresh session** (the ticket status table + STATUS notes are the handoff); sub-agents
  are for scouting/validation only, never the implementation.
- **Workflow-as-artifact:** keep this prompt verbatim; the `STATUS-0N.md` notes record each ticket's
  outcome. Move the folder to `docs/to-do/implemented/` on ship (T5).
