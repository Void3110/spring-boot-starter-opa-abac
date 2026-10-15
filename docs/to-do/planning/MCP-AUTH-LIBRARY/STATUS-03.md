---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/spring
  - area/mcp
---

# STATUS — T3: `OpaMcpAutoConfiguration` + the prerequisite startup failures

**Status:** ✅ DONE (2026-10-10, autonomous — part 0 runner)

## What shipped

- **`dev.dmitriikonovalov.opaabac.autoconfigure.OpaMcpAutoConfiguration`** on its own line in
  `AutoConfiguration.imports` (now three entries), `@AutoConfiguration(after = OpaAbacAutoConfiguration.class)`,
  `@ConditionalOnClass(name = {"…mcp.authz.ToolCallAuthorizer",
  "org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration"})` — **no**
  `opa.abac.enabled` condition. `@EnableConfigurationProperties` for the module's three properties classes.
  The starter build adds `compileOnly(project(":opa-abac-mcp"))` and, for tests only, the module + the Spring
  AI MCP server starter (with its BOM). Spring AI is not even `compileOnly` (the condition is a name string).
  `:opa-abac-spring-boot-starter:dependencies --configuration compileClasspath` shows
  `project ':opa-abac-mcp' → io.modelcontextprotocol.sdk:mcp-core:2.0.0`.
- **Beans** (`@ConditionalOnMissingBean` unless stated): `ToolCallAuthorizer` and `ToolRosterFilter` (the
  `ToolRegistry`, `OpaClient` and `PrincipalCeilingSupplier` arrive through `ObjectProvider.getIfAvailable()`,
  never as hard dependencies), `DelegationChainExtractor` (`ClaimDelegationChainExtractor` over a **private**
  `JsonMapper.builder().build()`), `AgentCapabilitySupplier` (`ConfigAgentCapabilitySupplier`), and — always —
  `ActorClaimWiringCheck`, fed `opa.abac.subject.attribute-claims` through `Binder.get(environment)`.
  `ActorClaimWiringCheck` is now a `SmartInitializingSingleton` (module change; its test calls
  `afterSingletonsInstantiated()`).
- **Three static `BeanPostProcessor`s:** (1) the memos — any `PrincipalCeilingSupplier` →
  `MemoizingPrincipalCeilingSupplier` under `opa.abac.resolve-memo.enabled` (read exactly as the role memo's
  `@ConditionalOnProperty` reads it), any `AgentCapabilitySupplier` → `TurnScopedCapabilityCache` (always), each
  double-wrap guarded; (2) the policy path — any `PolicyPathResolver` decorated by `ToolPolicyPathResolver`
  (now `(PolicyPathResolver delegate, ToolAuthorizationProperties)`: `tool` ⇒ `opa.abac.mcp.policy-path`, every
  other type ⇒ the delegate; the throw-for-other-types branch is gone; `TOOL_RESOURCE_TYPE` is public);
  (3) the gate — `McpToolGatePostProcessor`: every `List` bean, every element; a `SyncToolSpecification` is
  wrapped (one already gated is kept); any other element with a public no-arg `McpSchema.Tool tool()`
  accessor ⇒ startup failure naming its type and "supported: McpServerFeatures.SyncToolSpecification"; a
  scoped proxy (a `scopedTarget.` sibling) passes through un-iterated; the authorizer is fetched lazily.
- **`McpStartupVerifier`** — one `SmartInitializingSingleton`, two ordered steps in one callback: (1) no
  `OpaClient` ⇒ fails naming `opa.abac.enabled` and `OpaClient` (and that `opa.abac.allow-ungated-methods` does
  not apply to tools); no `PrincipalCeilingSupplier` ⇒ fails naming it; (2) every singleton `List` bean
  (`getBeanNamesForType(List.class, false, false)`, skipping scoped targets and names with a `scopedTarget.`
  sibling) is resolved with `getBean`, then every tool-bearing element must carry the gate, else startup fails
  naming the bean. Step 3 is T4's.
- **The example shrinks:** `IdentityConfiguration` and `AgentCapabilityConfiguration` deleted;
  `ExampleAuthzConfiguration` keeps only the raw `TypeLevelPrincipalCeilingSupplier` bean and the
  `RosterFilterInstaller` bean (its `example.mcp.authz.roster-filter` condition unchanged); the example's own
  `toolPolicyPathResolver` bean and gate post-processor are gone (the starter's decorate/wrap).
  `ToolRegistrationConfiguration` unchanged (the validator stays until T4). `application.yml` comments that
  described wiring updated; no key changed.
- **Docs:** `AGENT-TOOL-AUTHORIZATION.md` gains *Installing the module* (the dependency, the auto-configuration,
  what an adopter provides / what is defaulted, the reserved `tool` type, the startup-failure rows that exist
  after this ticket); the module README gains the `authz` package row, the install paragraph and the two
  `opa.abac.mcp.*` gate properties; `CHANGELOG.md` gains the starter line (the auto-configuration, its
  prerequisites, and that `opa.abac.enabled=false` does not turn it off).

## Tests

New `OpaMcpAutoConfigurationTest` (starter, `ApplicationContextRunner`, the QA baseline: `act_chain` claims +
the starter's `OpaClient` pointed at an in-process `HttpServer` stub + a ceiling source + an empty registry;
failures asserted by this slice's own text along the cause chain) — **26 cases, all green**:

- **U9** activation (beans present; the capability bean is a `TurnScopedCapabilityCache` over the YAML
  default); Spring AI hidden ⇒ none; the module hidden ⇒ none.
- **U10** no `OpaClient` ⇒ fails naming `opa.abac.enabled` + `OpaClient`; and with `opa.abac.enabled=false` +
  `opa.abac.allow-ungated-methods=true` + `OpaAbacDisabledAutoConfiguration` in the runner and no
  `@OpaPreAuthorize` bean ⇒ the same failure. **U11** no ceiling ⇒ fails naming `PrincipalCeilingSupplier`.
- **U12** an adopter capability source wins and is still memo-wrapped; an adopter extractor wins.
- **U13** with the starter's `PerTypePolicyPathResolver` (prefix `abac`): `tool` ⇒ `agent_tools` (`x/y` with the
  property), `catalog` ⇒ exactly the undecorated resolver's `abac/catalog`; the context's `OpaClient` hits
  `/v1/data/agent_tools` and `/v1/data/agent_tools/bulk` on the stub.
- **U14** the ceiling is a `MemoizingPrincipalCeilingSupplier` by default, the raw bean with the switch off.
- **U15** two sync lists, elements at index > 0 gated, a pre-gated element kept by identity, a `List<String>`
  and a resource-specification list untouched.
- **U16** the wiring check fails naming both properties with the starter's `OpaAbacProperties` bean replaced by
  a mock no tool-gate frame ever calls (see *Decisions*).
- **U35** async stateful, stateless sync, stateless async and a test-local record with `tool()` ⇒ each fails
  naming its type and the supported one (declarations matching); a `registerSingleton` sync list, undeclared ⇒
  fails naming the bean.
- **U36** (lazy, the QA preamble's processor installed; an unrequested probe bean asserted uncreated first):
  U10, U11, U16 and U35 still fail; request- and prototype-scoped list beans stay at 0 `@Bean` calls and do not
  fail; without lazy init a `@RequestScope` proxied list nobody injects starts.
- **Mutation-checked:** removing step 2's scoped-proxy skip fails the `@RequestScope` case; removing step 2
  entirely fails the `registerSingleton` and lazy-unsupported cases (both restored).
- Module: new `ToolPolicyPathResolverTest` (decorator); moved tests' `new ToolPolicyPathResolver(properties)`
  gained a delegate that throws an `AssertionError` for a non-`tool` context (no assertion changed).
- **I1:** `ToolGateInstallationTest` (every handler in every sync list a `ToolCallGate`) and
  `McpServerSecurityTest` green on the auto-configuration, plus two new cases: the `ToolCallAuthorizer` bean
  is declared by `OpaMcpAutoConfiguration`, and the context's `PolicyPathResolver` resolves `tool` to
  `agent_tools` and `catalog` to `catalog`.
- Counts: `:opa-abac-mcp:test` 103, `:opa-abac-spring-boot-starter:test` 126, `:example-mcp-server:test` 57 —
  0 failures. `./gradlew build` green.

## Architecture review + refactor

Inline ★ review (T3 is a headline ticket; the multi-lens path is unavailable inside a part-runner — layer 3
re-covers it):

- **Fail-closed:** every configuration that would serve a tool without the gate in part 0 fails startup —
  no `OpaClient` (also under ADR 0038's acknowledgment), no ceiling source, any tool-bearing element that is
  not a stateful sync specification, any list that bypassed the post-processor — and all of it from one eager
  callback, proven under real lazy initialization. The post-processor checks **every** element (correction 3).
- **Security:** the policy-path decorator sends only `tool` to the tool document and everything else to the
  application's resolver (correction 1) — a catalog decision can no longer be routed to the agent policy, nor
  a tool decision to a per-type document (U13). The claim parser's mapper is private (correction 2) — the
  size-cap serialization no longer depends on the application's Jackson customizations. The memos keep their
  keys/scopes (T2). No message or WARN carries a source's response body.
- **Concurrency:** no new threading; the gate is the moved `ToolCallGate`, deciding on the calling thread.
- **Refactor from the review:** the ceiling memo first read `opa.abac.resolve-memo.enabled` with
  `Environment.getProperty(…, Boolean.class, true)`, which accepts `yes`/`on`/`1` where the role memo's
  `@ConditionalOnProperty(havingValue = "true")` does not — two readers of one switch could disagree. Now read
  as `"true".equalsIgnoreCase(env.getProperty(key, "true"))`, identical to the role memo.
- **Module-layer separation:** the module declares no beans (it gained no Spring wiring); every bean and
  post-processor is the starter's; the post-processor, verifier and the tool-bearing rule are package-private
  starter classes — the starter's public API grows by `OpaMcpAutoConfiguration` and one `.imports` line only.
- **Static analysis:** local Sonar on changed files — no new finding; the 43 remaining are the documented
  by-design classes listed in STATUS-02.

## Integration / e2e

**I1** green: the example's `@SpringBootTest` classes boot on the starter's auto-configuration with the
example's identity / capability / gate configuration deleted. No rig run in this ticket (T4's E1).

## Decisions

- **U16's "a mock that is never called"** — measured: a Mockito mock registered for `OpaAbacProperties` is
  still *bound* by Spring's `ConfigurationPropertiesBindingPostProcessor` (its type carries
  `@ConfigurationProperties`, so `JavaBeanBinder` calls its getters/setters), so `verifyNoInteractions` cannot
  hold for reasons outside this slice. The assertion records every caller's stack and asserts that no frame
  from `…opaabac.mcp` or `OpaMcpAutoConfiguration` ever touched it — the property the case exists for.
  (Mulch `spring-security-integration`.)
- The gate post-processor, the verifier and the tool-bearing rule live in the **starter** as package-private
  classes (`McpToolGatePostProcessor`, `McpStartupVerifier`, `McpToolSpecifications`): the module must declare
  no beans, and none of them is adopter API.
- A request-scoped *target* list (`scopedTarget.*`, created inside a request) is post-processed normally —
  wrapped, or refused — rather than passed through: that can only gate more, never less. Only the proxy is
  passed through un-iterated.
- `CeilingMemoWiringTest` (T2's example-side wiring proof) deleted — the decoration moved into the
  auto-configuration and U14 replaces it.
- Seams verified against reality, no deviation: `McpServerAutoConfiguration`'s FQN (jar listing); the SDK
  records' public `tool()` accessors (`javap`); the decorated resolver reaching the starter's `OpaClient`
  whatever the auto-configuration order (U13 green through the real `HttpOpaClient`).

## Commit

This commit — `feat(mcp): OpaMcpAutoConfiguration wires the tool-gate and refuses to start without its prerequisites (MCP-AUTH-LIBRARY T3)`.
