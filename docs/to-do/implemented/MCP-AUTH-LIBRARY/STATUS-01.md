---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/spring
  - area/mcp
---

# STATUS — T1: the `opa-abac-mcp` module; the identity kernel + tool declarations move

**Status:** ✅ DONE (2026-10-10, autonomous — part 0 runner)

## What shipped

- **Module `opa-abac-mcp`** (`settings.gradle.kts` library block; the example block's "no library module
  changes" sentence replaced). Build on the keycloak-directory pattern: `java-library` + the Boot BOM import;
  `api(project(":opa-abac-spring-security"))`; **`api(libs.mcp.core)`** with the new catalog entry
  `mcpSdk = "2.0.0"` (comment: moves with `springAi`) and library `mcp-core`; Spring Web + Spring Security
  core + slf4j declared by the module itself (`implementation`); `spring-boot` + the configuration processor
  for binding metadata only — **no beans declared**. `package-info.java` for the root, `identity` and `tool`
  packages (incubating statement), and `opa-abac-mcp/README.md`.
- **Packaging.** BOM constraint; root `publishableJarModules` allow-list; `opa-abac-mcp/gradle.properties`
  (`POM_ARTIFACT_ID`/`POM_NAME`/`POM_DESCRIPTION`); every coordinate count moved six → seven (libraries five
  → six): `RELEASING.md` (:9, :14, the coordinate block, :147, :160, :163, :169, :186, :266),
  `build.gradle.kts` ("six publishable library modules"), `settings.gradle.kts` ("7th published coordinate"),
  `README.md` (count + brace list), `gradle/libs.versions.toml` ("six library modules + the BOM"),
  `opa-abac-bom/build.gradle.kts` ("7th coordinate"). PIT wiring untouched.
- **Moves** to `dev.dmitriikonovalov.opaabac.mcp.identity` / `…mcp.tool` (`git mv`, package + import lines):
  the twelve identity classes and the five tool classes the ticket lists.
- **The three non-plain changes:** (1) `IdentityProperties` → `opa.abac.mcp.identity`,
  `AgentCapabilityProperties` → `opa.abac.mcp.agents` (list shape kept); (2) `ActorClaimWiringCheck(IdentityProperties,
  List<String>)` — no starter type; messages name `opa.abac.mcp.identity.actor-claim`; a `null` list reads as
  empty (fails, never passes); the example's `IdentityConfiguration` passes
  `opaAbacProperties.getSubject().getAttributeClaims()` until T3; (3) `AgentCapabilityUnavailableException
  extends DecisionIndeterminateException`.
- **Example consumes the module** (`implementation(project(":opa-abac-mcp"))`); every import repointed;
  `RosterFilterSmokeCheckTest`'s fully-qualified names follow (build-breaker, same commit).
- **Rig + config rename:** `application.yml` (`example.mcp.{identity,agents}` → `opa.abac.mcp.{identity,agents}`,
  nested under `opa.abac`); `infra/compose.mcp.yaml`'s fifteen `EXAMPLE_MCP_AGENTS_PROFILES_*` →
  `OPA_ABAC_MCP_AGENTS_PROFILES_*` (same suffixes and `${MCP_READONLY_*}` interpolations); `McpServerProperties`
  javadoc; `run-agent-tool-matrix.sh`'s E5 operator check now greps `opa-abac-mcp/src/main/java` too.
- **Docs:** module README; root README (layout tree row, the "Phase 9 did not ship agent support" paragraph
  points at the module); `AGENT-TOOL-AUTHORIZATION.md` (intro: packaged as a module; the `opa.abac.mcp.identity.*`
  and `opa.abac.mcp.agents.profiles[]` property names; the outage's family membership); root `CLAUDE.md`
  (*Layout* line; the MCP-server section points at the module); `CHANGELOG.md` entry under the existing
  `## [Unreleased] — 1.5.0` heading (the module, its coordinate, the family change, the property rename).

## Tests

- Moved with unchanged bodies (package/imports only): `ClaimDelegationChainExtractorTest`,
  `DelegationChainBoundaryTest`, `AgentCapabilitySupplierTest`, `AgentCapabilityWireContractTest` (its
  `locate(…agent_tools.rego)` still resolves from the module directory), `TurnScopedCapabilityCacheTest`,
  `ToolDescriptorTest`, `ToolRegistryTest`. None remains in `example-mcp-server` (**U1**).
- `ActorClaimWiringCheckTest` adapted to the new constructor (`List<String>` in place of `OpaAbacProperties`),
  assertions kept; the fail case additionally asserts both property names; one new case: a `null` claim list
  fails (**U2**). The test imports nothing from `…autoconfigure`.
- **U3:** new `AgentCapabilitySupplierTest#theCapabilityOutageJoinsTheCouldNotDecideFamily`; the moved
  `TurnScopedCapabilityCacheTest` outage cases still replay the outage.
- **U4:** new `McpPropertiesBindingTest` (module: the new prefixes bind, the retired ones leave the defaults,
  a dotted actor id round-trips through the list shape) and `ApplicationYamlBindingTest` (example: its own
  `application.yml` binds `opa.abac.mcp.identity` / `opa.abac.mcp.agents.profiles[*]` and nothing under
  `example.mcp.identity` / `example.mcp.agents`).
- `./gradlew :opa-abac-mcp:test :example-mcp-server:test` green — **62** (module) + **84** (example), 0 failures;
  `./gradlew build` green.
- `./gradlew :opa-abac-mcp:generatePomFileForMavenPublication`: the POM lists
  `io.modelcontextprotocol.sdk:mcp-core:2.0.0` at **compile** scope, `opa-abac-spring-security` compile, web /
  security-core / spring-boot / slf4j runtime — **no Spring AI artifact**. `:opa-abac-mcp:javadoc` builds.

## Architecture review + refactor

Inline ★ review (single agent, this ticket's diff):

- **Fail-closed:** the only logic change is `ActorClaimWiringCheck`'s input. A `null` claim list now reads as
  empty and fails — never a pass (pinned). The family re-parent changes no outcome: `ToolCallAuthorizer` and
  `ToolRosterFilter` (still in the example) catch `AgentCapabilityUnavailableException` **before**
  `DecisionIndeterminateException`, `TurnScopedCapabilityCache` catches its own type, and no other reader of
  the family (the 503 advice, the role memo) ever sees a capability outage — it never escapes the authorizer.
- **Security:** the property rename is the widening risk (a half-renamed rig would boot with no profiles —
  every agent authoritative-empty, a deny, not a widening; and the wiring check would fail a renamed claim).
  The yml, compose and binding tests move together in this commit; U4 pins both prefixes.
- **Boundary / module-layer:** `opa-abac-core` untouched; no existing module's public API changed; the module
  declares no beans and depends on neither the starter, the example nor Spring AI (POM verified).
- **Javadoc in moved classes** that named example-only types: `ToolErrorLayer`'s `{@link CatalogApiErrorTranslator}`
  became prose (it would not resolve in the module); ticket-number asides ("(T3)", "(T4)") dropped.
- **Static analysis:** local Sonar on the changed files — one real finding, fixed: an unused import my import
  rewrite added to `ToolRegistryValidator` (S1128). The remaining 39 are pre-existing findings in moved or
  import-touched **test** files, every one in a documented by-design FP class (`quality-gate-sonar`
  mx-302e78): S5778 ×31, S5853 ×4, S2925 ×2, S125 ×1 (prose), S5976 ×1 — not re-fixed, per the part-0 rule.
- Nothing else substantive.

## Integration / e2e

None for T1 (no IT/e2e named; the rig run is T4's E1). `./gradlew build` (all modules, incl. the example's
`@SpringBootTest` classes and the Testcontainers ITs) is green.

## Decisions

- `ActorClaimWiringCheck` stays an `InitializingBean` in T1 (T3 makes it a `SmartInitializingSingleton`).
- The module's test classpath uses `spring-test` + `jakarta.servlet-api` (for `MockHttpServletRequest`) and
  `logback-classic` at runtime, rather than `spring-boot-starter-test` — the keycloak module's lean pattern.
- README "module table" = the layout tree in the root README (there is no separate table); the row was added there.

## Commit

This commit — `refactor(mcp): the opa-abac-mcp module; the identity kernel and tool declarations move (MCP-AUTH-LIBRARY T1)`.
