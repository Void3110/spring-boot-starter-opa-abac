---
tags:
  - status/planned
  - type/project
  - area/api
  - area/spring
  - area/architecture
---

# REST API refinement — the error contract (Phase 5.9) — autonomous implementation prompt

> **What this is.** A self-contained prompt for implementing the **error contract** slice autonomously,
> ticket by ticket, with an architecture-review gate and a checkpoint after each ticket. The design and
> work list it refers to live alongside it in this folder.
>
> **Before you run it:** create the branch — `git checkout -b feature/void3110/rest-api-refinement` off a
> clean `main`. Confirm `git config --local user.email` is `void31102025@gmail.com`. Then paste the
> **PROMPT** section below to the agent.

---

## PROMPT

You are implementing the **REST API refinement — the error contract (Phase 5.9)** on branch
`feature/void3110/rest-api-refinement`.

**The problem.** Both example services return errors today through a per-service `@RestControllerAdvice`
(`…/web/ApiExceptionHandler.java`) that builds a generated `ApiError {status, message, timestamp}` at
content type `application/json`. The statuses are right and there is **no fail-open** — but for a
*published* library two gaps remain: the body is **not machine-actionable** (a consumer must branch on the
human `message` string; two `422`s — a bad tag value vs the role-subset rule — are indistinguishable), and
it is **not `application/problem+json`** (RFC-7807, the de-facto standard). This slice makes the error body
canonical RFC-7807 `problem+json` with a typed, **library-owned** `errorCode` vocabulary, plus two cheap
ride-alongs: a `Location` header on every `201`, and one-line intent comments at the deliberately-ungated
user-service bootstrap mutations. **This is a contract-SHAPE change, not a decision-logic one** — every
error path lands on the **same status** as before, now carrying a typed `errorCode`. **Scope boundary —
what is NOT in this slice:** **pagination** (a list-shape change → its own slice **5.95**);
**`actions`/`pageActions`** affordance metadata (→ **Phase 6**); a **hosted problem-type registry** (`type`
is a stable, relative, opaque id — `/problems/<kebab>` — not dereferenced); **any authorization behavior
change** (the ungated bootstrap mutations stay ungated — only commented); **`opa-abac-core` is not touched
at all** (it stays Spring-free — the contract is HTTP/Spring-MVC-shaped and lives in
`opa-abac-spring-security` + the two example web layers).

Implement the core work directly. Do not delegate the implementation to a sub-agent.

### Read before you start (in order)

1. `REST-API-REFINEMENT.md` (this folder's index) — what this slice delivers, the file glossary, the
   ticket status table, the critical path, conventions.
2. `00-DESIGN.md` — the `ProblemDetail` body (five RFC-7807 members + `errorCode`/`timestamp` extensions),
   the `ApiErrorCode` interface + `LibraryErrorCode` base enum + per-app enums, the **clean replacement**
   of `ApiError`, the `Location` + intent-comment ride-alongs, the **fail-closed posture (§7)**, and
   considered-&-rejected.
3. `01-DECOMPOSITION.md` — the **5** tickets, each with Goal / Deliverables / Acceptance /
   What-NOT-to-touch. **This is your work list.**
4. **The pinned decisions** — ADR `0011-error-contract-problem-json` (every fork: depth = minimal-superset;
   clean-replacement; library-owned `ApiErrorCode` interface + base enum + per-app enums; semantic
   granularity; `errorCode` typed first-class in the OpenAPI schema via a library-shipped `ProblemDetail`
   DTO). Constrained by ADR `0006-three-layer-enforcement-model` (the app layer whose error responses this
   shapes — unchanged).
5. `10-QA-TEST-CASES.md` — the unit / integration / e2e cases your work must satisfy (U1–U6, I1–I7,
   C1–C3, E1–E5 + the fail-closed checklist).
6. **Context you will be checked against** in the review gate (step 5): the two shipped advices
   `example-catalog-management-service/.../web/ApiExceptionHandler.java` +
   `example-user-management-service/.../web/ApiExceptionHandler.java` (the exact exception→status maps you
   must preserve); the generated `ApiError` schema in `…/openapi/catalog-api.yaml` (~line 457) +
   `…/openapi/user-mgmt-api.yaml` (~line 849); the shipped `opa-abac-spring-security` package
   (`dev.dmitriikonovalov.opaabac.security`: `OpaAuthorizationManager`,
   `OpaPreAuthorizeAuthorizationManager`, `AbacFilter` — where 403 is raised today); the guide
   `docs/guides/REST-API-DESIGN.md` (§3 status codes, §4 bodies, §6 error handling, §9 targets); the
   shipped multi-service slice `docs/to-do/implemented/TAG-DICTIONARY/` (the contract-touching,
   two-service, codegen-coordinated pattern).
7. Root `CLAUDE.md` — the **IP boundary** (clean-room: original names only) and the **commit identity**
   rule (`Void3110 <void31102025@gmail.com>`).
8. `infra/README.md` — the local rig (needed for the e2e ticket), incl. the **in-network token caveat**
   (issuer `keycloak:8888`) and the **"`./deploy.sh build` to force new app code into the pods"** gotcha.
9. **Prime Mulch:** `ml prime opa-abac` + `ml prime api-design` + the directly-relevant record ids:
   `mx-e4f34a` (the REST conventions + design-review baseline for both services), `mx-b0b966` (the vanilla
   `org.openapi.generator` `generatorName=spring`, interface-only codegen convention — how the spec
   regenerates the model). Also `ml prime autonomous-runs` and skim the synthesis record: the one recurring
   pause class is "a fail-open/contract semantic left unpinned" — **ADR 0011 has deliberately pinned all
   five** (depth = minimal-superset, clean-replacement, library-owns-the-vocabulary, semantic-granularity,
   typed-in-spec); hold that line and do not re-open them mid-run.

### Per-ticket loop (tickets T1 → T5, IN ORDER; T1 is independently landable; T2 ∥ T3 once T1 lands)

For each ticket do ALL of the following, in order, and **STOP at the checkpoint before the next**:

1. **Prime for the files you're about to touch.** `ml prime --files <path>`, and re-read that ticket's
   section in `01-DECOMPOSITION.md`.

2. **Build the deliverables** exactly as `01-DECOMPOSITION.md` lists them — exact classes, packages
   (`dev.dmitriikonovalov.opaabac.security` for the library; `dev.dmitriikonovalov.example.{catalog,
   usermgmt}.web` for the services; the generated `…openapi.model.ProblemDetail`), mappings, spec edits.
   Match the surrounding code's naming and idioms. **Clean-room:** no proprietary names anywhere.

3. **Write/extend the tests** for the ticket (the relevant U*/I*/C*/E* cases from `10-QA-TEST-CASES.md`).
   The library unit tests are plain JUnit + AssertJ (mapping + serialization — no DB). The per-service
   slice tests are **MockMvc / `@WebMvcTest`** (no Postgres needed for these). The full `./gradlew build`
   still runs the existing **Testcontainers ITs** against **real Postgres** (never H2) — keep them green.
   Policies are not touched in this slice (no rego change).

4. **Compile + run unit tests until green.** `./gradlew :<module>:test` (and `./gradlew build` for the
   per-service tickets so codegen + the existing ITs run). Fix-until-green.

5. **★ ARCHITECTURE REVIEW + REFACTOR — the gate before integration/e2e validation.** Once unit tests
   pass, do NOT advance yet. Run a focused self-review, then refactor and re-test:
   - **Fail-closed check (the load-bearing invariant for this slice):** **no error path changes which
     status it returns or widens access** — the only change is the body shape + a typed `errorCode`.
     Concretely: every exception maps to the **same** status as today (404/400/403/409/422/503); the
     403 deny path (`@OpaPreAuthorize`/`OpaAuthorizationManager`) still denies, now rendering
     `ACCESS_DENIED` `problem+json`; the catalog 503 still rejects the untagged write; no gate is added or
     removed. (The review confirmed no fail-open exists and this slice introduces none.)
   - **Boundary / additivity check:** `opa-abac-core` is **untouched** (grep the diff — no
     `opa-abac-core/` file changes); the library change is additive (T1 ships only new types — the example
     advices still compile against the old `ApiError` until T2/T3 adopt). Name the one mechanical cost per
     service: removing `ApiError` from the spec regenerates the model, so that module's
     `ApiExceptionHandler` + any test referencing `ApiError` must switch to `ProblemDetail` **in the same
     commit**.
   - **Module-layer separation:** the `ApiErrorCode` interface + `LibraryErrorCode` + the `ProblemDetail`
     carrier + the advice base/mapping helper live in `opa-abac-spring-security`; each app's own enum +
     its remapped advice + its spec live in that app's module; `opa-abac-core` learns nothing of the wire
     contract.
   - **Pattern-reuse check:** match the shipped **two-service, codegen-coordinated** pattern from
     TAG-DICTIONARY (a spec change regenerates the model; the build proves the contract); reuse the
     existing advice idioms (the `@ExceptionHandler` grouping) — remap them, do not reinvent the handler
     structure. Use the **library-shipped `ProblemDetail` DTO**, NOT Spring's
     `org.springframework.http.ProblemDetail` (whose untyped `properties` map would lose the typed
     `errorCode` — ADR 0011 §5).
   - **SOLID / decomposition** — cohesive (SRP: the helper builds the body, the codes name the failures,
     the advice routes exceptions); depends on the `ApiErrorCode` interface (DIP — the app enum plugs in);
     anything to split/simplify?
   - **Apply** the refactoring the review surfaces, then **re-run the unit tests** to confirm green.
   - Write a short note of what the review found + what you refactored into `STATUS-0N.md`. If it found
     nothing substantive, say so explicitly — **do not invent churn.**

6. **Integration / e2e validation (MANDATORY for the relevant tickets).**
   - **T2 / T3:** the MockMvc / `@WebMvcTest` slice IT — a representative error per status the service
     emits (404/400/403/409/422/503) returns a well-formed `application/problem+json` with the **expected
     `errorCode`** (named per case from `10-QA-TEST-CASES.md`); a `201` carries
     `Location: /api/v1/<collection>/<id>`. `./gradlew :<module>:build` runs codegen + the existing
     Testcontainers ITs (keep them green). Fix-until-green.
   - **T4 (e2e):** bring the rig up — `./profile.sh up` first (base Postgres), then
     `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2`; `./deploy.sh build` to **force** the
     new app code into the pods. Then `cd scripts/postman` and run the **extended** matrices' runners
     (e.g. `./run-matrix.sh` / `./run-team-matrix.sh` / `./run-tag-matrix.sh` — whichever you extended).
     Honor the in-network token caveat (mint tokens with issuer `keycloak:8888`); keep runtime-captured
     ids in **collection** variable scope. **Extend** existing collections — **no new collection file.**
     Fix-until-green.

7. **Update documentation (after each ticket).** Tick the ticket in the `REST-API-REFINEMENT.md` status
   table; record real values/decisions in `STATUS-0N.md` (e.g. the final exception→`errorCode` map you
   chose per service). The docs ticket (T5) moves the error-contract + `Location` items in
   `docs/guides/REST-API-DESIGN.md` from §9 *Targets* into the adopted §3/§4/§6 + the §10 checklist, and
   flips `POC-ROADMAP.md` Phase 5.9 to done. Root/project `CLAUDE.md` only if a new build/run step matters
   (none expected).

8. **Mulch expertise check (after each ticket).** `ml prime`/skim, then record any genuine reusable
   insight (`ml record opa-abac --type <pattern|decision|failure|reference> …` — e.g. the library-owned
   `ApiErrorCode` interface + per-app enum split; the library-shipped `ProblemDetail` vs Spring's untyped
   one; the `AccessDeniedException`→403 mapping in the shared advice base) and `ml sync`. **Before
   `ml sync`, `git restore --staged .`** so the sync commit touches `.mulch/` **only** (the swept-staged
   trap). Skip recording only if nothing is non-obvious.

9. **Commit** — one focused commit on this branch (code + tests + docs + the `STATUS-0N.md` note
   together). Identity `Void3110 <void31102025@gmail.com>`. Conventional subject
   (`feat(spring-security): …`, `feat(catalog): …`, `feat(user-svc): …`, `test(e2e): …`, `docs: …`). A
   `Co-Authored-By: Claude` trailer is welcome.

10. **CHECKPOINT — STOP and report.** Summarize what shipped, paste the unit + integration/e2e summary,
    **summarize the review findings + the refactoring you applied** (step 5), list docs updated, and note
    any open question you resolved (e.g. which conflicts you split into distinct app codes). Then proceed
    to the next ticket. **Do not batch tickets without a checkpoint.**

### Permissions / autonomy granted (do these WITHOUT asking)

- Create/modify library code (`opa-abac-spring-security`), example code
  (`example-catalog-management-service`, `example-user-management-service` — incl. the OpenAPI specs),
  tests, docs in this folder + the guides, the `scripts/postman/` suite, and Mulch — all on this branch.
- Stand up / tear down / reseed the local rig (`./profile.sh`, `./deploy.sh`,
  `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 …`); reset fixtures; rebuild images (`./deploy.sh build`); restart
  services; drop/recreate the **local** schema if needed.
- Fix any issue your own validation reveals (compile, unit, IT, e2e, refactor). Iterate until green.
- Commit per ticket on this branch.

### Hard rules

- **Report at every checkpoint and continue.** Don't batch tickets.
- **The architecture-review + refactor step (5) is NOT optional and happens BEFORE integration/e2e
  validation** — unit green → review → refactor → re-test → then ITs/e2e. Document what it found.
- **Fix-until-green within the ticket.** Only STOP mid-ticket if genuinely *blocked*: the same root cause
  survives ≥3 focused attempts, OR a design decision the docs don't cover is needed, OR a local
  prerequisite is unrecoverable.
- **Fail-closed is the load-bearing invariant.** **No error path changes which status it returns or widens
  access; the only change is the body shape + a typed `errorCode` — the review confirmed no fail-open
  exists and this slice introduces none.** Every exception maps to the SAME status as today; the 403 deny
  still denies (now `ACCESS_DENIED` `problem+json`); the catalog 503 still rejects the untagged write; no
  gate is added or removed.
- **Clean replacement, not a hybrid.** `ApiError` is **removed** from both specs; the body carries `detail`
  (no legacy `message` alongside it); content type is `application/problem+json` on every error response —
  canonical RFC-7807 + the two named extensions (`errorCode`, `timestamp`) only.
- **The vocabulary is library-owned-and-extensible, semantic, and typed in the contract.** The library
  ships `ApiErrorCode` (interface) + `LibraryErrorCode` (its own codes); each app ships its own enum
  implementing the same interface; one code per **distinct, client-actionable** failure (not one per
  status); `errorCode` is a **typed `enum` member of each spec's `ProblemDetail` schema** — use the
  **library-shipped `ProblemDetail` DTO**, never Spring's untyped `org.springframework.http.ProblemDetail`.
- **Spec-first** — the `ProblemDetail` schema change lands in the OpenAPI spec; codegen regenerates the
  model and the build proves the contract (**codegen drift = build break**).
- **`opa-abac-core` stays Spring-free and is NOT touched** — the error contract is HTTP/Spring-MVC-shaped;
  it lives in `opa-abac-spring-security` + the example web layers. Grep the diff: no `opa-abac-core/`
  changes.
- **The intent comments are comment-only** — `UserController` + `POST /teams` stay ungated by design; add
  the one-line `bootstrap: pre-membership, authenticated-only by design` comment and **NO
  `@OpaPreAuthorize`**.
- **Clean-room IP boundary** — never introduce proprietary names, package names, comments, or source.
- **Do NOT push, open PRs, or touch `main`.** Local + this branch only. The maintainer pushes.

### At the end of the run (flow phase ④)

- Run a post-run `/deep-review` of the branch vs `main` (the deep-review skill); fix anything real it
  finds in a follow-up commit on the branch.
- Record one **`autonomous-runs`** reference record with `--outcome-status <success|partial|failure>`
  capturing OUTCOME + PAUSE-CAUSE · CHECKPOINT/TICKET FRICTION · PLANNING-GAP→FIX · QA (see the repo
  `CLAUDE.md` `autonomous-runs` section). `git restore --staged .` before `ml sync`.

---

## Operator notes (not part of the prompt)

- **The headline tickets** — **T1** (the library vocabulary + carrier + advice base — the reusable
  foundation; its unit tests prove the mapping + serialization + the `AccessDeniedException`→403 seam) and
  **T2 + T3** (each service's spec-replace + advice-remap + `Location` + the MockMvc IT proving a
  well-formed `problem+json` with the right `errorCode` per status). Their passing tests justify the whole
  contract.
- **The fail-closed edge to eyeball** — this is a contract-SHAPE change, so the risk is *accidentally
  changing a status or widening access while remapping*. Two specifics: (1) make sure the 403 deny path
  still **denies** (the new `AccessDeniedException`→`ACCESS_DENIED` mapping must not swallow the deny into a
  200/empty body — it renders the 403, it does not authorize); (2) the catalog `TagDefinitionFetchException`
  must still land on **503 and not store** the resource — the remap changes only the body, never the
  reject. The MockMvc ITs (I2c, I3/I6) pin both.
- **Standalone-value subset** — **T1** (the `ApiErrorCode` interface + `LibraryErrorCode` + `ProblemDetail`
  carrier + advice base + unit tests) is reusable library value with no app dependency; it lands
  independently if the window is short.
- **Rig / e2e specifics** — full two-service rig (`ENABLE_OIDC=1 ENABLE_USER_SERVICE=1`); `./profile.sh up`
  before `./deploy.sh up`; **`./deploy.sh build` to force the new app code into the pods** (else the e2e
  hits the old `ApiError` body and the new assertions fail confusingly); mint tokens in-network (issuer
  `keycloak:8888`); keep runtime ids in **collection** variable scope (env scope shadows → empty-id URLs).
  **Extend** the existing matrices' assertions — no new collection.
- **The MockMvc slice tests don't need Postgres**, but the full `./gradlew build` runs the existing
  Testcontainers ITs — keep them green (the podman `DOCKER_HOST` + `TESTCONTAINERS_RYUK_DISABLED=true`
  caveat is **environment, not code**).
- **CI does not run the rig yet** — the newman matrices are a local/manual gate; a compose-up→newman CI
  job is a tracked follow-up.
- **Workflow-as-artifact:** keep this prompt verbatim; the `STATUS-0N.md` notes record each ticket's
  outcome. Move the folder to `docs/to-do/implemented/REST-API-REFINEMENT/` on ship.
