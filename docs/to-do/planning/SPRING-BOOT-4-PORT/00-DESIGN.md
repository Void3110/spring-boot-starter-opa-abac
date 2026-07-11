---
tags:
  - status/planned
  - type/design
  - area/architecture
---

# SPRING-BOOT-4-PORT — settled design (grill-me 2026-07-11)

> **What this is.** The phase-① settled design for the Spring Boot 3.4 → 4.0 port, produced by a
> grill-me over [RESEARCH.md](RESEARCH.md) (the 2026-07-08 research pass). Every fork below is
> **decided**; `/decompose` turns this into the ticket package + ADR 0026. This slice is deliberately
> **mechanical**: no feature work, no behavior change — the acceptance frame for everything is
> *byte-identical observable behavior on the new line*.

## Problem

The library publishes its 1.0 in late 2026. The whole 3.x Boot line is out of OSS support (3.4 since
2025-12, 3.5 — the final 3.x — since 2026-06); Boot 4.0 (Framework 7 / Security 7 / Jakarta EE 11 /
Hibernate 7 / Jackson 3) is the current line. An external consumer test (2026-07-08, Java 25 + Boot 4)
already ran the 3.4-built artifact cleanly — the port is about **targeting** Boot 4 first-class, not
about making it run. RESEARCH.md holds the per-module inventory; this doc holds the decisions.

## Scope

**In:**
1. 3.4.13 → 3.5.x bump (diagnostic step, T1).
2. Build line: Boot 4.0.x (latest patch at port time), Gradle 9.x wrapper, **Java 25** toolchain +
   bytecode, CI (`.github/workflows/ci.yml`) to JDK 25, starter renames (`-web` → `-webmvc`).
3. Core: Jackson 2 → **Jackson 3** hard swap.
4. Security: `check()` → `authorize()` (three implementors); **Resilience4j 2.2.0 → 2.4.0** with the
   internal-pin elimination.
5. Data: `Specification.where(null)` → the Data JPA 4 neutral idiom; Hibernate 7 proof via the
   existing ITs.
6. Starter conditionals + context-runner tests; examples/codegen (openapi-generator + springdoc
   bumps); Boot test-annotation churn.
7. Docs sweep for version references; ADR 0026; PERFORMANCE.md re-baseline (final ticket).

**Out (decided, do not re-litigate):**
- **JSpecify adoption** — moved to the 1.0-polish backlog (valuable for API consumers, orthogonal to
  the port).
- **SF7-native `CallGuard` impl** — stays ADR 0017 backlog; SF7's resilience core has no circuit
  breaker, so R4j remains necessary regardless.
- **Version bump to 1.0.0** — happens at publish (7.5), not in this PR.
- **Maven-Central publish plumbing** (signing, POM metadata) — 7.5's checklist.
- **Demo SPA** (TypeScript, no Java surface) and **rig images** (APISIX/OPA/Keycloak/Postgres) —
  untouched; only the two example-service images rebuild.
- Any dual 3.5/4.0 artifact (see F1).

## Settled forks

### F1 — Packaging: single-line Boot 4 for 1.0 (ADR 0026 material)
Door explicitly closed on a dual 3.5/4.0 artifact: nothing has ever been published (there is no 3.x
consumer to abandon), the one empirical consumer already runs Boot 4, and dual-line costs are
permanent while the benefit decays to zero on an EOL line. After the merge, `main` requires Boot 4 to
build; the last 3.4 commit is tagged **`pre-sb4-port`** (a tag, not a maintained branch).

### F2 — The 3.5.x step: T1 of this slice, same PR, distinct commit
3.5's OSS support also ended (2026-06), killing the "main stays supported if the port stalls"
argument for a standalone PR. The bump's value is diagnostic (3.5 is the migration-guide baseline;
it surfaces every deprecation we trip as a clean warning sweep before the hard break) and is realized
inside the run. A distinct commit preserves the bisection point between "3.5 semantics" and
"4.0 break". One deep-review, one perf re-baseline.

### F3 — Java target: 25 (toolchain and published bytecode)
The known first consumers of the published artifact target **Java 25 + Boot 4**; broader reach at 21
was considered and declined. Gradle wrapper → latest 9.x (8.12 cannot run on JDK 25 and Boot 4's
plugin needs newer anyway); CI to JDK 25 in the same commit as the wrapper. Side effect to verify:
with a 9.x wrapper + a 25 toolchain, no `JAVA_HOME` override should be needed on a default-JDK-25
machine — local-dev docs mentioning the JDK 21 ritual get updated.

### F4 — Jackson 3: hard swap in core, wire-format parity asserted-not-assumed
No `spring-boot-jackson2` bridge (wrong 1.0 story). Mechanics are small: two core files change
databind imports (`com.fasterxml.jackson.databind` → `tools.jackson.databind`), annotations keep the
`com.fasterxml` package, `api(jackson-databind)` moves to the `tools.jackson` groupId, and the
`HttpOpaClient(ObjectMapper, …)` public-constructor type change is absorbed by our own starter (the
only caller). **The pinned rule — Jackson 3 flips serialization/deserialization defaults, and all
four mapper construction sites are built bare, so three wire contracts could shift silently:**
1. the **OPA `input` document** (what the rego sees),
2. **JWT-claims parsing** tolerance,
3. the **jsonb tags column** representation (`ResourceTagsConverter` — must round-trip against rows
   written by Jackson 2).

The port ticket must identify which existing tests pin each of the three shapes (adding a pin where
none exists), and any default-flip that changes bytes gets explicitly configured back on the builder
(`JsonMapper.builder()…`) with a comment naming the flipped default.

### F5 — Security 7: covariant `authorize()` rename
Three implementors (`OpaAuthorizationManager`, `OpaPreAuthorizeAuthorizationManager`,
`OpaMethodSecurityConfiguration.DeferredAuthorizationManager` — the 7.3 addition the research note's
"both managers" undercounted). Production code never calls `.check()` directly; the 32 test call
sites are a pure method rename because the overrides keep the covariant `AuthorizationDecision`
return type — assertions untouched. Verify-at-port: the `spring-security-access` relocation
(`AccessDeniedException` packaging) and that `AuthorizationManagerBeforeMethodInterceptor` wires
unchanged.

### F6 — Resilience4j: bump to 2.4.0 and delete the internal coupling
Verified against upstream source (2026-07-11): **2.4.0 removed the
`CircuitBreakerStateMachine(name, config, Clock)` constructor we call** — staying put is not an
option once the version moves — **and 2.3.0+ added a public `CircuitBreakerConfig.Builder.clock(Clock)`**.
So: clock moves into the config builder, construction switches to the public
`CircuitBreaker.of(name, config)` factory, and the `internal.*` import in `Resilience4jCallGuard`
is deleted. Same virtual-time testability through pure public API; the B3 deterministic-timing test
suite is the gate that breaker behavior is unchanged; ADR 0017's "contained internal coupling" note
is updated to "eliminated as of R4j 2.4.0".

### F7 — Data JPA 4 / Hibernate 7: mechanical + prove-it
`Specification.where(null)` (3 main sites, 12 test sites) migrates to the Data JPA 4 neutral idiom
(`Specification.unrestricted()` per the upstream deprecation — verify exact name at port). Hibernate 7
is prove-don't-assume: `@JdbcTypeCode(SqlTypes.JSON)`, the jsonb `AttributeConverter`,
`@ColumnTransformer("?::ltree")`, `@TimeZoneStorage(NATIVE)`, and the native ltree rewrite all still
exist in 7 — the Testcontainers ITs + the `ddl-auto: validate` boot are the acceptance, not reading
release notes.

### F8 — Perf re-baseline: once, in-branch, double attribution
The re-baseline is the slice's **final ticket**: PERFORMANCE.md updates in the same PR, measured on
the rig rebuilt from ported images. No intermediate measurement on 3.5. The fine print must state
that the numbers move for **two commingled reasons** — the new stack (Tomcat 11 / Hibernate 7 /
Jackson 3) *and* PR #68's product-list plain→filtered change, which deliberately deferred its own
re-baseline to this one — not separably attributable, and that's accepted.

### F9 — Paper trail and sequencing
**ADR 0026** records F1/F3/F4/F6 (with the `pre-sb4-port` tag noted for archaeology). The **7.4
delta security review runs after the port merges** — the delta since 7.0.5 then covers the port's
churn, and the CVE-audit lands on the post-port dependency tree (the tree 1.0 actually ships).
7.5 (publish) stays held behind 7.4; release notes anchor to the Boot-4/Java-25 story
(PRs #64, #65, #67, #68, this port).

## Acceptance (the claims under proof)

- Full `./gradlew test` green on Boot 4.0.x / JDK 25 (all modules; Testcontainers ITs prove
  Hibernate 7 against real Postgres, `ddl-auto: validate` boots).
- OPA suite untouched and green (rego is not Java; 228 as of PR #68).
- Every e2e matrix green against the rig rebuilt from ported images (gateway entry, same posture
  laws per runner headers).
- The three wire-format pins of F4 hold (byte-parity or an explicitly-configured, commented restore).
- The B3 virtual-time resilience suite green on R4j 2.4.0 (F6).
- No behavior change anywhere: no OpenAPI contract diff, no new endpoints, no rego edits.

## Decompose-level to-dos (not forks — details the tickets must nail)

- Exact versions at port time: Boot 4.0.x latest patch, Gradle 9.x latest, springdoc 3.x line,
  openapi-generator line that supports the Boot-4 flavor **and Gradle 9** (7.11.0 may need a bump for
  either reason; generated code is jakarta + annotations-only Jackson, so expect survival — verify).
- Keycloak `admin-client` 26.x under Jakarta EE 11 — verify, expect none-to-trivial.
- Boot test-annotation churn: `@SpringBootTest` no longer auto-provides `TestRestTemplate`/`MockMvc`
  → `@AutoConfigureTestRestTemplate` / `@AutoConfigureMockMvc` (19 / 17 files); moved
  `spring-boot-test` annotation packages per the modularization.
- Starter conditionals: our string-named `@ConditionalOnClass` back-offs reference non-Boot classes
  (package-stable) — `ApplicationContextRunner`/`FilteredClassLoader` tests re-run as the proof.
- `OwnershipAutoConfiguration` injects the app's `ObjectMapper` via `ObjectProvider` — under Boot 4
  that bean is Jackson 3's; type reference migrates with the swap.
- Docs sweep: version references in README/CLAUDE.md/guides (Java 21, Boot 3.4, Gradle 8.12) +
  the local-dev JDK ritual note.
- CI: `ci.yml` JDK 25 + wrapper in the same commit as the build-line bump.
- Tag `pre-sb4-port` on the last 3.4 commit before the port lands on main.

## Hand-forward (recorded now so the handoff can't lose them)

- After merge: update the machine-local memory note about the JDK-21 `JAVA_HOME` ritual (it inverts —
  this repo becomes default-JDK-25-native).
- 7.4 targets gain nothing new from this slice *by design* (no new surface) — but the CVE audit must
  run on the post-port tree.
- Backlog (not 7.4/7.5 blockers): JSpecify adoption on the public API; SF7-native `CallGuard`.
