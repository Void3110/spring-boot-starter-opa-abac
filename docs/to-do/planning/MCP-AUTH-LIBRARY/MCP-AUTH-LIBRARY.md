---
tags:
  - status/planned
  - type/index
  - area/abac
  - area/opa
  - area/spring
  - area/mcp
---

# MCP-AUTH-LIBRARY — the agent tool-gate as a library module (`opa-abac-mcp`)

> **Status: 📋 decomposed 2026-10-09 — eight tickets, two parts; not yet implemented.** Designed 2026-10-03. Grill-me settled fifteen forks the same day
> ([[00-DESIGN]] §Considered and rejected); the contract is
> [[0036-mcp-auth-library-module|ADR 0036]], which **supersedes** the 2026-08-01 decision to defer this
> extraction to a second consumer and **amends** [[0028-agent-tool-call-authorization|ADR 0028]]'s
> packaging line (`opa-abac-agent` → `opa-abac-mcp`).
> **Amended 2026-10-09** (before decomposition) for ADRs 0037 and 0038, which landed in between: the
> "could not decide" family and `tool-gate-policy-unavailable`; ADR 0038's `allow-ungated-methods` does not
> extend to MCP tools ([[0036-mcp-auth-library-module|ADR 0036]] §12, [[00-DESIGN]] fork 16).
> **Execution:** autonomous, two parts (ORCHESTRATOR) — part 0 = T1–T4, the behavior-preserving extraction;
> part 1 = T5–T8, the new behavior (declared in [[00-DESIGN]] §Execution parts). The decomposition pinned seven
> seams the design named but did not specify ([[0036-mcp-auth-library-module|ADR 0036]] §13).
> **Branch:** `feature/void3110/mcp-auth-library`.
> **Review routing (the repo's standing rule):** one multi-lens `/deep-review` workflow pass when both
> parts are in; any follow-up round on the same branch is a Fable + Opus pair. State the remaining usage
> pool and the expected cost before the decompose validation, the run and the review.
> **Next:** the decomposition gates (6a mechanical, 6b adversarial) → `/autonomous-implement MCP-AUTH-LIBRARY`
> on the maintainer's go → one multi-lens `/deep-review` → merge → release 1.5.0.

## Why this slice exists

Phase 9 ([[AGENT-TOOL-AUTHZ]]) proved the two-layer model — principal ceiling ∩ agent capability, computed
in Rego, in front of unchanged per-resource policies — inside `example-mcp-server`, and named a reusable
module as its exit criterion. The extraction was deferred until a second consumer had shaped the seams.
That hand-port has since happened elsewhere (it kept the call-time gate and the fail-closed table, changed
the role source, and dropped the roster filter), and the SDK seam the roster adapter waits for (java-sdk
#578) slipped to an open-ended 2.2 — so waiting no longer buys information ([[0036-mcp-auth-library-module|ADR 0036]] §Context).

Packaging it is also where the example's configuration-specific safety stops being enough: a library runs
under configurations the example never did. The slice's real content is the set of edges that are safe in
the example and fail-open elsewhere — an async or stateless tool-specification type the wrapper never
sees, a disabled starter, a missing ceiling source, a policy older than the jar reading a moved input
field, an agent client whose IdP mapper is missing — each turned into a startup failure or a deny.

## The fifteen settled forks (one line each — detail in [[00-DESIGN]])

1. Extract now, **incubating** (API may change in minors until an external consumer adopts it).
2. The roster **decision** ships; the reflective `tools/list` installer stays in the example until #578.
3. The gate wraps **all four** SDK tool-specification types; any it cannot wrap fails startup; never inert;
   a direct API for hand-built specifications.
4. Identity stays **thread-bound**, decided on invocation; per-type ITs; WebFlux out of v1; the transport
   context is the planned successor.
5. **One module, `opa-abac-mcp`**, auto-configured by the starter (ADR 0020 pattern); core untouched.
6. Supported: MCP SDK 2.0.x + Spring AI 2.0.x on Boot **4.0.x and 4.1.x**, both tested in CI.
7. A dedicated **`PrincipalCeilingSupplier`** SPI with the union/over-approximation contract; no default.
8. `AgentCapabilitySupplier` + a **YAML default**.
9. Absent actor = human, plus an opt-in **`agent-clients`** guard (a listed client without an actor ⇒ deny).
10. Declarations validated **at wrap time** (both directions) + a call-time deny.
11. Input **schema v1** at `input.environment.schema`; the policy refuses an unknown version; the actor stays
    at `subject.attributes.actor` in v1.
12. Conventions: `opa.abac.mcp.*`, package `…opaabac.mcp`, documented denial codes, `ToolCallClassifier` out.
13. One slice, two parts, autonomous.
14. **OPA required** — no Java decision path.
15. Schedule: decompose after the usage-pool reset; release 1.5.0 after merge (1.4.0 carries the engine-error slice).
16. *(amendment)* ADR 0038's acknowledgment does **not** let an MCP server boot without OPA.

## Files

| File | State |
|---|---|
| [[00-DESIGN]] | ✅ written 2026-10-03 |
| [[0036-mcp-auth-library-module|ADR 0036]] | ✅ Accepted (planned) |
| [[01-DECOMPOSITION]] | ✅ written 2026-10-09 — T1–T8, critical path, slice-wide invariants |
| [[10-QA-TEST-CASES]] | ✅ U1–U34 · I1–I6 · E1–E3 |
| [[AUTONOMOUS-IMPLEMENTATION-PROMPT]] | ✅ the flow guide's §4 template, slots filled |
| `STATUS-01.md` … `STATUS-08.md` | stubs, filled at each checkpoint |

## Tickets

| # | Ticket | Part | Status |
|---|---|---|---|
| T1 | The `opa-abac-mcp` module; the identity kernel + tool declarations move | 0 | ☐ |
| T2 | The decision moves, onto `PrincipalCeilingSupplier` | 0 | ☐ |
| T3 | `OpaMcpAutoConfiguration` + the prerequisite startup failures | 0 | ☐ |
| T4 | Wrap-time declaration check; part 0 closes on the rig (matrix E1–E11 unchanged) | 0 | ☐ |
| T5 | All four specification types; reactive / unwrappable ⇒ startup failure; per-type ITs | 1 | ☐ |
| T6 | The `agent-clients` guard (`tool-gate-actor-required`) + matrix cell E12 | 1 | ☐ |
| T7 | Input schema v1 — jar marker + policy guard, one commit | 1 | ☐ |
| T8 | Boot 4.1.x tested in CI; slice close-out | 1 | ☐ |

**Critical path:** strictly T1 → T8 (see [[01-DECOMPOSITION]]); T1–T4 is the independently landable subset.

## Related

[[AGENT-TOOL-AUTHORIZATION]] · [[AGENT-TOOL-AUTHZ]] · [[0028-agent-tool-call-authorization|ADR 0028]] ·
[[0020-user-directory-port|ADR 0020]] · [[POC-ROADMAP]] · [[AUTONOMOUS-IMPLEMENTATION-FLOW]]
