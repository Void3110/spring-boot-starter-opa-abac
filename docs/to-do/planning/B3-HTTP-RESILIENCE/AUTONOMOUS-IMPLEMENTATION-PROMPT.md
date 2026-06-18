---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/spring
---

# B3 — Cross-service HTTP resilience — autonomous implementation prompt

> **What this is.** A self-contained prompt for implementing **Slice B3 (cross-service HTTP resilience)**
> autonomously, ticket by ticket, with an architecture-review gate and a checkpoint after each ticket. The
> design and work list it refers to live alongside it in this folder.
>
> **Before you run it:** create the branch — `git checkout -b feature/void3110/http-resilience` off a clean
> `main`. Confirm `git config --local user.email` is `void31102025@gmail.com`. Then paste the **PROMPT**
> section below to the agent.

---

## PROMPT

You are implementing **cross-service HTTP resilience (Slice B3)** on branch
`feature/void3110/http-resilience`.

**The problem.** Slice B2 made a role-source **outage** error-distinct from **no-role** and forced every
consumer to fail closed — closing the one widening-on-failure path, but at the cost of a **hard deny wall**:
during a transient blip (a pod restart, a GC pause, brief network weather) every fallback-eligible request
denies. B3 adds a **uniform retry/backoff/circuit-break posture** across all three cross-service HTTP edges
(`HttpOpaClient`, `HttpRoleDefinitionSupplier`, `TagDefinitionClient`) so transients recovering within a
bounded budget no longer surface as denials — **without** re-opening B2's realm-role fallback: an
exhausted-retry outage still fails closed exactly as B2 mandates. Resilience makes outages **rarer, never
wider.** **In scope:** the `CallGuard` seam + a Resilience4j impl (Spring layer, **not** core), a resilient
`OpaClient` decorator auto-configured `@ConditionalOnClass` R4j, app-side resolve/tag wrappers, per-edge
budgets + breakers + kill-switch, and the e2e headline. **Explicitly NOT in scope:** the Boot-4 / Java-25-26
native-resilience backend + the second artifact line (a later Boot-4 slice), and the load-testing rig +
empirical tuning (Phase 7). Java 21 / Spring Boot 3.4 baseline. **Zero `opa-abac-core` change, zero Rego.**

Implement the core work directly. Do not delegate the implementation to a sub-agent. Sub-agents are
welcome for **read-only scouting** (e.g. "find every place across the suite that asserts X") and for
**log-noisy validation** (e.g. run a newman matrix / a long build and report back only the failure
summary) — their findings come back to you; the code, tests, and docs are written in this loop.

### Read before you start (in order)

1. `B3-HTTP-RESILIENCE.md` (this folder's index) — what this slice delivers, the file glossary, the ticket
   status table, the critical path, conventions.
2. `00-DESIGN.md` — the ten settled forks, the behavior matrix, the all-edges sweep, the fail-closed
   posture, and considered-&-rejected (the "route D" rejection, the breaker-open-`error()` landmine).
3. `01-DECOMPOSITION.md` — the **four** tickets (T1→T4), each with Goal / Deliverables / Acceptance /
   What-NOT-to-touch. **This is your work list.**
4. **The pinned decisions** — ADR [[0017-cross-service-http-resilience|0017]] (this slice) and ADR
   [[0014-supplier-outage-error-distinct|0014]] (B2 — the contract you must preserve). Skim ADR
   [[0010-hierarchy-aware-list-filter|0010]] + [[0005-partial-eval-to-jpa-specification|0005]] for *why*
   `compile` must return `error()` (`fromError=true`), never `denyAll()`/`allowAll()`, on an OPA outage.
5. `10-QA-TEST-CASES.md` — the U*/I*/E* cases your work must satisfy (P1–P9).
6. **Context you will be checked against** in the review gate (step 5): the existing edges
   `opa-abac-core/.../HttpOpaClient.java` + `OpaClient.java` + `PartialResult.java` (the decorate target +
   the fail-closed values), `example-catalog-management-service/.../config/HttpRoleDefinitionSupplier.java`
   + `TagDefinitionClient.java` (B2's classification you wrap), the starter `autoconfigure/*` +
   `OpaAbacProperties.java` (where conditional wiring lands), and the shipped slice
   [[B2-SUPPLIER-OUTAGE]] (the five-consumer-sweep discipline this slice's all-edges-sweep mirrors).
7. Root `CLAUDE.md` — the **IP boundary** (clean-room: original names only) and the **commit identity**
   rule (`Void3110 <void31102025@gmail.com>`).
8. `infra/README.md` — the local rig (needed for the T4 e2e), incl. the **in-network token caveat** and the
   **"restart OPA after editing a policy"** gotcha (this slice edits **no** rego, so no OPA restart — but
   `./deploy.sh build` still forces new app code into pods).
9. **Prime Mulch:** `ml prime opa-abac` and `ml search "supplier outage fail-closed HttpOpaClient
   PartialResult error fromError"` — the B2 contract record + the fail-closed-two-shapes decision are the
   load-bearing ones.

### Per-ticket loop (tickets T1 → T4, IN ORDER; T2 and T3 are independent once T1 lands — either order)

For each ticket do ALL of the following, in order, and **STOP at the checkpoint before the next**:

1. **Prime for the files you're about to touch.** `ml prime --files <path>`, and re-read that ticket's
   section in `01-DECOMPOSITION.md`.

2. **Build the deliverables** exactly as `01-DECOMPOSITION.md` lists them — exact classes, packages
   (`dev.dmitriikonovalov.opaabac.security.resilience` for the seam + decorator;
   `dev.dmitriikonovalov.opaabac.autoconfigure` for the starter wiring; the catalog `config` package for
   the app wrappers), mappings, config keys. Match the surrounding code's naming and idioms.
   **Clean-room:** no proprietary names anywhere.

3. **Write/extend the tests** for the ticket (the relevant U*/I*/E* cases from `10-QA-TEST-CASES.md`).
   Client/edge tests use an **in-process `com.sun.net.httpserver.HttpServer` stub** (no WireMock).
   Persistence/IT tests run against **real Postgres via Testcontainers** (never H2). **All retry/breaker
   tests use virtual-time / programmatic R4j state transitions — zero `Thread.sleep`, zero wall-clock
   assertions** (the `CallGuard` clock is injectable for exactly this).

4. **Compile + run unit tests until green.** `./gradlew :<module>:test` (and `./gradlew build` for the
   example/IT tickets). Fix-until-green.

5. **★ ARCHITECTURE REVIEW + REFACTOR — the gate before integration/e2e validation.** Once unit tests
   pass, do NOT advance yet. Run a focused self-review, then refactor and re-test:
   - **Fail-closed check:** every error / timeout / breaker-open / exhausted-retry / disabled-config path
     lands on the delegate's fail-closed value — `allow`→`false`, `compile`→**`PartialResult.error()`
     (`fromError=true`)**, `allowAll`→n×`false`, resolve→throw `RoleResolutionException`, tag→throw
     `TagDefinitionFetchException`. **Never** a wider result; `compile` is **never** `denyAll()`/`allowAll()`.
   - **Security check:** the widening that would matter here is (a) a breaker-open/exhausted `compile`
     returning `denyAll()` (fromError false) so a 5.5-B hierarchy `subtreeSpec` widening survives an OPA
     outage, or (b) a retried resolve 4xx eventually riding the realm fallback wider than the resolved
     role. State why each cannot happen (the decorator owns `error()`; the predicate marks 4xx
     non-retryable; an exhausted transient still throws — B2 intact).
   - **Concurrency / idempotency check:** the resilience-wrapped calls run on the request thread **outside
     any write lock** (the no-lock invariant — verified: the team-row `FOR UPDATE` is server-side in
     user-mgmt, makes no outbound edge; `TagAssignmentService` is not `@Transactional`). Retry is safe
     because every edge is **side-effect-free** (a retried read can't double-execute). Confirm no wrapped
     call was introduced inside a transaction.
   - **Wiring check** — every seam this ticket adds (the `CallGuard`, the decorator bean, the conditional
     auto-config, each app wrapper, the config properties) has a **named consumer** and a test through its
     **non-happy path** (the off-state behavior, the breaker-open synth, the 4xx-no-retry edge, the
     exhausted-throw). Zero call sites = not done.
   - **Boundary / additivity check** — `opa-abac-core` stays **Spring-free and unchanged** (no B3 type, no
     R4j import enters it); `HttpOpaClient`/`OpaClient`/`PartialResult` are byte-for-byte unchanged; the
     starter still wires a **plain** `OpaClient` when R4j is absent or disabled. Name those unchanged
     surfaces.
   - **Module-layer separation** — the seam + impl + decorator in `opa-abac-spring-security`; the
     conditional wiring + properties in `opa-abac-spring-boot-starter`; the app wrappers in the catalog
     example. No layer reaches across; R4j never enters core.
   - **Pattern-reuse check** — match B2's classification discipline (only the legitimate terminal signals
     are un-retried) and the existing fail-closed posture in `HttpOpaClient`; don't reinvent.
   - **SOLID / decomposition** — `CallGuard` is one cohesive seam (SRP); the decorator depends on the
     `OpaClient` interface (DIP); anything to split/simplify?
   - **Apply** the refactoring the review surfaces, then **re-run the unit tests** to confirm green.
   - Write a short note of what the review found + what you refactored into `STATUS-0N.md`. If it found
     nothing substantive, say so explicitly — **do not invent churn.**

6. **Integration / e2e validation (MANDATORY for the relevant tickets).**
   - T3: example ITs against real Postgres + the in-process OPA/resolve stub (I1, I2) asserting the
     resolve blip recovers and a sustained resolve outage still denies (no realm widening). `./gradlew
     build`. Fix-until-green.
   - T4 (e2e): bring the rig up (`./profile.sh up`; `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up
     --pods 2`; `./deploy.sh build` for fresh app images), then `cd scripts/postman` and run the new
     `./run-resilience-matrix.sh` (E1 transient-recovers→success, E2 sustained→still-denies) **plus every
     existing `run-*.sh` matrix** + `opa test infra/opa/policies/` (the count must be **unchanged** — zero
     Rego). Honor the in-network token caveat. Fix-until-green.

7. **Update documentation (after each ticket).** Tick the ticket in the `B3-HTTP-RESILIENCE.md` status
   table; record real values/decisions (chosen defaults, the stub approach) in `STATUS-0N.md`. T4 writes
   `docs/guides/HTTP-RESILIENCE.md` and reconciles [[PARTIAL-EVALUATION-FILTERING]] (the `fromError`
   suppression cross-ref) + [[B2-SUPPLIER-OUTAGE]] (the wall this softens). Root/project `CLAUDE.md` only if
   a new build/run step matters (e.g. the optional-R4j note).

8. **Mulch expertise check (after each ticket).** `ml prime`/skim, then record any genuine reusable insight
   (`ml record opa-abac --type <pattern|decision|failure|reference> …`) and `ml sync`. **Before `ml sync`,
   `git restore --staged .`** so the sync commit touches `.mulch/` **only** (the swept-staged trap). Skip
   recording only if nothing is non-obvious. At the **end of the run** (after T4), also record the
   `autonomous-runs` retrospective per `CLAUDE.md`.

9. **Commit** — one focused commit on this branch (code + tests + docs + the `STATUS-0N.md` note together).
   Identity `Void3110 <void31102025@gmail.com>`. Conventional subject (`feat(resilience): …`). A
   `Co-Authored-By: Claude` trailer is welcome.

10. **CHECKPOINT — STOP and report.** Summarize what shipped, paste the unit + integration/e2e summary,
    **summarize the review findings + the refactoring you applied** (step 5), list docs updated, and note
    any open question you resolved (notably: whether T4 reused the existing harness or added a small stub).
    Then proceed to the next ticket. **Do not batch tickets without a checkpoint.**

### Permissions / autonomy granted (do these WITHOUT asking)

- Create/modify library code (`opa-abac-spring-security`, `opa-abac-spring-boot-starter`), example code
  (`example-catalog-management-service`), tests, docs in this folder + the guides, and the
  `scripts/postman/` suite + the compose rig (`infra/`, `compose.yaml`) — all on this branch. **Do not
  touch `opa-abac-core` or any `.rego`.**
- Stand up / tear down / reseed the local rig (`./profile.sh`, `./deploy.sh`, `ENABLE_OIDC=1
  ENABLE_USER_SERVICE=1 …`); reset fixtures; rebuild images; drop/recreate the **local** schema if needed.
- Fix any issue your own validation reveals (compile, unit, IT, e2e, refactor). Iterate until green.
- Commit per ticket on this branch.

### Hard rules

- **Report at every checkpoint and continue.** Don't batch tickets.
- **The architecture-review + refactor step (5) is NOT optional and happens BEFORE integration/e2e
  validation** — unit green → review → refactor → re-test → then ITs/e2e. Document what it found.
- **Fix-until-green within the ticket.** Only STOP mid-ticket if genuinely *blocked*: the same root cause
  survives ≥3 focused attempts, OR a design decision the docs don't cover is needed, OR a local
  prerequisite is unrecoverable.
- **Fail-closed is the load-bearing invariant:** in every breaker state and config state, no path returns a
  wider/more-permissive result on an error than on success — `compile` is **always** `error()`
  (`fromError=true`) on failure, never `denyAll()`/`allowAll()`; an exhausted outage still throws.
- **Slice-specific invariants the agent must never trade away:** (1) `opa-abac-core` stays Spring-free and
  **unchanged** — the seam + R4j live in the Spring layer; (2) **B2 preserved** — retry wraps *around* the
  classification, 4xx never retries, only the legitimate terminal signals (204, 200+valid) are un-retried,
  an exhausted transient still throws; (3) **"uniform" = classification + config shape + contract, not
  numbers** — asymmetric per-edge budgets, three per-endpoint breakers; (4) **breaker is latency/load only,
  never a decision input**; (5) **`enabled=false` ⟺ byte-identical to pre-B3**; (6) **zero Rego**; (7)
  **virtual-time tests only — zero `Thread.sleep`**.
- **Clean-room IP boundary** — never introduce proprietary names, package names, comments, or source.
- **`opa-abac-core` stays Spring-free** (and untouched this slice).
- **Do NOT push, open PRs, or touch `main`.** Local + this branch only. The maintainer pushes.

---

## Operator notes (not part of the prompt)

- **The headline tickets** — **T2** (the library feature: fail-closed identity in every state + the
  optional/conditional R4j wiring, P1/P2/P5/P8), **T3** (B2 intact after retry, P3), **T4** (the e2e soften:
  transient-recovers→success vs sustained→still-denies, P9). Their passing justifies the whole design.
- **The fail-closed edge to eyeball** — the breaker-open / exhausted `compile` path: it must return
  `PartialResult.error()` (`fromError=true`), **never** `denyAll()` (fromError false) or `allowAll()`. A
  `denyAll()` here lets a 5.5-B hierarchy `subtreeSpec` widening survive an OPA outage — the one silent
  fail-open in this slice. U5 pins it.
- **Standalone-value subset** — **T1+T2** alone ship the published-library OPA resilience (decorator +
  optional/conditional R4j) with full unit proof; landable if the window is short. T3 adds the example
  edges, T4 the e2e headline.
- **Rig / e2e specifics** — `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2`; `./deploy.sh
  build` for fresh app images; **this slice edits no rego**, so no OPA restart is needed (but the in-network
  token caveat still applies). T4's open question: prefer driving a flaky upstream via the existing harness
  (an env-toggled failure count) over adding a stub container — add the smallest stub only if the harness
  can't.
- **CI does not run the rig yet** — the newman matrix is a local/manual gate; a compose-up→newman CI job is
  a tracked Phase-7 follow-up (joins the load-testing rig).
- **Context management** — if the window grows long mid-run, finish the ticket, stop at its checkpoint, and
  resume in a **fresh session** (the ticket status table + STATUS notes are the handoff); sub-agents are for
  scouting/validation only, never the implementation.
- **Workflow-as-artifact:** keep this prompt verbatim; the `STATUS-0N.md` notes record each ticket's
  outcome. Move the folder to `docs/to-do/implemented/` on ship.
