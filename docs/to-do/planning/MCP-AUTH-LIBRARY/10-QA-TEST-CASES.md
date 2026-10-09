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
> moved test shows only package/import lines, property-prefix strings, the ceiling-stub type (T2), constructor
> call sites the tickets name (T2's `ToolRosterFilterTest:213` line, T3's `ToolPolicyPathResolver` delegate),
> and T2's named fixture restructuring of `ToolCallGateTest` (the tool body calls the catalog stub without the
> example's client; the three cases that need the catalog translator — the two target-gate relabel cases and
> `offIsNeverWiderThanOn` — stay in the example as `CatalogToolGateTest`). Any other
> change to a moved assertion is a behavior change and needs a STATUS *Decisions* entry.
>
> **The starter context-runner baseline (T3–T5):** every `ApplicationContextRunner` fixture starts from
> `opa.abac.subject.attribute-claims=act_chain` + an `OpaClient` (pointed at an in-process `HttpServer` stub) +
> a `PrincipalCeilingSupplier` + an **empty** `ToolRegistry`, and contributes a non-empty specification list
> only together with matching declarations (and vice versa). A case that proves one failure removes exactly one baseline piece, so the failure it
> asserts cannot come from another guard. Startup-failure assertions read the message along the cause chain
> (`context.getStartupFailure()` **is** the thrown exception; `rootCause()` of an exception without a cause
> fails) and assert this slice's own text, never just `hasFailed()`.
>
> **Lazy-initialization cases** (U24, U36, U37): `spring.main.lazy-initialization` is read only by
> `SpringApplication`, so an `ApplicationContextRunner` ignores it. These cases install the processor
> themselves — `withInitializer(c -> c.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()))`
> — and first assert that an ordinary unrequested bean stays uninitialized (proof the context really is lazy).
>
> **Denials on the wire:** the gate's advisory is `CallToolResult{isError: true}` with the layer and code in
> **`_meta`** (`CallToolResult.meta()` in Java; the fourth record component) — `structuredContent` stays null.

## Unit (U*)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| U1 | The identity + declaration tests run in the module | `ClaimDelegationChainExtractorTest`, `DelegationChainBoundaryTest`, `AgentCapabilitySupplierTest`, `AgentCapabilityWireContractTest`, `TurnScopedCapabilityCacheTest`, `ToolDescriptorTest`, `ToolRegistryTest` green under `:opa-abac-mcp:test`, bodies unchanged per the part-0 rule; none remains in `example-mcp-server` | T1 |
| U2 | `ActorClaimWiringCheck` without the starter's properties | blank `actor-claim` ⇒ `IllegalStateException`; claim not in the copied list ⇒ fails, message names `opa.abac.mcp.identity.actor-claim` **and** `opa.abac.subject.attribute-claims`; claim present ⇒ passes. The class imports nothing from `…opaabac.autoconfigure` | T1 |
| U3 | The capability outage joins the family, codes unchanged | `new AgentCapabilityUnavailableException(..)` is a `DecisionIndeterminateException`; `TurnScopedCapabilityCacheTest`'s outage cases still replay the outage (not an empty profile) | T1 |
| U4 | The renamed prefixes bind, the old ones do not (test class named `…BindingTest` — the cross-cutting grep excludes it) | a `Binder` over `opa.abac.mcp.identity.actor-claim=x`, `opa.abac.mcp.agents.profiles[0].actor-id=a` populates `IdentityProperties` / `AgentCapabilityProperties`; the same values under `example.mcp.identity` / `example.mcp.agents` leave the defaults (`act_chain`, no profiles); in `example-mcp-server`, a `…BindingTest` loads its own `application.yml` (`YamlPropertySourceLoader`) and asserts nothing binds under `example.mcp.identity` / `example.mcp.agents` while `opa.abac.mcp.identity.actor-claim` and `opa.abac.mcp.agents.profiles[0].actor-id` do | T1 |
| U5 | The moved gate tests, on the new SPI | every `ToolCallGateTest` case green with unchanged expected codes — the moved cases in the module, the three that need the catalog translator (the two target-gate relabel cases and `offIsNeverWiderThanOn`) in the example's `CatalogToolGateTest`; ceiling stub ⇒ `PrincipalCeilingSupplier`; a ceiling lookup throwing `RoleResolutionException` **and** one throwing a test-local `DecisionIndeterminateException` subtype ⇒ both `tool-gate-ceiling-unavailable`; a capability outage ⇒ `tool-gate-capability-unavailable`; an engine failure (`PolicyEngineException`) ⇒ `tool-gate-policy-unavailable` | T2 |
| U6 | The moved roster tests | every `ToolRosterFilterTest` case green: engine could not decide ⇒ empty; all-false ⇒ empty; identity / capability / ceiling edge ⇒ unfiltered; wrong-length vector ⇒ empty; `agent-gate` off ⇒ ceiling-only contexts | T2 |
| U7 | `MemoizingPrincipalCeilingSupplier` | the example's T2 wiring honors `opa.abac.resolve-memo.enabled` (`false` ⇒ the raw supplier); inside a bound request: two lookups of one `(subject, type)` ⇒ one delegate call; an empty result memoized; a family outage memoized and **re-thrown** on the repeat without a second delegate call; a non-family `RuntimeException` propagates and is **not** memoized (the next lookup calls the delegate); no request context ⇒ every lookup reaches the delegate | T2 |
| U8 | The example's ceiling source, renamed | `TypeLevelPrincipalCeilingSupplierTest` (was `TypeLevelRoleDefinitionSupplierTest`) green with unchanged assertions; the class implements `PrincipalCeilingSupplier` and **not** `RoleDefinitionSupplier` | T2 |
| U9 | Auto-configuration activation | module + Spring AI on the classpath ⇒ `ToolCallAuthorizer`, `ToolRosterFilter`, `DelegationChainExtractor`, `AgentCapabilitySupplier` (a `TurnScopedCapabilityCache` over `ConfigAgentCapabilitySupplier`), `ActorClaimWiringCheck` present; Spring AI hidden (`FilteredClassLoader`) ⇒ none of them; the module hidden ⇒ none of them | T3 |
| U10 | No `OpaClient` ⇒ startup failure | the baseline minus the `OpaClient`: the context fails and the failure's message chain contains `opa.abac.enabled` and `OpaClient`; the same with `opa.abac.enabled=false` **and** `opa.abac.allow-ungated-methods=true` (fork 16), with `OpaAbacDisabledAutoConfiguration` among the runner's `AutoConfigurations` (the combined setup an adopter gets) and no `@OpaPreAuthorize` bean, so ADR 0038's `UngatedMethodsGuard` cannot be the failure being observed | T3 |
| U11 | No `PrincipalCeilingSupplier` ⇒ startup failure | with an `OpaClient` but no ceiling supplier: fails, the message names `PrincipalCeilingSupplier` | T3 |
| U12 | Adopter beans win | an adopter `AgentCapabilitySupplier` ⇒ no `ConfigAgentCapabilitySupplier` bean, and the adopter's bean is still wrapped by `TurnScopedCapabilityCache`; an adopter `DelegationChainExtractor` ⇒ the default backs off | T3 |
| U13 | The policy-path decorator | with the starter's `PerTypePolicyPathResolver`: a `tool` context resolves to `agent_tools` (and to `x/y` with `opa.abac.mcp.policy-path=x/y`); a `catalog` context resolves to exactly what the undecorated resolver returns; an OPA `HttpServer` stub behind the context's `OpaClient` records `/v1/data/agent_tools` for a tool decision and `/v1/data/agent_tools/bulk` for a roster batch | T3 |
| U14 | The ceiling memo is wired | the context's `PrincipalCeilingSupplier` is a `MemoizingPrincipalCeilingSupplier` by default; with `opa.abac.resolve-memo.enabled=false` it is the raw bean | T3 |
| U15 | The sync gate covers every list and every element | two `List<McpServerFeatures.SyncToolSpecification>` beans (as `toolSpecs` + `syncTools` are in the example), their tools declared (baseline), ⇒ every element's handler is a `ToolCallGate`, including an element at index > 0; an element already gated is not wrapped twice; a `List<String>` bean and a `List` of resource specifications are returned untouched | T3 |
| U16 | The wiring check reads the environment | `opa.abac.mcp.identity.actor-claim=act_chain` with `opa.abac.subject.attribute-claims` lacking it ⇒ startup fails naming both, asserted in a context where the starter's `OpaAbacProperties` bean is replaced by a mock that is never called | T3 |
| U17 | Wrap-time declarations check | wrapped ⊋ registry ⇒ fails, message lists the undeclared names; registry ⊋ wrapped ⇒ fails, message lists the never-wrapped names; names spread over two lists that together equal the registry ⇒ starts; no `ToolRegistry` bean ⇒ fails naming it; empty registry + no tools ⇒ starts | T4 |
| U18 | The call-time backstop | the moved `ToolCallGateTest` case for an undeclared tool name ⇒ `tool-undeclared`, still green | T4 |
| U19 | The removed types are gone | `git grep -n 'ToolCallClassifier\|ToolRegistryValidator' -- '*.java'` returns nothing; `McpToolSurfaceTest`'s remaining cases green | T4 |
| U20 | Async stateful wrapper | deny ⇒ the returned `Mono` emits the advisory (`_meta` layer `tool-gate` + code) and the delegate `BiFunction` is **never invoked**; allow ⇒ the delegate's result is emitted; the authorizer is called **once, at `apply`**, never again at subscription (a counting stub, subscribed twice); a delegate throwing `ToolInvocationException` synchronously **and** one emitting `Mono.error(ToolInvocationException)` ⇒ the same advisory with its layer + code; a delegate whose deferred body records a `ToolFailureRecord` and emits an error result ⇒ relabelled at emission (request bound); another emitted error and an empty completion ⇒ passed through unchanged | T5 |
| U21 | Stateless sync wrapper | the U20 deny/allow/`ToolInvocationException` cases with an `McpTransportContext` first argument | T5 |
| U22 | Stateless async wrapper | every U20 case for `McpStatelessServerFeatures.AsyncToolSpecification` | T5 |
| U23 | Unwrappable specification ⇒ startup failure (all four now supported) | a `List` bean of a test-local record exposing `McpSchema.Tool tool()` ⇒ fails naming the record type; a list holding one known and one unknown element ⇒ fails; a list of elements without such an accessor ⇒ untouched; U35's three SDK-type cases, their tools declared, now start and wrap | T5 |
| U24 | WebFlux ⇒ startup failure | with `spring-webflux` on the test classpath: a `ReactiveWebApplicationContextRunner` with module + Spring AI ⇒ fails, the message names WebFlux / reactive and "not supported in v1" — also under real lazy initialization (preamble); the servlet runner with the same configuration starts. If the artifact cannot be fetched in the sandbox (PKIX), STATUS-05 carries `**LOCAL PROOF DEFERRED:** PKIX — spring-webflux` with the maintainer's command | T5 |
| U25 | Non-web ⇒ deny-all | a plain (non-web) context starts; a wrapped handler of each of the four types invoked with an empty `SecurityContextHolder` ⇒ `tool-gate-unauthenticated`, delegate never invoked | T5 |
| U26 | `agent-clients` empty ⇒ unchanged | for a no-actor caller with `azp` present, the built `AbacContext` equals the one built before T6 (same subject attributes, no new key) and the decision path is the human one | T6 |
| U27 | The guard | `azp` listed + no actor ⇒ `tool-gate-actor-required` (layer `tool-gate`) and a WARN naming the client — **also with `opa.abac.mcp.agent-gate.enabled=false`**; `azp` listed + a valid actor ⇒ the agent path; `azp` not listed + no actor ⇒ the human path; `azp` listed + a **malformed** actor ⇒ `tool-gate-identity-unreadable` (the extractor's deny wins) | T6 |
| U28 | The wiring check covers `azp` | `agent-clients=[c]` without `azp` in `opa.abac.subject.attribute-claims` ⇒ startup fails naming `azp`; with it ⇒ starts; `agent-clients` empty ⇒ `azp` not required; a binder test shows an empty-string environment value binds `agent-clients` to an empty list | T6 |
| U29 | The roster under the guard | a listed client without an actor at list time ⇒ `RosterDecision.unfiltered()` + a WARN, while every call for that caller denies `tool-gate-actor-required` — the same with the agent gate OFF | T6 |
| U30 | Every tool-gate input is marked | the context from `authorize` and **every** item of a roster batch (an `HttpServer` stub recording the bulk body) carry `environment.schema = "opa-abac.mcp.tool-gate/v1"` | T7 |
| U31 | The policy refuses an unknown or missing version (`opa test`) | unknown version ⇒ deny for a human and for an agent; marker missing ⇒ deny for both; `environment` absent ⇒ deny; a bulk with one unmarked item ⇒ that position `false`, the others unchanged; all pre-existing cases green with the marker added to their fixtures (the helper **and** the two hand-built inputs) | T7 |
| U32 | The guard is load-bearing | temporarily delete the `known_schema` requirement ⇒ at least one named U31 test fails (record its name in STATUS-07), then restore | T7 |
| U33 | Boot 4.1.x line | `./gradlew :opa-abac-mcp:test :opa-abac-spring-boot-starter:test :example-mcp-server:test -PspringBootLine=compat` green, and `dependencyInsight --dependency spring-boot --configuration testRuntimeClasspath -PspringBootLine=compat` on those modules resolves `4.1.1` — or STATUS-08 carries `**LOCAL PROOF DEFERRED:** PKIX — Boot 4.1.1` with the maintainer's command (the proof is then that command or CI on the PR to `main`) | T8 |
| U34 | The default line is untouched | without the property, `dependencyInsight` on the same modules resolves `4.0.8`; the set of job ids under `jobs:` in `ci.yml` (`opa-policy-tests`, `clean-room`, `verification-layer`, `demo-ui`, `build` — the ruleset's required checks) is identical to `main`'s, and the diff adds exactly one step inside `build` | T8 |
| U35 | Part 0 never serves an unwrapped type | the baseline plus a non-empty `List` of `McpServerFeatures.AsyncToolSpecification` (and, separately, of each stateless type) with **matching declarations** (so only the type rule can fail) ⇒ startup failure naming the type and the supported one; a test-local record exposing `McpSchema.Tool tool()` ⇒ the same failure | T3 |
| U36 | Guards are eager | under real lazy initialization (the processor installed per the preamble): the U10, U11 and U16 failures still happen, and U35's — the unsupported list is resolved by the verifier's step 2, not by being injected anywhere; an unrelated **request-scoped** `List` bean and a **prototype** `List` bean in the same context are not instantiated and do not fail startup | T3 |
| U37 | The declarations check under lazy initialization | under real lazy initialization (preamble): a registry declaring two tools and a matching sync list nothing injects ⇒ starts, both handlers gated; the registry declaring a third tool ⇒ fails naming it | T4 |
| U38 | The authz rename is complete in the example's YAML | T1's `…BindingTest` extended: nothing in `application.yml` binds under `example.mcp.authz.agent-gate` / `example.mcp.authz.policy-path`, while `opa.abac.mcp.agent-gate.enabled` and `opa.abac.mcp.policy-path` do, and `example.mcp.authz.role-source.*` / `roster-filter.enabled` still bind to `ExampleAuthzProperties` | T2 |

## Integration (I*)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| I1 | The example boots on the auto-configuration | `@SpringBootTest` of `example-mcp-server` with its own identity/capability/gate configuration classes deleted: `ToolGateInstallationTest` (every handler in every sync list is a `ToolCallGate`) and `McpServerSecurityTest` green; `ToolCallAuthorizer` is the starter-built bean; the `PolicyPathResolver` resolves `tool` to `agent_tools` | T3 |
| I2 | SYNC + STREAMABLE, real `POST /mcp` (in `opa-abac-spring-boot-starter/src/test`, like I3–I6) | `initialize` → `notifications/initialized` → `tools/call` with a forwarded bearer: the OPA stub's recorded input carries the caller's subject id; stub deny ⇒ `isError: true` with `_meta.layer = tool-gate`, `_meta.code = tool-gate-denied` and a body-invocation counter of 0; stub allow ⇒ counter 1 | T5 |
| I3 | ASYNC + STREAMABLE | the I2 assertions with a `Mono`-returning test tool | T5 |
| I4 | SYNC + STATELESS | the I2 assertions without a session (`initialize` → `tools/call`) | T5 |
| I5 | ASYNC + STATELESS | the I4 assertions with a `Mono`-returning test tool | T5 |
| I6 | A `@Primary` sync customizer without `immediateExecution` | type `SYNC` + `STREAMABLE` plus a `@Primary McpSyncServerCustomizer` that does not set `immediateExecution(true)`: a real `tools/call` ⇒ `tool-gate-unauthenticated` and a body counter of 0 (the handler runs on `boundedElastic` with no identity — measured 2026-10-09) | T5 |

## E2E (E*)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| E1 | Part 0 on the rig: nothing changed | after `ENABLE_MCP=1 ./deploy.sh build` + `ENABLE_MCP=1 ./deploy.sh up --pods 2`: `scripts/postman/run-agent-tool-matrix.sh` green on matrix cells E1–E11 including the E6/E7/E11 drills, and `git diff --exit-code "$(git merge-base main HEAD)" HEAD -- scripts/postman/agent-tool-matrix.postman_collection.json` empty | T4 |
| E2 | The guard live (matrix cell E12) | the drill pod (`agent-clients=catalog-gateway`, `azp` copied): demo's `catalog-gateway` token on `tools/call get_catalog` ⇒ advisory `code` = `tool-gate-actor-required` asserted by value; a `catalog-agent-readonly` token on the same pod ⇒ its normal readonly outcome; after the EXIT trap the default pod answers the human token normally | T6 |
| E3 | Jar ↔ policy pairing live | with T7's jar and policy on the rig (image rebuilt, OPA restarted after the rego edit): matrix cells E1–E12 green — the marker reaches the policy on single and batch paths | T7 |
