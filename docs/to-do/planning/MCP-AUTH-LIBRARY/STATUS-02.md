---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/spring
  - area/mcp
---

# STATUS — T2: the decision moves, onto `PrincipalCeilingSupplier`

**Status:** ✅ DONE (2026-10-10, autonomous — part 0 runner)

## What shipped

- **Moves** to `dev.dmitriikonovalov.opaabac.mcp.authz` (`git mv`): `ToolCallAuthorizer`, `ToolCallGate`,
  `ToolRosterFilter`, `RosterDecision`, `ToolAuthorizationDecision`, `ToolPolicyPathResolver` (as is — still
  throws for a non-`tool` type), `ToolAuthorizationProperties`. `buildContext` and the `CODE_*` constants stay
  package-private; `RosterDecision`, `ToolRosterFilter.decide()` / `apply(…)` stay public and the example's
  `RosterFilterInstaller` reaches them across the module boundary unchanged. Javadoc that named example-only
  types became prose.
- **`ToolAuthorizationProperties` → `@ConfigurationProperties("opa.abac.mcp")`**, `agent-gate.enabled` +
  `policy-path` only. New example class **`ExampleAuthzProperties`** (`example.mcp.authz`) carries
  `role-source.*` and `roster-filter.enabled` under their unchanged names.
- **The SPI `PrincipalCeilingSupplier`** (`Optional<RoleDefinition> lookup(String subject, String resourceType)`)
  with the full ADR 0036 §6 contract in its javadoc (union of grants / denial only if universal; over- allowed,
  under- forbidden; empty = authoritative no-role; an outage throws a `DecisionIndeterminateException` subtype;
  no default; the governing-root implementer's note; why not `RoleDefinitionSupplier`).
- **`ToolCallAuthorizer`** takes the SPI and attributes a ceiling outage **by call site**: the ceiling lookup
  (`resolveCeiling`) re-throws any family member as the package-private `CeilingUnavailableException` (itself a
  family member), which `authorize` maps to `tool-gate-ceiling-unavailable`. Every other code and the catch
  order are unchanged; the roster (which catches the family → unfiltered) is unaffected.
- **`MemoizingPrincipalCeilingSupplier`** (module): the ADR 0023 single-lookup memo, key `(subject, resourceType)`,
  resolved / empty / family outage memoized and replayed, other exceptions un-memoized, no request ⇒
  pass-through, bookkeeping failure ⇒ pass-through.
- **The example:** `TypeLevelRoleDefinitionSupplier` → **`TypeLevelPrincipalCeilingSupplier`** (implements the
  SPI only; `RoleResolutionException` stays its outage signal). `ToolAuthorizationConfiguration` →
  **`ExampleAuthzConfiguration`**, still wiring every bean; it wraps the ceiling source in the memo **honoring
  `opa.abac.resolve-memo.enabled`** (default on; `false` ⇒ the raw supplier).
- **Rig + config rename:** `application.yml` `example.mcp.authz.{policy-path,agent-gate}` → `opa.abac.mcp.*`;
  `infra/compose.mcp.yaml` `EXAMPLE_MCP_AUTHZ_AGENT_GATE_ENABLED` → `OPA_ABAC_MCP_AGENT_GATE_ENABLED` (still
  `${MCP_AGENT_GATE_ENABLED:-true}`); the runner's header comment. `EXAMPLE_MCP_AUTHZ_ROLE_SOURCE_BASE_URL` and
  `EXAMPLE_MCP_AUTHZ_ROSTER_FILTER_ENABLED` unchanged, as designed.
- **Docs:** `AGENT-TOOL-AUTHORIZATION.md` — kill-switch row renamed; new *The principal ceiling* section (the
  contract in prose, why not `RoleDefinitionSupplier`, the memo). `CHANGELOG.md` — the SPI + memo line; the
  property-rename note extended to the two authz keys.

## Tests

- **U5:** `ToolCallGateTest` split — every case moved to the module except the three that need the example's
  catalog translator (`namesTheTargetGateWhenTheCatalogDenies`, `namesTheTargetGateEvenWhenTheDelegateSwallowsTheException`,
  `offIsNeverWiderThanOn`), which stay in the example as **`CatalogToolGateTest`** with the real catalog
  client and unchanged assertions. In the module the tool body calls the same `HttpServer` stub through a
  test-local JDK `HttpClient` GET, so every `catalogCalls` / `opaCalls` assertion is literally the same. Ceiling
  stubs → `PrincipalCeilingSupplier` lambdas. New case `attributesAnAdoptersOwnOutageSubtypeToTheCeiling` (a
  test-local family subtype ⇒ `tool-gate-ceiling-unavailable`); the existing `RoleResolutionException`,
  capability-outage and engine-failure cases are unchanged.
- **U6:** `ToolRosterFilterTest` moved; diff = package, the ceiling-stub type (3 sites), and the dropped
  `getRosterFilter().setEnabled(false)` line (see *Decisions*).
- **U7:** new `MemoizingPrincipalCeilingSupplierTest` (one delegate call per key; distinct keys per subject and
  type; empty memoized; family outage memoized and re-thrown with no second call; a non-family exception not
  memoized; no request ⇒ pass-through; a new request asks again) and the example's `CeilingMemoWiringTest`
  (memo by default; `opa.abac.resolve-memo.enabled=false` ⇒ the raw `TypeLevelPrincipalCeilingSupplier`).
- **U8:** `TypeLevelPrincipalCeilingSupplierTest` (renamed; `lookup(user, type, null)` → `lookup(user, type)`,
  assertions unchanged) + a new case pinning that the class implements `PrincipalCeilingSupplier` and **not**
  `RoleDefinitionSupplier`.
- **U38:** `ApplicationYamlBindingTest` extended — nothing binds under `example.mcp.authz.agent-gate` /
  `policy-path`; `opa.abac.mcp.agent-gate.enabled` and `opa.abac.mcp.policy-path` do; `role-source.*` and
  `roster-filter.enabled` still bind to `ExampleAuthzProperties`.
- `RosterFilterSmokeCheckTest`: `ExampleAuthzConfiguration.class`; its fixture builds the module's
  `ToolAuthorizationProperties`.
- `./gradlew :opa-abac-mcp:test :example-mcp-server:test` green — **101** (module) + **57** (example) = 158,
  0 failures (T1's 146 + 12 new; no case lost in the split). `./gradlew build` green.

## Architecture review + refactor

Inline ★ review:

- **Fail-closed / call-site attribution.** The wrap happens only around the ceiling lookup, so a family member
  thrown there — whatever its type, even an `AgentCapabilityUnavailableException` — is the ceiling's code, and a
  family member thrown by `OpaClient.allow` stays `tool-gate-policy-unavailable`. The roster still lands on
  *unfiltered* for a ceiling edge (the wrapper is a family member).
- **Security (memo isolation).** The ceiling memo keys on `(subject, resourceType)` and lives in the request
  attributes; pinned by the distinct-key and new-request cases.
- **Refactor from the review:** the first draft normalized a `null` delegate answer to `Optional.empty()` in
  the memo — a semantic the role memo does not have (Sonar S2789 flagged the null check). Reverted to the role
  memo's exact semantics: an SPI-violating `null` fails the same way it does on the raw supplier (an NPE that
  escapes the gate as an error — never an allow). Also dropped a `delegate()` accessor nobody needed (S6206).
- **Behavioral equivalence for the example:** the gate saw `Memoizing(TypeLevel)` before (as a
  `RoleDefinitionSupplier`, decorated by the starter's BPP) and sees `MemoizingPrincipalCeiling(TypeLevel)` now,
  under the same switch — same lookups, same memo scope.
- **Static analysis:** local Sonar on changed files — real findings fixed (S2789, S6206, S1144 an unused
  `denyResponse` left in `CatalogToolGateTest`). Remaining 43 are documented by-design classes
  (mx-302e78 / mx-6fd6b3): S5778 ×32, S5853 ×4, S1168 ×2 (the memo's `requestMemo()` pass-through sentinel —
  the same shape as `MemoizingRoleDefinitionSupplier`), S2925 ×2, S3011 ×1 (`RosterFilterInstaller`'s
  deliberate reflection), S125 ×1, S5976 ×1.

## Integration / e2e

None for T2 (the example's `@SpringBootTest` classes run under `./gradlew build`, green; the rig run is T4's E1).

## Decisions

- **`ToolRosterFilterTest:213`'s `properties.getRosterFilter().setEnabled(false)` dropped** — the setting moved
  to `ExampleAuthzProperties` and the roster filter object never read it; the case's assertions are unchanged.
- **Consequence recorded:** the example no longer defines a `RoleDefinitionSupplier`, so the starter's
  `@ConditionalOnMissingBean` `NoOpRoleDefinitionSupplier` now appears in its context (decorated by the
  starter's memo BPP). Nothing in the example injects it — the gate was its only consumer, and the example has
  no `@OpaPreAuthorize` methods.
- `CeilingMemoWiringTest` pins the T2 wiring only; T3 moves the decoration into the auto-configuration (U14
  replaces it).
- The moved `ToolCallGateTest`'s `I8–I15` javadoc label narrowed to what stayed; the moved/remaining case
  split is exactly the decomposition's.

## Commit

This commit — `refactor(mcp): the decision moves into the module, onto PrincipalCeilingSupplier (MCP-AUTH-LIBRARY T2)`.
