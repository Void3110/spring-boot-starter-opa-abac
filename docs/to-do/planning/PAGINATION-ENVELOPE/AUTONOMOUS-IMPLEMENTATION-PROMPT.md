---
tags:
  - status/planned
  - type/project
  - area/api
  - area/spring
  - area/abac
---

# Pagination envelope (Phase 5.95) — autonomous implementation prompt

> **What this is.** A self-contained prompt for implementing the pagination-envelope slice autonomously,
> ticket by ticket, with an architecture-review gate and a checkpoint after each ticket. The design and
> work list it refers to live alongside it in this folder.
>
> **Before you run it:** create the branch — `git checkout -b feature/void3110/pagination-envelope` off
> a clean `main`. Confirm `git config --local user.email` is `void31102025@gmail.com`. Then paste the
> **PROMPT** section below to the agent.

---

## PROMPT

You are implementing **the pagination envelope (Phase 5.95)** on branch
`feature/void3110/pagination-envelope`.

**The problem.** Every public list endpoint in both example services returns a bare, unbounded array —
a documented demo limitation a published library's reference services must not model. This slice adopts
**one shared `{count, page, perPage, items}` envelope on all 9 public lists**, composed with the Phase-5
partial-eval filter through an **additive paged `findAuthorized(…, Pageable)` → `Page<T>` overload** in
`opa-abac-spring-data` — so the *filtered* row set paginates and `count` is the **subject-relative
authorized total on every query path** (pure-SQL, allowlist-fallback, kill-switch, error). The headline:
*the count is the count of rows __you__ may see.* Scope boundary: this is a **list-shape change only** —
no authorization behavior changes anywhere (`listCategories` stays the only residual-filtered list),
zero Rego changes, no client `?sort=` (deferred target), no `/internal/**` pagination (plain by design),
no `_actions` metadata (Phase 6 lands on this envelope).

Implement the core work directly. Do not delegate the implementation to a sub-agent.

### Read before you start (in order)

1. `PAGINATION-ENVELOPE.md` (this folder's index) — what this slice delivers, the file glossary, the
   ticket status table, the critical path, conventions.
2. `00-DESIGN.md` — the four-path paged seam, the wire contract, the ordering rules, the fail-closed
   posture, considered-&-rejected.
3. `01-DECOMPOSITION.md` — the 6 tickets, each with Goal / Deliverables / Acceptance /
   What-NOT-to-touch. **This is your work list.**
4. **The pinned decisions** — ADR `0012-pagination-envelope` (this slice's forks); context: ADR
   `0005-partial-eval-to-jpa-specification` + ADR `0010-hierarchy-aware-list-filter` (the list paths you
   are paginating — their compositions are reused byte-identically) and ADR
   `0011-error-contract-problem-json` (the `400 VALIDATION_FAILED` `problem+json` surface the strict
   negatives land on).
5. `10-QA-TEST-CASES.md` — the unit / integration / e2e cases your work must satisfy.
6. **Context you will be checked against** in the review gate (step 5):
   `opa-abac-spring-data/src/main/java/dev/dmitriikonovalov/opaabac/data/filter/AbacQueryService.java`
   (the seam you extend — its two invariants are load-bearing),
   `docs/to-do/implemented/DATA-FILTERING/` + `docs/to-do/implemented/HIERARCHY-LIST-FILTER/` (the
   list-filter patterns), `docs/to-do/implemented/REST-API-REFINEMENT/01-DECOMPOSITION.md` (the
   per-service spec-change build-breaker model), `docs/guides/REST-API-DESIGN.md` +
   `docs/guides/PARTIAL-EVALUATION-FILTERING.md` (the guides this slice advances).
7. Root `CLAUDE.md` — the **IP boundary** (clean-room: original names only) and the **commit identity**
   rule (`Void3110 <void31102025@gmail.com>`).
8. `infra/README.md` — the local rig (needed for the e2e ticket), incl. the **in-network token caveat**
   and the **"restart OPA after editing a policy"** gotcha.
9. **Prime Mulch:** `ml prime opa-abac` + the directly-relevant record ids: `mx-4e6071` (the
   AbacQueryService seam + its two invariants), `mx-6876cd` (residual → JSONB `Specification`),
   `mx-8bd79f` (the two-subject e2e list matrix), `mx-15ee3e` (partial-eval → Specification), and in
   `api-design`: `mx-ea9893` (the ADR-0012 settlement — the pinned contract).

### Per-ticket loop (tickets T1 → T6, IN ORDER; T2/T3/T4 are mutually independent after T1 — execute
them in the listed order, but a T3/T4 finding never blocks T2)

For each ticket do ALL of the following, in order, and **STOP at the checkpoint before the next**:

1. **Prime for the files you're about to touch.** `ml prime --files <path>`, and re-read that ticket's
   section in `01-DECOMPOSITION.md`.

2. **Build the deliverables** exactly as `01-DECOMPOSITION.md` lists them — exact classes, packages
   (`dev.dmitriikonovalov.opaabac.data.filter`, `dev.dmitriikonovalov.example.{catalog,usermgmt}.*`),
   spec schemas/params, mappings. Match the surrounding code's naming and idioms.
   **Clean-room:** no proprietary names anywhere.

3. **Write/extend the tests** for the ticket (the relevant U*/I*/E* cases from `10-QA-TEST-CASES.md`).
   Core/client tests use an **in-process `com.sun.net.httpserver.HttpServer` stub** (no WireMock).
   Persistence/IT tests run against **real Postgres via Testcontainers** (never H2). Policies use
   `opa test` (not applicable here — this slice changes no Rego; the existing suite must simply stay
   untouched).

4. **Compile + run unit tests until green.** `./gradlew :<module>:test` (and `./gradlew build` for the
   example/IT tickets). Fix-until-green.

5. **★ ARCHITECTURE REVIEW + REFACTOR — the gate before integration/e2e validation.** Once unit tests
   pass, do NOT advance yet. Run a focused self-review, then refactor and re-test:
   - **Fail-closed check — no paged path returns a wider page or a larger `count` on an error than on
     success: `fromError` → an empty page with `count = 0` and no repo call; the fallback's `count` is
     the batch-filtered survivor count (a short/all-false batch drops rows); the kill-switch keeps
     `notDenied` AND-ed in the paged query; the unsorted-`Pageable` guard throws before any OPA/repo
     call.**
   - **Boundary / additivity check — `opa-abac-core` stays Spring-free (zero core diffs); the library
     change is additive: the 3-arg/4-arg `findAuthorized` overloads are byte-compatible and every
     pre-existing library test passes unmodified; the one mechanical cost is the per-service spec
     build-breaker (regenerated list signatures), confined to that service's own commit (T3/T4).**
   - **Module-layer separation — `Pageable`/`Page` live in `opa-abac-spring-data` only; the wire
     envelope (`PageEnvelope`, `<Resource>Page`) lives in the services' OpenAPI specs only; the fixed
     `createdAt,id` order is built in the controllers; validation bounds live in the spec params (the
     generated constraints → the existing ADR-0011 advice — no hand-rolled validation).**
   - **Pattern-reuse check — the paged paths reuse the existing compositions byte-identically
     (`scope.and(tagResidual.or(subtreeSpec)).and(notDenied)`, the sorted candidate fetch + the
     order-preserving `batchFilter`); the spec edits follow the REST-API-REFINEMENT per-service model;
     the e2e matrix follows the `run-filter-matrix.sh` two-subject model.**
   - **SOLID / decomposition** — cohesive (SRP), depends on interfaces (DIP); anything to split/simplify?
   - **Apply** the refactoring the review surfaces, then **re-run the unit tests** to confirm green.
   - Write a short note of what the review found + what you refactored into `STATUS-0N.md`. If it found
     nothing substantive, say so explicitly — **do not invent churn.**

6. **Integration / e2e validation (MANDATORY for the relevant tickets).**
   - T2: `PaginationListIT` (Testcontainers, real Postgres) asserting the two-subject `count` contrast
     (I1), the `perPage=2` stability walk — union exact, no repeat/drop (I2), fallback parity (I3),
     past-the-end (I4). `./gradlew :opa-abac-spring-data:test`. Fix-until-green.
   - T3 / T4: `./gradlew :example-catalog-management-service:build` /
     `./gradlew :example-user-management-service:build` (codegen clean = C1–C2) + the service IT cases
     (I5–I6 / I7–I9). Fix-until-green.
   - T5 (e2e): bring the rig up (`./profile.sh up`, then
     `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2`; `./deploy.sh build` first to force
     the new app images), then `cd scripts/postman && ./run-pagination-matrix.sh` (E1–E3) **and re-run
     every updated existing matrix** (`./run-filter-matrix.sh`, `./run-hierarchy-list-matrix.sh`,
     `./run-matrix.sh`, `./run-tag-matrix.sh`, `./run-team-matrix.sh`, `./run-tests.sh`) green with
     numerically unchanged row counts (E4). Honor the in-network token caveat. Fix-until-green.

7. **Update documentation (after each ticket).** Tick the ticket in the `PAGINATION-ENVELOPE.md` status
   table; record real values/decisions in `STATUS-0N.md`. The ticket that finalizes a guide topic
   writes/reconciles `docs/guides/REST-API-DESIGN.md` (§7/§8/§9),
   `docs/guides/PARTIAL-EVALUATION-FILTERING.md` (the paged composition), and
   `docs/guides/E2E-TESTING.md` (T6). Root/project `CLAUDE.md` only if a new build/run step matters.

8. **Mulch expertise check (after each ticket).** `ml prime`/skim, then record any genuine reusable
   insight (`ml record opa-abac --type <pattern|decision|failure|reference> …`) and `ml sync`. **Before
   `ml sync`, `git restore --staged .`** so the sync commit touches `.mulch/` **only** (the
   swept-staged trap). Skip recording only if nothing is non-obvious.

9. **Commit** — one focused commit on this branch (code + tests + docs + the `STATUS-0N.md` note
   together). Identity `Void3110 <void31102025@gmail.com>`. Conventional subject
   `feat(pagination): …` / `test(pagination): …` / `docs(pagination): …` as fits the ticket.
   A `Co-Authored-By: Claude` trailer is welcome.

10. **CHECKPOINT — STOP and report.** Summarize what shipped, paste the unit + integration/e2e summary,
    **summarize the review findings + the refactoring you applied** (step 5), list docs updated, and
    note any open question you resolved. Then proceed to the next ticket. **Do not batch tickets
    without a checkpoint.**

### Permissions / autonomy granted (do these WITHOUT asking)

- Create/modify library code (`opa-abac-spring-data`), example code (both services + their OpenAPI
  specs), tests, docs in this folder + the guides, the `scripts/postman/` suite, and Mulch — all on
  this branch.
- Stand up / tear down / reseed the local rig (`./profile.sh`, `./deploy.sh`,
  `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 …`); reset fixtures; rebuild images (`./deploy.sh build`);
  restart OPA; drop/recreate the **local** schema if needed.
- Fix any issue your own validation reveals (compile, unit, IT, e2e, refactor). Iterate until green.
- Commit per ticket on this branch.

### Hard rules

- **Report at every checkpoint and continue.** Don't batch tickets.
- **The architecture-review + refactor step (5) is NOT optional and happens BEFORE integration/e2e
  validation** — unit green → review → refactor → re-test → then ITs/e2e. Document what it found.
- **Fix-until-green within the ticket.** Only STOP mid-ticket if genuinely *blocked*: the same root
  cause survives ≥3 focused attempts, OR a design decision the docs don't cover is needed, OR a local
  prerequisite is unrecoverable.
- **Fail-closed is the load-bearing invariant — no paged path returns a wider page or a larger `count`
  on an error than on success: a failed compile empties the page (`count = 0`), a failed/short batch
  decision drops rows from both the page and the count, the kill-switch keeps the deny filter AND-ed,
  and an unsorted `Pageable` is a thrown error — never a silently nondeterministic page.**
- **Slice-specific invariants — never trade these away:**
  - **Exact-count everywhere:** `count` is the subject's authorized total on every path — never
    `items.length`, never an estimate, never `null`.
  - **AND-don't-replace is preserved verbatim:** the paged compositions are the existing ones —
    `scope.and(tagResidual.or(subtreeSpec)).and(notDenied)` — with a `Pageable` added; the residual/
    scope/deny logic does not change.
  - **Additive-only library change:** the 3-arg/4-arg overloads stay byte-compatible; if you think you
    need a non-additive change, STOP and report.
  - **Authorization semantics nowhere:** every `@OpaPreAuthorize` (and every deliberate absence) stays
    byte-identical; `listCategories` remains the only residual-filtered list; the e2e row counts are
    numerically unchanged.
  - **Strict params, no clamping:** bounds violations are `400 VALIDATION_FAILED` `problem+json` via
    the existing advice; past-the-end is `200` + empty + exact `count`.
  - **Sorted by construction:** every paged query carries `createdAt ASC, id ASC`; the seam rejects an
    unsorted `Pageable`.
  - **Zero Rego edits** (`infra/opa/` untouched) and **`/internal/**` stays unpaginated** (its note is
    a comment, not a change).
- **Clean-room IP boundary** — never introduce proprietary names, package names, comments, or source.
- **`opa-abac-core` stays Spring-free** — this slice does not touch core at all (grep the diff).
- **`ddl-auto: validate` must pass** — no schema change in this slice, so a clean boot is pure
  regression proof.
- **Do NOT push, open PRs, or touch `main`.** Local + this branch only. The maintainer pushes.

---

## Operator notes (not part of the prompt)

- **The headline tickets** — **T2** (the two-subject `count` contrast + the `perPage=2` stability walk
  against real Postgres — the determinism proof) and **T5** (the live count contrast through the
  gateway + the suite-wide envelope migration with numerically unchanged row counts). T1 is the seam
  they both stand on.
- **The fail-closed edge to eyeball** — the **allowlist-fallback path**: the page must be sliced from
  the batch-filtered survivors (never the raw candidates), `totalElements` must be the survivor count,
  and the candidate fetch must carry the sort (an unsorted fallback fetch would page a different
  sequence than the pure-SQL path — I3 is the trap-detector).
- **Standalone-value subset** — T1 + T2 (the paged library seam + its Postgres proof) land green alone
  with no app change, if the window is short.
- **Rig / e2e specifics** — mint tokens **in-network** (issuer `keycloak:8888`); `./deploy.sh build`
  to force the new app images into the pods; no OPA restart needed (zero Rego changes) but the README
  gotcha stands if anything is edited; the pagination fixtures are a **dedicated namespaced set** in
  the fixture registry — do not fatten shared fixtures (other matrices pin exact counts on them).
- **CI does not run the rig yet** — the newman matrix is a local/manual gate; a compose-up→newman CI
  job is a tracked follow-up.
- **Workflow-as-artifact:** keep this prompt verbatim; the `STATUS-0N.md` notes record each ticket's
  outcome. Move the folder to `docs/to-do/implemented/` on ship.
