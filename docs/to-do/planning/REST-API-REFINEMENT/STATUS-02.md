---
tags:
  - status/done
  - type/project
  - area/api
  - area/spring
---

# STATUS T2 — Catalog: `CatalogErrorCode` + advice remap + OpenAPI `ProblemDetail` schema + `Location` on 201 + MockMvc IT

> ✅ **Shipped.** One focused commit on `feature/void3110/rest-api-refinement`. Catalog adopts the T1
> contract: its advice emits `ProblemDetail`, the spec replaces `ApiError`, every 201 carries `Location`.

## What shipped

- **`CatalogErrorCode`** (enum implementing `ApiErrorCode`, `…catalog.web`) — **intentionally empty**.
  Every catalog failure maps cleanly to a `LibraryErrorCode` (not-found → `RESOURCE_NOT_FOUND`,
  validation → `VALIDATION_FAILED`, illegal tag → `TAG_VALUE_ILLEGAL`, dictionary outage →
  `DEPENDENCY_UNAVAILABLE`, deny → `ACCESS_DENIED`). Per ADR 0011 §4 (semantic granularity) **no codes
  were invented to fill it**; it is the typed, one-line-to-extend point should a distinct catalog failure
  appear. (Documented in the enum Javadoc.)
- **`web/ApiExceptionHandler`** now `extends AbstractProblemAdvice` — each `@ExceptionHandler` resolves to
  a `LibraryErrorCode` and builds a `ProblemDetail` via the inherited `problem(...)` helper at
  `application/problem+json`, pulling `instance` from the injected `HttpServletRequest`. **Same status per
  exception as before.** 403 flows through the inherited `AccessDeniedException` mapping (T1).
- **`openapi/catalog-api.yaml`** — **`ApiError` removed**; a `ProblemDetail` schema added (7 members,
  `errorCode` a **typed `enum`**: `ACCESS_DENIED`, `RESOURCE_NOT_FOUND`, `VALIDATION_FAILED`,
  `TAG_VALUE_ILLEGAL`, `DEPENDENCY_UNAVAILABLE` — the union catalog emits). The shared `responses` block
  flipped to `application/problem+json` + `ProblemDetail`, and gained reusable `Forbidden`,
  `UnprocessableEntity`, `ServiceUnavailable` responses; `createCategory` now declares its `422`/`503`.
- **`Location` on every 201** — `CatalogController`, `CategoryController`, `ProductController` build
  `Location` via `ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")` and return
  `ResponseEntity.created(location)`. The nested category/product locations are correct by construction
  (they extend the actual request URI). Bodies unchanged.

## Tests

`./gradlew :example-catalog-management-service:build` — **green** (codegen clean + all Testcontainers ITs
against real Postgres). The contract cases:

- **`ErrorContractIT`** (new, full-context MockMvc + real Postgres) — **3/3**:
  - **I1** GET missing catalog → 404 `application/problem+json`, `errorCode=RESOURCE_NOT_FOUND`,
    `type=/problems/resource-not-found`, `instance` == request path, `timestamp` present, **no `message`**.
  - **I2** blank-name create → 400 `problem+json`, `errorCode=VALIDATION_FAILED`, no `message`.
  - **I3-201** create → `201` + `Location: http://localhost/api/v1/catalogs/<id>` matching the created id.
- **`CategoryTagAssignmentIT`** (extended) — **6/6**, now asserting on the live paths:
  - **I2b** unknown tag key → 422 `problem+json`, `errorCode=TAG_VALUE_ILLEGAL`, no `message`.
  - **I2c** dictionary-fetch failure → 503 `problem+json`, `errorCode=DEPENDENCY_UNAVAILABLE`, **and**
    nothing stored (the list stays empty — fail-closed preserved).
- **Codegen (C1/C2/C3)** — the generated `…openapi.model.ProblemDetail` carries a typed `ErrorCodeEnum`;
  on a clean regeneration `ApiError.java` is **gone** (no schema → no model); every error response is
  `application/problem+json`.
- **I3 (403)** is covered by the library U5 (the inherited `AccessDeniedException`→403 mapping) + the
  live e2e E1 (T4); the catalog's permissive test chain always grants, so a deny can't be driven in this
  harness — noted, not skipped silently.

## Architecture review + refactor (the ★ gate)

- **Fail-closed:** every exception → the **same** status as today (404/400/422/503); the 503 still rejects
  + stores nothing (I2c asserts the empty list); the inherited 403 renders a deny, never authorizes. No
  status changed, no access widened.
- **Boundary / additivity:** `git diff --stat main...HEAD -- opa-abac-core/` empty. The `ApiError`-removal
  build-breaker is confined to the catalog module and lands in this commit (advice rewritten; no test
  referenced `ApiError`).
- **Pattern reuse:** library `ProblemDetail` DTO (typed enum in the spec — confirmed in the generated
  model); reused the advice `@ExceptionHandler` grouping (remapped, not reinvented); `Location` via the
  idiomatic `ServletUriComponentsBuilder`.
- **Clean-room:** scan of all changed files empty.
- **Refactored:** **nothing substantive.** Considered whether the empty `CatalogErrorCode` should be
  dropped — kept it as the documented, typed extension point (the deliverable + the per-app seam demo),
  inventing no codes.

## Integration / e2e

The MockMvc ITs above are the integration proof (real Postgres). The gateway e2e is T4.

## Decisions

- **`CatalogErrorCode` ships empty** — every catalog failure maps to a library code; no invented codes.
- **Spec documents the full error surface** — added reusable `Forbidden`/`UnprocessableEntity`/
  `ServiceUnavailable` responses and wired `422`/`503` onto `createCategory`, so the contract now declares
  what the service actually emits (it previously omitted 422/503).
- **403 proof split** to U5 (unit) + E1 (e2e) since the permissive IT chain can't deny.

## Rig note (environment, not code)

The Testcontainers ITs first failed with *"Could not find a valid Docker environment"* — the **podman
machine reported `running` but its API socket file was missing** (stale state), so the build's
`resolveDockerHost()` found nothing and Testcontainers' fallbacks missed the Docker Desktop socket.
`podman machine stop && start` recreated the socket (`API forwarding listening on /var/run/docker.sock`)
and the build auto-discovered it as designed. No code change. Recorded to Mulch (`opa-abac`).

## Commit

`feat(catalog): adopt RFC-7807 problem+json error contract + Location on 201` on
`feature/void3110/rest-api-refinement`.
