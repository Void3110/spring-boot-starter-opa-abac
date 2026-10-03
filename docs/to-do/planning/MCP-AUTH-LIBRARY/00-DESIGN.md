---
tags:
  - status/planned
  - type/design
  - area/abac
  - area/opa
  - area/spring
  - area/mcp
---

# MCP-AUTH-LIBRARY — 00-DESIGN

> Settled by `/grill-me` on 2026-10-03 (fifteen forks, each grounded in the code before it was asked —
> §Considered and rejected). The contract is [[0036-mcp-auth-library-module|ADR 0036]]; this note is the
> mechanism the tickets decompose. Index: [[MCP-AUTH-LIBRARY]].

## The gap in one paragraph

Agent tool-call authorization ([[0028-agent-tool-call-authorization|ADR 0028]], guide
[[AGENT-TOOL-AUTHORIZATION]]) exists only as `example-mcp-server`: an adopter who wants the tool-gate —
principal ceiling ∩ agent capability, computed in Rego, in front of the target service's unchanged
policies — copies ~2,000 lines out of an example. Worse, the example's wiring is only safe **for the
example's own configuration**: it wraps one of the MCP SDK's four tool-specification types, reads identity
from thread-bound state on the one transport it runs, and pairs with a policy that recognizes agent calls by
an unversioned key. Each of those is a silent fail-open the moment someone else's configuration differs.
This slice packages the gate as **`opa-abac-mcp`** and closes those edges.

## What this builds on, and what it must not break

- **Builds on:** the starter's `OpaClient` (`allow`, `allowAll`), `AbacContext` (its existing
  `environment` map carries the schema marker), the subject extraction (`opa.abac.subject.attribute-claims`),
  the request-scoped memo ([[0023-request-scoped-resolution-memoization|ADR 0023]]), and the optional-module
  packaging of `opa-abac-keycloak-directory` ([[0020-user-directory-port|ADR 0020]]).
- **Must not change:** `opa-abac-core`'s API; any existing module's public API; the per-type catalog
  policies; `infra/opa/policies/*` other than `agent_tools.rego` (+ its test); the catalog service. The
  two-layer model itself (composition, nothing asserted downstream) is ADR 0028's and is not reopened.
- **Must stay green, unchanged:** `run-agent-tool-matrix.sh` E1–E11 (the matrix's requests and assertions;
  only rig configuration may change, for the property rename) and every existing `example-mcp-server` test
  (tests move with their classes; none is deleted without a named replacement).
- **Related, out of scope:** the starter's `opa.abac.enabled=false` currently removes the
  `@OpaPreAuthorize` advisor and leaves annotated methods running ungated. That is a starter fix tracked
  separately; this module must simply not reproduce it (§3).

## The slice boundary

**In:** the module + BOM + publishing; the starter auto-configuration; moving the kernel out of the example;
the new `PrincipalCeilingSupplier` SPI; the YAML capability default; wrap-time declaration validation; the
four specification types; WebFlux detection; the `agent-clients` guard; input schema v1 (+ the policy and
its tests); the Boot 4.1 test step; the property rename (incl. rig references); the docs.

**Out:** a supported `tools/list` installer (waits for java-sdk #578); a thread-independent identity carrier
(waits for a Spring AI context-extractor hook); a PDP-optional / Java decision path; a host-side
`ToolCallingManager` gate; an `@ToolAuthorization` annotation; `ToolCallClassifier`; Spring AI 2.1;
releasing 1.4.0 (a separate step after merge); the starter's `opa.abac.enabled` fix.

## The design

### 1. Module and packaging (ADR 0036 §1–§2, §10)

- New Gradle module `opa-abac-mcp`, package `dev.dmitriikonovalov.opaabac.mcp`, depending on
  `opa-abac-core` + `opa-abac-spring-security`, Spring AI's MCP server annotations and the MCP SDK (versions
  from the Spring AI BOM; anything appearing in a public signature is `api`, the rest `implementation`).
- Added to `opa-abac-bom` and to the publishing allow-list (`RELEASING.md`). Incubating: `package-info`,
  module README, root README module table.
- The starter gets `compileOnly` on the module and on Spring AI, and an `OpaMcpAutoConfiguration` activated
  by `@ConditionalOnClass` on both — **not** gated on `opa.abac.enabled` (§3).
- Supported: MCP SDK 2.0.x + Spring AI 2.0.x on Boot 4.0.x and 4.1.x. The `build` CI job gains a step that
  re-runs `:opa-abac-mcp:test` and `:example-mcp-server:test` with Boot overridden to the newest 4.1.x — a
  step inside the existing job, so the `main` ruleset's five required checks are unchanged.

What moves (from `example-mcp-server`):

| Into the module | Stays in the example | Replaced |
|---|---|---|
| `identity/*` (delegation chain + extractor + wiring check, capability profile/supplier/YAML default, turn-scoped cache, properties) · `ToolCallAuthorizer` · `ToolCallGate` · `ToolRosterFilter` · `RosterDecision` · `ToolAuthorizationDecision` · `ToolPolicyPathResolver` · `ToolDescriptor` · `ToolRegistry` · `ToolErrorLayer` · `ToolInvocationException` · `ToolFailureRecord` · the gate configuration + properties (→ the starter's auto-configuration) | `CatalogTools`, `CatalogApiClient`, `CatalogApiErrorTranslator`, `CallerBearerSupplier`, `SecurityConfig`, `CatalogToolConfiguration`, `ToolRegistrationConfiguration` (the catalog's declarations), `TypeLevelRoleDefinitionSupplier` (→ implements `PrincipalCeilingSupplier`), `RosterFilterInstaller` + its kill-switch | `ToolRegistryValidator` (→ wrap-time validation, §6) · `ToolCallClassifier` (dropped from v1) |

### 2. The gate on every tool (ADR 0036 §3)

A `BeanPostProcessor` wraps the tool-specification lists Spring AI's auto-configuration produces, for **all
four** SDK types — `McpServerFeatures.{Sync,Async}ToolSpecification` and
`McpStatelessServerFeatures.{Sync,Async}ToolSpecification`. The async wrappers **decide when the handler is
invoked**, on the calling thread, then return either the denial or the delegate's `Mono` (the
decide-eagerly / apply-lazily split `ToolRosterFilter` already uses). A tool-specification bean of any other
type ⇒ **startup failure**. The direct API (`ToolCallAuthorizer`) is public for hand-built specifications.

### 3. Never present-but-inert (ADR 0036 §3)

| Condition at startup | Outcome |
|---|---|
| module + Spring AI on the classpath, no `OpaClient` bean (incl. `opa.abac.enabled=false`) | **fails**, naming `opa.abac.enabled` and the missing bean |
| no `PrincipalCeilingSupplier` bean | **fails** (there is no default) |
| a tool-specification type the module cannot wrap | **fails** |
| a reactive (WebFlux) web application | **fails** — out of scope for v1 |
| declared tools ≠ wrapped tools (either direction) | **fails** (§6) |
| actor claim (or `azp`, when `agent-clients` is non-empty) not in `opa.abac.subject.attribute-claims` | **fails** (the existing wiring check, extended to `azp`) |

There is no property that turns the gate off. `opa.abac.mcp.agent-gate.enabled=false` drops the agent
conjunct only (the call is the principal's, still ceiling-bounded).

### 4. Identity (ADR 0036 §4)

Principal from `SecurityContextHolder`; turn-scoped capability memo and failure records from the request
attributes — as today. **Proof obligation:** one integration test per specification type on Spring MVC
showing the identity is visible where the gate decides. A type that fails it is not shipped as supported —
it falls into §3's "cannot wrap" row. ADR 0036 records the `McpTransportContext` carrier as the successor
(open an upstream Spring AI issue asking for a context-extractor hook on the transport auto-configuration).

### 5. The SPIs (ADR 0036 §6)

- **`PrincipalCeilingSupplier`** — `Optional<RoleDefinition> lookup(String subject, String resourceType)`.
  Javadoc carries the full contract: union of grants, denial only if universal, over-approximation allowed /
  under-approximation forbidden, empty = authoritative no-role, failure throws. Plus the rig finding as an
  implementer's note: roles recorded on a governing root must be found when the requested type is a
  descendant (`TypeLevelRoleDefinitionSupplier.scopeTypesFor`). The request-scoped memo decorates it like
  the role-definition supplier.
- **`AgentCapabilitySupplier`** — unchanged tri-state; YAML default `ConfigAgentCapabilitySupplier` reading
  `opa.abac.mcp.agents.<id>`, `@ConditionalOnMissingBean`. The library's tests drive the gate with a stub
  supplier that **throws**, proving an outage denies and is never read as empty (what the waived I6 case
  was for).
- **`DelegationChainExtractor`** — moves unchanged with `ClaimDelegationChainExtractor`.

### 6. Declarations (ADR 0036 §9)

`ToolRegistry` / `ToolDescriptor` move unchanged. The wrapping step collects the tool names it wrapped and
compares them with the registry in both directions; a mismatch fails startup with both lists in the message.
At call time the gate still denies a tool missing from the registry (`tool-undeclared`) — the backstop for
anything registered after startup.

### 7. The agent-clients guard (ADR 0036 §8)

`opa.abac.mcp.identity.agent-clients` (list, default empty). In the delegation step: if `azp` is in the list
and no valid actor claim is present ⇒ deny with a new code, **`tool-gate-actor-required`** (layer
`tool-gate`), logged distinctly. Empty list ⇒ byte-identical to today. `azp` reaches the subject through
`opa.abac.subject.attribute-claims` (§3 checks it).

### 8. Input schema v1 (ADR 0036 §7)

The gate sets `environment.schema = "opa-abac.mcp.tool-gate/v1"` on every tool-gate context (single
decisions and the roster's batch). `agent_tools.rego` gains a top-level guard: no `allow` unless
`input.environment.schema` is in its known set. New `opa test` cases: unknown version ⇒ deny (human and
agent); missing marker ⇒ deny; v1 ⇒ existing cases unchanged. The v1 field list (subject id/roles, the
`actor` / `chain` / `agent_capability` attributes, `role_definition`, `resource.type` + the declared tool
attributes, `environment.schema`) is published in [[AGENT-TOOL-AUTHORIZATION]]. The actor stays at
`input.subject.attributes.actor` in v1.

### 9. Conventions (ADR 0036 §11)

Properties `opa.abac.mcp.{agent-gate.enabled, policy-path, identity.*, agents.<id>}`; the example's
`example.mcp.{authz,identity,agents}` keys move (≈20 references across `deploy.sh`, compose, `infra/`,
`scripts/`), except `example.mcp.authz.roster-filter.enabled`. Denial = `CallToolResult{isError}` with
structured `{layer, code}`; the codes (`tool-gate-denied`, `tool-undeclared`, `tool-gate-unauthenticated`,
`tool-gate-identity-unreadable`, `tool-gate-capability-unavailable`, `tool-gate-ceiling-unavailable`,
`tool-gate-actor-required`, and the `target-gate` layer) are documented in the guide.

## Fail-closed posture — every new edge

| Edge | Lands on | Pinned by |
|---|---|---|
| unwrappable specification type / WebFlux / no `OpaClient` / no ceiling supplier / declarations ≠ wrapped | startup failure | auto-configuration tests (`ApplicationContextRunner`) |
| identity not visible where an async/stateless gate decides | deny (`tool-gate-identity-unreadable`); type unsupported ⇒ startup failure | the per-type ITs |
| ceiling supplier throws / returns empty | deny (`…-ceiling-unavailable` / policy default) | unit + `opa test` |
| capability supplier throws | deny (`…-capability-unavailable`), never empty | unit (throwing stub) |
| unconfigured agent (YAML default) | empty profile ⇒ every tool denied | unit + `opa test` |
| listed agent client without an actor claim | deny (`tool-gate-actor-required`) | unit + e2e cell |
| schema marker unknown or missing | deny | `opa test` |
| tool not in the registry at call time | deny (`tool-undeclared`) | unit |
| roster (unchanged): batch all-false ⇒ empty; edges outside the batch ⇒ unfiltered + WARN; wrong-length ⇒ empty | as today | existing tests, moved |

## Validation: what proves it

- **Part 0 bar (behavior-preserving):** E1–E11 green with no collection change; every moved test green in
  its new module; `opa test` 451/451 unchanged; the only new behavior is startup failures for
  misconfigurations, each with a context-runner test.
- **Part 1:** the four per-type ITs; the WebFlux startup test; the guard's unit cases + one e2e cell (an
  agent-client token without the actor mapper ⇒ `tool-gate-actor-required`); the schema `opa test` cases and
  E1–E11 still green with the marker in place (proving jar ↔ policy pairing); the Boot 4.1 step green.
- Sonar gate clean on changed `.java`; `check-shell-guards.py` and `check-collection-conformance.py` clean
  on any touched runner/collection.

## Execution parts

Two execution parts; the exact declaration line is written at decomposition, once the ticket count exists.
**Part 0 — the behavior-preserving extraction** (module, packaging, auto-configuration, the moves, the
ceiling SPI + the example's implementation, the YAML default, wrap-time validation, the startup checks,
the property rename), held to the Part 0 bar above. **Part 1 — the new behavior and the docs** (four
specification types + their ITs, WebFlux detection, the `agent-clients` guard, schema v1 + policy + tests,
the Boot 4.1 step, ADR/guide/READMEs/roadmap). Separating "moved" from "changed" is the point: a
regression introduced while extracting would otherwise hide among intended changes.

## For the decomposer to measure (not design forks)

1. Whether each async / stateless handler can be wrapped and sees the identity on Spring MVC — the answer
   decides which per-type IT is "supported" vs "startup failure", never whether to skip.
2. How WebFlux is detected (e.g. a failing bean under `@ConditionalOnWebApplication(type = REACTIVE)`).
3. How the Boot 4.1.x override is expressed in Gradle (the dependency-management BOM import is
   version-catalog-driven today).
4. How the ADR 0023 memo auto-configuration discovers and decorates a `PrincipalCeilingSupplier`.
5. The module's published POM scopes for Spring AI / the MCP SDK (`api` vs `implementation`).
6. The full list of `example.mcp.*` references outside `example-mcp-server` (≈20 found at design time).

## Living docs touched in-branch

[[AGENT-TOOL-AUTHORIZATION]] (packaging, properties, schema v1, support statement, how to install the roster
filter on SDK 2.0.x, the codes) · root `README.md` (module table, incubating) · the new module README ·
`example-mcp-server` docs · [[POC-ROADMAP]] (Phase 9's exit criterion delivered) · `RELEASING.md`
(allow-list) · `CLAUDE.md` (the MCP-server section: "no library module changes" no longer true) · the ADR
index.

## Considered and rejected (the grill, 2026-10-03)

| # | Fork | Chosen | Rejected (why) |
|---|---|---|---|
| 1 | Override the second-consumer rule? | extract, **incubating** | stable 1.x (one example shaping the API); keep deferring (both waited-for events resolved) |
| 2 | Roster in the library? | the **decision** only | ship the reflective installer (private-field reflection in a published jar); roster example-only (leaves the best-tested logic as copy-paste) |
| 3 | Gate installation | **wrap all four types**, never inert, + direct API | explicit calls only (one forgotten line = ungated tool); sync-only (silent bypass on async/stateless) |
| 4 | Identity source | **thread-bound now**, transport context later | take over Spring AI's transport beans (internal coupling) |
| 5 | Layout | **one module `opa-abac-mcp`**, starter auto-config | split agent/mcp modules (no host-side consumer); types into core |
| 6 | Supported versions | **Boot 4.0.x + 4.1.x, both tested** | repo-wide 4.1 bump first (extra slice, moves every baseline); 4.0 only (wrong for by-the-book Spring AI users) |
| 7 | Ceiling source | **dedicated `PrincipalCeilingSupplier`**, no default | null-id `RoleDefinitionSupplier` (reinterprets adopters' suppliers); generic impl (assumes the demo's model) |
| 8 | Capability source | SPI + **YAML default** | SPI only (human-only servers need a bean to boot); + HTTP registry impl (no consumer) |
| 9 | Absent actor claim | human, + **opt-in `agent-clients` guard** | `azp` as implicit actor (every client becomes an agent); document only |
| 10 | Undeclared tools | registry, **validated at wrap time** + call-time deny | `@ToolAuthorization` annotation now (more API, Spring AI discovery coupling) |
| 11 | Policy ↔ jar pairing | **schema v1 marker**, policy refuses unknown | document only (silent misread); startup PDP probe (couples boot to OPA reachability) |
| 12 | Conventions | as §9 | — |
| 13 | Slice shape | **one slice, two parts**, autonomous (ORCHESTRATOR) | two slices (two first reviews, later Part 3); one part (~10 tickets in one head) |
| 14 | OPA optional? | **required** | decider SPI / Java fallback (second source of truth outside `opa test`) |
| 15 | Schedule | design now → decompose after the 2026-10-08 usage-pool reset → run → review → release | decompose before the reset (risk of running out mid-validation) |
