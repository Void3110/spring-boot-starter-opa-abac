# opa-abac-mcp — the agent tool-gate for MCP servers (incubating)

Agent tool-call authorization for a [Model Context Protocol](https://modelcontextprotocol.io) server
built on Spring AI. Every `tools/call` is decided by OPA **before the tool body runs**, as
*principal ceiling ∩ agent capability*, computed in Rego — in front of the target service's own,
unchanged per-resource policies. The contract is
[ADR 0036](../docs/architecture/adr/0036-mcp-auth-library-module.md); the two-layer model it packages is
[ADR 0028](../docs/architecture/adr/0028-agent-tool-call-authorization.md); the full guide is
[AGENT-TOOL-AUTHORIZATION](../docs/guides/AGENT-TOOL-AUTHORIZATION.md).

> **Incubating.** This module's API may change in a **minor** release until an external consumer
> adopts it (ADR 0036 §1). Pin the version you build against.

## What is in it

| Package | Holds |
|---|---|
| `dev.dmitriikonovalov.opaabac.mcp.identity` | the dual identity behind one token (`DelegationChain`, `DelegationChainExtractor` + the claim-based default), the agent-capability seam (`AgentCapabilitySupplier`, tri-state, with a YAML default), the turn-scoped capability memo, and the startup check that the actor claim reaches the subject |
| `dev.dmitriikonovalov.opaabac.mcp.authz` | the decision: the PEP (`ToolCallAuthorizer`, also a direct API for hand-built specifications), one adapter per MCP SDK tool-specification type (`ToolCallGate`, `AsyncToolCallGate`, `StatelessToolCallGate`, `StatelessAsyncToolCallGate` — each decides when its handler is invoked, never inside a `Mono`), the `tools/list` roster decision (`ToolRosterFilter`), the policy-path decorator, and the **`PrincipalCeilingSupplier`** SPI (no default) with its request memo |
| `dev.dmitriikonovalov.opaabac.mcp.tool` | the static tool declarations (`ToolDescriptor`, `ToolRegistry`) and the structured, layer-labelled tool failure (`ToolInvocationException`, `ToolFailureRecord`, `ToolErrorLayer`) |

The module holds types and logic and **declares no beans**: the starter's `OpaMcpAutoConfiguration` wires
it when the module sits next to Spring AI's MCP server. You provide a `PrincipalCeilingSupplier` and a
`ToolRegistry`; everything else is defaulted and overridable. A missing `OpaClient` (including
`opa.abac.enabled=false`), a missing ceiling source, a tool specification type the module cannot wrap or a
reactive (WebFlux) application **fails startup** — the module is never present but inert. See the guide's *Installing the module*.

## Properties

| Property | Default | Meaning |
|---|---|---|
| `opa.abac.mcp.identity.actor-claim` | `act_chain` | the claim carrying RFC 8693 `act` semantics; must also be listed in `opa.abac.subject.attribute-claims` |
| `opa.abac.mcp.identity.max-chain-depth` | `4` | deeper chains deny |
| `opa.abac.mcp.identity.max-claim-length` | `2048` | larger claims deny, before any traversal |
| `opa.abac.mcp.identity.agent-clients` | empty | OAuth client ids (`azp`) that are agents: a listed client's token without an actor claim is denied `tool-gate-actor-required`; non-empty requires `azp` in `opa.abac.subject.attribute-claims` |
| `opa.abac.mcp.agent-gate.enabled` | `true` | `false` drops the agent conjunct only — the call is the principal's, still ceiling-bounded; nothing turns the gate off |
| `opa.abac.mcp.policy-path` | `agent_tools` | the OPA **package** the tool-gate asks (`/bulk` is appended for the roster batch) |
| `opa.abac.mcp.agents.profiles[]` | none | the YAML capability default: `actor-id`, `allowed-categories`, `allowed-tools` (mandatory — empty denies every tool), `allowed-actions`, `max-risk-tag`; an unconfigured agent is authoritative-empty |

## Dependencies

`opa-abac-spring-security` (and through it `opa-abac-core`) and the MCP Java SDK's `mcp-core`, pinned
to the version Spring AI 2.0.x depends on. **No Spring AI artifact** is a dependency of this module.

```kotlin
implementation(platform("dev.dmitriikonovalov:opa-abac-bom:<version>"))
implementation("dev.dmitriikonovalov:opa-abac-spring-boot-starter")
implementation("dev.dmitriikonovalov:opa-abac-mcp")
```
