---
tags:
  - status/active
  - type/guide
  - area/abac
  - area/opa
  - area/rego
  - area/security
  - area/spring
---

# Agent tool-call authorization — two decision layers, no propagation

> **Phase 9** (ADR [[0028-agent-tool-call-authorization|0028]]). The starter answers *"may this
> **human** act on this resource?"*. An AI agent calling tools on a person's behalf collapses two
> identities into one bearer token, so the agent inherits the **whole** human ceiling — and the tool
> surface itself has no gate at all. This guide is the shipped contract for the gate that closes
> that: a new example service, `example-mcp-server`, whose `@McpTool` methods proxy the catalog REST
> API and are authorized **before** they run, by a policy that computes **principal ceiling ∩ agent
> capability in Rego**.
>
> **Nothing in the library changes.** No `opa-abac-*` module, no existing service, no existing
> `.rego` document. The MCP server consumes the published starter exactly as an adopter would.
>
> *(T5 wrote this guide; T6 adds the rig and end-to-end sections.)*

## Why the MCP specification does not cover this

The spec stops at the transport edge, deliberately:

- It specifies how a server **validates a bearer token** and nothing above it. There is **no per-tool
  authorization** concept: a token that passes validation may call **every** tool the server exposes.
- There is **no mechanism for filtering the advertised tool list**. The 2026-07-28 revision
  *sanctions* the set varying by the authorization presented — and forbids per-connection variation —
  but ships no mechanism.
- The identity reaching the tool body is a **single** identity, the token's `sub`. Nothing
  distinguishes *the human on whose behalf the call is made* from *the agent making it*, so a policy
  cannot bound the agent separately from the person.

## The mechanism: two layers, nothing asserted downstream

```
  agent ──MCP──▶  example-mcp-server              ──REST (the caller's own bearer)──▶  catalog service
                  ┌───────────────────────────┐                                       ┌──────────────────┐
                  │ LAYER 1 — the TOOL-GATE   │                                       │ LAYER 2 —        │
                  │  may this (principal,     │                                       │  the TARGET-GATE │
                  │  actor) invoke THIS TOOL? │                                       │  may this        │
                  │  → agent_tools.rego (NEW) │                                       │  PRINCIPAL touch │
                  │  → ceiling ∩ capability   │                                       │  THIS RESOURCE?  │
                  └───────────────────────────┘                                       │  → UNCHANGED     │
                                                                                      └──────────────────┘
        principal ceiling ∩ agent capability                ∩            principal ceiling on the row
```

The load-bearing property is what is **not** in that picture: the MCP server sends the catalog
service the caller's bearer **and nothing else** — never a role, a capability, a delegation chain, or
an acting-as header, and never a minted, exchanged or rewritten token. The catalog service therefore
re-derives the principal and applies the same ceiling it would for a direct REST call. The
intersection holds **across** the two layers rather than being passed between them, which is why the
MCP server can only ever fail to narrow — it can never widen. (Asserting a caller-supplied role is
exactly the fail-open shape [[MULTI-TENANT-ISOLATION|slice B4]] removed; it is not reintroduced.)

The honest cost: the per-type policies never *see* the actor, so they cannot do agent-aware **row**
filtering. A tool needing "this agent may see only *some* of the rows its principal may see" would
need a propagation design, which is out of scope here.

## Dual identity: principal, actor, chain

`DelegationChainExtractor` normalizes the validated token into a `DelegationChain` — the principal
(the token `sub`), the actor (the agent), and an ordered chain — read from a **plain custom claim**
carrying RFC 8693 `act` semantics, minted by a stock Keycloak protocol mapper. No token exchange, no
nightly build.

| Claim state | Reading | Why |
|---|---|---|
| **absent** | an ordinary **human** call — principal-only evaluation | Honest, not a bypass: principal-only is the widest the tool-gate ever goes, and it is still exactly the principal's own ceiling. Agent narrowing only ever restricts. |
| **present, well-formed** | an agent call — the intersection applies | |
| **malformed / oversized / cyclic** | **deny** | A broken agent claim is not a human. Never downgrade malformed to principal-only. |

## The capability seam is tri-state

`AgentCapabilitySupplier` resolves the actor's profile — allowed categories, an allowed-tool list,
allowed actions, a max risk tag. It follows the repo's supplier doctrine (ADR
[[0014-supplier-outage-error-distinct|0014]]) exactly:

| Outcome | Decision | Distinguishable? |
|---|---|---|
| **resolved** | narrow by the profile | |
| **authoritative-empty** (agent known, zero capability) | **deny every tool** — a real answer | distinct **internal** code + log line |
| **outage** (throws) | **deny** | distinct internal code + log line, **identical** caller-facing error (no oracle) |

The profile's `allowed-tools` list is **mandatory**: an empty list denies every tool. Reading it as
"unrestricted" would turn a profile someone trimmed to nothing into a profile that permits
everything.

The lookup is memoized **per turn** — a turn is *one MCP request*, never a session. Within a turn the
roster and the gate must see one capability answer or they could disagree; across turns the memo must
die, or revoking an agent's capability mid-session would not take effect until the client reconnected
and "the list said I could" would quietly become an authorization.

## The intersection is computed in Rego

`infra/opa/policies/agent_tools.rego` (package `agent_tools`, `default allow := false`) consumes the
shipped `permission_categories` expansion, so a principal's ceiling here is derived exactly the way
every other enforced path derives it:

```rego
principal_actions := permissions.effective_actions(input.role_definition, input.resource.attributes.target_type)
agent_actions     := {action | some action in capability.allowed_actions}
effective_actions := principal_actions & agent_actions   # set intersection can only SHRINK
```

That one line is the whole agent model: **a capability naming an action the ceiling does not grant
contributes nothing**. An agent can never do what its principal cannot. It lives in the policy, not
in Java, so it is auditable and `opa test`-able rather than drifting from the rule that is supposed
to own it.

Category, tool allow-list and risk-tag checks are independent `AND`s on top, so satisfying one never
compensates for failing another. The risk ranking is a table **in the policy** for the same reason.

## The roster: a hint, never a grant

`tools/list` advertises only the tools the caller may actually call — one batch `allowAll`
round-trip (the ADR [[0016-action-enrichment-affordance-metadata|0016]] primitive) over one
`AbacContext` per registered tool, against the same `bulk` rule the call path asks.

**Uniform for every caller, with no code branch:** a human (no chain) is evaluated ceiling-only, an
agent gets ceiling ∩ capability. The contrast comes from the policy.

Three rules make the hint safe:

1. **Call-time enforcement is authoritative and always runs.** A listed tool never skips the gate.
   That is how mid-session revocation is caught — and it is why the roster may be degraded at all.
   There is deliberately **no** roster-derived cache and no "already checked in the pre-flight"
   shortcut; the moment one appears, the hint has silently become an authorization.
2. **The roster answers the same question the call gate will.** Whatever the call path would decide
   *right now*, the roster predicts. This settles the switch interaction rather than leaving it to be
   discovered: with `agent-gate` **off** the call path evaluates an ordinary human principal, so the
   roster builds its contexts **without** the agent attributes and shows the ceiling-only cut. A
   roster that kept narrowing while calls did not would hide tools the caller can successfully call —
   the one direction a hint must never fail in.
3. **Omit, never fabricate.** A tool is only ever removed from the advertised list. Nothing is added,
   in any failure mode.

Decisions are paired with the registry's declaration order **by index**, then applied to the
delegate's list **by name** — the two orders are different lists and nothing contracts them to agree.

## Fail-closed posture

Every edge lands on **deny, or the smaller result**.

| Edge | Failure | Lands on |
|---|---|---|
| delegation chain | claim absent | principal-only (an ordinary human call) |
| delegation chain | malformed / oversized / cyclic | **deny** |
| capability supplier | authoritative-empty | **deny** every tool |
| capability supplier | outage | **deny** (distinct code, identical caller-facing error) |
| principal ceiling | outage at **call** time | **deny** (`tool-gate-ceiling-unavailable`) |
| tool-gate OPA call | down / timeout / malformed / no `allow` binding | **deny** |
| tool metadata | a tool with no declared action/category/risk/target-type | **deny at registration time** — startup fails, the tool is never exposed |
| roster batch | dead PDP, **or** a genuinely zero-capability agent | the **empty roster**, treated as authoritative (see below) |
| roster identity / capability / ceiling at **list** time | unreadable or throwing | the **unfiltered** list + WARN, with the gate still denying per call |
| roster adapter | pinned SDK internals moved by an upgrade | **startup failure**, naming the pins |

**Why an empty roster is the honest answer.** `OpaClient.allowAll` is contractually **total and
fail-closed**: it never throws, and normalises outage, timeout, non-200, malformed body *and* length
mismatch alike into an all-`false` vector. "The batch failed" is therefore not a signal this seam can
emit — a PDP outage is indistinguishable from a zero-capability agent, and the design does not
pretend otherwise. Both are answered with an empty roster, which is correct in both cases: during
that outage every `tools/call` denies too, so a roster advertising four unusable tools would be the
misleading one. Degradation to the *unfiltered* list survives only for the edges **outside** the
batch, which genuinely can fail.

### Kill-switches — and what OFF means

| Switch | OFF behaviour |
|---|---|
| `example.mcp.authz.agent-gate.enabled` | The tool-gate stops **narrowing**. The call is evaluated exactly as an ordinary human principal's, and the catalog service still enforces that principal's ceiling on every resource — so OFF **cannot grant an agent more than its principal already has**. The roster reads the same switch and shows the same ceiling-only cut. |
| `example.mcp.authz.roster-filter.enabled` | The adapter **and its smoke check** are not installed at all — the deliberate escape hatch for the day an SDK bump moves the pinned internals. The served list is the unfiltered one, byte-identical to the outside-the-batch degradation path, with call-time enforcement unchanged. |

There is **no** switch that disables call-time enforcement. The authoritative gate is not optional.

## Denials are structured, and name the layer

A deny is a `CallToolResult` with `isError` set and structured content carrying a stable code **and
the denying layer** — `tool-gate` or `target-gate`. That is the protocol's own way of telling a model
something went wrong in a way it can act on: pick another tool, ask the human to escalate. Never a
stack trace, never a bare 5xx, never a silent empty result. The two layers being distinguishable is
what lets a model react instead of retrying blindly.

## Running it

The tool-gate wraps **every** advertised tool's call handler by post-processing the specification
list Spring AI's scanner produces, so a tool added later cannot be forgotten — nothing enumerates
tool names.

```bash
./gradlew :example-mcp-server:test
opa test infra/opa/policies -v
```

> **One configuration pin you must not lose.** `spring.ai.mcp.server.protocol: STREAMABLE` is set
> **explicitly** in `application.yml`. Spring AI 2.0.0's auto-configuration matches the property
> *string* and ignores the properties class's own field default: with the property absent, the SSE
> condition (`matchIfMissing = true`) wins and the server silently serves the legacy transport
> (`GET /sse` + `POST /mcp/message`) while `POST /mcp` 404s. The roster adapter's seam exists only on
> the streamable transport, and its smoke check fails startup naming this property if it is missing.

> **The streamable transport streams, so the security chain must permit `ASYNC`.** A `tools/list`
> response is written as SSE via an async dispatch; on that re-dispatch a stateless chain has no
> authentication left, and `authenticated()` would deny a response that has *already started* —
> aborting a half-written chunked body rather than returning a clean 403. Permitting `ASYNC` (as this
> module's `SecurityConfig` does, alongside `ERROR`) widens nothing: an async dispatch can only exist
> for a request that already passed the chain on its initial dispatch.

## Scope boundary — explicitly not in this slice

MCP transport / OAuth authorization (the scope fence sits **above** the validated token, delegated to
the ecosystem's MCP security layer) · the host-side `ToolCallingManager` leg · HITL / approval flows ·
policy-filtered retrieval · `listChanged` notifications (the SDK offers only a *global* broadcast;
revocation bites at call time immediately, and at the next re-list for the view) · a real LLM in the
loop · extracting an `opa-abac-agent` library module (the slice's exit criterion).

**The existence oracle is accepted and documented:** a roster-hidden tool still answers `tools/call`
with the layer-naming advisory deny, revealing that it exists. All demo tools are publicly
documented, and hiding existence was never a goal.

## Related

- ADR [[0028-agent-tool-call-authorization|0028]] — the two-layer decision model, enforcement by
  composition, the additive dual-identity subject shape, example-first packaging.
- ADR [[0014-supplier-outage-error-distinct|0014]] — the tri-state supplier contract the capability
  seam copies · ADR [[0023-request-scoped-resolution-memoization|0023]] — the memo scope this slice
  narrows to a turn · ADR [[0016-action-enrichment-affordance-metadata|0016]] — the batch `allowAll`
  primitive the roster reuses.
- [[ABAC-AUTHORIZATION]] — how an `AbacContext` is built and asked · [[PERMISSION-MODEL]] — the
  `permission_categories` expansion the ceiling is derived through · [[HTTP-RESILIENCE]] — the
  edge-wrapping discipline every outbound call here follows.
- [[MULTI-TENANT-ISOLATION]] — where asserting a caller-supplied role was removed as fail-open.
