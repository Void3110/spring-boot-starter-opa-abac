---
tags:
  - status/active
  - type/review
  - area/release
---

# 1.2.0 pre-publish sweep — 2026-08-18

Delta sweeps before the 1.2.0 Central cut, per `RELEASE-1.2.0.md` §4. **One fix applied**, three
findings deferred with reasons.

## Release code is byte-identical to the last all-gates-green commit

`git diff 20496b5..HEAD` over everything except `docs/`, `.mulch/` and `README.md` is **empty** — the
SPA-CHALLENGE-UX merge, where the full gate set ran green, is the same source being released. CI on
`main` re-proved the build on a clean runner. The rig e2e matrices were therefore **not re-run**: they
would re-prove unchanged code.

## Build

`./gradlew clean build --no-build-cache` — **1225 tests, 0 failures, 0 errors, 0 skipped**; 54 tasks
executed, nothing restored from cache. (A first run had returned green in 2 s with every test task
`FROM-CACHE`; that is Gradle correctly reusing outputs for unchanged inputs, but it is not a local
execution, and the artifacts being signed here are built on this machine.) `opa test` **389/389**.

## CVE sweep — 197 resolved coordinates against OSV

Queried the full resolved `runtimeClasspath` of all five published modules plus `example-mcp-server`.

**Non-finding, recorded so it is not re-investigated:** `jackson-databind:2.11.0`, `jackson-core:2.15.4`
/`2.17.2` and `commons-io:2.11.0` appear in `opa-abac-keycloak-directory`'s dependency *tree* — the
Keycloak admin client requests them — but **none resolves**. Dependency management upgrades them to
`jackson-databind:2.21.4`, `jackson-core:2.21.4` and `commons-io:2.19.0`. Reading the tree rather than
the resolution would have reported nine advisories that do not exist on any classpath.

**Genuine, verified against OSV affected ranges** (all MODERATE):

| Advisory | Resolved dep affected | Fixed in | Version owned by |
|---|---|---|---|
| GHSA-5gvw-p9qm-jgwh — `@JsonView` bypass for `@JsonUnwrapped` containers | `tools.jackson.core:jackson-databind:3.1.4` | 3.1.5 | **this repo** (`libs.versions.toml`) |
| GHSA-5gvw-p9qm-jgwh | `com.fasterxml.jackson.core:jackson-databind:2.21.4` | 2.21.5 | Spring Boot 4.0.7 |
| GHSA-mhm7-754m-9p8w — `@JsonView` bypass for creator properties | `com.fasterxml.jackson.core:jackson-databind:2.21.4` | 2.21.5 | Spring Boot 4.0.7 |
| GHSA-5jmj-h7xm-6q6v — case-insensitive deserialization bypasses `@JsonIgnoreProperties` | `com.fasterxml.jackson.core:jackson-databind:2.21.4` | 2.21.5 | Spring Boot 4.0.7 |
| GHSA-qv9r-c865-cp47 — non-finite float encoding in `MapMessage` JSON | `org.apache.logging.log4j:log4j-api:2.25.4` | 2.25.5 | Spring Boot 4.0.7 |

*(`tools.jackson.core:jackson-databind:3.1.4` is **not** affected by GHSA-5jmj — that range is fixed at
3.1.4 exactly.)*

**Exposure in this codebase is nil, measured not assumed:** `@JsonView` **0** usages, `@JsonUnwrapped`
**0**, `MapMessage` **0**, and GHSA-5jmj requires case-insensitive deserialization, which is never
enabled (`ACCEPT_CASE_INSENSITIVE` **0**).

**Fixed:** the one version this repo owns — `jackson` **3.1.4 → 3.1.5**. Re-queried: `3.1.5` is
**CLEAN**. Rebuild green at 1225/0.

**Deferred, with the reason:** the other three are Spring Boot 4.0.7 *managed* versions.
**Boot 4.0.8 does not exist yet** (checked on Central), so closing them today means overriding the BOM —
which is the thing the BOM exists to prevent — for advisories with no reachable path in this library.
They resolve for free on the next Boot patch. Tracked in `ENGINEERING-BACKLOG`.

## Zero-config fail-closed

Re-asserted by name rather than re-derived; all pass in the release build:

- `OpaAbacAutoConfigurationTest.defaultSupplier_isNoOp` — the unconfigured starter's supplier is the
  fail-closed no-op.
- `StepUpDecisionAndChallengeTest.auditIsSilentWithoutAPolicy` — the step-up additions did **not** open
  a default: an unconfigured `opa.abac.audit.privileged-read.*` emits nothing.
- `StepUpDecisionAndChallengeTest.managerDeniesOnANullDecision`.
- Policy: `test_elevation_undefined_on_unmapped_acr`,
  `test_elevation_undefined_on_non_numeric_auth_time`, `test_non_numeric_required_level_fails_closed`,
  and `test_unmapped_required_acr_closes_elevation_and_mutes_the_challenge` — the operator footgun
  closes elevation rather than opening it.

## UI QA

**Not re-run — discharged.** The E10–E21 browser case list was run adversarially against this exact
source at the SPA-CHALLENGE-UX close-out, and no source has changed since. A re-run would re-prove
byte-identical code.

## Artifact dry-run

`publishToMavenLocal` with gpg-command signing: exactly **6** coordinates, no `example-*`; the five
libraries each with jar + sources + javadoc + pom and a `.asc` per artifact; `opa-abac-bom` a pom with
`<packaging>pom</packaging>` and all five modules managed at `1.2.0`, **no jar**; signatures carry
**issuer fpr subpkt 33** (`…EDC25E3A289FE64E`) — the check that failed the first 1.0.0 attempt.
