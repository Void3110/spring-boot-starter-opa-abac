---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/opa
  - area/spring
  - area/mcp
---

# MCP auth library (`opa-abac-mcp`) — autonomous implementation prompt

> **What this is.** A self-contained prompt for implementing the MCP-AUTH-LIBRARY slice autonomously, ticket by
> ticket, with an architecture-review gate and a checkpoint after each ticket. The design and work
> list it refers to live alongside it in this folder.
>
> **Before you run it:** the branch already exists and carries this package — the design, ADR 0036
> and the decomposition were committed on it — so do **not** create it from `main`: check out
> `feature/void3110/mcp-auth-library` with a clean tree (rebase it onto `main` first if `main` has
> moved). Confirm `git config --local user.email` is `void31102025@gmail.com`. Then paste the
> **PROMPT** section below to the agent — or run `/autonomous-implement MCP-AUTH-LIBRARY`, which
> resolves the two execution parts declared in `00-DESIGN.md` (ORCHESTRATOR mode).

---

## PROMPT

You are implementing **the MCP-AUTH-LIBRARY slice** on branch `feature/void3110/mcp-auth-library`.

**The problem.** Agent tool-call authorization (ADR 0028 — principal ceiling ∩ agent capability, in
Rego, in front of the target service's unchanged policies) exists only inside `example-mcp-server`, and its
wiring is safe only for that example's own configuration: it wraps one of the MCP SDK's four
tool-specification types, and pairs with a policy that recognizes an agent call by an unversioned key. This
slice extracts the gate into a published, **incubating** module `opa-abac-mcp`, auto-configured by the
starter, and turns every edge that is safe in the example but fail-open elsewhere — an unwrapped
specification type, a disabled starter, a missing ceiling source, an agent client without its actor mapper,
a v1-aware policy meeting a later input version — into a startup failure or a deny. **Part 0 (T1–T4) moves code and changes no
behavior; part 1 (T5–T8) adds the new edges.** NOT in scope: a supported `tools/list` installer (waits for
java-sdk #578 — the reflective one stays in the example), a thread-independent identity carrier, a Java
decision path (OPA stays required), WebFlux, Spring AI 2.1, and releasing 1.5.0.

Implement the core work directly. Do not delegate the implementation to a sub-agent. Sub-agents are
welcome for **read-only scouting** (e.g. "find every place across the suite that asserts X") and for
**log-noisy validation** (e.g. run a newman matrix / a long build and report back only the failure
summary) — their findings come back to you; the code, tests, and docs are written in this loop.

### Read before you start (in order)

1. `MCP-AUTH-LIBRARY.md` (this folder's index) — what this slice delivers, the file glossary, the ticket
   status table, the critical path, conventions.
2. `00-DESIGN.md` — what moves / stays / is replaced, the startup-failure table, the fail-closed table,
   the part bars, and the sixteen settled forks (considered-&-rejected).
3. `01-DECOMPOSITION.md` — the 8 tickets, each with Goal / Deliverables / Acceptance /
   What-NOT-to-touch. **This is your work list.**
4. **The pinned decisions** — ADR 0036 (the contract, incl. §12 for ADRs 0037/0038 and §13, the
   decomposition pins); bound by ADR 0028 (the two-layer model, not reopened), ADR 0020 (optional-module
   packaging), ADR 0023 (the request memo), ADR 0037 (the "could not decide" family), ADR 0038 (the
   disabled-starter guard — whose acknowledgment does NOT extend to tools).
5. `10-QA-TEST-CASES.md` — the unit / integration / e2e cases your work must satisfy.
6. **Context you will be checked against** in the review gate (step 5):
   `docs/to-do/implemented/AGENT-TOOL-AUTHZ/` (the slice whose code moves — its STATUS notes record why
   each fail-closed branch exists), `docs/to-do/implemented/USER-DIRECTORY-PORT/` (an optional library
   module + a `compileOnly` starter auto-configuration), `docs/guides/AGENT-TOOL-AUTHORIZATION.md`,
   `opa-abac-spring-boot-starter/.../autoconfigure/OpaDirectoryAutoConfiguration.java`,
   `OpaResolveMemoAutoConfiguration.java` (bean-level BPP decoration), `OpaAbacDisabledAutoConfiguration.java`
   (the `Binder` read), and `opa-abac-spring-security/.../MemoizingRoleDefinitionSupplier.java`.
7. Root `CLAUDE.md` — the **IP boundary** (clean-room: original names only) and the **commit identity**
   rule (`Void3110 <void31102025@gmail.com>`).
8. `infra/README.md` — the local rig (needed for the e2e ticket), incl. the **in-network token caveat**
   and the **"restart OPA after editing a policy"** gotcha.
9. **Prime Mulch:** `ml prime spring-security-integration opa-abac-authz-model --budget 8000`; before T7
   also `ml prime rego-policy --budget 8000`; before T4/T6/T7's rig runs `ml prime opa-abac-e2e-suite
   opa-abac-rig-deploy-ops --budget 8000`; before T1/T8 `ml prime opa-abac-publish opa-abac-sb4-integration
   --budget 8000` (the catch-all `opa-abac` domain was retired — use the domain table in root `CLAUDE.md`).
   Read these records in full: mx-1ff28e (the settled forks), mx-ece7be + mx-b61225 (what the original
   AGENT-TOOL-AUTHZ run and its review found — the real-adapter lesson and the every-reader-of-a-switch
   lesson), mx-b30c68 (grep guides + collections before reversing a behavior).

### Per-ticket loop (tickets T1 → T8, IN ORDER; two execution parts — part 0 = T1–T4, part 1 = T5–T8; no parallel landings)

For each ticket do ALL of the following, in order, and **STOP at the checkpoint before the next**:

1. **Prime for the files you're about to touch.** `ml prime --files <path>`, and re-read that ticket's
   section in `01-DECOMPOSITION.md`.

2. **Build the deliverables** exactly as `01-DECOMPOSITION.md` lists them — exact classes, packages
   (`dev.dmitriikonovalov.opaabac.mcp.{identity,tool,authz}`, `dev.dmitriikonovalov.opaabac.autoconfigure`,
   `dev.dmitriikonovalov.example.mcp.*`), mappings, rego rules. Match the surrounding code's naming and idioms.
   **Clean-room:** no proprietary names anywhere.

3. **Write/extend the tests** for the ticket (the relevant U*/I*/E* cases from `10-QA-TEST-CASES.md`).
   Core/client tests use an **in-process `com.sun.net.httpserver.HttpServer` stub** (no WireMock).
   Persistence/IT tests run against **real Postgres via Testcontainers** (never H2). Policies use
   `opa test` (T7; no partial evaluation in this slice). Starter wiring uses `ApplicationContextRunner`.

4. **Compile + run unit tests until green.** `./gradlew :<module>:test` (and `./gradlew build` for the
   example/IT tickets). Fix-until-green.

5. **★ ARCHITECTURE REVIEW + REFACTOR — the gate before integration/e2e validation.** Once unit tests
   pass, do NOT advance yet. Run a focused self-review, then refactor and re-test:
   - **Fail-closed check — every tool call that cannot be decided is denied with its own code, the roster
     hint is empty or unfiltered exactly as the design's table says, and every configuration that would serve
     a tool without the gate (an unwrappable specification type, no `OpaClient`, no ceiling supplier,
     WebFlux, declarations ≠ wrapped) fails startup — never a skip, never present-but-inert.**
   - **Security check — the widenings that matter here: an agent call evaluated on the human branch (the
     actor dropped, misread, or an agent client without an actor); a memo (ceiling or capability) serving one
     caller's answer to another — the ceiling memo keys on `(subject, resourceType)`, the capability memo on
     the actor id (its SPI's only argument), and both die with the request; a
     policy-path decorator that sends a non-`tool` decision to the tool document or vice versa; a denial
     message or WARN that leaks a source's response body. State why each cannot happen.**
   - **Concurrency / idempotency check — no mutation is gated in this slice; the concurrency risk is
     threading: every wrapper decides on the thread that invoked its handler (before any `Mono` exists) and
     never re-decides at subscription; the authorization's reads of thread-bound state
     (`SecurityContextHolder`, request attributes) happen only there — the async result decoration may read
     the failure record at emission, and absent request state leaves the result unchanged; a resubscribed
     `Mono` does not call the authorizer again; startup guards stay eager under lazy initialization.**
   - **Wiring check** — every seam this ticket adds (an SPI, a property, a guard, an exception + advice
     mapping, a cache accessor, a rego entrypoint, a recovery edge) has a **named consumer** and a test
     through its **non-happy path**; zero call sites = the ticket is not done.
   - **Boundary / additivity check — `opa-abac-core` is untouched (it stays Spring-free); no existing
     module's public API changes — the starter only adds `OpaMcpAutoConfiguration` and one `.imports` line;
     the mechanical cost is the moved tests' ceiling stubs (T2) and the example's deleted wiring (T3).**
   - **Module-layer separation — `opa-abac-mcp` holds types and logic and declares no beans; the starter
     owns every bean and post-processor; the example owns its catalog declarations, its ceiling source and the
     reflective roster installer; the module's main (published) scope never depends on the starter, the
     example or Spring AI — wiring tests that need a running server live in the starter's test sources (T5).**
   - **Pattern-reuse check — the keycloak-directory module's build + `compileOnly` wiring (ADR 0020); the
     memo decorator's tri-state replay (`MemoizingRoleDefinitionSupplier`); bean-level BPP decoration
     (`OpaResolveMemoAutoConfiguration`); the `Binder` read (`OpaAbacDisabledAutoConfiguration`); the
     example's own decide-eagerly / apply-lazily split (`RosterFilterInstaller.RosterFilteringHandler`).**
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
   - T3: the example's `@SpringBootTest` boots on the auto-configuration (I1). T5: the four per-type ITs
     over a real `POST /mcp` (`RANDOM_PORT`, never MockMvc) and the customizer IT (I2–I6). T7: `opa test
     infra/opa/policies` (all green, including the new schema cases). No Postgres in this slice.
     Fix-until-green.
   - e2e tickets T4, T6, T7: rebuild the MCP image from the current commit (`ENABLE_MCP=1 ./deploy.sh
     build` — `up` only builds it when absent), bring the rig up (`ENABLE_MCP=1 ./deploy.sh up --pods 2`;
     the flag force-enables OIDC, OPA and the user-service), then `cd scripts/postman &&
     ./run-agent-tool-matrix.sh`. Honor the in-network token caveat and restart OPA after a rego edit
     (T7). Fix-until-green.

7. **Update documentation (after each ticket).** Tick the ticket in the `MCP-AUTH-LIBRARY.md` status table;
   record real values/decisions in `STATUS-0N.md`. The ticket that finalizes a guide topic
   writes/reconciles `docs/guides/AGENT-TOOL-AUTHORIZATION.md` (each ticket owns its own delta — see
   its Deliverables; never parked in T8). Root/project `CLAUDE.md` only if a new build/run step matters.

8. **Mulch expertise check (after each ticket).** `ml prime`/skim, then record any genuine reusable
   insight (`ml record <domain> --type <pattern|decision|failure|reference> …` — use the domain table
   in root `CLAUDE.md`; the catch-all `opa-abac` domain was retired) and `ml sync`. **Before
   `ml sync`, `git restore --staged .`** so the sync commit touches `.mulch/` **only** (the
   swept-staged trap). Skip recording only if nothing is non-obvious.

9. **Commit** — one focused commit on this branch (code + tests + docs + the `STATUS-0N.md` note
   together). Identity `Void3110 <void31102025@gmail.com>`. Conventional subject `feat(mcp): …` (or
   `refactor(mcp): …` for part 0's moves, `ci: …` for T8's step).
   A `Co-Authored-By: Claude` trailer is welcome.

10. **CHECKPOINT — STOP and report.** Summarize what shipped, paste the unit + integration/e2e summary,
    **summarize the review findings + the refactoring you applied** (step 5), list docs updated, and
    note any open question you resolved. Then proceed to the next ticket. **Do not batch tickets
    without a checkpoint.**

### Permissions / autonomy granted (do these WITHOUT asking)

- Create/modify the new `opa-abac-mcp` module, the starter (an added auto-configuration only), the BOM and
  the root build's publish allow-list, `example-mcp-server`, `agent_tools.rego` + its test (T7 only),
  `infra/compose.mcp.yaml`, `deploy.sh` (only if a drill needs it), `.github/workflows/ci.yml` (T8's one
  step), `gradle/libs.versions.toml` (T1's `mcpSdk`, T8's `springBootCompat`), `settings.gradle.kts`, tests,
  docs in this folder + the guides + the READMEs + `RELEASING.md` + `CHANGELOG.md` (under the existing
  `[Unreleased] — 1.5.0` heading only) + root `CLAUDE.md`, the `scripts/postman/` agent-tool runner/collection
  (T1's E5-check path, T2's one header comment, T6's cell), and Mulch — all on this branch.
- Stand up / tear down / reseed the local rig (`./profile.sh`, `./deploy.sh`, `ENABLE_MCP=1 ./deploy.sh up --pods 2`);
  reset fixtures; rebuild images; restart OPA; drop/recreate the **local** schema if needed.
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
- **Fail-closed is the load-bearing invariant: no error, missing input or misconfiguration ever lets a tool
  call through that a healthy, correctly configured server would deny, and no configuration ever serves a
  tool without the gate.** Fail-closed does **not** always mean
  "deny and continue": where the slice defines more than one failure *class*, each lands where the
  design says — a request-time failure may degrade to the narrower result while an
  install/startup-time failure **fails the context outright** rather than booting silently degraded.
  This slice has **two classes**: a **startup/installation** failure (no `OpaClient` — even under
  `opa.abac.enabled=false` + `opa.abac.allow-ungated-methods=true`; no `PrincipalCeilingSupplier`; no
  `ToolRegistry`; declarations ≠ wrapped; a specification type the module does not wrap — in part 0 every
  type but stateful sync; WebFlux; a wiring check) **fails the context**, eagerly, even under lazy
  initialization; a **request-time** failure denies that call with its own code (`tool-gate-unauthenticated`,
  `-identity-unreadable`, `-capability-unavailable`, `-ceiling-unavailable`, `-policy-unavailable`,
  `-actor-required`, `tool-undeclared`, `tool-gate-denied`) while the roster hint lands on **empty** (the
  engine could not decide; all-false; wrong length) or **unfiltered + WARN** (an identity / capability /
  ceiling / actor-required edge outside the batch). Never collapse two
  classes into one rule because the sentence reads cleaner.
- **Verify a third-party seam before you build on it.** If a ticket names a library class, method,
  annotation, config property, endpoint, or policy path and reality disagrees — the signature differs,
  the hook does not exist, the property behaves differently — **that is not a blocker to work around
  and not a silent adaptation**: confirm against the artifact (`javap -p` / `-c` / `-v` against the jar in
  `~/.gradle/caches/modules-2/files-2.1/…`, the autoconfigure class's annotations, `opa eval` / `opa test`),
  then record the deviation in the STATUS *Decisions* section, in the ticket's own words, before
  proceeding. A plan that named the seam from a mental model is a planning defect the run should
  surface, not absorb.
- **Slice-specific invariants:** (1) **part 0 changes no behavior the example can observe** — a moved test
  keeps its assertions (the QA doc's part-0 rule names the only permitted edits); its new outcomes are startup
  failures for misconfigurations plus T3's three named corrections; (2) **never present-but-inert** — no
  property disables the gate (`agent-gate.enabled=false` drops only the agent conjunct); (3) **match in
  Rego** — no Java decision path; (4) **additive library change** — no existing module's public API
  changes, `opa-abac-core` untouched; (5) **the module declares no beans** — the starter wires; (6) **the
  published jar never reflects into another library's private fields** — `RosterFilterInstaller` stays in
  the example; (7) **decide on invocation** — never inside a `Mono`; (8) **every reader of a switch agrees**
  (`agent-gate.enabled`, `agent-clients`); (9) only `agent_tools.rego` + its test change under
  `infra/opa/policies/`; (10) the gate's resource type `tool` is reserved (ADR 0036 §13).
- **Clean-room IP boundary** — never introduce proprietary names, package names, comments, or source.
- **`opa-abac-core` stays Spring-free** — and in this slice it is not touched at all.
- **`ddl-auto: validate` must pass** — no schema change in this slice; nothing to migrate.
- **Do NOT push, open PRs, or touch `main`.** Local + this branch only. The maintainer pushes.

---

## Operator notes (not part of the prompt)

- **The headline tickets** — T3 (the auto-configuration and its prerequisite failures: "add the dependency"
  is the whole install, and a missing piece is loud), T5 (all four specification types gated, proven over real
  HTTP), T7 (jar ↔ policy pairing — the silent-misread class closed).
- **The fail-closed edge to eyeball** — T3's gate post-processor (an element type it skips is an ungated
  tool — it must check every element of every list, and T5 must fail on an unknown type rather than pass it
  through); T3's policy-path decorator (a `tool` decision routed to the wrong document denies everything —
  loud — but a non-`tool` decision routed to the tool document would decide a catalog call with the agent
  policy); T6's guard precedence (a malformed actor must stay `identity-unreadable`).
- **Standalone-value subset** — part 0 (T1–T4): the example's exact behavior as an incubating dependency.
- **Rig / e2e specifics** — rebuild the MCP image every time (`ENABLE_MCP=1 ./deploy.sh build`); the
  runner's E6/E7/E11 drills (and T6's E12) recreate the MCP pod and an EXIT trap restores it; restart OPA
  after T7's rego edit; the in-network token caveat applies (issuer `http://keycloak:8888`). T8's Boot 4.1 run
  and T5's WebFlux test need artifacts the sandbox cannot fetch on this host (PKIX: Boot 4.1.1,
  `spring-webflux`) — each ticket records `**LOCAL PROOF DEFERRED:** PKIX — <artifact>` and continues; the
  maintainer runs the named command outside the sandbox before the PR (or warms the cache before the run), and
  CI on the PR to `main` is the gate — a feature-branch push triggers no CI.
- **Release** — 1.5.0 = ADR 0038 + this slice, one cut after merge (no 1.4.1); the run never touches the
  version, the CHANGELOG heading or a tag.
- **CI does not run the rig yet** — the newman matrix is a local/manual gate; a compose-up→newman CI
  job is a tracked follow-up.
- **Context management** — if the window grows long mid-run, finish the ticket, stop at its checkpoint,
  and resume in a **fresh session** (the ticket status table + STATUS notes are the handoff); sub-agents
  are for scouting/validation only, never the implementation.
- **Workflow-as-artifact:** keep this prompt verbatim; the `STATUS-0N.md` notes record each ticket's
  outcome. Move the folder to `docs/to-do/implemented/` on ship.
