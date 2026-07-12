---
tags:
  - status/done
  - type/project
  - area/build
---

# STATUS — T2: Per-module POM identity (POM_NAME / POM_DESCRIPTION / POM_ARTIFACT_ID) for the 5 libraries

**Status:** ✅ DONE

## What shipped

A `gradle.properties` in each of the five library modules, carrying **only** the three per-artifact
fields vanniktech reads module-locally (the shared url/scm/license/developer block stays in the root
`gradle.properties`, not duplicated):

| Module | `POM_ARTIFACT_ID` | `POM_NAME` | `POM_DESCRIPTION` |
|---|---|---|---|
| opa-abac-core | opa-abac-core | OPA ABAC :: Core | Framework-agnostic ABAC model and OPA client |
| opa-abac-spring-security | opa-abac-spring-security | OPA ABAC :: Spring Security | Spring Security integration for OPA ABAC authorization |
| opa-abac-spring-data | opa-abac-spring-data | OPA ABAC :: Spring Data | Spring Data JPA integration for OPA partial-evaluation filtering |
| opa-abac-keycloak-directory | opa-abac-keycloak-directory | OPA ABAC :: Keycloak Directory | Keycloak-admin implementation of the OPA ABAC user-directory port (optional module) |
| opa-abac-spring-boot-starter | opa-abac-spring-boot-starter | OPA ABAC :: Spring Boot Starter | Spring Boot auto-configuration starter for OPA ABAC authorization |

Each `POM_DESCRIPTION` is the module's existing `description = "…"` string, verbatim (the two agree).

## Tests

- **I1 ✅** — `./gradlew generatePomFileForMavenPublication` (no signing key needed for POM generation);
  inspected all five `build/publications/maven/pom-default.xml`. Every POM carries: distinct
  `<artifactId>` + `<name>` + `<description>`, plus the shared `<url>`, `<scm>`, `<licenses>` (Apache-2.0),
  and `<developers>` blocks — non-empty across all five. `<groupId>dev.dmitriikonovalov</groupId>`.
- **U1 ✅** — `./gradlew build` stays green (exit 0) after adding the per-module properties.

## Architecture review + refactor

- **Clean-room ✅** — `POM_NAME` strings + descriptions are original/generic (authorization + product
  catalog domain); no proprietary names.
- **No duplication ✅** — module `gradle.properties` carries only the per-artifact three; the shared POM
  fields come from root and are not repeated per module (ticket's explicit "do not duplicate" rule).
- **Consistency ✅** — `POM_DESCRIPTION` == the module's `description = "…"`, verbatim (no drift).
- **Boundary ✅** — no source touched; no example module got a `gradle.properties`; core stays Spring-free.

**Refactor applied:** none substantive. One deliberate choice: the `::` name separator (Spring/Sonatype
POM convention) so the names read cleanly in Central search / mvnrepository — a convention match, not churn.

## Integration / e2e

POM-content verification via `generatePomFileFor…` (the signing-free path). The signed six-coordinate
proof is the T6 gate.

## Decisions

- Per-artifact `POM_NAME` uses the `groupId :: module` style (e.g. "OPA ABAC :: Spring Security"),
  matching how Spring's own POMs render in search UIs.

## Commit

`build(publish): per-module POM identity (name/description/artifactId) for the 5 libraries` on
`feature/void3110/maven-central-publishing`, identity `Void3110 <void31102025@gmail.com>`.
