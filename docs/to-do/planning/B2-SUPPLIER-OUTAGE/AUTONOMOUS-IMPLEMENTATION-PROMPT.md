---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/security
  - area/spring
---

# Supplier-outage fix-slice (Slice B2) — autonomous implementation prompt

> **What this is.** A self-contained prompt for implementing Slice B2 (distinguishing a role-source
> *outage* from an authoritative *no-role* at the `RoleDefinitionSupplier` SPI) autonomously, ticket by
> ticket, with an architecture-review gate and a checkpoint after each ticket. The design and work list
> it refers to live alongside it in this folder.
>
> **Before you run it:** create the branch — `git checkout -b feature/void3110/supplier-outage` off a
> clean `main`. Confirm `git config --local user.email` is `void31102025@gmail.com`. Then paste the
> **PROMPT** section below to the agent.

---

## PROMPT

You are implementing the **supplier-outage fix-slice** (Slice B2) on branch
`feature/void3110/supplier-outage`.

**The problem.** `RoleDefinitionSupplier.lookup(...)` returns `Optional<RoleDefinition>`, and today
**both** an authoritative *no-role* (the user-management service answers HTTP `204`) and a resolve
*outage* (timeout, connection-refused, 5xx, malformed body) collapse to `Optional.empty()`. At the
`@OpaPreAuthorize` gate an empty role means `input.role_definition` is absent, so the catalog policy's
**realm-role fallback** fires — which is *correct* for an authoritative no-role (non-members, type-level
creates, by 5.97 design) but **wrong for an outage**: a subject carrying realm `catalog-editor` then
rides the fallback to READ+WRITE+TAG, and the `denied_actions`/`required_tags` narrowing their *resolved*
role carried evaporates. **An outage makes access wider, not narrower** — the one tracked
widening-on-failure path (review C1/C4, aggravated by Phase 6.5). You are making an *outage*
**error-distinct** from a *no-role* at the SPI contract: a supplier **throws** a new unchecked
`RoleResolutionException` on an outage; every consumer catches it and fails closed to *its own* safe
outcome; an authoritative `204` still returns `Optional.empty()` and the realm fallback decides as
designed. The headline: an outage can no longer widen a grant. **Scope boundary:** this is a
classification/contract fix only — **resilience** (retry/backoff/circuit-breaking, timeout tuning) is
explicitly NOT in this slice (it is the separate **Slice B3**, before publish); there is **no
kill-switch** (the off-ramp would re-open the hole); there are **zero Rego changes** (the realm fallback
is *retained* — the outage simply never reaches it); and there is **no new error code / wire status** (an
outage is a uniform `403` deny).

Implement the core work directly. Do not delegate the implementation to a sub-agent. Sub-agents are
welcome for **read-only scouting** (e.g. "find every call site of `RoleDefinitionSupplier.lookup`
across the suite") and for **log-noisy validation** (e.g. run the newman matrices and report back only
the failure summary) — their findings come back to you; the code, tests, and docs are written in this
loop.

### Read before you start (in order)

1. `B2-SUPPLIER-OUTAGE.md` (this folder's index) — what this slice delivers, the file glossary, the
   ticket status table, the critical path, conventions.
2. `00-DESIGN.md` — the problem (§1, incl. the per-consumer table), the mechanism (§2), the **behavior
   matrix** (§3 — the cells that change and the ones that must not), the example adoption (§4), what
   this slice does NOT change (§5), the proof obligations (§6), and the forks already closed (§7 — do
   not reopen them).
3. `01-DECOMPOSITION.md` — the five tickets, each with Goal / Deliverables / Acceptance /
   What-NOT-to-touch, **plus the two pinned semantics** (outage → uniform 403, not 503; the strict
   204-only HTTP invariant). **This is your work list.**
4. **The pinned decisions** — ADR `docs/architecture/adr/0014-supplier-outage-error-distinct.md` (every
   fork, with rejections — the tri-state contract, the unchecked exception, the strict HTTP invariant,
   supplier-classifies/consumer-maps, no kill-switch, zero Rego, the implementor map). Skim ADR `0013`
   (the realm-fallback semantics B2 protects; its §3 split-fail-closed posture is the precedent), ADR
   `0007` (the `denied_actions`/`required_tags` narrowing an outage erased), ADR `0011` (the
   problem+json error contract — B2 adds nothing to it).
5. `10-QA-TEST-CASES.md` — the U / I / E / D cases your work must satisfy, the pinned-contract table,
   and the fail-closed checklist.
6. **Context you will be checked against** in the review gate (step 5): the source finding
   `docs/code-review/PERMISSION-CATEGORIES-REVIEW.md` (C1/C4 + the "the sweep stopped at the surface the
   ticket named" lesson — sweep **all five** `lookup()` consumers, not the one named); the shipped
   [[RESOURCE-RESOLUTION]] (the split-fail-closed SPI idiom; `AncestorResolutionException` is the naming
   + catch family to mirror); [[HIERARCHY-SINGLE-RESOURCE]] (`HierarchicalAuthorizer`'s fail-closed
   shape); the existing `OpaPreAuthorizeAuthorizationManager` / `OpaAuthorizationManager` broad-catch
   idiom; `docs/guides/E2E-TESTING.md` for the rig.
7. Root `CLAUDE.md` — the **IP boundary** (clean-room: original names only) and the **commit identity**
   rule (`Void3110 <void31102025@gmail.com>`).
8. `infra/README.md` — the local rig (needed for the e2e ticket), incl. the **in-network token caveat**
   and the **"restart OPA after editing a policy"** gotcha (note: this slice edits **no** policy, so OPA
   need not restart for a rego change — but `./deploy.sh build` still forces new app code into pods).
9. **Prime Mulch:** `ml prime opa-abac` and `ml search "supplier outage fallback fail-closed SPI
   consumer"` (the directly-relevant records: the **5-consumer scope map** "B2 supplier-outage: the 5
   lookup() consumers an SPI change must sweep", the **B2 decision** "outage error-distinct from
   no-role", the `RoleDefinitionSupplier` SPI pattern, the app-resolved effective-role pattern, the
   fail-closed two-shapes record).

### Per-ticket loop (tickets T1 → T5, IN ORDER; T2/T3/T4 are independent once T1 lands; T5 last)

For each ticket do ALL of the following, in order, and **STOP at the checkpoint before the next**:

1. **Prime for the files you're about to touch.** `ml prime --files <path>`, and re-read that ticket's
   section in `01-DECOMPOSITION.md`.

2. **Build the deliverables** exactly as `01-DECOMPOSITION.md` lists them — exact classes, packages
   (`dev.dmitriikonovalov.opaabac.{core,security,data}` for the library;
   `dev.dmitriikonovalov.example.{catalog,usermgmt}.*` for the apps), catch sites, log levels. Match the
   surrounding code's naming and idioms. **Clean-room:** no proprietary names anywhere.

3. **Write/extend the tests** for the ticket (the relevant U/I/E cases from `10-QA-TEST-CASES.md`).
   Manager/data-consumer tests follow the existing Mockito patterns
   (`OpaPreAuthorizeAuthorizationManagerTest`, `HierarchicalAuthorizerTest`, `SubtreeSpecResolverTest`);
   the `HttpRoleDefinitionSupplier` classification tests extend `HttpRoleDefinitionSupplierTest` against
   an **in-process `com.sun.net.httpserver.HttpServer` stub** (no WireMock); the catalog IT runs
   against **real Postgres via Testcontainers** (never H2).

4. **Compile + run unit tests until green.** `./gradlew :<module>:test` (and `./gradlew build` for the
   example/IT tickets). Fix-until-green.

5. **★ ARCHITECTURE REVIEW + REFACTOR — the gate before integration/e2e validation.** Once unit tests
   pass, do NOT advance yet. Run a focused self-review, then refactor and re-test:
   - **Fail-closed check — the slice's load-bearing invariant, stated concretely: an outage **throws**
     and is never returned as `Optional.empty()`; every consumer maps the throw to its own fail-closed
     outcome (gate → deny with no `OpaClient` call; `HierarchicalAuthorizer` → `false`;
     `SubtreeSpecResolver` → no widening; `CategoryListAuthorizer` → empty page); the outage **never
     reaches OPA**, so the realm fallback clause is never fed an outage input; an authoritative `204`
     still returns empty and the fallback decides (the designed path, unbroken). There is no branch
     where an outage degrades to the empty-role fallback.**
   - **Security check — name the widening that would matter for this ticket and state why it cannot
     happen: an outage swallowed back into `Optional.empty()` (the exact bug — would re-open the realm
     fallback to a wider grant); a non-`204` status mapped to no-role (a misrouted/misconfigured call
     silently widening); a consumer left on the old `.orElse(null)` with no catch (the unswept-sibling
     hole — confirm **all five** consumers are addressed); the `userId`/token/body reaching a log line
     (only status/class is logged, at WARN at the supplier and DEBUG at the consumers).**
   - **Concurrency / idempotency check — this slice gates no new mutation and adds no lock; confirm the
     outage catch sites do not alter the existing decide-under-protection ordering (the catch denies
     *before* any handler/mutation runs) and that a retried request after an outage simply re-runs the
     gate (no cached outage state). `CONCURRENCY-AND-LOCKING.md` Rules 1–2 are untouched — say so.**
   - **Wiring check** — every seam this slice touches has a named consumer and a non-happy-path test:
     `RoleResolutionException` is **thrown** by `HttpRoleDefinitionSupplier` + `TeamRoleDefinitionSupplier`
     and **caught** by all five consumers (gate ×2, hierarchy, subtree, list); each catch has a test
     asserting its fail-closed outcome (a throw with no consumer test = the ticket is not done). The
     `SubtreeSpecResolver` "no code change" is **still wired** — its existing catch is proven by U6.
   - **Boundary / additivity check — `opa-abac-core` stays Spring-free (prove with the import set — the
     new exception imports nothing Spring/JPA); the `lookup(...)` signature is **unchanged** (the
     tri-state is the unchecked-throw convention + javadoc, not a new return type); name the
     byte-for-byte-unchanged surfaces (the 5.97 resolver/cache, `AbacQueryService` + the four
     `findAuthorized` paths, pagination, every `@OpaPreAuthorize` annotation, the OpenAPI specs, the
     Rego policies, the schema) and the zero mechanical cost (NoOp/Demo/lambda never throw → no stub
     widens).**
   - **Module-layer separation — the exception + contract in core; the gate catches in spring-security;
     the data-consumer catches in spring-data; the classifying supplier + the list-consumer wrap + the
     conformant user-mgmt supplier in the examples. No layer reaches across.**
   - **Pattern-reuse check — `RoleResolutionException` mirrors `AncestorResolutionException` (naming +
     unchecked + catch-to-fail-closed); the gate catch mirrors the existing broad-catch-→-DENY idiom
     (made explicit); the classification is the ADR-0014 §4 invariant verbatim — no novel design.**
   - **SOLID / decomposition** — cohesive (SRP), depends on interfaces (DIP); anything to split/simplify?
   - **Apply** the refactoring the review surfaces, then **re-run the unit tests** to confirm green.
   - Write a short note of what the review found + what you refactored into `STATUS-0N.md`. If it found
     nothing substantive, say so explicitly — **do not invent churn.**

6. **Integration / e2e validation (MANDATORY for the relevant tickets).**
   - T5: `./gradlew build` (all modules + the catalog ITs against real Postgres) — `SupplierOutageGateIT`
     incl. the headline cut (I1: outage → 403, handler never ran) and the contrast (I2: authoritative
     no-role → the fallback still grants). Fix-until-green.
   - T5: bring the rig up (`./profile.sh up`; `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up
     --pods 2`; `./deploy.sh build` for fresh app images), then `cd scripts/postman` and run **every
     existing `run-*.sh` matrix** + `catalog-e2e` + `opa test infra/opa/policies/` (**157/157
     unchanged** — assert the count). Honor the in-network token caveat. (No rego edit → no OPA restart
     needed for a policy change.) Fix-until-green. The optional "stop the user service" outage cell is a
     nice-to-have — add it or record its deferral in STATUS (no silent cap).

7. **Update documentation (after each ticket).** Tick the ticket in the `B2-SUPPLIER-OUTAGE.md` status
   table; record real values/decisions in `STATUS-0N.md` (the exact outage statuses tested, the 403
   body, the `opa test` count). T1 writes the `RoleDefinitionSupplier` SPI javadoc; T4 rewrites the
   `HttpRoleDefinitionSupplier` class javadoc; **T5** reconciles `docs/guides/PERMISSION-MODEL.md` and
   `docs/guides/ABAC-AUTHORIZATION.md` (the closed widening path) and adds the short mechanism note to
   one of them. Root/project `CLAUDE.md` only if a new build/run step matters (it does not).

8. **Mulch expertise check (after each ticket).** `ml prime`/skim, then record any genuine reusable
   insight (`ml record opa-abac --type <pattern|decision|failure|reference> …`) and `ml sync`. **Before
   `ml sync`, `git restore --staged .`** so the sync commit touches `.mulch/` **only** (the
   swept-staged trap). Skip recording only if nothing is non-obvious.

9. **Commit** — one focused commit on this branch (code + tests + docs + the `STATUS-0N.md` note
   together). Identity `Void3110 <void31102025@gmail.com>`. Conventional subject
   `feat(supplier-outage): <ticket summary>` (or a narrower `feat(core)` / `feat(spring-security)` /
   `feat(spring-data)` / `feat(example)` / `test(e2e)` / `docs(…)` scope). A `Co-Authored-By: Claude`
   trailer is welcome.

10. **CHECKPOINT — STOP and report.** Summarize what shipped, paste the unit + integration/e2e summary,
    **summarize the review findings + the refactoring you applied** (step 5), list docs updated, and
    note any open question you resolved. Then proceed to the next ticket. **Do not batch tickets
    without a checkpoint.**

### Permissions / autonomy granted (do these WITHOUT asking)

- Create/modify library code, example-catalog + example-user-management code, tests, docs in this folder
  + the guides, the `scripts/postman/` suite, and Mulch — all on this branch.
- Stand up / tear down / reseed the local rig (`./profile.sh`, `./deploy.sh`, `ENABLE_OIDC=1
  ENABLE_USER_SERVICE=1 …`); reset fixtures; rebuild images; drop/recreate the **local** schema if
  needed.
- Fix any issue your own validation reveals (compile, unit, IT, e2e, refactor). Iterate until green.
- Commit per ticket on this branch.

### Hard rules

- **Fail-closed is the load-bearing invariant — no path returns a wider decision on an error than on
  success:** an outage **throws** `RoleResolutionException` (never `Optional.empty()`); every consumer
  fails closed (gate → deny with no OPA call; hierarchy → `false`; subtree → no widening; list → empty
  page); the outage **never reaches OPA**, so the realm fallback is never fed it; an authoritative `204`
  → `Optional.empty()` → the fallback decides exactly as before.
- **Do NOT push, open PRs, or touch `main`.** Local + this branch only. The maintainer pushes.
- **Clean-room IP boundary** — never introduce proprietary names, package names, comments, or source.
- **Report at every checkpoint and continue.** Don't batch tickets.
- **The architecture-review + refactor step (5) is NOT optional and happens BEFORE integration/e2e
  validation** — unit green → review → refactor → re-test → then ITs/e2e. Document what it found.
- **Fix-until-green within the ticket.** Only STOP mid-ticket if genuinely *blocked*: the same root
  cause survives ≥3 focused attempts, OR a design decision the docs don't cover is needed, OR a local
  prerequisite is unrecoverable.
- **Slice-specific invariants — never trade these away:**
  - **All five** `lookup()` consumers are addressed (gate ×2, `HierarchicalAuthorizer`,
    `SubtreeSpecResolver`, `CategoryListAuthorizer`); a consumer left on the old `.orElse(null)` with no
    outage handling is an unswept-sibling hole.
  - The strict HTTP invariant holds: **only `204` → fallback, only `200`+valid → resolved, everything
    else throws** (200-blank, all 4xx, all 5xx, timeout, refused, malformed-200).
  - An outage is a **uniform `403` deny** — no `503`, no new error code, no distinct wire status.
  - There is **no kill-switch** — fail-closed is non-optional (the off-ramp would re-open the hole).
  - **Zero Rego** — the realm fallback clause is *retained*; `opa test` stays 157/157.
  - The role-source outage axis stays **separate** from the ancestor-collapse axis
    (`AncestorResolutionException` → empty chain is a different failure; do not entangle them).
  - NoOp/Demo and any app lambda never throw — additivity (zero behavior change for any app without a
    classifying supplier).
- **`opa-abac-core` stays Spring-free.**
- **No schema change, no OpenAPI shape change** — a clean `ddl-auto: validate` boot still holds.

---

## Operator notes (not part of the prompt)

- **The headline ticket is T5 (with T2 as the behavioral core).** The IT where a realm-`catalog-editor`
  subject under a simulated outage is **denied (403)** instead of riding the fallback to a wider grant
  (I1), paired with the contrast cell where an authoritative `204` **still** rides the fallback (I2),
  justifies the whole design. T2 is where the cut actually happens (the gate catch); T5 proves it.
- **The fail-closed edge to eyeball:** the swallow-back-to-empty regression. The single most dangerous
  bug shape is a `catch` somewhere that turns the new `RoleResolutionException` back into
  `Optional.empty()` (or `.orElse(null)` with no catch on the list/hierarchy paths) — that *passes*
  every pre-B2 test and silently re-opens the realm fallback to an outage. Every `STATUS-0N.md` for
  T2–T4 must state explicitly that no such branch exists and that the ticket's consumer fails closed.
  Second edge: a non-`204` status (especially `404`) wrongly mapped to no-role in the classification.
- **The keystone scope discipline:** this slice exists *because* the prior review found "the sweep
  stopped at the surface the ticket named." Do not repeat it — **all five** consumers are in the
  decomposition for a reason; `SubtreeSpecResolver` needs no code change but **does** need its proof
  test (U6). A throw with no consumer test is an unfinished sweep.
- **Standalone-value subset:** T1 + T2 land the complete library spine of the fix — the exception type,
  the tri-state contract, and the two gate managers that carry the only widening path — correct and
  dormant until T4 wires the classifying supplier. T3 (data consumers) and T4 (example) can follow in
  any order.
- **Rig / e2e specifics:** mint tokens **in-network** (APISIX validates issuer `keycloak:8888`);
  `./deploy.sh build` to force new app images; **this slice edits no rego**, so no OPA restart is needed
  for a policy change and `opa test` must stay **157/157** (assert it); the optional outage e2e cell
  would point the catalog at a dead user-service base-url or drop the route.
- **CI does not run the rig yet** — the newman matrices are a local/manual gate; a compose-up→newman CI
  job is a tracked follow-up.
- **Context management** — if the window grows long mid-run, finish the ticket, stop at its checkpoint,
  and resume in a **fresh session** (the ticket status table + STATUS notes are the handoff); sub-agents
  are for scouting/validation only, never the implementation.
- **Workflow-as-artifact:** keep this prompt verbatim; the `STATUS-0N.md` notes record each ticket's
  outcome. Move the folder to `docs/to-do/implemented/` on ship (T5).
