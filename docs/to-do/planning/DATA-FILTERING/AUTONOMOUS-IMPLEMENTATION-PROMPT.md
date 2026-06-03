---
tags:
  - status/planned
  - type/project
  - area/spring-data
  - area/abac
  - area/opa
---

# Data filtering — autonomous implementation prompt

> **What this is.** A self-contained prompt for implementing the Phase-5 data-filtering slice
> autonomously, ticket by ticket, with an architecture-review gate and a checkpoint after each ticket.
> The design and work list it refers to live alongside it in this folder.
>
> **Before you run it:** create the branch — `git checkout -b feature/void3110/data-filtering` off a
> clean `main`. Confirm `git config --local user.email` is `void31102025@gmail.com`. Then paste the
> **PROMPT** section below to the agent.

---

## PROMPT

You are implementing the **data-filtering slice** (OPA partial evaluation → JPA `Specification` row
filtering, plus batch evaluation) on branch `feature/void3110/data-filtering`.

**The problem.** Every authorization decision in this repo so far is **single-resource**
(`@OpaPreAuthorize` — "may this subject do X to *this one* resource?"). That's wrong for **list**
endpoints, where the question is "of the N rows, *which* may this subject see?" The catalog's list
endpoints currently do one coarse type-level `:read` check and then return **every** row. You are
building the two mechanisms that answer the list question without N round-trips or a fetch-all-then-
filter: (A) ask OPA to **partially evaluate** the policy with the subject known and the row unknown
(the Compile API), translate the **residual** into a JPA `Specification`, and push it into the SQL
`WHERE` clause over the existing `tags` JSONB column; and (B) a **batch** decision call that finishes
the residue which doesn't reduce to SQL, in one round-trip. This is the headline differentiator of the
library.

Implement the core work directly. Do not delegate the implementation to a sub-agent.

### Read before you start (in order)

1. `DATA-FILTERING.md` (this folder's index) — what this slice delivers, the file glossary, the ticket
   status table, the critical path, conventions.
2. `00-DESIGN.md` — the design: the Compile-API call + `unknowns`, the residual model (DNF
   `PartialResult`/`Conjunction`/`Condition`), the `Specification` translation over JSONB
   (`jsonb_extract_path_text` / the `?` existence op), the batch path, the rego `filter`/`bulk`
   entrypoints, the **fail-closed** posture, the **unsupported-residual → deny-or-batch** safety
   property, and considered-&-rejected.
3. `01-DECOMPOSITION.md` — the seven tickets, each with Goal / Deliverables / Acceptance /
   What-NOT-to-touch. **This is your work list.**
4. `10-QA-TEST-CASES.md` — the unit / integration / e2e cases your work must satisfy.
5. **Context you will be checked against** in the review gate (step 5): the shipped
   [[TAG-DICTIONARY]] slice (`infra/opa/policies/category.rego` — the `tags_satisfied` shape you
   generalize), the [[LIBRARY-SPINE]] `HttpOpaClient` (the fail-closed JDK-client pattern you extend),
   and [[DOMAIN-MODEL-FOUNDATION]] (`AbstractSecuredEntity` / `ResourceTags` / the `tags` JSONB column +
   GIN index you filter over). The e2e details are in `docs/guides/E2E-TESTING.md`.
6. Root `CLAUDE.md` — the **IP boundary** (clean-room: original names only) and the **commit identity**
   rule (`Void3110 <void31102025@gmail.com>`).
7. `infra/README.md` — the local rig (needed for the e2e ticket), including the in-network token caveat
   and the "restart OPA after editing a policy" gotcha.
8. **Prime Mulch:** `ml prime opa-abac` and `ml search "partial eval data filtering specification"`
   (records mx-15ee3e, mx-666644, mx-7d3605 are directly relevant); skim any client / rego / JSONB
   records.

### Per-ticket loop (tickets T1 → T7, IN ORDER; T2 may land alongside T1)

For each ticket do ALL of the following, in order, and **STOP at the checkpoint before the next**:

1. **Prime for the files you're about to touch.** `ml prime --files <path>` for the specific module or
   class, and re-read that ticket's section in `01-DECOMPOSITION.md`.

2. **Build the deliverables** exactly as `01-DECOMPOSITION.md` lists them — exact classes, packages
   (`dev.dmitriikonovalov.opaabac.{core,data,autoconfigure}` for the library;
   `dev.dmitriikonovalov.example.catalog.*` for the app), the rego `filter`/`bulk` rules, mappings.
   Match the surrounding code's naming and idioms. **Clean-room:** no proprietary names anywhere.

3. **Write/extend the tests** for the ticket (the relevant U*/I*/E* cases from `10-QA-TEST-CASES.md`).
   Core tests use an **in-process `com.sun.net.httpserver.HttpServer` stub** (no WireMock). The
   `Specification` translation gets BOTH a unit test (Criteria capture) AND a **Testcontainers IT
   against real Postgres + JSONB** (never H2). The rego gets `opa test` + `opa eval --partial` cases.

4. **Compile + run unit tests until green.** `./gradlew :<module>:test` (and `./gradlew build` for the
   example/IT tickets). Fix-until-green.

5. **★ ARCHITECTURE REVIEW + REFACTOR — the gate before integration/e2e validation.** Once unit tests
   pass, do NOT advance yet. Run a focused self-review, then refactor and re-test:
   - **Additivity / boundary:** `compile`/`allowAll` are purely additive to `OpaClient`; `allow` and
     the whole `@OpaPreAuthorize` path are **byte-for-byte unchanged** (prove with
     `git diff --name-only` on the security module — it must be untouched by T1–T5). `opa-abac-core`
     carries **no** JPA/Spring import (the residual model + Compile-API call are Spring-free).
   - **Fail-closed, every layer:** compile error → `DENY_ALL` (empty page); batch error → all-false;
     an unsupported residual lands on **deny** or on an **exact batch re-check** — never on
     "return everything". Grep your own code for any path that returns all rows on an error.
   - **Three-layer separation:** the Compile-API call + DNF model live in core; the JSONB→Criteria
     translation lives in spring-data; the rego `filter` entrypoint lives in the policy. No layer
     reaches across (core knows nothing of JPA; the factory knows nothing of OPA wire format).
   - **Pattern reuse:** the fail-closed JDK-client shape matches `HttpOpaClient.allow`; the scalar-vs-
     array tag handling matches the [[TAG-DICTIONARY]] `resource_tag_values` normalize; the bean
     conditionals match the existing starter idioms; the operator set stays **small and closed**.
   - **Apply** the refactoring the review surfaces, then **re-run the unit tests** to confirm green.
   - Write a short note of what the review found + what you refactored into `STATUS-0N.md`. If it found
     nothing substantive, say so explicitly — **do not invent churn.**

6. **Integration / e2e validation (MANDATORY for the relevant tickets).**
   - T3 / T4: `./gradlew :opa-abac-spring-data:test` incl. the **Testcontainers IT** that runs the
     generated `Specification` against real Postgres and asserts the row set. Fix-until-green.
   - T6: `./gradlew build` (incl. `ddl-auto: validate` boot + `opa test`). Fix-until-green.
   - T7: bring the rig up (`ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2`), then
     `cd scripts/postman && ./run-filter-matrix.sh`. Honor the **in-network token caveat** and
     **restart OPA after editing `category.rego`** (`--watch` doesn't always reload). Fix-until-green.

7. **Update documentation (after each ticket).**
   - This folder: tick the ticket in the `DATA-FILTERING.md` status table; record real
     values/decisions in the `STATUS-0N.md` (the residual shapes you actually parsed, the SQL the
     factory generated, the row counts the e2e asserted).
   - For the ticket that finalizes a guide topic, write/reconcile `docs/guides/DATA-FILTERING.md` and
     the partial-eval architecture section with what shipped.
   - Root/project `CLAUDE.md` only if a new build/run step matters for manual testing.

8. **Mulch expertise check (after each ticket).** `ml prime`/skim, then record any genuine reusable
   insight (`ml record opa-abac --type <pattern|decision|failure|reference> …`) and `ml sync`. Skip
   only if nothing is non-obvious. **Before `ml sync`, `git restore --staged .`** so the sync commit
   touches `.mulch/` **only** (the swept-staged trap).

9. **Commit** — one focused commit on this branch (code + tests + docs + the `STATUS-0N.md` note
   together). Identity `Void3110 <void31102025@gmail.com>`. Conventional subject
   `feat(data-filtering): <ticket summary>`. A `Co-Authored-By: Claude` trailer is welcome.

10. **CHECKPOINT — STOP and report.** Summarize what shipped, paste the unit + integration/e2e summary,
    **summarize the review findings + the refactoring you applied** (step 5), list docs updated, and
    note any open question you resolved. Then proceed to the next ticket. **Do not batch tickets
    without a checkpoint.**

### Permissions / autonomy granted (do these WITHOUT asking)

- Create/modify library code, example code, rego, tests, docs in this folder + the guides, the
  `scripts/postman/` suite, and Mulch — all on this branch.
- Stand up / tear down / reseed the local rig (`./profile.sh`, `./deploy.sh`, `ENABLE_OIDC=1 …`,
  `ENABLE_USER_SERVICE=1 …`); reset fixtures; rebuild images; restart OPA; drop/recreate the **local**
  catalog schema if a checksum reset is needed (local Postgres only).
- Fix any issue your own validation reveals (compile, unit, IT, e2e, refactor). Iterate until green.
- Commit per ticket on this branch.

### Hard rules

- **Report at every checkpoint and continue.** Don't batch tickets.
- **The architecture-review + refactor step (5) is NOT optional and happens BEFORE integration/e2e
  validation** — unit green → review → refactor → re-test → then ITs/e2e. Document what the review
  found each ticket.
- **Fix-until-green within the ticket** for compile/test/IT/e2e/config issues. Only STOP mid-ticket if
  genuinely *blocked*: the same root cause survives ≥3 focused attempts, OR a design decision the docs
  don't cover is needed, OR a local prerequisite is unrecoverable.
- **Fail-closed is the load-bearing invariant.** No code path may return more rows on an error than on
  success. Compile/transport/parse failure → `DENY_ALL`. Batch failure → all-false. An unsupported
  residual → deny (or, with `allowlistFallback`, an exact batch re-check over a recognized-conjunct
  pre-filter) — **never an unfiltered fetch**.
- **Clean-room IP boundary** — never introduce proprietary names, package names, comments, or source.
  Reference the prior platform only generically.
- **`opa-abac-core` stays Spring-free** — no JPA/Spring imports leak into the residual model or the
  Compile-API client.
- **Purely additive to `OpaClient`** — `allow` and the `@OpaPreAuthorize` path are unchanged; **no DB
  schema change** (the `tags` JSONB + GIN index already exist; `ddl-auto: validate` must stay clean);
  **no OpenAPI spec change** (the list response shape is the same — fewer items, not a new schema).
- **Do NOT push, open PRs, or touch `main`.** Local + this branch only. Report at checkpoints; the
  maintainer pushes.

---

## Operator notes (not part of the prompt)

- **The headline is T3 + T6 + T7.** A passing Testcontainers IT that runs the generated `Specification`
  against real Postgres + JSONB (T3), then the e2e matrix where two subjects get **different row sets**
  from the **same** endpoint with the cut happening in SQL (T7) — that pair is the proof that justifies
  the whole partial-eval design. Make the e2e assert row *counts* (the SQL cut), not just response
  shape.
- **The fail-closed edge is the thing to eyeball.** Read each `STATUS-0N.md` for the explicit statement
  that every error path lands on deny/batch, never on a wide fetch. This is where a partial-eval data
  filter silently leaks if done carelessly — the review gate exists largely for this.
- **T1 + T2 + T3 are standalone library value.** If only a short window is available, landing the core
  Compile-API client + the residual model + the `Specification` factory already delivers the reusable
  differentiator, with the example adoption (T6) and e2e (T7) following later.
- **OPA Compile API specifics.** `POST /v1/compile` with `{query, input, unknowns}`. An empty
  `result` (`{}`) means the query is unconditionally true (`ALLOW_ALL`); `result.queries == []` means
  unsatisfiable (`DENY_ALL`); a non-empty `queries`/`support` is the DNF residual. Validate locally with
  `opa eval --partial --unknowns input.resource --format pretty 'data.category.filter == true'` against
  a canned input before wiring the Java parser.
- **E2E needs the full rig + an in-network token + an OPA restart after the rego edit.** APISIX
  validates the issuer as `keycloak:8888`, so a host-minted token is rejected; `--watch` doesn't always
  reload the policy. Both are properties of the rig, not bugs.
- **CI does not run the rig yet**, so the newman filter matrix is a local/manual gate for now. Wiring a
  compose-up → newman e2e job (and an `opa test` job) into `.github/workflows/ci.yml` is a sensible
  follow-up, tracked separately.
