---
tags:
  - status/planned
  - type/architecture
  - area/api
  - area/spring
  - area/abac
---

# 00 — Design: Pagination envelope (Phase 5.95)

> The design, written from a settled **ADR [[0012-pagination-envelope|0012]]** (which pins every fork
> below), the [[REST-API-DESIGN-REVIEW|REST API design review]] (finding #5), and guide
> [[REST-API-DESIGN]] (§7/§9). A **list-shape** change: every public list adopts one shared
> `{count, page, perPage, items}` envelope, composed with the Phase-5 partial-eval filter. **No
> authorization behavior changes; zero Rego changes; `opa-abac-core` is not touched.**

## 1. The problem, precisely

Every public list endpoint returns a bare, unbounded array — and they sit on **two different
authorization shapes** that the slice must paginate without disturbing:

| Endpoint | Service | Authorization today (unchanged by this slice) |
|---|---|---|
| `listCategories` | catalog | **the residual path** — `CategoryListAuthorizer.readable()` → the 4-arg `AbacQueryService.findAuthorized` (tag residual ∨ subtreeSpec, ∧ scope, ∧ notDenied) |
| `listCatalogs` | catalog | type-level `@OpaPreAuthorize` gate + `findAll()` (deliberately coarse since Phase 5) |
| `listProducts` | catalog | type-level gate + `findByCategoryId` scope |
| `listUsers`, `listTeams`, `listMembers`, `listRoleDefinitions`, `listTagDefinitions` (global + team) | user-svc | `@OpaPreAuthorize` gates + plain repo/derived queries |

Pagination must also hold on **all four `findAuthorized` paths** (ADR 0005/0010): pure-SQL residual ·
allowlist-batch fallback (fetch-all + in-memory re-check) · the `partialEval.enabled=false` kill-switch ·
the `fromError` fail-closed cut. And under ABAC the total is **subject-relative**: two users paging the
same URL legitimately see different `count`s — which shapes the past-the-end and count semantics below.

## 2. The target shape (ADR 0012 §1, §3)

```json
{
  "count": 7,
  "page": 0,
  "perPage": 20,
  "items": [ { "...": "Category" } ]
}
```

| Member | Semantics |
|---|---|
| `count` | the total number of rows **the calling subject is authorized to see**, across all pages — never `items.length`. The demo headline: *the count is the count of rows you may see.* |
| `page` / `perPage` | echo the request verbatim (nothing clamps). |
| `items` | the page slice, in the fixed order (§6). |

The params contract (strict, 0-based — ADR 0012 §3):

| Semantic | Pinned |
|---|---|
| Wire params | `page`, `perPage` (shared `components/parameters` in each spec) |
| Base | **0-based**: `page` *is* `PageRequest.of(page, perPage)` — no translation layer |
| Defaults | `page=0`, `perPage=20` |
| Bounds | `page >= 0`, `1 <= perPage <= 100` |
| Violations | **`400 VALIDATION_FAILED`** `problem+json` (ADR 0011) — **no clamping**; no new error codes needed |
| Past-the-end | **`200` + empty `items` + exact `count`** — never `404` (the last page is subject-relative) |

## 3. The library seam (ADR 0012 §2)

`opa-abac-spring-data` gains an **additive** overload (the 5.5-B back-compat move again — every existing
caller compiles unchanged):

```java
public <T extends AbacDataObject> Page<T> findAuthorized(
        JpaSpecificationExecutor<T> repo,
        Specification<T> scope,
        AbacContext queryContext,
        Specification<T> subtreeSpec,
        Pageable pageable)   // throws IllegalArgumentException if pageable.getSort().isUnsorted()
```

Behavior per path:

| Path | Paged behavior | `count` |
|---|---|---|
| **Pure-SQL** | `repo.findAll(combined, pageable)` — Spring Data issues the `COUNT` over the same combined `Specification` | exact, from SQL |
| **Allowlist fallback** | fetch **all** scoped candidates **SQL-sorted** (`findAll(spec, sort)`), batch-filter (order-preserving), slice the requested page in memory via `PageImpl` | exact = filtered size; **cost unchanged from Phase 5** (the path is fetch-all today) |
| **Kill-switch** | coarse `allow` check, then `repo.findAll(scope.and(notDenied), pageable)` | exact under the degraded policy |
| **`fromError`** | empty page, fail-closed | `0` |

The **unsorted-`Pageable` guard** is the one opinionated addition: paginating without a total order is a
correctness bug (rows silently repeat/vanish across pages), so the seam refuses it — fail-loud at dev
time. An unpaged `Pageable` fails the same guard (it carries no sort).

## 4. The OpenAPI shape (ADR 0012 §2)

Vanilla codegen, no custom templates. Per spec file: one `PageEnvelope` base + thin `allOf` compositions —
one definition point per spec, drift structurally impossible across the nine endpoints:

```yaml
PageEnvelope:
  type: object
  required: [count, page, perPage]
  properties:
    count:   { type: integer, format: int64, description: "Total rows the caller is authorized to see, across all pages." }
    page:    { type: integer, minimum: 0, description: "0-based page index, echoing the request." }
    perPage: { type: integer, minimum: 1, maximum: 100, description: "Page size, echoing the request." }

CategoryPage:
  allOf:
    - $ref: '#/components/schemas/PageEnvelope'
    - type: object
      required: [items]
      properties:
        items: { type: array, items: { $ref: '#/components/schemas/Category' } }
```

- Naming: **`<Resource>Page`** — `CatalogPage`, `CategoryPage`, `ProductPage`, `UserPage`, `TeamPage`,
  `MembershipPage`, `RoleDefinitionPage`, `TagDefinitionPage`.
- `page`/`perPage` as **shared parameter components** (`#/components/parameters/Page`, `PerPage`) so the
  bounds live once per spec, not nine times.
- The 3-field base is deliberately defined **twice** (once per spec file); a cross-file `$ref` would couple
  the two services' build inputs (ADR 0012, considered & rejected).

## 5. Where the pieces live

```
opa-abac-spring-data/
  filter/AbacQueryService        (the paged findAuthorized overload; the unsorted guard;
                                  the sorted candidate fetch on the fallback path)

example-catalog-management-service/
  openapi/catalog-api.yaml       (PageEnvelope + CatalogPage/CategoryPage/ProductPage; Page/PerPage params)
  config/CategoryListAuthorizer  (readable(...) gains the Pageable pass-through → paged findAuthorized)
  web/*Controller                (build PageRequest.of(page, perPage, ORDER) — fixed sort; map Page<T> → <Resource>Page)

example-user-management-service/
  openapi/user-mgmt-api.yaml     (PageEnvelope + the five <Resource>Page schemas; Page/PerPage params)
  service/*Service               (list(...) methods gain Pageable → Page<T> via findAll(pageable)/derived queries)
  web/*Controller                (same PageRequest construction + mapping)

scripts/postman/
  run-pagination-matrix.sh       (new matrix; dedicated namespaced fixture set in the registry)
docs/guides/
  REST-API-DESIGN.md             (§7 rewritten to the adopted convention; §9 row moved into the body)
  PARTIAL-EVALUATION-FILTERING.md (new "paged composition" section — the four paths)
  E2E-TESTING.md                 (the new matrix + fixture set)
```

> **`opa-abac-core` is untouched** — pagination is a data/HTTP concern; `Pageable`/`Page` enter only in
> `opa-abac-spring-data`, which already depends on Spring Data. **`infra/opa/policies/` is untouched** —
> zero Rego changes; `opa test` stays as-is.

## 6. Ordering (ADR 0012 §4)

- **Fixed server-side order on all 9 endpoints: `createdAt ASC, id ASC`.** Every entity inherits both
  fields from `AbstractAuditableEntity`; the `id` tiebreaker makes it a total order (same-transaction
  inserts share timestamps). Controllers construct it once (a shared constant per service) — clients do
  not choose.
- **Client `?sort=` is out of scope** — a deferred target (guide §9): it multiplies the spec surface and
  the sortable-field-allowlist validation story while proving nothing new about residual composition.

## 7. Fail-closed posture (nothing widens)

- **`fromError` → empty page, `count = 0`** — the Phase-5 cut, unchanged: no policy answer, no rows, and
  no count leak either.
- **The fallback path can only narrow:** the in-memory page is a slice of the batch-filtered survivors;
  a short or empty decision list still drops rows (the existing `allowAll` fail-closed contract).
- **The kill-switch path keeps `notDenied` AND-ed** — paging it changes nothing about the deny-override.
- **Past-the-end is not a probe:** `200` + empty + exact `count` — and since `count` is subject-relative
  by design, it discloses nothing a first page would not.
- **The guard fails loud, not open:** an unsorted `Pageable` is a thrown `IllegalArgumentException` at
  dev time, not a silently nondeterministic page.

## 8. Proof posture (ADR 0012 §Consequences)

| Layer | Asserts | Weight |
|---|---|---|
| **Unit (`AbacQueryServiceTest`)** | the paged overload on **all four paths** (pure-SQL count + page; fallback in-memory slice with exact count and preserved order; kill-switch; `fromError` → empty/0); the unsorted-`Pageable` guard throws. | **Must-have, exhaustive.** |
| **Real-Postgres IT** (the `HierarchyListFilterIT` precedent) | **two subjects, same data, different `count`**; the **stability walk** — all pages at `perPage=2`, union exactly the authorized set, no repeats/drops (the determinism regression test). | **Must-have** — the slice's decisive correctness case. |
| **MockMvc IT (per service)** | envelope shape + required fields; `perPage=101` / `page=-1` → `400 VALIDATION_FAILED` `problem+json`; past-the-end → `200` + empty + exact `count`. | **Must-have but lean.** |
| **OpenAPI codegen** | both specs declare `PageEnvelope` + `<Resource>Page`; `./gradlew build` clean (drift = build break). | **Automatic.** |
| **E2E (newman, gateway)** | `run-pagination-matrix.sh`: viewer vs editor get **different `count` on the same URL**; a paged walk returns disjoint pages; one live negative (`perPage=500` → 400 + typed code through APISIX). Existing collections updated to the envelope shape. | **One new matrix**, following `run-filter-matrix.sh`. |
| **Fixtures** | a **dedicated, namespaced pagination set** in the e2e fixture-id registry (≥5 rows under one parent) — shared-seed exact-count assertions in other matrices must not be perturbed. | rig, not a test. |

## 9. Considered & rejected

Pinned with rationale in **ADR [[0012-pagination-envelope|0012]]** — headline rejections: Slice-shape /
`hasNext` (loses the count contrast we already afford) · hybrid `count: null` (a sometimes-field) ·
library-owned envelope type (the mediator worldview) · 1-based pages (a permanent off-by-one layer) ·
clamping (silent meaning change) · `404` past-the-end (subject-relative last page) · client sort now ·
cursor/keyset pagination · residual-filtering the coarse lists "while we're in there" · custom codegen
templates · a cross-file shared envelope `$ref`.

## 10. What this slice does NOT do

- **No authorization behavior change, anywhere** — the residual path stays `listCategories`-only;
  catalogs/products/user-svc lists keep their coarse gates. (Their residual conversion, if ever, is its
  own decision.)
- **No Rego change** — pagination is not a policy concern; OPA answers *which rows*, `LIMIT/OFFSET`
  answers *how many per page*.
- **No client `sort`** — deferred target (guide §9).
- **No `/internal/**` pagination** — that surface stays plain by design (guide §8); a one-line
  "unpaginated by design" note keeps the absence legible.
- **No dual wire shape** — the bare-array → envelope break is clean (pre-publication, no external
  consumers; the newman collections update in the same slice).
- **No `_actions`/`pageActions`** — Phase 6 ([[ACTION-ENRICHMENT]]) lands on this envelope; that is the
  sequencing reason 5.95 precedes it.
- **No touch of `opa-abac-core`.**

## Related

- ADR [[0012-pagination-envelope|0012]] — the decision this design implements.
- [[REST-API-DESIGN-REVIEW]] (finding #5) · [[REST-API-DESIGN]] (§7/§9 — the guide this advances).
- ADR [[0005-partial-eval-to-jpa-specification|0005]] · ADR [[0010-hierarchy-aware-list-filter|0010]] (the
  list paths paginated here) · ADR [[0011-error-contract-problem-json|0011]] (the error surface the
  negatives land on).
- [[POC-ROADMAP]] — Phase 5.95 (this); Phase 6 (lands on this envelope). · [[USER-STORIES]] — Epic D (D5).
