---
tags:
  - status/planned
  - type/project
  - area/api
  - area/spring
---

# STATUS T2 — Catalog: `CatalogErrorCode` + advice remap + OpenAPI `ProblemDetail` schema + `Location` on 201 + MockMvc IT

> Stub — filled at the T2 checkpoint. One focused commit. ☐ not yet shipped.

## What shipped

_TBD at the T2 checkpoint — incl. the final catalog exception→`errorCode` map (which failures reuse a `LibraryErrorCode`, which got a `CatalogErrorCode`)._

## Tests

_TBD — I1–I3 + I3-201 (problem+json + the expected errorCode per status; Location on the 3 catalog 201s); C1–C3 (ProblemDetail generated, ApiError gone, content type flipped)._

## Architecture review + refactor (the ★ gate)

_TBD — fail-closed (same statuses; 503 still rejects untagged; 403 inherited from the library base); boundary (opa-abac-core untouched; build-breaker confined to the catalog module); pattern reuse; what was refactored, or "nothing substantive"._

## Integration / e2e

_TBD — MockMvc/@WebMvcTest slice; `./gradlew :example-catalog-management-service:build` green (codegen clean + existing CatalogCrudIT green). Gateway e2e is T4._

## Decisions

_TBD — e.g. whether any 422 failure was split into a distinct CatalogErrorCode, or all map to library codes._

## Commit

_TBD — `feat(catalog): …` on `feature/void3110/rest-api-refinement`._
