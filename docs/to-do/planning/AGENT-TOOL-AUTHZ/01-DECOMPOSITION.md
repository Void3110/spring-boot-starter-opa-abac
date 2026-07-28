---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/opa
  - area/rego
  - area/spring
---

# AGENT-TOOL-AUTHZ — decomposition

> The ordered work list for [[AGENT-TOOL-AUTHZ|Phase 9]], decomposed from [[00-DESIGN]] +
> [[0028-agent-tool-call-authorization|ADR 0028]]. **6 tickets, one focused commit each.** Each
> ticket's *Acceptance* references a case in [[10-QA-TEST-CASES]]. Everything lands in the **new**
> `example-mcp-server` module under `dev.dmitriikonovalov.example.mcp.*`, plus one new rego document
> and the rig/e2e wiring. **No `opa-abac-*` module is created or changed; no existing service and no
> existing `.rego` document is touched.**

## Critical path

```
T1 ──► T2 ──► T3 ──► T4 ──► T5 ──► T6
(skeleton) (identity) (policy+cap) (PEP) (roster) (rig+e2e+docs)
```

**Strictly sequential** — each ticket consumes the seam the previous one shipped. **T1 is
independently landable** (a runnable MCP server proxying the existing catalog REST API with the
caller's bearer, every tool declaring its action/category/risk-tags, no authorization yet) and has
standalone demo value. **T1–T4 are the reusable core** — identity normalization, the tool-gate
policy + capability seam, and the enforcing PEP; if the window is short, T5 (roster polish) and T6
(rig/e2e/docs) can follow. The ★ review + checkpoint after each ticket is mandatory.

**Invariants every ticket carries** (repeated in each *What NOT to touch*): fail-closed on every new
edge; no library module and no existing service/rego touched; the MCP server never asserts a role,
capability, or acting-as header to the catalog service; call-time enforcement is always
authoritative; clean-room (no proprietary names, the demo domain stays product catalogs).

---

## T1 — Walking skeleton: `example-mcp-server` + declared `@McpTool` catalog proxies (no authz)

**Goal.** Ship a runnable Spring AI MCP server whose tools proxy the **existing** catalog REST API
with the caller's own bearer, each tool statically declaring its action / category / risk-tags — the
surface the next five tickets gate. No authorization in this ticket.

**Deliverables.**
- **New Gradle module** `example-mcp-server` — added to the *example* `include(…)` block in
  `settings.gradle.kts`; `build.gradle.kts` on Java 25 / Spring Boot 4.0; Spring AI **2.0.0** +
  MCP Java SDK **2.0.0** pinned in `gradle/libs.versions.toml` (MCP spec revision **2025-11-25**).
  Not published — the example allow-list in the root publish config is unchanged.
- `McpServerApplication` (`dev.dmitriikonovalov.example.mcp`) — the boot app.
- `…mcp.tool.CatalogTools` — **4** `@McpTool` methods: `listCatalogs`, `getCatalog`,
  `listCategories`, `getProduct`. Each delegates to `CatalogApiClient`; **no** persistence, **no**
  shared DB, **no** duplicated enforcement.
- `…mcp.tool.CatalogApiClient` — `RestClient` over `example.mcp.catalog.base-url`, forwarding the
  **caller's** bearer verbatim (never minting, exchanging, or rewriting a token) with connect/read
  timeouts; `…mcp.tool.CatalogApiErrorTranslator` maps a downstream 403 to the
  `target-gate`-labelled advisory error (the label vocabulary lands here; T4 adds the `tool-gate`
  half).
- `…mcp.tool.CallerBearerSupplier` — reads the validated bearer off the current request context.
- `…mcp.tool.ToolDescriptor` record `(String name, String action, String category, Set<String>
  riskTags)` + `…mcp.tool.ToolRegistry` — the static declarations, validated **at registration
  time**: a tool missing action, category, or risk-tags **fails context startup and is not exposed**
  (the fail-closed "unclassifiable tool" edge, [[00-DESIGN]] §4).
- `…mcp.tool.ToolCallClassifier` — **SPI, contract only, no implementation**. Javadoc pins
  *most-restrictive-on-ambiguity* and names the consumer: `ToolCallAuthorizer` (T4) would consult it
  only for an **undeclared** tool — a state `ToolRegistry` validation makes unreachable for the demo's
  tools, which is exactly why no implementation ships (ADR 0028, considered-and-rejected).
- `…mcp.config.McpServerProperties` — `@ConfigurationProperties("example.mcp")`: catalog base URL +
  timeouts.
- **STATUS-01 note:** the 30-second confirm that the spec site has flipped **Current** to the
  2026-07-28 revision — a docs-only observation; the pins in this ticket do not move.

**Acceptance.** [[10-QA-TEST-CASES]] **U1** (all 4 registered tools expose a non-blank action, a
category, and at least one risk-tag; a `ToolRegistry` lookup of an unregistered name returns empty,
never a permissive default) + **U2** (registration-time validation: a descriptor with a blank category
fails startup — the tool is **not** exposed) + **I1** (in-process
`com.sun.net.httpserver.HttpServer` stub standing in for the catalog REST API: the tool call carries
the caller's bearer **byte-for-byte** and no extra role/capability header; a stub 403 → the
`target-gate` advisory error; a stub 5xx / connection-refused → a structured tool error, never a raw
stack trace). `./gradlew :example-mcp-server:test`.

**What NOT to touch.** No `opa-abac-*` module (this is an example service **on** the published
starter). No change to `example-catalog-management-service`, `example-user-management-service`, or any
`.rego`. **No authorization** in this ticket — do not sneak a gate in; T4 owns the PEP and a
half-built gate is worse than none. Never mint, exchange, or rewrite the caller's token, and never add
a role/capability/acting-as header (that caller-supplied-role shape is what
[[MULTI-TENANT-ISOLATION|slice B4]] removed). No persistence in this module. **Build-breakers:** the
`settings.gradle.kts` include **and** the `gradle/libs.versions.toml` pins must land in **this**
commit or `./gradlew build` will not see the module.

---

## T2 — Dual identity: `DelegationChainExtractor` + `DelegationChain` (RFC 8693 `act` semantics)

**Goal.** Normalize the validated token into principal + actor + an ordered delegation chain, read
from a **plain custom claim** minted by stock Keycloak — fail-closed on every malformed shape, with
"no actor claim" meaning an ordinary human call rather than an agent grant.

**Deliverables.**
- `…mcp.identity.DelegationChain` record — `(String principal, String actor, List<String> chain)`
  with `isAgentCall()` (`actor != null`); `chain` is **immutable and ordered** (nearest actor first).
  `principal` is the token `sub`, resolved exactly as the rest of the repo resolves it.
- `…mcp.identity.DelegationChainExtractor` interface — `DelegationChain extract(Jwt token)`; the
  Javadoc pins the fail-closed contract: **throw, never widen**.
- `…mcp.identity.ClaimDelegationChainExtractor` — the implementation. Reads the configurable claim
  (`example.mcp.identity.actor-claim`, default `act_chain`) carrying **RFC 8693 `act` semantics**
  (nested `act` objects, or the flattened array the demo mapper mints) and normalizes both shapes to
  the ordered `chain`. The seam is deliberately shaped so a **real** `act` claim can be read later
  without touching callers — no token-exchange, no Keycloak nightly build.
- `…mcp.identity.DelegationChainException` — thrown on: a claim of the wrong type, a blank/absent
  `sub`, a chain deeper than `example.mcp.identity.max-chain-depth` (default **4**), a claim longer
  than `max-claim-length`, a **cyclic** chain, or an actor id failing the id charset check. Callers
  (T4, T5) map it to **deny** — never to a principal-only fallback.
- `…mcp.identity.IdentityProperties` — `@ConfigurationProperties("example.mcp.identity")`:
  `actor-claim`, `max-chain-depth`, `max-claim-length`.
- **Consumers named:** `ToolCallAuthorizer` (T4) and `ToolRosterFilter` (T5) both resolve the caller
  through this seam; in this ticket the only consumer is the unit test (the bean is registered so T4
  can inject it unchanged).

**Acceptance.** [[10-QA-TEST-CASES]] **U3** (no actor claim → `principal == sub`, `actor == null`,
`chain` empty, `isAgentCall() == false` — the honest human reading, **not** a deny and **not** an
agent grant) + **U4**, the non-happy path (each of: a claim of the wrong JSON type, a blank `sub`,
chain depth 5 against max 4, an oversized claim, a cycle `agent-a → agent-b → agent-a`, and an actor
id with illegal characters → `DelegationChainException`; **none** of them falls back to
principal-only) + **I2** (a real Spring `Jwt` carrying the demo realm's minted claim → principal +
actor + the chain in order; the extractor reads **only** the identity claim — never roles, scopes, or
audience). `./gradlew :example-mcp-server:test`.

**What NOT to touch.** A **malformed** agent claim is never downgraded to "an ordinary human call" —
broken agent identity denies (the two rows in [[00-DESIGN]] §4 are deliberately different). The
extractor **describes**, it never grants: no capability, no role, no policy decision here. No token
minting or exchange — the scope fence sits above the validated token. No `opa-abac-*` module, no
existing service, no rego. Clean-room: the claim name and personas stay generic.

---

## T3 — Tool-gate policy `agent_tools.rego` + `AgentCapabilitySupplier` (ceiling ∩ capability, in Rego)

**Goal.** Land the new tool-gate policy document that computes **principal ceiling ∩ agent
capability** in Rego, and the tri-state capability seam that feeds it — with `opa test` covering the
allow and **every** deny edge.

**Deliverables.**
- `infra/opa/policies/agent_tools.rego` — a **new** document, package `agent_tools`, explicit
  **`default allow := false`**. Rules:
  - `principal_actions` — the principal's effective actions for the tool's category, derived through
    the **shipped** `permission_categories` expansion ([[PERMISSION-MODEL]]) from
    `input.subject.roles` / `input.role_definition`. This is the **ceiling**.
  - `agent_actions` — from `input.subject.attributes.agent_capability` (allowed categories, allowed
    tools, allowed actions, max risk tag).
  - `effective_actions := principal_actions & agent_actions` — the **intersection, computed in the
    policy** so it is auditable and `opa test`-able; never in Java.
  - `allow if` the tool's declared `input.action` is in `effective_actions`, the tool's category is
    permitted, and **every** declared risk-tag sits at or below the capability's max risk tag.
  - A **human** call (no `input.subject.attributes.actor`) evaluates principal-only — the widest the
    tool-gate ever goes, still bounded by the principal's own ceiling.
  - Absent `agent_capability` on an **agent** call, absent `role_definition`, an unknown category, or
    an unknown risk tag → no `allow` binding → **deny**.
- `infra/opa/policies/agent_tools_test.rego` — the `opa test` cases: the allow; a capability
  **narrower** than the ceiling → the narrower result; a capability **wider** than the ceiling →
  **still the ceiling** (no widening — the load-bearing case); a risk-tag above max → deny; a category
  outside the allow-list → deny; a tool outside the allow-list → deny; missing `agent_capability` →
  deny; missing `role_definition` → deny; an empty capability profile → deny every tool; the no-actor
  human call → principal-only allow.
- `…mcp.identity.AgentCapabilityProfile` record — `(Set<String> allowedCategories, Set<String>
  allowedTools, Set<String> allowedActions, String maxRiskTag)` plus a static `empty()`.
- `…mcp.identity.AgentCapabilitySupplier` interface — the **tri-state** doctrine of
  [[0014-supplier-outage-error-distinct|ADR 0014]]: a resolved profile, an **authoritative-empty**
  profile (the actor is known and has zero capability — a real answer), or a thrown
  `AgentCapabilityUnavailableException` (outage/timeout). Both non-resolved states **deny**; they
  differ in the log and the error code, never to the caller.
- `…mcp.identity.ConfigAgentCapabilitySupplier` — the demo source:
  `@ConfigurationProperties("example.mcp.agents")` mapping actor id → profile. An **unknown** actor is
  authoritative-empty (deny all); a source failure **throws**.
- `…mcp.identity.TurnScopedCapabilityCache` — the memo, keyed by `(actorId, turnId)` where a turn is
  **one MCP request**; explicitly **not** session-lifetime
  ([[0023-request-scoped-resolution-memoization|ADR 0023]], narrowed), so a mid-session revocation is
  picked up on the next turn.
- **Consumers named:** `ToolCallAuthorizer` (T4) resolves capability through the supplier + cache and
  puts the profile on `input.subject.attributes.agent_capability`; `ToolRosterFilter` (T5) reuses the
  same turn's memo across the whole batch.

**Acceptance.** [[10-QA-TEST-CASES]] **U5**, the non-happy path (a known actor → a profile; an
**unknown** actor → authoritative-empty, asserted **distinct** from outage; a throwing config source →
`AgentCapabilityUnavailableException`, never a silent `empty()`) + **U6** (the turn-scoped memo: two
lookups inside one turn → **one** source call; a second turn → a **fresh** call, so a profile revoked
between turns takes effect on the second) + **P1–P10** (the `agent_tools_test.rego` cases above).
`opa test infra/opa/policies -v` and `./gradlew :example-mcp-server:test`.

**What NOT to touch.** **No existing rego document** — `catalog.rego`, `category.rego`,
`product.rego`, `role.rego`, `team.rego`, `permissions.rego` and their tests are read-only here; this
is an **additive** document. Do not edit `permission_categories.json` (the expansion is consumed, not
changed). The intersection lives **in Rego** — no Java-side pre-filter that could drift from it. No
standing agent grants: a capability may never add an action the ceiling lacks (the wider-capability
test is the guard). An unknown actor is a **real answer** (deny), not an outage. No PEP wiring yet —
T4 owns it. No `opa-abac-*` module, no existing service.

---

## T4 — The PEP: `@McpTool` interception, layer-naming advisory deny, kill-switch

**Goal.** Enforce the tool-gate **before** any tool body runs, surface a structured advisory denial
that names the denying layer, and land a kill-switch whose OFF state is provably not wider than ON.

**Deliverables.**
- `…mcp.authz.ToolCallAuthorizer` — the PEP core. Builds the `AbacContext` exactly as [[00-DESIGN]]
  §2.2 specifies: `subject.id` = principal; `subject.roles` / `role_definition` as today;
  `subject.attributes.actor` + `.chain` from T2's `DelegationChain`;
  `subject.attributes.agent_capability` from T3's supplier + turn memo; `resource.type = "tool"` with
  the descriptor's category + risk-tags in `resource.attributes`; `action` = the declared verb. Calls
  `OpaClient.allow(…)` and returns a `ToolAuthorizationDecision`.
- `…mcp.authz.ToolAuthorizationDecision` record — `(boolean allowed, String layer, String code,
  String message)`; `layer` is `tool-gate` here and `target-gate` for the translated downstream 403.
- `…mcp.authz.McpToolAuthorizationAspect` — the server-side `@McpTool` interception, registered by
  `…mcp.authz.ToolAuthorizationConfiguration` and ordered so it runs **before** the tool body. A deny
  **short-circuits**: the body never runs, no target is resolved, no downstream call is made.
- `…mcp.authz.ToolAuthorizationException` + the advisory mapping — a denial reaches the client as a
  **structured tool error naming the layer**, carrying a stable code, so the model can pick another
  tool or ask for escalation; never silent, never a raw stack trace.
- `…mcp.authz.ToolPolicyPathResolver` — a `PolicyPathResolver` binding resource type `tool` to the
  `agent_tools/allow` document.
- `…mcp.authz.ToolAuthorizationProperties` — `@ConfigurationProperties("example.mcp.authz")`:
  `agent-gate.enabled` (default **true**), `policy-path` (default `agent_tools/allow`), `timeout`.
  The Javadoc states the OFF semantics: the tool-gate is skipped, the call is evaluated exactly as a
  human principal's would be, and the catalog service still enforces the principal ceiling — so OFF
  removes the **narrowing** and can never grant beyond the principal.
- **Consumers named:** every `@McpTool` in `CatalogTools` (T1) is intercepted; T5's roster filter
  reuses this ticket's context builder for the batch.

**Acceptance.** [[10-QA-TEST-CASES]] **U7** (context build: every input path of §2.2 present and
correctly populated; the declared risk-tags land in `resource.attributes`; nothing agent-related leaks
into `resource`) + **I3**, the non-happy paths (in-process `HttpServer` OPA stub + catalog stub: allow
→ the body runs and the catalog stub sees the caller's bearer; **deny → the catalog stub is never
called** and the error names `tool-gate`; OPA 5xx / timeout / malformed body / a missing `allow`
binding → **deny**; `AgentCapabilityUnavailableException` → deny with a distinct code but a
caller-identical advisory; `DelegationChainException` → deny; `agent-gate.enabled=false` → the
tool-gate is skipped **and** the catalog stub's 403 still surfaces as `target-gate`).
`./gradlew :example-mcp-server:test`.

**What NOT to touch.** **Never** send a role, capability, chain, or acting-as header to the catalog
service — the intersection is enforced **across** layers, not propagated ([[00-DESIGN]] §2). Never
resolve the target resource in the MCP server (the service that owns the data owns its gate). There is
**no** kill-switch that disables call-time enforcement, and no error path that falls through to allow.
Do not touch `example-catalog-management-service`, `example-user-management-service`, the existing
rego, or any `opa-abac-*` module — the shipped `OpaClient` fail-closed contract ([[HTTP-RESILIENCE]])
is consumed as-is. No roster filtering yet (T5).

---

## T5 — Roster filtering: `tools/list` via batch `allowAll`, omit-never-fabricate

**Goal.** Advertise only the tools the caller may actually call — in one batch OPA round-trip — while
keeping the list a **hint** whose failure degrades to *more* visibility, never less, and never to a
grant.

**Deliverables.**
- `…mcp.authz.ToolRosterFilter` — the `tools/list` pre-flight: one `AbacContext` per registered tool
  (built by T4's context builder, over one shared turn-scoped capability memo), a **single**
  `OpaClient.allowAll(List<AbacContext>)` round-trip, N booleans back — the batch primitive of
  [[0024-batch-role-resolution|ADR 0024]] — filtered by index into the advertised list.
- `…mcp.authz.ToolListFilterConfiguration` — registers the filter with the MCP server's `tools/list`
  handler; `ToolAuthorizationProperties.rosterFilter.enabled` (default **true**) is the kill-switch,
  whose OFF state is byte-identical to the degradation path below.
- **Omit-never-fabricate degradation** — on any failure (`allowAll` throws, times out, or returns a
  list whose size does not match the request) the server logs WARN and returns the **unfiltered**
  list. It never returns an empty roster (that reads as broken and the agent gives up) and never
  invents a tool name. Degrading a hint is safe precisely because the hint carries no authority.
- **Consumers named:** the MCP `tools/list` handler consumes the filter; T6's scripted client asserts
  the resulting cut.

**Acceptance.** [[10-QA-TEST-CASES]] **U8**, the non-happy path (an `allowAll` response shorter or
longer than the request → the **unfiltered** list, never an index-shifted partial filter; an empty
registry → an empty list with **no** OPA call) + **I4** (an OPA stub returning a mixed batch → the
roster holds **exactly** the allowed tool names; OPA down → the **full** roster, never empty; then a
tool the roster listed a moment ago, whose capability has since been revoked, is **still denied at
call time** with `tool-gate` — the list is a hint, the call-time gate is authoritative).
`./gradlew :example-mcp-server:test`.

**What NOT to touch.** A listed tool **never** skips the call-time gate — no roster-derived cache, no
"already checked in the pre-flight" shortcut. Never present an empty roster on failure and never
fabricate a tool. No `notifications/tools/list_changed` (out of scope; the call-time gate is what
catches revocation). The roster kill-switch OFF must be **exactly** the degradation path, so OFF is
never wider than ON. No library module, no existing service, no existing rego; nothing asserted
downstream.

---

## T6 — Rig + scripted-client e2e + guide + folder move

**Goal.** Prove the whole cut on the live rig with a deterministic scripted MCP client, wire the new
service into `deploy.sh`, document it, and move the folder to `implemented/`.

**Deliverables.**
- **Rig:** `infra/compose.mcp.yaml` (the new service on the shared `opa-abac-example` network, image
  `opa-abac-mcp:local`, reaching the catalog service in-network) + `deploy.sh` wiring — an
  `ENABLE_MCP=1` flag that force-enables `ENABLE_OIDC` + `ENABLE_OPA` + `ENABLE_USER_SERVICE` (the
  `ENABLE_SPA` / `ENABLE_DIRECTORY` precedent), the image build step, the health wait, and the status
  line. The default rig (no `ENABLE_MCP`) stays byte-for-byte unchanged.
- **Realm:** `infra/keycloak/realm-export.json` gains an agent client plus a **protocol mapper**
  minting the plain `act_chain` custom claim, and the personas the matrix needs — a principal with a
  broad catalog role, an agent actor whose capability profile is strictly narrower, and one actor
  configured with an **empty** profile. Demo secrets only, obviously demo-scoped.
- **Capability config:** the `example.mcp.agents` demo profiles passed to the service pod, including
  both the narrower-than-ceiling profile and a deliberately **wider-than-ceiling** one, so the e2e can
  prove no widening on the live rig and not only in `opa test`.
- **e2e:** `scripts/postman/agent-tool-matrix.postman_collection.json` +
  `scripts/postman/run-agent-tool-matrix.sh`, registered in `scripts/postman/run-tests.sh`. The
  collection **is** the deterministic scripted MCP client: ordered JSON-RPC `initialize` →
  `tools/list` → `tools/call` requests over the streamable-HTTP transport, asserting **the actual
  cut** (which tool names, which denials, which layer label), never just response shape
  ([[E2E-TESTING]]).
- **Docs:** a new `docs/guides/AGENT-TOOL-AUTHORIZATION.md` (the two-layer model, the dual-identity
  claim shape, the capability tri-state, the roster-is-a-hint rule, the fail-closed table, the scope
  boundary); tick the guide index in `docs/README.md` and the [[POC-ROADMAP]] Phase 9 row, and confirm
  [[0028-agent-tool-call-authorization|ADR 0028]] is linked from the ADR index.
- **Folder move:** `git mv docs/to-do/planning/AGENT-TOOL-AUTHZ docs/to-do/implemented/`, flip the
  index frontmatter `status/planned → status/done`, add a **Shipped** banner, and record the run
  retrospective in the `autonomous-runs` expertise domain.

**Acceptance.** [[10-QA-TEST-CASES]] **E1** (roster cut: the agent persona's `tools/list` returns
**exactly** the allowed subset of the 4 registered tools — named and counted) + **E2** (allow/deny
contrast on the same token: an in-capability tool returns catalog data, an out-of-capability tool is
denied with the `tool-gate` label and the catalog service shows **no** corresponding request) + **E3**
(the same principal's token **without** the actor claim gets the full principal-only cut — proving
no-actor is an honest human call and that the agent path only ever narrowed it) + **E4** (a
listed-but-since-revoked tool is still denied at call time, on the next turn, with no restart) +
**E5** (the mid-run PDP-kill drill: stop the OPA container → every tool call denies and `tools/list`
degrades to the **unfiltered** roster; **zero widening** — the previously denied tool is still denied;
restart OPA and the matrix resumes green). `ENABLE_MCP=1 ./deploy.sh up --pods 2` then
`scripts/postman/run-agent-tool-matrix.sh`; plus `./gradlew build` green.

**What NOT to touch.** No existing collection's assertions change — the new matrix is additive and
`run-tests.sh` only gains a registration. The existing rego is unchanged, so the other matrices need
no policy reload. Mint tokens **in-network**, per the rig caveats. Clean-room: no employer, internal
system, or proprietary name anywhere in the compose file, the realm export, the collection, or the
guide; the demo domain stays product catalogs. **Do NOT push, open a PR, or touch `main`.** The
library-module extraction (`opa-abac-agent`) is the slice's **exit criterion**, deliberately **not**
part of this ticket.

---

## Cross-cutting acceptance

- `./gradlew build` green — all library modules + all three example apps **including the new
  `example-mcp-server`** + OpenAPI codegen + Testcontainers ITs (Java 25 / Spring Boot 4.0 / Gradle 9.x).
- `opa test infra/opa/policies -v` green — the new `agent_tools_test.rego` **and** every pre-existing
  policy test still passing (an additive document must not shadow or break a sibling).
- The agent matrix green through the rig (`ENABLE_MCP=1 ./deploy.sh up --pods 2` →
  `scripts/postman/run-agent-tool-matrix.sh`), and the full `scripts/postman/run-tests.sh` still green.
- **Fail-closed holds on every new edge** — chain extraction, capability lookup, the tool-gate OPA
  call, the roster batch: each lands on deny or the **smaller** result (U4, U5, I3, I4, E5). No new
  code path can widen a decision.
- **The intersection is provable and computed in Rego** — a capability wider than the principal's
  ceiling yields the ceiling, not the capability (the `agent_tools_test.rego` case + E2).
- **Nothing is asserted downstream** — the catalog service receives the caller's bearer and nothing
  else; the diff is grepped for any role/capability/acting-as header before the ★ review passes.
- **Kill-switches are never wider OFF than ON** — `agent-gate` off still leaves the catalog service
  enforcing the principal ceiling; `roster-filter` off is exactly the degradation path; there is no
  switch for call-time enforcement.
- **Zero library change** — `git diff --stat` touches no `opa-abac-*` module, no
  `example-catalog-management-service`, no `example-user-management-service`, and no pre-existing
  `.rego`. No schema change anywhere (the MCP server has no persistence).

## Related

- [[AGENT-TOOL-AUTHZ]] · [[00-DESIGN]] · [[10-QA-TEST-CASES]]
- [[0028-agent-tool-call-authorization|ADR 0028]] · [[0014-supplier-outage-error-distinct|ADR 0014]] ·
  [[0023-request-scoped-resolution-memoization|ADR 0023]] · [[0024-batch-role-resolution|ADR 0024]]
- [[ABAC-AUTHORIZATION]] · [[PERMISSION-MODEL]] · [[HTTP-RESILIENCE]] · [[E2E-TESTING]] ·
  [[MULTI-TENANT-ISOLATION]] · [[POC-ROADMAP]]
