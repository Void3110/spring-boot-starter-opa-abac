---
tags:
  - status/planned
  - type/project
  - area/architecture
  - area/spring
---

# SPRING-BOOT-4-PORT — decomposition

> T1…T7, in order. Each ticket is one focused commit's worth of work. Design: [[00-DESIGN]] (all
> forks F1–F9 settled, grill-me 2026-07-11); inventory: [[RESEARCH]]. Pinned by ADR
> [[0026-spring-boot-4-single-line-port|0026]] (packaging, Java 25, Jackson 3, R4j 2.4.0) and
> constrained by ADR [[0017-cross-service-http-resilience|0017]] (the CallGuard seam T3 touches).
> Slice note: [[SPRING-BOOT-4-PORT]]. QA cases: [[10-QA-TEST-CASES]]. **Seven tickets, T1→T7.**

## Sizing-gate verdict (flow guide §2a — recorded, not skipped)

Smells **(a)** and **(d)** do not fire: the slice adds **zero new surface** and every consumer of every
changed mechanism is enumerated below (3 `authorize()` implementors, 32 test call sites, 4 mapper
construction sites, 3+12 `Specification.where(null)` sites, 19/17 `TestRestTemplate`/`MockMvc` files).
Smells **(b)** (all Java deployables) and **(c)** (7 tickets) fire *textually* and were **weighed in
phase ① (grill Q2, maintainer decision: one slice)**: unlike the B4 cautionary case there is no
semantic change riding along — the acceptance frame for every ticket is *byte-identical behavior*, the
blast radius is compiler-enumerated (a version bump either compiles or it doesn't; the full suite +
e2e fleet is the enumerator), and T1–T3 form an independently-landable prefix that de-risks the window.

## Slice invariants (every ticket carries these forward)

1. **Byte-identical behavior** — no OpenAPI contract diff, no new endpoints, no rego edits, no error
   contract change, no fail-closed edge moved. Any behavior delta discovered mid-ticket is a defect to
   fix, not a decision to make.
2. **Wire-format parity is asserted, not assumed** (design F4): the OPA `input` document, JWT-claims
   parsing, and the jsonb tags column must each be pinned by a test; a Jackson-3 default-flip that
   changes bytes gets explicitly configured back on the builder with a comment naming the flipped
   default. The jsonb pin includes round-tripping a Jackson-2-written literal.
3. **`opa-abac-core` stays Spring-free** (Jackson 3 is a JDK-level dep, fine; nothing from
   `org.springframework.*` enters core).
4. **No version bump to 1.0.0, no publish plumbing, no JSpecify, no SF7-native CallGuard** (design
   scope fence) — the artifact version is untouched; those are 7.5 / backlog items.
5. **Demo SPA and rig images untouched** — only the two example-service images rebuild; APISIX/OPA/
   Keycloak/Postgres configs unchanged.
6. **Deprecated-but-working idioms may ride only between T4 and T6** (e.g. `Specification.where(null)`
   compiles-with-warning on the 4.0 line); T6 zeroes the sweep. Nothing deprecated survives the slice
   without an explicit accepted-and-documented note in `STATUS-06`.
7. **Never trust `| tail` exit codes on builds** — check `${PIPESTATUS[0]}` or grep
   `BUILD SUCCESSFUL`; on `./deploy.sh build`, verify image freshness (pod created-at) before trusting
   an e2e run.

## Critical path

```
T1 (Boot 3.5.x + the deprecation map)          ── the migration-guide baseline; distinct commit (F2)
  │
  ├──► T2 (Security: covariant authorize())    ── pre-bump, green on SS 6.5   ┐ parallel;
  └──► T3 (R4j 2.4.0, internal pin deleted)    ── pre-bump, Boot-independent  ┘ independently landable
         │
         ▼
       T4 (THE BUMP: Boot 4.0.x + Gradle 9 + JDK 25 + renames + test-annotation churn)
         │                                     ── headline #1: full build green = Hibernate 7 proven
         ├──► T5 (core Jackson 3 + wire-format parity pins)   ┐ parallel
         └──► T6 (Data JPA 4 idiom + deprecation zero-out)    ┘
                │
                ▼
              T7 (rig rebuild + full e2e fleet + PERFORMANCE.md re-baseline + docs sweep)
                                               ── headline #2: the live proof + the honest numbers
```

- **Sequential:** T1 → (T2 ∥ T3) → T4 → (T5 ∥ T6) → T7. T2/T3 are deliberately *pre-bump* — both
  compile and prove on the 3.5 line (`authorize()` exists since Security 6.4; R4j is
  Boot-independent), so the big-bang T4 commit stays purely mechanical.
- **Independently landable:** **T1+T2+T3** are mergeable on their own (still a 3.5 repo, two
  deprecation classes retired early) if the window is short.
- **Build-breaker watch:** **T4 is the flagged build-breaker by design** — the BOM bump breaks
  compilation repo-wide in one shot (starter rename, moved Boot classes, test-annotation
  auto-provisioning). Everything needed to get the FULL build green lands in the T4 commit; that is
  expected and correct here (there is no smaller compilable step through a BOM major). T5's
  `HttpOpaClient` constructor-type change breaks the starter's call sites — same-commit, files named
  in the ticket.

---

## T1 — Boot 3.4.13 → 3.5.x + the deprecation map

**Goal.** Move to the Boot-4 migration guide's assumed baseline and record every deprecation warning
our code trips — the port map for T2/T3/T6.

**Deliverables.**
- `gradle/libs.versions.toml`: `springBoot` → latest 3.5.x patch (resolve at run time); any
  BOM-coupled pin that 3.5 forces (verify `springDependencyManagement`, `testcontainers`).
- A deprecation sweep of the full `./gradlew build` output (with `-Xlint:deprecation` where needed),
  recorded as a checklist in `STATUS-01.md`: expected entries at minimum — the three
  `AuthorizationManager#check` overrides, `Specification.where(null)` sites; anything unexpected gets
  triaged into T2–T6 or explicitly accepted.
- No source changes beyond what 3.5 *requires* (expected: none).

**Acceptance.** B1 in [[10-QA-TEST-CASES]]: full `./gradlew build` green on 3.5.x (all modules,
Testcontainers ITs, codegen); `opa test infra/opa/policies` unchanged (228). The deprecation map
exists in `STATUS-01.md`.

**What NOT to touch.** No Gradle wrapper change yet (8.12 runs 3.5 fine — the wrapper moves with T4);
no Java-version change; no dependency bumps beyond what 3.5's BOM forces; invariants 1–7.

## T2 — Security 7 pre-migration: covariant `authorize()` (on the 3.5 line)

**Goal.** Retire the deprecated `check()` contract while still on Security 6.5, so T4 carries zero
Security-API work.

**Deliverables.**
- `OpaAuthorizationManager`, `OpaPreAuthorizeAuthorizationManager`, and
  `OpaMethodSecurityConfiguration.DeferredAuthorizationManager` (the 7.3 addition): `check(...)` →
  `authorize(...)` with the **covariant `AuthorizationDecision` return** (narrower than the
  interface's `AuthorizationResult`), `@Override` retained, the `check()` overrides deleted.
- The 32 test call sites across `OpaPreAuthorizeAuthorizationManagerTest`,
  `OpaAuthorizationManagerTest`, `OpaPreAuthorizeAuthorizationManagerResolutionTest` (+ any others the
  T1 map lists): method rename only — assertions stay byte-identical (the covariant return keeps
  `AuthorizationDecision` flowing).
- The in-code comment at `OpaPreAuthorizeAuthorizationManager` that anticipated this migration:
  updated, not deleted (it now documents the done state).

**Acceptance.** S1–S3 in [[10-QA-TEST-CASES]]: `./gradlew :opa-abac-spring-security:test` green;
full `./gradlew build` green (the interceptor path — `AuthorizationManagerBeforeMethodInterceptor`
dispatches to `authorize()` since 6.4 — proven by the existing `@OpaPreAuthorize` ITs unchanged).
Zero `check(` references remain outside vendored/generated code (grep-clean).

**What NOT to touch.** No behavior change in any manager (same decisions, same exceptions, same
`AbacContext` construction); no signature change beyond the rename+widening; the R4j surface (that's
T3); invariants 1–7.

## T3 — Resilience4j 2.2.0 → 2.4.0: delete the internal coupling

**Goal.** Bump R4j to the current line and replace the `internal.CircuitBreakerStateMachine`
construction with pure public API — the Clock seam moved into `CircuitBreakerConfig` in 2.3.0+
(verified against upstream source 2026-07-11; 2.4.0 *removed* the Clock constructor, so the bump
forces this).

**Deliverables.**
- `gradle/libs.versions.toml`: `resilience4j` → 2.4.0 (update the stale "managed by the Spring Boot
  BOM" comment — the catalog pin is authoritative).
- `Resilience4jCallGuard.buildBreaker(...)`: `.clock(clock)` on the `CircuitBreakerConfig.custom()`
  builder; construction via `CircuitBreaker.of(name, cbConfig)`; the
  `io.github.resilience4j.circuitbreaker.internal.*` import **deleted**.
- Javadoc on `Resilience4jCallGuard` (the "Deterministic timing" section + the buildBreaker comment):
  updated — the seam is now public API.
- ADR [[0017-cross-service-http-resilience|0017]]: the "contained internal coupling" note amended
  ("eliminated as of R4j 2.4.0", dated) — an addendum line, not a rewrite.

**Acceptance.** R1 in [[10-QA-TEST-CASES]]: the existing B3 deterministic-timing suite
(`./gradlew :opa-abac-spring-security:test` — breaker open/half-open via virtual clock, backoff via
recorded sleeper, kill-switch byte-identical) green **unchanged** on 2.4.0. Grep-clean:
no `circuitbreaker.internal` import anywhere.

**What NOT to touch.** No breaker semantics change (window type/size, thresholds, no automatic
transition — the config block stays as-is apart from `.clock()`); no `CallGuard` interface change;
no retry/backoff logic change; invariants 1–7.

## T4 — THE BUMP: Boot 4.0.x + Gradle 9 + JDK 25 + renames + test-annotation churn

**Goal.** Land the whole mechanical break in one compiler-driven commit: the repo builds and all
tests pass on Boot 4.0.x / Framework 7 / Security 7 / Hibernate 7 / JDK 25 / Gradle 9.

**Deliverables.**
- `gradle/libs.versions.toml`: `springBoot` → latest 4.0.x patch; `springDependencyManagement`
  (verify it's still the idiom on Boot 4 — if the plugin is retired for `platform()` BOM imports,
  migrate the four consuming build files); `springdocOpenApi` → the 3.x line; `openapiGenerator` →
  the release that supports the Boot-4 flavor **and Gradle 9**; `testcontainers`/`junit`/`mockito`
  bumps only if Boot 4's baseline forces them.
- `gradle/wrapper/gradle-wrapper.properties`: latest Gradle 9.x. Root build files: Java toolchain
  21 → **25**.
- `.github/workflows/ci.yml`: `setup-java` → JDK 25 (same commit as the wrapper).
- Example build files: `spring-boot-starter-web` → `spring-boot-starter-webmvc`; sweep for moved
  Boot classes (`FilterRegistrationBean`, `ClientHttpRequestFactoryBuilder`/`Settings` in the
  user-service `RestClient` config, `spring-boot-test` annotation packages per the modularization).
- Test churn: `@AutoConfigureTestRestTemplate` / `@AutoConfigureMockMvc` added where Boot 4 no longer
  auto-provides (19 files reference `TestRestTemplate`, 17 `MockMvc` — the compiler/test failures
  enumerate the exact set).
- Verify-at-port items closed and recorded in `STATUS-04.md`: `spring-security-access` relocation
  (does `AccessDeniedException` packaging move for us?); keycloak-admin-client 26.x under EE 11;
  the openapi-generator output still compiles (jakarta + `com.fasterxml` annotations only).
- **This is the flagged build-breaker commit**: everything the BOM bump breaks lands here — nothing
  else does (no idiom modernization, no Jackson work; those are T5/T6).

**Acceptance.** B2 + H1–H3 + C1 in [[10-QA-TEST-CASES]]: full `./gradlew build` green on JDK 25 with
**no `JAVA_HOME` override** (Gradle 9 toolchain resolution — F3's side effect, verified and noted);
Hibernate 7 proven by the existing ITs (jsonb `AttributeConverter`, `@JdbcTypeCode(SqlTypes.JSON)`,
`@ColumnTransformer("?::ltree")` + the native ltree rewrite, `@TimeZoneStorage(NATIVE)`) and the
`ddl-auto: validate` boot; the starter's `ApplicationContextRunner`/`FilteredClassLoader` conditional
tests green; `opa test` unchanged (228).

**What NOT to touch.** `Specification.where(null)` sites stay as-is (deprecated-but-compiling —
T6's job); core's Jackson 2 pin stays (T5's job — the mixed intermediate state is deliberate:
core/starter keep constructing `com.fasterxml` mappers from our own catalog pin, and
`OwnershipAutoConfiguration`'s `ObjectProvider<ObjectMapper>` simply falls back to its `::new` when
no Jackson-2 bean exists on Boot 4 — the fallback path already exists and is fail-safe); no rego, no
SPA, no rig configs; invariants 1–7.

## T5 — Core Jackson 3 + the three wire-format parity pins

**Goal.** Swap `opa-abac-core` (and every library mapper site) to Jackson 3 with wire-format parity
proven, not assumed.

**Deliverables.**
- `opa-abac-core/build.gradle.kts`: `api(jackson-databind)` → the `tools.jackson.core:jackson-databind`
  3.x coordinate (catalog: new `jackson3` version pin aligned with Boot 4's managed version;
  annotations remain `com.fasterxml.jackson.core:jackson-annotations` — verify whether an explicit
  dep is now needed since Jackson 3 splits them).
- Import swap `com.fasterxml.jackson.databind` → `tools.jackson.databind` in `HttpOpaClient` +
  `CompileResponseParser` (annotations in `AbacContext`/`RoleDefinition`/DTOs untouched).
- The four mapper construction sites migrate to the Jackson 3 idiom (`JsonMapper.builder()…build()`
  or equivalent): `OpaAbacAutoConfiguration` (the private OPA wire mapper),
  `OpaAbacSecurityBeans` (the private JWT-claims mapper), `OwnershipAutoConfiguration`
  (`ObjectProvider<ObjectMapper>` retargets to the Jackson-3 type — Boot 4's auto-configured bean
  now matches again), `ResourceTagsConverter` (the static jsonb mapper in `opa-abac-spring-data`).
- `HttpOpaClient(ObjectMapper, …)` public constructor: type migrates to Jackson 3; the starter call
  site (`OpaAbacAutoConfiguration:80`) lands in the same commit (**the named build-breaker**).
- **The three wire-parity pins (W1–W3)**: identify the existing test that pins each shape, add one
  where none exists; any Jackson-3 default-flip that changes bytes is configured back on the builder
  with a comment naming the flipped default (invariant 2).

**Acceptance.** W1–W3 + J1 in [[10-QA-TEST-CASES]]: `:opa-abac-core:test` (HttpServer-stub
request-body assertions = the OPA `input` pin), `:opa-abac-spring-security:test` (claims extraction),
`:opa-abac-spring-data:test` (jsonb round-trip **including a Jackson-2-written literal**), full
`./gradlew build` green. Grep-clean: no `com.fasterxml.jackson.databind` import outside generated
code.

**What NOT to touch.** No mapper *behavior* customization beyond restoring flipped defaults (the
private-mapper isolation design is load-bearing — the app's Jackson customizations must keep NOT
leaking into the OPA wire); the OpenAPI-generated DTOs (annotations-only, survive as-is); core stays
Spring-free; invariants 1–7.

## T6 — Data JPA 4 idiom + deprecation zero-out

**Goal.** Retire every deprecation the port line introduces, so the slice ends warning-clean.

**Deliverables.**
- `Specification.where(null)` → the Data JPA 4 neutral idiom (`Specification.unrestricted()` per the
  upstream deprecation — verify the exact replacement): `ResidualSpecificationFactory` (incl. its
  javadoc), `AbacQueryService` (2 sites), + the 12 test sites (`ByoEntityFilterIT`,
  `AbacQueryServiceIT`, `PaginationListIT`, `ResidualSpecificationFactoryTest` comment).
- The T1 deprecation map re-swept on the 4.0 line: every remaining warning fixed or explicitly
  accepted with a dated note in `STATUS-06.md`.

**Acceptance.** D1–D2 in [[10-QA-TEST-CASES]]: `:opa-abac-spring-data:test` green — the composed
residual/scope semantics byte-identical (the existing differential ITs are the pin:
same rows before/after); full `./gradlew build` green with the deprecation sweep clean.

**What NOT to touch.** The AND-don't-replace composition in `AbacQueryService` (scope ∧ residual —
the neutral-element swap must not alter how `or(subtreeSpec)` composes); no query-shape change (the
ITs' row sets are the proof); invariants 1–7.

## T7 — Rig rebuild + full e2e fleet + PERFORMANCE.md re-baseline + docs sweep

**Goal.** Prove the ported system live through the gateway, re-baseline the performance ledger once
(F8), and reconcile every doc the port dates.

**Deliverables.**
- Rig rebuilt from ported images (`./deploy.sh build` — verify image freshness per invariant 7), the
  full newman fleet green per the per-runner rig-posture law (runner headers + mulch `mx-50a48b`).
- `PERFORMANCE.md` re-baselined per ADR [[0021-load-testing-methodology|0021]] (same harness, REPS
  discipline), with the **double-attribution fine print** (F8): numbers move for two commingled
  reasons — the new stack (Tomcat 11 / Hibernate 7 / Jackson 3) *and* PR #68's product-list
  plain→filtered change deferred to this re-baseline — not separably attributable, accepted.
- Docs sweep: root `CLAUDE.md` build line (Java 25 · Boot 4.0 · Gradle 9.x) + the Testcontainers/CI
  notes if versions moved; `README.md`; `infra/README.md` if any command output changed; the ADR 0017
  addendum landed in T3 checked; ADR [[0026-spring-boot-4-single-line-port|0026]] cross-checked
  against what actually shipped (it was written at decomposition time); `docs/guides/*` version
  references.
- The `pre-sb4-port` tag: **an operator action at merge time** (tag the last 3.4 commit on `main`
  before this branch lands) — recorded in the operator notes, not performed by the run.

**Acceptance.** E1–E2 + P1 in [[10-QA-TEST-CASES]]: every matrix runner green on its required rig
posture; the perf re-baseline validity gates pass (ADR 0021 — validity, not latency thresholds);
`PERFORMANCE.md` updated with the double-attribution note; the docs sweep grep
(`grep -rn 'Java 21\|Boot 3\.4\|Gradle 8' docs/ README.md CLAUDE.md` modulo historical
STATUS/review notes) comes back clean or explicitly-historical.

**What NOT to touch.** Rig configs/images other than the two example services; the load-harness
scripts (they are stack-agnostic — only the ledger updates); no matrix collection edits (this port
must pass the suite *as it stands* — an edited assertion is a smell of a behavior change, invariant
1); invariants 1–7.

---

## Cross-cutting acceptance

- Full `./gradlew build` green on JDK 25 / Gradle 9.x / Boot 4.0.x with no `JAVA_HOME` override —
  all library modules + both example services + OpenAPI codegen + Testcontainers ITs.
- `opa test infra/opa/policies` — **228/228, byte-untouched** (zero rego is itself an acceptance).
- The full newman fleet green on the rebuilt rig, per-runner postures honored.
- The three wire-format parity pins (W1–W3) hold.
- Zero deprecation warnings from our own code at slice end (or an explicit dated acceptance note).
- The fail-closed invariant holds on every error path — unchanged, byte-identical (invariant 1).
