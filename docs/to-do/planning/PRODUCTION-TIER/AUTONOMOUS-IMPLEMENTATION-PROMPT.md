---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/opa
  - area/spring
---

# Production tier — autonomous implementation prompt

> **What this is.** A self-contained prompt for implementing the production-tier slice autonomously,
> ticket by ticket, with an architecture-review gate and a checkpoint after each ticket. The design and
> work list it refers to live alongside it in this folder.
>
> **Before you run it:** create the branch — `git checkout -b feature/void3110/production-tier` off a
> clean `main`. Confirm `git config --local user.email` is `void31102025@gmail.com`. Then paste the
> **PROMPT** section below to the agent.

---

## PROMPT

You are implementing **the production-tier slice** on branch `feature/void3110/production-tier`.

**The problem.** Slice A (SUPERVISED-SCOPE) gave a unit manager a live, read-only **list** of their
unit's catalogs and deliberately closed contents outright: the synthesized supervisor role grants
nothing on `category`/`product`, and ADR 0031 confines ancestor inheritance to membership so that
closure is real. This slice adds the **tier** that decides how much further oversight goes: an
**operator-managed `env` tag** on the governing root (ADR 0030 §3) — unstrippable by the supervised
population by construction — is carried to child decisions as **`input.resource.root_attributes`**
(ADR 0032, three distinguishable states), the synthesized role widens to grant child READ **directly**
(no inheritance), and two provenance-scoped `denied` clauses per leaf policy close **production** and
**unproven** tiers. **The headline:** `sup-anna` opens her report's staging catalog down to its
products with no ceremony; the operator flips that catalog to production and her very next child read
is refused; nothing its owner can do through the API touches the tag that makes it so. **Explicitly
NOT in this slice** (slice C): the `deny_reason` envelope field, the RFC 9470 challenge,
`acr`/`auth_time` ingestion and freshness, audit emission points, **any** Keycloak realm/flow work
(zero realm diff), the non-ROPC e2e token path, threading root context into the `_actions`
enrichment advice (B pins the omission as contract), and the SPA. A supervisor's production child
read in B is a **plain 403**.

Implement the core work directly. Do not delegate the implementation to a sub-agent. Sub-agents are
welcome for **read-only scouting** (e.g. "find every place across the suite that asserts X") and for
**log-noisy validation** (e.g. run a newman matrix / a long build and report back only the failure
summary) — their findings come back to you; the code, tests, and docs are written in this loop.

### Read before you start (in order)

1. `PRODUCTION-TIER.md` (this folder's index) — what this slice delivers, the file glossary, the
   ticket status table, the critical path, conventions.
2. `00-DESIGN.md` — the mechanism, the six settled forks + two decompose-time amendments, the
   fail-closed posture, considered-&-rejected, and the execution parts.
3. `01-DECOMPOSITION.md` — the 6 tickets, each with Goal / Deliverables / Acceptance /
   What-NOT-to-touch. **This is your work list.**
4. **The pinned decisions** — `docs/architecture/adr/0030-step-up-decision-contract.md` **§1–4 only**
   (§5–9 are slice C — do not implement any of them),
   `docs/architecture/adr/0032-root-attribute-enrichment-input-contract.md` (as amended §Population),
   `docs/architecture/adr/0031-inheritance-confined-to-membership-roles.md` (the line this slice must
   not blur: contents open via **direct grant**, never by re-opening inheritance), and
   `docs/architecture/adr/0029-supervised-read-scope.md` (the scope machinery this slice leaves
   untouched).
5. `10-QA-TEST-CASES.md` — the unit / integration / e2e cases your work must satisfy.
6. **Context you will be checked against** in the review gate (step 5):
   `opa-abac-spring-security/src/main/java/dev/dmitriikonovalov/opaabac/security/OpaPreAuthorizeAuthorizationManager.java`
   (the resolution + role-override seams T3 extends — read `resolveCheck`, `withRoleResourceOverride`,
   `resolveInstance` before touching anything),
   `opa-abac-core/src/main/java/dev/dmitriikonovalov/opaabac/core/AbacContext.java` (the `ancestors`
   additive-evolution pattern T3 repeats),
   `.../security/RequestAttributesResourceCache.java` (the memo ride),
   `example-catalog-management-service/.../config/TagAssignmentService.java` +
   `TagDefinitionView.java` + `.../web/ApiExceptionHandler.java` + `CatalogErrorCode.java` (T2's
   surfaces), `example-user-management-service/.../service/SupervisorRoles.java` (T4's),
   `infra/opa/policies/category.rego` + `product.rego` (the `allow`/`denied`/`filter` shapes — note
   `filter` never consults `denied`, deliberately), and
   `docs/to-do/implemented/SUPERVISED-SCOPE/` (the slice being extended).
7. Root `CLAUDE.md` — the **IP boundary** (clean-room: original names only) and the **commit identity**
   rule (`Void3110 <void31102025@gmail.com>`).
8. `infra/README.md` — the local rig (needed for the e2e ticket), incl. the **in-network token caveat**
   and the **"restart OPA after editing a policy"** gotcha.
9. **Prime Mulch:** `ml prime opa-abac-authz-model spring-security-integration --budget 8000`, then
   `ml prime rego-policy --budget 8000` before T4 and `ml prime opa-abac-e2e-suite --budget 8000`
   before T6 (use the domain table in root `CLAUDE.md`; the catch-all `opa-abac` domain was retired).
   Directly relevant records: **mx-e842ba** (the `allow if { granted; not denied }` shape the tier
   denies extend), **mx-cbd39e** + **mx-f847f2** (why nothing non-PE-friendly may enter `filter` —
   the reason the tier decision lands at the coarse gate), **mx-078a8e** (role-def-only filter, no
   fallback), **mx-fb443b** (the closed `errorCode` enum discipline), **mx-cc7262**
   (omit-on-all-false — the `_actions` contract this slice pins), **mx-951d2f** (never count a
   fail-closed result as a breaker failure), **mx-926c85** (OPA helpers fail closed), and the
   `opa-abac-e2e-suite` failure record on **vacuous newman assertions** (every `pm.test` callback
   must throw — the assertion-style convention in `docs/guides/E2E-TESTING.md`).

### Per-ticket loop (tickets T1 → T6, IN ORDER — strictly sequential; T3 may land any time before T5 but is executed in sequence here; T1–T4 are the standalone-value subset)

For each ticket do ALL of the following, in order, and **STOP at the checkpoint before the next**:

1. **Prime for the files you're about to touch.** `ml prime --files <path>`, and re-read that ticket's
   section in `01-DECOMPOSITION.md`.

2. **Build the deliverables** exactly as `01-DECOMPOSITION.md` lists them — exact classes, packages
   (`dev.dmitriikonovalov.example.usermgmt.{domain,web}` for T1,
   `dev.dmitriikonovalov.example.catalog.{config,web}` for T2 and T5,
   `dev.dmitriikonovalov.opaabac.{core,security}` for T3,
   `infra/opa/policies/{category,product}.rego` + `…usermgmt.service` for T4), mappings, rego rules.
   Match the surrounding code's naming and idioms. **Clean-room:** no proprietary names anywhere.

3. **Write/extend the tests** for the ticket (the relevant U*/I*/E* cases from `10-QA-TEST-CASES.md`).
   Core/client tests use an **in-process `com.sun.net.httpserver.HttpServer` stub** (no WireMock).
   Persistence/IT tests run against **real Postgres via Testcontainers** (never H2). Policies use
   `opa test` — **T4 is the slice's ONLY policy change** (four `denied` clauses across two files +
   their tests, incl. the four clause-deletion mutation checks). Every other ticket leaves the corpus
   untouched, and `opa test infra/opa/policies/` must be green at every checkpoint.

4. **Compile + run unit tests until green.** `./gradlew :<module>:test` (and `./gradlew build` for the
   example/IT tickets). Fix-until-green.

5. **★ ARCHITECTURE REVIEW + REFACTOR — the gate before integration/e2e validation.** Once unit tests
   pass, do NOT advance yet. Run a focused self-review, then refactor and re-test:
   - **Fail-closed check — the slice's load-bearing invariant: the tier gate's floor is deny — a
     supervised child read on an unproven (absent) or production tier is refused in every branch,
     and no error, resolver failure, or missing input ever yields a wider result than success.**
     The one request-time failure class lands where `00-DESIGN` says: enrichment failure ⇒ the field
     is absent ⇒ the supervised path closes while a **member's request proceeds unchanged** — never
     a 5xx, never an exception out of the manager, never a member-visible change.
   - **Security check — name the widening that would matter for this ticket** (the tier deny written
     as a naive negation that absent-`env` slips past; a `root_attributes` predicate reaching the SQL
     residual; the operator endpoint reachable through the gateway; a client-authorable
     `operatorManaged`; the echo-rejection freezing tag edits and prompting a workaround; the
     supervised deny leaking onto membership decisions) **and state why it cannot happen.**
   - **Concurrency / idempotency check** — the operator merge-upsert converges under retry (same
     posted map twice = one state); the delta-rejection compares against the same loaded entity
     state the write persists (no TOCTOU between the current-tags read and the save — it rides the
     existing loaded-entity flow); a root-tag change mid-request yields one coherent decision, never
     a mixed gate/instance view (the per-request memo pins one snapshot).
   - **Wiring check** — every seam this ticket adds (the `operatorManaged` column and view field, the
     widened `validateAndBuild` signature, `TagOperatorManagedException` + its advice mapping + enum
     constant + yaml enum entry, the internal controller, the record component, the manager
     enrichment, the new rego clauses) has a **named consumer** and a test through its **non-happy
     path**; zero call sites = the ticket is not done.
   - **Boundary / additivity check — `opa-abac-core` stays Spring-free; the record change is additive
     (compat constructors; absent serializes byte-identically — every existing library test passes
     UNMODIFIED); `AbacResource`, `AbacResourceResolver`, `ParentRef`, both ancestor resolvers, and
     `opa-abac-spring-data` are byte-for-byte unchanged; the mirrored policy bundle
     (`permissions.rego` + `permission_categories.json`) untouched; `catalog.rego` untouched.**
   - **Module-layer separation** — the dictionary flag lives in the user-service; enforcement and the
     operator path live in the catalog service (where tag values are written); enrichment lives in
     the manager; the tier decision lives in policy. None reaches across.
   - **Pattern-reuse check** — the `ancestors` record-evolution pattern, the
     `RequestAttributesResourceCache` idiom, the `InternalOwnershipController`/bootstrap-endpoint
     posture, the `0004` seed-changeset shape, and the existing `denied` deny-overrides shape must be
     **reused, not reinvented**.
   - **SOLID / decomposition** — cohesive (SRP), depends on interfaces (DIP); anything to split/simplify?
   - **Static-analysis gate — the local Sonar scan is CLEAN on the changed files.** For a ticket that
     touches `.java`, run `./.sonar-local/sonar-local.sh` (the pinned local SonarQube,
     `.sonar-local/README.md`; bring the stack up once per run). Expected: `CLEAN — 0 open findings`
     on the changed files. `ml prime quality-gate-sonar` before judging a non-clean result — standing
     by-design false-positives are recorded there, and a documented FP is not re-fixed; a real finding
     is fixed in this ticket's commit.
   - **Apply** the refactoring the review surfaces, then **re-run the unit tests** to confirm green.
   - Write a short note of what the review found + what you refactored into `STATUS-0N.md`. If it found
     nothing substantive, say so explicitly — **do not invent churn.**

6. **Integration / e2e validation (MANDATORY for the relevant tickets).**
   - T1: Testcontainers IT — the `0008` changeset boots `ddl-auto: validate` clean and the seed row
     asserts (I1), the internal projection carries the flag (I2). T2: the 409 mapping over HTTP (I3)
     and the operator endpoint's happy + non-happy paths (I4). T5: the four child endpoints' recorded
     input shapes and both failure-state populations (I5–I8). Fix-until-green.
   - T6: bring the rig up (`ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2`), then
     `cd scripts/postman && ./run-production-tier-matrix.sh`, **plus the E8 non-regression set** —
     an **enumerated** list, at minimum `run-supervised-scope-matrix.sh` (the role and policy changed
     under it — its E6 cells are rewritten in this ticket), `run-tests.sh`, `run-filter-matrix.sh`,
     `run-hierarchy-list-matrix.sh`, `run-isolation-matrix.sh`, `run-action-enrichment-matrix.sh`;
     **record in `STATUS-06.md` exactly which you ran and which you skipped, with the reason** (if a
     matrix preflight-requires the directory flavour, run the whole set on that superset flavour).
     Honor the in-network token caveat. **T4 edited `category.rego` + `product.rego`, so the rig's
     OPA MUST be restarted after deploy if policies were reloaded in place** (`--watch` does not
     reliably reload — a 403-on-everything or a stale allow is almost always an unreloaded policy,
     not a code bug). Fix-until-green.

7. **Update documentation (after each ticket).** Tick the ticket in the `PRODUCTION-TIER.md` status
   table; record real values/decisions in `STATUS-0N.md`. **Documentation deltas ride the ticket that
   introduces the mechanism** — T1: the *Operator-managed keys* subsection in
   `docs/guides/TAG-BASED-AUTHORIZATION.md`; T2: its enforcement + operator-path paragraph; T3: the
   *Root-attribute enrichment* section in `docs/guides/ABAC-AUTHORIZATION.md`; T4: the *Production
   tier* subsection in `docs/guides/TEAM-BASED-AUTHORIZATION.md` (T6 appends only the e2e/E6-flip
   paragraph there); T5: no delta (say so in STATUS-05); T6: the fixture-registry row in
   `scripts/postman/README.md`. Root/project `CLAUDE.md` only if a new build/run step matters.

8. **Mulch expertise check (after each ticket).** `ml prime`/skim, then record any genuine reusable
   insight (`ml record <domain> --type <pattern|decision|failure|reference> …` — use the domain table
   in root `CLAUDE.md`; the catch-all `opa-abac` domain was retired) and `ml sync`. **Before
   `ml sync`, `git restore --staged .`** so the sync commit touches `.mulch/` **only** (the
   swept-staged trap). Skip recording only if nothing is non-obvious.

9. **Commit** — one focused commit on this branch (code + tests + docs + the `STATUS-0N.md` note
   together). Identity `Void3110 <void31102025@gmail.com>`. Conventional subject
   `feat(production-tier): …`. A `Co-Authored-By: Claude` trailer is welcome.

10. **CHECKPOINT — STOP and report.** Summarize what shipped, paste the unit + integration/e2e summary,
    **summarize the review findings + the refactoring you applied** (step 5), list docs updated, and
    note any open question you resolved. Then proceed to the next ticket. **Do not batch tickets
    without a checkpoint.**

### Permissions / autonomy granted (do these WITHOUT asking)

- Create/modify library code (`opa-abac-core`, `opa-abac-spring-security` — T3's additive changes
  only), example code (user-service + catalog-service), the two rego files + tests (T4 only), tests,
  docs in this folder + the guides, and the `scripts/postman/` suite, and Mulch — all on this branch.
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
- **Fail-closed is the load-bearing invariant: the tier gate's floor is deny — no path returns more
  or wider child content on an error than on success; an unproven tier is a closed tier.** Fail-closed
  does **not** always mean "deny and continue": where the slice defines more than one failure *class*,
  each lands where the design says. **This slice has one request-time class** (enrichment failure ⇒
  `root_attributes` absent ⇒ the supervised path closes while a member's request proceeds unchanged)
  and **no install-time class** (a missing `env` definition fails operator writes at the internal
  endpoint's dictionary validation, never a silent tier downgrade — untagged stays non-production
  regardless, per ADR 0030 §3's stated trust dependency). Never collapse the supervised-closes and
  member-proceeds halves into one rule because the sentence reads cleaner.
- **Verify a third-party seam before you build on it.** If a ticket names a library class, method,
  annotation, config property, endpoint, or policy path and reality disagrees — the signature differs,
  the hook does not exist, the property behaves differently — **that is not a blocker to work around
  and not a silent adaptation**: confirm against the artifact (`javap -p` against the jar in
  `~/.gradle/caches/`, the OpenAPI spec under `example-*/src/main/resources/openapi/`, `opa eval`
  against `infra/opa/policies/`, or by reading the repo source), then record the deviation in the
  STATUS *Decisions* section, in the ticket's own words, before proceeding. A plan that named the seam
  from a mental model is a planning defect the run should surface, not absorb.
- **Slice-specific invariants — never trade these away:**
  **(1) Contents open via DIRECT grant only** — the widened role's own `category`/`product` keys;
  never a new inheritance path, never a change to ADR 0031's conjuncts.
  **(2) The tier deny is provenance-scoped** — both clauses require `provenance == "supervised"`; a
  membership decision must be structurally unable to reach them.
  **(3) The three `root_attributes` states stay distinguishable** — absent = unproven (closed),
  `{}` = untagged (non-production, open), tagged = as tagged; **NON_NULL serialization, never
  NON_EMPTY**; and the naive `not root_attributes.env == "production"` shape is forbidden (an absent
  `env` passes a negated comparison — use the two-clause shape).
  **(4) Nothing tier-related enters `filter`** — the list decision lands at the coarse gate;
  a `root_attributes` predicate in the residual is a slice-boundary breach.
  **(5) Additive only** — the record change keeps every existing input byte-identical and every
  existing library test unmodified-green; `TagDefinitionView` tolerates the field's absence;
  **exactly one policy change: T4's four `denied` clauses in `category.rego` + `product.rego`.** Any
  other policy edit means you have left the slice boundary.
  **(6) The tag is operator-managed end to end** — no public write path may change an
  operator-managed key's presence-or-value (delta-based, echo passes); `operatorManaged` appears in
  no request schema; the operator path stays in-network.
  **(7) Zero realm diff, zero envelope diff** — no Keycloak change, no `deny_reason`, no `_actions`
  advice change (the omission on supervised child rows is the pinned contract, asserted in E7).
- **Clean-room IP boundary** — never introduce proprietary names, package names, comments, or source.
- **`opa-abac-core` stays Spring-free** — the record change is pure Java/Jackson.
- **`ddl-auto: validate` must pass** — T1 adds a changeset; a clean boot is the proof.
- **Do NOT push, open PRs, or touch `main`.** Local + this branch only. The maintainer pushes.

---

## Operator notes (not part of the prompt)

- **The headline tickets** — **T4** (the tier denies: the mechanism the slice exists for, and the one
  place a careless shape fails **open** — the naive-negation trap), **T3** (the input contract every
  adopter sees; the additivity proof is the whole risk), and **T6** (E4 tier-flip liveness + E5
  unstrippability: the two cells that justify the design).
- **The fail-closed edges to eyeball** — the **absent-state clause** in T4 (deleting or weakening it
  turns an enrichment outage into an open tier — U11's mutation guards exist for exactly this) and
  T3's **failure-to-absent** discipline (an exception escaping the manager's root resolve would turn
  a tag lookup into a member-facing outage; a `{}`-on-failure would silently open the tier).
- **Standalone-value subset** — **T1–T4 (= part 0)**: the dictionary flag, the enforcement, the input
  contract, and the closed policy — everything provable without the rig, leaving supervised contents
  exactly as closed as slice A (the closed-by-absence intermediate state).
- **Rig / e2e specifics** — `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2`; zero realm
  diff (reuse the `sup-*` personas and `mint_token()`); the new runner needs the **catalog service's
  published host port** for `/internal/bootstrap/resource-tags` (the user-service precedent is
  `localhost:28090`-style host `curl`); restart OPA after T4's policy change lands on the rig; E8's
  enumerated non-regression set runs on one flavour (escalate to the directory superset if any listed
  matrix preflight-requires it).
- **CI does not run the rig yet** — the newman matrix is a local/manual gate; a compose-up→newman CI
  job is a tracked follow-up.
- **Context management** — if the window grows long mid-run, finish the ticket, stop at its checkpoint,
  and resume in a **fresh session** (the ticket status table + STATUS notes are the handoff); sub-agents
  are for scouting/validation only, never the implementation.
- **This package declares execution parts** (`00-DESIGN` §Execution parts: part 0 = T1–T4 · part 1 =
  T5–T6). Under `/autonomous-implement` the partition runs **orchestrated** — each part a
  fresh-context subagent, delegated sequentially and collected from disk (guide §4a). The
  fresh-session note above is the **manual fallback** for a bare-prompt paste, not the primary path;
  the "do not delegate the implementation" rule binds the implementer (the part-runner), exactly as
  §4a reconciles it. Part-brief authoring note: T4 and T6 both touch
  `docs/guides/TEAM-BASED-AUTHORIZATION.md` — ownership is split at the **subsection** level (T4 owns
  the tier subsection; T6 appends only the e2e/E6-flip paragraph), so briefs must not declare the
  whole file exclusive to either part.
- **Workflow-as-artifact:** keep this prompt verbatim; the `STATUS-0N.md` notes record each ticket's
  outcome. Move the folder to `docs/to-do/implemented/` on ship.
