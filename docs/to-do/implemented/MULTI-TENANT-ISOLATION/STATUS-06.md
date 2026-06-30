---
tags:
  - status/done
  - type/project
  - area/abac
---

# STATUS — T6: catalog GET /internal/{type}/{id}/created-by endpoint

**Status:** ✅ DONE

## What shipped

- **`InternalOwnershipController`** (`…catalog.web`) — `GET /internal/catalog/{id}/created-by` →
  `200 {"createdBy":"<sub-uuid>"}` (the catalog's `created_by`) or `404`. A **pure data read** (returns
  the creator; the resolver does the compare → cache key stays subject-independent, ADR 0019). The body
  is a `record CreatedByView(UUID createdBy)` so a **null** `createdBy` (catalog created by an
  unauthenticated / non-UUID auditor) serializes as `createdBy: null` (the resolver treats null/absent as
  not-owner) — `Map.of` is null-hostile and would have thrown.
- **`SecurityConfig`** — `/internal/**` is now `permitAll` (in-network), mirroring the user-service's
  resolve API. The catalog becomes an in-network attribute source the user-service's
  `DiscoveryOwnershipResolver` reads.
- **No mapping change needed:** `CatalogEntity` already carries `@CreatedBy private UUID createdBy`
  (via `AbstractAuditableEntity`), populated by the catalog's `AuditorAware` returning the sub —
  confirmed by I10.

## Tests

- `InternalOwnershipIT` (**real Postgres**, MockMvc) — **2/2**: I10 (`200 {createdBy}` == the creator sub
  = the permissive `TEST_PRINCIPAL`), I11 (missing catalog → `404`).
- Full catalog suite **109/109** (the `/internal/**` permitAll change did not regress the actuator-auth
  cells). `./gradlew :example-catalog-management-service:test` green.

## Architecture review + refactor

- **Fail-closed at the read:** `404` for missing (resolver → not-owner); a null `createdBy` serializes as
  null (resolver → not-owner). No path returns a wrong owner.
- **Boundary (LOAD-BEARING, cross-ticket):** `/internal/**` is `permitAll` + in-network — this is safe
  **only because T8 keeps `/internal/**` OFF the gateway** (the gateway proxies only `/api/v1/**` +
  Keycloak). Direct exposure would leak a creator id. **T8 must NOT add an `/internal` route**, and its
  acceptance asserts `GET :9085/internal/...` is 404/not-routed. Recorded as a hard invariant.
- **Additivity:** the public `/api/v1/catalogs` contract and the `AuditorAware` semantics are unchanged
  (it already stored the sub). Pattern mirrors the user-service `InternalResolveController`.
- **No refactor beyond the null-safe `record`** (caught while writing it: `Map.of` rejects a null value).

## Integration / e2e

ITs against **real Postgres**. The endpoint is consumed by T5's `DiscoveryOwnershipResolver`, wired into
`createTeam` in **T7**, exercised end-to-end (squat-deny) as **E7** in T9.

## Decisions

- **`/internal/**` permitAll on the catalog** (not a per-endpoint matcher) — consistent with the
  user-service, and the network boundary (never gateway-fronted) is the real isolation. The `created-by`
  read is the only `/internal` endpoint the catalog exposes today.

## Commit

(see branch `feature/void3110/multi-tenant-isolation`)
