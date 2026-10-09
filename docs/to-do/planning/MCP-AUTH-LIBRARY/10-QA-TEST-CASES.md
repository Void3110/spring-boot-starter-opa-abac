---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/opa
  - area/spring
  - area/mcp
---

# MCP-AUTH-LIBRARY — QA test cases

> Concrete cases; each becomes a ticket's *Acceptance*. U = unit (incl. `ApplicationContextRunner` slice tests
> and `opa test`), I = integration (a real application context; the per-type ones over a **real** HTTP
> `POST /mcp`, never MockMvc), E = e2e on the local rig, through the gateway. OPA is stubbed with an in-process
> `com.sun.net.httpserver.HttpServer` (no WireMock). E-cases assert the actual outcome (the advisory's
> `code`, the tool list's exact names), and every `pm.test` callback throws — a returned boolean is the
> vacuous-cell defect.
>
> **Naming:** the agent-tool matrix (`scripts/postman/run-agent-tool-matrix.sh`) labels its own cells E1–E11
> (E12 is added by T6). Those are *matrix cell* names; the QA ids below (E1–E3) are this package's.
>
> **The part-0 rule for moved tests:** a test that moves with its class keeps its assertions; the diff of a
> moved test shows only package/import lines, property-prefix strings and the ceiling-stub type (T2). Any
> other change to a moved assertion is a behavior change and needs a STATUS *Decisions* entry.

## Unit (U*)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| U1 | The identity + declaration tests run in the module | `ClaimDelegationChainExtractorTest`, `DelegationChainBoundaryTest`, `AgentCapabilitySupplierTest`, `AgentCapabilityWireContractTest`, `TurnScopedCapabilityCacheTest`, `ToolDescriptorTest`, `ToolRegistryTest` green under `:opa-abac-mcp:test`, bodies unchanged per the part-0 rule; none remains in `example-mcp-server` | T1 |
| U2 | `ActorClaimWiringCheck` without the starter's properties | blank `actor-claim` ⇒ `IllegalStateException`; claim not in the copied list ⇒ fails, message names `opa.abac.mcp.identity.actor-claim` **and** `opa.abac.subject.attribute-claims`; claim present ⇒ passes. The class imports nothing from `…opaabac.autoconfigure` | T1 |
| U3 | The capability outage joins the family, codes unchanged | `new AgentCapabilityUnavailableException(..)` is a `DecisionIndeterminateException`; `TurnScopedCapabilityCacheTest`'s outage cases still replay the outage (not an empty profile) | T1 |
| U4 | The renamed prefixes bind, the old ones do not | a `Binder` over `opa.abac.mcp.identity.actor-claim=x`, `opa.abac.mcp.agents.profiles[0].actor-id=a` populates `IdentityProperties` / `AgentCapabilityProperties`; the same values under `example.mcp.identity` / `example.mcp.agents` leave the defaults (`act_chain`, no profiles) | T1 |
| U5 | The moved gate tests, on the new SPI | every `ToolCallGateTest` case green with unchanged expected codes; ceiling stub ⇒ `PrincipalCeilingSupplier`; a ceiling lookup throwing `RoleResolutionException` **and** one throwing a test-local `DecisionIndeterminateException` subtype ⇒ both `tool-gate-ceiling-unavailable`; a capability outage ⇒ `tool-gate-capability-unavailable`; an engine failure (`PolicyEngineException`) ⇒ `tool-gate-policy-unavailable` | T2 |
| U6 | The moved roster tests | every `ToolRosterFilterTest` case green: engine could not decide ⇒ empty; all-false ⇒ empty; identity / capability / ceiling edge ⇒ unfiltered; wrong-length vector ⇒ empty; `agent-gate` off ⇒ ceiling-only contexts | T2 |
| U7 | `MemoizingPrincipalCeilingSupplier` | inside a bound request: two lookups of one `(subject, type)` ⇒ one delegate call; an empty result memoized; a family outage memoized and **re-thrown** on the repeat without a second delegate call; a non-family `RuntimeException` propagates and is **not** memoized (the next lookup calls the delegate); no request context ⇒ every lookup reaches the delegate | T2 |
| U8 | The example's ceiling source, renamed | `TypeLevelPrincipalCeilingSupplierTest` (was `TypeLevelRoleDefinitionSupplierTest`) green with unchanged assertions; the class implements `PrincipalCeilingSupplier` and **not** `RoleDefinitionSupplier` | T2 |
| U9 | Auto-configuration activation | module + Spring AI on the classpath ⇒ `ToolCallAuthorizer`, `ToolRosterFilter`, `DelegationChainExtractor`, `AgentCapabilitySupplier` (a `TurnScopedCapabilityCache` over `ConfigAgentCapabilitySupplier`), `ActorClaimWiringCheck` present; Spring AI hidden (`FilteredClassLoader`) ⇒ none of them; the module hidden ⇒ none of them | T3 |
| U10 | No `OpaClient` ⇒ startup failure | with a ceiling supplier + registry but no `OpaClient`: the context fails, the root cause's message contains `opa.abac.enabled` and `OpaClient`; the same with `opa.abac.enabled=false` **and** `opa.abac.allow-ungated-methods=true` (fork 16) | T3 |
| U11 | No `PrincipalCeilingSupplier` ⇒ startup failure | with an `OpaClient` but no ceiling supplier: fails, the message names `PrincipalCeilingSupplier` | T3 |
| U12 | Adopter beans win | an adopter `AgentCapabilitySupplier` ⇒ no `ConfigAgentCapabilitySupplier` bean, and the adopter's bean is still wrapped by `TurnScopedCapabilityCache`; an adopter `DelegationChainExtractor` ⇒ the default backs off | T3 |
| U13 | The policy-path decorator | with the starter's `PerTypePolicyPathResolver`: a `tool` context resolves to `agent_tools` (and to `x/y` with `opa.abac.mcp.policy-path=x/y`); a `catalog` context resolves to exactly what the undecorated resolver returns; an OPA `HttpServer` stub behind the context's `OpaClient` records `/v1/data/agent_tools` for a tool decision and `/v1/data/agent_tools/bulk` for a roster batch | T3 |
| U14 | The ceiling memo is wired | the context's `PrincipalCeilingSupplier` is a `MemoizingPrincipalCeilingSupplier` by default; with `opa.abac.resolve-memo.enabled=false` it is the raw bean | T3 |
| U15 | The sync gate covers every list and every element | two `List<McpServerFeatures.SyncToolSpecification>` beans (as `toolSpecs` + `syncTools` are in the example) ⇒ every element's handler is a `ToolCallGate`, including an element at index > 0; a `List<String>` bean and a `List` of resource specifications are returned untouched | T3 |
| U16 | The wiring check reads the environment | `opa.abac.mcp.identity.actor-claim=act_chain` with `opa.abac.subject.attribute-claims` lacking it ⇒ startup fails naming both, asserted in a context where the starter's `OpaAbacProperties` bean is replaced by a mock that is never called | T3 |
| U17 | Wrap-time declarations check | wrapped ⊋ registry ⇒ fails, message lists the undeclared names; registry ⊋ wrapped ⇒ fails, message lists the never-wrapped names; names spread over two lists that together equal the registry ⇒ starts; no `ToolRegistry` bean ⇒ fails naming it; empty registry + no tools ⇒ starts | T4 |
| U18 | The call-time backstop | the moved `ToolCallGateTest` case for an undeclared tool name ⇒ `tool-undeclared`, still green | T4 |
| U19 | The removed types are gone | `git grep -n 'ToolCallClassifier\|ToolRegistryValidator' -- '*.java'` returns nothing; `McpToolSurfaceTest`'s remaining cases green | T4 |
| U20 | Async stateful wrapper | deny ⇒ the returned `Mono` emits the advisory `{layer: tool-gate, code}` and the delegate `BiFunction` is **never invoked**; allow ⇒ the delegate's `Mono` is returned; the authorizer is called **once, at `apply`**, never again at subscription (a counting stub, subscribed twice); a delegate throwing `ToolInvocationException` synchronously ⇒ the advisory with its layer + code | T5 |
| U21 | Stateless sync wrapper | the U20 deny/allow/`ToolInvocationException` cases with an `McpTransportContext` first argument | T5 |
| U22 | Stateless async wrapper | the U20 cases for `McpStatelessServerFeatures.AsyncToolSpecification` | T5 |
| U23 | Unwrappable specification ⇒ startup failure | a `List` bean of a test-local record exposing `McpSchema.Tool tool()` ⇒ fails naming the record type; a list holding one known and one unknown element ⇒ fails; a list of elements without such an accessor ⇒ untouched | T5 |
| U24 | WebFlux ⇒ startup failure | a `ReactiveWebApplicationContextRunner` with module + Spring AI ⇒ fails, the message names WebFlux / reactive and "not supported in v1"; the servlet runner with the same configuration starts | T5 |
| U25 | Non-web ⇒ deny-all | a plain (non-web) context starts; a wrapped handler of each of the four types invoked with an empty `SecurityContextHolder` ⇒ `tool-gate-unauthenticated`, delegate never invoked | T5 |
| U26 | `agent-clients` empty ⇒ unchanged | for a no-actor caller with `azp` present, the built `AbacContext` equals the one built before T6 (same subject attributes, no new key) and the decision path is the human one | T6 |
| U27 | The guard | `azp` listed + no actor ⇒ `tool-gate-actor-required` (layer `tool-gate`) and a WARN naming the client; `azp` listed + a valid actor ⇒ the agent path; `azp` not listed + no actor ⇒ the human path; `azp` listed + a **malformed** actor ⇒ `tool-gate-identity-unreadable` (the extractor's deny wins) | T6 |
| U28 | The wiring check covers `azp` | `agent-clients=[c]` without `azp` in `opa.abac.subject.attribute-claims` ⇒ startup fails naming `azp`; with it ⇒ starts; `agent-clients` empty ⇒ `azp` not required; a binder test shows an empty-string environment value binds `agent-clients` to an empty list | T6 |
| U29 | The roster under the guard | a listed client without an actor at list time ⇒ `RosterDecision.unfiltered()` + a WARN, while every call for that caller denies `tool-gate-actor-required` | T6 |
| U30 | Every tool-gate input is marked | the context from `authorize` and **every** item of a roster batch (an `HttpServer` stub recording the bulk body) carry `environment.schema = "opa-abac.mcp.tool-gate/v1"` | T7 |
| U31 | The policy refuses an unknown or missing version (`opa test`) | unknown version ⇒ deny for a human and for an agent; marker missing ⇒ deny for both; `environment` absent ⇒ deny; a bulk with one unmarked item ⇒ that position `false`, the others unchanged; all pre-existing cases green with the marker added to their fixtures (the helper **and** the two hand-built inputs) | T7 |
| U32 | The guard is load-bearing | temporarily delete the `known_schema` requirement ⇒ at least one named U31 test fails (record its name in STATUS-07), then restore | T7 |
| U33 | Boot 4.1.x line | `./gradlew :opa-abac-mcp:test :example-mcp-server:test -PspringBootLine=compat` green, and `dependencyInsight --dependency spring-boot --configuration testRuntimeClasspath -PspringBootLine=compat` on both modules resolves `4.1.1` — or STATUS-08 carries the PKIX deferral line with the maintainer's command | T8 |
| U34 | The default line is untouched | without the property, `dependencyInsight` on the same modules resolves `4.0.8`; `ci.yml`'s job names are byte-identical to `main` (`git diff main -- .github/workflows/ci.yml` adds one step and changes no `name:` of a job) | T8 |

## Integration (I*)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| I1 | The example boots on the auto-configuration | `@SpringBootTest` of `example-mcp-server` with its own identity/capability/gate configuration classes deleted: `ToolGateInstallationTest` (every handler in every sync list is a `ToolCallGate`) and `McpServerSecurityTest` green; `ToolCallAuthorizer` is the starter-built bean; the `PolicyPathResolver` resolves `tool` to `agent_tools` | T3 |
| I2 | SYNC + STREAMABLE, real `POST /mcp` | `initialize` → `notifications/initialized` → `tools/call` with a forwarded bearer: the OPA stub's recorded input carries the caller's subject id; stub deny ⇒ `structuredContent` `{layer: tool-gate, code: tool-gate-denied}` and a body-invocation counter of 0; stub allow ⇒ counter 1 | T5 |
| I3 | ASYNC + STREAMABLE | the I2 assertions with a `Mono`-returning test tool | T5 |
| I4 | SYNC + STATELESS | the I2 assertions without a session (`initialize` → `tools/call`) | T5 |
| I5 | ASYNC + STATELESS | the I4 assertions with a `Mono`-returning test tool | T5 |
| I6 | A `@Primary` sync customizer without `immediateExecution` | type `SYNC` + `STREAMABLE` plus a `@Primary McpSyncServerCustomizer` that does not set `immediateExecution(true)`: a real `tools/call` ⇒ `tool-gate-unauthenticated` and a body counter of 0 (the handler runs on `boundedElastic` with no identity — measured 2026-10-09) | T5 |

## E2E (E*)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| E1 | Part 0 on the rig: nothing changed | after `ENABLE_MCP=1 ./deploy.sh build` + `ENABLE_MCP=1 ./deploy.sh up --pods 2`: `scripts/postman/run-agent-tool-matrix.sh` green on matrix cells E1–E11 including the E6/E7/E11 drills, with `git diff --exit-code` empty on the agent-tool collection file | T4 |
| E2 | The guard live (matrix cell E12) | the drill pod (`agent-clients=catalog-gateway`, `azp` copied): demo's `catalog-gateway` token on `tools/call get_catalog` ⇒ advisory `code` = `tool-gate-actor-required` asserted by value; a `catalog-agent-readonly` token on the same pod ⇒ its normal readonly outcome; after the EXIT trap the default pod answers the human token normally | T6 |
| E3 | Jar ↔ policy pairing live | with T7's jar and policy on the rig (image rebuilt, OPA restarted after the rego edit): matrix cells E1–E12 green — the marker reaches the policy on single and batch paths | T7 |
