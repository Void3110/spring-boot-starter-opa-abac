---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/spring
---

# Supervised scope — autonomous implementation prompt

> **What this is.** A self-contained prompt for implementing the supervised-scope slice autonomously,
> ticket by ticket, with an architecture-review gate and a checkpoint after each ticket. The design and
> work list it refers to live alongside it in this folder.
>
> **Before you run it:** create the branch — `git checkout -b feature/void3110/supervised-scope` off a
> clean `main`. Confirm `git config --local user.email` is `void31102025@gmail.com`. Then paste the
> **PROMPT** section below to the agent.

---

## PROMPT

You are implementing **the supervised-scope slice** on branch `feature/void3110/supervised-scope`.

**The problem.** Slice B4 made **team membership the sole access path** to the catalog root list — the
coarse `catalog:list` gate was dropped, `GovernedScopeResolver` became the only authority, and the
`filter` entrypoint went role-definition-only with no fallback — so a subject who is a member of no team
sees nothing, by design. A **unit manager** is exactly that subject, and needs to see the catalogs of the
teams their reports own or manage. This slice adds a **second, disjoint access path**: a per-request
derivation over a reporting relation, behind a new fail-closed seam, resolving to a **synthesized
read-only role** — never by reintroducing the realm-role fallback B4 removed, which would be a fail-open
backdoor. **The headline:** a manager on zero teams gets a correct, live, read-only page of their unit's
catalogs, and revoking a reporting edge withdraws access on the very next request. **Explicitly NOT in
this slice** (they are slices B and C): the `env` tag and the `operatorManaged` flag, root-attribute
enrichment, any `deny_reason` or decision-envelope change, the RFC 9470 challenge, `acr`/`auth_time`
ingestion, the step-up-related Keycloak realm work, and the SPA. **Contents — categories and products —
stay closed**, and **no library module is touched at all.** The **one** realm change slice A does make is
T6's: an e2e persona *is* a Keycloak user, so the new persona accounts and the UX-only `unit-supervisor`
role are added to `infra/keycloak/realm-export.json` — nothing else in the realm is touched.

Implement the core work directly. Do not delegate the implementation to a sub-agent. Sub-agents are
welcome for **read-only scouting** (e.g. "find every place across the suite that asserts X") and for
**log-noisy validation** (e.g. run a newman matrix / a long build and report back only the failure
summary) — their findings come back to you; the code, tests, and docs are written in this loop.

### Read before you start (in order)

1. `SUPERVISED-SCOPE.md` (this folder's index) — what this slice delivers, the file glossary, the ticket
   status table, the critical path, conventions.
2. `00-DESIGN.md` — the mechanism, the epic boundary (what belongs to slices B and C), the **two failure
   classes**, and considered-&-rejected.
3. `01-DECOMPOSITION.md` — the 6 tickets, each with Goal / Deliverables / Acceptance /
   What-NOT-to-touch. **This is your work list.**
4. **The pinned decisions** — `docs/architecture/adr/0029-supervised-read-scope.md` (the contract this
   slice implements) and `docs/architecture/adr/0018-team-scoped-resource-isolation.md` (the invariant it
   pierces without weakening). `0030-step-up-decision-contract.md` is **slices B and C — do not
   implement any of it.**
5. `10-QA-TEST-CASES.md` — the unit / integration / e2e cases your work must satisfy.
6. **Context you will be checked against** in the review gate (step 5):
   `example-catalog-management-service/src/main/java/dev/dmitriikonovalov/example/catalog/config/HttpGovernedScopeResolver.java`
   (the classification discipline T4 mirrors),
   `.../config/HttpRoleDefinitionSupplier.java` and `.../config/TagDefinitionClient.java` (the shipped
   `CallGuard` edge wrapping), `.../config/CatalogListAuthorizer.java` (what T5 rewrites),
   `example-user-management-service/src/main/java/dev/dmitriikonovalov/example/usermgmt/web/InternalResolveController.java`
   and `.../web/InternalBootstrapController.java` (the endpoint siblings T1 mirrors),
   `.../service/EffectiveRoleService.java` and `.../service/TeamRoleCapabilities.java` (T2's and T3's seam, and the
   capability ladder), and `docs/to-do/implemented/MULTI-TENANT-ISOLATION/` (the slice that established
   the mechanism).
7. Root `CLAUDE.md` — the **IP boundary** (clean-room: original names only) and the **commit identity**
   rule (`Void3110 <void31102025@gmail.com>`).
8. `infra/README.md` — the local rig (needed for the e2e ticket), incl. the **in-network token caveat**
   and the **"restart OPA after editing a policy"** gotcha.
9. **Prime Mulch:** `ml prime opa-abac-authz-model spring-security-integration --budget 8000`, then
   `ml prime opa-abac-e2e-suite --budget 8000` before T6. Directly relevant records: **mx-6f551d**
   (root-list governed-scope isolation — the thing being extended), **mx-078a8e** (the role-def-only
   filter, no fallback), **mx-1ce7d5** (base-scope SPI is fail-closed-to-empty, unlike the tri-state role
   supplier), **mx-951d2f** (never count a fail-closed result as a breaker failure), **mx-cc7262**
   (omit-on-all-false affordance), **mx-3446c4** (verify affordance verb sets against real endpoints),
   **mx-87bf33** (B4's type-level-gate breakage — the cautionary blast-radius case), **mx-91fa5d**
   (fault-injecting e2e), **mx-fb443b** (the closed `errorCode` enum).

### Per-ticket loop (tickets T1 → T6, IN ORDER — strictly sequential; T1+T2+T3 are the standalone-value subset)

For each ticket do ALL of the following, in order, and **STOP at the checkpoint before the next**:

1. **Prime for the files you're about to touch.** `ml prime --files <path>`, and re-read that ticket's
   section in `01-DECOMPOSITION.md`.

2. **Build the deliverables** exactly as `01-DECOMPOSITION.md` lists them — exact classes, packages
   (`dev.dmitriikonovalov.example.usermgmt.{domain,service,web}` for T1–T3,
   `infra/opa/policies/{category,product}.rego` for T3's four inheritance clauses,
   `dev.dmitriikonovalov.example.catalog.config` for T4–T5), mappings, rego rules. Match the surrounding
   code's naming and idioms. **Clean-room:** no proprietary names anywhere.

3. **Write/extend the tests** for the ticket (the relevant U*/I*/E* cases from `10-QA-TEST-CASES.md`).
   Core/client tests use an **in-process `com.sun.net.httpserver.HttpServer` stub** (no WireMock).
   Persistence/IT tests run against **real Postgres via Testcontainers** (never H2). Policies use
   `opa test` — **T3 is the slice's ONLY policy change**: it adds the six U35–U40 cases and updates the five existing inheritance fixtures (measured, not estimated) to carry the provenance stamp. Every other ticket leaves the corpus
   untouched, and `opa test infra/opa/policies/` must be green at every checkpoint.

4. **Compile + run unit tests until green.** `./gradlew :<module>:test` (and `./gradlew build` for the
   example/IT tickets). Fix-until-green.

5. **★ ARCHITECTURE REVIEW + REFACTOR — the gate before integration/e2e validation.** Once unit tests
   pass, do NOT advance yet. Run a focused self-review, then refactor and re-test:
   - **Fail-closed check — the slice's load-bearing invariant: every error, timeout, cycle, depth-cap
     breach, unparseable body, and unresolvable role lands on an EMPTY collection or the EMPTY page,
     never on a partial supervised set and never on the table.** Both classes land where `00-DESIGN`
     says: an errored org source → the subject's **own memberships**; a partial derivation →
     **membership-only**.
   - **Security check — name the widening that would matter for this ticket** (a supervised scope that
     is not reduced by membership; the supervisor role reaching a row it did not earn; the realm marker
     becoming resolver input; a role code being trusted as authority rather than as provenance; the
     reporting relation leaking through an unauthenticated `/internal` route) **and state why it cannot
     happen.**
   - **Concurrency / idempotency check** — a reporting edge written twice converges to one row; a
     derivation racing a membership change yields a coherent smaller-or-equal set, never a mixed
     snapshot that widens; the two legs of the list read a consistent id set within one request.
   - **Wiring check** — every seam this ticket adds (an endpoint, a client, a config property, a logger,
     an exception + advice mapping) has a **named consumer** and a test through its **non-happy path**;
     zero call sites = the ticket is not done.
   - **Boundary / additivity check — NO library module is touched in this slice; `opa-abac-core` stays
     Spring-free and unchanged; the marker rides `RoleDefinition`'s EXISTING `attributes` map (adding a
     field would be the envelope change this slice forbids); name the byte-for-byte-unchanged surfaces.**
   - **Module-layer separation** — derivation lives in the user-service, the set difference and the
     composition live in the catalog service; neither reaches across.
   - **Pattern-reuse check** — `HttpGovernedScopeResolver`'s classification discipline, the shipped
     `CallGuard` edge wrapping, the `TeamRoleCapabilities` ladder, and the bootstrap-endpoint shape must
     be **reused, not reinvented**.
   - **SOLID / decomposition** — cohesive (SRP), depends on interfaces (DIP); anything to split/simplify?
   - **Static-analysis gate — the local Sonar scan is CLEAN on the changed files.** For a ticket that
     touches `.java`, run `./.sonar-local/sonar-local.sh` (the pinned local SonarQube,
     `.sonar-local/README.md`; bring the stack up once per run). Expected: `CLEAN — 0 open findings`
     on the changed files. `ml prime quality-gate-sonar` before judging a non-clean result — standing by-design
     false-positives are recorded there, and a documented FP is not re-fixed; a real finding is fixed
     in this ticket's commit.
   - **Apply** the refactoring the review surfaces, then **re-run the unit tests** to confirm green.
   - Write a short note of what the review found + what you refactored into `STATUS-0N.md`. If it found
     nothing substantive, say so explicitly — **do not invent churn.**

6. **Integration / e2e validation (MANDATORY for the relevant tickets).**
   - T1: Testcontainers IT against real Postgres asserting the supervised id set **by id** (I1) and a
     clean `ddl-auto: validate` boot (I2). T2: the four resolve branches over HTTP (I3). **T3: the seam
     test — `resourceRole(...)` stamps `attributes.provenance = "membership"` (I7)**; `opa test` alone
     cannot catch the Java side ceasing to stamp. T5: the paged union (I4), the `_actions` map (I5), and
     the audit event (I6). Fix-until-green.
   - T6: bring the rig up (`ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2`), then
     `cd scripts/postman && ./run-supervised-scope-matrix.sh`, **plus the E7 non-regression run**. E7 is
     an **enumerated** list, not "the full suite": there is no aggregate runner, and the 15 `run-*.sh`
     need mutually exclusive rig flavours. Re-run every matrix touching catalog listing or role
     resolution on this one rig, and **record in `STATUS-06.md` exactly which you ran and which you
     skipped, with the reason**. Honor the in-network token caveat. Fix-until-green.

7. **Update documentation (after each ticket).** Tick the ticket in the `SUPERVISED-SCOPE.md` status
   table; record real values/decisions in `STATUS-0N.md`. The ticket that finalizes a guide topic
   writes/reconciles `docs/guides/TEAM-BASED-AUTHORIZATION.md` (a **new section** in that guide — this
   slice does not earn a new guide). Root/project `CLAUDE.md` only if a new build/run step matters.

8. **Mulch expertise check (after each ticket).** `ml prime`/skim, then record any genuine reusable
   insight (`ml record <domain> --type <pattern|decision|failure|reference> …` — use the domain table in
   root `CLAUDE.md`; the catch-all `opa-abac` domain was retired) and `ml sync`. **Before `ml sync`,
   `git restore --staged .`** so the sync commit touches `.mulch/` **only** (the swept-staged trap).
   Skip recording only if nothing is non-obvious.

9. **Commit** — one focused commit on this branch (code + tests + docs + the `STATUS-0N.md` note
   together). Identity `Void3110 <void31102025@gmail.com>`. Conventional subject
   `feat(supervised-scope): …`. A `Co-Authored-By: Claude` trailer is welcome.

10. **CHECKPOINT — STOP and report.** Summarize what shipped, paste the unit + integration/e2e summary,
    **summarize the review findings + the refactoring you applied** (step 5), list docs updated, and
    note any open question you resolved. Then proceed to the next ticket. **Do not batch tickets
    without a checkpoint.**

### Permissions / autonomy granted (do these WITHOUT asking)

- Create/modify example code (user-service + catalog-service), tests, docs in this folder + the guides,
  and the `scripts/postman/` suite, and Mulch — all on this branch.
- Stand up / tear down / reseed the local rig (`./profile.sh`, `./deploy.sh`,
  `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2`); reset fixtures; rebuild images;
  restart OPA; drop/recreate the **local** schema if needed.
- Bring up the local Sonar stack for the ★gate's static-analysis check
  (`docker compose -f .sonar-local/docker-compose.yml up -d && ./.sonar-local/bootstrap.sh`).
- Fix any issue your own validation reveals (compile, unit, IT, e2e, refactor). Iterate until green.
- Commit per ticket on this branch.

### Hard rules

- **Report at every checkpoint and continue.** Don't batch tickets.
- **The architecture-review + refactor step (5) is NOT optional and happens BEFORE integration/e2e
  validation** — unit green → review → refactor → re-test → then ITs/e2e. Document what it found.
- **Fix-until-green within the ticket.** Only STOP mid-ticket if genuinely *blocked*: the same root
  cause survives ≥3 focused attempts, OR a design decision the docs don't cover is needed, OR a local
  prerequisite is unrecoverable.
- **Fail-closed is the load-bearing invariant: no path returns more or wider rows on an error than on
  success — the floor is the empty collection and the empty page, never a partial supervised set and
  never the table.** Fail-closed does **not** always mean "deny and continue": where the slice defines
  more than one failure *class*, each lands where the design says — a request-time failure may degrade
  to the narrower result while an install/startup-time failure **fails the context outright** rather than
  booting silently degraded. **This slice has two request-time classes:** an **errored/unreachable org
  source** degrades the subject to their **own memberships**, and a **partial derivation** (cycle,
  depth-cap breach, malformed element) collapses to **membership-only** — never a partial supervised
  set, because a partial set is indistinguishable from a correct smaller one. Never collapse the two
  classes into one rule because the sentence reads cleaner.
- **Verify a third-party seam before you build on it.** If a ticket names a library class, method,
  annotation, config property, endpoint, or policy path and reality disagrees — the signature differs,
  the hook does not exist, the property behaves differently — **that is not a blocker to work around
  and not a silent adaptation**: confirm against the artifact (`javap -p` against the jar in
  `~/.gradle/caches/`, the OpenAPI spec under `example-*/src/main/resources/openapi/`, `opa eval` against
  `infra/opa/policies/`, or by reading the repo source), then record the deviation in the STATUS
  *Decisions* section, in the ticket's own words, before proceeding. A plan that named the seam from a
  mental model is a planning defect the run should surface, not absorb.
- **Slice-specific invariants — never trade these away:**
  **(1) Membership always wins** — `supervised := S \ M`; the two scopes stay disjoint, which is what
  makes the two legs safe rather than merely careful. **(2) The realm marker is UX-only** — it is never
  resolver input; claim + zero reports must see nothing. **(3) Reach is CONTROL-capable seats only** —
  `OWNER`/`ADMINISTRATOR`/`SENIOR`, never `MEMBER`/`READER`. **(4) The synthesized role grants the COARSE token
  `catalog: ["READ"]`, and nothing on `category` or `product`** — coarse tokens, never fine verbs (a fine
  verb expands to ∅ and grants nothing at all). **The missing child keys are NOT sufficient on their own**
  — the shipped `catalog → child` inheritance tables would hand that role `category:view`/`product:view`
  whenever ancestors are present (always, at runtime), so contents are closed by the role **plus T3's
  confinement rule** (ADR 0031: ancestor inheritance requires `attributes.provenance == "membership"`).
  **(5) Additive
  only** — no library module changes, no decision-envelope changes, and **exactly one narrow Rego change:
  T3's four inheritance clauses in `category.rego` + `product.rego`. Any other policy edit means you have
  left the slice boundary.** **(6) A partial
  derivation collapses; it never degrades to a partial set.**
- **Clean-room IP boundary** — never introduce proprietary names, package names, comments, or source.
- **`opa-abac-core` stays Spring-free** — and in this slice, entirely untouched.
- **`ddl-auto: validate` must pass** — T1 adds a changeset; a clean boot is the proof.
- **Do NOT push, open PRs, or touch `main`.** Local + this branch only. The maintainer pushes.

---

## Operator notes (not part of the prompt)

- **The headline tickets** — **T5** (the two-leg partitioned list: the mechanism the whole slice exists
  for, and the one place a mistake widens rather than narrows), **T3** (the confinement rule — without it
  the supervised path leaks child contents; ADR 0031) and **T6** (E1 + E4: the derived scope is
  exact, and revoking an edge withdraws access on the next request). **T1** is the quiet prerequisite
  whose fail-closed behavior everything downstream inherits.
- **The fail-closed edges to eyeball** — the **set difference** in T5, and the **provenance conjunct** in T3. If `supervised = S \ M` is skipped
  or applied in the wrong direction, a doubly-reachable row gets judged by the vacuous-tag supervisor
  role instead of its tag-gated membership role: that is a genuine widening, and it is the one defect in
  this slice that fails **open**. U27 and E9 are its assertions. Second place to look: T4's classification
  — any failure class that returns something other than an empty list.
- **Standalone-value subset** — **T1 + T2 + T3**. They leave the user-service able to answer "who does this
  subject supervise, and with what role", fully tested, with nothing user-visible changed. A good place
  to stop if the window closes.
- **Rig / e2e specifics** — `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2`. Honor the
  in-network token caveat. **T3 edits `category.rego` + `product.rego`, so the rig's OPA MUST be restarted
  after that change lands** (`--watch` does not reliably reload — the standing rig gotcha); a 403-on-
  everything or a stale-allow after T3 is almost always an unreloaded policy, not a code bug. No *other*
  policy edit belongs in this slice. **E8's fault injection is its own edge, not B3's:** B3's
  `ENABLE_RESILIENCE_STUB=1` (`mx-91fa5d`) repoints the whole user-service the rest of the matrix needs —
  instead, repoint only T4's *dedicated* supervised base-URL property at a dead port for a second short
  pass, then recreate the catalog pods.
- **The slice boundary is testable, and E6 is what tests it.** If contents (categories/products) become
  readable on the supervised path, the run has drifted into slice B. **Two things hold that line, not
  one:** the synthesized role's missing `category`/`product` keys **and T3's provenance conjunct**. The
  keys alone are NOT sufficient — the shipped `catalog → child` inheritance tables hand that role the
  child verbs whenever ancestors are present (always, at runtime), which is the fail-open ADR 0031
  exists to close. If E6 goes green before T3 lands, it is passing for the wrong reason.
- **CI does not run the rig yet** — the newman matrix is a local/manual gate; a compose-up→newman CI
  job is a tracked follow-up.
- **Context management** — if the window grows long mid-run, finish the ticket, stop at its checkpoint,
  and resume in a **fresh session** (the ticket status table + STATUS notes are the handoff); sub-agents
  are for scouting/validation only, never the implementation.
- **This package declares execution parts** (`00-DESIGN` §Execution parts: part 0 = T1–T3 · part 1 =
  T4–T6, amended 2026-08-01 and re-cut 2026-08-02 when ADR 0031 added T3). Under `/autonomous-implement` the partition runs **orchestrated** — each
  part a fresh-context subagent, delegated sequentially and collected from disk (guide §4a). The
  fresh-session note above is the **manual fallback** for a bare-prompt paste, not the primary path;
  the "do not delegate the implementation" rule binds the implementer (the part-runner), exactly as
  §4a reconciles it.
- **Workflow-as-artifact:** keep this prompt verbatim; the `STATUS-0N.md` notes record each ticket's
  outcome. Move the folder to `docs/to-do/implemented/` on ship.
