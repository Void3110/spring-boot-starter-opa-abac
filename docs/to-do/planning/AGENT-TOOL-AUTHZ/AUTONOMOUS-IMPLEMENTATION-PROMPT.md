---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/opa
  - area/rego
  - area/spring
---

# AGENT-TOOL-AUTHZ — autonomous implementation prompt

> **What this is.** A self-contained prompt for implementing **agent tool-call authorization
> (Phase 9)** autonomously, ticket by ticket, with an architecture-review gate and a checkpoint after
> each ticket. The design and work list it refers to live alongside it in this folder.
>
> **Before you run it:** create the branch — `git checkout -b feature/void3110/agent-tool-authz` off a
> clean `main`. Confirm `git config --local user.email` is `void31102025@gmail.com`. Then paste the
> **PROMPT** section below to the agent.

---

## PROMPT

You are implementing **agent tool-call authorization (AGENT-TOOL-AUTHZ, Phase 9)** on branch
`feature/void3110/agent-tool-authz`.

**The problem.** The starter answers *"may this **human** act on this resource?"*; an AI agent calling
tools on a person's behalf collapses two identities into one bearer token, so the agent inherits the
**whole** human ceiling and the tool surface itself has no gate at all — the MCP specification stops at
token validation and has no per-tool authorization, no filtered `tools/list`, and no principal/actor
distinction. This slice adds a **new example service**, `example-mcp-server`, whose `@McpTool` methods
proxy the existing catalog REST API with the caller's own bearer and are gated by a **new tool-gate
rego document** that computes **principal ceiling ∩ agent capability in Rego**: two-layer enforcement,
no propagation — the MCP server never asserts a role downstream, and the catalog service independently
enforces the principal ceiling with its policies literally untouched. The headline is that an agent
sees only the tools it may call (a filtered roster that is a *hint*, never a grant) and a denial is a
structured advisory error naming the denying layer, so the model can react. **Explicitly NOT in scope:**
MCP transport/OAuth authorization (the scope fence sits above the validated token), the host-side
`ToolCallingManager` leg, HITL/approval flows, policy-filtered retrieval, `listChanged` notifications, a
real LLM in the loop, extracting an `opa-abac-agent` library module (the slice's exit criterion, a
follow-up), and any change to `example-catalog-management-service`, `example-user-management-service`,
the existing rego, or any `opa-abac-*` module.

Implement the core work directly. Do not delegate the implementation to a sub-agent. Sub-agents are
welcome for **read-only scouting** (e.g. "find every place across the suite that asserts X") and for
**log-noisy validation** (e.g. run a newman matrix / a long build and report back only the failure
summary) — their findings come back to you; the code, tests, and docs are written in this loop.

### Read before you start (in order)

1. `AGENT-TOOL-AUTHZ.md` (this folder's index) — what this slice delivers, the file glossary, the ticket
   status table, the critical path, conventions.
2. `00-DESIGN.md` — the two-layer mechanism, the pinned forks (scope fence, PEP leg, identity, agent
   model, input schema, packaging), the fail-closed posture table + kill-switch semantics, and
   considered-&-rejected.
3. `01-DECOMPOSITION.md` — the 6 tickets, each with Goal / Deliverables / Acceptance /
   What-NOT-to-touch. **This is your work list.** **Amendment 2026-07-31:** T5's mechanism was
   re-settled after the T1–T4 run verified that the originally assumed `tools/list` hook does not
   exist in the SDK — the rewritten T5 section and `00-DESIGN.md` §3.2 are **authoritative** over
   anything else in this package, on **both** the mechanism *and the roster failure semantics*.
   **Three** failure classes, never conflated:
   1. an adapter **installation** failure fails startup (never degrade);
   2. the **batch** returning all-`false` — or, from a substituted `OpaClient`, a wrong-length vector
      — is **authoritative ⇒ an empty roster**. The shipped `allowAll` is total and fail-closed: it
      never throws and normalises outage, timeout, non-200, malformed body and length mismatch alike
      into all-`false`, so a PDP outage is *indistinguishable* here from a zero-capability agent, and
      an empty roster is correct in both cases (during that outage every `tools/call` denies too);
   3. only the edges **outside** the batch — an unreadable roster identity, and the
      capability/ceiling lookups preceding it — degrade to the **unfiltered** list + WARN.

   Never fabricate a tool, in any mode. *(This supersedes the earlier blanket "a runtime roster
   failure degrades to the unfiltered list, never an empty roster", which was written against a
   failure signal this seam cannot emit.)* The QA doc's roster/e2e blocks were extended the same day
   (I22–I31, E10–E11).
4. **The pinned decisions** — [[0028-agent-tool-call-authorization|ADR 0028]] (the two-layer decision
   model, enforcement-by-composition with no role propagation, the additive dual-identity subject
   shape, example-first packaging), plus the three it builds on:
   [[0014-supplier-outage-error-distinct|ADR 0014]] (the tri-state supplier contract the capability
   seam copies), [[0023-request-scoped-resolution-memoization|ADR 0023]] (the memo scope this slice
   narrows to a turn), [[0016-action-enrichment-affordance-metadata|ADR 0016]] (the `allowAll` batch primitive the
   roster pre-flight reuses).
5. `10-QA-TEST-CASES.md` — the unit / integration / e2e cases your work must satisfy.
6. **Context you will be checked against** in the review gate (step 5): the shipped
   [[USER-DIRECTORY-PORT]] slice
   (`docs/to-do/implemented/USER-DIRECTORY-PORT/01-DECOMPOSITION.md` + the module it produced,
   `opa-abac-keycloak-directory/`, and `settings.gradle.kts`) for the **new-module + optional-wiring**
   shape this module's Gradle/config work mirrors; [[MULTI-TENANT-ISOLATION]]
   (`docs/to-do/implemented/MULTI-TENANT-ISOLATION/00-DESIGN.md`) for the **governed-scope /
   fail-closed doctrine** and the caller-supplied-role shape that was removed and is not
   reintroduced; [[B2-SUPPLIER-OUTAGE]]
   (`docs/to-do/implemented/B2-SUPPLIER-OUTAGE/00-DESIGN.md` and the `RoleDefinitionSupplier`
   tri-state it produced in `opa-abac-spring-security/`) — the contract
   `AgentCapabilitySupplier` copies verbatim in spirit; [[B3-HTTP-RESILIENCE]]
   (`docs/to-do/implemented/B3-HTTP-RESILIENCE/00-DESIGN.md` + the `OpaClient` guard path) for the
   **edge-wrapping discipline** every new outbound call follows; and the guides
   `docs/guides/ABAC-AUTHORIZATION.md` (how an `AbacContext` is built and asked),
   `docs/guides/PERMISSION-MODEL.md` (the `permission_categories` expansion the ceiling is derived
   through), and `docs/guides/E2E-TESTING.md` (assert the actual cut, not response shape).
7. Root `CLAUDE.md` — the **IP boundary** (clean-room: original names only) and the **commit identity**
   rule (`Void3110 <void31102025@gmail.com>`).
8. `infra/README.md` — the local rig (needed for the e2e ticket), incl. the **in-network token caveat**
   and the **"restart OPA after editing a policy"** gotcha.
9. **Prime Mulch:** the domain table in root `CLAUDE.md` replaced the retired `opa-abac` catch-all —
   prime the row that matches the task. Start with `ml prime opa-abac-methodology autonomous-runs`
   (the planning/run-retrospective pair; read the synthesis record first), then per ticket:
   `ml prime spring-security-integration` for T1/T2/T4/T5 (the authz mechanism, the fail-closed spine,
   supplier/resilience seams), `ml prime rego-policy spring-security-integration` for T3 (the new
   document + its Java-side feed), and `ml prime opa-abac-rig-deploy-ops opa-abac-e2e-suite` for T6.
   Add `ml prime quality-gate-sonar` before judging any local-Sonar finding.

### Per-ticket loop (tickets T1 → T6, IN ORDER; strictly sequential — each ticket consumes the seam the previous one shipped. T1 is independently landable; T1–T4 are the reusable core if the window is short)

For each ticket do ALL of the following, in order, and **STOP at the checkpoint before the next**:

1. **Prime for the files you're about to touch.** `ml prime --files <path>`, and re-read that ticket's
   section in `01-DECOMPOSITION.md`.

2. **Build the deliverables** exactly as `01-DECOMPOSITION.md` lists them — exact classes, packages
   (`dev.dmitriikonovalov.example.mcp.{tool,identity,authz,config}`), mappings, rego rules
   (`infra/opa/policies/agent_tools.rego`, package `agent_tools`). Match the surrounding code's naming
   and idioms. **Clean-room:** no proprietary names anywhere.

3. **Write/extend the tests** for the ticket (the relevant U*/I*/E* cases from `10-QA-TEST-CASES.md`).
   Core/client tests use an **in-process `com.sun.net.httpserver.HttpServer` stub** (no WireMock) —
   here that means one stub standing in for the catalog REST API and one for OPA. Persistence/IT tests
   run against **real Postgres via Testcontainers** (never H2); this module has **no** persistence, so
   none is expected. Policies use `opa test` (the tool-gate is a boolean `allow` — no partial
   evaluation in this slice).

4. **Compile + run unit tests until green.** `./gradlew :<module>:test` (and `./gradlew build` for the
   example/IT tickets). Fix-until-green.

5. **★ ARCHITECTURE REVIEW + REFACTOR — the gate before integration/e2e validation.** Once unit tests
   pass, do NOT advance yet. Run a focused self-review, then refactor and re-test:
   - **Fail-closed check — the slice's load-bearing invariant, stated concretely:** every new edge —
     delegation-chain extraction, capability lookup, the tool-gate OPA call, the roster batch — lands
     on **deny or the smaller result** on any error, timeout, or missing input, and **no kill-switch
     OFF state is wider than its ON state**. Name, for this ticket, each error path and the deny (or
     smaller-result) it lands on. The one deliberate asymmetry: an **absent** actor claim is an
     ordinary human call (principal-only, still bounded by the principal's ceiling), while a
     **malformed** one denies — never downgrade malformed to human.
   - **Security check — name the widening that would matter for this ticket** and state why it cannot
     happen: an agent capability that *adds* an action the principal's ceiling lacks (standing grants);
     a role, capability, chain, or acting-as header reaching the catalog service; the turn-scoped memo
     serving one subject's or actor's capability profile to another request or turn; the roster
     pre-flight being treated as a grant so a listed tool skips the call-time gate; the tool-gate being
     evaluated after the tool body has already touched a data path; a token minted, exchanged, or
     rewritten instead of forwarded verbatim; a demo secret, claim value, or internal detail reaching
     logs or the advisory error body.
   - **Concurrency / idempotency check** — this slice gates **no mutation** (the tools are reads over
     the catalog REST API and the MCP server has no persistence), so say so explicitly rather than
     skipping the check; then prove the one shared-state edge: the turn-scoped capability memo is keyed
     by `(actorId, turnId)` where a turn is **one MCP request**, is never session-lifetime, and cannot
     leak across concurrent turns — a decision made in one turn is never reused to authorize the next
     (`CONCURRENCY-AND-LOCKING.md` Rules 1–2 — code that locks first but acts on a pre-lock decision is
     the defect; the memo equivalent is authorizing turn *n* on turn *n−1*'s snapshot). A replayed
     identical tool call converges on the same decision.
   - **Wiring check** — every seam this ticket adds (an SPI, a property, a guard, an exception + advice
     mapping, a cache accessor, a rego entrypoint, a recovery edge) has a **named consumer** and a test
     through its **non-happy path**; zero call sites = the ticket is not done. `ToolCallClassifier` is
     the single deliberate exception: it ships **contract-only** with its consumer named in Javadoc
     (`ToolCallAuthorizer`, for an undeclared tool — a state `ToolRegistry` validation makes
     unreachable), and that is recorded in ADR 0028's considered-and-rejected. Do not ship a stub
     implementation to satisfy this check.
   - **Boundary / additivity check** — `opa-abac-core` stays Spring-free and, like every other
     `opa-abac-*` module, is **not touched at all**; the whole slice is additive. Name the
     byte-for-byte-unchanged surfaces (every library module, `example-catalog-management-service`,
     `example-user-management-service`, `catalog.rego` / `category.rego` / `product.rego` /
     `role.rego` / `team.rego` / `permissions.rego` and their tests, `permission_categories.json`, the
     existing postman collections, the default rig with no `ENABLE_MCP`) and the one mechanical cost
     (the `settings.gradle.kts` include + the `gradle/libs.versions.toml` pins, which must land in T1's
     commit or the build will not see the module). `git diff --stat` is the proof.
   - **Module-layer separation** — the dual identity lives in `…mcp.identity`, the policy decision in
     `…mcp.authz`, the tool surface and its outbound client in `…mcp.tool`; the tool layer never
     evaluates policy and the authz layer never resolves a target resource. The intersection is
     computed **in Rego** — no Java-side pre-filter that could drift from the policy. No layer reaches
     across.
   - **Pattern-reuse check** — the named shipped patterns this must match, not reinvent: the
     [[USER-DIRECTORY-PORT]] new-module + `@ConfigurationProperties`/optional-wiring shape, the
     [[B2-SUPPLIER-OUTAGE]] tri-state supplier contract (resolved / authoritative-empty /
     outage-throws, distinct in the log and the error code, identical to the caller), the
     [[B3-HTTP-RESILIENCE]] edge-wrapping discipline for the OPA and catalog calls, and the
     [[0016-action-enrichment-affordance-metadata|ADR 0016]] batch `allowAll` primitive for the roster —
     **total and fail-closed**, unlike ADR 0024's `lookupAll`, which throws for the whole batch.
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
   - **T1, T2, T4, T5:** `./gradlew :example-mcp-server:test` — the in-process `HttpServer` stubs for
     the catalog API and OPA, asserting the caller's bearer is forwarded byte-for-byte with no
     role/capability/acting-as header, that a tool-gate deny means the catalog stub is **never called**,
     that every OPA failure mode (5xx, timeout, malformed body, missing `allow` binding) denies, and
     that a **dead PDP** yields the **empty** roster (authoritative — the shipped `allowAll` cannot
     signal failure) and that a **stubbed** `OpaClient` returning a wrong-length vector also lands on
     the **empty** roster, never the unfiltered list and never an index-shifted partial filter.
     Fix-until-green.
   - **T3:** `opa test infra/opa/policies -v` — the new `agent_tools_test.rego` **and** every
     pre-existing policy test still green (an additive document must not shadow a sibling). The
     load-bearing case is a capability **wider** than the ceiling yielding the **ceiling**.
     Fix-until-green.
   - **T6 (e2e ticket):** bring the rig up (`ENABLE_MCP=1 ./deploy.sh up --pods 2` — the flag
     force-enables `ENABLE_OIDC` + `ENABLE_OPA` + `ENABLE_USER_SERVICE`), reseed
     (`scripts/postman/seed-demo-data.sh`), then `cd scripts/postman && ./run-agent-tool-matrix.sh`,
     and confirm the full `./run-tests.sh` is still green. Honor the in-network token caveat and
     restart OPA after a rego edit. The matrix **is** the deterministic scripted MCP client — assert
     the actual cut (which tool names the roster holds, which calls deny, which layer label), including
     the mid-run PDP-kill drill proving zero widening. Fix-until-green.

7. **Update documentation (after each ticket).** Tick the ticket in the `AGENT-TOOL-AUTHZ.md` status
   table; record real values/decisions in `STATUS-0N.md`. `docs/guides/AGENT-TOOL-AUTHORIZATION.md` is
   written across **two** tickets, deliberately: **T5 CREATES it** (the two-layer model, the
   dual-identity claim shape, the capability tri-state, the roster-is-a-hint rule, its own roster
   section, and the explicit-`STREAMABLE` requirement) so the slice has a guide even if the run stops
   after T5 — T1–T4 shipped with none; **T6 EXTENDS it** (the rig story, the e2e cut, the fail-closed
   table, the scope boundary). T6 must not "create" a file T5 already wrote. T6 also ticks the guide
   index in `docs/README.md`, the [[POC-ROADMAP]]
   Phase 9 row, and the ADR index entry for
   [[0028-agent-tool-call-authorization|ADR 0028]]. Root/project `CLAUDE.md` gets the new module + the
   `ENABLE_MCP` rig flag, since both are new build/run steps.

8. **Mulch expertise check (after each ticket).** `ml prime`/skim, then record any genuine reusable
   insight (`ml record <domain> --type <pattern|decision|failure|reference> …`) into the domain the
   insight belongs to per root `CLAUDE.md` — `spring-security-integration`, `rego-policy`,
   `opa-abac-rig-deploy-ops`, `opa-abac-e2e-suite`, `quality-gate-sonar` — and `ml sync`. At the end of
   the run, add the slice's retrospective to `autonomous-runs`. **Before `ml sync`,
   `git restore --staged .`** so the sync commit touches `.mulch/` **only** (the swept-staged trap).
   Skip recording only if nothing is non-obvious.

9. **Commit** — one focused commit on this branch (code + tests + docs + the `STATUS-0N.md` note
   together). Identity `Void3110 <void31102025@gmail.com>`. Conventional subject
   (`feat(mcp): …` for T1/T2/T4/T5, `feat(policy): …` for T3, and
   `test(e2e)/docs(agent-tool-authz): …` for T6). A `Co-Authored-By: Claude` trailer is welcome.

10. **CHECKPOINT — STOP and report.** Summarize what shipped, paste the unit + integration/e2e summary,
    **summarize the review findings + the refactoring you applied** (step 5), list docs updated, and
    note any open question you resolved. Then proceed to the next ticket. **Do not batch tickets
    without a checkpoint.**

### Permissions / autonomy granted (do these WITHOUT asking)

- Create/modify the new `example-mcp-server` module's code and tests, the new `agent_tools.rego` +
  its test document, the realm export + `deploy.sh` / `infra/compose.mcp.yaml`, the docs in this
  folder + the guides, the `scripts/postman/` suite, and Mulch — all on this branch.
- Stand up / tear down / reseed the local rig (`./profile.sh`, `./deploy.sh`,
  `ENABLE_MCP=1 ./deploy.sh up --pods 2`); reset fixtures; rebuild images; restart OPA after a rego
  edit; restart Keycloak to re-import a changed realm; drop/recreate the **local** schema if needed.
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
- **Fail-closed is the load-bearing invariant:** every new edge — delegation-chain extraction,
  capability lookup, the tool-gate OPA call, the roster batch — lands on **deny or the smaller
  result**, and no kill-switch's OFF state is wider than its ON state; no path returns more or wider
  results on an error than on success.
- **Slice-specific invariants the agent must never trade away:** the MCP server sends the catalog
  service the caller's bearer **and nothing else** — never a role, capability, chain, or acting-as
  header, and never a minted, exchanged, or rewritten token; agent capability **narrows only, never
  grants** — a capability wider than the principal's ceiling yields the ceiling, and the intersection
  is computed **in Rego** so it stays auditable; **call-time enforcement is always authoritative** and
  the roster is a hint (a listed tool never skips the gate; an all-`false` **batch** result is
  authoritative ⇒ the **empty** roster, while only the edges *outside* the batch degrade to the
  unfiltered list; and a tool is **never fabricated** in any mode); the tool-gate runs
  **before** the tool body, so a denied call resolves no target and makes no downstream request; a
  **malformed** agent claim denies while an **absent** one is an ordinary human call; and **zero**
  `opa-abac-*` module, existing example service, or pre-existing `.rego` document is created or
  changed.
- **Clean-room IP boundary** — never introduce proprietary names, package names, comments, or source.
  The demo domain stays product catalogs; the agent client secret and personas are obvious **demo**
  values, local-rig-scoped.
- **`opa-abac-core` stays Spring-free** — and this slice does not touch core, or any other library
  module, at all.
- **`ddl-auto: validate` must pass** — there is no schema change anywhere (the MCP server has no
  persistence), so a clean boot of the untouched catalog service is the proof.
- **Do NOT push, open PRs, or touch `main`.** Local + this branch only. The maintainer pushes.

---

## Operator notes (not part of the prompt)

- **The headline tickets** — **T4** (the PEP: `@McpTool` interception, the short-circuited deny, the
  layer-naming advisory error) and **T5/T6** (the filtered roster + the live proof). T4 is what makes
  the design real; T6's **E**-cases are what make it believable — the roster cut named and counted, the
  allow/deny contrast on the *same* token with the catalog service showing no request for the denied
  call, and the mid-run PDP-kill drill showing zero widening. T3's wider-capability-yields-the-ceiling
  `opa test` case is the single assertion the whole agent model rests on.
- **The fail-closed edge to eyeball** — the **roster degradation path** and the **kill-switch OFF
  states**. The degradation-to-unfiltered path now covers only the edges *outside* the batch (an
  unreadable identity, the capability/ceiling lookups); the batch itself lands on the **empty**
  roster. Either way it only stays safe while the call-time gate is untouchable; if an agent ever adds
  a roster-derived cache or an "already checked in the pre-flight" shortcut, the hint has silently
  become a grant. Likewise check that
  `agent-gate.enabled=false` merely removes the *narrowing* (the catalog service still enforces the
  principal ceiling) and that `rosterFilter.enabled=false` is **byte-identical** to the outside-batch
  degradation path — an OFF state that is wider than ON is the failure mode this design exists to
  avoid. Also grep
  the diff for any role/capability/acting-as header before the ★ review passes.
- **Standalone-value subset** — **T1–T4** are the reusable core (a runnable MCP server, dual identity,
  the tool-gate policy + capability seam, the enforcing PEP), unit- and `opa test`-provable without the
  rig. **T1 alone lands independently** — a Spring AI MCP server proxying the catalog REST API with the
  caller's token, every tool declaring action/category/risk-tags, no authorization yet. T5 and T6 can
  follow if the window is short — but **T5 is not "polish"** post-amendment: the reflective roster
  adapter, its startup smoke check, and the explicit `STREAMABLE` protocol flip make it
  the riskiest ticket in the slice (00-DESIGN §3.2). T6 (rig/e2e/docs) is the wrapper.
- **Rig / e2e specifics** — mint tokens **in-network** (the compose network), per the rig caveats;
  **restart OPA after editing a policy** (T3 and T6 both need this — an edited `agent_tools.rego` is
  not live until the container restarts); `ENABLE_MCP=1` adds the new service to the pod set and
  force-enables `ENABLE_OIDC` + `ENABLE_OPA` + `ENABLE_USER_SERVICE`, so the default rig (no
  `ENABLE_MCP`) stays byte-for-byte unchanged; the MCP server reaches the catalog service at its
  **in-network** address, not a published host port; re-import the realm (restart Keycloak) after
  editing `realm-export.json` to pick up the agent client and the claim mapper.
- **CI does not run the rig yet** — the newman matrix is a local/manual gate; a compose-up→newman CI
  job is a tracked follow-up. (CI **will** now build the new `example-mcp-server` module.)
- **Context management** — if the window grows long mid-run, finish the ticket, stop at its checkpoint,
  and resume in a **fresh session** (the ticket status table + STATUS notes are the handoff); sub-agents
  are for scouting/validation only, never the implementation.
- **Workflow-as-artifact:** keep this prompt verbatim; the `STATUS-0N.md` notes record each ticket's
  outcome. Move the folder to `docs/to-do/implemented/` on ship (T6).

## Related

- [[AGENT-TOOL-AUTHZ]] · [[00-DESIGN]] · [[01-DECOMPOSITION]] · [[10-QA-TEST-CASES]]
- [[0028-agent-tool-call-authorization|ADR 0028]] · [[0014-supplier-outage-error-distinct|ADR 0014]] ·
  [[0023-request-scoped-resolution-memoization|ADR 0023]] · [[0024-batch-role-resolution|ADR 0024]]
- [[AUTONOMOUS-IMPLEMENTATION-FLOW]] · [[ABAC-AUTHORIZATION]] · [[PERMISSION-MODEL]] ·
  [[E2E-TESTING]] · [[CONCURRENCY-AND-LOCKING]] · [[HTTP-RESILIENCE]]
- [[USER-DIRECTORY-PORT]] · [[MULTI-TENANT-ISOLATION]] · [[B2-SUPPLIER-OUTAGE]] ·
  [[B3-HTTP-RESILIENCE]] · [[POC-ROADMAP]]
