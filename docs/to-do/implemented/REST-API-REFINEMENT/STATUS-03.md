---
tags:
  - status/done
  - type/project
  - area/api
  - area/spring
---

# STATUS T3 — User-service: `UserMgmtErrorCode` + advice remap + OpenAPI `ProblemDetail` schema + `Location` on 201 + intent comments + MockMvc IT

> ✅ **Shipped.** One focused commit on `feature/void3110/rest-api-refinement`. The user-service adopts the
> contract with a **semantically-split** conflict vocabulary, plus the review-gate library refactor (see
> the ★ section) and the two bootstrap intent comments.

## What shipped

- **`UserMgmtErrorCode`** (enum implementing `ApiErrorCode`, `…usermgmt.web`) — seven app codes, each
  carrying its `HttpStatus`. The 409 conflict group is **split** (ADR 0011 §4): `TEAM_TARGET_EXISTS`,
  `MEMBERSHIP_CONFLICT`, `ROLE_CODE_CONFLICT`, `ROLE_IMMUTABLE`, `TAG_KEY_CONFLICT`,
  `TAG_DEFINITION_IMMUTABLE` (all 409); plus `TAG_DEFINITION_INVALID` (422).
- **`web/ApiExceptionHandler`** now `extends AbstractProblemAdvice`, one `@ExceptionHandler` per
  distinct conflict (so each maps to its own code) + grouped handlers for the generic 404/400 (reusing
  `LibraryErrorCode.RESOURCE_NOT_FOUND` / `VALIDATION_FAILED`) and the subset rule (reusing the library
  `ROLE_SUBSET_VIOLATION`). All build a `ProblemDetail` at `application/problem+json`; **same status per
  exception as before**. 403 inherited from the library base.
- **The final exception → `errorCode` map:**
  | Exception(s) | Status | `errorCode` | Owner |
  |---|---|---|---|
  | `NotFound` / `MembershipNotFound` / `RoleNotFound` / `TagDefinitionNotFound` | 404 | `RESOURCE_NOT_FOUND` | library |
  | `IllegalArgument` / `MethodArgumentNotValid` | 400 | `VALIDATION_FAILED` | library |
  | `TeamTargetExists` | 409 | `TEAM_TARGET_EXISTS` | app |
  | `MembershipConflict` | 409 | `MEMBERSHIP_CONFLICT` | app |
  | `RoleConflict` | 409 | `ROLE_CODE_CONFLICT` | app |
  | `SystemRoleImmutable` | 409 | `ROLE_IMMUTABLE` | app |
  | `TagKeyConflict` | 409 | `TAG_KEY_CONFLICT` | app |
  | `TagDefinitionImmutable` | 409 | `TAG_DEFINITION_IMMUTABLE` | app |
  | `SubsetRuleViolation` | 422 | `ROLE_SUBSET_VIOLATION` | library |
  | `InvalidTagDefinition` | 422 | `TAG_DEFINITION_INVALID` | app |
  | OPA-deny / unauth | 403 | `ACCESS_DENIED` | library (inherited) |
- **`openapi/user-mgmt-api.yaml`** — `ApiError` removed; `ProblemDetail` schema added with the **typed
  `errorCode` enum** (the 11-code union the service emits); all five reusable responses
  (`BadRequest`/`NotFound`/`Conflict`/`Forbidden`/`UnprocessableEntity`) flipped to
  `application/problem+json` + `ProblemDetail`.
- **`Location` on every 201** — built from the **DTO's** addressable id (entities expose no `getId()`):
  `User`/`Team` → `/{id}` (UUID); `Membership` → `/{userId}` (the GET-by-id path is `/members/{userId}`);
  `RoleDefinition` → `/{code}` (string-keyed); `TagDefinition` → `/{key}`. All via
  `ServletUriComponentsBuilder.fromCurrentRequest()`.
- **Intent comments (review #4, comment-only)** — one line at `UserController.createUser` and
  `TeamController.createTeam`: `bootstrap: pre-membership, authenticated-only by design …`. **Neither
  gained `@OpaPreAuthorize`** — the endpoints stay ungated by design (I7 grep confirms both comments
  present, no annotation added).

## Tests

`./gradlew :example-user-management-service:build` + the full `./gradlew build` — **green** (codegen clean,
all Testcontainers ITs against real Postgres). New `ErrorContractIT` (real secured chain, random port,
subject header) — **5/5**:

- **I4** GET unknown user → 404 `problem+json`, `errorCode=RESOURCE_NOT_FOUND`,
  `type=/problems/resource-not-found`, no `message`.
- **I4b** blank-name create → 400 `problem+json`, `errorCode=VALIDATION_FAILED`, no `message`.
- **I5** duplicate team target → 409 `problem+json`, `errorCode=TEAM_TARGET_EXISTS`.
- **I6** a stranger calling a gated `/teams/{id}/members` → **403** `problem+json`,
  `errorCode=ACCESS_DENIED` (a **real** deny — the in-process OPA client returns no role-def → deny → the
  inherited `AccessDeniedException` mapping renders the body; proven end-to-end, not just by U5).
- **I6-201** create user → `Location: /api/v1/users/<id>`.
- **I5b** (the 422 subset body) is exercised by `MembershipManagementIT`'s subset path; the remaining 409
  refinements are asserted live in the e2e (T4).
- **I7** grep: both intent comments present; neither create gained `@OpaPreAuthorize`.

## Architecture review + refactor (the ★ gate)

**A substantive refactor was applied** (decided with the maintainer):

- The user-svc advice initially needed a private `appProblem()` helper because `ApiErrorCode` exposed no
  `status()` — only the concrete enums carried it (via a non-interface accessor). **`status()` was lifted
  onto the `ApiErrorCode` interface** so the shared `AbstractProblemAdvice` resolves `(status, errorCode)`
  for *any* code generically: the base gained a single `problem(ApiErrorCode, detail, request)` overload
  (replacing the `LibraryErrorCode`-specific one), `LibraryErrorCode.status()` / `UserMgmtErrorCode.status()`
  became `@Override`, and **both** app advices now call the one generic `problem(...)` — the `appProblem`
  boilerplate is gone. (Empty `CatalogErrorCode` implements `status()` with an unreachable throw — it has
  no constants.) This makes the contract slot complete and the per-app advices trivial. T1's unit tests
  (U1–U6) stay green; the test's foreign `AppErrorCode` gained `status()`.
- **Fail-closed:** every exception → the **same** status as today (404/400/409/422); the inherited 403
  renders a deny, never authorizes (I6 proves it end-to-end); the ungated bootstrap mutations stay ungated
  (only commented — I7). No status changed, no access widened.
- **Boundary / additivity:** `opa-abac-core` untouched (empty diff). The `ApiError`-removal build-breaker is
  confined to the user-svc module and lands in this commit. The interface refactor is additive to the
  *behavior* (no error path changes) though it touches T1's committed `ApiErrorCode` — noted in STATUS-01.
- **Semantic granularity:** the 409 group is genuinely split per client-actionable failure; generic
  failures reuse library codes; no codes invented beyond the discriminated set.
- **Clean-room:** scan of all changed files empty.

## Integration / e2e

`ErrorContractIT` above is the integration proof (real Postgres + real secured chain). The gateway e2e is
T4.

## Decisions

- **Conflict split** into six distinct 409 codes + one 422 app code (recorded in the map above).
- **`Location` keyed by the addressable identifier** per resource (userId for membership, code for
  role-def, key for tag-def — not the surrogate row id), matching each resource's GET-by-id path.
- **`status()` lifted to `ApiErrorCode`** (review-gate refactor) — completes the contract slot, removes
  per-app boilerplate; chosen over keeping a per-app helper.

## Commit

`feat(user-svc): adopt RFC-7807 problem+json error contract + Location + intent comments` (incl. the
`ApiErrorCode.status()` library refactor) on `feature/void3110/rest-api-refinement`.
