---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/spring
---

# STATUS — T4: The PEP: `@McpTool` interception, layer-naming advisory deny, kill-switch

**Status:** ✅ DONE

## What shipped

The gate that makes the design real: policy is asked **before** any tool body runs, and a denial
short-circuits.

| File | What |
|---|---|
| `authz/ToolCallAuthorizer` | the PEP core — builds the `AbacContext` of [[00-DESIGN]] §2.2 and asks OPA; six distinct internal deny codes |
| `authz/ToolAuthorizationDecision` | `(allowed, layer, code, message)`; one uniform caller-facing message across every internal cause |
| `authz/ToolCallGate` | wraps a `SyncToolSpecification`'s **call handler** — the interception point (see *Decisions* 1) |
| `authz/ToolAuthorizationConfiguration` | a `BeanPostProcessor` that gates **every** specification the scanner produced |
| `authz/ToolPolicyPathResolver` | binds resource type `tool` → the `agent_tools` document; refuses any other type |
| `authz/TypeLevelRoleDefinitionSupplier` | the principal's **type-level** ceiling (see *Decisions* 2) |
| `authz/ToolAuthorizationProperties` | `example.mcp.authz`: `agent-gate.enabled`, `policy-path`, `role-source.*` |
| `application.yml` | the authz block, with the OFF semantics written next to the switch |

## Tests

`./gradlew :example-mcp-server:test` — **99 passed, 0 failed** (up from 84).

| Case | Asserted |
|---|---|
| **I8** allow | body runs; **exactly one** OPA call; the OPA call **precedes** the catalog call — ordering asserted via a recorded call sequence, not inferred |
| **I9** tool-gate deny | **catalog stub request count == 0**; error names `tool-gate`; code `tool-gate-denied` |
| **I10** OPA 5xx, connection-refused | deny on each; catalog count 0 |
| **I11** malformed body, missing `allow` binding | deny on both; catalog count 0 |
| **I12** malformed identity claim | deny **before any OPA call** — OPA count 0, catalog count 0 |
| **I13** capability outage vs authoritative-empty | both deny; **distinct** codes; **identical** caller-facing content (asserted equal — no oracle) |
| **I14** catalog 403 | error names `target-gate`, upstream `errorCode` preserved; both stubs called once |
| **I15** kill-switch OFF | OPA **not asked**, and the catalog's 403 **still** denies as `target-gate` — OFF removes the narrowing, never the ceiling |
| ceiling outage | distinct code, OPA count 0 |
| unauthenticated / undeclared tool | deny, OPA count 0 |
| context shape | human call carries no `actor`/`chain`/`agent_capability`; agent call carries all three; nothing agent-related leaks into `resource`; `resource.type == "tool"`, declared category / risk_tags / target_type verbatim |
| **gate installation** | against the **real** context: every call handler in **every** specification list is a `ToolCallGate`, and the advertised set equals the declared set |

## Architecture review + refactor

**Fail-closed.** Every path through the PEP:

| Edge | Failure | Lands on |
|---|---|---|
| tool lookup | undeclared tool | deny (`tool-undeclared`) — no OPA call |
| authentication | no `AbacAuthentication` | deny (`tool-gate-unauthenticated`) — no OPA call |
| identity | `DelegationChainException` | deny (`tool-gate-identity-unreadable`) — **no OPA call spent on an unreadable identity** |
| capability | `AgentCapabilityUnavailableException` | deny (`tool-gate-capability-unavailable`) |
| ceiling | `RoleResolutionException` | deny (`tool-gate-ceiling-unavailable`) |
| OPA | down / timeout / malformed / no `allow` binding | deny — the shipped client's fail-closed contract, consumed as-is |
| policy | `allow == false` | deny (`tool-gate-denied`) |
| tool body | `ToolInvocationException` (incl. the catalog's 403) | advisory result naming `target-gate` |

**Kill-switch: OFF is not wider than ON.** `agent-gate.enabled=false` skips the tool-gate; the call is
then evaluated exactly as a human principal's would be, and the catalog service still enforces that
principal's ceiling because this server asserts nothing downstream. I15 proves it on the wire: with the
gate off, OPA is not called **and the catalog's 403 still denies**. There is **no** property that
disables call-time enforcement — the wrapping itself has no switch.

**Security — the widenings that would matter here.**
(1) **A gate that is not in the invocation path** — the big one, addressed in *Decisions* 1 and pinned by
`ToolGateInstallationTest` against the real context rather than by reading configuration.
(2) **A denial that still reaches the data path** — every deny case asserts `catalogCalls == 0`.
(3) **Spending work on an unreadable identity** — I12 asserts the OPA call count is 0, so a malformed
claim cannot be used to drive load through the PDP.
(4) **A denial that leaks which control fired** — internal codes differ, the caller-facing content is
asserted *equal* between a capability outage and a policy denial.
(5) **Anything asserted downstream** — the tool body still calls the catalog with the caller's bearer
only; the T1 header-set assertion still holds and nothing in T4 adds a header.
(6) **The path resolver silently routing elsewhere** — it throws on any non-`tool` type rather than
defaulting, and the client turns that into a deny.

**Concurrency / idempotency.** T4 gates no mutation — all four tools are reads and this module has no
persistence. The gate holds only immutable references; per-call state lives in the request. The
turn-scoped memo is the one shared structure and it is request-scoped (T3). A replayed identical call
re-asks policy and converges.

**Wiring.** Every seam has a consumer and a non-happy-path test: the authorizer (consumed by the gate),
the gate (installed on all four specs, asserted), the path resolver (consumed by the client; the
wrong-type branch tested), the role supplier (consumed by the authorizer; its outage path tested), the
properties (both switch states tested). The `bulk` rule from T3 remains T5's.

**Boundary / additivity.** `git diff --stat HEAD` outside `example-mcp-server/` is empty. No library
module, no existing service, no existing `.rego`.

**Module-layer separation.** `…mcp.authz` decides policy and resolves **no** target resource;
`…mcp.tool` executes and evaluates **no** policy. The gate is the only thing that knows about both, and
it knows about them only as a decision and a delegate.

**Pattern reuse.** The shipped `OpaClient` is consumed as-is, including its fail-closed contract and its
B3 resilience decoration — the discipline lands on the OPA edge, which is where B3 put it. The role
supplier follows B2's strict classification (only an explicit success is trusted; everything else
throws) and is decorated by the starter's resolve memo automatically.

**What the review found.** Two things, both fixed here. First, the interception point: an AOP aspect
would have been in the invocation path only if the annotation scanner happened not to unwrap the proxy —
see *Decisions* 1. Second, `ToolGateInstallationTest` initially assumed a single specification-list bean;
the context actually has two (`toolSpecs`, and an empty `syncTools`). The assertion was **strengthened**
rather than narrowed: no list may contain an ungated handler, and the union of all lists must equal the
declared surface — so a future release that introduces a third list fails this test instead of quietly
opening a hole.

**Static analysis.** 37 findings on the changed files, **0 real** and **no new rule classes** — T4 added
none. All in the documented by-design set (S5778×30, S5853, S125, S1075, S112, S4502, S5976, S2925).

## Integration / e2e

`./gradlew :example-mcp-server:test` green — the in-process OPA and catalog stubs are T4's integration
surface, and every case asserts request counts on **both** so "the body never ran" is demonstrated
rather than asserted. Live-rig proof is T6.

## Decisions

1. **The gate wraps the tool specification's `callHandler`, not an AOP aspect** — a deliberate deviation
   from the decomposition's `McpToolAuthorizationAspect`. The handler **is** what the MCP server invokes
   for a `tools/call`, so nothing can route around it. A Spring AOP aspect would only intercept calls
   made through the bean's proxy, and Spring AI's annotation scanner builds its callbacks from the bean
   it was handed — whether the gate sat in the path at all would depend on proxy-unwrapping behaviour we
   do not control and that could change in a minor upgrade. For a gate whose entire purpose is to be the
   one an untrusted host cannot skip, "probably in the path" is not good enough. Installed via a
   `BeanPostProcessor` over the scanner's list so no tool can be added without being gated.
2. **The principal's ceiling is type-level, unioned over governed targets** — the one design fork the
   planning docs did not cover, raised and settled with the maintainer mid-ticket. Since B4 authority is
   membership-scoped and the shipped resolve API requires a resource id, but the tool-gate deliberately
   resolves no target. `TypeLevelRoleDefinitionSupplier` therefore asks the shipped
   `/internal/governed-targets`, then the shipped batch `/internal/effective-roles`, and unions the
   grants while keeping only denials that apply to **every** governed target. It over-approximates
   authority on any particular resource, which is sound because the tool-gate is not the authority on
   resources — it can only let through a call the catalog service then decides properly with the caller's
   own bearer. Under-approximating would have been the dangerous direction: denying legitimate use is
   what tempts someone to switch the gate off. No user-service change.
3. **`policy-path` is `agent_tools`, the package** — not `agent_tools/allow` as the decomposition had it.
   The shipped client reads `result.<decision-field>` from `/v1/data/<path>` and posts a batch to
   `/v1/data/<path>/bulk`; the rule-qualified form would make T5's batch resolve a path that does not
   exist. (Recorded in T3, applied here.)
4. **A denial is an advisory `CallToolResult`, not a thrown exception.** `isError` plus structured
   `layer` and `code` metadata is the protocol's own way to tell a model something it can act on. An
   exception would surface as a transport fault, which a model can only retry.
5. **`ToolAuthorizationDecision.permitted()`, not `allowed()`** — `allowed` is the record's own accessor.
6. **Deferred: a `CallGuard` on the type-level resolve edge.** It is an authorization-input edge, so B3's
   discipline applies in principle; the demo runs a single unguarded attempt, matching the catalog
   service's documented test/demo constructor. The retry budget changes only *how many times we try
   before failing closed* — never the outcome — so the fail-closed contract is identical either way.

## Commit

`feat(mcp): enforce the tool-gate before any tool body runs (T4)`
