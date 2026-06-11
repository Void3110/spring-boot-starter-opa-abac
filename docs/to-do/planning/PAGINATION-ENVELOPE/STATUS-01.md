---
tags:
  - status/done
  - type/project
  - area/api
  - area/spring
  - area/abac
---

# STATUS — T1: Library — paged findAuthorized overload (all four paths) + unsorted-Pageable guard + unit tests

**Status:** ✅ DONE (2026-06-11)

## What shipped

- **The additive 5-arg overload** `findAuthorized(repo, scope, queryContext, subtreeSpec, Pageable): Page<T>`
  in `AbacQueryService` (`opa-abac-spring-data`), mirroring the unpaged dispatch exactly:
  - **Guard first:** `pageable.getSort().isUnsorted()` → `IllegalArgumentException` naming the
    sorted-`Pageable` rule, thrown before any OPA or repository call (`Pageable.unpaged()` fails the
    same check — it carries no sort).
  - **Kill-switch:** coarse `allow` deny → `Page.empty(pageable)` (no repo call); allow →
    `repo.findAll(scopeOnly(scope).and(notDenied()), pageable)` — the deny-override stays AND-ed.
  - **`fromError`:** `Page.empty(pageable)`, no repo call, no batch even with the allowlist on — a
    failed compile empties the page *including the count*.
  - **Allowlist fallback:** candidates fetched **SQL-sorted** (`findAll(scope, pageable.getSort())`),
    `batchFilter` unchanged (order-preserving), the window sliced in memory via `sliceInMemory(...)` →
    `PageImpl(slice, pageable, survivors.size())` — exact count at the path's existing fetch-all cost.
  - **Pure-SQL:** the identical composition paged — `repo.findAll(authorizedSpec(...), pageable)`;
    Spring Data derives the `COUNT` from the same specification.
- **Javadoc** on the overload: per-path count semantics, the guard rationale, the fallback cost note.

## Tests

- New unit cases **U1–U7** in `AbacQueryServiceTest` (9 test methods — U4 and U6 each carry a second
  branch case: fallback-off-no-batch, kill-switch allow/deny): pure-SQL spec+pageable pass-through and
  count-from-repo (U1); subtreeSpec composition without batch (U2); the guard for unsorted + unpaged
  before any call (U3); fallback slice `[a,c]`/`[d,e]` with `totalElements == 4` of 5 candidates and the
  sort forwarded to the candidate fetch (U4); fallback past-the-end keeps exact count (U5); kill-switch
  both verdicts (U6); `fromError` → empty + 0 + no repo + no batch (U7).
- **U8 (regression):** all 15 pre-existing `AbacQueryServiceTest` cases pass **unmodified**; module
  total `./gradlew :opa-abac-spring-data:test` → **122 tests, 0 failures** (incl. all existing ITs).

## Architecture review + refactor

- **Fail-closed:** guard-before-everything, error-empties-count, survivor-count-only, deny-AND-ed —
  all four checked against the fail-closed checklist and proven by U3/U7/U4-U5/U6 respectively.
- **Additivity:** new arity only; 3-arg/4-arg signatures and behavior untouched; `opa-abac-core` diff
  is zero (`git diff --stat -- opa-abac-core/` empty).
- **One substantive finding, refactored:** the load-bearing pure-SQL composition
  `scope.and(tagResidual.or(subtreeSpec)).and(notDenied)` was duplicated between the unpaged and paged
  paths — silent-drift risk on the slice's central invariant. Extracted to one private
  `authorizedSpec(scope, residual, subtreeSpec)` used by both; behavior identical; all 122 tests green
  after the refactor.

## Integration / e2e

N/A for this ticket (T2 carries the real-Postgres proof; the module's existing ITs ran green as part
of the test task).

## Decisions

- The in-memory fallback slice copies the sublist (`List.copyOf`) so the returned `Page` content is
  immutable and detached from the working list — matches the library's safe-return style.
- No 4-arg paged convenience overload (`repo, scope, ctx, pageable`): the design pins exactly one paged
  signature; callers pass `subtreeSpec = null` (the catalog authorizer resolves it anyway).
- Mulch recording deferred to T6 by design — the decomposition assigns the paged-seam pattern record
  there; recording it now would duplicate.

## Commit

`feat(pagination): paged findAuthorized overload — four paths, exact count, unsorted-Pageable guard`
