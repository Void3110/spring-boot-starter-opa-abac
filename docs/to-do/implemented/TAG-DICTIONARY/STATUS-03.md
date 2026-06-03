---
tags:
  - status/done
  - type/project
  - area/user-service
  - area/abac
---

# STATUS — Ticket 03: Tag assignment on the Category sub-resource (validated against the dictionary)

> Filled in at the ticket-03 checkpoint during the autonomous run. See [[01-DECOMPOSITION]] ticket 3.

**Status:** ✅ done

## What shipped

The **assignment** layer — an owner/member with write attaches dictionary-validated tags to a Category;
illegal tags are rejected (422), not silently dropped; a definitions-fetch failure rejects the write
(503, fail-closed).

- **user-service:** `GET /internal/tag-definitions?resourceType&resourceId` on `InternalResolveController`
  → `TagDefinitionService.applicableForResource`, which resolves the governing team via the same
  `TeamTargetMatcher` the resolve API uses and returns global keys + that team's keys (globals-only when no
  team governs). Internal-only (under `/internal/**`, not gateway-fronted), hand-written like the
  effective-role endpoint.
- **catalog:** `TagDefinitionClient` (JDK `HttpClient` + Jackson, mirroring `HttpRoleDefinitionSupplier`'s
  transport) fetches the applicable set, deserialized into a `TagDefinitionView` record.
  `TagAssignmentService` validates each submitted entry (known key, value type, cardinality, regex) and
  builds the `ResourceTags` to persist; `CategoryController` create/update validate-then-store **before
  persist**. A `tags` field added to `CategoryRequest`/`Category` (free-form object: scalar or array).
- **Authorization unchanged:** assignment is the existing `@OpaPreAuthorize(category:write)` — **no new
  capability**. The dictionary only constrains legality.
- **Exceptions:** `IllegalTagAssignmentException` → 422 (offending key named), `TagDefinitionFetchException`
  → 503, wired into the catalog `ApiExceptionHandler`.

## Tests

`./gradlew :example-catalog-management-service:test :example-user-management-service:test` green; the
pre-existing `CatalogCrudIT` / `ProductConcurrencyIT` / `BaseEntityAuditingIT` / `HttpRoleDefinitionSupplierTest`
unchanged-green.

- **`TagDefinitionClientTest`** (5, in-process `HttpServer` stub) — H1 (applicable-set round-trip +
  request URL shape) and H2 (500 / malformed / connection-refused / empty-body → `TagDefinitionFetchException`,
  never an empty "all-allowed" set).
- **`CategoryTagAssignmentIT`** (6, Testcontainers + a stub `TagDefinitionClient`) — A1 (legal
  `sensitivity=internal` + `region=[emea,amer]` persist as scalar + array, survive a re-read), A2 (unknown
  key → 422), A3 (enum miss → 422), A4 (SINGLE-given-array → 422), A6 (fetch failure → 503, no category
  leaked), plus a no-tags-needs-no-fetch case.
- **A5** (team-scoped key applies to that team's resource) is exercised through the rig in ticket 6; **A7**
  (a viewer cannot assign) is the existing `category:write` authorization, in the ticket-6 e2e matrix.

## Architecture review + refactor

- **Additivity / boundary:** catalog + user-service only; no library change. ✅
- **Fail-closed:** unknown key / enum miss / cardinality / malformed-pattern → 422 (never stored);
  definitions-fetch failure → 503 (A6 proves no category is persisted). Crucially the tag-fetch fails to a
  **rejection**, not an empty set — an empty set would widen what is legal. ✅
- **Three-layer separation:** assignment is a normal `write`; **no new capability**; the dictionary
  constrains legality only; validation happens before persist. ✅
- **Pattern reuse:** `TagDefinitionClient` mirrors `HttpRoleDefinitionSupplier`'s transport; the internal
  endpoint reuses `TeamTargetMatcher`; `ResourceTags` (scalar + array) stores both cardinalities with no
  schema change. ✅
- **Cross-service validation is by contract, not a shared class — a deliberate decision.** The two example
  apps share only the *published library* (core/data/security/starter), never each other. Putting a shared
  `TagValueValidator` in the library would be a non-additive library change (forbidden — the only library
  change is T4's `RoleDefinition` fields). So the catalog re-validates the fetched `TagDefinitionView`
  contract itself; the user-service remains the dictionary's authority and the catalog is the fail-closed
  enforcement point. Same *rules*, shared via the wire contract, not a class.
- **Refactor applied:** `validateAndBuild` originally built `ResourceTags` by repeated `with()` (a map copy
  per tag). Switched to collecting into one `LinkedHashMap` and a single `ResourceTags.fromMap` — one
  allocation, clearer. Re-ran the suite: green.

## Integration / e2e

Testcontainers ITs (real Postgres) + the in-process `HttpServer` client tests. Clean-room scan of the T3
diff clean. The full rig/newman pass (A5/A7 through the gateway) is ticket 6.

## Decisions recorded

`ml record opa-abac --type decision` — **fail-closed has two shapes**: a role/decision supplier fails
closed to *empty* (the policy then default-denies), but a **validation-input fetch** (the tag dictionary)
must fail closed to a *rejection* — an empty set would widen legality (all-allowed or
illegal-by-absence), so `TagDefinitionClient` throws and the write is rejected (503). Relates to the
fail-closed convention (`mx-926c85`) and the Phase-4.5 design (`mx-94e70d`). `ml sync` touched `.mulch/`
only.

## Commit

One focused commit on `feature/void3110/tag-dictionary`: `feat(example): validate + assign
dictionary-checked tags on Category, fail-closed (T3)`.
