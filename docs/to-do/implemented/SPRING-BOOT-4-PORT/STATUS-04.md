---
tags:
  - status/implemented
  - type/project
  - area/architecture
  - area/spring
---

# STATUS — T4: THE BUMP: Boot 4.0.x + Gradle 9 + JDK 25 + renames + test churn

**Status:** ✅ DONE

## What shipped

**Pins (resolved at run time per the prompt's permission):** Boot **4.0.7**, Gradle wrapper
**9.6.1**, Java toolchain **25** (root `build.gradle.kts`), springdoc **3.0.3**, openapi-generator
**7.14.0**, CI `ci.yml` matrix → **JDK 25** (same commit as the wrapper).
`spring-dependency-management` 1.1.7 still works as the BOM idiom on Boot 4 — not retired.

**The compiler-driven waves, in order (12 build iterations, every fix verified against the
resolved jar/POM via `javap`/`unzip`, never guessed):**

1. **Security 7**: `authorize()` is now the abstract method with a JSpecify-annotated
   `Supplier<? extends Authentication>` parameter — the three managers' signatures widened; the
   three T2 `check()` bridges **deleted** (Security 7 dropped `check()`).
2. **Data JPA 4**: `where(null)` became **ambiguous** (new `PredicateSpecification` overloads), so
   the 2 main + 13 test `where(null)` sites moved to `Specification.unrestricted()` in T4 (T6 was
   the plan, but they stopped *compiling* — the bump-breaker law wins; T6 keeps the remaining
   deprecated `where(spec)` site + the sweep). `JpaSpecificationExecutor` grew
   `update(UpdateSpecification)` / retyped `delete(DeleteSpecification)` — `OneRowRepo` updated.
3. **Boot 4 modularization** (the dominant churn class — classes keep packages but move to new
   artifacts, or move package AND artifact):
   - test slices: `@DataJpaTest`/`TestEntityManager` → `spring-boot-data-jpa-test` (starter),
     `@AutoConfigureTestDatabase` → `spring-boot-jdbc-test`, `@AutoConfigureMockMvc` →
     `spring-boot-webmvc-test`, `TestRestTemplate` → `org.springframework.boot.resttestclient` +
     **`@AutoConfigureTestRestTemplate` now required** (no auto-provisioning on `@SpringBootTest`);
     `spring-boot-restclient` needed alongside (holds `RestTemplateBuilder`; optional in
     resttestclient — without it the test auto-config **fails introspection**, not "bean missing").
   - `EntityScan` → `org.springframework.boot.persistence.autoconfigure` (artifact already present).
   - **`LiquibaseAutoConfiguration` → `spring-boot-liquibase`**: liquibase-core alone no longer
     migrates — both example services gained the runtime artifact (first symptom: H3's
     `ddl-auto: validate` failing on a bare schema).
   - `ClientHttpRequestFactoryBuilder` → `spring-boot-http-client` artifact;
     `ClientHttpRequestFactorySettings` → **`HttpClientSettings`** (`RoleAssignableClient` renamed).
   - `spring-boot-starter-web` → `spring-boot-starter-webmvc` (5 build-file sites).
4. **Example apps are Jackson 3 apps now** (Boot 4 web tier + RestClient converters): the three
   catalog HTTP clients (`TagDefinitionClient`, `HttpGovernedScopeResolver`,
   `HttpRoleDefinitionSupplier`) + `RoleAssignableClient` + 19 example test files moved
   `com.fasterxml.jackson.{databind,core}` → `tools.jackson.*` (annotations stay `com.fasterxml`).
   A Jackson-2 bridge bean was rejected: RestClient's converters are Jackson 3 regardless. The
   library's mixed state is untouched (core/starter mappers stay Jackson 2 until T5;
   `OwnershipAutoConfiguration`'s `ObjectProvider` falls back to `::new` as designed).
   `ProblemDetailContractTest` keeps its Jackson-2 mapper via an explicit jsr310 test dep
   (Boot 4 no longer supplies it) — dies in T5.
5. **Framework 7 renamed the 422 enum**: `HttpStatus.resolve(422)` returns `UNPROCESSABLE_CONTENT`
   (RFC 9110), breaking equality assertions against the `UNPROCESSABLE_ENTITY` alias — 14 test
   sites renamed. Wire bytes identical (the ProblemDetail carries `status: 422`; production
   `LibraryErrorCode` untouched).
6. **One latent test bug exposed** (not port-caused): `PaginationEnvelopeIT#envelopeAndDefaults`
   assumed the created row lands in the first 100 of the *unordered* users list — Gradle 9's class
   execution order let the shared container accumulate >100 users first. Repaired via the
   `?subject=` exact-match filter (presence) while keeping the envelope assertions on the plain
   list; passes isolated AND in-suite.

## Verify-at-port items (closed)

- `spring-security-access` relocation: **no change needed** — `AccessDeniedException` imports
  compile unchanged on Security 7.
- keycloak-admin-client 26.0.5 under Jakarta EE 11: **none-to-trivial confirmed** — module tests
  green, no edits.
- openapi-generator 7.14.0 output on Boot 4: generated code (jakarta + `com.fasterxml` annotations
  only) compiles and runs; springdoc 3.0.3 wired.
- B3 (CI): `ci.yml` reviewed statically — JDK 25 + same wrapper; the live CI run happens on the
  maintainer's push.

## Tests

- Full `./gradlew build` green on **JDK 25.0.3 with no `JAVA_HOME` override** (B2 — every build in
  this ticket ran with `unset JAVA_HOME`; `java -version` recorded in the log). **824 tests, 0
  failures**; `opa test` **228/228 untouched**; an uncached `--rerun-tasks` full run confirmed
  post-hoc (Gradle 9's build cache otherwise legitimately restores just-run results).
- H1–H3 (Hibernate 7 prove-don't-assume): jsonb `AttributeConverter` + `@JdbcTypeCode(JSON)` ITs,
  ltree `@ColumnTransformer` + native-rewrite ITs, and the `ddl-auto: validate` boot all green on
  real Postgres — H3 failed mid-ticket for a *dependency* reason (missing Liquibase auto-config)
  and its failure mode proved the validate gate is live.
- C1: the `ApplicationContextRunner`/`FilteredClassLoader` conditional suites green unchanged.

## Architecture review + refactor

- **Fail-closed:** the gate ITs (`ResourceResolutionGateIT`, `TagDecisionGateIT`,
  `SupplierOutageGateIT`), the B2/B3 suites, and both isolation ITs pass **unchanged** — no
  deny/empty/503/throw path answers differently. The library's three wire mappers are untouched
  (T5 owns them).
- **Security (the named widening):** the app-side dictionary/role parsing did move to Jackson 3 in
  T4 — the widening that would matter (tolerance loosening on internal wire reads) is pinned by
  those clients' own unit suites (HttpServer-stub tests incl. malformed/fail-closed cases), all
  green without edits. `RoleAssignableClient`'s fail-closed contract re-proven live by the
  membership-gate ITs (OPA-fails/times-out/negative-verdict → still rejected).
- **Concurrency:** the optimistic-lock/version-guard ITs pass unchanged on Hibernate 7
  (decide-under-protection holds).
- **Boundary/layering:** `opa-abac-core` untouched this ticket (still Spring-free, Jackson 2 until
  T5); dependency flow `core ← spring-security/spring-data ← starter` unchanged; no cross-module
  compile fixes were needed.
- **Refactor applied:** reverted my own too-broad `@AutoConfigureTestRestTemplate` on the
  MOCK-environment base (`AbstractPostgresIT`) — it belongs only on the RANDOM_PORT base + the 4
  standalone web ITs ("No local test web server available" was the symptom). Otherwise mechanical.

## Integration / e2e

Full build incl. Testcontainers ITs on real Postgres = this ticket's gate (green). Live fleet: T7.

## Decisions

- `where(null)` sites fixed with the **final** idiom (`unrestricted()`) rather than a throwaway
  cast — they stopped compiling, and committing a cast T6 deletes two commits later is churn.
- Example-app Jackson-3 migration executed in T4, not deferred to T5 (RestClient converters make a
  Jackson-2 bridge unworkable); the library mixed state stays per plan.

## Commit

`build(port)!: Boot 4.0.7 + Gradle 9.6.1 + JDK 25 — the bump commit (T4)`
