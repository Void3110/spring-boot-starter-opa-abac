---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/spring
  - area/security
---

# B4 — Multi-tenant isolation + self-service — autonomous implementation prompt

> **What this is.** A self-contained prompt for implementing **Slice B4 (multi-tenant isolation +
> self-service)** autonomously, ticket by ticket, with an architecture-review gate and a checkpoint after
> each ticket. The design and work list it refers to live alongside it in this folder.
>
> **Before you run it:** create the branch — `git checkout -b feature/void3110/multi-tenant-isolation`
> off a clean `main`. Confirm `git config --local user.email` is `void31102025@gmail.com`. Then paste the
> **PROMPT** section below to the agent.

---

## PROMPT

You are implementing **multi-tenant isolation + self-service (Slice B4)** on branch
`feature/void3110/multi-tenant-isolation`.

**The problem.** Every authenticated user currently sees **all 13 catalogs** and `catalog-editor` can
edit any of them: the per-type policies carry a **realm-role fallback** (when no `role_definition` is
resolved, `catalog-viewer`→READ and `catalog-editor`→READ+WRITE+TAG on *any* resource), and the catalog
**list** rides it (`catalog.rego` has no `filter` entrypoint). There is **no tenant isolation**. The
system *intends* team-governance — a team governs a catalog (`target_type='catalog'`, `target_id=<id>`),
and membership grants access — but the fallback contradicts it. B4 makes **team membership the sole
access path** to the catalog hierarchy, and adds the **self-service** flow (a new user creates a catalog
+ team, adds members) the isolation makes meaningful — with a **real cross-service ownership check** so
team-create cannot squat another user's catalog. **In scope:** a role-def-only catalog `filter`
entrypoint + removing the realm fallback from all three policies' single-decision path + a narrow
`catalog:create` fallback (T1); a `GovernedScopeResolver` SPI + the catalog HTTP impl + the user-service
governed-targets endpoint + the `CatalogListAuthorizer` (T2–T4); a pluggable `ResourceOwnershipResolver`
SPI + discovery client + the `created-by` contract + the `createTeam` wiring (T5–T7); gateway routing of
the public user-mgmt endpoints (T8); the demo users + the e2e isolation matrix (T9). **Explicitly NOT in
scope:** ReBAC / membership-join-in-policy (Phase 8); event-based ownership-cache invalidation (TTL-only
here, documented); the SPA changes that *demo* this slice (a separate branch/PR). Java 21 / Spring Boot
3.4 baseline. **`opa-abac-core` stays Spring-free and unchanged.**

Implement the core work directly. Do not delegate the implementation to a sub-agent. Sub-agents are
welcome for **read-only scouting** (e.g. "find every place across the suite that asserts X") and for
**log-noisy validation** (e.g. run a newman matrix / a long build and report back only the failure
summary) — their findings come back to you; the code, tests, and docs are written in this loop.

### Read before you start (in order)

1. `MULTI-TENANT-ISOLATION.md` (this folder's index) — what this slice delivers, the ticket status table,
   the critical path, conventions.
2. `00-DESIGN.md` — the mechanism (governed-scope as the base `scope`, the fallback removal across all
   three policies, the pluggable ownership resolver), the behavior matrix, the fail-closed posture, and
   the forks-already-closed list (do not reopen them).
3. `01-DECOMPOSITION.md` — the **nine** tickets (T1→T9), each with Goal / Deliverables / Acceptance /
   What-NOT-to-touch. **This is your work list.**
4. **The pinned decisions** — ADR [[0018-team-scoped-resource-isolation|0018]] (isolation /
   membership-as-sole-access-path) and ADR [[0019-pluggable-cross-service-ownership|0019]] (the ownership
   resolver). Skim ADR [[0005-partial-eval-to-jpa-specification|0005]] (`findAuthorized` / the
   Compile-API → DNF residual + the **fail-OPEN empty-result boundary**: `{}`=DENY_ALL, NOT allow-all),
   ADR [[0010-hierarchy-aware-list-filter|0010]] (the base-`scope` composition this reuses), and ADR
   [[0014-supplier-outage-error-distinct|0014]] (the fail-closed-on-failure doctrine + the in-network
   trust boundary).
5. `10-QA-TEST-CASES.md` — the R*/U*/I*/E* cases your work must satisfy.
6. **Context you will be checked against** in the review gate (step 5): the existing
   `infra/opa/policies/{catalog,category,product}.rego` + `permissions.rego` +
   `permission_categories.json` (the policy you edit + the table you must NOT); the shipped
   `example-catalog-management-service/.../config/CategoryListAuthorizer.java` +
   `opa-abac-spring-data/.../filter/AbacQueryService.java` +
   `opa-abac-spring-data/.../hierarchy/{AncestorResolver,SubtreeSpecResolver}.java` (the SPI + base-scope
   composition you mirror); `example-user-management-service/.../service/EffectiveRoleService.java` +
   `web/{TeamController,MembershipController,InternalResolveController,InternalBootstrapController}.java`
   + `service/CallerIdentity.java` (the membership join + the team-create path + the in-network seam);
   `example-catalog-management-service/.../config/AuditingConfig.java` (`created_by` = the sub); the
   starter `autoconfigure/OpaAbacAutoConfiguration.java` (where `ObjectProvider`-conditional wiring
   lands); and `infra/apisix/init-routes.sh` (the routing pattern + the SPA Keycloak-proxy precedent).
7. Root `CLAUDE.md` — the **IP boundary** (clean-room: original names only) and the **commit identity**
   rule (`Void3110 <void31102025@gmail.com>`).
8. `infra/README.md` — the local rig (needed for the T9 e2e), incl. the **in-network token caveat**, the
   **"restart OPA after editing a policy"** gotcha (this slice **edits rego** → **restart OPA** after T1
   before any matrix), and `./deploy.sh build` to force new app code into pods.
9. **Prime Mulch:** `ml prime opa-abac` and `ml search "filter role-def-only fail-closed realm fallback
   governed scope findAuthorized created_by ownership"` — the load-bearing records are **mx-cbd39e** (the
   `filter` rule is role-def-only, the PE-friendly tag match, the `member_2` operand side), **mx-a932a0**
   (Compile-API → DNF residual, the fail-OPEN empty-result boundary), **mx-f63604** (the PE-inline idiom:
   consume data tables inline, verify the residual with `opa eval --partial`), **mx-8bd79f** (the e2e
   filter matrix: two subjects / different row sets), and **mx-a55040** (the tri-state supplier /
   fail-closed discipline this mirrors).

### Per-ticket loop (tickets T1 → T9, IN ORDER; after T1 the isolation track T2→T3→T4 and the ownership track T5→T6→T7 are independent — either order, converging at T8)

For each ticket do ALL of the following, in order, and **STOP at the checkpoint before the next**:

1. **Prime for the files you're about to touch.** `ml prime --files <path>`, and re-read that ticket's
   section in `01-DECOMPOSITION.md`.

2. **Build the deliverables** exactly as `01-DECOMPOSITION.md` lists them — exact classes, packages
   (`dev.dmitriikonovalov.opaabac.data.filter` for the `GovernedScopeResolver` SPI;
   `dev.dmitriikonovalov.opaabac.security` for the `ResourceOwnershipResolver` SPI + `DiscoveryOwnershipResolver`;
   `dev.dmitriikonovalov.opaabac.autoconfigure` for the starter wiring; the catalog/user-mgmt `config`/`web`
   packages for the app wiring + endpoints), mappings, config keys (`abac.ownership.services.<type>`).
   Match the surrounding code's naming and idioms. **Clean-room:** no proprietary names anywhere.

3. **Write/extend the tests** for the ticket (the relevant R*/U*/I*/E* cases from `10-QA-TEST-CASES.md`).
   Policy tests are `opa test` + **`opa eval --partial` to verify the residual shape empirically**
   (mx-f63604 — never assume it). Client/edge tests use an **in-process `com.sun.net.httpserver.HttpServer`
   stub** (no WireMock). Persistence/IT tests run against **real Postgres via Testcontainers** (never H2).
   Cache/TTL tests use an **injectable clock** — zero `Thread.sleep`, zero wall-clock assertions.

4. **Compile + run unit tests until green.** `./gradlew :<module>:test` (and `./gradlew build` for the
   example/IT tickets; `opa test infra/opa/policies/` for T1). Fix-until-green.

5. **★ ARCHITECTURE REVIEW + REFACTOR — the gate before integration/e2e validation.** Once unit tests
   pass, do NOT advance yet. Run a focused self-review, then refactor and re-test:
   - **Fail-closed check:** every error / empty / unknown / unreachable / absent-bean path lands on the
     safe value — `GovernedScopeResolver` → an **always-false** Specification (never throws; missing scope
     → empty list, never the whole table); the catalog `filter` → **DENY_ALL** for a missing role-def
     (verified by `opa eval --partial`, NOT assumed: `{}`=DENY_ALL is the fail-OPEN trap, mx-a932a0);
     `ResourceOwnershipResolver` → **false** on unknown-type / unreachable / 404 / mismatch; `createTeam`
     → **403** when ownership is unverifiable. **Never** a wider result on an error than on success.
   - **Security check:** the widenings that would matter here are (a) a catalog `filter` rule that
     compiled to ALLOW_ALL for a *missing* role-def (a whole-table leak — R2 pins it), (b) the realm
     fallback surviving on any verb other than `create` (R4/R7 pin it), or (c) the ownership resolver
     defaulting to true / throwing-then-allowing so squatting re-opens (U7/U8, I7/I8). State why each
     cannot happen (the `filter` has no subject-roles fallback; the only retained fallback is verb-gated
     to `create`; the resolver returns `false` on every non-affirmative outcome).
   - **Isolation-completeness check:** the cut holds at **every** level — catalog list (governed scope),
     catalog single-GET, **and** category/product single-GET (fallback removed in all three policies) — so
     there is no deep-link leak (I5). And the governed-id Spec is the **base `scope`** (the AND-gate
     nothing escapes), never an OR-widener — a row from an un-governed catalog cannot enter the list.
   - **Wiring check** — every seam this ticket adds (the two SPIs, the two new `/internal` endpoints, the
     config registry + cache, the `createTeam` gate, the gateway routes, the `JpaSpecificationExecutor`
     addition) has a **named consumer** and a test through its **non-happy path** (the empty/error scope,
     the unknown-type/unreachable ownership, the squat-deny, the bootstrap-bypass). Zero call sites = not
     done.
   - **Boundary / additivity check** — `opa-abac-core` stays **Spring-free and unchanged** (the SPIs
     return `Specification`/use `UUID`, so they live in spring-data / spring-security, never core); the
     `permission_categories` table, the `filter` PE translator, `bulk`, pagination, hierarchy, enrichment
     are byte-for-byte unchanged; the `category`/`product` **list** paths (already role-def-only) are
     untouched; the `/internal/bootstrap` contract is unchanged (the seed depends on it). Name those
     unchanged surfaces.
   - **Module-layer separation** — the governed-scope SPI in `opa-abac-spring-data`; the ownership SPI +
     discovery client in `opa-abac-spring-security`; the conditional wiring + properties in
     `opa-abac-spring-boot-starter`; the app impls/endpoints in the two example services. No layer reaches
     across; no Spring type enters core.
   - **Pattern-reuse check** — mirror `CategoryListAuthorizer` (the list authorizer), `AncestorResolver`
     (the fail-closed-never-throw SPI shape), `HttpRoleDefinitionSupplier` (the HTTP client + classify),
     and B2's tri-state discipline; don't reinvent. Verify the `filter` residual with `opa eval --partial`,
     not by reading the rule.
   - **SOLID / decomposition** — each SPI is one cohesive seam (SRP); the discovery resolver depends on
     the config registry abstraction (DIP, OCP — a new owning service is config, not code); anything to
     split/simplify?
   - **Apply** the refactoring the review surfaces, then **re-run the unit tests** to confirm green.
   - Write a short note of what the review found + what you refactored into `STATUS-0N.md`. If it found
     nothing substantive, say so explicitly — **do not invent churn.**

6. **Integration / e2e validation (MANDATORY for the relevant tickets).**
   - T1: `opa test infra/opa/policies/` green (count updated) + `opa eval --partial` confirms R1–R3.
     **Restart OPA** (`docker restart opa-abac-opa`) before any later matrix uses the edited policy.
   - T3/T4/T6/T7: example/user-mgmt ITs against **real Postgres** + in-process stubs (I1–I12) — the
     different-row-sets cut (I1), the empty/multi-team cases (I2/I3), single-GET 403 at all three levels
     (I5), the ownership owner/non-owner/unverifiable/bootstrap-bypass cells (I6–I9), the `created-by`
     read (I10/I11), the governed-targets endpoint (I12). `./gradlew build`. Fix-until-green.
   - T9 (e2e): bring the rig up (`./profile.sh up`; `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up
     --pods 2`; `./deploy.sh build` for fresh app images; **OPA already restarted after T1**), then `cd
     scripts/postman` and run the new `./run-isolation-matrix.sh` (E1–E7) **plus every existing `run-*.sh`
     matrix** (E8 — they must pass **unchanged**) + `opa test infra/opa/policies/`. Honor the in-network
     token caveat. Fix-until-green.

7. **Update documentation (after each ticket).** Tick the ticket in the `MULTI-TENANT-ISOLATION.md` status
   table; record real values/decisions (chosen TTL default, the registry config keys, the matrix fixture
   ids) in `STATUS-0N.md`. T9 writes/reconciles the isolation guide
   (`docs/guides/TEAM-BASED-AUTHORIZATION.md` or a new `MULTI-TENANT-ISOLATION` guide) + the `infra/README.md`
   matrix section, ticks the `POC-ROADMAP.md` B4 row to shipped, and reconciles
   [[PARTIAL-EVALUATION-FILTERING]] (the catalog `filter` now exists). Root/project `CLAUDE.md` only if a
   new build/run step matters (the `abac.ownership.*` config, the user-mgmt gateway routes).

8. **Mulch expertise check (after each ticket).** `ml prime`/skim, then record any genuine reusable insight
   (`ml record opa-abac --type <pattern|decision|failure|reference> …`) and `ml sync`. **Before `ml sync`,
   `git restore --staged .`** so the sync commit touches `.mulch/` **only** (the swept-staged trap). Skip
   recording only if nothing is non-obvious. At the **end of the run** (after T9), also record the
   `autonomous-runs` retrospective per `CLAUDE.md`.

9. **Commit** — one focused commit on this branch (code + tests + docs + the `STATUS-0N.md` note together).
   Identity `Void3110 <void31102025@gmail.com>`. Conventional subject (`feat(isolation): …` /
   `feat(ownership): …`). A `Co-Authored-By: Claude` trailer is welcome.

10. **CHECKPOINT — STOP and report.** Summarize what shipped, paste the unit + integration/e2e summary,
    **summarize the review findings + the refactoring you applied** (step 5), list docs updated, and note
    any open question you resolved. Then proceed to the next ticket. **Do not batch tickets without a
    checkpoint.**

### Permissions / autonomy granted (do these WITHOUT asking)

- Create/modify library code (`opa-abac-spring-data`, `opa-abac-spring-security`,
  `opa-abac-spring-boot-starter`), example code (`example-catalog-management-service`,
  `example-user-management-service`), the policies (`infra/opa/policies/{catalog,category,product}.rego`
  + their `_test.rego`), tests, docs in this folder + the guides, the `scripts/postman/` suite, the
  Keycloak realm export, and the compose rig (`infra/`) — all on this branch. **Do not touch
  `opa-abac-core`.**
- Stand up / tear down / reseed the local rig (`./profile.sh`, `./deploy.sh`, `ENABLE_OIDC=1
  ENABLE_USER_SERVICE=1 …`); **restart OPA** after editing a policy; reset fixtures; rebuild images;
  drop/recreate the **local** schema if needed.
- Fix any issue your own validation reveals (compile, unit, IT, e2e, refactor). Iterate until green.
- Commit per ticket on this branch.

### Hard rules

- **Report at every checkpoint and continue.** Don't batch tickets.
- **The architecture-review + refactor step (5) is NOT optional and happens BEFORE integration/e2e
  validation** — unit green → review → refactor → re-test → then ITs/e2e. Document what it found.
- **Fix-until-green within the ticket.** Only STOP mid-ticket if genuinely *blocked*: the same root cause
  survives ≥3 focused attempts, OR a design decision the docs don't cover is needed, OR a local
  prerequisite is unrecoverable.
- **Fail-closed is the load-bearing invariant:** in every state, no path returns a wider/more-permissive
  result on an error than on success — a missing role-def → catalog `filter` **DENY_ALL** (verified with
  `opa eval --partial`, never assumed); a missing/failed governed scope → **empty list** (always-false
  Specification, never a throw, never the whole table); an unverifiable ownership → team-create **403**;
  an unknown/unreachable owning service → `isOwner` **false**.
- **Slice-specific invariants the agent must never trade away:** (1) `opa-abac-core` stays Spring-free and
  **unchanged** — both SPIs live in the Spring layer; (2) **membership is the sole access path** — the
  realm fallback is removed for view/update/delete/list in **all three** policies; the **only** retained
  fallback is verb-gated to `catalog:create`; (3) the governed-id Spec is the **base `scope`**, never an
  OR-widener — no un-governed row can enter the list; (4) the catalog `filter` is **role-def-only** (no
  subject-roles fallback) so a missing role fails closed to empty; (5) **ownership is fail-closed and
  pluggable** — config-keyed discovery, `false` on every non-affirmative outcome, the `/internal/bootstrap`
  path bypasses the check (trusted in-network seam); (6) **`/internal/**` is NEVER gateway-exposed** (it is
  `permitAll` + `trust-forwarded-jwt`); (7) the existing e2e matrices must pass **unchanged** — if one
  needs a team membership added, that is a real regression to flag, not silently patch.
- **Clean-room IP boundary** — never introduce proprietary names, package names, comments, or source.
- **`opa-abac-core` stays Spring-free** (and untouched this slice).
- **Do NOT push, open PRs, or touch `main`.** Local + this branch only. The maintainer pushes.

---

## Operator notes (not part of the prompt)

- **The headline tickets** — **T1** (the policy now fails closed: `filter` role-def-only + fallback
  removed + narrow `create`, R1–R9), **T4** (the catalog list isolates: two subjects → different row sets,
  single-GET 403 at all three levels, I1/I3/I5), **T7** (squatting closed: non-owner team-create → 403,
  I7/I8), **T9** (the e2e cut through the rig: self-service onboarding + scoped member access + multi-team
  + no direct-id leak + squat denied, E2/E4/E6/E7). Their passing justifies the whole design.
- **The fail-closed edge to eyeball** — the catalog `filter` residual for a **missing** role-def: it must
  partial-evaluate to **DENY_ALL** (`{}` / unsatisfiable), NOT ALLOW_ALL. Verify with `opa eval --partial`
  (mx-a932a0 — `{}`=DENY_ALL is counter-intuitive and getting it wrong is a whole-table leak). R2 pins it.
  Second edge: the ownership resolver must return **false** (never default-true/throw-allow) on every
  unknown/unreachable/404 path — U7/U8 pin it.
- **Standalone-value subset** — **T1 alone** ships the fail-closed policy (independently landable, pure
  `opa test`); **T1+T2+T3+T4** ship full catalog-list isolation with ITs; **T5+T6+T7** ship the ownership
  check; T8+T9 wire the gateway + prove the e2e. T1 is the spine — land it first.
- **Rig / e2e specifics** — `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2`; `./deploy.sh
  build` for fresh app images; **this slice edits rego (T1)** → `docker restart opa-abac-opa` after T1 and
  before any matrix; the in-network token caveat applies. T9's seed: pre-seed alice/bob/carol as
  user-service users + Carol's multi-team setup; Alice's create+add is performed **live** by the matrix.
- **The `CatalogRepository` build-breaker** — T4 must add `JpaSpecificationExecutor<CatalogEntity>` in the
  same commit, or `CatalogListAuthorizer` won't compile (it has `JpaRepository` + `LockableJpaRepository`
  today).
- **CI does not run the rig yet** — the newman matrix is a local/manual gate; a compose-up→newman CI job is
  a tracked Phase-7 follow-up.
- **Context management** — if the window grows long mid-run, finish the ticket, stop at its checkpoint, and
  resume in a **fresh session** (the ticket status table + STATUS notes are the handoff); sub-agents are for
  scouting/validation only, never the implementation.
- **Workflow-as-artifact:** keep this prompt verbatim; the `STATUS-0N.md` notes record each ticket's
  outcome. Move the folder to `docs/to-do/implemented/` on ship.
