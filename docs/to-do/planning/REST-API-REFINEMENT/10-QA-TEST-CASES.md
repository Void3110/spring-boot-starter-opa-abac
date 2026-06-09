---
tags:
  - status/planned
  - type/project
  - area/api
  - area/spring
  - area/architecture
---

# 10 — QA test cases: REST API refinement — the error contract (Phase 5.9)

> The concrete cases each [[01-DECOMPOSITION|ticket]]'s *Acceptance* references. **U** = unit (library —
> mapping + serialization, no DB), **I** = integration (per service, MockMvc / `@WebMvcTest` — no Postgres
> needed for the slice tests), **E** = e2e through the gateway (newman, **extending** existing matrices —
> no new collection). Plus a **codegen** block (the build proves the spec). This is a **contract-shape**
> change: every error path lands on the **same status** as before, now carrying a typed `errorCode` in a
> canonical `application/problem+json` body — the cases assert the **body shape + the right code**, not a
> changed decision. The fail-closed posture is **preserved verbatim** (no error path widens access); see
> the fail-closed checklist below.

## Conventions
- **Unit (library):** plain JUnit + AssertJ (no Postgres, no rig). Serialize the `ProblemDetail` carrier
  with the app Jackson `ObjectMapper` (or a `JacksonTester`) and assert the exact RFC-7807 member names;
  assert **no `message`** field is present.
- **Integration (per service):** MockMvc via `@WebMvcTest` (controller advice slice — no Postgres). Drive
  a representative path per status by stubbing the service layer to throw the mapped exception (or by a
  request that the controller rejects), then assert `status`, `Content-Type: application/problem+json`,
  and `errorCode`. The `201`+`Location` case can use the same slice (stub the service to return a saved
  entity with a known id). A **403** case drives a denied `@OpaPreAuthorize` path (mock the
  `OpaAuthorizationManager`/`OpaClient` to deny, or a member token against a gated method).
- **e2e:** full rig `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2`; `./profile.sh up` (base
  Postgres) **before** `deploy.sh up`; `./deploy.sh build` to **force** the new app code into the pods;
  mint tokens **in-network** (issuer `keycloak:8888`); keep runtime-captured ids in **collection**
  variable scope. **Extend** the existing matrices — no new collection.
- **The expected codes (from the [[00-DESIGN]] §3 table, the vocabulary the cases pin):**

  | Failure | Status | `errorCode` | Owner |
  |---|---|---|---|
  | not-found | 404 | `RESOURCE_NOT_FOUND` | library |
  | bean-validation / illegal-arg | 400 | `VALIDATION_FAILED` | library |
  | OPA-deny / unauth / unresolved subject | 403 | `ACCESS_DENIED` | library |
  | catalog illegal tag assignment | 422 | `TAG_VALUE_ILLEGAL` | library |
  | catalog dictionary outage (fail-closed) | 503 | `DEPENDENCY_UNAVAILABLE` | library |
  | role subset-rule violation | 422 | `ROLE_SUBSET_VIOLATION` | library |
  | user-svc conflicts (duplicate target / immutable role / tag-key / immutable tag-def) | 409 | `STATE_CONFLICT` *or* a `UserMgmtErrorCode` refinement | library / app |

---

## Unit — library: `ApiErrorCode` / `LibraryErrorCode` / `ProblemDetail` carrier + advice base (T1)

| # | Case | Expected |
|---|------|----------|
| **U1** | Every `LibraryErrorCode` constant exposes `code()`/`problemType()`/`title()` | `code()` == the enum name; `problemType()` == `/problems/<kebab-of-name>` (a stable **relative** opaque id, **no** host); `title()` non-blank and status-stable (one per code) |
| **U2** | Each `LibraryErrorCode` carries the right **status** | `ACCESS_DENIED`→403, `DEPENDENCY_UNAVAILABLE`→503, `VALIDATION_FAILED`→400, `RESOURCE_NOT_FOUND`→404, `STATE_CONFLICT`→409, `TAG_VALUE_ILLEGAL`→422, `ROLE_SUBSET_VIOLATION`→422 |
| **U3** | The helper builds a `ProblemDetail` from `(status, code, detail, instance)` | `type=code.problemType()`, `title=code.title()`, `status=status.value()`, `detail`=given, `instance`=given path, `errorCode=code.code()`, `timestamp`≈now |
| **U4** | The carrier serializes to the canonical RFC-7807 members | JSON has exactly `type`, `title`, `status`, `detail`, `instance`, `errorCode`, `timestamp` — and **no `message`** (clean replacement); the `ResponseEntity` content type is `application/problem+json` |
| **U5** | Spring Security `AccessDeniedException` / `AuthorizationDeniedException` → the deny code | resolves to `LibraryErrorCode.ACCESS_DENIED` at **403** `problem+json` (so a denied call lands as a problem body, not Spring's default error) |
| **U6** | A **foreign app enum** implementing `ApiErrorCode` plugs into the helper unchanged | the helper builds a well-formed `ProblemDetail` from the app code (`errorCode` = the app code's `code()`); the interface is the only seam — no library change needed to add app codes |

## Integration — catalog (MockMvc / `@WebMvcTest`) (T2)

| # | Case (drive the mapped path) | Expected |
|---|------|----------|
| **I1** | `NotFoundException` (a GET of a missing catalog/category/product) | 404 · `application/problem+json` · `errorCode=RESOURCE_NOT_FOUND` · canonical members present; `instance` == the request path |
| **I2** | `MethodArgumentNotValidException` (a malformed create body) | 400 · `problem+json` · `errorCode=VALIDATION_FAILED` |
| **I2b** | `IllegalTagAssignmentException` (an unknown tag key / enum miss on a Category write) | 422 · `problem+json` · `errorCode=TAG_VALUE_ILLEGAL` (or the catalog refinement if split) |
| **I2c** | `TagDefinitionFetchException` (dictionary outage — fail-closed) | 503 · `problem+json` · `errorCode=DEPENDENCY_UNAVAILABLE`; the resource is **not** stored (status unchanged from today) |
| **I3** | A denied `@OpaPreAuthorize` / load-then-check call (mock the manager to deny) | 403 · `problem+json` · `errorCode=ACCESS_DENIED` (inherited from the library advice base, T1) |
| **I3-201** | A successful create (stub the service to return a saved entity with id `X`) | 201 · `Location: /api/v1/catalogs/X` (resp. `/categories/X`, `/products/X`); the 201 body is unchanged |

## Integration — user-service (MockMvc / `@WebMvcTest`) (T3)

| # | Case (drive the mapped path) | Expected |
|---|------|----------|
| **I4** | not-found group (`NotFoundException` / `MembershipNotFoundException` / `RoleNotFoundException` / `TagDefinitionNotFoundException`) | 404 · `problem+json` · `errorCode=RESOURCE_NOT_FOUND` |
| **I4b** | `IllegalArgumentException` / `MethodArgumentNotValidException` | 400 · `problem+json` · `errorCode=VALIDATION_FAILED` |
| **I5** | conflict group (`TeamTargetExistsException` / `MembershipConflictException` / `RoleConflictException` / `SystemRoleImmutableException` / `TagKeyConflictException` / `TagDefinitionImmutableException`) | 409 · `problem+json` · `errorCode` == `STATE_CONFLICT` **or** the distinct `UserMgmtErrorCode` chosen per exception (semantic granularity — assert the code the advice maps, per the final map recorded in `STATUS-03.md`) |
| **I5b** | domain-rule group (`SubsetRuleViolationException` → `ROLE_SUBSET_VIOLATION`; `InvalidTagDefinitionException` → its 422 code) | 422 · `problem+json` · the expected `errorCode` |
| **I6** | A denied gated call (`@OpaPreAuthorize` on a `#teamId` mutation — mock the manager to deny) | 403 · `problem+json` · `errorCode=ACCESS_DENIED` |
| **I6-201** | A successful create on each of the five create endpoints (stub the service) | 201 · `Location: /api/v1/<collection>/<id>` (nested where nested, e.g. `/api/v1/teams/{teamId}/memberships/<id>`); body unchanged |
| **I7** | **Intent comments** (grep/review — not a runtime test) | `UserController.createUser` and `TeamController.createTeam` each carry the one-line `bootstrap: pre-membership, authenticated-only by design` comment; **neither gains `@OpaPreAuthorize`** (no behavior change) |

## Codegen — the build proves the spec (T2/T3)

| # | Case | Expected |
|---|------|----------|
| **C1** | `catalog-api.yaml` + `user-mgmt-api.yaml` each declare `ProblemDetail` with `errorCode` as a **typed `enum`** (the union of codes that service emits) | `./gradlew build` regenerates `…openapi.model.ProblemDetail` with a typed `ErrorCode` enum; the generated client is typed |
| **C2** | `ApiError` is **removed** and no longer referenced | no `$ref` to `ApiError` in either spec; no import of `…openapi.model.ApiError` in either module; drift (a stale `ApiError` import or an out-of-sync schema) is a **build break** |
| **C3** | Every error response declares `content: application/problem+json` | the generated/served content type is `application/problem+json`, not `application/json` |

## e2e — through the gateway (T4, **extend** existing matrices — no new collection)

| # | Case (live, through APISIX) | Expected |
|---|------|----------|
| **E1** | A live **403** — a member calls a gated `@OpaPreAuthorize` `#teamId` mutation (denied) | `Content-Type: application/problem+json` · `errorCode=ACCESS_DENIED` · status 403 · canonical members present |
| **E2** | A live **422** — an illegal tag assignment (catalog) **or** a subset-rule violation (user-svc) | `problem+json` · `errorCode` == `TAG_VALUE_ILLEGAL` / `ROLE_SUBSET_VIOLATION` |
| **E3** | A live **409** — a conflict (duplicate team target / immutable role / tag-key conflict) | `problem+json` · the expected `errorCode` (`STATE_CONFLICT` or the app refinement) |
| **E4** | A live **201** — a create the matrix already issues (create team / create category / add member) | the response carries `Location: /api/v1/<collection>/<id>` matching the created id |
| **E5** *(opt — if a matrix already drives them)* | a live **404** / **400** / **503** | `problem+json` + `RESOURCE_NOT_FOUND` / `VALIDATION_FAILED` / `DEPENDENCY_UNAVAILABLE` |

## Fail-closed checklist (must all hold — preserved verbatim, no fail-open introduced)

- [ ] **No status changes.** Every exception maps to the **same** HTTP status as today (404/400/403/409/422/503) — only the body shape + the typed `errorCode` change (U2, I1–I6, build green).
- [ ] **403 is a real deny, now a problem body.** OPA-deny / unauth / unresolved subject still **denies** (403); the only change is it now renders `application/problem+json` with `ACCESS_DENIED` (U5, I3/I6, E1). No path widens access.
- [ ] **Fail-closed 503 unchanged.** The catalog dictionary outage still returns 503 and does **not** store the resource untagged — now `DEPENDENCY_UNAVAILABLE` (I2c).
- [ ] **No cross-parent / no-grant leak.** A child via the wrong parent still 404s; list endpoints still cut to empty on no-grant — unchanged (this slice touches only the error body, not list/scope logic).
- [ ] **Ungated bootstrap stays ungated.** `UserController` + `POST /teams` keep **no `@OpaPreAuthorize`** — the slice adds only an intent comment (I7); no gate added or removed anywhere.
- [ ] **Clean replacement.** No body carries a legacy `message` alongside `detail`; `ApiError` is gone from both specs; content type is `application/problem+json` (U4, C2, C3).
- [ ] **`opa-abac-core` untouched** (Spring-free boundary preserved — grep the diff; the carrier + codes live in `-spring-security`).

## Related
- [[01-DECOMPOSITION]] (the tickets these cases gate) · [[00-DESIGN]] (the design + the fail-closed
  posture §7 + the exception→code table §3) · ADR [[0011-error-contract-problem-json|0011]] (the pinned
  forks).
- [[REST-API-DESIGN-REVIEW]] (findings #1/#2/#4) · [[REST-API-DESIGN]] (§3/§4/§6/§9 — the guide this
  advances).
- The shipped templates: `docs/to-do/implemented/TAG-DICTIONARY/10-QA-TEST-CASES.md` (the multi-service,
  contract-touching shape) · `docs/to-do/implemented/HIERARCHY-LIST-FILTER/10-QA-TEST-CASES.md` (the
  U/I/E grouping + fail-closed-checklist shape).
