---
tags:
  - status/implemented
  - type/project
  - area/architecture
  - area/spring
---

# STATUS — T5: Core Jackson 3 + the three wire-format parity pins

**Status:** ✅ DONE

## What shipped

- **Catalog:** `jackson` 2.18.2 → **3.1.4** (`tools.jackson.core:jackson-databind`, aligned with
  Boot 4.0.7's managed version; annotations ride transitively on the `com.fasterxml` 2.21 line —
  no explicit dep needed, verified by compile). The T4 jsr310 test dep deleted as planned.
- **Core:** `HttpOpaClient` + `CompileResponseParser` imports → `tools.jackson.databind`
  (`writeValueAsBytes`/`readValue`/`readTree` are API-stable; the broad `Exception` catches keep
  every fail-closed path identical — Jackson 3's unchecked `JacksonException` lands in the same
  handlers). Annotations in `AbacContext`/`RoleDefinition`/`HttpOpaClient` records stay
  `com.fasterxml` (Jackson 3 reads them). The public `HttpOpaClient(ObjectMapper, …)` constructor
  type migrated; the **starter call site landed in the same commit** (the named build-breaker).
- **The four mapper construction sites** → `JsonMapper.builder().build()`:
  `OpaAbacAutoConfiguration` (private OPA wire mapper — the isolation comment extended with the
  W1 parity note), `OpaAbacSecurityBeans` (private JWT-claims mapper), `ResourceTagsConverter`
  (static jsonb mapper; catches `JacksonException`, failure contract unchanged),
  `OwnershipAutoConfiguration` (`ObjectProvider<ObjectMapper>` retargeted — on Boot 4 the
  auto-configured Jackson-3 bean **matches again**; the bare-context fallback builds a JsonMapper).
- **Jackson 3 API deltas absorbed:** `JsonNode.fieldNames()` → `propertyNames()` (returns a
  `Collection`, so the AssertJ `.toIterable()` bridges went too — 4 sites in 3 test files);
  `JsonProcessingException` → `JacksonException`.
- **One discovery the plan missed — Hibernate 7.2 does not support Jackson 3.** Its JSON
  `FormatMapper` probes only `com.fasterxml.jackson.databind.ObjectMapper` (javap-verified on
  `JacksonIntegration`), and the mapper is required at boot once any `@JdbcTypeCode(JSON)`
  attribute exists. This is why Boot 4's BOM still manages the jackson-2 line (2.21.4).
  Resolution: **`runtimeOnly("com.fasterxml.jackson.core:jackson-databind")` on
  `opa-abac-spring-data`** — Jackson 2 remains solely as Hibernate's internal JSON engine; zero
  `com.fasterxml` databind/core types in any source (J1 grep-clean); documented for removal when
  Hibernate ORM gains Jackson 3 support. (T4's example ITs masked this: core still carried
  Jackson 2 then.)
- `ProblemDetailContractTest` mapper → Jackson 3 (java.time is ISO-8601 out of the box — the
  JavaTimeModule + WRITE_DATES_AS_TIMESTAMPS ceremony deleted).

## The three wire-parity pins (W1–W3) — asserted, not assumed

- **W1 (OPA `input`)** — new `requestBody_exactShape_noFieldAppearsOrVanishes` in
  `HttpOpaClientTest`: the EXACT property sets of `input`, `subject`, `resource`,
  `role_definition`; pins that the NON_EMPTY `ancestors` stays **absent** when empty (a
  defined-but-empty value would flip `input.resource.ancestors` from rego-undefined — F4's
  fail-closed edge). Existing U7/U7b keep per-field values + role_definition absent-when-null.
- **W2 (JWT claims)** — new `unknownClaims_ignored` in `JwtClaimsSubjectExtractorTest`: tolerance
  pinned as a contract. (Structurally immune to the FAIL_ON_UNKNOWN_PROPERTIES flip — the
  extractor reads a JsonNode tree, no data-binding — but now it's a decision, not an accident.)
  Existing U12–U15 keep missing-sub/roles/exp + malformed behavior.
- **W3 (jsonb tags)** — new `ResourceTagsConverterParityTest`: a **hardcoded Jackson-2.18-written
  literal** (insertion order, non-ASCII unescaped, integral numbers, nested object/array) reads
  back value-equal on Jackson 3; the Jackson-3 rewrite round-trips; fresh writes keep the compact
  insertion-order form; empty/null still `"{}"`, never SQL NULL. (Byte-order parity is
  deliberately NOT asserted — Postgres jsonb does not preserve key order; value parity is the
  contract.)
- **Zero default-flip restores were needed:** all three pins passed against bare
  `JsonMapper.builder().build()` — no Jackson-3 serialization default touches these contracts, so
  there is nothing to configure back (the builder stays bare by evidence, not assumption).

## Tests

- All four library modules green; full `./gradlew build` green (824 + 6 new = **830 tests**,
  0 failures — count verified from the result XMLs). `opa test` untouched.
- **J1 grep-clean:** zero `com.fasterxml.jackson.databind` / `com.fasterxml.jackson.core.*`
  references outside `build/` (the runtimeOnly Hibernate engine has no source-level surface).

## Architecture review + refactor

- **Fail-closed:** every deny path in `HttpOpaClient` (non-200, malformed, missing/non-boolean
  decision, mixed-batch, unsafe path, interrupt) is inside broad catches that Jackson 3's
  unchecked exceptions still hit — the existing U-series fail-closed tests all pass unchanged.
- **Security (the named widening):** absent-vs-null on the OPA input is now *pinned tighter than
  before the port* (W1's exact property sets); claims tolerance pinned (W2); the jsonb
  round-trip against real Jackson-2 rows pinned (W3). The private-mapper isolation design is
  intact — app Jackson customizations still cannot leak into the OPA wire (the wire mapper stays
  private and bare).
- **Boundary:** core stays Spring-free (tools.jackson is a JDK-level dep, allowed); the one
  accepted public-API change (`HttpOpaClient` ctor) is absorbed by our own starter.
- **Refactor:** nothing substantive beyond the planned edits; no churn invented.

## Integration / e2e

Full build (Testcontainers ITs on real Postgres — the jsonb path now runs converter-Jackson-3 over
Hibernate's Jackson-2 engine) green. Live fleet: T7.

## Decisions

- **Hibernate's Jackson-2 engine stays, runtime-only** (above) — the alternative (shipping our own
  Jackson-3 `FormatMapper`) adds public surface and an ADR-level decision for what upstream will
  obsolete; rejected for this mechanical slice.
- No builder restores: evidence-based bare mapper (above).

## Commit

`refactor(core)!: Jackson 3 hard swap + the three wire-parity pins (T5)`
