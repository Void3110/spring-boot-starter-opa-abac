---
tags:
  - status/done
  - type/project
  - area/api
  - area/spring
  - area/abac
---

# STATUS — T3: Catalog — spec envelope + paged controllers + CategoryListAuthorizer pass-through + IT

**Status:** ✅ DONE (2026-06-11)

## What shipped

- **`openapi/catalog-api.yaml`** — `PageEnvelope` (count int64 / page / perPage, required, bounds as
  schema constraints) + `CatalogPage`/`CategoryPage`/`ProductPage` (`allOf` + required `items`) + shared
  `components/parameters/Page` (`min 0, default 0`) and `PerPage` (`1–100, default 20`); the three list
  ops gained the two params, their `200`s now return `<Resource>Page`, and each carries a `400`
  (BadRequest) response for the bounds negatives.
- **`web/PageDefaults`** — the service's fixed total order (`createdAt ASC, id ASC`) + the
  `pageRequest(page, perPage)` builder (one definition point).
- **Controllers** — `listCatalogs`: `catalogs.findAll(pageable)`; `listProducts`: a paged derived
  `findByCategoryId(UUID, Pageable)` (the orphaned `List` variant removed — see review); `listCategories`:
  the `Pageable` passes through the authorizer to the paged residual seam. Every `@OpaPreAuthorize`
  byte-identical (diff-checked: zero annotation lines changed).
- **`CatalogMapper`** — `toCatalogPage`/`toCategoryPage`/`toProductPage` (count = `totalElements`,
  page/perPage echo the request, items mapped with the existing `toDto`s).
- **`config/CategoryListAuthorizer`** — `readable(catalogId, parentId, pageable): Page<CategoryEntity>`
  delegating to the **paged** 5-arg `findAuthorized` (same context, scope, subtreeSpec resolution);
  the unpaged variant migrated (single call site); unauthenticated → `Page.empty(pageable)`.
- **`web/ApiExceptionHandler`** — a `ConstraintViolationException` → `400 VALIDATION_FAILED` mapping:
  the generated `@Validated` API interface surfaces param-bound violations via AOP method validation
  (NOT `HandlerMethodValidationException` — see Decisions), and the advice didn't map it before.

## Tests

`./gradlew :example-catalog-management-service:build` → **35 tests, 0 failures** (codegen regenerated
the three list signatures — C1/C2):

- **I5** `PaginationEnvelopeIT.envelopeAndDefaults_onCategoriesList` (defaults 0/20, count=3, fixed
  order), `explicitWindow_slicesInFixedOrder_withExactCount` (perPage=2 windows + echo),
  `envelope_onCatalogsAndProductsLists` (all three lists carry the envelope).
- **I6** `boundsViolations_are400ValidationFailed` (perPage=101/0, page=-1 → `400` `problem+json`
  `VALIDATION_FAILED`, on both a residual list and a coarse list), `pastTheEnd_is200EmptyWithExactCount`.
- **In-commit break absorption:** `CatalogCrudIT` (bare-array → `items`) and `CategoryTagAssignmentIT`
  (empty-body → `count==0` + empty `items`) updated; all other catalog ITs green unmodified.

## Architecture review + refactor

- **One real finding (fix-until-green):** the planned `HandlerMethodValidationException` mapping never
  fires in this codebase — the generated API interfaces carry class-level `@Validated`, so Spring uses
  AOP method validation and raises `jakarta.validation.ConstraintViolationException`. The advice handler
  was swapped accordingly (detail renders `perPage: must be …`, the param not the method path).
- **One cleanup:** the paged `findByCategoryId` orphaned the unpaged `List` variant — removed in this
  commit. (Noted, untouched: `CategoryRepository.findByCatalogId`/`findByCatalogIdAndParentId` were
  already caller-less *before* this slice — pre-existing cruft for the phase-④ review, not this ticket.)
- Boundary checks: library modules consumed read-only; authorization shape unchanged (`listCategories`
  still the only residual list); validation contract-driven (spec constraints → generated annotations →
  the advice); the envelope mapping lives in the mapper, the order constant in one place.

## Integration / e2e

The module's full IT suite (Testcontainers, real Postgres + Liquibase) is the integration proof; the
gateway e2e + the two-subject count contrast land in T5.

## Decisions

- Bounds violations surface as `ConstraintViolationException` (AOP `@Validated` path), not
  `HandlerMethodValidationException` — pinned here for T4, which needs the same advice mapping.
- The three list `200` responses also document `400` in the spec (the params make it reachable).
- `PaginationEnvelopeIT` pins deterministic counts only on catalog-scoped lists; the top-level
  `/catalogs` list asserts shape + echo (the shared test container holds sibling ITs' rows).

## Commit

`feat(pagination): catalog service adopts the list envelope — spec, paged controllers, authorizer pass-through`
