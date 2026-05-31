---
tags:
  - status/implemented
  - type/project
  - area/spring
  - area/abac
---

# STATUS — Ticket 4: Starter: auto-configuration (conditional + overridable)

> Filled in at the ticket-4 checkpoint. See [[01-DECOMPOSITION]] ticket 4.

**Status:** ✅ implemented (2026-06-01)

## What shipped
In `opa-abac-spring-boot-starter` (`dev.dmitriikonovalov.opaabac.autoconfigure`):

- **`OpaAbacProperties`** extended: kept `enabled`/`baseUrl`/`policyPrefix`/`timeout`; added
  `decisionField` (`allow`), `verifySignature` (false, reserved/unimplemented), and a nested
  `subject` group (`idClaim`=sub, `rolesClaim`=realm_access.roles, `usernameClaim`=preferred_username,
  `attributeClaims`=[], `validateExpiry`=true) via `@NestedConfigurationProperty`. The
  `spring-configuration-metadata.json` regenerated to **11 properties** (incl. the `subject.*` group).
- **`OpaAbacAutoConfiguration`** — `@AutoConfiguration`, gated on `opa.abac.enabled` (matchIfMissing).
  Core beans always present, each `@ConditionalOnMissingBean`: `ObjectMapper` (named), `PolicyPathResolver`
  (← `PerTypePolicyPathResolver(policyPrefix)`), `OpaClient` (← `HttpOpaClient` from an `OpaClientConfig`
  mapped from properties), `RoleDefinitionSupplier` (← `NoOpRoleDefinitionSupplier` — the app overrides).
  A nested `SecurityAutoConfiguration` `@ConditionalOnClass(name = {SecurityFilterChain,
  OncePerRequestFilter})` imports the security beans.
- **`OpaAbacSecurityBeans`** — the Spring-Security beans, imported only when security/web is present so
  the security types never load otherwise: `AbacSubjectExtractor` (← `JwtClaimsSubjectExtractor` from the
  `subject` properties), `AbacFilter`, `OpaPreAuthorizeAuthorizationManager`, and (via
  `@Import(OpaMethodSecurityConfiguration.class)`) the `@OpaPreAuthorize` advisor. **No
  `SecurityFilterChain`.**
- Build: `spring-boot-starter-security` + `-web` are **`compileOnly`** on the starter (the app supplies
  them at runtime; the starter stays usable in a non-web app) and `testImplementation` for the tests.

## Tests
`./gradlew :opa-abac-spring-boot-starter:test` — **6 passed, 0 failed** (`ApplicationContextRunner`).
- U30 enabled + security → all spine beans present **and `doesNotHaveBean(SecurityFilterChain)`**.
- U31 `enabled=false` → no spine beans.
- U32 user `@Bean OpaClient` / `RoleDefinitionSupplier` → starter backs off (stubs win).
- U33 `FilteredClassLoader` removes security/web → only the core beans; no extractor/filter/manager; no
  chain.
- U34 property binding (`decisionField`, `policyPrefix`, `subject.rolesClaim`, `subject.validateExpiry`).
- default `RoleDefinitionSupplier` is the no-op.

Full library build green together: `:opa-abac-core:test` + `:opa-abac-spring-security:test` +
`:opa-abac-spring-boot-starter:test` all pass.

## Architecture review + refactor
Ran the gate against `00-DESIGN.md`:
- **Starter does NOT seize the chain** — asserted directly (U30 `doesNotHaveBean(SecurityFilterChain)`);
  the app declares its own chain and installs `AbacFilter`.
- **Conditional + overridable** — every bean `@ConditionalOnMissingBean`; U32 proves user beans win;
  U33 proves `@ConditionalOnClass` gates the security beans. (Honors Mulch `mx-fc941c`.)
- **Module-aware boundary** — security-typed beans isolated in `OpaAbacSecurityBeans`, referenced only
  behind `@ConditionalOnClass(name=...)` (string form), so the starter loads cleanly without security/web.

**Refactor applied:** nothing substantive — the core-vs-security two-config split was right from the
start; no churn invented.

**Resolved design point:** the opt-in request-level `OpaAuthorizationManager` is **not** auto-wired,
because it requires an application-specific path-prefix→type map (no sensible no-arg default). The app
declares it as a `@Bean` if it wants request-level rules; `@OpaPreAuthorize` is the auto-wired headline.
This matches "provided; the app wires it" in the decomposition.

## Integration / e2e
The `ApplicationContextRunner` slice tests **are** the mandated integration validation for T4 (bean
presence/absence, overridability, no-security classpath). The full app rig is T5/T7.

## Decisions recorded
No new Mulch record — `mx-fc941c` (auto-config conditional + overridable) already covers it. The
`compileOnly` security/web choice is noted here in STATUS as the rationale.

## Commit
`feat(starter): wire the spine — conditional, overridable, no SecurityFilterChain`.
