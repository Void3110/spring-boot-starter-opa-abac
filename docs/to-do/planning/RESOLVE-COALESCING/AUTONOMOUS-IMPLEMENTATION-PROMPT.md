---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/spring
---

# RESOLVE-COALESCING (Slice 7.3) — autonomous implementation prompt

> **What this is.** A self-contained prompt for implementing **Slice 7.3 (RESOLVE-COALESCING — the
> resolve-path performance slice)** autonomously, ticket by ticket, with an architecture-review gate
> and a checkpoint after each ticket. The design and work list it refers to live alongside it in this
> folder.
>
> **Before you run it:** create the branch — `git checkout -b feature/void3110/resolve-coalescing` off
> a clean `main`. Confirm `git config --local user.email` is `void31102025@gmail.com`. Then paste the
> **PROMPT** section below to the agent.

---

## PROMPT

You are implementing **RESOLVE-COALESCING (Slice 7.3)** on branch
`feature/void3110/resolve-coalescing`.

**The problem.** The 7.2 load baseline (root `PERFORMANCE.md`) measured the library's one real
hot-path defect: every role-resolve call in a request hits the same `RoleDefinitionSupplier` bean for
the **identical target** — the gate, the list authorizer, and one call per enriched row — so a
single GET / 20-row list / 100-row enriched page makes **2 / 22 / 102** sequential cross-service
resolves; the filtered-list path saturates at 10 req/s and OPA is OOM-killed at 50. Each row's
ancestor chain is also resolved **twice** per list request. This slice coalesces the fan-out on two
axes: a **request-scoped memo** (all three tri-state outcomes, "one request, one answer per target" —
ADR 0023) collapses duplicate targets, and a **batch `lookupAll`** (two-state entries, whole-batch
outage, strict completeness — ADR 0024) collapses the distinct-root pages a memo can't help (the
catalogs list: every row is its own governing root). A config/docs tail lands the 7.2 resilience
findings (APISIX `opa` plugin `timeout:500` int-ms; breaker-recovery + trace-sampling adopter notes;
the list batch-eval bound re-pinned to 2) and T6 re-measures everything against the recorded 7.2
baseline. **Explicitly NOT in scope:** the Spring Boot 4 port (the next slice), breaker tuning
(document-only), any cross-request/TTL caching (no revocation story — deliberately unbuilt), merging
the list's two batch-evals (rejected on layering, ADR 0024), and CI-runs-the-rig. **Zero Rego.**

Implement the core work directly. Do not delegate the implementation to a sub-agent. Sub-agents are
welcome for **read-only scouting** (e.g. "find every place across the suite that asserts X") and for
**log-noisy validation** (e.g. run a newman matrix / a long build and report back only the failure
summary) — their findings come back to you; the code, tests, and docs are written in this loop.

### Read before you start (in order)

1. `RESOLVE-COALESCING.md` (this folder's index) — what this slice delivers, the file glossary, the
   ticket status table, the critical path, conventions.
2. `00-DESIGN.md` — the settled design: the measured problem, the memo-proof multi-root gap, the
   fail-closed posture, considered-&-rejected, and the decompose-level to-dos already resolved.
3. `01-DECOMPOSITION.md` — the **six** tickets (T1→T6), each with Goal / Deliverables / Acceptance /
   What-NOT-to-touch, plus the slice invariants. **This is your work list.**
4. **The pinned decisions** — ADR [[0023-request-scoped-resolution-memoization|0023]] (the memo +
   staleness contract) and ADR [[0024-batch-role-resolution|0024]] (the batch + wire contract) — this
   slice implements both. Constraining context: ADR
   [[0014-supplier-outage-error-distinct|0014]] (the tri-state you must preserve verbatim), ADR
   [[0017-cross-service-http-resilience|0017]] (the `CallGuard` the batch rides as **one** call), ADR
   [[0016-action-enrichment-affordance-metadata|0016]] §7 (omit-never-fabricate — the advice contracts
   you must not bend), ADR [[0021-load-testing-methodology|0021]] (the measurement discipline T1/T6
   follow).
5. `10-QA-TEST-CASES.md` — the U*/I*/E*/P* cases your work must satisfy.
6. **Context you will be checked against** in the review gate (step 5):
   `opa-abac-spring-security/.../RequestAttributesResourceCache.java` (the request-attributes storage
   idiom the memos mirror), `opa-abac-spring-boot-starter/.../OpaResilienceAutoConfiguration.java`
   (the `BeanPostProcessor` decorator idiom), `opa-abac-core/.../RoleDefinitionSupplier.java` (the
   tri-state javadoc `lookupAll` must reference, never redefine),
   `example-catalog-management-service/.../config/HttpRoleDefinitionSupplier.java` (B2's strict
   classification + the guard wiring your batch override mirrors),
   `example-user-management-service/.../web/InternalResolveController.java` (the internal-contract
   style the batch endpoint joins), `opa-abac-spring-security/.../web/ActionEnrichmentAdvice.java` +
   `opa-abac-spring-data/.../filter/AbacQueryService.java` (the degrade ladders you must preserve;
   the finisher you must NOT touch), and `scripts/load/run-load.sh` + `scenarios/*.js` +
   `amplification.py` (the harness idiom T1 extends; the shipped slice [[LOAD-TESTING]] is the
   structural model).
7. Root `CLAUDE.md` — the **IP boundary** (clean-room: original names only) and the **commit
   identity** rule (`Void3110 <void31102025@gmail.com>`).
8. `infra/README.md` — the local rig (needed for T1/T6), incl. the **in-network token caveat**. This
   slice edits **no** rego; `./deploy.sh build` still forces new app code into pods. **Gradle 8.12
   needs JDK 21** — `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"` before any `./gradlew` if
   the default JDK is newer.
9. **Prime Mulch:** `ml prime opa-abac` and the directly-relevant records: `mx-3e9f8e` (the grill-me
   settlement — the slice's keystones), `mx-07ee10` (load-testing methodology), `mx-3faa4a` (the
   load-harness seed pattern: tag-gate the identity, canary-probe the chain), `mx-3acd67`
   (ladder-stage validity split), `mx-56d654` (the gate-side request-cache pattern the memo joins),
   `mx-a640a0` (the CallGuard seam).

### Per-ticket loop (tickets T1 → T6, IN ORDER — T1 first is load-bearing: its baseline must run against pre-memo code)

For each ticket do ALL of the following, in order, and **STOP at the checkpoint before the next**:

1. **Prime for the files you're about to touch.** `ml prime --files <path>`, and re-read that ticket's
   section in `01-DECOMPOSITION.md`.

2. **Build the deliverables** exactly as `01-DECOMPOSITION.md` lists them — exact classes, packages
   (`dev.dmitriikonovalov.opaabac.security` for the role memo;
   `dev.dmitriikonovalov.opaabac.autoconfigure` for the ancestor memo + BPPs;
   `dev.dmitriikonovalov.opaabac.core` for `ResolveTarget` + the `lookupAll` default; the example
   `…example.usermgmt.web` / `…example.catalog.config` packages for the wire pair; `scripts/load/`
   for the harness), mappings, config keys. Match the surrounding code's naming and idioms.
   **Clean-room:** no proprietary names anywhere.

3. **Write/extend the tests** for the ticket (the relevant U*/I*/E* cases from `10-QA-TEST-CASES.md`).
   Client/edge tests use an **in-process `com.sun.net.httpserver.HttpServer` stub** (no WireMock).
   Persistence/IT tests run against **real Postgres via Testcontainers** (never H2). No policy edits
   this slice (`opa test` count stays unchanged).

4. **Compile + run unit tests until green.** `./gradlew :<module>:test` (and `./gradlew build` for
   the example/IT tickets — and for **T3 always**, the `@FunctionalInterface` proof). Fix-until-green.

5. **★ ARCHITECTURE REVIEW + REFACTOR — the gate before integration/e2e validation.** Once unit tests
   pass, do NOT advance yet. Run a focused self-review, then refactor and re-test:
   - **Fail-closed check:** every failure path lands on the existing deny/omit degrade, never wider —
     a memoized outage **replays as the outage**; a short/malformed/extra-entried/non-200 batch is a
     **whole-batch** `RoleResolutionException`; the advice omits (row or page) and never fabricates;
     the memo decorators never throw from their own bookkeeping and outside a web request are pure
     pass-through.
   - **Security check:** the widenings that would matter here — (a) a memo entry serving an
     authorization artifact **across subjects or across requests** (the key includes `userId`; storage
     is request attributes that die with the request — state why both hold), (b) a **partial batch
     body yielding partial roles** (strict completeness makes it whole-batch outage — point at the
     test), (c) the batch endpoint reachable **through the gateway** (it must not be: `/internal/**`,
     no APISIX route), (d) a memoized outcome surviving into async/scheduler contexts (no request
     attributes there → pass-through). Name each and why it cannot happen.
   - **Concurrency / idempotency check:** memo state is request-confined (request attributes; no
     cross-thread sharing — async dispatch degrades to pass-through); the batch exchange is a
     **read-only GET**, side-effect-free by construction, so the guard's retry cannot double-execute;
     no resolve call moved inside a transaction or lock (`CONCURRENCY-AND-LOCKING.md` Rules 1–2 —
     nothing in this slice decides under weaker protection than it acts under).
   - **Wiring check** — every seam this ticket adds (`MemoizingRoleDefinitionSupplier`,
     `MemoizingAncestorResolver`, `OpaResolveMemoAutoConfiguration` + its BPPs, the
     `opa.abac.resolve-memo.enabled` property, `ResolveTarget`, the `lookupAll` default + override,
     the batch endpoint, the k6 scenario/seed) has a **named consumer** and a test through its
     **non-happy path** (the flag-off state, the no-request pass-through, the whole-batch outage, the
     breaker-open path, the 400-malformed-target, the ancestor-failure rung). Zero call sites = the
     ticket is not done — in particular, verify the starter's `AncestorChainSupplier` binding
     delegates to the **post-processed** `AncestorResolver` bean, or the advice path silently misses
     the memo.
   - **Boundary / additivity check** — `opa-abac-core` gains only pure-JDK types (`ResolveTarget`,
     the default method); `RoleDefinitionSupplier` stays a `@FunctionalInterface` and every existing
     impl compiles unchanged; the single-target `lookup()` path and `/internal/effective-role`
     (singular) are **byte-identical**; `AbacResourceCache`, `AbacQueryService`'s finisher, and all
     Rego are untouched. Name the unchanged surfaces.
   - **Module-layer separation** — role memo in `opa-abac-spring-security` (it has spring-web);
     ancestor memo + BPPs in the starter (the only module seeing both `AncestorResolver` and
     spring-web — ADR 0023 §1); core stays Spring-free; the wire pair is example-app code. No layer
     reaches across.
   - **Pattern-reuse check** — the memos mirror `RequestAttributesResourceCache` (storage + the
     no-request no-op language); the BPPs mirror `resilientOpaClientDecorator`; the batch
     classification mirrors B2's strict rules in `HttpRoleDefinitionSupplier` (only 200+complete
     trusted; transient-vs-permanent split reused, not reinvented); T1 mirrors the LOAD-TESTING
     harness idioms (validity gates, seed/teardown, attribution).
   - **SOLID / decomposition** — each decorator is one cohesive concern (SRP); consumers depend on
     the SPI interfaces (DIP); anything to split/simplify?
   - **Apply** the refactoring the review surfaces, then **re-run the unit tests** to confirm green.
   - Write a short note of what the review found + what you refactored into `STATUS-0N.md`. If it
     found nothing substantive, say so explicitly — **do not invent churn.**

6. **Integration / e2e validation (MANDATORY for the relevant tickets).**
   - T1: the rig up (`ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2`; `./deploy.sh
     build` first for fresh images), then `cd scripts/load && ./run-load.sh multi-root` — the
     validity-gated **baseline** run; keep the artifacts (P3-before). Fix-until-green.
   - T2/T3/T4/T5: the Testcontainers ITs named in each ticket's Acceptance (I1/I2/I3) via `./gradlew
     build`. Fix-until-green.
   - T6 (e2e + re-measure): fresh rig + **fresh trace store** (`docker compose -p opa-abac-example -f
     infra/compose.jaeger.yaml down -v` first), quiesced machine; `cd scripts/load` → re-run
     `REPS=3 ./run-load.sh full`, `./run-load.sh ceiling`, `./run-load.sh multi-root`,
     `./run-load.sh fault-opa` (P1/P2/P3/P4); then `cd scripts/postman` and run **every**
     `run-*.sh` matrix (E1/E2). Honor the in-network token caveat. Fix-until-green.

7. **Update documentation (after each ticket).** Tick the ticket in the `RESOLVE-COALESCING.md`
   status table; record real values/decisions (chosen names, the seed mechanism, measured numbers) in
   `STATUS-0N.md`. T6 rewrites `PERFORMANCE.md` (the 7.3 delta section + adopter notes + bounds
   re-pin), reconciles the [[ACTION-ENRICHMENT]] guide (the batching flow), the resilience guide (the
   memo-above-guard interplay), the supplier/authorization guide (the `lookupAll` contract + the
   flag), `infra/README.md` (the gateway timeout), `scripts/load/README.md` (the new scenario), and
   ticks [[USER-STORIES]] **F5**. Root/project `CLAUDE.md` only if a new build/run step matters.

8. **Mulch expertise check (after each ticket).** `ml prime`/skim, then record any genuine reusable
   insight (`ml record opa-abac --type <pattern|decision|failure|reference> …`) and `ml sync`.
   **Before `ml sync`, `git restore --staged .`** so the sync commit touches `.mulch/` **only** (the
   swept-staged trap). Skip recording only if nothing is non-obvious. At the **end of the run** (after
   T6), also record the `autonomous-runs` retrospective per `CLAUDE.md`.

9. **Commit** — one focused commit on this branch (code + tests + docs + the `STATUS-0N.md` note
   together). Identity `Void3110 <void31102025@gmail.com>`. Conventional subject (`perf(resolve): …` /
   `feat(resolve): …` as fits). A `Co-Authored-By: Claude` trailer is welcome.

10. **CHECKPOINT — STOP and report.** Summarize what shipped, paste the unit + integration/e2e
    summary, **summarize the review findings + the refactoring you applied** (step 5), list docs
    updated, and note any open question you resolved (notably: the T1 seed mechanism chosen, and
    whether T6 found an existing catalogs-list `_actions` cell or added one). Then proceed to the
    next ticket. **Do not batch tickets without a checkpoint.**

### Permissions / autonomy granted (do these WITHOUT asking)

- Create/modify library code (`opa-abac-core`, `opa-abac-spring-security`,
  `opa-abac-spring-boot-starter`), example code (both services), the harness (`scripts/load/`), the
  `scripts/postman/` suite, `infra/` config, tests, docs in this folder + the guides, and Mulch — all
  on this branch. **All Rego stays untouched.**
- Stand up / tear down / reseed the local rig (`./profile.sh`, `./deploy.sh`, `ENABLE_OIDC=1
  ENABLE_USER_SERVICE=1 …`); reset fixtures; rebuild images; drop/recreate the **local** schema if
  needed; reset the trace store.
- Fix any issue your own validation reveals (compile, unit, IT, e2e, harness validity gates,
  refactor). Iterate until green.
- Commit per ticket on this branch.

### Hard rules

- **Fail-closed is the load-bearing invariant:** no memo or batch path ever widens — a memoized
  outcome replays exactly (including the outage throw); a batch that is short, malformed,
  extra-entried, non-200, or transport-failed is a whole-batch outage landing on the existing
  deny/omit degrades; outside a web request the decorators are pure pass-through; empty never
  fabricates a role.
- **Report at every checkpoint and continue.** Don't batch tickets.
- **The architecture-review + refactor step (5) is NOT optional and happens BEFORE integration/e2e
  validation** — unit green → review → refactor → re-test → then ITs/e2e. Document what it found.
- **Fix-until-green within the ticket.** Only STOP mid-ticket if genuinely *blocked*: the same root
  cause survives ≥3 focused attempts, OR a design decision the docs don't cover is needed, OR a local
  prerequisite is unrecoverable.
- **Slice-specific invariants the agent must never trade away:** (1) **one request, one answer per
  target** — all three tri-state outcomes memoized, replayed, never reinterpreted; (2) **B2 preserved
  verbatim** — the single-target `lookup()` path byte-identical, the batch classification reuses B2's
  strict rules, terminal signals never retried; (3) **batching is unconditional code** — the flag
  governs memoization only, one flag for both memos, default `true`; (4) **strict completeness** —
  exactly one entry per requested target or the whole batch is an outage; (5) **`opa-abac-core` stays
  Spring-free** — `ResolveTarget` + the default method are pure JDK; (6) **`/internal/**` is never
  gateway-exposed** — the batch endpoint gets no route; (7) **the enrichment contracts hold** —
  omit-never-fabricate, all-`false`→omit, the row-vs-page degrade split, snapshot-never-verdict; (8)
  **zero Rego**; (9) **report-only perf posture** — validity gates, no latency thresholds, deltas vs
  the recorded 7.2 baseline; (10) **T1 lands and its baseline runs before any library change** — the
  before/after is the slice's proof.
- **Clean-room IP boundary** — never introduce proprietary names, package names, comments, or source.
- **Do NOT push, open PRs, or touch `main`.** Local + this branch only. The maintainer pushes.

---

## Operator notes (not part of the prompt)

- **The headline tickets** — **T2** (the measured same-root collapse: 2/22/102 → 1, U1–U7 + I3),
  **T4+T5** (the multi-root batch: M → one exchange, U10/I1/I2), **T6** (the re-measured proof:
  P1–P4 + the full newman fleet). Their passing justifies the whole design.
- **The fail-closed edge to eyeball** — two: the **strict-completeness check** in
  `HttpRoleDefinitionSupplier.lookupAll` (a partial 200 body must be a whole-batch outage — a lenient
  parse here silently yields partial roles), and the **memo's outage marker** (it must re-throw within
  the request and die with it — an outage leaking across requests would invert into a deny-DoS; one
  serving across subjects would be a privacy/authz leak; the `userId` in the key + request-attribute
  storage are the guards, U1/U3 pin them).
- **Standalone-value subset** — **T1+T2** alone deliver the measured headline (every 7.2 scenario was
  same-root) + the harness blind-spot fix; landable if the window is short. T3–T5 add the multi-root
  batch; T6 seals with proof.
- **Rig / e2e specifics** — **Docker, never podman** for this rig. `ENABLE_OIDC=1
  ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2`; `./deploy.sh build` to force fresh images; official
  perf runs need a **fresh trace store** + quiesced machine (ADR 0021); the in-network token caveat
  applies to newman; **zero rego edits** this slice, so no OPA restarts (config.json untouched too).
  `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"` before any `./gradlew` (Gradle 8.12 can't run
  on JDK 25). T1's team/membership seeding happens in the **user-management** service (API or bulk
  SQL — match the existing seed idiom; record the choice in STATUS-01).
- **CI does not run the rig yet** — the newman + k6 gates are local/manual; a compose-up→newman CI
  job is a tracked follow-up.
- **Context management** — if the window grows long mid-run, finish the ticket, stop at its
  checkpoint, and resume in a **fresh session** (the ticket status table + STATUS notes are the
  handoff); sub-agents are for scouting/validation only, never the implementation.
- **Workflow-as-artifact:** keep this prompt verbatim; the `STATUS-0N.md` notes record each ticket's
  outcome. Move the folder to `docs/to-do/implemented/` on ship.
