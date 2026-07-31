---
tags:
  - status/planned
  - type/architecture
  - area/abac
  - area/opa
  - area/rego
  - area/spring
---

# AGENT-TOOL-AUTHZ — design

> The settled design for [[AGENT-TOOL-AUTHZ|Phase 9]] — agent tool-call authorization, the
> smallest honest demo. Grilled 2026-07-27, forked to a decomposition 2026-07-28; **§3.2 rewritten
> 2026-07-31** around the verified `tools/list` mechanism, after the T1–T4 run established that the
> originally assumed SDK hook does not exist. The structural rationale is pinned by
> **[[0028-agent-tool-call-authorization|ADR 0028]]** (referenced here, not repeated). Nothing in
> the library changes: this slice is a **new example service** that puts the shipped starter behind
> an MCP tool surface.

## 1. The gap today

The starter secures **human REST calls**: a request arrives at the gateway with a validated bearer
token, the app re-derives the `Subject`, resolves a `RoleDefinition`, and asks OPA the
resource/action question ([[0006-three-layer-enforcement-model|ADR 0006]]). Every enforced path in the repo has that
shape. An **AI agent invoking a tool** does not: the tool call is a new front door, and today it is
an unauthorized one.

The MCP specification does not close the gap — it deliberately stops at the transport edge:

- It specifies how a server **validates a bearer token** (OAuth 2.1 resource server, resource
  indicators, audience binding) and nothing above it. There is **no per-tool authorization**
  concept in the spec: a token that passes validation is a token that may call **every** tool the
  server exposes.
- There is **no mechanism for filtering the advertised tool list**. `tools/list` is a static
  catalogue of the server's capabilities; the spec has no notion of "these tools, for this caller."
  (The 2026-07-28 revision, via SEP-2567, *sanctions* the set varying by the authorization presented
  on the request — and forbids per-connection variation — but still ships no mechanism.) An agent is
  told about tools it must not use, and the model learns to want them.
- The identity that reaches the tool body is a **single** identity — the token's `sub`. There is no
  spec-level distinction between *the human on whose behalf the call is made* and *the agent making
  it*, so a policy cannot bound the agent separately from the person.

The result, without this slice: a token minted for a low-privilege human, handed to an agent,
lets that agent call any tool the server has — including tools the human would never be offered in
the UI — and the only backstop is whatever the downstream service happens to enforce. That backstop
is real here (the catalog service is fully gated) but it is the **principal's** ceiling, not the
agent's. An agent operating with the full authority of its principal is precisely the thing this
slice refuses.

## 2. The mechanism

**Two layers, no propagation.** A new example service hosts the tools; agent narrowing lives
*only* in that service's tool-gate; the catalog service independently enforces the principal
ceiling with its policies **literally untouched**. The intersection is enforced **across** the
layers rather than passed between them.

```
  agent  ──MCP──▶  example-mcp-server                    ──REST(caller's bearer)──▶  example-catalog-management-service
                   ┌──────────────────────────────┐                                  ┌────────────────────────────┐
                   │ LAYER 1 — the TOOL-GATE      │                                  │ LAYER 2 — the TARGET-GATE  │
                   │  may this (principal, actor) │                                  │  may this PRINCIPAL do this│
                   │  invoke THIS TOOL?           │                                  │  on THIS RESOURCE?         │
                   │  → agent-tools.rego (NEW)    │                                  │  → catalog/category/product│
                   │  → ceiling ∩ capability      │                                  │    .rego (UNCHANGED)       │
                   └──────────────────────────────┘                                  └────────────────────────────┘
                              principal ceiling ∩ agent capability          ∩          principal ceiling on the row
```

The catalog service cannot be exceeded by the agent (it enforces the principal ceiling on every
resource, exactly as it does for a human), and the agent is *further* narrowed by the tool-gate.
The net effect is the intersection, provably — with **zero** trust placed in anything the MCP
server asserts downstream. The MCP server never sends a role, a capability, or an "acting-as"
header to the catalog service: asserting a caller-supplied role is exactly the fail-open shape
[[MULTI-TENANT-ISOLATION|slice B4]] removed, and it is not reintroduced here.

### 2.1 Named integration points

Everything is new and lives in the new module — no library module is created or changed.

```
example-mcp-server  (NEW Gradle module, dev.dmitriikonovalov.example.mcp.*)

  …mcp.tool
    CatalogTools                  @McpTool methods (3–4) proxying the EXISTING catalog REST API
                                  with the caller's bearer; each DECLARES action / category /
                                  risk-tags as static attributes on its registration
    ToolCallClassifier            SPI, CONTRACT-ONLY (no impl this slice) — would derive
                                  action/category/risk for an undeclared tool; its contract states
                                  most-restrictive-on-ambiguity

  …mcp.identity
    DelegationChainExtractor      seam: validated token -> DelegationChain
    DelegationChain               value type: principal (token `sub`) + actor (agent id, optional)
                                  + an ORDERED chain; RFC 8693 `act` semantics, read from a PLAIN
                                  CUSTOM CLAIM minted by stock Keycloak (a realm protocol mapper)
                                  — no nightly build, no experimental token-exchange
    AgentCapabilitySupplier       seam: actor id -> capability profile (allowed tool categories,
                                  max risk tag, tool allow-list); tri-state per ADR 0014
                                  (resolved / authoritative-empty / outage-throws); TURN-SCOPED memo

  …mcp.authz
    ToolCallAuthorizer            the PEP: builds the AbacContext, calls OPA `agent_tools`, decides
    ToolCallGate                  wraps every tool specification's callHandler (BeanPostProcessor) —
                                  runs BEFORE the tool body; chosen over the originally planned
                                  aspect because a proxy-based aspect is not reliably in the
                                  invocation path (T4 deviation, STATUS-04 Decisions §1)
    ToolRosterFilter              tools/list: the DURABLE filter core (batch `allowAll`) — §3.2
    RosterFilterInstaller         the DISPOSABLE startup adapter installing the core into the SDK's
                                  request-handler map — resolves the transport provider by INTERFACE
                                  type so a stub can be substituted in tests; smoke-checked, fails
                                  the context on drift; deleted when java-sdk #578 ships (SDK 2.1)
    TypeLevelRoleDefinitionSupplier  the principal's TYPE-LEVEL ceiling, resolved from the shipped
                                  user-service (`/internal/governed-targets` then the batch
                                  `/internal/effective-roles`, unioned) — added in T4 because the
                                  tool-gate deliberately resolves no target; STATUS-04 Decisions §2
    ToolAuthorizationProperties   kill-switches (per layer) + timeouts

infra/opa/policies
    agent_tools.rego              NEW policy document, `default allow := false`; computes
                                  principal-ceiling ∩ agent-capability IN REGO (auditable)
    agent_tools_test.rego         `opa test` — allow, and every deny edge

infra/keycloak/realm-export.json  an agent client + the custom-claim protocol mapper + personas
deploy.sh / infra/compose.*       the new service on the rig
scripts/postman                   the deterministic scripted MCP-client e2e
docs/guides/                      a new guide + the index tick
```

### 2.2 The OPA input — additive, not a schema change

The tool-gate reuses the shipped `AbacContext` shape as-is. `AbacContext.Subject` already carries
`Map<String,Object> attributes`, so the dual identity rides there:

| input path | meaning |
|---|---|
| `input.subject.id` | the **principal** — the token `sub`, resolved exactly as today |
| `input.subject.roles` / `input.role_definition` | the principal's **ceiling**, resolved exactly as today |
| `input.subject.attributes.actor` | the **agent** (absent for an ordinary human call) |
| `input.subject.attributes.chain` | the ordered delegation chain |
| `input.subject.attributes.agent_capability` | the capability profile (categories, max risk, allow-list) |
| `input.resource.type` = `"tool"`, `input.resource.attributes` | the tool's declared **category, `target_type`, and risk-tags**. `target_type` is the resource type the tool reads (`catalog` / `category` / `product`) and is **mandatory** — the policy derives `principal_actions` through `permissions.effective_actions(role_definition, target_type)`, so an absent value leaves that undefined and the call **denies** (added in T3; STATUS-03) |
| `input.action` | the tool's declared action verb |

No core type changes; no per-type policy changes. The existing documents never see these keys
because the existing documents are never asked this question.

## 3. How it works

### 3.1 One tool call

1. The gateway validates the token and forwards the bearer (unchanged; MCP transport/OAuth authz is
   out of scope — see §5).
2. `DelegationChainExtractor` normalizes the token into a `DelegationChain`. **No actor claim ⇒ an
   ordinary human call** — the gate then evaluates principal-only. That is honest rather than a
   bypass, because agent narrowing only ever *restricts*: a principal-only evaluation is the
   widest the tool-gate ever goes, and it is still bounded by the principal's own ceiling.
3. `AgentCapabilitySupplier` resolves the actor's capability profile — **turn-scoped** memo, never
   session-lifetime, so a mid-session revocation is picked up on the next turn.
4. `ToolCallAuthorizer` builds the `AbacContext` (§2.2) and asks OPA `agent_tools`. Rego computes
   the **intersection**: the principal's effective actions for the tool's category (the ceiling,
   derived through the shipped `permission_categories` expansion) **∩** the agent's capability
   profile. The intersection is computed in the policy, not in Java, so it is auditable and
   testable with `opa test`.
5. **Deny ⇒ short-circuit.** The tool body never runs, no target is resolved, no downstream call is
   made. The caller receives a **structured advisory tool-error naming the denying layer**
   (`tool-gate`), so the model can react — pick another tool, ask the human to escalate — rather
   than retry blindly. Never silent, never a raw stack trace.
6. **Allow ⇒ the tool body runs**, calling the existing catalog REST API with the **caller's own
   bearer**. The catalog service then does what it always does: re-derives the principal, resolves
   the role, evaluates the per-type policy on the actual resource. A deny there surfaces as the
   same structured advisory error, labelled `target-gate`.

The two-step order is deliberate: the **tool-gate always runs first**, because it is cheap (no
target resolution, one OPA call) and because a tool the agent may not call should never reach a
data path at all. The target-gate happens naturally, as a consequence of the tool executing.

**The honest cost:** the per-type policies never *see* the actor this slice. They enforce the
principal ceiling, which is what makes the composition sound, but they cannot do agent-aware **row**
filtering. A tool that needed "this agent may see only *some* of the rows its principal may see"
would need the later propagation design — it is out of scope here, and §5 says so.

### 3.2 One `tools/list`

**The verified ground truth (2026-07-29, `javap` against the pinned jars).** MCP Java SDK 2.0.0 has
**no supported seam** for varying `tools/list` per caller. The handler is a private lambda registered
into a plain `HashMap` by `McpAsyncServer` (method-local in a private method, then held in a
package-private field on the session factory — unreachable via any supported API); it reads the live
global tool list and ignores **both**
its arguments — including the fresh per-request `McpAsyncServerExchange` that already carries the
caller's session id, client info, and transport context. Every Spring AI customizer runs at
construction time only; the `mcpSyncServer` bean carries no `@ConditionalOnMissingBean`; and 2.0.0
is the newest release of both upstreams. The supported seam is java-sdk **#578** (a pluggable
`ToolsRepository` whose list method receives per-request context), targeted at the opt-in **2.1**
minor — unreleased, no date.

**The mechanism (settled 2026-07-31): delegate-then-filter, installed into the live handler map** —
split deliberately into a durable core and a disposable adapter:

- **`ToolRosterFilter` — the durable core** (survives SDK 2.1; owns all the semantics and tests).
  Given the caller's resolved identity and the delegate's `ListToolsResult`: one `AbacContext` per
  registered tool (T4's context builder, the shared turn-scoped capability memo), a **single** batch
  `allowAll` round-trip ([[0016-action-enrichment-affordance-metadata|ADR 0016]] — the `allowAll`/`bulk` batch, **total and
  fail-closed**; NOT ADR 0024's `lookupAll`, whose batch *throws* on outage) against the
  `agent_tools` document,
  booleans paired with the registry's declaration order **by index** — then reduced to an
  **allow-set of tool names** and applied to the delegate's `ListToolsResult.tools()` **by name**,
  never by position. The two lists are different objects: the contexts are built in `ToolRegistry`
  declaration order (the order contract shipped in T1), while the delegate's list is whatever order
  Spring AI's annotation scanner produced, which is nowhere contracted to match. Zipping the vector
  positionally onto the delegate's list would advertise tool A on tool B's `true` — a widening bug a
  hand-built fixture whose two orders happen to agree would never catch. Omitted-only output.
- **The adapter — disposable** (deleted the day #578 ships; the migration touches only this class).
  Once at startup, before the connector opens: reach the streamable transport provider's session
  factory (`DefaultMcpStreamableServerSessionFactory`) and its shared `requestHandlers` map — two
  reflective field reads, workable because the jars are non-modular — and wrap the `"tools/list"`
  entry: delegate to the original handler, then filter its result by the identity resolved from
  `SecurityContextHolder` — the same source `ToolCallAuthorizer` uses, per the identity paragraph
  below. **The exchange is not an identity source**: the stock auto-configured provider installs no
  `McpTransportContextExtractor`, so its default returns `McpTransportContext.EMPTY` and
  `exchange.transportContext()` is blank for every caller. All sessions share that one handler map,
  so one wrap covers every caller; the map is **never** mutated after startup.

**One hard prerequisite, with a startup guard:**

1. **`spring.ai.mcp.server.protocol=STREAMABLE`, set explicitly.** The 2.0.0 auto-config
   `@Conditional`s match the property string and ignore the properties-field default — with the
   property absent the server silently runs the legacy SSE transport (`GET /sse` +
   `POST /mcp/message`; `POST /mcp` 404s), where the adapter's seam is not reachable. The smoke
   check asserts the transport type, not just the fields.

**Identity: `SecurityContextHolder`, the same source the call path already uses** — settled by a lab
spike 2026-07-31, replacing an earlier prerequisite that assumed otherwise. The list handler runs
**inline on the servlet thread, inside the security filter chain** (Spring AI's sync auto-config sets
`immediateExecution(true)`), so the resolved `AbacAuthentication` is visible there exactly as it is in
`ToolCallAuthorizer`. Measured, not inferred: a probe filter that clears its `ThreadLocal` in a
`finally` still had the value visible inside a wrapped `tools/list` handler, on the same
`http-nio-*-exec-*` thread — proving the handler runs *before* the chain unwinds. Consequences: **no
transport-provider replacement, no custom `contextExtractor`, no wiring guard**, and — the point —
**one identity mechanism for both paths**, so the roster and the gate can never disagree about who
the caller is. Request-scoped state resolves for the same reason, so the turn-scoped capability memo
works unchanged on the list path.

**The governing invariant — the roster answers the same question the call gate will.** Whatever the
call path would decide for this caller *right now*, the roster must predict. This is what makes the
hint trustworthy in the only direction that matters, and it settles switch interactions rather than
leaving them to be discovered: with **`agent-gate` OFF** the call path evaluates an ordinary human
principal, so the roster builds its contexts **without the agent attributes** and shows the
ceiling-only cut. A roster that kept narrowing by capability while calls did not would hide tools the
caller can successfully call — the one direction a hint must never fail in.

**The seam that makes that true** (named here because the shipped T4 code does not have it): today
`ToolCallAuthorizer.buildContext` attaches `actor` / `chain` / `agent_capability` unconditionally
whenever the caller is an agent, and `authorize()` consults `agent-gate.enabled` only *afterwards*.
Reused as-is by the roster, that would make the gate-OFF roster **narrower** than the gate-OFF call
path — the forbidden direction. T5 therefore widens the builder to
`buildContext(descriptor, boolean applyAgentNarrowing)`, and both callers pass the switch, so the two
paths read it in exactly one place.

**Failure semantics — two distinct classes, deliberately different:**

- **Installation failure fails startup.** If the smoke check cannot find the pinned internals (an
  SDK or Spring AI upgrade moved them), the context fails with an error naming them. Degrading to an
  unfiltered roster here would be *safe* — the roster is a hint — but it would switch the slice's
  headline off silently; a demo whose flagship feature can quietly vanish is worse than one that
  refuses to boot after an unverified upgrade.
- **Runtime failure degrades, omit-never-fabricate — but the batch itself cannot report failure.**
  The shipped `OpaClient.allowAll` is **contractually total and fail-closed**: it never throws and
  always returns exactly N booleans, normalising every transport error, timeout, non-200, malformed
  body **and** length mismatch into an all-`false` vector (`HttpOpaClient#allowAll` → `allFalse(n)`).
  A PDP outage is therefore **indistinguishable** at this seam from a genuine zero-capability answer,
  and the design does not pretend otherwise:
  - **All-`false` is treated as authoritative ⇒ an empty roster is the correct answer**, whatever
    caused it. During an OPA outage every `tools/call` denies too, so an empty roster is the *honest*
    report — a roster advertising four tools that all fail would be the misleading one. (Settled
    2026-07-31; supersedes the earlier "never present an empty roster" rule for the batch path, which
    was written against failure modes this seam cannot express.)
  - **The degradation-to-unfiltered path survives only for the edges *outside* the batch** — the ones
    that really can fail: an unreadable roster identity, and the capability/ceiling lookups that
    precede the batch (§4). Those log WARN and serve the **unfiltered** list plus the (still-enforcing)
    call-time gate.
  - **Never fabricate**: no tool is ever added to a roster, in any failure mode.

  Degrading a *hint* is safe precisely because the hint carries no authority — and so is an empty one,
  because the authoritative gate is what actually decides.

**Uniform rule, no special-casing.** Every caller's roster answers the same policy question the call
gate asks: for a human (no delegation chain) the `bulk` rule evaluates ceiling-only; for an agent,
ceiling ∩ capability. "You see what you may call" holds for everyone — the agent-vs-human contrast
comes from the policy, not from a code branch.

**The list is a hint, never a grant.** Call-time enforcement is authoritative and always runs, even
for a tool that appeared in the roster a moment ago — which is exactly how mid-session revocation is
caught. Proving that a *listed-but-since-revoked* tool is still denied at call time is an acceptance
case, not an implementation detail.

**Stances pinned 2026-07-31.** No `listChanged` in v1: the SDK offers only a **global** broadcast
(`notifyToolsListChanged()` has no per-session variant), so a targeted nudge on capability change is
follow-up material — revocation bites at call time immediately, and at the next re-list for the
view. The **existence oracle** is accepted and documented: a roster-hidden tool still answers
`tools/call` with the layer-naming advisory deny, revealing that it exists — all demo tools are
publicly documented, and hiding existence was never a goal of this slice.

## 4. Fail-closed posture

**The invariant: no new edge widens the result.** Every edge this slice adds lands on **deny**, or
on the **smaller** of the two possible results, on any error, timeout, or missing input.

| Edge | Failure | Lands on |
|---|---|---|
| `DelegationChainExtractor` | claim absent | **principal-only** evaluation (an ordinary human call — the narrowest honest reading, never an agent grant) |
| `DelegationChainExtractor` | claim malformed / wrong type / oversized / cyclic chain | **deny** the call (do not fall back to principal-only — a broken agent claim is not a human) |
| `AgentCapabilitySupplier` | resolved profile | narrow by it |
| `AgentCapabilitySupplier` | **authoritative-empty** (agent known, zero capability) | **deny** every tool (a real answer, per [[0014-supplier-outage-error-distinct\|ADR 0014]]) |
| `AgentCapabilitySupplier` | outage / timeout (throws) | **deny** — distinct from authoritative-empty in the log and the error code, identical to the caller |
| principal ceiling (user-service) | outage / timeout / non-200 at **call** time | **deny** (`tool-gate-ceiling-unavailable`) — B2's strict classification: only an explicit success is trusted |
| tool-gate OPA call | OPA down / timeout / malformed response | **deny** (the shipped `OpaClient` fail-closed contract; [[HTTP-RESILIENCE]]) |
| tool-gate OPA call | policy returns no `allow` binding | **deny** (`default allow := false`) |
| roster batch `allowAll` | OPA down / timeout / malformed / length mismatch | the shipped client normalises **all** of these to all-`false` and never throws ⇒ an **empty roster**, treated as authoritative. Honest: during that outage every `tools/call` denies too (§3.2) |
| roster batch `allowAll` | a genuine zero-capability agent | the same **empty roster** — indistinguishable by design, and correct in both cases |
| roster batch `allowAll` | a **substituted** `OpaClient` returns a wrong-length vector (the shipped client cannot — it normalises this to all-`false` before the caller sees it, but `OpaClient` is an adopter-implementable SPI that deliberately refuses a default impl) | the **empty roster** — a defensive `booleans.size() != contexts.size()` guard in `ToolRosterFilter` lands on the *smaller* result, never the unfiltered list and never an index-shifted partial filter |
| roster identity | no `AbacAuthentication` in the security context at list time | **unfiltered list** + WARN — an unauthenticated caller never reaches the surface anyway (the chain rejects it), and the call-time gate denies on the same condition |
| capability supplier at **list** time | outage (throws) | **unfiltered list** + WARN — the hint carries no authority, and the call-time gate denies every tool for the same outage |
| principal ceiling at **list** time | `RoleResolutionException` | **unfiltered list** + WARN — same reasoning; the authoritative deny still happens per call |
| roster adapter (startup, not runtime) | pinned SDK internals moved by an upgrade | **startup failure** — the smoke check fails the context naming the pins; never a silent unfiltered no-op (§3.2) |
| tool metadata | a tool with no declared action/category/risk | **deny** at registration-time validation — an unclassifiable tool is not exposed |
| tool body | downstream catalog call denied | the target-gate's deny, surfaced as a labelled advisory error |

**Kill-switches.** Each layer has one, and **an OFF state is never wider than ON**:

- `agent-gate off` ⇒ the tool-gate is skipped ⇒ the call is evaluated exactly as a human principal's
  call would be, and the catalog service still enforces the principal ceiling on every resource.
  Switching the agent gate off therefore **cannot grant an agent more than its principal already
  has**; it only removes the *narrowing*. That is the whole point of enforcing the intersection
  across layers instead of propagating it.
- `roster-filter off` ⇒ the adapter (and its smoke check) is **not installed at all** — the
  deliberate post-upgrade escape hatch when an SDK bump moves the pinned internals — and the served
  roster is the unfiltered list, with call-time enforcement unchanged: at runtime, byte-identical to
  the degradation path above.
- There is **no** switch that disables call-time enforcement. The authoritative gate is not
  optional.

## 5. Scope boundary (explicitly NOT in this slice)

- **MCP transport / OAuth authorization** — token issuance, resource indicators, audience binding.
  Delegated to the ecosystem's MCP security layer. The scope fence sits **above the validated
  token**: the principal is the validated token's `sub`, and everything here is what happens after.
- **The host-side PEP leg** — a `ToolCallingManager` decorator on the agent host. A later leg;
  server-side `@McpTool` interception comes first because it is the one an untrusted host cannot
  skip.
- **Policy-filtered retrieval / RAG** — explicitly a later phase.
- **Role / capability propagation to the catalog service** — no header, no claim, no assertion. The
  per-type policies do not see the actor this slice (§3.1, "the honest cost").
- **Any change to the library** — no `opa-abac-*` module is created or modified. Extracting an
  optional `opa-abac-agent` module is this slice's **exit criterion** (a follow-up), not a ticket.
- **Any change to `example-catalog-management-service`, `example-user-management-service`, or the
  existing rego** — the target-gate is exercised exactly as shipped.
- **A reference `ToolCallClassifier` implementation** — contract only (see §6).
- **`notifications/tools/list_changed`** — mid-session revocation is caught by the call-time gate; a
  push notification is a documented follow-up.
- **HITL / approval flows** — no human-in-the-loop confirmation step.
- **A real LLM in the loop** — the e2e driver is a **deterministic scripted MCP client**. The authz
  path does not care whether a model or a script chose the tool.

## 6. Considered & rejected

See [[0028-agent-tool-call-authorization|ADR 0028]] for the full rationale; the summary:

| Option | Why rejected |
|---|---|
| **Host-side leg first** (decorate `ToolCallingManager` on the agent host) | The host is the least trustworthy place to enforce — a different host, or a direct MCP connection, skips it entirely. The server-side gate is the one that cannot be routed around; the host leg is an optimization on top, deferred. |
| **Role-header / capability propagation to the catalog service** | Would require the catalog service to trust a role asserted by an upstream caller — the exact fail-open shape [[MULTI-TENANT-ISOLATION\|slice B4]] removed. Enforcement by composition gets the same intersection with zero added trust. |
| **The MCP server evaluating the target-gate itself** (resolving the resource and asking the per-type policy) | Duplicates enforcement in a second place, which then drifts from the service that owns the resource semantics. It also demands the MCP server resolve targets it does not own. The service that owns the data keeps its own gate. |
| **Embedded domain access** (shared DB / embedded catalog module) | Bypasses the target-gate entirely and forces re-implementation of every per-type policy path. Calling the existing REST API with the caller's token keeps enforcement in exactly one place per layer. |
| **The agent as a grant-holder** (standing capabilities independent of a principal) | An agent that can act beyond any human is an unbounded privilege. Capability may only **narrow** a principal's ceiling; the intersection is the whole model, and it is computed in Rego so it is auditable. |
| **A reference `ToolCallClassifier` implementation** | Every demo tool **declares** its action/category/risk statically, so an implementation would be an unconsumed seam with no test that could distinguish it from a stub. The contract ships (most-restrictive-on-ambiguity); the implementation waits for a consumer. |
| **`listChanged` notifications in v1** | Solves a UX problem (a stale roster), not a security one — the call-time gate already denies a revoked tool. Adds session state and a notification path for no authorization gain. Documented follow-up. |
| **A real-LLM e2e driver** | Non-deterministic tool selection makes the e2e assert response *shape* instead of **the actual cut** (which tools, which denials). A scripted client asserts the cut, which is what the suite is for ([[E2E-TESTING]]). |
| **Keycloak token-exchange / an experimental build for the `act` claim** | RFC 8693 token-exchange support that carries `act` is not in a stock release we can pin the rig to. A plain custom claim minted by a stock protocol mapper carries the same **semantics**, keeps the rig reproducible, and leaves the extractor seam free to read a real `act` later. |
| **A library module in this slice** (`opa-abac-agent` up front) | Extracting an abstraction from one consumer guesses at the seam. Example-first; extraction is the exit criterion once the shape is proven. |
| **Per-request `addTool`/`removeTool` to shape the roster** | Global server state: one caller's roster leaks to every concurrent session, and it races. The 2026-07-28 spec revision additionally *forbids* per-connection variation. Written down so nobody reaches for the obvious API later. |
| **The stateless-protocol handler decorator** (`McpStatelessServerHandler`, mechanism B of the T5 investigation) | Public-API-only — no reflection — but it switches the whole server to the STATELESS variant: no sessions, no server-initiated features, per-request `initialize`, different client framing. A protocol change is a bigger contract change than one deletable reflective adapter. Closest to where upstream PR #1034 is heading; revisit at SDK 2.1. |
| **Transport-layer JSON-RPC response rewrite** (a servlet filter on `/mcp`) | Verified workable — non-`initialize` POSTs are answered SSE-framed deterministically — but it re-implements JSON-RPC + SSE parsing at the HTTP layer, must parse (never substring-sniff) the method, and couples to response framing. Last resort, ranked below both mechanisms. |
| **Waiting for SDK 2.1's `ToolsRepository` (java-sdk #578) — descoping the roster from v1** | The supported seam, but unreleased with no committed date. The headline ships now behind a one-class adapter scheduled for deletion; the migration note pins #578 so the wait happens *in parallel*, not *instead*. |

## 7. Baseline pins

Spring AI **2.0.0** · MCP Java SDK **2.0.0** · MCP spec revision **2025-11-25** (SDK support for the
2026-07-28 revision is months out; the authorization layer is transport-revision-agnostic, so the pin
costs nothing here). Java 25 / Spring Boot 4.0 / Gradle 9.x as the rest of the repo
([[0026-spring-boot-4-single-line-port|ADR 0026]]). T1 carries a 30-second confirm that the spec site
has flipped **Current** to the 2026-07-28 revision — a docs-only note; it changes nothing in this slice.

**Two pins added by the 2026-07-31 amendment:** `spring.ai.mcp.server.protocol=STREAMABLE` must be
**explicit** — the 2.0.0 auto-config conditionals ignore the properties-field default, and an absent
property silently serves the legacy SSE transport where `POST /mcp` 404s (verified against the jars;
recorded in the `opa-abac-sb4-integration` expertise domain). And the roster adapter pins three
SDK-2.0.0 internals (§3.2), smoke-checked at startup — upgrading either jar deliberately re-opens
that reckoning, which is the intended migration trigger toward java-sdk #578.

## Related

- [[AGENT-TOOL-AUTHZ]] · [[01-DECOMPOSITION]] · [[10-QA-TEST-CASES]]
- [[0028-agent-tool-call-authorization|ADR 0028]] (the decisions) ·
  [[0014-supplier-outage-error-distinct|ADR 0014]] (the tri-state supplier doctrine) ·
  [[0018-team-scoped-resource-isolation|ADR 0018]] (why nothing asserts a role downstream) ·
  [[0023-request-scoped-resolution-memoization|ADR 0023]] (the memo scope this one narrows to a turn) ·
  [[0016-action-enrichment-affordance-metadata|ADR 0016]] (the `allowAll` batch primitive the roster filter
  reuses) · [[0024-batch-role-resolution|ADR 0024]] (the *role* batch `lookupAll` — a different
  primitive with the opposite, throwing failure contract)
- [[ABAC-AUTHORIZATION]] · [[ABAC-AUTHORIZATION]] · [[PERMISSION-MODEL]] · [[HTTP-RESILIENCE]] · [[E2E-TESTING]]
- [[POC-ROADMAP]] (Phase 9) · [[MULTI-TENANT-ISOLATION]] (slice B4, the fail-open shape not reintroduced)
