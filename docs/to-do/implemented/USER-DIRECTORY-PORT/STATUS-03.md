---
tags:
  - status/done
  - type/project
  - area/security
  - area/api
---

# STATUS — T3: Starter auto-config (@ConditionalOnClass+@ConditionalOnProperty + NoOp fallback)

**Status:** ✅ DONE

## What shipped

- `OpaDirectoryAutoConfiguration` (starter, `…autoconfigure`) — the B3 optional-module pattern:
  a nested `KeycloakDirectoryConfiguration` gated on `@ConditionalOnClass(name =
  "org.keycloak.admin.client.Keycloak")` **+** `@ConditionalOnProperty(opa.abac.directory.keycloak.enabled
  = true)` (no `matchIfMissing` — absent means off) building the `KeycloakUserDirectory` bean from
  `KeycloakDirectoryProperties`; the outer class's `noOpUserDirectory()` fallback is
  `@ConditionalOnMissingBean(UserDirectory.class)`, so a `UserDirectory` always exists and the
  zero-config state is the fail-closed empty. Both beans CMB → an adopter-supplied directory wins.
- Registered via the `@Import` list on `OpaAbacAutoConfiguration` — the repo's idiom for sub-configs
  (how `OpaResilienceAutoConfiguration` and `OwnershipAutoConfiguration` register; the decomposition's
  "register in AutoConfiguration.imports" is satisfied through the imported parent, which is the one
  entry in that file).
- Starter `build.gradle.kts`: `compileOnly(project(":opa-abac-keycloak-directory"))` (+
  `testImplementation` for the slice tests) — the module never rides the starter transitively; the
  same treatment as web/security/R4j.
- Consumers: any app on the starter classpath; T4's endpoint injects the resulting bean.

## Tests

`:opa-abac-spring-boot-starter:test` — full suite green (existing OpaAbacAutoConfigurationTest
untouched and passing); new `OpaDirectoryAutoConfigurationTest` 4/4:

- **I3a** (two flavors) — enabled unset → `NoOp`, no Keycloak bean; module **fully absent**
  (`FilteredClassLoader("org.keycloak", "dev.dmitriikonovalov.opaabac.keycloak")`) even with
  `enabled=true` → context **has not failed**, `NoOp` present.
- **I3b** — module present + `enabled=true` + properties → single `KeycloakUserDirectory` bean, no
  `NoOp`; properties bound (server-url/realm/client-id asserted).
- **I3c** — adopter `UserDirectory` bean wins over both library beans.

## Architecture review + refactor

Nothing substantive to refactor. Points verified / worth recording:

- **Ordering:** the fallback works because member configurations register before the enclosing class's
  own `@Bean` methods, so the Keycloak bean (when opted in) exists by the time the `NoOp`'s CMB is
  evaluated. This is longstanding Spring behavior, documented in the class Javadoc, and **pinned by
  I3b** — if ordering ever changed, I3b fails loudly (two beans / wrong type).
- **Off-state first-class:** I3a runs with zero directory config and with the module absent — no
  startup failure, no Keycloak bean, the lean-starter promise proven.
- **Scope note:** the directory config rides `OpaAbacAutoConfiguration`, so `opa.abac.enabled=false`
  turns it off with the rest of the starter — consistent with the resilience/ownership siblings.
- **Boundary:** existing-file edits are mechanical only (one `@Import` line; two build-file lines).
- **Construction safety:** the Keycloak bean does no I/O at startup (T2's lazy grant) — a down IdP can
  never fail a boot.

## Integration / e2e

The `ApplicationContextRunner` slice tests above are this ticket's integration validation — green.

## Decisions

- Registered through the parent's `@Import` (repo idiom) rather than a second line in
  `AutoConfiguration.imports` — same effect, consistent with every existing sub-config.

## Commit

`feat(directory): auto-configure UserDirectory — Keycloak opt-in with NoOp fallback`
