---
tags:
  - status/done
  - type/project
  - area/api
  - area/spring
  - area/abac
---

# STATUS — T2: Library IT (real Postgres) — PaginationListIT: two-subject count + stability walk

**Status:** ✅ DONE (2026-06-11)

## What shipped

- **`PaginationListIT`** (`opa-abac-spring-data` test scope) — Testcontainers Postgres 16, the
  `AbacQueryServiceIT` model: own container (`paginationtest`), the shared
  `ResidualSpecificationIT.TestApp` + `FilterTestEntity`/`FilterTestRepository` fixtures, a programmable
  stub `OpaClient` per case. Every paged call carries the fixed total order `createdAt ASC, id ASC`.
- Seed: 5 `region=emea` rows + 3 `region=apac` rows — local to this IT; no shared-fixture change.
- A `batchStub(grantedRegion)` client forces the allowlist-fallback path (compile →
  `PartialResult.unsupported()`) and decides each row by its `region` tag via the per-row context's
  resource attributes — the same grant shape the pure-SQL residual expresses, enabling the I3 parity
  comparison.

## Tests

`./gradlew :opa-abac-spring-data:test` → **126 tests, 0 failures** (122 prior + 4 new), all existing
ITs green unmodified:

- **I1** `twoSubjects_sameData_differentCounts` — totals 5 vs 3 on the same paged call; contents
  disjoint and exactly the per-subject seeded sets.
- **I2** `stabilityWalk_perPage2_visitsAuthorizedSetExactlyOnce` — 3 pages at `perPage=2`; the
  concatenated walk equals the single-page (`perPage=100`) list element-for-element (same order, no
  repeat, none dropped) and the union is exactly the authorized set; `totalElements == 5` on every page.
- **I3** `fallbackPath_pagesSameSequenceAsPureSql` — for each of the 3 windows, the fallback page's ids
  equal the pure-SQL page's ids **in order**, totals both 5 (path-independent contract).
- **I4** `pastTheEnd_emptyContent_exactCount` — page 10 on both paths → empty content, `count` still 5.

## Architecture review + refactor

- The IT revealed **no library bug** — no amendment to the T1 surface was needed.
- Pattern-reuse: mirrors `AbacQueryServiceIT` (container-per-IT, pinned `ContextConfiguration` against
  the shared TestApp, self-contained stub client). The `compileStub` duplication across ITs matches the
  module's existing self-contained-IT convention — kept deliberately; **nothing substantive refactored.**

## Integration / e2e

This ticket *is* the library's integration proof (above). Service-level ITs land in T3/T4; gateway e2e
in T5.

## Decisions

- The fallback parity stub decides rows from `ctx.resource().attributes().get("region")` — exercising
  the real `withResource` per-row context construction rather than canned index-based decisions.
- Determinism is asserted as *walk == single-page list element-for-element*, which subsumes
  no-repeat/no-drop and order stability in one comparison.

## Commit

`test(pagination): PaginationListIT — subject-relative counts, stability walk, fallback parity (real Postgres)`
