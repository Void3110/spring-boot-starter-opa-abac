---
tags:
  - status/done
  - type/project
  - area/spring-data
  - area/abac
---

# STATUS — T3: ResidualSpecificationFactory — residuals → JPA Specification over JSONB (spring-data)

> Filled in at the T3 checkpoint during the autonomous run. See [[01-DECOMPOSITION]] T3 and the
> per-ticket loop in [[AUTONOMOUS-IMPLEMENTATION-PROMPT]].

**Status:** ✅ done

## What shipped

`dev.dmitriikonovalov.opaabac.data.filter`:

- **`ResidualSpecificationFactory`** — `from(PartialResult) → Specification<T>`:
  - `ALLOW_ALL` → `Specification.where(null)` (no predicate — the caller's scope is the only constraint);
  - `DENY_ALL` **and a null residual** → `(r,q,cb) -> cb.disjunction()` (always-false — the fail-closed shape);
  - `CONDITIONAL` → `OR( AND(conditions) )` over the DNF; a vacuously-true (empty) conjunction short-circuits
    to `cb.conjunction()` (always-true).
  - per-`Condition`: `tags.<k>` EQ/NEQ/IN via `jsonb_extract_path_text(tags,'<k>')`; `CONTAINS` via the `?`
    existence op; a non-`tags` path via `root.get("<field>")` (intrinsic column). All literals **bound** via
    the criteria builder — no SQL strings.
- **`JsonPathDialect`** (package-private seam) + `JsonPathDialect.Postgres` — the one place that knows the
  JSONB function names (`jsonb_extract_path_text` for scalars, `jsonb_extract_path` + `jsonb_exists` for
  array membership). A non-Postgres dialect is a seam, **not** a rewrite (second dialect deliberately not built).
- **Build:** added Testcontainers + `spring-boot-starter-test` + the podman socket / `TESTCONTAINERS_RYUK_DISABLED`
  test-task config to `opa-abac-spring-data/build.gradle.kts` (mirroring the example modules).

## Tests

`./gradlew :opa-abac-spring-data:test` green (13 new + all pre-existing).

- **Unit (`ResidualSpecificationFactoryTest`, Mockito Criteria capture, U13–U19):** `ALLOW_ALL`→null
  predicate; `DENY_ALL`/null→`disjunction()`; EQ→`jsonb_extract_path_text … = …`; IN→`… .in(…)`;
  CONTAINS→`jsonb_exists` + `isTrue`; intrinsic→`root.get("categoryId")` (no JSONB fn); DNF→two `and()` +
  one `or()`.
- **Testcontainers IT (`ResidualSpecificationIT`, real Postgres 16 + JSONB, I1):** seeds rows tagged
  `{region:emea}`, `{region:apac}`, `{region:[emea,amer]}`, `{sensitivity:public}`; asserts the **exact**
  surviving row set for EQ (scalar emea), IN (emea+apac scalars), CONTAINS "amer" (array only), CONTAINS
  "emea" (scalar **and** array — see Decisions), DNF (apac ∪ public), ALLOW_ALL (all), DENY_ALL (none).

## Architecture review + refactor

- **Module boundary — verified clean.** `git diff` shows **core untouched** (`PartialResult` consumed
  read-only). The factory has **no** OPA-wire knowledge — no `HttpClient`/`ObjectMapper`/`JsonNode`/`/v1/`;
  the only "compile" tokens are javadoc describing what a residual *is*. core knows nothing of JPA; the
  factory knows nothing of the Compile API — the module-layer separation holds.
- **Fail-closed — verified.** `DENY_ALL` and a null residual both → `cb.disjunction()`; the meaningless
  `CONTAINS` on a scalar intrinsic column also → `cb.disjunction()` (deny that row), never an open predicate.
- **Injection-safe / dialect-portable — verified.** Every literal is bound via `cb.literal`/`cb.function`;
  no SQL string interpolation anywhere.
- **Pattern reuse.** The scalar-vs-array split mirrors the [[TAG-DICTIONARY]] `resource_tag_values` normalize.
- No production-code refactor needed; the design is fail-closed by construction.

## Integration / e2e

The Testcontainers IT *is* the integration proof (real Postgres + JSONB, exact row sets). e2e through the
gateway is T7.

## Decisions recorded

Two findings the IT surfaced and I resolved mid-ticket:

1. **Auditing in a non-app module.** `AbstractAuditableEntity.createdAt` is `@CreatedDate nullable=false`,
   populated by `AuditingEntityListener` — so the IT's bootstrap config needs `@EnableJpaAuditing` **plus** a
   `DateTimeProvider` yielding **`OffsetDateTime`** (matching the `timestamptz` columns; a `LocalDateTime`
   provider throws on save). Known gotcha (Mulch mx-5fb01d); applied here.
2. **⚠️ `CONTAINS` matches a scalar tag too — and that is correct.** The Postgres `?` operator on a JSONB
   **string** scalar tests string equality (on an **array** it tests element membership). So `region CONTAINS
   "emea"` matches **both** the scalar `region=="emea"` row and the `[emea,amer]` array row. This **agrees**
   with the single-decision Rego, whose `resource_tag_values` normalizes a scalar to a singleton set — so a
   scalar tag satisfies a membership grant exactly as an array does. This SQL/Rego agreement is what keeps
   the **list and a single-GET deciding the same rows** (an ADR-0005/0006 requirement). The IT now asserts
   the policy-consistent set; recorded as a Mulch pattern (see below).

## Commit

`feat(data-filtering): T3 ResidualSpecificationFactory — residual → JPA Specification over JSONB` — _(SHA at commit)_
