---
tags:
  - status/planned
  - type/architecture
  - area/architecture
---

# Spring Boot 4 port — research note (pre-grill-me)

> **What this is.** The findings of a 2026-07-08 research pass: what a Spring Boot 3.4 → 4.0 port of
> this repo actually requires, crossed against a file-level inventory of the Spring API surface the
> code touches. **Not a design** — the input to a future grill-me/decompose when the port slots into
> Phase 7. One empirical data point anchors it (§1).

## 1. Why this is publish-strategy, not maintenance

- **The 3.x line is out of OSS support** (3.4 since 2025-12, 3.5 — the final 3.x — since 2026-06).
  Boot 4.0 (Framework 7 / Security 7 / Jakarta EE 11 / Hibernate 7 / Jackson 3) is the current line.
  A library publishing its **1.0** in late 2026 against 3.4 is dated on arrival: Boot 4 is arguably
  the publish target, not a follow-up.
- **Empirical anchor:** an external consumer session (2026-07-08) used this starter as a dependency
  inside a **Java 25 + Spring Boot 4** microservice and it **worked without runtime issues** — the
  3.4-compiled artifact is already consumable on SB4 in practice. So the port is not about making it
  *run* on Boot 4; it is about **targeting** Boot 4 first-class (compile against it, track its API
  removals before they bite, publish the right story). Two **public-API polish items** also came out
  of that consumer test — tracked separately as the pre-publish API polish pass (the
  `Taggable.TAGS_ATTRIBUTE` decoupling and the `AbacDataObject` → `AbacResource` rename); land those
  BEFORE 1.0 regardless of the port.
- One caveat on the anchor: the consumer ran with our managers implementing the deprecated
  `AuthorizationManager#check(...)` and it functioned on SB4's Security version — so `check()`
  removal evidently hadn't landed in the shipped 7.x line at test time. Treat the exact
  removal timing as **verify-at-port**; the `authorize()` migration is required either way (§3).

## 2. Baseline / build facts (verified against the official migration guide)

| Item | Now | Boot 4 requirement | Impact |
|---|---|---|---|
| Java | 21 toolchain | 17+ (21/25 first-class) | none |
| Gradle wrapper | 8.12 | newer plugin baseline (verify exact) — note 8.12 can't even RUN on JDK 25 | bump wrapper |
| Servlet | 6.0 (Tomcat 10.1) | **6.1** (Tomcat 11; Undertow dropped) | transparent for us |
| Jackson | 2.18 (`com.fasterxml`) | **Jackson 3** (`tools.jackson`, new groupIds; only `jackson-annotations` keeps `com.fasterxml`) | §3 core decision |
| Starters | `spring-boot-starter-web` | **renamed `spring-boot-starter-webmvc`**; Boot modularized into `spring-boot-<tech>` modules with `org.springframework.boot.<tech>` packages | example build files + any Boot-class imports |
| Kotlin / GraalVM | n/a | 2.2+ / 25+ | n/a |

## 3. Per-module port surface (from the code inventory)

**`opa-abac-core` — tiny; one decision.** Spring-free ✓ (JDK `HttpClient`). Uses Jackson 2 directly
(`ObjectMapper`/`JsonNode`/`TypeReference` in `HttpOpaClient`, `ProblemDetail`). Decision: migrate to
**Jackson 3** for the Boot-4 line (mechanical for this usage; builder-style immutable mapper) rather
than dragging Jackson 2 into consumers via our `api(jackson-databind)`. A deprecated
`spring-boot-jackson2` bridge exists but is the wrong 1.0 story.

**`opa-abac-spring-security` — the one real API break.** Both managers implement
`AuthorizationManager#check(...)` (deprecated since 6.4; Security 7 replaces it with `authorize(...)`
returning `AuthorizationResult` — `AuthorizationDecision` implements it, so the change is a rename
plus return-type widening; the in-code comment at `OpaPreAuthorizeAuthorizationManager` already
anticipates it). Everything else we touch survives Security 7: `AuthorizationManagerBeforeMethodInterceptor`,
`@EnableMethodSecurity`, `OncePerRequestFilter`, the hand-rolled SpEL stack (not the Security
expression handler), `ResponseBodyAdvice`. **Verify-at-port:** `AccessDeniedException` packaging (some
legacy access APIs moved to a separate `spring-security-access` module); the **R4j
`internal.CircuitBreakerStateMachine` pin** against whatever Resilience4j version rides with Boot 4.

**`opa-abac-spring-data` — mechanical + prove-it.** Data JPA 4.0 keeps `Specification` (adds
`PredicateSpecification`/`UpdateSpecification`/`DeleteSpecification`); our three
`Specification.where(null)` sites hit a deprecation (migrate to the 4.0 composition idiom); 3-arg
lambdas + `JpaSpecificationExecutor.findAll(spec, pageable)` unchanged. `hibernate-jpamodelgen` →
`hibernate-processor` (we don't use it). **Hibernate 7** is prove-don't-assume territory: our
`@JdbcTypeCode(SqlTypes.JSON)` + custom jsonb `AttributeConverter`, `@ColumnTransformer("?::ltree")`,
`@TimeZoneStorage(NATIVE)`, and the native ltree rewrite via `EntityManager` all still exist in 7 —
the Testcontainers ITs + the `ddl-auto: validate` boot are the gate.

**`opa-abac-spring-boot-starter` — low risk.** The auto-config SPI is unchanged in Boot 4
(`@AutoConfiguration`, `@ConditionalOn*`, `AutoConfiguration.imports`, `@ConfigurationProperties`,
`AllNestedConditions`, the `BeanPostProcessor` decorator). Boot's modularization moves **Boot's own**
classes; our string-named `@ConditionalOnClass` back-offs all reference non-Boot classes
(SecurityFilterChain, OncePerRequestFilter, JpaSpecificationExecutor, Keycloak) — package-stable ✓.

**`opa-abac-keycloak-directory` — tiny.** `keycloak-admin-client` 26.x bundles its own RESTEasy +
`jakarta.ws.rs` client; independent of Boot. Verify under EE 11 at port time; expect none-to-trivial.

**Examples — broad but shallow.** `spring-boot-starter-web` → `-webmvc`; springdoc 2.8 → 3.x (the
Boot-4 line); the Security DSL used is all-lambda (nothing Security 7 removes appears);
`FilterRegistrationBean` and the 3.4-relocated `ClientHttpRequestFactoryBuilder/Settings`
(user-service `RestClient`) are Boot classes that may move packages again — import churn. OpenAPI
generator: output is jakarta-flavored and its Jackson usage is **annotations only** (which keep the
`com.fasterxml` groupId under Jackson 3) — likely survives; verify the generator's SB4 flavor.

**Tests — moderate churn, zero redesign.** `@SpringBootTest` no longer auto-provides
`TestRestTemplate`/`MockMvc` → add `@AutoConfigureTestRestTemplate` (37 refs) /
`@AutoConfigureMockMvc` (94 refs); some `spring-boot-test` annotation packages moved with the
modularization. No `@MockBean` anywhere (that removal costs nothing). `@DynamicPropertySource` +
`FilteredClassLoader` + `ApplicationContextRunner` all stay public API. JSpecify nullability:
warnings only for a plain-Java build.

**Rig/harness — untouched.** APISIX/OPA/Keycloak/Jaeger/k6/newman are not Java; `PERFORMANCE.md`
would want a re-baseline after the port (new Tomcat/Hibernate/Jackson under the same harness).

## 4. Shape of the port (for the eventual decompose)

1. **Step 0 (cheap, do first):** 3.4.13 → **3.5.x** — stays on the migration-guide baseline,
   surfaces every deprecation warning our code hits, near-zero risk.
2. **The 4.0 slice** (~6 tickets): build/BOM + wrapper + starter renames → core Jackson 3 →
   security `authorize()` → data (`where(null)` idiom + Hibernate-7 ITs) → starter conditionals +
   context-runner tests → examples/codegen/test-annotations + full e2e + perf re-baseline.
3. **Packaging decision for 1.0:** target Boot 4 single-line. A dual 3.5/4.0 artifact is
   theoretically possible (implement `authorize()` only — exists since 6.4; §1 shows the runtime
   compat window is real) but fragile and the 3.x line is EOL anyway.

## Sources

[Spring Boot 4.0 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide) ·
[Spring Security 7 migration](https://docs.spring.io/spring-security/reference/migration/) ·
[Security 7 authorization changes](https://docs.spring.io/spring-security/reference/migration-7/authorization.html) ·
[Spring Data 2025.1 release notes](https://github.com/spring-projects/spring-data-commons/wiki/Spring-Data-2025.1-Release-Notes) ·
[`Specification.where` deprecation](https://github.com/spring-projects/spring-data-jpa/issues/3893) ·
[Boot versions/EOL overview](https://www.herodevs.com/blog-posts/spring-boot-versions-eol-dates-and-latest-releases-april-2026) ·
[spring-security-access relocation](https://noregressions.dev/guides/migration/cards/spring-security-access-relocated)
