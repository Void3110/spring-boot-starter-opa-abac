---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/opa
  - area/spring
---

# Step-up elevation — autonomous implementation prompt

> **What this is.** A self-contained prompt for implementing the step-up-elevation slice
> autonomously, ticket by ticket, with an architecture-review gate and a checkpoint after each
> ticket. The design and work list it refers to live alongside it in this folder.
>
> **Before you run it:** the planning package must already be **on the base branch** (this folder +
> the ADR 0030 amendments merged to `main` — they exist only on the planning branch until then).
> Create the branch — `git checkout -b feature/void3110/step-up-elevation` off a clean `main`.
> Confirm `git config --local user.email` is `void31102025@gmail.com`. Then paste the **PROMPT**
> section below to the agent.

---

## PROMPT

You are implementing **the step-up-elevation slice** on branch
`feature/void3110/step-up-elevation`.

**The problem.** Slice B (PRODUCTION-TIER) closed a supervisor's **production** contents with a
plain 403 and made the `env` tier unstrippable; oversight that can never open production is a wall,
not a gate. This slice adds the **elevation**: the resource server reads `acr` + `auth_time` from
the token (pure configuration — the ingestion seam exists), the policy adds an `elevated` predicate
and narrows the production deny by it, and when elevation is the **sole blocker** the decision
carries a structured `deny_reason` that the library's advice maps to a **401 + RFC 9470
`WWW-Authenticate` challenge**. The client re-authenticates with `max_age` + an essential `acr`
claim, Keycloak's conditional level-2 flow demands TOTP, and the next read is 200 — until the
freshness window closes (refresh **preserves** `auth_time`, measured — refresh cannot launder).
**The headline:** `sup-anna` hits her report's production catalog, answers one TOTP, and reads it
for five minutes; re-auth *without* `max_age` provably stays stuck (the loop-prevention negative);
an out-of-unit supervisor learns nothing (plain 403, no challenge); and an agent-marked call
(the `act_chain` delegation claim) is refused outright, any tier — **the supervised path is
human-only** (an "elevated agent" token is unmintable on this rig; that contract is pinned by
constructed-input `opa test`). **Explicitly NOT
in this slice:** the SPA challenge UX (a collaborative follow-up), any SMS/second-factor plugin
beyond TOTP, a supervised agent read-out, audit persistence/retention, the request-pattern
`OpaAuthorizationManager` (stays boolean), and any Keycloak change beyond T1's realm-export edits.

Implement the core work directly. Do not delegate the implementation to a sub-agent. Sub-agents are
welcome for **read-only scouting** and for **log-noisy validation** (run a newman matrix / a long
build and report back only the failure summary) — their findings come back to you; the code, tests,
and docs are written in this loop.

### Read before you start (in order)

1. `STEP-UP-ELEVATION.md` (this folder's index) — what this slice delivers, the nine pins, the
   ticket status table, conventions.
2. `00-DESIGN.md` — the mechanism (§1–7), the fail-closed posture, considered-&-rejected, and the
   execution parts. §6's three-prong agent closure is seam-verified — build what it says, not a
   remembered simplification.
3. `01-DECOMPOSITION.md` — the 6 tickets, each with Goal / Deliverables / Acceptance /
   What-NOT-to-touch. **This is your work list.**
4. **The pinned decisions** — `docs/architecture/adr/0030-step-up-decision-contract.md` **§5–9 AND
   §Amendments** (the five 2026-08-13 amendments are load-bearing: sole-blocker, elevation-proof
   unproven tier, the mirrored window, the human-only supervised path, the diagnosis note),
   `0032-root-attribute-enrichment-input-contract.md` (the input contract you consume — and its
   §Consequences note that `HierarchicalAuthorizer` is tier-unaware), `0031` (inheritance stays
   confined), `0029` (the scope machinery), and `0028` (the agent-surface line: the tool-gate
   narrows, the target-gate decides — this slice adds a target-side input, never a tool-gate
   change).
5. `10-QA-TEST-CASES.md` — the U1–U20 / I1–I5 / E1–E9 cases your work must satisfy.
6. **Context you will be checked against** in the review gate (step 5):
   `opa-abac-spring-security/src/main/java/dev/dmitriikonovalov/opaabac/security/OpaPreAuthorizeAuthorizationManager.java`
   (the single-decision path `decide()` replaces; the enrichment + memo seams B added — read
   `resolveCheck`, `enrichWithRootAttributes`, `resolveRootAttributes` before touching anything),
   `.../security/AbstractProblemAdvice.java` (the handler the 401 branch extends),
   `opa-abac-core/.../HttpOpaClient.java` (the parse discipline `decide()` mirrors),
   `.../security/resilience/ResilientOpaClient.java` (**the default-method trap**: it implements
   `OpaClient`, so without an explicit `decide()` override the inherited default delegates to its
   own guarded `allow()` and silently swallows every reason — the override is mandatory),
   `.../security/SubjectClaimsConfig.java` + `JwtClaimsSubjectExtractor.java` (the ingestion seam —
   `convertValue(node, Object.class)` preserves number types),
   `example-catalog-management-service/.../config/CatalogListAuthorizer.java` (the two-leg query
   the supervised-leg agent guard lands in),
   `infra/opa/policies/category.rego` + `product.rego` (B's tier denies incl. the shape-tolerant
   `root_env_values` — your `not elevated` conjunct amends the production clause ONLY) +
   `catalog.rego` (has `denied`/`not denied` machinery — the agent deny lands there too),
   `infra/keycloak/realm-export.json` (the literal `defaultClientScopes` list you are fixing), and
   `docs/to-do/implemented/PRODUCTION-TIER/` (the slice being extended — its STATUS notes carry
   decisions you build on).
7. Root `CLAUDE.md` — the **IP boundary** (clean-room: original names only) and the **commit
   identity** rule (`Void3110 <void31102025@gmail.com>`).
8. `infra/README.md` — the local rig, incl. the **in-network token caveat**, the **restart-OPA**
   gotcha, and the supervised/production-tier matrix sections this slice extends.
9. **Prime Mulch:** `ml prime opa-abac-authz-model spring-security-integration --budget 8000`,
   then `ml prime rego-policy --budget 8000` before T2 and
   `ml prime opa-abac-rig-deploy-ops opa-abac-e2e-suite --budget 8000` before T1/T5/T6 (domain
   table in root `CLAUDE.md`). Directly relevant records: **mx-df8f2b** (the two-clause tier shape
   + its cardinality twin — the shapes your conjunct amends), **mx-30d510** (the presence-test
   discriminator — `actor=false` is the recorded escape), **mx-afd666 + mx-a85001** (the P0
   probe + its diagnosis correction: the scopes fix, the Secure-cookie-over-http 400 gotcha the
   miner must handle, refresh-preserves-auth_time), **mx-9fef93** (poll a REAL OPA decision, never `/health`; the
   podman-build + side-load workaround), **mx-951d2f** (never count a fail-closed result as a
   breaker failure — the `decide()` override inherits this), **mx-fb443b** (the closed `errorCode`
   enum discipline), and the `opa-abac-e2e-suite` records on vacuous newman assertions and the
   herestring-swallowed-exit trap.

### Per-ticket loop (tickets T1 → T6, IN ORDER — strictly sequential; T2/T3 are mutually independent but are executed in sequence here; T1–T4 are the standalone-value subset)

For each ticket do ALL of the following, in order, and **STOP at the checkpoint before the next**:

1. **Prime for the files you're about to touch.** `ml prime --files <path>`, and re-read that
   ticket's section in `01-DECOMPOSITION.md`.

2. **Build the deliverables** exactly as `01-DECOMPOSITION.md` lists them — exact classes, packages
   (`dev.dmitriikonovalov.opaabac.core` for T3's records,
   `dev.dmitriikonovalov.opaabac.security{,.resilience}` for T3/T4's library side,
   `dev.dmitriikonovalov.example.catalog.config` for T4's example side,
   `infra/keycloak/realm-export.json` for T1, `infra/opa/policies/` for T2,
   `scripts/postman/` for T5/T6), mappings, rego rules. Match the surrounding code's naming and
   idioms. **Clean-room:** no proprietary names anywhere.

3. **Write/extend the tests** for the ticket (the relevant U*/I*/E* cases from
   `10-QA-TEST-CASES.md`). Core/client tests use an **in-process
   `com.sun.net.httpserver.HttpServer` stub** (no WireMock). Persistence/IT tests run against
   **real Postgres via Testcontainers** (never H2). Policies use `opa test` with **pinned clocks**
   for every window case — **T2 is the slice's ONLY policy ticket** (three files + `step_up.json`
   + their tests, incl. the per-clause deletion-mutation guards). Every other ticket leaves the
   corpus untouched, and `opa test infra/opa/policies/` must be green at every checkpoint.

4. **Compile + run unit tests until green.** `./gradlew :<module>:test` (and `./gradlew build` for
   the example/IT tickets). Fix-until-green.

5. **★ ARCHITECTURE REVIEW + REFACTOR — the gate before integration/e2e validation.** Once unit
   tests pass, do NOT advance yet. Run a focused self-review, then refactor and re-test:
   - **Fail-closed check — the slice's load-bearing invariant: elevation can only NARROW what deny
     covers — no token state, parse failure, outage, or missing input ever yields a wider result
     than success, and the unproven tier stays closed for everyone.** Missing/unmapped `acr` or
     `auth_time` ⇒ `elevated` undefined ⇒ the production deny holds; a malformed or outage-shaped
     reason ⇒ a plain deny at the layer it arose; a half-formed challenge is never emitted; audit
     failure changes no decision.
   - **Security check — name the widening that would matter for this ticket** (a `deny_reason`
     that leaks tier or scope information to a subject who is not one elevation away from allow;
     the challenge emitted for a write or an agent; a fabricated reason during an OPA outage
     sending users into a TOTP treadmill; the `act_chain` presence-test regressing to truthiness; the
     supervised list leg reachable by an agent; a realm-export edit that widens an existing
     client's scopes beyond the two named) **and state why it cannot happen.**
   - **Sole-blocker completeness check (T2 especially)** — `denied_other` must cover **every**
     deny in the file except the step-up clause; a deny class missed there turns into a leaked
     challenge. Enumerate the file's deny clauses in the STATUS note and tick each against
     `denied_other`.
   - **Wiring check** — every seam this ticket adds (the `decide()` default + overrides, the new
     records, `StepUpRequiredDecision`, the advice branch, the audit logger, the enum constant +
     yaml enum entry, the leg guard, the miner's flags, the drill) has a **named consumer** and a
     test through its **non-happy path**; zero call sites = the ticket is not done.
   - **Boundary / additivity check — `opa-abac-core` stays Spring-free; `allow()`/`allowAll()`
     byte-identical; every existing library test passes UNMODIFIED; the request-pattern
     `OpaAuthorizationManager`, `opa-abac-spring-data`, `HierarchicalAuthorizer`, the tool-gate
     (`agent_tools.rego`), the SPA, and every `filter`/`bulk` entrypoint are byte-for-byte
     unchanged.**
   - **Pattern-reuse check** — the additive-default-method evolution (T3 mirrors B's T3 record
     pattern), the `RequestAttributesResourceCache`/memo idiom (read, don't re-invent), the
     existing supervised-degrade shape for the leg guard, B's per-clause mutation-guard
     discipline, and the established problem+json advice shape must be **reused, not reinvented**.
   - **SOLID / decomposition** — cohesive (SRP), depends on interfaces (DIP); anything to
     split/simplify?
   - **Static-analysis gate — the local Sonar scan is CLEAN on the changed files.** For a ticket
     that touches `.java`, run `./.sonar-local/sonar-local.sh`. `ml prime quality-gate-sonar`
     before judging a non-clean result — documented FP classes are not re-fixed; a real finding is
     fixed in this ticket's commit.
   - **Apply** the refactoring the review surfaces, then **re-run the unit tests** to confirm
     green. Write a short note of what the review found + what you refactored into
     `STATUS-0N.md`. If it found nothing substantive, say so explicitly — **do not invent churn.**

6. **Integration / e2e validation (MANDATORY for the relevant tickets).**
   - T1: the down-first re-import + the I5 probe (Keycloak alone suffices — the full rig is not
     required). T4: I1–I4 (MockMvc + Testcontainers). Fix-until-green.
   - T5: E9 against the up rig (`ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2`
     after a `./deploy.sh down` — the realm changed).
   - T6: the full matrix — `cd scripts/postman && ./run-step-up-matrix.sh` (E1–E7; the runner
     restarts OPA and polls a **real decision**, never `/health`), the agent cells on the
     `ENABLE_MCP=1` flavour, **plus the E8 non-regression enumeration** — the ten named runners,
     each run and green or skipped with the reason **recorded in `STATUS-06.md`** (if a matrix
     preflight-requires a superset flavour, run the whole set on it). **Production-tier's seven
     enumerated C-flip cells (E2a–E2d, E4b, E4c, E5d) are rewritten by this ticket to the
     401 + challenge shape — exactly those, nothing else** (T6's What-NOT names them). Honor the
     in-network token
     caveat; images rebuilt via the podman-build + side-load workaround if Docker's pull wedges.
     Fix-until-green.

7. **Update documentation (after each ticket).** Tick the ticket in the `STEP-UP-ELEVATION.md`
   status table; record real values/decisions in `STATUS-0N.md`. **Documentation deltas ride the
   ticket that introduces the mechanism** — T1: the realm note + down-first in `infra/README.md`;
   T2: none (policy comments carry the contract); T3: the decision-envelope section in
   `docs/guides/ABAC-AUTHORIZATION.md`; T4: the *Step-up elevation* subsection in
   `docs/guides/TEAM-BASED-AUTHORIZATION.md` (T6 appends only the e2e paragraph there); T5: the
   code-flow token path section in `docs/guides/E2E-TESTING.md`; T6: the postman README
   registry/runner rows + `infra/README.md`'s matrix section. Root/project `CLAUDE.md` only if a
   new build/run step matters.

8. **Mulch expertise check (after each ticket).** `ml prime`/skim, then record any genuine
   reusable insight and `ml sync`. **Before `ml sync`, `git restore --staged .`** (the
   swept-staged trap). Skip recording only if nothing is non-obvious.

9. **Commit** — one focused commit on this branch (code + tests + docs + the `STATUS-0N.md` note
   together). Identity `Void3110 <void31102025@gmail.com>`. Conventional subject
   `feat(step-up-elevation): …`. A `Co-Authored-By: Claude` trailer is welcome.

10. **CHECKPOINT — STOP and report.** Summarize what shipped, paste the unit + integration/e2e
    summary, **summarize the review findings + the refactoring you applied** (step 5), list docs
    updated, and note any open question you resolved. Then proceed to the next ticket. **Do not
    batch tickets without a checkpoint.**

### Permissions / autonomy granted (do these WITHOUT asking)

- Create/modify library code (`opa-abac-core`, `opa-abac-spring-security` — T3/T4's additive
  changes only), example code (catalog service — T4's config + leg guard), the realm export
  (T1 only), the three rego files + `step_up.json` + tests (T2 only), the postman suite (T5/T6),
  docs in this folder + the guides, and Mulch — all on this branch.
- Stand up / tear down / reseed the local rig (`./profile.sh`, `./deploy.sh`, the
  `ENABLE_MCP=1` flavour for the agent cells); **`./deploy.sh down` before any flavour that needs
  the new realm** (it re-imports); reset fixtures; rebuild images (podman side-load if needed);
  restart OPA; run the drill's data override and restore it.
- Configure the live Keycloak via admin console/API **for T1's spike only**, then transplant the
  exported JSON into the realm export (the export stays the source of truth).
- Bring up the local Sonar stack for the ★gate's static-analysis check.
- Fix any issue your own validation reveals (compile, unit, IT, e2e, refactor). Iterate until
  green.
- Commit per ticket on this branch.

### Hard rules

- **Report at every checkpoint and continue.** Don't batch tickets.
- **The architecture-review + refactor step (5) is NOT optional and happens BEFORE
  integration/e2e validation** — unit green → review → refactor → re-test → then ITs/e2e.
  Document what it found.
- **Fix-until-green within the ticket.** Only STOP mid-ticket if genuinely *blocked*: the same
  root cause survives ≥3 focused attempts, OR a design decision the docs don't cover is needed,
  OR a local prerequisite is unrecoverable.
- **Fail-closed is the load-bearing invariant: elevation can only narrow what deny covers — no
  token state, parse failure, outage, or missing input ever yields a wider result than success,
  and an unproven tier is a closed tier for everyone.** Where the slice defines more than one
  failure *class*, each lands where the design says: missing/malformed claims ⇒ not elevated ⇒
  the deny holds (never a 5xx); a malformed wire reason ⇒ plain deny at the parse; an
  outage-shaped result ⇒ plain deny at the wrapper (**never a fabricated reason**); a partial
  reason ⇒ plain 403 at the advice (**never a half-formed challenge**); audit failure ⇒ the
  decision stands. Never collapse these into one rule because the sentence reads cleaner.
- **Verify a third-party seam before you build on it.** If a ticket names a library class, method,
  annotation, config property, endpoint, flow-JSON shape, or policy path and reality disagrees —
  confirm against the artifact (`javap -p` against the jar, the OpenAPI spec, `opa eval`, the
  live-exported realm, or the repo source), then record the deviation in the STATUS *Decisions*
  section before proceeding. A plan that named a seam from a mental model is a planning defect the
  run should surface, not absorb.
- **Slice-specific invariants — never trade these away:**
  **(1) Sole-blocker `deny_reason`** — emitted iff `stepup_denied ∧ granted ∧ not denied_other`;
  a subject who is not exactly one elevation away from allow gets a plain deny, always.
  **(2) The unproven tier is elevation-proof** — the absent-`root_attributes` clause gains no
  conjunct; an enrichment outage is a closed tier for elevated supervisors too.
  **(3) Additive envelope** — `decide()` is a default method; `allow()`/`allowAll()`
  byte-identical; every existing library test unmodified-green; **`ResilientOpaClient` overrides
  `decide()` explicitly** (the default-method trap) and every fail-closed outcome carries a null
  reason.
  **(4) Exactly one policy ticket: T2** — three files + `step_up.json`; any other policy edit
  means you have left the slice boundary.
  **(5) Nothing elevation- or agent-related enters `filter` or `bulk`** — the residual and the
  batch stay boolean and byte-identical.
  **(6) The supervised path is human-only, three prongs** — the **`act_chain`** wire claim
  ingested (config — `actor` is the MCP server's internal name and never reaches this service),
  the provenance-scoped agent deny in all three leaf policies (presence-test on the `act_chain`
  key, never truthiness), and the supervised list leg skipped app-side for `act_chain`-bearing
  subjects; the tool-gate is untouched.
  **(7) One freshness window** — the policy data's `max_age` and the realm's level-2 max age are
  the same number, cross-referenced; the advice's challenge carries the reason's values, never a
  local copy.
- **Clean-room IP boundary** — never introduce proprietary names, package names, comments, or
  source.
- **`opa-abac-core` stays Spring-free** — T3's records are pure Java/Jackson.
- **Do NOT push, open PRs, or touch `main`.** Local + this branch only. The maintainer pushes.

---

## Operator notes (not part of the prompt)

- **The headline tickets** — **T2** (the policy composition: the sole-blocker factoring is the one
  place a missed deny class leaks a challenge — the `denied_other` completeness check exists for
  exactly this), **T4** (the emitter every adopter sees; the additive-advice discipline is the
  risk), and **T6** (E2 round-trip liveness + E3 loop-prevention: the two cells that justify the
  design; E6 the human-only proof).
- **The fail-closed edges to eyeball** — the **`elevated` undefined-input discipline** in T2 (a
  type-coerced `auth_time` or a truthiness `actor` test would fail open), the
  **`ResilientOpaClient.decide` override** in T3 (forgetting it silently swallows every reason —
  or worse, a naive override fabricates one during an outage), and the **partial-reason 403
  fallback** in T4 (a half-formed challenge is the §7 infinite loop).
- **Standalone-value subset** — **T1–T4 (= part 0)**: the realm, the policy, the envelope, the
  emitter — everything provable without the full rig; the safe intermediate state is "the realm
  can mint `aal2` but nothing deployed requests it" (ordinary logins stay `aal1`; production
  stays a plain-shaped deny until part 1's miner).
- **Rig / e2e specifics** — `./deploy.sh down` before the first post-T1 up (realm re-import);
  restart OPA after T2 reaches the rig and poll a **real decision**; the drill restores via OPA
  restart in an EXIT trap; the agent cells need `ENABLE_MCP=1`; catalog host ports are
  `BASE_PORT=28080`+index (verify with `docker ps`); Docker-pull wedges → podman-build +
  side-load (Mulch `opa-abac-rig-deploy-ops`).
- **CI does not run the rig** — the newman matrices are a local/manual gate.
- **Context management** — if the window grows long mid-run, finish the ticket, stop at its
  checkpoint, and resume in a **fresh session** (the ticket status table + STATUS notes are the
  handoff); sub-agents are for scouting/validation only, never the implementation.
- **This package declares execution parts** (`00-DESIGN` §Execution parts: part 0 = T1–T4 ·
  part 1 = T5–T6). Under `/autonomous-implement` the partition runs **orchestrated** — each part a
  fresh-context subagent, delegated sequentially and collected from disk (guide §4a). Part-brief
  authoring notes: **(a)** `docs/guides/TEAM-BASED-AUTHORIZATION.md` ownership is split at the
  **subsection** level (T4 owns the *Step-up elevation* subsection; T6 appends only the e2e
  paragraph) — do not declare the whole file exclusive to either part; **(b)** the same split for
  `infra/README.md` (T1 owns the realm note; T6 appends the matrix section); **(c)** part 1's
  brief must carry T5's **frozen miner contract** (E9) and the catalog service's published host
  ports; **(d)** declared untracked deliverables: none (both parts).
- **Workflow-as-artifact:** keep this prompt verbatim; the `STATUS-0N.md` notes record each
  ticket's outcome. Move the folder to `docs/to-do/implemented/` on ship.
