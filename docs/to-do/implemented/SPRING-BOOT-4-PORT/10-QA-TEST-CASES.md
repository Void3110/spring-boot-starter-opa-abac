---
tags:
  - status/planned
  - type/project
  - area/architecture
  - area/spring
---

# SPRING-BOOT-4-PORT — QA test cases

> The cases each ticket's *Acceptance* references ([[01-DECOMPOSITION]]). A port's QA posture is
> unusual and deliberate: **the existing suites ARE the primary pin** — ~157 Java tests, 228 opa
> tests, the full newman fleet, all required to pass *unchanged* on the new line. New cases exist
> only where the port itself introduces a risk the current suite doesn't pin (the three wire-format
> contracts, the toolchain claim). Conventions: in-process `com.sun.net.httpserver.HttpServer` stub
> for OPA-client tests (no WireMock); real Postgres via Testcontainers (never H2); e2e asserts the
> actual cut, not response shape.

## B — Build / toolchain

- **B1 (T1)** — Full `./gradlew build` green on **3.5.x** (all modules, codegen, Testcontainers ITs);
  `opa test infra/opa/policies` unchanged (228). The deprecation map recorded in `STATUS-01.md`
  lists, at minimum, the 3 `check()` overrides and the `Specification.where(null)` sites.
- **B2 (T4)** — Full `./gradlew build` green on **Boot 4.0.x / Gradle 9.x / JDK 25** with **no
  `JAVA_HOME` override** on a default-JDK-25 machine (run `java -version` + the build in a shell with
  no override exported; record in `STATUS-04.md`). This single case also proves: starter renames,
  moved Boot-class imports, springdoc 3.x, the openapi-generator flavor, and the test-annotation
  churn (every `TestRestTemplate`/`MockMvc` test runs — auto-provisioning gaps fail loudly here).
- **B3 (T4)** — `.github/workflows/ci.yml` references JDK 25 and runs the same wrapper (static
  review locally; the live CI run happens on the maintainer's push — note it in `STATUS-04.md`).

## S — Security 7 (`authorize()`)

- **S1 (T2)** — The three managers expose `authorize(...)` with covariant `AuthorizationDecision`
  returns; the deleted `check()` overrides leave zero `.check(` references in our code (grep-clean,
  vendored/generated excluded).
- **S2 (T2)** — The 32 renamed test call sites pass with **assertions byte-identical** (the rename is
  the entire diff in those files — reviewable by inspection).
- **S3 (T2)** — The framework dispatch path is proven by the *existing* `@OpaPreAuthorize` ITs
  unchanged: `AuthorizationManagerBeforeMethodInterceptor` → `authorize()` → our manager → the same
  403/200 behavior (`ResourceResolutionGateIT`, `TagDecisionGateIT`, `SupplierOutageGateIT` et al.
  all green with zero edits).

## R — Resilience4j 2.4.0

- **R1 (T3)** — The existing B3 deterministic-timing suite passes **unchanged** on 2.4.0: breaker
  opens after N window failures; virtual-clock advance moves open → half-open without sleeping;
  backoff intervals recorded by the stub sleeper; `resilience.enabled=false` byte-identical to
  unguarded. Grep-clean: no `circuitbreaker.internal` import remains.

## H — Hibernate 7 (prove-don't-assume, all existing ITs)

- **H1 (T4)** — The jsonb surface: `ResourceTagsConverter` + `@JdbcTypeCode(SqlTypes.JSON)` ITs green
  against real Postgres (tag persist/read paths across the tag-assignment ITs).
- **H2 (T4)** — The ltree surface: the native ltree rewrite + `@ColumnTransformer("?::ltree")` ITs
  green (hierarchy/path ITs, reparent included).
- **H3 (T4)** — `ddl-auto: validate` boots clean on both example services (the Liquibase schema still
  matches Hibernate 7's expectations — a failed validation is a port defect, not a schema task).

## C — Starter auto-configuration

- **C1 (T4)** — The `ApplicationContextRunner` + `FilteredClassLoader` conditional suites green
  unchanged: every `@ConditionalOnClass` back-off (SecurityFilterChain, OncePerRequestFilter,
  JpaSpecificationExecutor, Keycloak, R4j) still keys on a present/absent class correctly under
  Boot 4's modularized classpath.

## W — Wire-format parity (the port's own risk; design F4)

- **W1 (T5) — the OPA `input` document.** Identify the existing HttpServer-stub test(s) asserting the
  serialized request body `HttpOpaClient` sends; if the pin asserts parsed JSON rather than the
  shape-relevant behavior (field presence/absence, null handling), tighten it to pin:
  `@JsonInclude` honored, no new fields appear, absent-vs-null unchanged. Must pass identically on
  Jackson 3; any default-flip restored on the builder with a naming comment.
- **W2 (T5) — JWT-claims parsing.** The `JwtClaimsSubjectExtractor` tests pin tolerance behavior:
  unknown claims ignored (Jackson 3 flips `FAIL_ON_UNKNOWN_PROPERTIES` — the flip's direction is
  benign here, but pin it explicitly so the tolerance is a decision, not an accident), missing
  claims → the existing empty/deny behavior, malformed payload → the existing failure mode.
- **W3 (T5) — the jsonb tags column.** `ResourceTagsConverter` round-trip test **including a
  Jackson-2-written literal** (a hardcoded string exactly as Jackson 2.18 serialized it — key
  order, empty map `{}`, a non-ASCII value) read back by the Jackson-3 converter into an equal map;
  and the Jackson-3-written form read back equal. Empty/null still maps to `"{}"`, never SQL `NULL`.
- **J1 (T5)** — Grep-clean: no `com.fasterxml.jackson.databind` import outside `build/` and generated
  sources; `jackson-annotations` (`com.fasterxml`) imports remain valid and compile.

## D — Data JPA 4 idiom

- **D1 (T6)** — After the neutral-idiom swap, the existing differential filter ITs
  (`AbacQueryServiceIT`, `PaginationListIT`, `ByoEntityFilterIT`, `CatalogListIsolationIT`,
  `ProductListIsolationIT`) return **the same row sets** as before the swap — ALLOW_ALL contributes
  no predicate, DENY_ALL still yields the empty page, scope ∧ residual composition unchanged
  (AND-don't-replace pinned twice by the sibling-category cells).
- **D2 (T6)** — The deprecation sweep on the 4.0 line is clean: `./gradlew build` output carries no
  deprecation warnings from our own sources (or `STATUS-06.md` documents each accepted one, dated).

## E — End-to-end (the live proof)

- **E1 (T7)** — The full newman fleet green against the rig rebuilt from ported images, each runner
  on its required posture (run-tests/run-matrix on BASIC `ENABLE_OIDC=1`; run-team-matrix on
  `ENABLE_DIRECTORY=1`; resilience on the B3 stub rig; spa-smoke on `ENABLE_SPA=1`; the rest on the
  directory rig). **Zero collection edits** — an assertion that needs changing is a behavior delta
  (slice invariant 1) and therefore a defect.
- **E2 (T7)** — Image freshness verified before trusting E1 (pod created-at newer than the build —
  invariant 7; the `| tail` trap bit twice in PR #68's session).

## P — Performance re-baseline

- **P1 (T7)** — `PERFORMANCE.md` re-baselined per ADR 0021 (same harness, same scenarios, REPS
  discipline, validity gates only — saturation is data, auth failure is red); the ledger carries the
  **double-attribution fine print** (new stack + PR #68's filtered product list, commingled by
  design). No latency *threshold* gates — deltas vs the 7.3 ledger are reported honestly, whatever
  they are.
