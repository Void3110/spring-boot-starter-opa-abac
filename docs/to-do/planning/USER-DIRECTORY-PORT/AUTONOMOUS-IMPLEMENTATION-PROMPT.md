---
tags:
  - status/planned
  - type/project
  - area/security
  - area/api
---

# USER-DIRECTORY-PORT — autonomous implementation prompt

> **What this is.** A self-contained prompt for implementing the Keycloak-admin user-directory port
> autonomously, ticket by ticket, with an architecture-review gate and a checkpoint after each ticket.
> The design and work list it refers to live alongside it in this folder.
>
> **Before you run it:** create the branch — `git checkout -b feature/void3110/user-directory-port` off a
> clean `main`. Confirm `git config --local user.email` is `void31102025@gmail.com`. **Export `JAVA_HOME`
> to Corretto 21** (`export JAVA_HOME="$(/usr/libexec/java_home -v 21)"`) — the machine default `java` is
> now JDK 25 and Gradle 8.12 fails under it with a bare `25.0.3` error. Then paste the **PROMPT** section
> below to the agent.

---

## PROMPT

You are implementing **the Keycloak-admin user-directory port** on branch
`feature/void3110/user-directory-port`.

**The problem.** The demo SPA member picker can only offer **provisioned** users (rows in the user-service
`users` table) — to add a teammate who has never logged in, an admin must search the **identity
directory** (all realm accounts), which the library has no seam for and which this repo has **zero**
existing Keycloak-admin code for. This slice adds a pure **search** read-model SPI
(`UserDirectory.search(query, limit) → List<DirectoryUser>`) in `opa-abac-spring-security`, a concrete
`KeycloakUserDirectory` in a **new optional module** (`opa-abac-keycloak-directory`) auto-wired like B3's
Resilience4j, and a bearer-only `search` endpoint under `/api/v1/users` returning a bounded plain list.
**Explicitly NOT in scope:** provisioning (stays the SPA's existing `POST /users` on select — the port
never mutates), any OPA/rego change (search is bearer-only; the acting-gate `team:add-member` already
exists), paging the directory (a bounded top-`limit` list is the whole contract), and the Slice-1 query
filters (shipped separately). Every seam here is new — purely additive; `opa-abac-core` is untouched.

Implement the core work directly. Do not delegate the implementation to a sub-agent. Sub-agents are
welcome for **read-only scouting** (e.g. "find how OpaResilienceAutoConfiguration gates R4j", "find the
member-picker candidate list in the SPA") and for **log-noisy validation** (run the newman matrix / a long
build and report back only the failure summary) — their findings come back to you; the code, tests, and
docs are written in this loop.

### Read before you start (in order)

1. `USER-DIRECTORY-PORT.md` (this folder's index) — what this slice delivers, the file glossary, the
   ticket status table, the critical path, conventions.
2. `00-DESIGN.md` — the mechanism (the named seams), the 8 pinned forks, the fail-closed / **no-oracle**
   posture, and the scope boundary.
3. `01-DECOMPOSITION.md` — the 6 tickets, each with Goal / Deliverables / Acceptance / What-NOT-to-touch.
   **This is your work list.**
4. **The pinned decisions** — [[0020-user-directory-port|ADR 0020]] (the 8 forks) and, for the mirrored
   SPI shape, [[0019-pluggable-cross-service-ownership|ADR 0019]] (`ResourceOwnershipResolver`).
5. `10-QA-TEST-CASES.md` — the unit / integration / e2e cases your work must satisfy.
6. **Context you will be checked against** in the review gate (step 5): the **B3 optional-module wiring**
   `opa-abac-spring-boot-starter/src/main/java/dev/dmitriikonovalov/opaabac/autoconfigure/OpaResilienceAutoConfiguration.java`
   (the `@ConditionalOnClass` + `@ConditionalOnProperty` + `@ConditionalOnMissingBean` pattern to mirror)
   and its `ApplicationContextRunner` test; the mirrored SPI
   `opa-abac-spring-security/src/main/java/dev/dmitriikonovalov/opaabac/security/ownership/ResourceOwnershipResolver.java`
   (+ its `NoOp`/discovery default); the user-service web layer
   (`example-user-management-service/.../web/UserController.java`, `UserMgmtMapper`, the OpenAPI spec
   `.../resources/openapi/user-mgmt-api.yaml`); the realm export `infra/keycloak/realm-export.json` and
   `deploy.sh` / `infra/compose.usermgmt.yaml`; the SPA picker `example-demo-ui/src/teams.tsx` +
   `api.ts`.
7. Root `CLAUDE.md` — the **IP boundary** (clean-room: original names only) and the **commit identity**
   rule (`Void3110 <void31102025@gmail.com>`).
8. `infra/README.md` — the local rig (needed for the T5/T6 e2e), incl. the **in-network token caveat**
   (mint tokens inside the compose network; the module calls Keycloak at the **in-network**
   `http://keycloak:8888` — `KC_HOSTNAME_ADMIN_URL=localhost:28888` is a console-URL rewrite, NOT the REST
   path) and the "restart OPA after editing a policy" gotcha (no rego changes here).
9. **Prime Mulch:** `ml prime opa-abac` (+ the user-directory-slice decision recorded 2026-07-06; the B3
   optional-module records `mx-ca0380`/`mx-a640a0`; ADR-0019 ownership records).

### Per-ticket loop (tickets T1 → T6, IN ORDER; backend-first — T1 is independently landable, T6 is the integration cap)

For each ticket do ALL of the following, in order, and **STOP at the checkpoint before the next**:

1. **Prime for the files you're about to touch.** `ml prime --files <path>`, and re-read that ticket's
   section in `01-DECOMPOSITION.md`.

2. **Build the deliverables** exactly as `01-DECOMPOSITION.md` lists them — exact classes, packages
   (`dev.dmitriikonovalov.opaabac.security.directory`, `…opaabac.keycloak.directory`,
   `…opaabac.autoconfigure`, `…example.usermgmt.web`), the OpenAPI additions, the Gradle module. Match the
   surrounding code's naming and idioms (mirror `OpaResilienceAutoConfiguration` and
   `ResourceOwnershipResolver`). **Clean-room:** no proprietary names anywhere.

3. **Write/extend the tests** for the ticket (the relevant U*/I*/E* cases from `10-QA-TEST-CASES.md`). The
   Keycloak admin API is stubbed with an **in-process `com.sun.net.httpserver.HttpServer`** (no WireMock).
   Auto-config uses `ApplicationContextRunner`. Persistence-touching tests (none expected — the directory
   has no persistence) would use **real Postgres via Testcontainers** (never H2). No `opa test` (no policy).

4. **Compile + run unit tests until green.** `./gradlew :<module>:test` (and `./gradlew build` for the
   T2 new-module / T4 codegen / T6 whole-build tickets). Fix-until-green.

5. **★ ARCHITECTURE REVIEW + REFACTOR — the gate before integration/e2e validation.** Once unit tests
   pass, do NOT advance yet. Run a focused self-review, then refactor and re-test:
   - **Fail-closed / no-oracle check (this slice's load-bearing invariant):** `search` returns `[]` on
     **every** error edge (Keycloak unreachable/timeout/5xx, token-grant failure, blank `q` → no Keycloak
     call, zero matches) and **never throws**; `limit` is clamped (default 20, hard max 50); an outage and
     a genuine-empty are **indistinguishable** to the caller and UI (differ only in the WARN log). State
     why each holds for the ticket.
   - **Security check:** name the widening that would matter — the disclosure escaping `{subject,
     displayName}` (email/roles/attributes leaking), the service account holding more than `view-users`,
     the client secret reaching logs/error bodies, or an outage surfacing backend state to the UI — and
     state why it cannot happen (the DTO is the disclosure ceiling; the realm role is `view-users` only;
     the no-oracle empty hides state).
   - **Concurrency / idempotency check:** `search` is a pure read (no mutation to guard); the auto-config
     builds a singleton bean. State that there is no gated mutation in this slice (provisioning stays out,
     §1) — so the concurrency invariant is "n/a, read-only," said explicitly.
   - **Wiring check** — every seam this ticket adds has a **named consumer** and a test through its
     **non-happy path**: T1 the `NoOp` empty (U1a); T2 every Keycloak error edge → empty (I2b) + blank-`q`
     no-call (U2b); T3 the **off-state** (`NoOp` when disabled/absent, I3a) and the adopter-override
     (I3c); T4 the 200-empty-not-error path (I4b); T5 the `view-users`-only / denied-write proof (E-pre);
     T6 the never-provisioned-account hit (E1). A seam with only its happy path tested is not done.
   - **Boundary / additivity check** — `opa-abac-core` is **not touched**; the change is additive (all-new
     types, a new module, a new endpoint, a new optional dep — no existing signature changes except the
     regenerated `UserApi` gaining `searchUsers`, which lands with its `UserController` override in the
     same commit). Name the byte-for-byte-unchanged surfaces (`GET /users`, the existing controllers) and
     the one mechanical cost (the `settings.gradle.kts` module include in T2; the regenerated API in T4).
   - **Module-layer separation** — the port lives in `spring-security`; the Keycloak impl **only** in the
     new module (no Keycloak type leaks into `spring-security`, the starter, or the example service — the
     endpoint is URL/Keycloak-agnostic, §6); the starter owns wiring; the example service owns the HTTP
     surface. No layer reaches across.
   - **Pattern-reuse check** — mirror `OpaResilienceAutoConfiguration` (the `@ConditionalOnClass` +
     `@ConditionalOnProperty` + `@ConditionalOnMissingBean` optional-module wiring) and
     `ResourceOwnershipResolver` (the fail-closed SPI + `NoOp` default); do not reinvent them.
   - **SOLID / decomposition** — cohesive (SRP), depends on the `UserDirectory` interface (DIP); anything
     to split/simplify?
   - **Apply** the refactoring the review surfaces, then **re-run the unit tests** to confirm green.
   - Write a short note of what the review found + what you refactored into `STATUS-0N.md`. If it found
     nothing substantive, say so explicitly — **do not invent churn.**

6. **Integration / e2e validation (MANDATORY for the relevant tickets).**
   - **T2:** the HttpServer-stub ITs (I2a–I2c) — matching search mapped + bounded; every error edge →
     empty; blank username → subject. **T3:** the `ApplicationContextRunner` tests (I3a–I3c) — on/off/
     override. **T4:** the MockMvc slice (I4a/I4b) — 200 with rows / 200-empty-never-error. Fix-until-green.
   - **T5/T6 (e2e):** bring the rig up with the directory enabled
     (`ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ENABLE_DIRECTORY=1 ./deploy.sh up --pods 2`), reseed
     (`scripts/postman/seed-demo-data.sh`), then `cd scripts/postman && ./run-tests.sh` (extend the
     existing user-service matrix — no new collection). Mint tokens **in-network**. No rego changed → no
     OPA restart. Assert the **actual cut** (a never-provisioned account returned; blank `q` empty; the
     ≤50 clamp), not just shape. Fix-until-green.

7. **Update documentation (after each ticket).** Tick the ticket in the `USER-DIRECTORY-PORT.md` status
   table; record real values/decisions in `STATUS-0N.md`. The T6 ticket authors/reconciles the
   directory/identity-search guide under `docs/guides/` and confirms [[0020-user-directory-port|ADR 0020]]
   is linked from the ADR index + roadmap. Root/project `CLAUDE.md` **does** get a new build/run note: the
   new module + the `ENABLE_DIRECTORY` flag (T5/T6).

8. **Mulch expertise check (after each ticket).** `ml prime`/skim, then record any genuine reusable
   insight (`ml record opa-abac --type <pattern|decision|failure|reference> …`) and `ml sync`. **Before
   `ml sync`, `git restore --staged .`** so the sync commit touches `.mulch/` **only** (the swept-staged
   trap). Skip recording only if nothing is non-obvious.

9. **Commit** — one focused commit on this branch (code + tests + docs + the `STATUS-0N.md` note
   together). Identity `Void3110 <void31102025@gmail.com>`. Conventional subject
   (`feat(directory): …` for T1–T4, `chore(infra)/feat(rig): …` for T5, `test(e2e)/docs(user-directory-port): …`
   for T6). A `Co-Authored-By: Claude` trailer is welcome.

10. **CHECKPOINT — STOP and report.** Summarize what shipped, paste the unit + integration/e2e summary,
    **summarize the review findings + the refactoring you applied** (step 5), list docs updated, and note
    any open question you resolved. Then proceed to the next ticket. **Do not batch tickets without a
    checkpoint.**

### Permissions / autonomy granted (do these WITHOUT asking)

- Create/modify library code (the new module + `spring-security` + the starter), the OpenAPI spec (+
  regenerated sources), example-service code, tests, the SPA (`example-demo-ui/src/*`, T6), the realm
  export + deploy/compose config, docs in this folder + the guides, the `scripts/postman/` suite, and
  Mulch — all on this branch.
- Stand up / tear down / reseed the local rig (`./profile.sh`, `./deploy.sh`,
  `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ENABLE_DIRECTORY=1 …`); reset fixtures; rebuild images; restart
  Keycloak to import a changed realm; drop/recreate the **local** schema if needed.
- Fix any issue your own validation reveals (compile, unit, IT, e2e, refactor). Iterate until green.
- Commit per ticket on this branch.

### Hard rules

- **Report at every checkpoint and continue.** Don't batch tickets.
- **The architecture-review + refactor step (5) is NOT optional and happens BEFORE integration/e2e
  validation** — unit green → review → refactor → re-test → then ITs/e2e. Document what it found.
- **Fix-until-green within the ticket.** Only STOP mid-ticket if genuinely *blocked*: the same root cause
  survives ≥3 focused attempts, OR a design decision the docs don't cover is needed, OR a local
  prerequisite is unrecoverable.
- **Fail-closed to empty is the load-bearing invariant:** `search` returns an empty list on every error /
  timeout / auth-failure / blank-query path and **never throws**; no error ever surfaces a wider result
  than success, and an outage is **indistinguishable** from a genuine-empty to the caller and the UI (the
  no-oracle rule — differ only in the WARN log).
- **Slice-specific invariants the agent must never trade away:** the port is **search-only** (never
  provision/mutate); the disclosure is **type-bounded to `{subject, displayName}`** (the DTO is the
  ceiling); the Keycloak impl stays in its **own optional module** (no Keycloak type leaks into
  `spring-security`, the starter, or the example service — the endpoint is URL-agnostic); a bare adopter
  gets **`NoOpUserDirectory`** (the lean-starter promise); the service account holds **only `view-users`**.
- **Clean-room IP boundary** — never introduce proprietary names, package names, comments, or source. The
  `catalog-directory` client secret is an obvious **demo** secret, local-rig-scoped.
- **`opa-abac-core` stays Spring-free** — and this slice does not touch core at all.
- **`ddl-auto: validate` must pass** — the directory has no persistence, so a clean app boot is the proof
  nothing schema-side changed.
- **Do NOT push, open PRs, or touch `main`.** Local + this branch only. The maintainer pushes.

---

## Operator notes (not part of the prompt)

- **The headline tickets** — **T2** (`KeycloakUserDirectory` + its fail-closed edges) and **T4** (the
  endpoint), proven live by **E1** (a search returns a **never-provisioned** realm account). That is the
  whole point: the picker can reach anyone in the directory, not just the provisioned subset.
- **The fail-closed edge to eyeball** — the **no-oracle empty** (ADR 0020 §3/§8): an outage and a
  genuine-empty must be indistinguishable to caller + UI. If an agent adds a "directory unavailable"
  banner or a distinct HTTP status for outage, it has leaked backend state — check I4b (200-empty, never
  an error) and the SPA copy. Also eyeball the **`view-users`-only** service-account grant (T5) — a wider
  `realm-management` role is exactly the privilege excess this design avoids.
- **Standalone-value subset** — **T1–T4** are the reusable library core (port + impl + wiring + endpoint),
  newman/unit-provable without the SPA; T5/T6 (realm config + SPA) can follow if the window is short. T1
  alone lands independently.
- **Rig / e2e specifics** — the module calls Keycloak **in-network** (`http://keycloak:8888`); mint tokens
  in-network; the `ENABLE_DIRECTORY=1` flag gates the whole thing so the default rig is unchanged; restart
  Keycloak to re-import the realm after editing `realm-export.json`; **no rego**, so no OPA restart.
  `JAVA_HOME` must be Corretto 21 for every `./gradlew` (default `java` is 25 → bare `25.0.3` failure).
- **CI does not run the rig yet** — the newman matrix is a local/manual gate; a compose-up→newman CI job
  is a tracked follow-up. (CI **will** now build the new `opa-abac-keycloak-directory` module.)
- **Context management** — if the window grows long mid-run, finish the ticket, stop at its checkpoint, and
  resume in a **fresh session** (the ticket status table + STATUS notes are the handoff); sub-agents are
  for scouting/validation only, never the implementation.
- **Workflow-as-artifact:** keep this prompt verbatim; the `STATUS-0N.md` notes record each ticket's
  outcome. Move the folder to `docs/to-do/implemented/` on ship (T6).
