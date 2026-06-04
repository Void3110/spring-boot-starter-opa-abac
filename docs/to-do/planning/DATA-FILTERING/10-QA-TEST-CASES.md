---
tags:
  - status/planned
  - type/project
  - area/spring-data
  - area/abac
  - area/opa
---

# Data filtering — QA test cases

> The concrete cases the implementation must satisfy, for [[DATA-FILTERING]]. `U*` = unit, `I*` =
> integration (Testcontainers / `ApplicationContextRunner`), `E*` = end-to-end through the rig.
> Maps onto the tickets in [[01-DECOMPOSITION|01-DECOMPOSITION.md]].

## Core — partial-eval client (`compile`) — T1

- **U1** Trivially-true compile body (`result` absent or `{}`) → `PartialResult.ALLOW_ALL`.
- **U2** Empty `result.queries` (`[]`) → `DENY_ALL`.
- **U3** Single-condition residual (`input.resource.tags.region == "emea"`) → `CONDITIONAL` with one
  `Conjunction` of one `Condition(path="tags.region", EQ, "emea")`.
- **U4** DNF residual (region == emea OR sensitivity in {public}) → `CONDITIONAL` with two
  `Conjunction`s; operators `EQ` and `IN` parsed correctly.
- **U5** Array-membership residual (`"emea" in input.resource.tags.region`) → `Condition(CONTAINS,…)`.
- **U6** Intrinsic-column residual (`input.resource.categoryId == <uuid>`) → `Condition(path="categoryId",…)`,
  not a `tags.*` path.
- **U7** Fail-closed: HTTP 500 / connection-refused / timeout / malformed JSON → `DENY_ALL`.
- **U8** Unsupported expression (operator outside the closed set, or a reference that isn't
  `input.resource.*`) → `DENY_ALL` (and a flag the query was not fully SQL-expressible).
- **U9** Request shape pinned: POST `/v1/compile`, `unknowns == ["input.resource"]`, `input` carries
  `subject`/`action`/`role_definition` but **no** `resource`; query path is the resolved per-type
  `data/<prefix>/<type>/filter == true`.

## Core — batch (`allowAll`) — T2

- **U10** Mixed bulk body `[true,false,true]` → `[true,false,true]` positionally.
- **U11** Fail-closed: 500 / refused / timeout / malformed / length ≠ N → `List.of(false × N)`.
- **U12** Empty input list → empty result, **no HTTP call made** (assert the stub received nothing).

## spring-data — `ResidualSpecificationFactory` — T3

- **U13** `ALLOW_ALL` → `Specification.where(null)` (no predicate contributed).
- **U14** `DENY_ALL` → an always-false predicate (`cb.disjunction()`).
- **U15** `EQ` on `tags.region` → `jsonb_extract_path_text(tags,'region') = 'emea'` (Criteria
  `function(...)` captured).
- **U16** `IN` on `tags.region` → `jsonb_extract_path_text(...) IN (…)`.
- **U17** `CONTAINS` on an array tag → the `?` existence op (`jsonb_exists(tags->'region','emea')`).
- **U18** Intrinsic path (`categoryId EQ <uuid>`) → `root.get("categoryId")` comparison, not JSONB.
- **U19** DNF (two `Conjunction`s) → `OR( AND(...), AND(...) )` predicate structure.
- **I1** *(Testcontainers, real Postgres)* seed Categories with tags
  `{region:emea}`, `{region:apac}`, `{region:[emea,amer]}`, `{sensitivity:public}`; run the generated
  `Specification` for each of `EQ`, `IN`, `CONTAINS`, and a DNF residual; assert the **exact surviving
  row set** each time. Prove `CONTAINS` matches the array-valued tag.

## spring-data — `AbacQueryService` — T4

- **U20** `ALLOW_ALL` residual → only the caller `scope` applied (no extra predicate).
- **U21** `DENY_ALL` residual → empty list (spec is always-false).
- **U22** `CONDITIONAL` → `scope.and(authzSpec)` passed to `repo.findAll`.
- **U23** Allowlist fallback: when the residual is flagged not-fully-SQL **and** `allowlistFallback=on`,
  `allowAll` is invoked over the survivors and `false` rows are dropped; when the flag is off, it is
  **not** invoked.
- **U24** `partialEval.enabled=false` → coarse path (scope + one `allow`), no `compile` call.
- **I2** *(Testcontainers)* end-to-end through the service: two different subjects over the same seeded
  table return the correct (different) row sets.

## starter — wiring — T5

- **I3** `ApplicationContextRunner`: `ResidualSpecificationFactory` + `AbacQueryService` present with
  JPA on the classpath and `partialEval.enabled=true`.
- **I4** Absent without JPA on the classpath (`FilteredClassLoader`); `AbacQueryService` short-circuits
  (or is absent) when `partialEval.enabled=false`.
- **I5** A user-supplied `ResidualSpecificationFactory` / `AbacQueryService` overrides the auto one
  (`@ConditionalOnMissingBean`).
- **I6** `partialEval` properties bind (`enabled`, `allowlistFallback`); metadata present.

## example + rego — T6

- **I7** App boots with the filtered list handlers; `ddl-auto: validate` clean (no schema change).
- **U25 (opa test)** `data.category.filter` evaluates true/false correctly for tagged inputs; existing
  `category` / `catalog` / `product` / `team` cases stay green; `bulk` returns a list of decisions.
- **U26 (opa eval --partial)** for a tag-gated role, `compile` of `data.category.filter == true` with
  `unknowns=[input.resource]` returns the expected residual (region condition); for an unrestricted
  role, returns unconditional-true (→ `ALLOW_ALL`).

## e2e — list-filtering matrix — T7

- **E1** Reader A (gated `region=emea`) `GET …/categories` → only the emea-tagged rows.
- **E2** Reader B (gated `region=apac`) → only the apac-tagged rows (a **different** set from the same
  endpoint, same role shape — tags drive the row set).
- **E3** Allow-all subject → **all** rows.
- **E4** Empty-grant subject → `[]`.
- **E5** The cut is in SQL: assert row **counts** match the tag distribution (not just that the response
  was filtered) — i.e. the residual reached Postgres, the app didn't fetch-all-then-drop.
- **E6** Existing single-decision matrices (`run-matrix.sh`, `run-team-matrix.sh`, `run-tag-matrix.sh`)
  still pass — the filtering path didn't regress single-resource authorization.

## Cross-cutting

- `./gradlew build` green; `opa test` green; `ddl-auto: validate` clean; `CatalogCrudIT` /
  `ProductConcurrencyIT` unchanged-green.
- **Fail-closed proven** at every layer (U7, U8, U11, U14, U21, U24).
- `opa-abac-core` Spring-free; `OpaClient.allow` + `@OpaPreAuthorize` byte-for-byte unchanged
  (`git diff --name-only` on the security module empty for T1–T5).
- **Clean-room scan clean** on all new code + docs.
