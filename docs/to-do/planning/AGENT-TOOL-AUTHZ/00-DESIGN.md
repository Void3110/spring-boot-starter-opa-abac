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
> smallest honest demo. Grilled 2026-07-27, forked to a decomposition 2026-07-28; the structural
> rationale is pinned by **[[0028-agent-tool-call-authorization|ADR 0028]]** (referenced here,
> not repeated). Nothing in the library changes: this slice is a **new example service** that
> puts the shipped starter behind an MCP tool surface.

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
  An agent is told about tools it must not use, and the model learns to want them.
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
    McpToolAuthorizationAspect    server-side @McpTool interception — runs BEFORE the tool body
    ToolRosterFilter              tools/list pre-flight via the batch `allowAll` primitive
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
| `input.resource.type` = `"tool"`, `input.resource.attributes` | the tool's declared category + risk-tags |
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

`ToolRosterFilter` runs a **batch `allowAll` pre-flight**: one context per registered tool, one OPA
round-trip, N booleans back (the same primitive [[0024-batch-role-resolution|ADR 0024]] introduced
for role resolution). The agent is advertised only the tools it may call, so the model is not
tempted by capability it does not have.

**The list is a hint, never a grant.** Call-time enforcement is authoritative and always runs, even
for a tool that appeared in the roster a moment ago — which is exactly how mid-session revocation is
caught. Proving that a *listed-but-since-revoked* tool is still denied at call time is an acceptance
case, not an implementation detail.

**Omit-never-fabricate.** If the roster batch fails, the server degrades to the **unfiltered** list
plus the (still-enforcing) call-time gate. It never presents "no tools" — that looks broken and the
agent gives up — and it never fabricates a tool. Degrading the *hint* is safe precisely because the
hint carries no authority.

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
| tool-gate OPA call | OPA down / timeout / malformed response | **deny** (the shipped `OpaClient` fail-closed contract; [[HTTP-RESILIENCE]]) |
| tool-gate OPA call | policy returns no `allow` binding | **deny** (`default allow := false`) |
| roster batch `allowAll` | any failure | **unfiltered list** + call-time gate still enforcing (degrading a hint, not a grant) |
| tool metadata | a tool with no declared action/category/risk | **deny** at registration-time validation — an unclassifiable tool is not exposed |
| tool body | downstream catalog call denied | the target-gate's deny, surfaced as a labelled advisory error |

**Kill-switches.** Each layer has one, and **an OFF state is never wider than ON**:

- `agent-gate off` ⇒ the tool-gate is skipped ⇒ the call is evaluated exactly as a human principal's
  call would be, and the catalog service still enforces the principal ceiling on every resource.
  Switching the agent gate off therefore **cannot grant an agent more than its principal already
  has**; it only removes the *narrowing*. That is the whole point of enforcing the intersection
  across layers instead of propagating it.
- `roster-filter off` ⇒ the unfiltered list, with call-time enforcement unchanged — identical to the
  degradation path above.
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

## 7. Baseline pins

Spring AI **2.0.0** · MCP Java SDK **2.0.0** · MCP spec revision **2025-11-25** (SDK support for the
2026-07-28 revision is months out; the authorization layer is transport-revision-agnostic, so the pin
costs nothing here). Java 25 / Spring Boot 4.0 / Gradle 9.x as the rest of the repo
([[0026-spring-boot-4-single-line-port|ADR 0026]]). T1 carries a 30-second confirm that the spec site
has flipped **Current** to the 2026-07-28 revision — a docs-only note; it changes nothing in this slice.

## Related

- [[AGENT-TOOL-AUTHZ]] · [[01-DECOMPOSITION]] · [[10-QA-TEST-CASES]]
- [[0028-agent-tool-call-authorization|ADR 0028]] (the decisions) ·
  [[0014-supplier-outage-error-distinct|ADR 0014]] (the tri-state supplier doctrine) ·
  [[0018-team-scoped-resource-isolation|ADR 0018]] (why nothing asserts a role downstream) ·
  [[0023-request-scoped-resolution-memoization|ADR 0023]] (the memo scope this one narrows to a turn) ·
  [[0024-batch-role-resolution|ADR 0024]] (the batch primitive the roster filter reuses)
- [[ABAC-AUTHORIZATION]] · [[ABAC-AUTHORIZATION]] · [[PERMISSION-MODEL]] · [[HTTP-RESILIENCE]] · [[E2E-TESTING]]
- [[POC-ROADMAP]] (Phase 9) · [[MULTI-TENANT-ISOLATION]] (slice B4, the fail-open shape not reintroduced)
