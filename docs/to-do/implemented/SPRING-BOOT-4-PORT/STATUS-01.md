---
tags:
  - status/done
  - type/project
  - area/architecture
  - area/spring
---

# STATUS — T1: Boot 3.4.13 → 3.5.x + the deprecation map

**Status:** ✅ DONE

## What shipped

- `gradle/libs.versions.toml`: `springBoot` 3.4.13 → **3.5.16** (latest 3.5.x patch at run time;
  4.0.x latest was 4.0.7 the same day — recorded for T4).
- `junit` 5.11.4 → **5.12.2**, aligned with Boot 3.5.16's managed Jupiter/platform line. The stale
  pin was not benign: the Boot BOM upgraded the engine to 1.12.2 while the launcher stayed older,
  and the test executor died at discovery ("OutputDirectoryProvider not available") in every module
  that imports the Boot BOM.
- **Explicit `testRuntimeOnly("org.junit.platform:junit-platform-launcher")` in all 7 test-bearing
  modules** (5 library + 2 example). Gradle 8.12 auto-loads a launcher from its own distribution
  when none is declared — unaligned with a BOM-managed 5.12 engine, and the auto-loading itself is
  the "Deprecated Gradle features … incompatible with Gradle 9.0" warning. After this change the
  build has **zero Gradle-9 incompatibility warnings** (verified in the build log) — a T4 pre-clean.
- `OpaAbacAutoConfigurationTest.OneRowRepo` (test stub): Data JPA 3.5 changed
  `JpaSpecificationExecutor` — `findBy` now takes
  `Function<? super JpaSpecificationExecutor.SpecificationFluentQuery<S>, R>` (was
  `FluentQuery.FetchableFluentQuery`), and a new abstract
  `findAll(Specification, Specification countSpec, Pageable)` overload exists. Stub updated to both
  (signatures verified via `javap` against the resolved 3.5.13 spring-data-jpa jar, not guessed).
- No other source changes — production code untouched.

## The deprecation map (what the port retires, by ticket)

| Warning | Sites | Ticket |
|---|---|---|
| `[deprecation] check(Supplier,T) in AuthorizationManager` | `OpaAuthorizationManager:55`, `OpaMethodSecurityConfiguration:80` (DeferredAuthorizationManager), `OpaPreAuthorizeAuthorizationManager:95` | T2 |
| `[removal] Specification.where(Specification)` — **already marked for removal on 3.5**, stronger than the plain deprecation the design expected | main: `AbacQueryService:288,408`, `ResidualSpecificationFactory:69`; tests: `AbacQueryServiceIT:91,94`, `ByoEntityFilterIT:126`, `PaginationListIT:104,106,122,129,151,153,163,166` (11 warning sites; +2 comment-only refs carry no warning) | T6 |
| Gradle: automatic test-framework loading (the Gradle-9 blocker) | all 7 modules | **fixed in T1** |
| R4j `internal.CircuitBreakerStateMachine` — no compile warning (internal API, never deprecated; 2.4.0 *removed* it) | `Resilience4jCallGuard:92` | T3 |

Nothing unexpected beyond the two 3.5-forced test-infra items above (JUnit alignment, Data JPA 3.5
interface change). **Diagnostic for T6, verified via javap on 3.5:** `Specification.unrestricted()`
exists (alongside `allOf`/`anyOf`) — F7's "verify exact replacement name" is settled.

## Tests

- Full `./gradlew clean build` → **BUILD SUCCESSFUL**; all 7 `:test` tasks executed (not
  up-to-date), **824 test cases green** incl. Testcontainers ITs against real Postgres.
- `opa test infra/opa/policies` → **228/228** (untouched).
- Deprecation sweep captured with a scratchpad-only `-Xlint:deprecation` init script — no committed
  build-file lint changes.

## Architecture review + refactor

Review ran the port-specific gate (fail-closed / security / concurrency / wiring / boundary /
layering): **nothing substantive** — T1 carries zero production-code changes, so no fail-closed edge
can move; the platform bump's behavior is pinned by the unchanged 824-test + 228-rego suites. The
one new build seam (explicit launcher) had its non-happy path exercised for real (the discovery
crash before, green after). `opa-abac-core` gained only a test-scope JUnit artifact — still
Spring-free. No refactor needed; no churn invented.

## Integration / e2e

Full build = the T1 integration gate (Testcontainers ITs green, codegen green). e2e fleet deferred
to T7 by design (build-only tickets until then).

## Decisions

- Boot 3.5 patch resolved at run time per the prompt's permission: **3.5.16**.
- The JUnit launcher fix applied to **all** test-bearing modules (not just the two that crashed) —
  the crash is resolution-order-dependent; Gradle 9 requires the declaration everywhere anyway.

## Commit

`build(port): Boot 3.5.16 + JUnit 5.12.2, explicit test launcher, deprecation map (T1)` — one
commit: catalog, 7 build files, the OneRowRepo stub, this note, the index tick.
