---
tags:
  - status/done
  - type/project
  - area/architecture
  - area/spring
---

# STATUS — T6: Data JPA 4 idiom + deprecation zero-out

**Status:** ✅ DONE

## What shipped

Most of the planned `where(null)` work landed early: T4 was **forced** to fix the 15 `where(null)`
sites (`Specification.unrestricted()`) because Data JPA 4's new overloads made a null argument
*ambiguous* — they stopped compiling, and the bump-breaker law took them. What remained for T6:

- The last `Specification.where(...)` site — `AbacQueryService.authorizedSpec`'s
  `Specification.where(tagResidual).or(subtreeSpec)` → **`tagResidual.or(subtreeSpec)`**
  (`where(spec)` was always an identity wrapper; the composition semantics are unchanged and the
  isolation/differential ITs pin it). Zero `Specification.where(` references remain repo-wide.
- **The 4.0-line re-sweep** (full clean build with `-Xlint:deprecation`) surfaced three more
  deprecation classes the T1 map could not see (they are 4.0-line deprecations), all retired with
  jar-verified replacements:
  - Jackson 3 deprecated `JsonNode.asText()` → **`asString()`** (13 sites in
    `CompileResponseParser`, 2 in `JwtClaimsSubjectExtractor`, 2 in `DiscoveryOwnershipResolver`,
    + test/example sites — swept 1:1 incl. the defaulted overload).
  - Framework 7 deprecated `HttpStatus.UNPROCESSABLE_ENTITY` → **`UNPROCESSABLE_CONTENT`**
    (production: `LibraryErrorCode`, `UserMgmtErrorCode`; same 422 integer — the ProblemDetail
    body and the wire are byte-identical; the e2e collections assert the numeric status at T7).
  - MockMvc deprecated `isUnprocessableEntity()` → **`isUnprocessableContent()`** (7 catalog test
    sites).

## Tests

- D1: full clean build green — **830 tests, 0 failures**; the differential filter ITs
  (`AbacQueryServiceIT`, `PaginationListIT`, `ByoEntityFilterIT`, `CatalogListIsolationIT`,
  `ProductListIsolationIT`, `HierarchyListFilterIT`) return the same row sets (ALLOW_ALL
  contributes no predicate; DENY_ALL still empties; scope ∧ residual, AND-don't-replace pinned).
- D2: **zero deprecation warnings from our sources** on the clean lint build (generated code under
  `build/` excluded as always). Nothing needed an accepted-with-note exception.
- `opa test` 228/228 (untouched).

## Architecture review + refactor

Mechanical retirements; the one semantics-adjacent edit (`where(tagResidual).or(subtreeSpec)` →
`tagResidual.or(subtreeSpec)`) preserves the load-bearing composition — the widening still cannot
escape `scope.and(...)` and `notDenied()` still overrides it, proven by the unchanged isolation
ITs. `asString()` is Jackson 3's rename of `asText()` (same coercion semantics — the full parser
suites pass unchanged). No refactor beyond the sweep; no churn invented.

## Integration / e2e

Full clean build with Testcontainers ITs = the gate (green). Live fleet: T7.

## Decisions

- The `UNPROCESSABLE_ENTITY` alias retirement touches production constants but not the wire (same
  int) — done here rather than left as an accepted warning, keeping D2 at literal zero.

## Commit

`refactor(data): retire the last where() + zero the 4.0-line deprecation sweep (T6)`
