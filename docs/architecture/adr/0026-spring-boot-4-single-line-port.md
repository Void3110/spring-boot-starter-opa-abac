---
tags:
  - status/active
  - type/decision
  - area/architecture
  - area/spring
---

# ADR 0026 — Spring Boot 4 single-line port (Java 25, Jackson 3, R4j 2.4.0)

**Status:** Accepted (planned 2026-07-11; the SPRING-BOOT-4-PORT slice implements it)
**Date:** 2026-07-11
**Context tags:** Boot 4 / Framework 7 / Security 7 / Hibernate 7 / Jackson 3, single-line packaging, Java 25 bytecode, wire-format parity, `authorize()`, R4j internal-pin elimination

> The packaging and platform decisions behind the Spring Boot 3.4 → 4.0 port — settled by grill-me
> over the research pass (`docs/to-do/planning/SPRING-BOOT-4-PORT/RESEARCH.md`). The port itself is
> deliberately mechanical (byte-identical behavior); this ADR records the four decisions that were
> genuinely forks, so they aren't re-litigated at implementation or at 1.0.

## Context

The library publishes its 1.0 in late 2026. The entire Boot 3.x line is out of OSS support (3.4
since 2025-12; 3.5 — the final 3.x — since 2026-06); Boot 4.0 (Framework 7 / Security 7 / Jakarta
EE 11 / Hibernate 7 / Jackson 3) is the current line. An external consumer test (2026-07-08, a
Java 25 + Boot 4 microservice) ran the 3.4-built artifact cleanly — so the port is about *targeting*
Boot 4 first-class (compile against it, retire the deprecated APIs we sit on, publish the right
story), not about making it run.

## Decision

1. **Single-line Boot 4 packaging for 1.0 — the dual 3.5/4.0 door is closed.** Nothing has ever been
   published, so there is no 3.x consumer to abandon; the one empirical consumer already runs Boot 4;
   dual-line costs (a CI matrix, a compatibility implementation, a second BOM story) are permanent
   while the benefit decays to zero on an EOL line. After the port merges, `main` requires Boot 4 to
   build; the last 3.4 commit is tagged **`pre-sb4-port`** (a tag for archaeology, not a maintained
   branch).

2. **Java 25 bytecode and toolchain** (with a Gradle 9.x wrapper and JDK-25 CI). The known first
   consumers of the published artifact target Java 25 + Boot 4; broader reach at 21 was considered
   and declined. Java 25 is an LTS; Boot 4 treats it as first-class.

3. **Jackson 3 in `opa-abac-core`, as a hard swap — no `spring-boot-jackson2` bridge** (the bridge is
   deprecated scaffolding, the wrong 1.0 story). The `HttpOpaClient(ObjectMapper, …)` public
   constructor migrates to the Jackson 3 type (a compile-time break absorbed entirely by our own
   starter — acceptable at first publish). **The load-bearing rule: wire-format parity is asserted,
   not assumed.** Jackson 3 flips serialization/deserialization defaults, and three wire contracts
   could shift silently: the OPA `input` document, JWT-claims parsing, and the jsonb tags column
   (which must round-trip rows written by Jackson 2). Each is pinned by a test; any default-flip that
   changes bytes is explicitly configured back on the mapper builder with a comment naming the
   flipped default.

4. **Resilience4j 2.2.0 → 2.4.0, eliminating the `internal` coupling.** Verified against upstream
   source (2026-07-11): 2.4.0 *removed* the `CircuitBreakerStateMachine(name, config, Clock)`
   constructor `Resilience4jCallGuard` calls, and 2.3.0+ added a public
   `CircuitBreakerConfig.Builder.clock(Clock)`. The clock moves into the config; construction
   switches to the public `CircuitBreaker.of(name, config)` factory; the
   `io.github.resilience4j.circuitbreaker.internal.*` import is deleted. Same virtual-time
   testability (ADR 0017's deterministic-timing proof) through pure public API; ADR 0017's
   "contained internal coupling" note is amended to "eliminated as of R4j 2.4.0". The SF7-native
   `CallGuard` backend stays backlog — Framework 7's resilience core has no circuit breaker, so R4j
   remains necessary regardless.

Supporting choices that ride with the port (not forks, recorded for completeness): the 3.4.13 → 3.5.x
bump lands first as a distinct commit in the same PR (the migration-guide baseline + the deprecation
map; a standalone 3.5 PR buys nothing since 3.5 is also EOL); `Specification.where(null)` migrates to
the Data JPA 4 neutral idiom; Hibernate 7 is proven by the existing Testcontainers ITs +
`ddl-auto: validate` (prove-don't-assume); `PERFORMANCE.md` re-baselines **once**, in-branch, with
double attribution (the new stack and PR #68's product-list plain→filtered change are commingled by
design).

## Consequences

- **1.0 ships against Boot 4 / Java 25 only.** Consumers on Boot 3.x/Java 21 use nothing — there is
  no published 3.x artifact and never was. The version requirement is stated in the release notes and
  README.
- The port slice's acceptance frame is **byte-identical observable behavior**: no OpenAPI diff, zero
  rego edits, zero e2e-collection edits, no error-contract change, no fail-closed edge moved. Any
  behavior delta found while porting is a defect, not a decision.
- The **7.4 delta security review runs after the port merges**, so its dependency-CVE audit lands on
  the post-port tree — the tree 1.0 actually ships.
- Deferred, explicitly not lost: JSpecify adoption on the public API (1.0-polish backlog; valuable to
  API consumers) and the SF7-native `CallGuard` impl (ADR 0017 backlog).

## Related

- The slice implementing this: [[SPRING-BOOT-4-PORT]] (design `00-DESIGN.md`, forks F1–F9).
- The resilience seam amended: [[0017-cross-service-http-resilience]].
- The re-baseline it governs together with: [[0021-load-testing-methodology]].
- The API-polish precursors from the same consumer test: `AbacResource` + `Taggable.TAGS_ATTRIBUTE`
  (PR #64).
