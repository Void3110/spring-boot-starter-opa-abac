---
tags:
  - status/planned
  - type/index
  - area/abac
  - area/opa
  - area/rego
  - area/spring
  - area/catalog-service
---

# AGENT-TOOL-AUTHZ — AI-agent tool-call authorization on the shipped starter (Phase 9)

> **Status: Planning.** A new example service — **`example-mcp-server`**, a Spring AI MCP server
> exposing catalog tools — gains a **server-side `@McpTool` policy gate** with OPA as the PDP, so an
> agent's tool call is authorized *before* it executes. It proves the emerging agent-authorization
> pattern **on the published starter with zero library change**: no `opa-abac-*` module, no existing
> service, and no existing `.rego` document is touched. Phase 9 of [[POC-ROADMAP]].

## Why this slice exists

**The gap.** The starter answers *"may this **human** act on this resource?"* An AI agent calling
tools on a user's behalf collapses two identities into one bearer token: the policies see only the
human's `sub`, so an agent inherits the **whole** human ceiling with nothing to narrow it, and the
tool surface itself — which tools exist, which may be invoked at all — has no gate whatsoever. No
Spring-native worked example of agent tool-call authorization exists, the same absence this repo
filled for classic ABAC.

**The mechanism.** **Two-layer enforcement, no propagation.** A new **tool-gate** rego document
(explicit `default allow := false`) decides `(subject, tool, tool-category, risk-tags)` and is the
*only* place agent narrowing lives: effective authority = **principal ceiling ∩ agent capability**,
computed **in Rego** so it is auditable, with **no standing agent grants**. Identity is normalized by
a `DelegationChainExtractor` seam reading **RFC 8693 `act` semantics** from a plain custom claim
minted by stock Keycloak — principal (the human, token `sub`) + actor (the agent) + an ordered
delegation chain; a token with **no** actor claim is an ordinary human call. Capability comes from an
`AgentCapabilitySupplier` seam on the repo's tri-state doctrine (resolved / authoritative-empty /
outage-throws → deny) with a **turn-scoped** memo. Tools then execute against the **existing catalog
REST API with the caller's own token**, so the catalog service independently enforces the principal
ceiling with its per-type policies **literally unchanged** — the MCP server never asserts a role
downstream (that caller-supplied-role shape is exactly what [[MULTI-TENANT-ISOLATION]] removed). The
intersection therefore holds **across** the layers, provably, rather than being passed between them.

**The headline.** An agent sees only the tools it may call — `tools/list` filtered by a batch
`allowAll` pre-flight, a hint and never a grant, because **call-time enforcement always runs and is
authoritative** — and a denial is a **structured advisory tool-error naming the denying layer**
(tool-gate vs target-gate) so the model can react instead of hallucinating around a stack trace.
Every new edge — chain extraction, capability lookup, tool-gate call, roster batch — lands on **deny
or the smaller result**, and each kill-switch's OFF state is never wider than its ON state.

## Files in this folder

| File | What it is |
|---|---|
| [[00-DESIGN]] | The mechanism, the pinned forks (scope fence, PEP leg, identity, agent model, input schema, packaging), the fail-closed posture, considered-&-rejected. |
| [[01-DECOMPOSITION]] | The ordered work list T1…T6 + the critical path. |
| [[10-QA-TEST-CASES]] | Concrete U*/I*/E* cases → each ticket's Acceptance. |
| AUTONOMOUS-IMPLEMENTATION-PROMPT | The self-contained prompt the run executes. |
| STATUS-01 … STATUS-06 | One stub per ticket, filled at each checkpoint. |

## Ticket status at a glance

| # | Title | Status |
|---|---|---|
| T1 | Walking skeleton: `example-mcp-server` + declared `@McpTool` catalog proxies (no authz) | ✅ DONE |
| T2 | Dual identity: `DelegationChainExtractor` (RFC 8693 `act` semantics, fail-closed) | 📋 TODO |
| T3 | Tool-gate rego (ceiling ∩ capability, in Rego) + `AgentCapabilitySupplier` | 📋 TODO |
| T4 | The PEP: `@McpTool` interception, layer-naming advisory deny, kill-switch | 📋 TODO |
| T5 | Roster filtering: `tools/list` via batch `allowAll`, omit-never-fabricate | 📋 TODO |
| T6 | Rig + scripted-client e2e + guide + folder move | 📋 TODO |

## Critical path

```
T1 ──► T2 ──► T3 ──► T4 ──► T5 ──► T6
```

Strictly sequential. **T1 is independently landable** — a runnable MCP server proxying the catalog
REST API with the caller's token, every tool declaring its action/category/risk-tags, no
authorization yet — and carries standalone demo value on its own. **T1–T4 are the reusable core**
(identity + policy + PEP) if the window is short: T5 is roster polish and T6 is the rig/e2e/docs
wrapper.

## Conventions

- **Clean-room, public repo.** No employer, internal system, or proprietary name in any committed
  file; the demo domain stays product catalogs. Packages: `dev.dmitriikonovalov.example.mcp.*`.
- **Commit identity** `Void3110 <void31102025@gmail.com>` (repo-local — verify before committing).
- **Baselines:** Spring AI 2.0.0, MCP Java SDK 2.0.0, MCP spec revision 2025-11-25; Java 25 /
  Spring Boot 4.0 / Gradle 9.x as the rest of the repo.
- **Tests:** in-process `com.sun.net.httpserver.HttpServer` stubs (never WireMock), real Postgres via
  Testcontainers (never H2) where persistence is involved, `opa test` for policy, and an e2e driven by
  a **deterministic scripted MCP client** asserting the actual cut — which tools, which denials.
- **Example-first packaging.** Everything lands in `example-mcp-server`; extracting an optional
  `opa-abac-agent` module is the slice's **exit criterion**, deliberately not a ticket here.
- **Out of scope, stated so it stays out:** MCP transport/OAuth authz (delegated to the ecosystem's
  MCP-security layer), the host-side `ToolCallingManager` leg, HITL/approval flows, policy-filtered
  retrieval, `listChanged` notifications, a real LLM in the loop, and any change to catalog-service,
  user-service, the existing rego, or any `opa-abac-*` module.

## Related

- [[POC-ROADMAP]] — Phase 9 (agent tool-call authorization); the first, smallest-honest-demo slice of it.
- [[0028-agent-tool-call-authorization|ADR 0028]] — the two-layer decision model, enforcement-by-composition
  (no role propagation), the additive dual-identity subject shape, example-first packaging.
- [[0019-pluggable-cross-service-ownership|ADR 0019]] — the SPI shape the two new seams mirror.
- [[0024-batch-role-resolution|ADR 0024]] — the batch `allowAll` primitive the roster pre-flight reuses.
- [[0006-three-layer-enforcement-model|ADR 0006]] — the pre-existing layered enforcement model this slice extends upward to tools.
- [[MULTI-TENANT-ISOLATION]] — where asserting a caller-supplied role was removed as fail-open.
