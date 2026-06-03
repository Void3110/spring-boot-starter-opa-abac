---
tags:
  - status/planned
  - type/project
  - area/user-service
  - area/abac
---

# user-management-service — autonomous implementation prompt

> **What this is.** A self-contained prompt for implementing Phase 4 (the team abstraction +
> app-resolved authorization) autonomously, ticket by ticket, with an architecture-review gate and a
> checkpoint after each ticket. The design and work list live alongside it in this folder.
>
> **Before you run it:** create the branch — `git checkout -b feature/void3110/user-management-service`
> off a clean `main`. Confirm `git config --local user.email` is `void31102025@gmail.com`. Then paste
> the **PROMPT** section below to the agent.

---

## PROMPT

You are implementing the **user-management-service** (Phase 4 — the team abstraction) on branch
`feature/void3110/user-management-service`.

**The problem.** The catalog app does real, role-definition-driven ABAC, but its role definitions come
from a **static demo supplier** keyed on Keycloak realm roles — there is no real authority that says
"this user, on this resource, has this role." You are building that authority: a second example service
that owns **teams**, **role definitions**, and **grants**, and resolves a caller's **effective role for a
resource**, feeding the catalog spine's `RoleDefinitionSupplier` SPI (the HTTP-backed implementation that
replaces the demo one — a single-bean swap built for in Phase 3).

The centerpiece is the **team abstraction**: a user creates a resource → it links to a **team-target** →
the creator becomes the **owner** → the owner manages a team and grants access via team membership.
Authorization is **app-resolved** (the service resolves the role server-side; the catalog still passes
`role_definition` in OPA `input`). Scope is **teams + role-defs + management + resolve + catalog wiring**
— NOT the dynamic tag dictionary (Phase 4.5) and NOT ReBAC-in-Rego (Phase 7).

Implement the core work directly. Do not delegate the implementation to a sub-agent.

### Read before you start (in order)

1. `USER-MANAGEMENT-SERVICE.md` (this folder's index) — purpose, the team abstraction, the integration
   point, the build sequence, the ticket status table.
2. `00-DESIGN.md` — the design: the entity model (`role ≠ grant`), system + team-scoped custom roles,
   owner-on-create, transfer-ownership, the no-self-escalation subset rule, the effective-role resolve
   API, the app-resolved `HttpRoleDefinitionSupplier`, dogfooding the starter, considered-&-rejected.
3. `01-DECOMPOSITION.md` — the nine tickets, each with Goal / Deliverables / Acceptance /
   What-NOT-to-touch. **This is your work list.**
4. `10-QA-TEST-CASES.md` — the unit / integration / policy / e2e cases your work must satisfy.
5. **The patterns you build against:** the catalog app (`example-catalog-management-service`) — copy its
   conventions (OpenAPI-first codegen, Liquibase, base-entity adoption, Testcontainers, `SecurityConfig`,
   the `RoleDefinitionSupplier`/`@OpaPreAuthorize` wiring). The shipped [[LIBRARY-SPINE]] slice
   (`docs/to-do/implemented/LIBRARY-SPINE/`) is the reference for how the spine works and how the demo
   supplier is wired. `docs/architecture/DOMAIN-MODEL.md` for the base-entity stack.
6. Root `CLAUDE.md` — the **IP boundary** (clean-room: original names only; the prior platform is
   study-only) and the **commit identity** rule (`Void3110 <void31102025@gmail.com>`).
7. `infra/README.md` + `deploy.sh` — the local rig, needed for ticket 9 (running a **second** service
   alongside the catalog pool), including the in-network token caveat.
8. **Prime Mulch:** `ml prime opa-abac`. Note especially: the role-definition-driven decision +
   pluggable `RoleDefinitionSupplier` (`mx-360261`); the cross-platform team-access research (the
   "Team-based resource access models" reference — role≠grant, owner-on-create, subset rule, resource→team
   indirection); fail-closed (`mx-926c85`); the starter-owns-beans/app-owns-chain convention; the
   ObjectMapper + AbacFilter-anonymous gotchas from the prior slice; chained-ids in collection scope
   (`mx-ecc3ef`) and the dual-token matrix (`mx-05b2c1`).

### Per-ticket loop (tickets 1 → 9, IN ORDER)

For each ticket do ALL of the following, in order, and **STOP at the checkpoint before the next**:

1. **Prime for the files you're about to touch.** `ml prime --files <path>` for the module/class, and
   re-read that ticket's section in `01-DECOMPOSITION.md`.

2. **Build the deliverables** exactly as `01-DECOMPOSITION.md` lists them — exact entities, packages
   (`dev.dmitriikonovalov.example.usermgmt.*` for the new app; catalog code stays under
   `dev.dmitriikonovalov.example.catalog.*`), the Liquibase changelogs, the rego, the infra edits. Match
   the catalog app's naming and idioms. **Clean-room:** no proprietary names anywhere.

3. **Write/extend the tests** for the ticket (the relevant cases from `10-QA-TEST-CASES.md`). ITs run
   against **real Postgres via Testcontainers** (never H2), like the catalog app. Use an in-process
   `com.sun.net.httpserver.HttpServer` stub for the catalog's `HttpRoleDefinitionSupplier` (no WireMock);
   use `opa test` for policies.

4. **Compile + run tests until green.** `./gradlew :example-user-management-service:test` (and
   `:example-catalog-management-service:test` for ticket 8, and `./gradlew build` for the build-wide
   tickets). Fix-until-green.

5. **★ ARCHITECTURE REVIEW + REFACTOR — the gate before integration/e2e validation.** Once tests pass,
   do NOT advance yet. Run a focused self-review, then apply refactoring and re-test:
   - **Fail-closed check:** the catalog's `HttpRoleDefinitionSupplier` denies (returns empty) on every
     resolve failure; the resolve API returns empty (not 500) for no-match; OPA default-denies. Deny
     never silently becomes allow.
   - **The hard rules check:** owner-on-create is **atomic** (a forced mid-create failure persists
     nothing); the **subset rule** blocks assigning/defining a role exceeding the actor's own perms;
     **transfer-ownership** reassigns cleanly; removing a member **revokes** (resolve re-derives); every
     management endpoint authorizes the **calling subject**, never the service identity.
   - **Boundary check:** the library public APIs are **unchanged** (the HTTP supplier is app code, not a
     library change); the new service is a clean Spring app adopting the starter; `role ≠ grant` stays
     modeled as separate `RoleDefinition` + `TeamMembership`.
   - **Pluggability / SOLID:** `TeamTargetMatcher` and the role/permission helpers are clean seams; the
     `PermissionSubset` check is shared (T4/T5), not duplicated; cohesive services, depend on interfaces.
   - **Apply** the refactoring the review surfaces, then **re-run the tests** to confirm green.
   - Write a short note of what the review found + what you refactored into `STATUS-0N.md`. If nothing
     substantive, say so explicitly — **do not invent churn.**

6. **Integration / e2e validation (MANDATORY for the relevant tickets).**
   - Tickets 2–7: Testcontainers ITs (schema/seed, owner-on-create atomicity, the subset rule, transfer,
     the resolve API) + `opa test` for the service's `team.rego`. Ticket 8: `./gradlew build` + the
     `HttpServer`-stub unit tests. Fix-until-green.
   - Ticket 9: bring the rig up with **both** services (`ENABLE_OIDC=1 ./deploy.sh up …`, the catalog
     pods pointed at the user-service), seed demo data, then run the newman matrix
     (`cd scripts/postman && ./run-*.sh`). Honor the in-network token caveat. Fix-until-green.

7. **Update documentation (after each ticket).**
   - This folder: tick the ticket in the `USER-MANAGEMENT-SERVICE.md` status table; record real
     values/decisions in the `STATUS-0N.md`.
   - Ticket 9 authors `docs/guides/TEAM-BASED-AUTHORIZATION.md` and reconciles `infra/README.md` +
     `docs/guides/E2E-TESTING.md` + the roadmap; moves the folder to `implemented/` with a Shipped banner.
   - Root/project `CLAUDE.md` only if a new build/run step matters for manual testing (e.g. running the
     second service).

8. **Mulch expertise check (after each ticket).** `ml prime`/skim, then record any genuine reusable
   insight (`ml record opa-abac --type <pattern|decision|failure|reference> …`) and `ml sync`. Skip only
   if nothing is non-obvious. Verify the sync commit touches `.mulch/` only.

9. **Commit** — one focused commit on this branch (code + tests + docs + the `STATUS-0N.md` note
   together). Identity `Void3110 <void31102025@gmail.com>`. Conventional subject — use
   `feat(user-mgmt): …` for the new service, `feat(example): …` for the catalog wiring,
   `chore(infra): …` for the rig, `test(e2e): …` for the matrix, `docs(...): …` for doc-only. A
   `Co-Authored-By: Claude` trailer is welcome.

10. **CHECKPOINT — STOP and report.** Summarize what shipped, paste the test + integration/e2e summary,
    **summarize the review findings + the refactoring you applied** (step 5), list docs updated, and note
    any open question you resolved. Then proceed to the next ticket. **Do not batch tickets without a
    checkpoint.**

### Permissions / autonomy granted (do these WITHOUT asking)

- Create/modify the new `example-user-management-service` module, the catalog app's `HttpRoleDefinitionSupplier`
  + wiring, the rego policies, tests, docs in this folder + the new guide, the `scripts/postman/` suite,
  `infra/` (compose, `deploy.sh`, realm/seed), `settings.gradle.kts`, and Mulch — all on this branch.
- Stand up / tear down / reseed the local rig (`./profile.sh`, `./deploy.sh`, `ENABLE_OIDC=1 …`); add a
  second service + its DB; reset fixtures; rebuild images; reload OPA policies; add realm users/teams —
  local environment only.
- Use `/rego-skill` to author + `opa test` the `team.rego` (and any per-type policy edits).
- Fix any issue your own validation reveals (compile, unit, IT, policy, e2e, refactor). Iterate until green.
- Commit per ticket on this branch.

### Hard rules

- **Report at every checkpoint and continue.** Don't batch tickets.
- **The architecture-review + refactor step (5) is NOT optional and happens BEFORE integration/e2e
  validation.** Document what the review found each ticket.
- **Fix-until-green within the ticket** for compile/test/IT/policy/e2e/config issues. Only STOP
  mid-ticket if genuinely *blocked*: the same root cause survives ≥3 focused attempts, OR a design
  decision the docs don't cover is needed, OR a local prerequisite is unrecoverable.
- **Clean-room IP boundary** — never introduce proprietary names, package names, comments, or source.
  Reference the prior platform only generically.
- **Do NOT change the library public APIs.** This slice consumes the `RoleDefinitionSupplier` SPI; the
  `HttpRoleDefinitionSupplier` is **catalog app code**, not a library change. If you think the library
  needs a change, STOP and report — don't silently widen the API.
- **Fail-closed everywhere** — the catalog's HTTP supplier returns `Optional.empty()` on any resolve
  failure; the resolve API returns empty (not an error) for no-match; OPA default-denies.
- **Enforce the hard rules** — owner-on-create atomic; the no-self-escalation **subset** rule on every
  assign/define; transfer-ownership reassigns; membership removal revokes; authorize the **actor** of a
  grant, never the service identity.
- **`role ≠ grant`** — keep `RoleDefinition` and `TeamMembership` as separate entities; "team-scoped" is
  the membership's scope, not a role per team.
- **Layering (decided — see [[01-DECOMPOSITION]] "Internal structure" / [[00-DESIGN]]).** Use a dedicated
  `…usermgmt.service/` package (`@Transactional`; all cross-entity invariants live there; controllers stay
  thin). Mapping is a **hand-written** static `UserMgmtMapper` like the catalog's `CatalogMapper` — **do
  NOT add MapStruct**. **No facade layer.** Do not flatten the service logic into controllers or `domain/`.
- **`ddl-auto: validate` must pass** — Liquibase owns the new service's schema; a clean boot is the proof.
- **Do NOT push, open PRs, or touch `main`.** Local + this branch only. Report at checkpoints; the
  maintainer pushes.

---

## Operator notes (not part of the prompt)

- **Checkpoint-gated per ticket.** The architecture-review step (5) is the deliberate addition — it makes
  the agent self-review (fail-closed, the hard rules, the library-API boundary, SPI pluggability, SOLID)
  and apply real (not ritual) refactoring *before* the heavier IT/e2e validation, then re-test. Eyeball
  each `STATUS-0N.md` for what it refactored.
- **The team abstraction is the headline.** The whole point is `role ≠ grant` + the hard rules
  (owner-on-create, subset/no-escalation, transfer, revocation-via-membership, authorize-the-actor). These
  are the teaching points that make this slice stand out — check they're enforced *and tested*, not just
  described.
- **App-resolved, not ReBAC.** The catalog passes `role_definition` in OPA input exactly as today; the
  user-service resolves the role. ReBAC-in-Rego is Phase 7. Don't drift into pushing the graph into OPA.
- **Dogfooding is a feature.** The user-service secures its OWN management API with the starter — the
  service that produces role definitions is also a consumer. That's the clean recursive demo; make sure
  the management endpoints are `@OpaPreAuthorize`-secured against the caller's team role.
- **Two services in the rig.** Ticket 9 is the first time the rig runs more than the catalog pool — its own
  container + Postgres DB + the catalog pods pointed at it in-network. Budget time for the compose/deploy
  wiring; it's the riskiest infra step.
- **Workflow-as-artifact:** keep this prompt verbatim and let the `STATUS-0N.md` notes record each ticket's
  outcome — this folder is a studied case study of the plan→autonomous-implement→test→review workflow,
  moved to `docs/to-do/implemented/` on ship alongside `DOMAIN-MODEL-FOUNDATION` and `LIBRARY-SPINE`.
