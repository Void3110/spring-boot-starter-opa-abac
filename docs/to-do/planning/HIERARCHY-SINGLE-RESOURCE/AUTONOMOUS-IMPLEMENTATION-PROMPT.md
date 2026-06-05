---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/opa
  - area/spring-data
---

# Hierarchical single-resource — autonomous implementation prompt

> **What this is.** A self-contained prompt for implementing Slice 5.5-A (hierarchical single-resource
> authorization) autonomously, ticket by ticket, with an architecture-review gate and a checkpoint after
> each ticket. The design and work list it refers to live alongside it in this folder.
>
> **Before you run it:** create the branch — `git checkout -b feature/void3110/hierarchy-single-resource`
> off a clean `main`. Confirm `git config --local user.email` is `void31102025@gmail.com`. Then paste the
> **PROMPT** section below to the agent.

---

## PROMPT

You are implementing **hierarchical single-resource authorization** (Phase 5.5-A) on branch
`feature/void3110/hierarchy-single-resource`.

**The problem.** A deep path like `catalog/{id}/category/{id}/product/{id}` is authorized today
**leaf-only or one-step**: `@OpaPreAuthorize` resolves the role on the leaf type itself, and only two
hand-written authorizers do a single hard-coded hop to `("catalog", catalogId)`; a Product has no
parent-governed path at all. So the levels between root and leaf are never considered. You are building the
**N-level ancestor walk**: a resource declares its immediate parent, a resolver produces the full
ancestor chain, and a grant on **any** ancestor can satisfy a check on a descendant — **opt-in per
relation, deny-overridable, fail-closed**. This is the general form of the one-step hop the tag demo
stubbed, and the headline differentiator after partial-eval data filtering. **Scope is SINGLE-RESOURCE
only** — the hierarchy-aware *list* filter is the separate Slice 5.5-B and is explicitly NOT in this slice.

Implement the core work directly. Do not delegate the implementation to a sub-agent.

### Read before you start (in order)

1. `HIERARCHY-SINGLE-RESOURCE.md` (this folder's index) — what this slice delivers, the file glossary, the
   ticket status table, the critical path, conventions.
2. `00-DESIGN.md` — the design: `ParentRef`/`abacParent()` (core), `input.resource.ancestors`, the
   `AncestorResolver` SPI + ltree/recursive-CTE impls (cycle + depth, fail-closed), the opt-in
   `AbstractHierarchicalEntity` (ltree `path`, path-maintainer, atomic `reparent()`), the single-resource
   check `direct OR (walk_ok AND inherited)` + deny-overrides, and considered-&-rejected.
3. `01-DECOMPOSITION.md` — the seven tickets, each with Goal / Deliverables / Acceptance /
   What-NOT-to-touch. **This is your work list.**
4. **The pinned decision** — ADR `docs/architecture/adr/0008-hierarchical-resource-authorization.md` (the
   canonical record of this slice's model: chain-in-input, opt-in inheritance, the resolver SPI, fail-closed,
   ltree-vs-CTE). Skim ADR `0005` (the partial-eval residual you must **leave untouched**) and `0006` (this
   deepens layer 2 — app/per-resource — to N levels).
5. `10-QA-TEST-CASES.md` — the U/I/P/e2e cases your work must satisfy.
6. **Context you will be checked against** in the review gate (step 5): the shipped [[DATA-FILTERING]]
   (`AbacQueryService` — the seam shape you mirror for `HierarchicalAuthorizer`; the residual model you must
   NOT touch), the [[TAG-DICTIONARY]] (`category.rego` — the per-type policy you add an inheritance clause
   to; the load-then-check the `CategoryAuthorizer` does), [[DOMAIN-MODEL-FOUNDATION]]
   (`AbstractSecuredEntity` / `AbstractCrudService.mutate` — the base + write-centralization you extend), and
   [[LIBRARY-SPINE]] (`RoleDefinitionSupplier` — resolve the role on the governing root). E2E details:
   `docs/guides/E2E-TESTING.md`.
7. Root `CLAUDE.md` — the **IP boundary** (clean-room: original names only) and the **commit identity** rule
   (`Void3110 <void31102025@gmail.com>`).
8. `infra/README.md` — the local rig (needed for the e2e ticket), incl. the **in-network token caveat** and
   the **"restart OPA after editing a policy"** gotcha; `./deploy.sh build` to force new app code into pods.
9. **Prime Mulch:** `ml prime opa-abac` and `ml search "hierarchical resource authorization parent inherit"`
   (records `mx-506f5c` hierarchical-inherit-from-parent and `mx-76e16f` load-then-check-on-parent are
   directly relevant; also skim the fail-closed `mx-926c85` and the partial-eval records).

### Per-ticket loop (tickets T1 → T7, IN ORDER)

For each ticket do ALL of the following, in order, and **STOP at the checkpoint before the next**:

1. **Prime for the files you're about to touch.** `ml prime --files <path>` for the specific module or
   class, and re-read that ticket's section in `01-DECOMPOSITION.md`.

2. **Build the deliverables** exactly as `01-DECOMPOSITION.md` lists them — exact classes, packages
   (`dev.dmitriikonovalov.opaabac.{core,data,autoconfigure}` for the library;
   `dev.dmitriikonovalov.example.catalog.*` for the app), the rego inheritance clause, the Liquibase
   changelog. Match the surrounding code's naming and idioms. **Clean-room:** no proprietary names anywhere.

3. **Write/extend the tests** for the ticket (the relevant U/I/P/e2e cases from `10-QA-TEST-CASES.md`).
   Core tests use plain JUnit (no Spring). The resolver, the hierarchical entity, and the re-parent get
   **Testcontainers ITs against real Postgres** (never H2). The rego gets `opa test` (+ an `opa eval` probe).

4. **Compile + run unit tests until green.** `./gradlew :<module>:test` (and `./gradlew build` for the
   example/IT tickets). Fix-until-green.

5. **★ ARCHITECTURE REVIEW + REFACTOR — the gate before integration/e2e validation.** Once unit tests
   pass, do NOT advance yet. Run a focused self-review, then refactor and re-test:
   - **Fail-closed check (the load-bearing invariant):** every walk failure (cycle / broken link / depth
     breach / SQL error / `NULL` path / opt-in off / unresolved role) lands on **direct-grant-only or deny**
     — `final_allow = direct OR (walk_ok AND inherited)` — never wider, never strips a direct grant. The
     resolver **throws** (never returns a partial/truncated chain). Grep your own code AND the rego for any
     branch that could grant on a missing/failed input.
   - **Boundary / additivity check:** `opa-abac-core` stays Spring-free (no Spring/JPA in `ParentRef` /
     `abacParent()` / the `ancestors` field — prove with the import set). The `Resource`/`AbacDataObject`
     changes are **additive** (back-compat ctor + default method; old callers/tests compile unchanged;
     no-ancestors serializes byte-for-byte as before). `RoleDefinition` is **unchanged**. The Phase-5
     **residual model / `CompileResponseParser` / `ResidualSpecificationFactory` / operator set are untouched**
     (prove with `git diff --name-only` — the filter machinery must not appear for T1–T5).
   - **Module-layer separation:** `ParentRef`/`abacParent()`/`ancestors` in core; the walk + ltree/CTE +
     `AbstractHierarchicalEntity` in spring-data; the inheritance clause in the rego. No layer reaches across
     (core knows no SQL; the resolver knows no OPA wire format).
   - **Pattern reuse:** the `HierarchicalAuthorizer` seam mirrors `AbacQueryService`; the path-maintainer
     reuses the `AbstractCrudService.mutate` write-centralization; the role is resolved on the **governing
     root** like `CategoryAuthorizer`; the resolver fail-closed shape matches `HttpOpaClient`/the residual
     client; the bean conditionals match the starter idioms.
   - **Apply** the refactoring the review surfaces, then **re-run the unit tests** to confirm green.
   - Write a short note of what the review found + what you refactored into `STATUS-0N.md`. If it found
     nothing substantive, say so explicitly — **do not invent churn.**

6. **Integration / e2e validation (MANDATORY for the relevant tickets).**
   - T2 / T3 / T4: `./gradlew :opa-abac-spring-data:test` incl. the **Testcontainers ITs** — the resolver
     (both impls, cycle/depth/broken), the hierarchical entity + the **atomic re-parent** (a forced
     mid-rewrite failure leaves the tree unchanged), and the single-resource check. Fix-until-green.
   - T6: `./gradlew build` (incl. `ddl-auto: validate` boot + the example ITs) + `opa test
     infra/opa/policies/`. Fix-until-green.
   - T7: bring the rig up (`ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2`), then
     `cd scripts/postman && ./run-<hierarchy-matrix>.sh`. Honor the **in-network token caveat** and **restart
     OPA after editing a policy**. Fix-until-green.

7. **Update documentation (after each ticket).** Tick the ticket in the `HIERARCHY-SINGLE-RESOURCE.md`
   status table; record real values/decisions in `STATUS-0N.md` (the exact `input.resource.ancestors` you
   built, the ltree SQL the re-parent emitted, the row/decision the e2e asserted). The ticket that finalizes
   the guide writes `docs/guides/HIERARCHICAL-AUTHORIZATION.md`. Root/project `CLAUDE.md` only if a new
   build/run step matters.

8. **Mulch expertise check (after each ticket).** `ml prime`/skim, then record any genuine reusable insight
   (`ml record opa-abac --type <pattern|decision|failure|reference> …`) and `ml sync`. **Before `ml sync`,
   `git restore --staged .`** so the sync commit touches `.mulch/` **only** (the swept-staged trap). Skip
   recording only if nothing is non-obvious.

9. **Commit** — one focused commit on this branch (code + tests + docs + the `STATUS-0N.md` note together).
   Identity `Void3110 <void31102025@gmail.com>`. Conventional subject `feat(hierarchy): <ticket summary>`
   (or a narrower `feat(core)` / `feat(spring-data)` / `feat(starter)` / `feat(example)` / `feat(opa)` /
   `test(e2e)` scope). A `Co-Authored-By: Claude` trailer is welcome.

10. **CHECKPOINT — STOP and report.** Summarize what shipped, paste the unit + integration/e2e summary,
    **summarize the review findings + the refactoring you applied** (step 5), list docs updated, and note any
    open question you resolved. Then proceed to the next ticket. **Do not batch tickets without a checkpoint.**

### Permissions / autonomy granted (do these WITHOUT asking)

- Create/modify library code, example code, rego, the Liquibase changelog, tests, docs in this folder + the
  new guide, the `scripts/postman/` suite, and Mulch — all on this branch.
- Stand up / tear down / reseed the local rig (`./profile.sh`, `./deploy.sh`, `ENABLE_OIDC=1 …`,
  `ENABLE_USER_SERVICE=1 …`); reset fixtures; rebuild images; restart OPA; drop/recreate the **local** schema
  if a checksum reset is needed (local Postgres only).
- Use `/rego-skill` to author + `opa test` the inheritance clause.
- Fix any issue your own validation reveals (compile, unit, IT, policy, e2e, refactor). Iterate until green.
- Commit per ticket on this branch.

### Hard rules

- **Report at every checkpoint and continue.** Don't batch tickets.
- **The architecture-review + refactor step (5) is NOT optional and happens BEFORE integration/e2e
  validation** — unit green → review → refactor → re-test → then ITs/policy/e2e. Document what it found.
- **Fix-until-green within the ticket.** Only STOP mid-ticket if genuinely *blocked*: the same root cause
  survives ≥3 focused attempts, OR a design decision the docs don't cover is needed, OR a local prerequisite
  is unrecoverable.
- **Fail-closed is the load-bearing invariant.** No code path may grant more on an error/missing input than
  on success. A walk failure (cycle/broken/too-deep/SQL/`NULL`-path) collapses the **inherited** contribution
  to nothing but **preserves the direct leaf grant** — `final_allow = direct OR (walk_ok AND inherited)` —
  degrading to today's pre-hierarchy decision, never wider. The resolver **throws on any breach** (never a
  partial chain). Inheritance is **opt-in, default-off**. Deny-overrides is a final narrowing AND.
- **Single-resource ONLY.** Do **not** make any list endpoint hierarchy-aware — list endpoints keep today's
  Phase-5 tag-only filter. The hierarchy-aware list filter is Slice 5.5-B.
- **Resolve the role ONCE on the governing root** — not per-ancestor (per-node independent grants are
  Phase 8 / ReBAC). The chain is *context*; the role comes from the root.
- **Additive / boundary:** `opa-abac-core` stays Spring-free; `abacParent()`/`ancestors` are additive (old
  wire shape byte-for-byte when absent); `RoleDefinition` unchanged; **the Phase-5 residual model / parser /
  factory / operator set are untouched** (this slice adds no residual machinery); `AbstractHierarchicalEntity`
  is opt-in (non-hierarchical secured entities pay nothing).
- **`ddl-auto: validate` must pass** — the Liquibase ltree migration + the entity mapping must agree; a clean
  boot is the proof. **No OpenAPI shape change** for existing endpoints.
- **Clean-room IP boundary** — never introduce proprietary names, package names, comments, or source.
- **Do NOT push, open PRs, or touch `main`.** Local + this branch only. The maintainer pushes.

---

## Operator notes (not part of the prompt)

- **The headline is T3 + T6 + T7.** A passing atomic-re-parent IT (T3), the catalog adoption with the rego
  inheritance clause (T6), and the e2e where a **Catalog grant authorizes a Product 3 levels down** and a
  **subtree re-parent flips a decision** (T7) — that's the proof that justifies the whole walk. Make the
  e2e assert the **decision flip**, not just a 200.
- **The fail-closed edge to eyeball:** every `STATUS-0N.md` must state explicitly that each walk-failure path
  lands on direct-grant-only/deny. This is where a hierarchy feature silently over-grants if a truncated
  chain is returned instead of a throw — the review gate exists largely for this.
- **The re-parent atomicity (I8) is the subtle bug.** A non-atomic path rewrite lets a concurrent decision
  see a half-rewritten tree → a transient fail-open. The rewrite MUST share the `parent_id` change's
  transaction; test a forced mid-rewrite failure leaves the tree unchanged.
- **Standalone-value subset:** T1 + T2 + T3 land the reusable core (the parent model + the resolver SPI +
  both impls + the hierarchical entity) even before the example adoption (T6) and e2e (T7).
- **ltree needs the Postgres `ltree` extension** — the Liquibase changelog (T6) must `CREATE EXTENSION IF NOT
  EXISTS ltree` (and the Testcontainers image must allow it). The CTE resolver needs no extension.
- **CI does not run the rig yet**, so the newman hierarchy matrix is a local/manual gate for now. A
  compose-up → newman e2e job is a tracked follow-up.
- **Workflow-as-artifact:** keep this prompt verbatim; the `STATUS-0N.md` notes record each ticket's outcome.
  Move the folder to `docs/to-do/implemented/` on ship.
