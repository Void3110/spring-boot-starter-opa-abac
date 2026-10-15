---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/opa
  - area/spring
  - area/mcp
---

# MCP-AUTH-LIBRARY — decomposition

> T1…T8, in order. Each ticket is one focused commit's worth of work. The design is [[00-DESIGN]]; the
> contract is [[0036-mcp-auth-library-module|ADR 0036]] (§1–§12, plus §13, the decomposition pins written
> with this package). Seam claims below were checked against the source and the jars in the Gradle cache
> on 2026-10-09 (MCP Java SDK `mcp-core` 2.0.0, Spring AI 2.0.0, Spring Boot 4.0.8); each ticket says how.
> Two parts: **part 0 = T1–T4** (the behavior-preserving extraction), **part 1 = T5–T8** (the new behavior),
> declared in [[00-DESIGN]] §Execution parts.

## Sizing (flow guide §2a)

Eight tickets trips smell (c) on count, and the slice touches the library and one example service (b). The
grill settled this shape on purpose (fork 13: one slice, two parts, not two slices): the blast radius is
enumerable — part 0 changes **no** runtime behavior and is held to a mechanical bar (the agent matrix's
cells E1–E11 unchanged, every moved test green), and part 1's new edges are each pinned below. The parts
handle the context-window limit; neither part exceeds four tickets.

## Critical path

```
part 0:  T1 ──► T2 ──► T3 ──► T4
part 1:                        ──► T5 ──► T6 ──► T7 ──► T8
```

- **Sequential, all of it.** T1 creates the module and moves the Spring-free kernel; T2 moves the decision
  onto it (and needs T1's types); T3 replaces the example's wiring with the starter's auto-configuration
  (needs T1–T2's classes in the module); T4 replaces the annotation-scanning validator with the wrap-time
  check T3's post-processor makes possible, and closes part 0 on the rig.
- Part 1 is ordered by risk: T5 (the three new specification types — the one ticket whose outcome rests on a
  measured threading fact) first; T6 (the agent-clients guard) and T7 (schema v1 — jar and policy in one
  commit) are independent of each other but share the rig, so they stay sequential; T8 (the Boot 4.1 step +
  close-out) last.
- **Independently landable subset:** T1–T4 (part 0). After T4 the module ships the example's behavior as a
  dependency — a usable incubating artifact even if part 1 slipped, because T3 already refuses to start on
  any tool-specification type it does not wrap (stateful sync only, until T5).

## Slice-wide invariants (carried into every ticket's NOT-to-touch)

- **Fail-closed.** No error path widens the result: every request-time failure is a deny with its own code
  (or, for the roster hint, the documented empty/unfiltered outcome); every misconfiguration that would leave
  a tool ungated is a **startup failure**, never a skip.
- **Never present-but-inert.** No property, condition or classpath state leaves the module on the classpath
  with tools served ungated. `opa.abac.enabled=false` and ADR 0038's `opa.abac.allow-ungated-methods=true`
  do **not** turn the gate off (fork 16).
- **`opa-abac-core` untouched; every existing module's public API unchanged** (`opa-abac-spring-security`,
  `opa-abac-spring-data`, `opa-abac-keycloak-directory`; the starter only *adds* an auto-configuration).
- **Match in Rego.** The intersection, risk ordering and category check stay in `agent_tools.rego`; no Java
  decision path (fork 14). Only `agent_tools.rego` + its test change under `infra/opa/policies/` (T7).
- **Part 0 changes no behavior the example can observe.** Its new outcomes are startup failures for
  misconfigurations (incl. any tool-specification type part 0 does not yet wrap, T3) plus T3's three named
  corrections — the policy-path fall-through for non-`tool` types, the private claim mapper, every-element
  wrapping — each invisible to the example.
- **Startup guards run eagerly.** Every startup check runs from a `SmartInitializingSingleton` callback: the
  wiring check and the reactive guard are their own; prerequisites, list resolution and declarations are ordered
  steps of T3's one `McpStartupVerifier`; the unsupported-type rule fires in the gate post-processor, which the
  verifier's list resolution forces every singleton list through (a check living only in a post-processor is
  not eager — a post-processor sees only beans that get created) — so
  `spring.main.lazy-initialization=true` cannot skip it (Boot's `LazyInitializationBeanFactoryPostProcessor`
  exempts `SmartInitializingSingleton` beans — verified by `javap -c`).
- **The published module never depends on the starter, the example or Spring AI.** Test scopes may differ
  (T5's ITs live in the starter's test sources, the `opa-abac-keycloak-directory` precedent).
- **Clean-room.** The second consumer is "a hand-port in a codebase outside this repo" — nothing more.

---

## T1 — the `opa-abac-mcp` module, and the identity kernel + tool declarations move into it

**Goal.** A published, incubating `opa-abac-mcp` module exists and holds the Spring-free half of the
kernel — the delegation chain, the capability seam, the tool declarations and the tool-failure carrier —
with their tests, under `opa.abac.mcp.*` properties; the example consumes them from the module and behaves
exactly as before.

**Deliverables.**
- **Module.** `opa-abac-mcp/` added to `settings.gradle.kts` (library block — and the example block's comment
  loses its "no library module changes" sentence); `build.gradle.kts` on the `opa-abac-keycloak-directory`
  pattern (`java-library` + dependency-management importing the Boot BOM at `libs.versions.springBoot`);
  `api(project(":opa-abac-spring-security"))`; **`api(libs.mcp.core)` with an explicit version** — a new
  catalog entry `mcpSdk = "2.0.0"` (the `mcp-core` version Spring AI 2.0.0's own MCP modules depend on —
  `mcp-spring-webmvc-2.0.0.pom`; the Spring AI BOM manages only `org.springframework.ai` artifacts — and a
  comment says it moves with `springAi`) and library `mcp-core = io.modelcontextprotocol.sdk:mcp-core`. Not a BOM-only version: a
  version from this module's `mavenBom` import would not reach the starter, whose `compileOnly` edge to the
  module would then resolve `mcp-core` with no version (the Boot BOM does not manage it). Its types appear in
  public signatures from T2 (`ToolCallGate.gate(SyncToolSpecification, …)`); Spring Web
  (`RequestContextHolder`) and Spring Security core declared **by the module itself** — the starter's build
  notes that `opa-abac-spring-security` declares them `implementation`, so they do not arrive transitively
  on the compile classpath; `org.springframework.boot:spring-boot` + the configuration processor for the
  `@ConfigurationProperties` types (the keycloak module's "binding metadata only" pattern — **no beans are
  declared in this module**; the starter owns the wiring, T3). *(Seam verified: `mcp-core-2.0.0.jar` in the
  Gradle cache, `javap` of the four specification records; keycloak module's build file read.)*
- **Packaging.** `opa-abac-bom` gains the constraint; the root build's `publishableJarModules` allow-list
  gains `opa-abac-mcp` (`build.gradle.kts:38`); `opa-abac-mcp/gradle.properties` carries `POM_ARTIFACT_ID`,
  `POM_NAME`, `POM_DESCRIPTION` (the keycloak module's file is the template — every allow-listed module has
  one); every coordinate-count site moves from six to **seven** (library modules from five to six):
  `RELEASING.md` `:9`, `:14` and the `:16-23` coordinate block (a new line), `:147`, `:160`, `:163`, `:169`,
  `:186`, `:266`; `build.gradle.kts:35` ("The five publishable library modules"); `settings.gradle.kts:10` ("the 6th published
  coordinate"); `README.md:61` (the count **and** the module brace list); `gradle/libs.versions.toml:12` ("the
  five library modules + the BOM"); `opa-abac-bom/build.gradle.kts:23` ("the 6th coordinate"). Verify with
  `./gradlew :opa-abac-mcp:generatePomFileForMavenPublication` that the POM lists `mcp-core` with version
  `2.0.0` at compile scope and no Spring AI artifact. PIT wiring is **not** changed in this slice.
- **Moves** (package `dev.dmitriikonovalov.opaabac.mcp.identity` and `…opaabac.mcp.tool`, class names and
  bodies unchanged except as listed): `DelegationChain`, `DelegationChainException`,
  `DelegationChainExtractor`, `ClaimDelegationChainExtractor`, `AgentCapabilityProfile`,
  `AgentCapabilitySupplier`, `AgentCapabilityUnavailableException`, `ConfigAgentCapabilitySupplier`,
  `TurnScopedCapabilityCache`, `IdentityProperties`, `AgentCapabilityProperties`, `ActorClaimWiringCheck`;
  `ToolDescriptor`, `ToolRegistry`, `ToolErrorLayer`, `ToolInvocationException`, `ToolFailureRecord`.
  The example's `IdentityConfiguration`, `AgentCapabilityConfiguration`, `CatalogTools` and the authz
  classes now import them from the module (the example's wiring stays in the example until T3).
- **The three changes that are not plain moves:**
  1. **Property prefixes:** `IdentityProperties` → `opa.abac.mcp.identity`; `AgentCapabilityProperties` →
     `opa.abac.mcp.agents` — the **list shape is kept** (`opa.abac.mcp.agents.profiles[].actor-id`, ADR 0036
     §13: a map keyed by agent id would mangle client ids containing dots under relaxed binding).
  2. **`ActorClaimWiringCheck` no longer depends on the starter's `OpaAbacProperties`** (the module cannot
     see the starter): its constructor takes the `IdentityProperties` and the copied-claims list
     (`List<String>`); the example's `IdentityConfiguration` passes
     `opaAbacProperties.getSubject().getAttributeClaims()` until T3 rewires it. Messages name
     `opa.abac.mcp.identity.actor-claim`.
  3. **`AgentCapabilityUnavailableException extends DecisionIndeterminateException`** (ADR 0036 §12; the
     core type's constructors are `protected (String)` / `(String, Throwable)` — verified by source read).
     The gate (`ToolCallAuthorizer`) and the roster (`ToolRosterFilter`) already catch it **before**
     `DecisionIndeterminateException`, so every code stays distinct; `TurnScopedCapabilityCache` catches it
     by its own type (`:71`). No outcome changes.
- **Tests move with their classes** (unchanged bodies except package/imports/prefix strings):
  `ClaimDelegationChainExtractorTest`, `DelegationChainBoundaryTest`, `AgentCapabilitySupplierTest`,
  `AgentCapabilityWireContractTest` (its `locate("infra/opa/policies/agent_tools.rego")` still resolves —
  the module sits at the same depth as the example), `TurnScopedCapabilityCacheTest`,
  `ActorClaimWiringCheckTest` (adapted to the new constructor), `ToolDescriptorTest`, `ToolRegistryTest`.
- **Build-breaker — same commit:** `RosterFilterSmokeCheckTest:96-99` names
  `dev.dmitriikonovalov.example.mcp.identity.{DelegationChainExtractor,AgentCapabilitySupplier,DelegationChain}`
  fully qualified — they become the module's package.
- **Rig + config rename (same commit — the rig must not run a half-renamed config):**
  `example-mcp-server/src/main/resources/application.yml` (`example.mcp.identity` → `opa.abac.mcp.identity`,
  `example.mcp.agents` → `opa.abac.mcp.agents`); `infra/compose.mcp.yaml`'s fifteen
  `EXAMPLE_MCP_AGENTS_PROFILES_*` → `OPA_ABAC_MCP_AGENTS_PROFILES_*` (same index/field suffixes, same
  `${MCP_READONLY_*}` interpolations the E11 drill drives). `McpServerProperties`' class Javadoc, which names
  the old `example.mcp.identity` / `example.mcp.authz` homes, is updated. `run-agent-tool-matrix.sh`'s E5
  operator check (`grep … x-(agent|actor|…)` over `example-mcp-server/src/main/java`, `:322`) also scans
  `opa-abac-mcp/src/main/java`, so the moved gate code stays covered.
- **Documentation delta (this commit):** a new `opa-abac-mcp/README.md` (what it is, **incubating** — may
  change in a minor until an external consumer adopts it, ADR 0036 §1) and `package-info.java` saying the
  same; the root `README.md` module table gains the row; [[AGENT-TOOL-AUTHORIZATION]]'s identity/capability
  property names updated; root `CLAUDE.md` *Layout* gains the module line and the MCP-server section's
  "no library module changes" sentence is replaced by a pointer to the module; `CHANGELOG.md` gains the
  module's entry **under the existing `## [Unreleased] — 1.5.0` heading** (1.5.0 ships ADR 0038 together with
  this slice — never a second 1.5.0 heading): the new incubating module, its coordinates, and the property
  rename for anyone who copied the example. Every later ticket appends its own user-visible line there.

**Acceptance.** **U1**, **U2**, **U3**, **U4**. `./gradlew :opa-abac-mcp:test :example-mcp-server:test`
green; `./gradlew build` green. Non-happy paths: U2 (wiring-check failures), U3 (the re-parented outage still
lands on its own code — asserted through the moved `TurnScopedCapabilityCacheTest`), U4 (the old prefix no
longer binds).

**NOT to touch.** `opa-abac-core` (the module only *extends* its exception type). No starter change yet (T3
owns `OpaMcpAutoConfiguration`). No behavior change: every moved test passes with its assertions untouched.
`example.mcp.catalog.*`, `example.mcp.authz.*` keys unchanged in this ticket (T2's). The capability profile's
wire shape (`AgentCapabilityWireContractTest`) unchanged.

---

## T2 — the decision moves, onto a dedicated `PrincipalCeilingSupplier`

**Goal.** The tool-gate's decision — authorizer, sync gate, roster decision, policy-path routing, the gate's
properties — lives in the module, and the principal's ceiling arrives through the new
`PrincipalCeilingSupplier` SPI instead of the core `RoleDefinitionSupplier`; the example implements the SPI
and still wires the beans itself.

**Deliverables.**
- **Moves** to `dev.dmitriikonovalov.opaabac.mcp.authz`: `ToolCallAuthorizer`, `ToolCallGate`,
  `ToolRosterFilter`, `RosterDecision`, `ToolAuthorizationDecision`, `ToolPolicyPathResolver`,
  `ToolAuthorizationProperties`. The package-private `buildContext(ToolDescriptor, boolean)` and the
  `CODE_*` constants stay package-private (the roster and the gate tests share the package).
  `RosterDecision`, `ToolRosterFilter.decide()` and `ToolRosterFilter.apply(…)` are already `public` —
  the example's `RosterFilterInstaller` (which stays) reaches them across the module boundary unchanged.
- **`ToolAuthorizationProperties` → `@ConfigurationProperties("opa.abac.mcp")`** carrying `agent-gate.enabled`
  and `policy-path` only. The two example-owned settings move to a new example class
  `ExampleAuthzProperties` (`@ConfigurationProperties("example.mcp.authz")`): `role-source.*` (the example's
  ceiling source) and `roster-filter.enabled` (the reflective installer's escape hatch) — both keys keep
  their current names, so `EXAMPLE_MCP_AUTHZ_ROLE_SOURCE_BASE_URL` and
  `EXAMPLE_MCP_AUTHZ_ROSTER_FILTER_ENABLED` are unchanged on the rig.
- **The SPI:** `dev.dmitriikonovalov.opaabac.mcp.authz.PrincipalCeilingSupplier` —
  `Optional<RoleDefinition> lookup(String subject, String resourceType)`, with the full contract in its
  Javadoc (ADR 0036 §6: union of grants across governed resources; a denial kept only if universal;
  over-approximation allowed, under-approximation forbidden; empty = authoritative no-role ⇒ deny; an outage
  **throws a `DecisionIndeterminateException` subtype**; no default implementation; the implementer's note
  that roles recorded on a governing root must be found when the asked type is a descendant).
  `ToolCallAuthorizer` takes it in place of `RoleDefinitionSupplier`, and **attributes a ceiling outage by call
  site, not by type**: any `DecisionIndeterminateException` thrown by the ceiling lookup ⇒
  `tool-gate-ceiling-unavailable` (today's `catch (RoleResolutionException)` would label an adopter's own
  family member `tool-gate-policy-unavailable`). Every other code and catch order unchanged.
- **`MemoizingPrincipalCeilingSupplier`** (module): the ADR 0023 request memo for the new SPI, mirroring
  `MemoizingRoleDefinitionSupplier`'s single-lookup semantics (key `(subject, resourceType)`; resolved, empty
  and the family outage memoized and replayed; any other exception un-memoized; no request context ⇒
  pass-through; bookkeeping failure ⇒ pass-through). The example wraps its supplier with it explicitly in
  this ticket — **honoring `opa.abac.resolve-memo.enabled`** (default true; `false` ⇒ the raw supplier), so
  behavior is preserved in both states (the starter's memo BPP decorated the old `RoleDefinitionSupplier`
  bean under that flag); T3 moves the decoration into the auto-configuration.
- **The example:** `TypeLevelRoleDefinitionSupplier` → **`TypeLevelPrincipalCeilingSupplier`** implementing
  `PrincipalCeilingSupplier` (no longer a `RoleDefinitionSupplier` — keeping the old name would invite the
  confusion ADR 0036 §6 rejects); its `RoleResolutionException` outage signal stays (a family member).
  Consequence, recorded in STATUS: the example no longer defines a `RoleDefinitionSupplier`, so the starter's
  `@ConditionalOnMissingBean` `NoOpRoleDefinitionSupplier` (`OpaAbacAutoConfiguration:93`) appears in its
  context; nothing in the example injects it (verified: the only consumer was the gate).
  `ToolAuthorizationConfiguration` → **`ExampleAuthzConfiguration`**, still wiring every bean for now.
- **Build-breakers — same commit:**
  - `ToolRosterFilterTest` moves to the module; `ToolCallGateTest` **splits** (it builds the example's
    `CatalogApiClient` / `CatalogApiErrorTranslator` / `CallerBearerSupplier` in its fixture, `:13-15`,
    `:175-181`, and the module must not depend on the example): every case moves to the module **except the three that need the example's catalog translator** (a 403 ⇒
    `ToolInvocationException(TARGET_GATE, <errorCode>)` comes only from `CatalogApiClient` →
    `CatalogApiErrorTranslator`): the two target-gate relabel cases (`namesTheTargetGateWhenTheCatalogDenies`,
    `namesTheTargetGateEvenWhenTheDelegateSwallowsTheException`) **and `offIsNeverWiderThanOn`** (it asserts `target-gate` / `ACCESS_DENIED`, `:446-447`). They stay in the example as `CatalogToolGateTest` with the real
    catalog client and unchanged assertions. In the moved class the
    fixture's tool body calls the same `HttpServer` catalog stub through a small test-local
    `java.net.http.HttpClient` call instead of `CatalogApiClient`, so every `catalogCalls` / `opaCalls`
    assertion stays literally the same. The context-building cases (`buildsAHumanContextWithoutAgentAttributes`,
    `buildsAnAgentContextWithTheDualIdentity`) must move — they call the package-private `buildContext`.
  - Ceiling stubs change from `RoleDefinitionSupplier` lambdas `(userId, type, id) -> …` to
    `PrincipalCeilingSupplier` lambdas `(subject, type) -> …` (`ToolCallGateTest:160,236,365`;
    `ToolRosterFilterTest:84,413,460`).
  - `ToolRosterFilterTest:213` calls `properties.getRosterFilter().setEnabled(false)` — that setting moves to
    `ExampleAuthzProperties`; drop the line (the roster filter object never read it) and record it in
    STATUS-02 *Decisions*; the case's assertions are unchanged.
  - `TypeLevelRoleDefinitionSupplierTest` → `TypeLevelPrincipalCeilingSupplierTest`;
    `RosterFilterSmokeCheckTest:131` builds the module's `ToolAuthorizationProperties`;
    `RosterFilterSmokeCheckTest:91` names `ToolAuthorizationConfiguration.class` → `ExampleAuthzConfiguration`.
- **Rig + config rename (same commit):** `application.yml` `example.mcp.authz.{policy-path,agent-gate}` →
  `opa.abac.mcp.{policy-path,agent-gate}`; `infra/compose.mcp.yaml`
  `EXAMPLE_MCP_AUTHZ_AGENT_GATE_ENABLED` → `OPA_ABAC_MCP_AGENT_GATE_ENABLED` (still interpolating
  `${MCP_AGENT_GATE_ENABLED:-true}`, which `deploy.sh:115` and the E7 drill drive); the
  `run-agent-tool-matrix.sh` header comment that names the old variable.
- **Documentation delta (this commit):** [[AGENT-TOOL-AUTHORIZATION]] — the kill-switch table's
  `agent-gate` row renamed; a short *The principal ceiling* subsection stating the
  `PrincipalCeilingSupplier` contract (the Javadoc's, in prose) and why it is not `RoleDefinitionSupplier`;
  the CHANGELOG line for the new SPI.

**Acceptance.** **U5**, **U6**, **U7**, **U8**, **U38**. `./gradlew :opa-abac-mcp:test :example-mcp-server:test` and
`./gradlew build` green. Non-happy paths: U5's ceiling-outage cases (a `RoleResolutionException` **and** an
adopter-defined family subtype both ⇒ `tool-gate-ceiling-unavailable`), U7's memoized outage replay.

**NOT to touch.** No starter change (T3). `ToolPolicyPathResolver` moves **as is** — still the context's
whole `PolicyPathResolver` in the example, still throwing for a non-`tool` type; T3 changes how it composes.
`ToolRosterFilter`'s failure classification (engine ⇒ empty; ceiling/capability/identity ⇒ unfiltered + WARN;
wrong length ⇒ empty) unchanged — its tests move with their assertions. No new denial code.

---

## T3 — `OpaMcpAutoConfiguration`: the starter wires the gate, and refuses to start without its prerequisites

**Goal.** Adding `opa-abac-mcp` next to Spring AI's MCP server is the whole installation: the starter's
auto-configuration builds every gate bean, decorates what must be decorated, wraps the stateful sync tool
specifications as the example's post-processor did, and fails startup — with a message naming the fix —
when a prerequisite is missing. The example's own wiring shrinks to its catalog declarations, its ceiling
source and the reflective installer.

**Deliverables.**
- **`dev.dmitriikonovalov.opaabac.autoconfigure.OpaMcpAutoConfiguration`**, registered on its **own line** in
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (today: two entries) —
  **not** `@Import`ed by `OpaAbacAutoConfiguration`, which is `@ConditionalOnProperty(opa.abac.enabled)`
  (`:56`): this one carries no `opa.abac.enabled` condition (ADR 0036 §3).
  `@AutoConfiguration(after = OpaAbacAutoConfiguration.class)`; `@ConditionalOnClass(name = {` a module type
  `, "org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration" })` *(class name
  verified in `spring-ai-autoconfigure-mcp-server-common-2.0.0.jar`)*. The starter adds
  `compileOnly(project(":opa-abac-mcp"))` (the keycloak-directory pattern) plus `testImplementation` of the
  module and of the Spring AI MCP server starter for its context-runner tests; Spring AI itself is
  `compileOnly` only if the configuration references a Spring AI type (a `name =` string needs none). Verify
  with `./gradlew :opa-abac-spring-boot-starter:dependencies --configuration compileClasspath` that
  `mcp-core` resolves to T1's explicit version through the module edge.
- **Beans** (each `@ConditionalOnMissingBean` unless stated — an adopter's bean wins):
  `ToolCallAuthorizer`, `ToolRosterFilter`, `DelegationChainExtractor` (`ClaimDelegationChainExtractor` over a
  **private** `JsonMapper` — the `OpaClient` precedent at `OpaAbacAutoConfiguration:71-85`: a claim parser must
  not change with the application's Jackson customizations), `AgentCapabilitySupplier`
  (`ConfigAgentCapabilitySupplier` — the YAML default, ADR 0036 §6), `ActorClaimWiringCheck` (always; it reads
  `opa.abac.subject.attribute-claims` through `Binder.get(environment)` — the `OpaAbacDisabledAutoConfiguration`
  precedent — so it never depends on the starter's properties bean), and `@EnableConfigurationProperties` for
  the module's three properties classes. `ActorClaimWiringCheck` becomes a `SmartInitializingSingleton` (today
  an `InitializingBean` — under lazy initialization it would never run).
- **Three static `BeanPostProcessor`s** (bean-level, the `OpaResolveMemoAutoConfiguration` idiom, so adopter
  beans are decorated too and every consumer shares one instance):
  1. **memo** — any `PrincipalCeilingSupplier` → `MemoizingPrincipalCeilingSupplier`, honoring
     `opa.abac.resolve-memo.enabled` (default on) exactly as the role-definition memo does; any
     `AgentCapabilitySupplier` → `TurnScopedCapabilityCache` (always — the roster and the gate must share one
     answer per turn; today's `AgentCapabilityConfiguration` javadoc), each guarded against double-wrapping;
  2. **policy path** — the context's `PolicyPathResolver` (the starter's `PerTypePolicyPathResolver` or an
     adopter's) is **decorated**, not replaced: resource type `tool` ⇒ `opa.abac.mcp.policy-path` (default
     `agent_tools`, the *package* path — the batch call appends `/bulk`); every other type ⇒ the delegate's
     answer unchanged. The type name `tool` is therefore reserved (ADR 0036 §13). This is one of part 0's three named corrections, and it is invisible to the example (it decides `tool` only): `ToolPolicyPathResolver`
     becomes this decorator; the throw-for-other-types branch goes, because in a library the application's
     other gates share the `OpaClient` *(seam verified: `OpaAbacAutoConfiguration:71-85` builds the one
     `OpaClient` from the context's `PolicyPathResolver` bean, the parameter at `:73`; post-processing
     reaches it whatever the auto-configuration order)*. **Build-breaker — same commit:** the decorator takes a
     delegate, so the moved tests' `new ToolPolicyPathResolver(properties)` (`ToolCallGateTest:163,223,399`,
     `ToolRosterFilterTest:473`) gain a delegate that throws (those tests decide `tool` contexts only — a
     non-`tool` context reaching it is a test failure, the old behavior); no assertion changes;
  3. **the gate** — today's `toolSpecificationGateDecorator` moved: every `List` bean whose elements are
     `McpServerFeatures.SyncToolSpecification` is replaced by a list of `ToolCallGate.gate(…)`-wrapped
     specifications. Two corrections ride along: **every element** is checked (today: element 0 only), and
     the authorizer is fetched lazily through an `ObjectProvider` as today; an element whose handler is
     already a `ToolCallGate` is recorded but not wrapped again *(superseded by the layer-3 review, L1: kept only
     when that gate is bound to the element's own tool name and the context's authorizer — otherwise wrapped again)*. A scoped proxy (a bean with a `scopedTarget.` sibling) is passed
     through untouched — inspecting it outside a request would resolve its target and fail startup; it is an
     unsupported registration either way. **Every other element that exposes a public
     no-arg `tool()` accessor returning `io.modelcontextprotocol.spec.McpSchema.Tool` ⇒ startup failure**,
     naming its type and "supported: `McpServerFeatures.SyncToolSpecification`" — the three other SDK types
     included, until T5 wraps them, so part 0 alone can never serve an async or stateless tool ungated (an
     `ASYNC` server with an empty registry would otherwise pass T4's check with zero wrapped names). The
     context holds two such lists
     (`toolSpecs` from the annotation scanner and `syncTools` from `ToolCallbackConverterAutoConfiguration` —
     both verified by `javap` of the Spring AI configurations; `ToolGateInstallationTest` already asserts
     over both). The other three specification types are T5's.
- **Prerequisites ⇒ startup failure** (ADR 0036 §3; [[00-DESIGN]] §3 table rows 1–2):
  no `OpaClient` bean ⇒ fails, the message naming `opa.abac.enabled` and `OpaClient` — **also** under
  `opa.abac.enabled=false` + `opa.abac.allow-ungated-methods=true` (fork 16); no `PrincipalCeilingSupplier`
  bean ⇒ fails naming the SPI. Fetch both through `ObjectProvider` and throw an `IllegalStateException`
  with that text — a bare constructor dependency would fail with a generic
  `NoSuchBeanDefinitionException` that names neither the property nor the fix. The check is step 1 of the startup verifier below (eager under lazy initialization), and the gate beans
  (`ToolCallAuthorizer`, `ToolRosterFilter`) receive `OpaClient` / `PrincipalCeilingSupplier` — and the `ToolRegistry` — through
  `ObjectProvider.getIfAvailable()` in the auto-configuration's bean methods, never as a hard dependency of an
  eagerly created bean, so the verifier's named failure (prerequisites in step 1, a missing registry in T4's
  step 3) is the first failure an adopter sees, not Spring's `UnsatisfiedDependencyException`.
- **One startup verifier, steps in a fixed order.** A single `SmartInitializingSingleton` in
  `OpaMcpAutoConfiguration` (call it `McpStartupVerifier`) runs, in sequence inside **one** callback: (1) the
  prerequisite check above; (2) **eager list resolution** — every **singleton** bean whose type is assignable to
  `java.util.List` (`getBeanNamesForType(List.class, false, false)` — `includeNonSingletons=false`, so
  prototype and request/session-scoped lists, and scoped targets, are never touched; skip any name for which
  `ScopedProxyUtils.isScopedTarget` holds or that has a `scopedTarget.` sibling), then `getBean` on each, so under
  lazy initialization each specification list is still created — and therefore post-processed: wrapped,
  recorded, or refused as an unsupported type; then **asserts the result**: every element of every resolved list
  that exposes an `McpSchema.Tool tool()` accessor must carry one of the module's gate handlers, else startup
  fails naming the bean — the backstop for a list that never passed the post-processor (one added with
  `registerSingleton` is returned by the lookup but is never post-processed — measured), so no startup specification list serves a tool ungated; (3) T4 adds the declarations check as the third step. The steps
  never live in separate `SmartInitializingSingleton` beans: Spring runs those callbacks in bean-registration
  order (`DefaultListableBeanFactory.preInstantiateSingletons` iterates `beanDefinitionNames`; `@Order` does not
  apply — verified by `javap -c`), so a separate declarations check could run before resolution and fail a valid
  context. Without step (2) a lazy list escapes the post-processor entirely (Spring AI's MCP server configuration
  declares no lazy-init exclusion — verified). **Supported registrations, therefore:** singleton specification-list **bean definitions** (`@Bean` methods or registered definitions) with a `List`
  type (a list added with `registerSingleton` is not a definition and is never post-processed, so step 2's
  assertion fails startup if it carries tools without a gate handler) — Spring AI's own are; prototype- or scope-bound lists, and lists produced by a `FactoryBean`, are unsupported registrations (under lazy initialization an adopter `FactoryBean`'s product is not found by the singleton lookup even when
  its type is predictable — measured; a scoped proxy is skipped because it has a `scopedTarget.` sibling, and the
  scoped target itself because it is not a singleton) — documented with the `addTool`
  limit (T4).
- **The example shrinks:** `IdentityConfiguration` and `AgentCapabilityConfiguration` are deleted;
  `ExampleAuthzConfiguration` keeps only the `TypeLevelPrincipalCeilingSupplier` bean (now un-wrapped — the
  memo BPP decorates it) and the `RosterFilterInstaller` bean with its `example.mcp.authz.roster-filter`
  condition; `ToolRegistrationConfiguration` keeps the `ToolRegistry` (the validator stays until T4).
  `application.yml`'s comments that describe wiring are updated; no key changes.
- **Documentation delta (this commit):** [[AGENT-TOOL-AUTHORIZATION]] gains *Installing the module* — the
  dependency, the auto-configuration, what an adopter must provide (`PrincipalCeilingSupplier`, a
  `ToolRegistry`), what is defaulted (YAML capabilities, the extractor), the reserved `tool` type, and the
  startup-failure rows that exist after this ticket; the CHANGELOG line (the starter's new
  auto-configuration, its prerequisites, and that `opa.abac.enabled=false` does not turn it off).

**Acceptance.** **U9**, **U10**, **U11**, **U12**, **U13**, **U14**, **U15**, **U16**, **U35**, **U36** ·
**I1**. Starter tests via `ApplicationContextRunner` (the I3a–I3c slice-test idiom of
`OpaDirectoryAutoConfiguration`), every fixture starting from the QA doc's baseline. Non-happy
paths: U10/U11 (the two prerequisite failures, U10 also under the ADR 0038 acknowledgment), U12 (adopter beans
win), U13 (a non-`tool` context still reaches its own per-type path), U16 (the wiring check fails on a missing
claim even though the starter's properties bean is never consulted).

**NOT to touch.** `OpaAbacAutoConfiguration` / `OpaResolveMemoAutoConfiguration` / `OpaAbacDisabledAutoConfiguration`
— the new configuration stands beside them; no existing bean definition or condition changes. Only stateful
sync specifications are wrapped (part 0 = today's behavior). No new denial code. `RosterFilterInstaller`
stays in the example and is **not** auto-configured (ADR 0036 §5 — no private-field reflection in a
published jar).

---

## T4 — declarations validated against what was wrapped; part 0 closes on the rig

**Goal.** The tool registry is checked against the specifications the gate actually wrapped — both directions,
at startup — replacing the annotation-scanning validator; the dropped classifier SPI is gone; and the
extracted gate is proven on the live rig with the agent matrix's E1–E11 unchanged.

**Deliverables.**
- **Wrap-time validation** (module + starter): the gate post-processor records every tool name it wraps
  (across **all** specification lists — the union), and T3's `McpStartupVerifier` compares that set with the `ToolRegistry` as its **step 3** — after its own step 2 resolved every singleton list, in the same callback, so a
  lazily-initialized list is wrapped and counted rather than missed and no callback ordering is relied on:
  advertised-but-undeclared ⇒ startup failure; declared-but-never-wrapped ⇒ startup failure; the message
  carries both sorted lists (ADR 0036 §9). No `ToolRegistry` bean ⇒ startup failure naming it (a gate with no
  declarations would deny every call as `tool-undeclared` — fail-closed, but a misconfiguration to name at
  boot). An empty registry and zero wrapped tools ⇒ starts (a server with no tools).
- The call-time `tool-undeclared` deny stays (moved test, U18). ADR 0036 §13 corrects [[00-DESIGN]] §6's
  "backstop for anything registered after startup": **with the auto-configuration, supported registrations are the startup singleton specification-list beans**
  (T3: prototype- or scope-bound lists, and lists produced by a `FactoryBean`, are unsupported) — a hand-built specification is supported by contributing it
  as such a bean (un-gated; the post-processor wraps and records it). A specification registered any other
  way — `addTool` at runtime, or one a customizer adds to the server builder (customizers run before the
  server is built, but outside the list beans) — is not wrapped and not in the wrapped set: unsupported,
  ungated, and documented as such; declaring it would fail the startup check. The direct API
  (`ToolCallGate.gate`) stays public for wiring without the auto-configuration. The call-time deny remains
  the backstop for a wrapped specification whose name the registry lost.
- **Build-breaker — same commit:** none if T3's fixtures followed the QA baseline (lists only together with
  their declarations, from T3 on); any T3 fixture whose registry and specification lists differ in either
  direction now fails the check and is paired in this commit. T4 extends T3's verifier rather than adding a
  second startup bean.
- **Deleted:** the example's `ToolRegistryValidator` + `ToolRegistryValidatorTest` (replaced by U17);
  `ToolCallClassifier` (ADR 0036 §11: not in v1) and `McpToolSurfaceTest`'s classifier case
  (`shipsTheClassifierSeamWithNoImplementation`) — **retired, not replaced**: the seam it pinned no longer
  exists. `McpToolSurfaceTest`'s other cases stay.
- **Documentation delta (this commit):** [[AGENT-TOOL-AUTHORIZATION]] — the declarations paragraph
  (registry, both-directions startup check, the call-time backstop, the post-startup `addTool` limit, and
  T3's unsupported list registrations — prototype- or scope-bound lists, lists produced by a `FactoryBean`, and
  lists added with `registerSingleton`);
  the fail-closed table's tool-metadata row ("deny at registration time — startup fails, the tool is never
  exposed") gains "…checked against the specifications actually wrapped, both directions".
- **Part-0 close:** the layer-2 review record in this STATUS (flow guide §4a).

**Acceptance.** **U17**, **U18**, **U19**, **U37** · **E1**. Non-happy paths: U17's two mismatch directions and the
missing-registry failure; E1 runs the agent matrix through the gateway against an image **rebuilt** from this
commit (`ENABLE_MCP=1 ./deploy.sh build`, then `up` — `up` builds the MCP image only when it is absent,
`deploy.sh:411-416`), with the collection file **unchanged since the slice began**
(`git diff --exit-code "$(git merge-base main HEAD)" HEAD -- scripts/postman/agent-tool-matrix.postman_collection.json`).
The runner's advisory NOTE about `opa-abac-spring-boot-starter` changing (`:327-337`) is **expected** from T3
on — the change is additive — and STATUS-04 says so.

**NOT to touch.** `scripts/postman/agent-tool-matrix.postman_collection.json` and the runner's cells — part 0's
bar is "unchanged". No new specification type, no new denial code, no policy change.

---

## T5 — the gate on all four specification types; reactive and unwrappable configurations fail startup

**Goal.** Whatever MCP server shape an adopter picks on Spring MVC — sync or async, stateful or stateless —
every tool is gated, and a configuration the module cannot gate refuses to start rather than serving tools
ungated.

**Deliverables.**
- **Three wrappers** in `…opaabac.mcp.authz` beside `ToolCallGate` (the sync stateful one), one per remaining
  record *(signatures verified by `javap` on `mcp-core-2.0.0.jar`)*:
  `McpServerFeatures.AsyncToolSpecification(Tool, BiFunction<McpAsyncServerExchange, CallToolRequest,
  Mono<CallToolResult>>)`, `McpStatelessServerFeatures.SyncToolSpecification(Tool,
  BiFunction<McpTransportContext, CallToolRequest, CallToolResult>)`,
  `McpStatelessServerFeatures.AsyncToolSpecification(Tool, BiFunction<McpTransportContext, CallToolRequest,
  Mono<CallToolResult>>)`. Each **decides when its `BiFunction` is invoked** — on the calling thread, before
  any `Mono` exists — then returns the denial (`Mono.just(advisory)` for the async ones) or the delegate's
  result; the decision is never re-taken inside the `Mono`. **The async result contract, pinned:** on allow the
  wrapper returns the delegate's `Mono` **decorated**, not replaced — an emitted `ToolInvocationException`
  becomes the same advisory a synchronous throw produces; an error result is relabelled from
  `ToolFailureRecord` read **at emission** (the tool body runs at subscription — Spring AI's
  `AsyncMcpToolMethodCallback` defers it — so the record cannot exist at `apply`); request attributes absent at
  emission ⇒ the result passes through unrelabelled; any other emitted error and an empty completion pass
  through **unchanged**, exactly as without the gate (authorization already happened; the gate is not a result
  validator). Nothing in the decoration can turn an error into a success. Only the *authorization* reads of
  thread-bound state are confined to `apply`.
- **The gate post-processor covers all four** element types (a `List` bean of any of them), with the T4
  name-collection spanning all of them. T3's unsupported-type rule narrows to "not one of the four", and step 2's gated-element assertion accepts all
  four wrappers as gate handlers; its
  three SDK-type cases in U35 are rewritten into this ticket's wrapping assertions (same commit — by design).
- **Unwrappable ⇒ startup failure** (T3's rule, now with all four supported): a `List` bean any of whose
  elements exposes a public no-arg `tool()` accessor returning `io.modelcontextprotocol.spec.McpSchema.Tool`
  but is **not** one of the four records ⇒ fails, naming the element type (the rule is the accessor, because
  every SDK specification record has it — a future fifth type is caught without knowing its name). Checked
  on **every** element.
- **Reactive web application ⇒ startup failure** naming WebFlux as out of scope for v1 (ADR 0036 §4): a nested
  configuration under `@ConditionalOnWebApplication(type = REACTIVE)` registering a `SmartInitializingSingleton` that throws (eager under lazy initialization — its order relative to the
  verifier is irrelevant because U24's fixture is otherwise complete, so it is the only check that can fail; the shape [[00-DESIGN]] measure item 2 named). **Test
  prerequisite:** Boot matches `REACTIVE` only when `org.springframework.web.reactive.HandlerResult`
  (`spring-webflux`) is loadable (`OnWebApplicationCondition`, verified by `javap -c`), so U24 needs
  `testImplementation("org.springframework:spring-webflux")` (Boot-managed) on the starter's tests — and that
  artifact is **not** in the Gradle cache, which a sandboxed Gradle cannot fill on this host (PKIX, see T8).
  Same protocol as T8: try; if the fetch fails on certificate validation, record
  `**LOCAL PROOF DEFERRED:** PKIX — spring-webflux` in STATUS-05 with the exact command for the maintainer and
  continue — a known host limit, **not** a blocker. The proof is then the maintainer's out-of-sandbox run or
  CI on the PR to `main`; the main code ships regardless.
- **Non-web (no servlet) application** — e.g. an stdio MCP server: starts; with no authenticated principal on
  the thread every call is denied `tool-gate-unauthenticated`. Deny-all, never inert (ADR 0036 §13).
- **Per-type integration tests on Spring MVC** (`@SpringBootTest(webEnvironment = RANDOM_PORT)`, a real
  `POST /mcp` over HTTP — never MockMvc, the AGENT-TOOL-AUTHZ T5 lesson: only a real request exposes the
  async re-dispatch), one per configuration `spring.ai.mcp.server.type` ∈ {`SYNC`,`ASYNC`} ×
  `spring.ai.mcp.server.protocol` ∈ {`STREAMABLE`,`STATELESS`}, each with a test-scoped tool bean of the right
  shape (an `ASYNC` server only scans reactive-returning `@McpTool` methods — `Mono`, `Flux` or a `Publisher`,
  per `McpPredicates.isReactiveReturnType`; the ITs use `Mono`) and
  an in-process `HttpServer` OPA stub recording the input: the identity is visible **where the gate decides**
  (the recorded input carries the caller's subject id), a stub deny returns the structured
  `{layer: tool-gate, code: tool-gate-denied}` with the tool body never invoked, and a stub allow reaches it.
  **The proof obligation is binding** (ADR 0036 §4): a type whose IT cannot show the identity at the decision
  point is **not shipped as supported** — it moves to the unwrappable row (startup failure for that
  configuration), the IT asserts that failure instead, and STATUS + ADR 0036 §13 record which type and why.
  **These ITs live in `opa-abac-spring-boot-starter/src/test`** beside U9–U17 — the gate is installed by the
  starter's auto-configuration and the bearer read by its `AbacFilter`, so that is where a real wired server
  exists (the `opa-abac-keycloak-directory` precedent: an optional module's wiring tests live in the starter).
  A minimal test application there; the starter gains `testImplementation` of the Spring AI MCP webmvc
  starter. The module's own tests stay free of Spring AI and of the starter.
- **Pre-measured at decomposition (2026-10-09 spike, real `POST /mcp`, throwaway code deleted):** in all four
  configurations the specification's handler was invoked on the Tomcat request thread with the
  `AbacAuthentication` in `SecurityContextHolder` and the request attributes bound — for the async types also
  at subscription. The synchronous servers get there because Spring AI sets `immediateExecution(true)` on
  servlet: the stateful one through `servletMcpSyncServerCustomizer`, the stateless one inside
  `mcpStatelessSyncServer` when the environment is a `StandardServletEnvironment` (no property controls it).
  So all four are **expected** to pass — the ITs are what make that a shipped claim. Also measured: an
  `ASYNC` server's scanner ignores the example's sync `@McpTool` methods (`toolSpecs` size 0), and the
  example's `RosterFilterInstaller` fails startup on `STATELESS` (no streamable transport provider) — neither
  is changed here; both go into the guide.
- **CHANGELOG** line: all four types gated; WebFlux and unwrappable types fail startup; stdio is deny-all.
- **Documentation delta (this commit):** [[AGENT-TOOL-AUTHORIZATION]] *Supported configurations* — the four
  types and which are supported (from the ITs), WebFlux out, stdio deny-all, the post-startup `addTool` limit,
  `ASYNC` servers needing reactive-returning tools, the registration paths T4 pinned, and the customizer trap: `mcpSyncServer` injects a single
  `Optional<McpSyncServerCustomizer>` and Spring AI's `servletMcpSyncServerCustomizer` (the one that sets
  `immediateExecution(true)`) is **not** `@ConditionalOnMissingBean` — a second customizer fails startup with
  `NoUniqueBeanDefinitionException`, and a `@Primary` one that does not set `immediateExecution(true)` moves
  every handler to `boundedElastic` with no identity, so every call is denied (measured; I6 pins it).

**Acceptance.** **U20**, **U21**, **U22**, **U23**, **U24**, **U25** · **I2**, **I3**, **I4**, **I5**, **I6**.
Non-happy paths: every wrapper's deny path (body never invoked), U23/U24 (startup failures), U25 (deny-all),
I6 (a `@Primary` customizer that drops `immediateExecution` yields `tool-gate-unauthenticated` on every call —
never an allowed call without identity).

**NOT to touch.** `ToolCallAuthorizer`'s decision and codes (the wrappers call it, unchanged). No identity
carrier other than the thread-bound one (`McpTransportContext` is the planned successor, not this slice's).
No change to Spring AI's beans — the module post-processes lists, it never replaces a transport or server
bean. The example's server stays `SYNC` + `STREAMABLE`.

---

## T6 — the `agent-clients` guard: an agent client without an actor is refused

**Goal.** An operator can name the OAuth clients that are agents; a token from one of them that carries no
actor claim is denied as a misconfigured agent client instead of being evaluated as a human call.

**Deliverables.**
- `IdentityProperties` gains `agent-clients` (`List<String>`, default empty) under `opa.abac.mcp.identity`.
- **In the delegation step** (`ToolCallAuthorizer.buildContext`, after the extractor ran): the caller's `azp`
  (read from `subject.attributes`, where the starter copies it when `azp` is in
  `opa.abac.subject.attribute-claims`) is in the list **and** the chain is not an agent call ⇒ a new
  `ActorRequiredException` (module, `…mcp.identity`, *not* a `DecisionIndeterminateException` — it is a
  deterministic deny, not "could not decide") ⇒ `ToolCallAuthorizer` denies with **`tool-gate-actor-required`**
  (layer `tool-gate`), logged at WARN with its own line. A malformed claim still lands on
  `tool-gate-identity-unreadable` (the extractor runs first). An empty list ⇒ the check never runs —
  byte-identical contexts to today. **The guard runs regardless of `opa.abac.mcp.agent-gate.enabled`**: it
  is an identity check (does this client's token carry the actor it must?), like the malformed-claim deny the
  extractor keeps under OFF (`ToolCallAuthorizer:161-163`), not an agent-narrowing conjunct — so with the
  agent gate OFF a listed client without an actor is still `tool-gate-actor-required`.
- **The roster:** the same exception at list time ⇒ **unfiltered + WARN** — the identity-edge class the design's
  fail-closed table already assigns to edges outside the batch; the call-time gate denies every tool.
- **`ActorClaimWiringCheck` extended:** `agent-clients` non-empty and `azp` absent from
  `opa.abac.subject.attribute-claims` ⇒ startup failure naming `azp` and both properties.
- **The e2e cell** — matrix cell **E12**, a rig drill (the E7/E11 mechanism the runner already has): recreate
  the MCP pod with `OPA_ABAC_MCP_IDENTITY_AGENT_CLIENTS=catalog-gateway` and
  `OPA_ABAC_SUBJECT_ATTRIBUTE_CLAIMS=act_chain,azp`, call a tool with demo's ordinary `catalog-gateway` token
  (no actor mapper on that client — verified in `infra/keycloak/realm-export.json`) ⇒ the advisory's
  `code` is `tool-gate-actor-required`; then a `catalog-agent-readonly` token on the same pod still gets its
  normal agent outcome (a listed-only-what-must-be guard); the EXIT trap restores the default pod. No realm
  change. Compose gains the two interpolated variables with defaults that leave today's pod unchanged; the
  ticket **verifies by a binder test** what an empty default binds to (an empty list, never `[""]` — a
  `[""]` would trip the wiring check at boot, so a wrong default is loud, not silent).
- **Documentation delta (this commit):** [[AGENT-TOOL-AUTHORIZATION]] — the claim-state table gains the
  "`azp` listed, no actor" row; the codes list gains `tool-gate-actor-required`; the residual ("a user handing
  their own token to an agent is a human call, bounded by the human's ceiling", ADR 0036 §8); the matrix
  section gains E12; `scripts/postman/README.md` registers the drill; the CHANGELOG line for
  `opa.abac.mcp.identity.agent-clients`.

**Acceptance.** **U26**, **U27**, **U28**, **U29** · **E2**. Non-happy paths: U27 (the deny and its precedence
below the malformed-claim deny), U28 (the wiring-check failure), U29 (roster degradation), E2 (the live deny).

**NOT to touch.** The human path for an unlisted client (U26). `agent_tools.rego` (the guard is a PEP-side deny;
the policy never sees the call). The realm. `check-collection-conformance.py` and `check-shell-guards.py` must
stay clean on the touched collection and runner.

---

## T7 — input schema v1: the jar marks every tool-gate input, the policy refuses what it does not know

**Goal.** From v1 on, a policy and a jar that disagree on the input version deny instead of misreading a moved
field: every tool-gate input carries `environment.schema = "opa-abac.mcp.tool-gate/v1"`, and `agent_tools.rego`
grants nothing without a version it knows. **What this cannot do:** a policy written *before* v1 ignores the
marker — the guarantee starts with v1-aware policies. That pairing is safe for 1.5.0 because v1 *is* today's
field layout (nothing moved), and the upgrade note says to deploy the v1-aware policy with the 1.5.0 jar, so
the first incompatible change (v2) meets a policy that refuses it (ADR 0036 §7, clarified in §13).

**Deliverables.**
- `ToolCallAuthorizer.buildContext` sets `environment = {"schema": "opa-abac.mcp.tool-gate/v1"}` on every
  context (today `Map.of()`), so single decisions **and** every roster batch item carry it (the bulk request
  serializes each item's full `AbacContext` — `HttpOpaClient` `BulkItems(List<AbacContext>)`, read in source).
  The constant is public API (`ToolGateInput.SCHEMA_V1` or equivalent).
- `agent_tools.rego`: a `known_schema` rule (`input.environment.schema in {"opa-abac.mcp.tool-gate/v1"}`)
  required by `is_tool_request`, so both `allow` rules and, through `allow with input as item`, every `bulk`
  position refuse an unknown or missing marker.
- `agent_tools_test.rego`: the shared `request(…)` helper (`:76-82`) **and** the two hand-built inputs
  (`:318`, `:327`) carry the v1 marker, so every existing deny test still denies **for its own reason** (an
  input without the marker would make each negative test pass vacuously); new cases: unknown version ⇒ deny
  for a human and for an agent; marker missing ⇒ deny for both; `environment` absent ⇒ deny; a bulk with one
  unmarked item ⇒ that position `false`, the others unchanged.
- **One commit for jar + policy** — splitting them leaves a commit where the rig denies every call.
- **Documentation delta (this commit):** [[AGENT-TOOL-AUTHORIZATION]] *The input contract (v1)* — the field
  list (subject id/roles; `subject.attributes.{actor, chain, agent_capability}`; `role_definition`;
  `resource.type = tool`, `resource.id` = the tool name, `resource.attributes.{category, risk_tags,
  target_type}`; `action`; `environment.schema`), the rule that an incompatible change bumps the version, and
  that the actor stays at `subject.attributes.actor` in v1 (ADR 0036 §7), and that the guarantee holds for
  v1-aware policies only; the fail-closed table gains the schema row; the CHANGELOG line with the **upgrade
  note**: deploy the v1-aware `agent_tools.rego` together with the 1.5.0 jar (an older copy of the policy keeps
  working with v1 input, but cannot refuse a future version).

**Acceptance.** **U30**, **U31**, **U32** · **E3**. Non-happy paths: U31 (unknown / missing / absent ⇒ deny),
U32 (deleting the guard fails a named test — run the mutation, record the failing test in STATUS-07),
E3 (the rig with the marker in place: the agent matrix E1–E12 green, the policy restarted after the edit).

**NOT to touch.** Every other `infra/opa/policies/*` file. The decision rules' logic (only the request guard
changes). The actor's location in the input (v1 keeps `subject.attributes.actor`).

---

## T8 — Boot 4.1.x tested in CI, and the slice close-out

**Goal.** The supported range in ADR 0036 §10 is tested, not asserted: the build job re-runs the module's,
the starter's and the example's tests on the newest Boot 4.1.x; and the slice's living docs say what shipped.

**Deliverables.**
- `gradle/libs.versions.toml` gains `springBootCompat = "4.1.1"` (Boot 4.1.1 GA — the newest 4.1.x at
  decomposition; [[00-DESIGN]] measure item 3) with a comment naming its single purpose.
- A Gradle property `springBootLine` (`compat` ⇒ `springBootCompat`; absent ⇒ `springBoot`), read by every
  `mavenBom("org.springframework.boot:spring-boot-dependencies:…")` import (`opa-abac-spring-security`,
  `opa-abac-spring-data`, `opa-abac-keycloak-directory`, `opa-abac-spring-boot-starter`, `opa-abac-mcp`) and
  by an explicit import in `example-mcp-server` (its Boot plugin's own BOM import is the plugin version,
  4.0.8). Default build byte-identical in resolution (proved by `dependencyInsight`).
- `.github/workflows/ci.yml`: a **step inside the existing `build` job** (after "Build and test (unit +
  Testcontainers integration)") running `./gradlew :opa-abac-mcp:test :opa-abac-spring-boot-starter:test
  :example-mcp-server:test -PspringBootLine=compat --no-daemon` — the starter's tests carry the
  auto-configuration's startup-failure cases and T5's per-type ITs. No new job, no renamed job id — the
  `main` ruleset's five required checks are unchanged. The workflow triggers on pushes to `main` and on pull
  requests targeting `main` only (`ci.yml:3-7`) — a feature-branch push runs nothing.
- **Local proof and the PKIX gotcha:** the 4.1.x artifacts are not in the Gradle cache (measured: cached
  `spring-boot-dependencies` = 4.0.5/4.0.7/4.0.8), and a sandboxed Gradle cannot fetch new artifacts on this
  host (the host JDK's truststore; `RELEASING.md` §4, Mulch `opa-abac-publish`). If the in-sandbox run fails
  on certificate validation, record `**LOCAL PROOF DEFERRED:** PKIX — Boot 4.1.1` in STATUS-08 with the exact
  command for the maintainer (outside the sandbox, keychain `JAVA_TOOL_OPTIONS` + `--no-daemon`) — this is a
  known host limit, **not** a blocker; the proof is then the maintainer's out-of-sandbox run or the CI step on
  the PR to `main`, and the docs this ticket writes say the line is *tested in CI* — never that a local run
  passed unless STATUS-08 records one. A **test** failure on 4.1 is a real finding and is fixed in this ticket.
- **Close-out docs:** [[POC-ROADMAP]] (Phase 9's exit criterion delivered: `opa-abac-mcp`, incubating);
  [[USER-STORIES]] Epic J — J5's roster wording reconciled with ADR 0037 (an engine outage now yields the
  **empty** roster), J7–J9 ticked; ADR 0036 status → *Accepted (implemented)* and the ADR index row;
  [[AGENT-TOOL-AUTHORIZATION]] *Supported versions* (MCP SDK 2.0.x + Spring AI 2.0.x on Boot 4.0.x and 4.1.x,
  both tested; Spring AI 2.1 out until GA); `example-mcp-server` build-file comment updated (it now consumes
  the module); `CLAUDE.md` gains the `-PspringBootLine=compat` command; the CHANGELOG `## [Unreleased] —
  1.5.0` section reconciled (one coherent entry for the module beside ADR 0038's; the supported range; the
  heading itself is changed only by the release PR); follow-ups the slice leaves (a supported `tools/list`
  installer when java-sdk #578 ships, the transport-context identity carrier, any finding deferred by the run)
  become `ENGINEERING-BACKLOG` items **from 21** (item 20 is taken). Releasing 1.5.0 — ADR 0038 plus this
  slice, one cut (maintainer decision 2026-10-09; no 1.4.1) — is the maintainer's step after merge, on the
  `RELEASE-1.4.0` package's shape.

**Acceptance.** **U33**, **U34**. Non-happy path: U34 (the default line still resolves 4.0.8 — the compat
property cannot leak into the default build).

**NOT to touch.** The repo's Boot pin (`springBoot = "4.0.8"`). Any job name in `ci.yml`. The `main` ruleset.
Releasing 1.5.0 (a separate maintainer step after merge).

---

## Cross-cutting acceptance (the whole slice)

- `./gradlew build` green; `opa test infra/opa/policies` green (451 + T7's new cases); the local Sonar scan
  CLEAN on every changed `.java` (`ml prime quality-gate-sonar` before judging).
- `scripts/checks/check-shell-guards.py` and `scripts/checks/check-collection-conformance.py` clean.
- `scripts/planning/verify-package.sh MCP-AUTH-LIBRARY` stays green as STATUS notes fill in.
- **Every reader of a switch agrees** (the AGENT-TOOL-AUTHZ QA lesson): `opa.abac.mcp.agent-gate.enabled` is
  read once per decision path and threaded to both the call path and the roster (unchanged mechanism — T2
  keeps it); `agent-clients` is read by the authorizer, the roster and the wiring check, and U27–U29 cover all
  three — including that the agent-gate switch does not turn the guard off; `opa.abac.resolve-memo.enabled` is
  read by the role-definition memo and the ceiling memo alike (U14).
- No active binding, configuration or living doc uses a retired name:
  `git grep -nE 'example\.mcp\.(identity|agents)|example\.mcp\.authz\.(agent-gate|policy-path)|EXAMPLE_MCP_(IDENTITY|AGENTS|AUTHZ_AGENT_GATE|AUTHZ_POLICY_PATH)' -- ':!docs/to-do/implemented' ':!docs/to-do/planning/MCP-AUTH-LIBRARY' ':!docs/architecture/adr' ':!.mulch' ':!CHANGELOG.md' ':!*BindingTest.java'`
  returns nothing (the exclusions are history, this package's own migration text, the changelog's rename note,
  and U4's deliberate old-prefix binding test — name that test class `*BindingTest`). The grep sees dotted
  and env-var forms only — nested YAML is covered by U4/U38's `application.yml` binding check.
