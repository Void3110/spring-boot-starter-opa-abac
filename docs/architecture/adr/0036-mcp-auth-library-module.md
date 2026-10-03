---
tags:
  - status/active
  - type/decision
  - area/abac
  - area/opa
  - area/spring
  - area/mcp
---

# ADR 0036 — `opa-abac-mcp`: the MCP tool-gate as an incubating library module

**Status:** Accepted (planned) — slice **MCP-AUTH-LIBRARY** ([[MCP-AUTH-LIBRARY]], design [[MCP-AUTH-LIBRARY/00-DESIGN|00-DESIGN]])
**Date:** 2026-10-03
**Context tags:** agent tool-call authorization, MCP, module extraction, incubating API, input-schema versioning, fail-closed wiring
**Supersedes:** the recorded deferral decision "extract at the second consumer, not at an SDK version"
(Mulch `opa-abac-methodology` mx-019194, 2026-08-01). **Amends** [[0028-agent-tool-call-authorization|ADR 0028]]'s
packaging line: the optional module ships, under the name `opa-abac-mcp` rather than `opa-abac-agent`.

> [[0028-agent-tool-call-authorization|ADR 0028]] shipped agent tool-call authorization as an **example**
> (`example-mcp-server`) and named a reusable module as the slice's exit criterion. On 2026-08-01 that
> extraction was deferred until a second consumer had shaped the seams. This ADR records why the
> extraction now goes ahead, what the published module is — and, because a library is consumed by code
> we do not see, the wiring and contract rules that keep it from failing open in someone else's
> deployment.

## Context

Phase 9 delivered the two-layer model — a **tool-gate** (may this principal, through this agent, invoke
this tool at all?) in front of the target service's **unchanged** per-resource policies, with the agent's
narrowing computed in Rego as `principal_actions ∩ agent_actions` — entirely inside `example-mcp-server`.
The deferral record weighed extracting it and decided to wait, for three stated reasons: a single
consumer (N = 1) had shaped every seam; the role source, the capability source and the gate-off semantics
had each just been found wrong by review or by the rig; and the `tools/list` adapter reflected into MCP
Java SDK internals that java-sdk **#578** was expected to replace in the 2.1 minor. Its escape clause:
*if a consumer needs it before N = 2, ship an honest incubating artifact, "never a 1.0 shaped by one
example."*

What changed by 2026-10-03, each measured:

1. **The hand-port the record called for has happened** — in a codebase outside this repo, built on the
   starter. What it kept: the call-time gate as a pre-flight that can only narrow, and the fail-closed
   failure table. What it changed: the role source (its own membership model), the decision placement
   (an explicit gate call in each tool body — forced, because the starter's annotation path goes inert
   when the starter is disabled, which that service ships). What it **dropped**: the `tools/list` roster
   filter entirely. That is the second-consumer signal, read honestly: the kernel generalizes; the
   roster adapter did not need to.
2. **java-sdk #578 moved to milestone 2.2** and is still open; the newest MCP Java SDK is 2.0.1. Waiting
   for the supported `tools/list` seam is now an open-ended wait, so "wait for the SDK" no longer argues
   for anything.
3. **The SDK coupling is still confined.** Re-measured 2026-10-03: 17 MCP-SDK / Spring AI imports in six
   classes, of which only three move into the library (`ToolCallGate`, `ToolRosterFilter`, the gate's
   configuration); the other three stay in the example or are replaced (the example's own tools, the
   reflective installer, the annotation-scanning validator). The identity package, the decision and the
   SPIs — most of the ~2,000 lines that move — carry none: the split between a stable kernel and a thin
   adapter is already in the code.

## Decision

### 1. Ship it, incubating

A new published module, **`opa-abac-mcp`**, in the BOM at the project version (first: 1.4.0). Its API is
**incubating**: it may change in a minor release until an external consumer adopts it — stated in its
`package-info`, its README and the root README's module table. No `@Incubating` annotation type: that
would itself be API to keep.

### 2. Layout: one module, auto-configured by the starter; `opa-abac-core` untouched

`opa-abac-mcp` (package `dev.dmitriikonovalov.opaabac.mcp`) holds the identity kernel, the capability
seam, the tool declarations, the decision and its MCP adapter. The starter auto-configures it on the
[[0020-user-directory-port|ADR 0020]] pattern: `compileOnly` on the module and on Spring AI, activated only
when both are on the classpath. `opa-abac-core` gains nothing — the schema marker (§7) rides the existing
`AbacContext.environment` map. A protocol-agnostic agent module (for a host-side `ToolCallingManager`
gate) is **not** split out: it has no consumer, and the incubating status allows the split later.

### 3. The gate is installed on every tool, and is never silently inert

- The module wraps **all four** MCP SDK tool-specification types (sync / async × stateful / stateless).
  A specification of any type it cannot wrap — e.g. one a future SDK adds — **fails startup**. Never a
  skip.
- It is **not** gated on `opa.abac.enabled`. On the classpath with a prerequisite missing (no `OpaClient`,
  no `PrincipalCeilingSupplier`) it **fails startup** naming the missing piece — there is no
  "present but not enforcing" state.
- **OPA is required.** There is no Java decision path: the intersection, the risk ordering and the
  category integrity check stay in `agent_tools.rego`, where `opa test` covers them. A pluggable decider
  may come later if a real consumer needs a PDP-optional mode.
- No property disables the gate. `opa.abac.mcp.agent-gate.enabled=false` removes the **agent conjunct**
  only — the call is then the principal's own, still ceiling-bounded (never wider than ON, as today).
- The decision is also public as a direct API (`ToolCallAuthorizer`) for tool specifications built by
  hand; the wrapping is the default path.

### 4. Identity is read on the calling thread — the transport context is the planned successor

The gate reads the principal from `SecurityContextHolder` and the turn-scoped state from the request
attributes, as today, and **decides when the handler is invoked** (before any `Mono` is returned), so
async handlers are decided on the thread the identity lives on. An integration test per specification
type proves the identity is visible on Spring MVC; a type for which it is not falls under §3 (startup
failure). Spring WebFlux is **out of scope for v1** and detected at startup. The thread-independent
successor — identity carried in the SDK's `McpTransportContext` — needs a context-extractor hook Spring AI's
transport auto-configuration does not expose (2.0.0 and current `main`); the module adopts it when one
exists rather than taking over Spring AI's transport beans.

### 5. The roster filter: the decision ships, the reflective installer does not

`ToolRosterFilter` (the decision: which declared tools this caller may see; one batch round-trip; a hint,
never a grant; its three failure classes) ships in the module. `RosterFilterInstaller` — two reflective
reads of SDK-private fields — **stays in `example-mcp-server`**. The published jar never touches a private
field of another library. When java-sdk #578 ships, a minor release adds a supported installer.

### 6. The SPIs and their contracts

- **`PrincipalCeilingSupplier`** (new) — `(subject, resourceType) → Optional<RoleDefinition>`, the
  principal's authority over *some* resource of a type. Contract: grants **unioned** across the governed
  resources, a denial kept only when it applies to all of them; **over-approximation allowed,
  under-approximation forbidden** (the gate is not the authority on resources — the target service is);
  empty = authoritative no-role ⇒ deny; failure ⇒ throws ⇒ deny, never "no ceiling". **No default
  implementation** (a missing bean fails startup). Decorated by the request-scoped memo
  ([[0023-request-scoped-resolution-memoization|ADR 0023]]). Not `RoleDefinitionSupplier` with a null id:
  that would silently reinterpret every adopter's existing supplier.
- **`AgentCapabilitySupplier`** — unchanged tri-state ([[0014-supplier-outage-error-distinct|ADR 0014]]
  applied to capability), plus a **YAML-backed default** (`opa.abac.mcp.agents.<id>`,
  `@ConditionalOnMissingBean`): an unconfigured agent is authoritative-empty ⇒ every tool denied; a human
  call is unaffected.
- **`DelegationChainExtractor`** — unchanged: configurable claim, nested RFC 8693 `act` or flattened
  forms, depth/size bounds, loop rejection, iterative walk, malformed ⇒ deny, and the startup check that
  the claim is actually copied into the subject.

### 7. The input carries a schema version, and the policy refuses one it does not know

The tool-gate input carries **`input.environment.schema = "opa-abac.mcp.tool-gate/v1"`**. The reference
`agent_tools.rego` grants nothing unless the version is one it knows — an `opa test` pins *unknown version
⇒ deny, human calls included*. Any incompatible input change bumps the version, so a policy older than the
jar **denies** instead of misreading. Pinned for v1: the actor stays at **`input.subject.attributes.actor`**
— the policy recognizes an agent call by that key's presence, so moving it without a version bump would
turn every agent call into an unnarrowed human call with every test still green. The v1 field list is
published in [[AGENT-TOOL-AUTHORIZATION]].

### 8. Agent clients must carry an actor

`opa.abac.mcp.identity.agent-clients` (default empty) lists OAuth client ids that are agents. A token whose
`azp` is listed but which carries no valid actor claim is **denied** as a misconfigured agent client —
never evaluated as a human call. Empty list = today's behavior. Residual, documented: a user handing their
own token to an agent is a human call, bounded by the human's ceiling.

### 9. Declarations are validated against what is actually wrapped

Tools are declared in a `ToolRegistry` of `ToolDescriptor(name, action, category, targetType, riskTags)`.
The wrapping step checks every specification it wraps against the registry, **both directions** (advertised
but undeclared; declared but never advertised), and fails startup on a mismatch — covering hand-built
specifications the annotation scan never saw. The gate also denies an undeclared tool at call time.

### 10. Supported versions

MCP Java SDK 2.0.x and Spring AI 2.0.x, on Spring Boot **4.0.x and 4.1.x** — both tested: the repo stays on
its 4.0.x pin and the build job re-runs the module's and the example's tests with Boot 4.1.x. The module's
Spring AI / MCP dependencies take versions from the Spring AI BOM, so an adopter's BOM wins. Spring AI 2.1
is out of scope until it is GA.

### 11. Conventions

Properties under `opa.abac.mcp.*` (`agent-gate.enabled`, `policy-path`,
`identity.{actor-claim,max-chain-depth,max-claim-length,agent-clients}`, `agents.<id>`); the example's
`example.mcp.*` keys move there, except `example.mcp.authz.roster-filter.enabled`, which stays with the
installer. Denials are a `CallToolResult` with `isError` and a structured `{layer, code}`; the codes are
documented API. `ToolInvocationException` + `ToolFailureRecord` (a tool body reporting a target-gate
denial with its layer intact) ship in the module. `ToolCallClassifier` (a contract-only SPI with no
implementation) does **not** ship in v1.

## Considered options

| Option | Why not |
|---|---|
| **Keep deferring** (the 2026-08-01 rule) | The two things it waited for resolved differently: the hand-port happened, and the SDK seam slipped to an open-ended 2.2. Waiting longer buys no new information. |
| **Extract as stable** | A 1.x API promise shaped by one in-repo consumer is exactly what the deferral record warned against. |
| **Ship the reflective roster installer** (off by default) | The jar would contain `setAccessible` into another library's private fields and tie its release cadence to SDK internals expected to move. Off-by-default does not change what ships. |
| **Wrap only the stateful sync type** (as the example does) | In a library, an adopter on `spring.ai.mcp.server.type=ASYNC` or the stateless protocol would get **ungated tools, silently**. If a type cannot be wrapped, the answer is a startup failure, never a skip. |
| **Explicit gate calls only** (the hand-port's style) | Puts every adopter one forgotten line from an ungated tool; it is the right shape only for specifications built by hand, which the direct API covers. |
| **Identity from `McpTransportContext` now** | Requires replacing Spring AI's transport beans for three transports and copying their property wiring — the same coupling to another project's internals rejected for the roster installer. |
| **Reuse `RoleDefinitionSupplier` (null id = type-level)** | Silently changes the meaning of every existing adopter's supplier, and collides with the per-resource supplier an MCP server may also have. |
| **A Java decision fallback when OPA is off** | Two sources of truth for the core property (`principal ∩ agent`), one of them outside `opa test`. |
| **`azp` as the actor whenever the claim is absent** | Turns every MCP client into an agent; any client without a profile is then denied everything. |
| **No input-schema version** | The failure it prevents is silent: a moved field reroutes agent calls onto the human branch and no test notices. |
| **Two modules (`opa-abac-agent` + `opa-abac-mcp`)** | A seam for a host-side consumer that does not exist; the incubating status permits the split later. |
| **Kernel types into `opa-abac-core`** | Puts agent concepts into the framework-agnostic core for one consumer. |

## Consequences

- **Good:** the agent tool-gate becomes a dependency instead of ~2,000 lines to copy; every way the gate
  could be present-but-inert in an adopter's deployment (an unwrapped specification type, a disabled
  starter, a missing ceiling source, a moved input field, an agent client without its actor mapper) is a
  startup failure or a deny. `opa-abac-core` and every existing module's API are unchanged.
- **Cost:** `opa-abac-mcp` is the first module whose compile classpath includes Spring AI and the MCP SDK,
  and the first tested on two Boot lines. Adopters who want `tools/list` filtering on SDK 2.0.x copy the
  example's installer. Async/stateless support rests on the per-type integration tests, not on a
  thread-independent identity carrier.
- **Follow-on:** a supported roster installer when java-sdk #578 ships; the transport-context identity
  carrier when Spring AI exposes an extractor hook; a stability promise (dropping "incubating") when an
  external consumer adopts the module.

## Related

- [[0028-agent-tool-call-authorization|ADR 0028]] (the model this packages) ·
  [[0020-user-directory-port|ADR 0020]] (the optional-module packaging pattern) ·
  [[0023-request-scoped-resolution-memoization|ADR 0023]] (the memo the ceiling supplier rides) ·
  [[0014-supplier-outage-error-distinct|ADR 0014]] (the tri-state the capability seam follows)
- [[MCP-AUTH-LIBRARY]] (the slice) · [[AGENT-TOOL-AUTHORIZATION]] (the guide) · [[POC-ROADMAP]]
