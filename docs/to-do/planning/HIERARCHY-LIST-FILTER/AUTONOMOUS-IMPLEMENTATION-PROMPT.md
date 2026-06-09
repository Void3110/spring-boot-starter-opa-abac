---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/spring-data
  - area/opa
---

# Hierarchy-aware list filter (Slice 5.5-B) — autonomous implementation prompt

> **What this is.** A self-contained prompt for implementing the hierarchy-aware list filter autonomously,
> ticket by ticket, with an architecture-review gate and a checkpoint after each ticket. The design and work
> list it refers to live alongside it in this folder.
>
> **Before you run it:** create the branch — `git checkout -b feature/void3110/hierarchy-list-filter` off a
> clean `main` (already created during planning — `git checkout feature/void3110/hierarchy-list-filter`).
> Confirm `git config --local user.email` is `void31102025@gmail.com`. Then paste the **PROMPT** section
> below to the agent.

---

## PROMPT

You are implementing the **hierarchy-aware list filter (Slice 5.5-B)** on branch
`feature/void3110/hierarchy-list-filter`.

**The problem.** A list endpoint today cuts rows by the **row's own JSONB tags** (the shipped Phase-5
partial-eval residual) AND-ed with the caller's path scope. A grant **inherited from an ancestor** — which
Slice 5.5-A made authorize a *single* `GET …/{id}` — does **not** widen the list, so the single-GET and the
list disagree. This slice makes an **ancestor grant widen which rows a list returns**: the OPA residual stays
**tag-only**, and hierarchy is a separate app-built `subtreeSpec` OR-ed in —
`combined = scope.and( tagResidual.or(subtreeSpec) ).and( notDenied )` — proven as a per-subject SQL row-set
difference through the gateway. **Headline:** hierarchy-aware row filtering, pushed into SQL, fail-closed —
the capstone of the hierarchy story after partial-eval. **Scope boundary — what is NOT in this slice:**
**root-only** subtree widening (mid-tree per-node grants are Phase 8 / ReBAC); **no** change to the tag-only
residual / `CompileResponseParser` / `ResidualSpecificationFactory` / closed operator set / `RoleDefinition`;
only the **scalar `abac_deny`** deny in SQL (richer deny models later); **`opa-abac-core` is not touched at
all**.

Implement the core work directly. Do not delegate the implementation to a sub-agent.

### Read before you start (in order)

1. `HIERARCHY-LIST-FILTER.md` (this folder's index) — what this slice delivers, the file glossary, the
   ticket status table, the critical path, conventions.
2. `00-DESIGN.md` — the `subtreeSpec` composition, the `subtreeOf` SPI (ltree pushdown / CTE bounded walk),
   `SubtreeSpecResolver`, the 4-arg overload, `notDenied`-as-SQL, the hierarchy-aware batch path, the
   **fail-closed audit (§9)**, and considered-&-rejected.
3. `01-DECOMPOSITION.md` — the **6** tickets, each with Goal / Deliverables / Acceptance /
   What-NOT-to-touch. **This is your work list.**
4. **The pinned decisions** — ADR `0010-hierarchy-aware-list-filter` (every list fork) + ADR
   `0008-hierarchical-resource-authorization` (the hierarchy model) + ADR `0005`/`0006` (the residual +
   three-layer model this composes with, kept untouched).
5. `10-QA-TEST-CASES.md` — the unit / integration / e2e cases your work must satisfy.
6. **Context you will be checked against** in the review gate (step 5): the **shipped** 5.5-A
   (`opa-abac-spring-data/.../data/hierarchy/`: `AncestorResolver`, `LtreeAncestorResolver`,
   `RecursiveCteAncestorResolver`, `HierarchicalAuthorizer`, `HierarchyLabels`, `LtreePathSource`,
   `AbstractHierarchicalEntity`) and the **shipped** Phase-5 (`.../data/filter/`: `AbacQueryService`,
   `ResidualSpecificationFactory`, `JsonPathDialect`); `infra/opa/policies/category.rego` (the
   `final_allow`/`denied`/`filter` rules); the guides `docs/guides/PARTIAL-EVALUATION-FILTERING.md` +
   `HIERARCHICAL-AUTHORIZATION.md`.
7. Root `CLAUDE.md` — the **IP boundary** (clean-room: original names only) and the **commit identity** rule
   (`Void3110 <void31102025@gmail.com>`).
8. `infra/README.md` — the local rig (needed for the e2e ticket), incl. the **in-network token caveat** and
   the **"restart OPA after editing a policy"** gotcha.
9. **Prime Mulch:** `ml prime opa-abac` + `ml prime spring-data-filtering` + the directly-relevant record
   ids: `mx-4e6071` (the AbacQueryService seam + AND-with-scope + fullySupported), `mx-6876cd` (residual →
   JPA Specification over JSONB, scalar-vs-array agreement), `mx-cbd39e` (the role-def-only `filter` rule),
   `mx-8bd79f` (the list-filter e2e matrix + rig gotchas), `mx-a932a0` (the compile→DNF fail-closed parser).
   Also `ml prime autonomous-runs` and skim the synthesis record (`mx-7e0a17`): the one recurring pause class
   is "a fail-open/contract semantic left unpinned" — **this design has deliberately pinned all four**
   (root-only, `notDenied`-outside-the-OR, batch-carries-ancestors, `subtreeOf`-fail-closed); hold that line.

### Per-ticket loop (tickets T1 → T6, IN ORDER; T1 is independently landable)

For each ticket do ALL of the following, in order, and **STOP at the checkpoint before the next**:

1. **Prime for the files you're about to touch.** `ml prime --files <path>`, and re-read that ticket's
   section in `01-DECOMPOSITION.md`.

2. **Build the deliverables** exactly as `01-DECOMPOSITION.md` lists them — exact classes, packages
   (`dev.dmitriikonovalov.opaabac.data.{hierarchy,filter}`, `dev.dmitriikonovalov.example.catalog.*`),
   mappings, rego rules. Match the surrounding code's naming and idioms. **Clean-room:** no proprietary names
   anywhere.

3. **Write/extend the tests** for the ticket (the relevant U*/I*/E* cases from `10-QA-TEST-CASES.md`).
   Core/client tests use an **in-process `com.sun.net.httpserver.HttpServer` stub** (no WireMock).
   Persistence/IT tests run against **real Postgres via Testcontainers** (never H2 — `path <@` and `jsonb`
   are Postgres-only). Policies use `opa test` (+ `opa eval --partial` to confirm the residual stays
   tag-only where relevant).

4. **Compile + run unit tests until green.** `./gradlew :<module>:test` (and `./gradlew build` for the
   example/IT tickets). Fix-until-green.

5. **★ ARCHITECTURE REVIEW + REFACTOR — the gate before integration/e2e validation.** Once unit tests pass,
   do NOT advance yet. Run a focused self-review, then refactor and re-test:
   - **Fail-closed check:** every error/timeout/missing-input path lands on **narrower/empty**, never wider.
     Concretely: a failed/too-deep/cyclic subtree resolution → empty `subtreeSpec` → tag-only result;
     `subtreeOf` returns an always-false predicate on breach; `notDenied` is never silently `TRUE` for an
     inexpressible deny; the batch path only **removes** rows; `subtreeSpec` is OR-ed **inside**
     `scope.and(...)` so the widening never escapes the caller's scope.
   - **Boundary / additivity check:** `opa-abac-core` is **untouched** (grep the diff — no `opa-abac-core/`
     file changes); the 3-arg `findAuthorized` is byte-compatible (an old caller compiles + behaves
     identically); `subtreeOf` is additive (both shipped impls implement it); the residual model /
     `ResidualSpecificationFactory` / operator set / `RoleDefinition` are byte-for-byte unchanged. Name the
     one mechanical cost (the `AbacQueryService` constructor gains an `AncestorResolver`; every construction
     site updated in the same commit).
   - **Module-layer separation:** the subtree predicate + resolution live in `data.hierarchy`; the
     composition lives in `data.filter` (`AbacQueryService`); the example only *calls* the 4-arg overload. No
     OPA-wire knowledge in spring-data beyond what's shipped.
   - **Pattern-reuse check:** match `mx-4e6071` (AND-with-scope, fullySupported, the batch finisher),
     `mx-6876cd` (bound-literal JSONB Criteria via `JsonPathDialect`), and 5.5-A's resolver idioms — do not
     reinvent them.
   - **SOLID / decomposition** — cohesive (SRP), depends on interfaces (DIP); anything to split/simplify?
   - **Apply** the refactoring the review surfaces, then **re-run the unit tests** to confirm green.
   - Write a short note of what the review found + what you refactored into `STATUS-0N.md`. If it found
     nothing substantive, say so explicitly — **do not invent churn.**

6. **Integration / e2e validation (MANDATORY for the relevant tickets).**
   - **T1 / T4:** Testcontainers ITs against real Postgres asserting the exact surviving **row sets**
     (`subtreeOf` for both impls; the `notDenied` narrowing; the **AND-with-scope no-leak**; the
     **re-parent-on-list** move). `opa test infra/opa/policies/` to confirm no rego regression.
     Fix-until-green.
   - **T6 (e2e):** bring the rig up (`./profile.sh up` first, then
     `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2`; `./deploy.sh build` to force new app code
     into the pods), then `cd scripts/postman && ./run-<matrix>.sh`. Honor the in-network token caveat
     (issuer `keycloak:8888`), restart/redeploy OPA after a rego edit (confirm via `POST /v1/compile`), keep
     runtime ids in **collection** variable scope. Fix-until-green.

7. **Update documentation (after each ticket).** Tick the ticket in the `HIERARCHY-LIST-FILTER.md` status
   table; record real values/decisions in `STATUS-0N.md`. The e2e ticket (T6) writes/reconciles
   `docs/guides/PARTIAL-EVALUATION-FILTERING.md` (the hierarchy-aware list section) +
   `docs/guides/HIERARCHICAL-AUTHORIZATION.md` (the list analogue) + `E2E-TESTING.md` +
   `scripts/postman/README.md`. Root/project `CLAUDE.md` only if a new build/run step matters.

8. **Mulch expertise check (after each ticket).** `ml prime`/skim, then record any genuine reusable insight
   (`ml record opa-abac --type <pattern|decision|failure|reference> …`) and `ml sync`. **Before `ml sync`,
   `git restore --staged .`** so the sync commit touches `.mulch/` **only** (the swept-staged trap). Skip
   recording only if nothing is non-obvious.

9. **Commit** — one focused commit on this branch (code + tests + docs + the `STATUS-0N.md` note together).
   Identity `Void3110 <void31102025@gmail.com>`. Conventional subject (`feat(spring-data): …`,
   `feat(example): …`, `test(spring-data): …`). A `Co-Authored-By: Claude` trailer is welcome.

10. **CHECKPOINT — STOP and report.** Summarize what shipped, paste the unit + integration/e2e summary,
    **summarize the review findings + the refactoring you applied** (step 5), list docs updated, and note any
    open question you resolved. Then proceed to the next ticket. **Do not batch tickets without a
    checkpoint.**

### Permissions / autonomy granted (do these WITHOUT asking)

- Create/modify library code (`opa-abac-spring-data`, `opa-abac-spring-boot-starter`), example code
  (`example-catalog-management-service`), rego, tests, docs in this folder + the guides, the
  `scripts/postman/` suite, and Mulch — all on this branch.
- Stand up / tear down / reseed the local rig (`./profile.sh`, `./deploy.sh`,
  `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 …`); reset fixtures; rebuild images (`./deploy.sh build`); restart
  OPA; drop/recreate the **local** schema if needed.
- Fix any issue your own validation reveals (compile, unit, IT, e2e, refactor). Iterate until green.
- Commit per ticket on this branch.

### Hard rules

- **Report at every checkpoint and continue.** Don't batch tickets.
- **The architecture-review + refactor step (5) is NOT optional and happens BEFORE integration/e2e
  validation** — unit green → review → refactor → re-test → then ITs/policy/e2e. Document what it found.
- **Fix-until-green within the ticket.** Only STOP mid-ticket if genuinely *blocked*: the same root cause
  survives ≥3 focused attempts, OR a design decision the docs don't cover is needed, OR a local prerequisite
  is unrecoverable.
- **Fail-closed is the load-bearing invariant.** No code path may return more/wider rows on an error/missing
  input than on success. A failed/too-deep/cyclic subtree resolution collapses `subtreeSpec` to an empty
  (false) predicate → the result falls back to the **narrower** tag-only filter, never wider. `notDenied` is
  **never** silently `TRUE` for a deny the SQL can't express (route to the allowlist batch). The batch path
  only **removes** rows.
- **Root-only widening.** The only subtree-root is the list's **governing root**, resolved **once**; do
  **not** add per-node / mid-tree grant resolution (that is Phase 8 / ReBAC).
- **`subtreeSpec` is OR-ed INSIDE `scope.and(...)`, `notDenied` is AND-ed OUTSIDE the OR.** Never fold deny
  into the OR-ed residual (it would re-admit denied rows). Never `findAll(authzSpec)` without the scope — the
  widening must never escape the caller's `catalogId` scope (no cross-catalog leak).
- **Additive / boundary:** **`opa-abac-core` is not touched** (the 4-arg `Resource(...,ancestors)` ctor
  already exists from 5.5-A — consume it). The 3-arg `findAuthorized` stays byte-compatible. `subtreeOf` is
  additive — **both** shipped impls (`LtreeAncestorResolver`, `RecursiveCteAncestorResolver`) implement it.
  The shipped **residual model / `CompileResponseParser` / `ResidualSpecificationFactory` / closed operator
  set / `RoleDefinition` are untouched** (hierarchy is an app-built `subtreeSpec` only). Inheritance stays
  **opt-in, default-off**.
- **`ddl-auto: validate` must pass** — there is **no schema change** in this slice (5.5-A added the ltree
  `path` column + GIN index); a clean boot is the proof. **No OpenAPI shape change** (the list response is the
  same array, just more rows).
- **Real Postgres for ITs** — never H2 (`path <@`/`jsonb` are Postgres-only). The spring-data test task needs
  `TESTCONTAINERS_RYUK_DISABLED=true` + the resolved podman `DOCKER_HOST` (copy the example modules'
  `resolveDockerHost()`); `@EnableJpaAuditing` `DateTimeProvider` returns `OffsetDateTime`.
- **Clean-room IP boundary** — never introduce proprietary names, package names, comments, or source.
- **Do NOT push, open PRs, or touch `main`.** Local + this branch only. The maintainer pushes.

### At the end of the run (flow phase ④)

- Run a post-run `/deep-review` of the branch vs `main` (the deep-review skill); fix anything real it finds
  in a follow-up commit on the branch.
- Record one **`autonomous-runs`** reference record with `--outcome-status <success|partial|failure>`
  capturing OUTCOME + PAUSE-CAUSE · CHECKPOINT/TICKET FRICTION · PLANNING-GAP→FIX · QA (see the repo
  `CLAUDE.md` `autonomous-runs` section). `git restore --staged .` before `ml sync`.

---

## Operator notes (not part of the prompt)

- **The headline tickets** — **T3** (the `scope.and(tagResidual.or(subtreeSpec)).and(notDenied)` composition
  + the hierarchy-aware batch path) and **T4** (the IT proving widening + `notDenied` + **no-leak** +
  re-parent-on-list against real Postgres). Their passing tests justify the whole design. T1 (`subtreeOf`)
  is the reusable foundation.
- **The fail-closed edge to eyeball** — two places this slice would silently leak if done carelessly:
  (1) `subtreeSpec` OR-ed **outside** `scope.and(...)` (would surface foreign-catalog rows — the no-leak IT
  I7 catches it); (2) `notDenied` folded **inside** the OR (the subtree branch would re-admit a denied row —
  I6/E3 catch it). Both are pinned in ADR 0010; the ITs are mandatory.
- **Standalone-value subset** — **T1** (the `subtreeOf` SPI + both impls + their ITs) is reusable library
  value with no app dependency; it lands independently if the window is short.
- **Rig / e2e specifics** — full two-service rig (`ENABLE_OIDC=1 ENABLE_USER_SERVICE=1`); `./profile.sh up`
  before `./deploy.sh up`; `./deploy.sh build` to force new app code; restart OPA after a rego edit (confirm
  via `POST /v1/compile`); mint tokens in-network (issuer `keycloak:8888`); keep runtime ids in **collection**
  variable scope (env scope shadows → empty-id URLs); `in` is reserved in OPA 1.x — don't name a test var
  `in`. Reuse the shipped `run-filter-matrix.sh` / `data-filter-matrix.postman_collection.json` shape.
- **CI does not run the rig yet** — the newman matrix is a local/manual gate; a compose-up→newman CI job is a
  tracked follow-up.
- **Workflow-as-artifact:** keep this prompt verbatim; the `STATUS-0N.md` notes record each ticket's outcome.
  Move the folder to `docs/to-do/implemented/HIERARCHY-LIST-FILTER/` on ship.
