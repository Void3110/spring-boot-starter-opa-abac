---
tags:
  - status/planned
  - type/project
  - area/api
  - area/spring
  - area/abac
---

# 10 — QA test cases: Pagination envelope (Phase 5.95)

> The concrete cases each [[01-DECOMPOSITION|ticket]]'s *Acceptance* references. **U** = unit (library —
> the paged seam against the programmable stub `OpaClient` + a mock repo, no DB), **I** = integration
> (the library's real-Postgres Testcontainers IT, then per-service ITs), **E** = e2e through the gateway
> (newman — one **new** matrix + the suite-wide envelope migration). Plus a **codegen** block (the build
> proves the spec). This is a **list-shape** change: no authorization decision moves; the cases assert
> the **envelope, the exact subject-relative `count`, and pagination determinism** — and that every row
> set the suite already pins stays **numerically identical**. Zero Rego changes (`opa test` untouched).

## Conventions
- **Unit (library):** the existing `AbacQueryServiceTest` pattern — a programmable stub `OpaClient`
  (compile/allow/allowAll) + a mock `JpaSpecificationExecutor`; assert the `Pageable`/`Sort` the repo
  receives and the `Page` returned. No Postgres, no rig.
- **Integration (library):** `PaginationListIT` follows `AbacQueryServiceIT`/`HierarchyListFilterIT` —
  **real Postgres via Testcontainers** (never H2), the in-process `com.sun.net.httpserver.HttpServer`
  OPA stub, the module's test entity. Fixed order `createdAt ASC, id ASC` in every paged call.
- **Integration (services):** extend each service's existing IT setup; drive the generated endpoints;
  assert envelope members, defaults, the ADR-0011 `problem+json` negatives, past-the-end.
- **e2e:** full rig `./profile.sh up` → `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2`;
  `./deploy.sh build` to force new app images; mint tokens **in-network** (issuer `keycloak:8888`);
  runtime ids in **collection** scope; the pagination fixtures are a **dedicated namespaced set** in the
  fixture-id registry (shared fixtures must not grow — other matrices pin exact counts on them).
- **The pinned contract (from ADR [[0012-pagination-envelope|0012]], the values the cases assert):**

  | Semantic | Pinned |
  |---|---|
  | Envelope | `{count, page, perPage, items}` — `count` = the subject's authorized total, all pages |
  | Params | `page` ≥ 0 (default 0, 0-based) · `perPage` 1–100 (default 20) |
  | Violation | `400` `problem+json` `errorCode=VALIDATION_FAILED` — no clamping |
  | Past-the-end | `200` + empty `items` + exact `count` — never 404 |
  | Order | `createdAt ASC, id ASC` everywhere; unsorted `Pageable` at the seam → thrown |

---

## Unit — library: the paged `findAuthorized` overload (T1)

| # | Case | Expected |
|---|------|----------|
| **U1** | Pure-SQL path: a fully-supported residual + a sorted `PageRequest` | `repo.findAll(spec, pageable)` invoked with the **same combined** `scope.and(tagResidual.or(subtreeSpec)).and(notDenied)` composition as the unpaged path and the given pageable; the repo's `Page` returned as-is (content + `totalElements` from the count query) |
| **U2** | `subtreeSpec = null` on the paged path | reduces to `scope.and(tagResidual).and(notDenied)` — identical composition to the 4-arg unpaged behavior, paged |
| **U3** | **The guard:** an unsorted `PageRequest`, and `Pageable.unpaged()` | both → `IllegalArgumentException` before any OPA or repo call (the message names the sorted-`Pageable` rule) |
| **U4** | **Fallback path** (`unsupported()` residual + allowlist on): candidates A,B,C,D,E; batch allows A,C,D,E | candidates fetched via `repo.findAll(scope, sort)` (the pageable's sort, **not** unsorted); page 0/`perPage=2` → `[A,C]`, page 1 → `[D,E]`; `totalElements == 4` (the filtered size, not 5); input order preserved |
| **U5** | Fallback path, past-the-end (`page=5`, 4 survivors) | empty content, `totalElements == 4` — exact count survives an empty page |
| **U6** | **Kill-switch** (`enabled=false`): deny, then allow | deny → `Page.empty` (no repo call); allow → `repo.findAll(scope.and(notDenied), pageable)` — the deny tag stays AND-ed even degraded |
| **U7** | **`fromError`** residual | `Page.empty` (`totalElements == 0`), **no repo call** — a failed compile empties the page, count included |
| **U8** | Regression: the 3-arg and 4-arg overloads | every pre-existing `AbacQueryServiceTest` case passes **unmodified** — the overload is purely additive |

## Integration — library, real Postgres: `PaginationListIT` (T2)

| # | Case | Expected |
|---|------|----------|
| **I1** | **Two subjects, same data, different `count`:** rows tagged so A's residual matches 5, B's matches 3; same scope, same paged call | `totalElements` 5 vs 3; contents disjoint per the residuals; the same URL-shape query answers per-subject |
| **I2** | **The stability walk:** subject A, `perPage=2`, walk pages 0..N (order `createdAt ASC, id ASC`) | the union of all pages == exactly A's authorized set; **no row repeated, none dropped**; `count` identical on every page |
| **I3** | **Fallback parity:** the same data behind an `unsupported()` residual + allowlist | the page slice + `count` + **row order** match what the pure-SQL path returns for the same grant shape (path-independent contract) |
| **I4** | Past-the-end | a page beyond the last → `200`-equivalent empty content, `totalElements` exact |

## Integration — catalog service (T3)

| # | Case | Expected |
|---|------|----------|
| **I5** | Envelope + defaults on the three lists (`listCatalogs`, `listCategories`, `listProducts`) | no params → `page=0`, `perPage=20` applied; body carries `count`/`page`/`perPage`/`items`; `count` == the authorized total (categories: the residual-filtered total), `items` ≤ `perPage`, ordered `createdAt ASC, id ASC` |
| **I6** | The strict negatives + past-the-end | `perPage=101`, `perPage=0`, `page=-1` → **`400`** `application/problem+json` `errorCode=VALIDATION_FAILED` (the generated constraint → the existing ADR-0011 advice — no clamping); a past-the-end page → **`200`** + empty `items` + exact `count` |

## Integration — user-service (T4)

| # | Case | Expected |
|---|------|----------|
| **I7** | Envelope + defaults on a representative pair (one top-level list, one team-scoped list) | as I5 — envelope members, defaults, order, `count` == the gated query's total |
| **I8** | One strict negative + one past-the-end | as I6 — `400 VALIDATION_FAILED` `problem+json`; `200` + empty + exact `count` |
| **I9** | **The `/internal` note** (grep/review — not a runtime test) | the internal surface's definition site carries the one-line *unpaginated by design (bounded, in-network)* note; **no internal endpoint's shape changed** |

## Codegen — the build proves the spec (T3/T4)

| # | Case | Expected |
|---|------|----------|
| **C1** | Both specs declare `PageEnvelope` + the `<Resource>Page` `allOf` compositions + shared `Page`/`PerPage` parameter components (bounds + defaults in the schema) | `./gradlew build` regenerates the API interfaces + `<Resource>Page` models; the list signatures carry the params; drift = build break |
| **C2** | No public list still returns a bare array | no list op references a bare `array` response in either spec; every controller maps `Page<T>` → the generated envelope |

## e2e — through the gateway (T5)

| # | Case (live, through APISIX) | Expected |
|---|------|----------|
| **E1** | **The count contrast:** viewer vs editor, same list URL (the pagination fixture set) | different `count` values (assert the **numbers**, not just presence) — same policy, same endpoint, subject-relative totals |
| **E2** | **The paged walk:** `perPage=2` over the fixture set | pages **disjoint**; union == the single-page (`perPage=100`) result; `count` stable across pages |
| **E3** | **The live strict negative:** `perPage=500` | `400` `application/problem+json` `errorCode=VALIDATION_FAILED` through the gateway |
| **E4** | **The suite-wide regression:** every updated existing matrix (`data-filter`, `hierarchy-list`, `catalog-e2e`, catalog/tag/team) | green against the envelope with **numerically identical** row-count expectations — the cut did not move, only the shape (`json.length` → `json.count`/`json.items.length`) |

## Fail-closed checklist (must all hold — nothing widens)

- [ ] **A failed compile empties the page, count included.** `fromError` → empty `items`, `count = 0`,
      no repo call (U7) — no policy answer, no rows, no count leak.
- [ ] **The fallback only narrows.** The in-memory page is a slice of the batch-filtered survivors; a
      short/all-false batch drops rows; `count` is the filtered size, never the candidate count (U4/U5, I3).
- [ ] **The kill-switch keeps the deny.** `partialEval.enabled=false` still ANDs `notDenied` into the
      paged query (U6) — toggling it cannot make a denied row listable.
- [ ] **The guard fails loud, not open.** An unsorted/unpaged `Pageable` is a thrown error (U3), never a
      silently nondeterministic page.
- [ ] **Past-the-end discloses nothing new.** `200` + empty + the same subject-relative `count` a first
      page reports (I4/I6/I8) — not a 404 probe.
- [ ] **Authorization is byte-identical.** Every `@OpaPreAuthorize` (and deliberate absence) unchanged;
      `listCategories` is still the only residual list; zero `infra/opa/` diffs; every pinned row count
      in the suite numerically unchanged (E4).
- [ ] **The library change is additive.** 3-arg/4-arg overloads byte-compatible; all pre-existing
      library tests green unmodified (U8); `opa-abac-core` untouched (grep the diff).

## Related
- [[01-DECOMPOSITION]] (the tickets these cases gate) · [[00-DESIGN]] (the four-path table §3, the
  contract §2, the proof posture §8) · ADR [[0012-pagination-envelope|0012]] (the pinned forks).
- ADR [[0011-error-contract-problem-json|0011]] (the `400 VALIDATION_FAILED` `problem+json` surface the
  negatives land on).
- The shipped templates: `docs/to-do/implemented/HIERARCHY-LIST-FILTER/10-QA-TEST-CASES.md` (the
  list-filter U/I/E + fail-closed-checklist shape) ·
  `docs/to-do/implemented/REST-API-REFINEMENT/10-QA-TEST-CASES.md` (the two-service contract-change
  shape).
