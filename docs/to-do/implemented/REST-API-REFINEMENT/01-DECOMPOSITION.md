---
tags:
  - status/planned
  - type/project
  - area/api
  - area/spring
  - area/architecture
---

# 01 — Decomposition: REST API refinement — the error contract (Phase 5.9)

> The ordered work list for [[REST-API-REFINEMENT|Slice 5.9]], decomposed from [[00-DESIGN]] +
> ADR [[0011-error-contract-problem-json|0011]] + the [[REST-API-DESIGN-REVIEW]] (findings #1, #2, #4).
> **5 tickets, one focused commit each.** Each ticket's *Acceptance* references a case in
> [[10-QA-TEST-CASES]]. Packages: library under `dev.dmitriikonovalov.opaabac.security`; example services
> under `dev.dmitriikonovalov.example.{catalog,usermgmt}.web` (+ generated
> `…openapi.model.ProblemDetail`).
>
> **Critical path: T1 → (T2 ∥ T3) → T4 → T5.** T1 is **independently landable** (pure library + unit
> tests, no app — it ships only *new* types, so the example advices still compile against the old
> `ApiError` until they adopt in T2/T3). T2 (catalog) and T3 (user-service) are **independent** of each
> other once T1's `ApiErrorCode` interface + `LibraryErrorCode` enum + `ProblemDetail` carrier land. T4
> (e2e) extends the existing newman matrices once both services emit the new body. T5 is docs + roadmap +
> Mulch + the folder move.

This slice is a **contract-shape** change, not a decision-logic one. **There is no authorization behavior
change and no fail-open to introduce.** Every error path lands on the **same status** it returns today,
now carrying a typed `errorCode` in a canonical `application/problem+json` body. **`opa-abac-core` is not
touched** (it is Spring-free; the error contract is HTTP/Spring-MVC-shaped, so it lives in
`opa-abac-spring-security` + the two example web layers). The replacement is **clean** — `ApiError` is
removed from both specs and the body carries `detail` (not a legacy `message` alongside it).

### Where each piece lands (decided — see [[00-DESIGN]] §4 "Where the pieces live")

```
opa-abac-spring-security (…opaabac.security):
    ApiErrorCode            interface  — the contract slot: code()/problemType()/title()
    LibraryErrorCode        enum impl  — the library's own codes (ACCESS_DENIED 403,
                                         DEPENDENCY_UNAVAILABLE 503, VALIDATION_FAILED 400,
                                         RESOURCE_NOT_FOUND 404, STATE_CONFLICT 409,
                                         TAG_VALUE_ILLEGAL 422, ROLE_SUBSET_VIOLATION 422)
    ProblemDetail           carrier DTO — the library's own (typed errorCode member, NOT Spring's)
    ProblemDetailFactory /  a reusable advice base / mapping helper that builds a
      AbstractProblemAdvice    (status, ApiErrorCode, detail, instance) → ProblemDetail body,
                               and maps Spring Security AccessDeniedException → ACCESS_DENIED 403
example-catalog-management-service (…example.catalog.web):
    CatalogErrorCode        enum impl  — catalog's own domain codes
    ApiExceptionHandler     remapped: each @ExceptionHandler → an ApiErrorCode → ProblemDetail
    openapi/catalog-api.yaml  ApiError → ProblemDetail schema (typed errorCode enum)
    {Catalog,Category,Product}Controller   Location header on every 201
example-user-management-service (…example.usermgmt.web):
    UserMgmtErrorCode       enum impl  — user-service's own domain codes
    ApiExceptionHandler     remapped
    openapi/user-mgmt-api.yaml  ProblemDetail schema
    {User,Team,Membership,RoleDefinition,TagDefinition}Controller   Location on every 201
    UserController + TeamController.createTeam   one-line intent comment (NO behavior change)
infra/docs:
    scripts/postman/*  EXTEND existing matrices (no new collection)
    docs/guides/REST-API-DESIGN.md  §9 Targets → adopted §3/§4/§6 ; POC-ROADMAP ; Mulch
```

---

## T1 — Library: `ApiErrorCode` interface + `LibraryErrorCode` enum + `ProblemDetail` carrier + advice base/mapping helper

**Goal.** Ship the library's error vocabulary and the reusable mapping seam the app advices build on —
the contract slot (interface), the library's own codes (enum), the typed carrier DTO, and a helper that
turns `(status, ApiErrorCode, detail, instance)` into a `ProblemDetail` body. The reusable core of the
slice — **independently landable** (pure library + unit tests, no app).

**Deliverables.**
- **`ApiErrorCode`** interface (`opa-abac-spring-security`, `dev.dmitriikonovalov.opaabac.security`):
  `String code()` (the stable wire value, e.g. `"TAG_VALUE_ILLEGAL"`), `String problemType()` (the stable
  **relative** opaque `type` id `/problems/<kebab>` — NO hosted registry), `String title()` (the
  status-stable human title). `problemType()`/`title()` MAY be `default` methods deriving from `code()`
  (kebab-of-`code()` for the type; a humanized title) — a free implementation detail, but the seam is the
  three accessors.
- **`LibraryErrorCode`** enum implementing `ApiErrorCode` — the failures the library raises / the generic
  ones, with the status each maps to **documented on the constant** (the status is carried for the advice
  to use, not invented at the call site): `ACCESS_DENIED` (403), `DEPENDENCY_UNAVAILABLE` (503),
  `VALIDATION_FAILED` (400), `RESOURCE_NOT_FOUND` (404), `STATE_CONFLICT` (409), `TAG_VALUE_ILLEGAL` (422),
  `ROLE_SUBSET_VIOLATION` (422). Each constant supplies `code()` (its enum name), `problemType()`
  (`/problems/<kebab-of-name>`), `title()`.
- **`ProblemDetail`** carrier DTO (the library's own — NOT `org.springframework.http.ProblemDetail`,
  whose untyped `properties` map would lose the typed `errorCode`). Seven members: `type`, `title`,
  `status` (int), `detail`, `instance`, `errorCode` (String — the typed enum lands in the *spec*; the
  carrier holds the wire value), `timestamp` (`OffsetDateTime`). Jackson-serializable to exactly the
  RFC-7807 member names; **no legacy `message`**.
- **A reusable advice base / mapping helper** — a `ProblemDetailFactory` (or an
  `AbstractProblemDetailAdvice` the app advices extend; the exact shape — utility vs base class — is a
  free choice, pick the one the app advices reuse most cleanly without duplicating the build logic). It:
  - builds a `ProblemDetail` from `(HttpStatus status, ApiErrorCode code, String detail, String instance)`
    — `type=code.problemType()`, `title=code.title()`, `status=status.value()`, `errorCode=code.code()`,
    `timestamp=now()`, `instance`=the request path;
  - returns a `ResponseEntity<ProblemDetail>` with `Content-Type: application/problem+json`
    (`MediaType.APPLICATION_PROBLEM_JSON`);
  - **maps Spring Security's `AccessDeniedException` (and `AuthorizationDeniedException`) →
    `LibraryErrorCode.ACCESS_DENIED` (403)** so a denied call also lands as `problem+json` (today a 403
    from `@OpaPreAuthorize`/`OpaAuthorizationManager` is rendered by Spring's default, NOT the
    `ApiError` advice — wiring this in the shared base is what makes 403 a first-class problem body that
    both services inherit).
- **Unit tests** (`opa-abac-spring-security`, mock-free where possible): U1–U6 in [[10-QA-TEST-CASES]] —
  every `LibraryErrorCode` constant maps to its expected `(status, code, type, title)`; the helper builds
  a well-formed `ProblemDetail` for a representative `(status, code, detail, instance)`; the carrier
  serializes to the seven canonical members at `application/problem+json` (Jackson round-trip / field
  assertion) with **no `message`**; an `AccessDeniedException` resolves to `ACCESS_DENIED` 403; a foreign
  app enum implementing `ApiErrorCode` plugs into the helper unchanged (the interface is the seam).

**Acceptance.** `./gradlew :opa-abac-spring-security:test` green (incl. the existing 5 advice/manager
tests — no regression), proving U1–U6. The new types compile and serialize; **no app code changed in this
ticket** (the example advices still build against the old `ApiError` — T1 is additive).

**What NOT to touch.** `opa-abac-core` (untouched — it stays Spring-free; the carrier + codes are
HTTP-shaped and live in `-spring-security`). The example services' `ApiExceptionHandler`s / specs (T2/T3
adopt). The `@OpaPreAuthorize` / `OpaAuthorizationManager` **decision** logic — T1 only adds an
*exception→code* mapping in the advice base, it does **not** change when a 403 is raised or widen access.
No new `ApiError` shape (the library never used `ApiError` — that is generated per-app).

> **Build-breaker note (minimal here).** T1 is **purely additive** — it ships only new types. Nothing the
> example advices import is removed or renamed, so both example modules still compile green against the
> old generated `ApiError` until they adopt in T2/T3. **T1 lands green alone.** (The clean replacement —
> removing `ApiError` — happens per-service in T2/T3, each self-contained.)

---

## T2 — Catalog: `CatalogErrorCode` + advice remap + OpenAPI `ProblemDetail` schema + `Location` on 201 + MockMvc IT

**Goal.** Adopt the new contract in the catalog service: its own domain codes, the advice rebuilt to emit
`ProblemDetail`, the spec's `ApiError` replaced by a typed `ProblemDetail`, and a `Location` header on
each of its three `201`s. Independent of T3 once T1 has landed.

**Deliverables.**
- **`CatalogErrorCode`** enum (`dev.dmitriikonovalov.example.catalog.web` or `…config`, beside the
  exceptions) implementing `ApiErrorCode` — catalog's own domain failures where a client would branch
  *within* a status beyond the library codes. From the catalog advice today the discriminated failures
  are `IllegalTagAssignmentException` (422 — maps to the library's `TAG_VALUE_ILLEGAL`) and
  `TagDefinitionFetchException` (503 — maps to the library's `DEPENDENCY_UNAVAILABLE`); `NotFoundException`
  (404 → `RESOURCE_NOT_FOUND`) and `MethodArgumentNotValidException` (400 → `VALIDATION_FAILED`) map to
  library codes too. **Per ADR 0011 §4 (semantic granularity), reuse a `LibraryErrorCode` where the
  failure is exactly the generic one; introduce a `CatalogErrorCode` constant only for a *distinct,
  client-actionable* catalog failure not already named** (e.g. if "unknown tag key" vs "enum-value miss"
  are separately actionable, split them; otherwise the single `TAG_VALUE_ILLEGAL` suffices). The enum may
  be empty/minimal if every catalog failure maps cleanly to a library code — that is acceptable and
  expected; **do not invent codes to fill it.** *(Document the mapping you chose in `STATUS-02.md`.)*
- **`web/ApiExceptionHandler` remapped** — each `@ExceptionHandler` resolves its exception to an
  `ApiErrorCode` and calls the T1 helper to build a `ProblemDetail` `ResponseEntity` at
  `application/problem+json`. Same status per exception as today (404/400/422/503 — unchanged). The
  private `error(HttpStatus, String)` is replaced by a call into the library helper carrying the
  `ApiErrorCode` + the request path (`instance`). 403 now flows through the inherited library mapping
  (T1) — confirm the catalog advice/base covers `AccessDeniedException`.
- **`openapi/catalog-api.yaml`** — **remove `ApiError`**, add a `ProblemDetail` schema (members `type`,
  `title`, `status` int32, `detail`, `instance`, `errorCode`, `timestamp` date-time) with **`errorCode`
  as a typed `enum`** — the union of codes this service can emit (the library codes it uses + any
  `CatalogErrorCode`). Repoint the two `$ref: '#/components/schemas/ApiError'` error responses (lines
  ~317/~323) to `ProblemDetail`, and set their response `content` key to `application/problem+json`.
  Codegen regenerates `…openapi.model.ProblemDetail`; `ApiError` is no longer referenced (drift = build
  break).
- **`Location` on every 201** — `CatalogController:43`, `CategoryController:79`, `ProductController:56`
  emit `Location: /api/v1/<collection>/<id>` (build via `ServletUriComponentsBuilder` or the path
  template + the saved id, which the controller already has). The 201 body is unchanged.
- **MockMvc / `@WebMvcTest` IT** — I1–I3 + the 201 case in [[10-QA-TEST-CASES]]: a representative error
  per status the catalog emits (404, 400, 422, 503, and a 403 via a denied call) returns a well-formed
  `application/problem+json` with the **expected `errorCode`** (named per case from the [[00-DESIGN]]
  table); a create returns `201` + `Location: /api/v1/catalogs/<id>`.

**Acceptance.** `./gradlew :example-catalog-management-service:build` green (codegen clean — `ApiError`
gone, `ProblemDetail` generated, drift would break the build). The MockMvc IT proves I1–I3 + the 201
`Location` case. The existing `CatalogCrudIT` (Testcontainers, real Postgres) stays green (no behavior
change — only the error body shape + a header changed).

**What NOT to touch.** `opa-abac-core`. The user-service (T3 — independent). The library types (consume
T1 read-only — do not add codes to `LibraryErrorCode` here; catalog-specific codes go in
`CatalogErrorCode`). **Authorization decisions** — no status changes, no gate added/removed; the catalog
write still throws 503 (now `DEPENDENCY_UNAVAILABLE`) rather than store untagged; a child via the wrong
parent still 404s. No legacy `message` field — the body carries `detail` only.

> **Build-breaker (self-contained to this commit).** Removing `ApiError` from the spec regenerates the
> model: `web/ApiExceptionHandler` (which imports `…openapi.model.ApiError`) and any catalog test
> referencing `ApiError` break until they switch to `ProblemDetail`. Update **all** of them in this same
> commit so `./gradlew :example-catalog-management-service:build` stays green. The breakage is confined to
> the catalog module (T1 added the types additively; T3's user-service module is unaffected).

---

## T3 — User-service: `UserMgmtErrorCode` + advice remap + OpenAPI `ProblemDetail` schema + `Location` on 201 + intent comments + MockMvc IT

**Goal.** Adopt the new contract in the user-service: its own domain codes, the advice rebuilt to emit
`ProblemDetail`, the spec replaced, a `Location` on each of its five `201`s, and the two one-line intent
comments at the deliberately-ungated bootstrap mutations (review #4 — comment only). Independent of T2
once T1 has landed.

**Deliverables.**
- **`UserMgmtErrorCode`** enum (`dev.dmitriikonovalov.example.usermgmt.web` or `…service`) implementing
  `ApiErrorCode` — the user-service's own domain failures that a client branches on *within* a status. The
  advice today groups several exceptions per status: **409** (`TeamTargetExistsException`,
  `MembershipConflictException`, `RoleConflictException`, `SystemRoleImmutableException`,
  `TagKeyConflictException`, `TagDefinitionImmutableException`), **422** (`SubsetRuleViolationException`,
  `InvalidTagDefinitionException`), **404** (`NotFoundException`, `MembershipNotFoundException`,
  `RoleNotFoundException`, `TagDefinitionNotFoundException`), **400** (`IllegalArgumentException`,
  `MethodArgumentNotValidException`). **Per ADR 0011 §4 (semantic granularity): give a distinct
  `errorCode` to each conflict/rule a client would *handle differently*** — e.g.
  `SubsetRuleViolationException` → the library `ROLE_SUBSET_VIOLATION` (422); the immutable-system-role
  conflict vs the duplicate-team-target conflict are plausibly distinct client actions → separate
  `UserMgmtErrorCode` constants (e.g. `ROLE_IMMUTABLE`, `TEAM_TARGET_EXISTS`, `MEMBERSHIP_CONFLICT`,
  `TAG_KEY_CONFLICT`, `TAG_DEFINITION_IMMUTABLE`, `TAG_DEFINITION_INVALID`), all 409/422 as today. Where a
  failure is exactly the generic one (`RESOURCE_NOT_FOUND`, `VALIDATION_FAILED`), **reuse the
  `LibraryErrorCode`** — do not duplicate it. *(Record the final exception→code map in `STATUS-03.md`.)*
- **`web/ApiExceptionHandler` remapped** — each `@ExceptionHandler` group resolves its exception to the
  right `ApiErrorCode` (library or `UserMgmtErrorCode`) and builds a `ProblemDetail` via the T1 helper at
  `application/problem+json`. Same status per exception as today (404/409/422/400 — unchanged). 403 flows
  through the inherited library `AccessDeniedException` mapping (T1).
- **`openapi/user-mgmt-api.yaml`** — **remove `ApiError`** (schema ~line 849), add the `ProblemDetail`
  schema with the typed `errorCode` enum (the union of codes this service can emit — the library codes it
  uses + the `UserMgmtErrorCode` set), repoint every `$ref: '#/components/schemas/ApiError'` error
  response to `ProblemDetail` and flip its `content` key to `application/problem+json`. Codegen clean
  (drift = build break).
- **`Location` on every 201** — `UserController:39`, `TeamController:42`, `MembershipController:52`,
  `RoleDefinitionController:53`, `TagDefinitionController:77` emit
  `Location: /api/v1/<collection>/<id>` (use the nested collection path where the resource is nested, e.g.
  `/api/v1/teams/{teamId}/memberships/{id}`). Body unchanged.
- **Intent comments (review #4 — comment only, NO behavior change)** — one line at each deliberately-
  ungated bootstrap mutation: at `UserController.createUser` (`UserController:35` — extend the existing
  class-level note to the method site) and `TeamController.createTeam` (`TeamController:38`):
  `// bootstrap: pre-membership, authenticated-only by design` (or equivalent wording). **No
  `@OpaPreAuthorize` added — the endpoints stay ungated by design.** Its acceptance is a grep/review
  check.
- **MockMvc / `@WebMvcTest` IT** — I4–I6 + the 201 case in [[10-QA-TEST-CASES]]: a representative error
  per status the user-service emits (404, 400, 409, 422, and a 403 via a denied gated call) returns a
  well-formed `application/problem+json` with the **expected `errorCode`**; a create returns `201` +
  `Location`.

**Acceptance.** `./gradlew :example-user-management-service:build` green (codegen clean — `ApiError`
gone, `ProblemDetail` generated). The MockMvc IT proves I4–I6 + the 201 `Location` case. The two intent
comments are present (grep check — I7). Existing user-service ITs stay green (no behavior change).

**What NOT to touch.** `opa-abac-core`. The catalog service (T2 — independent). The library types
(consume T1; user-service codes go in `UserMgmtErrorCode`). **Authorization decisions** — the ungated
bootstrap mutations stay ungated (only commented); the gated mutations (`Membership`/`RoleDefinition`/
`TagDefinition`/team `transfer-ownership`) keep their `@OpaPreAuthorize` unchanged; no status changes. No
legacy `message` field — `detail` only.

> **Build-breaker (self-contained to this commit).** As T2: removing `ApiError` regenerates the model,
> so `web/ApiExceptionHandler` + any user-service test referencing `ApiError` break until they switch to
> `ProblemDetail`. Update all in this same commit so `./gradlew :example-user-management-service:build`
> stays green. Confined to the user-service module.

---

## T4 — e2e: EXTEND the existing newman matrices (assert `problem+json` + `errorCode` on existing negatives; `Location` on existing 201s)

**Goal.** Prove, through the full rig, that the new body shape survives the round-trip: existing negative
cases now carry `application/problem+json` + the right typed `errorCode`, and existing `201`s carry
`Location`. **No new collection** — extend the matrices already in `scripts/postman/`.

**Deliverables.**
- **Extend existing newman collections** in `scripts/postman/` (e.g. `catalog-abac-matrix`,
  `tag-abac-matrix`, `team-abac-matrix`, `data-filter-matrix`, and/or `catalog-e2e` — whichever already
  exercise the negative cases below). For the live negative cases the matrices already hit, **add
  assertions** (do not add new requests unless a status below is not yet exercised anywhere):
  - a live **403** (a denied `@OpaPreAuthorize` call — e.g. a member calling a gated team mutation) →
    `Content-Type: application/problem+json` + `errorCode == "ACCESS_DENIED"` + status 403 + the canonical
    members present (`type`/`title`/`status`/`detail`/`instance`);
  - a live **422** (an illegal tag assignment in catalog, or a subset-rule violation in user-svc) →
    `problem+json` + the expected `errorCode` (`TAG_VALUE_ILLEGAL` / `ROLE_SUBSET_VIOLATION`);
  - a live **409** (a conflict — e.g. a duplicate team target or an immutable-role edit) → `problem+json`
    + the expected `errorCode`;
  - *(if a matrix already drives 404/400/503, assert those bodies too — E-cases in [[10-QA-TEST-CASES]].)*
- **`Location` on existing `201`s** — on the create requests the matrices already issue (e.g. create
  team, create category, add member), add an assertion that the response carries
  `Location: /api/v1/<collection>/<id>` matching the created id.
- Reuse the in-network token + collection-scope-id conventions; mint tokens in-network (issuer
  `keycloak:8888`); keep runtime-captured ids in **collection** variable scope. Update
  `scripts/postman/README.md` only if a runner's assertions table changes.

**Acceptance.** Rig up → the extended matrices run **green** with the new assertions
(`problem+json` + the right `errorCode` on the live negatives; `Location` on the live 201s), stable across
reruns. **No new collection file.** `bash -n` clean on any touched runner; JSON valid. No push.

**What NOT to touch.** No new collection. The gateway `gateway.rego` / OPA policies (unchanged — no rego
behavior change in this slice). `opa-abac-core`. The library/app code (T1–T3 — frozen by now; if the e2e
reveals a real body-shape bug, fix it by amending the relevant T2/T3 commit, not a new behavior). Do not
add a newman CI job (tracked follow-up).

> **Build-breaker: none.** T4 is test-asset-only (newman JSON + shell). It changes no compiled code.

---

## T5 — Docs (guide §9 Targets → adopted §3/§4/§6) + roadmap + Mulch + folder move

**Goal.** Promote the now-adopted conventions from "target" to "the rule", flip the roadmap, record the
durable insights, and move the slice folder to `implemented/`.

**Deliverables.**
- **`docs/guides/REST-API-DESIGN.md`** — move the two §9 *Targets* rows into the adopted body:
  - the **RFC-7807 `problem+json` + `errorCode`** row → §6 *Error handling* (the error envelope is now the
    `ProblemDetail` body — document the seven members, the `application/problem+json` content type, the
    library-owned `ApiErrorCode` interface + `LibraryErrorCode` + per-app enum, semantic granularity) and
    a one-line note in §3 (the status→`errorCode` discrimination rule);
  - the **`Location` on `201`** row → §4 *Request and response bodies* (it is now emitted — make the
    parenthetical at §4 line ~227 the adopted rule) and the §10 checklist "A `POST` that creates" item
    (drop the *(target)* marker on `Location`).
  - Remove the two adopted rows from §9 *Targets* (leaving pagination + `actions` there as the remaining
    targets). Cross-link ADR [[0011-error-contract-problem-json|0011]].
- **`POC-ROADMAP.md`** — flip **Phase 5.9** status from `🔜 NEXT — to plan` to `✅ DONE` (error contract
  shipped); confirm 5.95 (pagination) is the next slice.
- **Root/project `CLAUDE.md`** — only if a new build/run step matters (it does not — no new command), so
  likely no change; note that in `STATUS-05.md` if so.
- **Mulch** — record the durable insights: the library-owned `ApiErrorCode` interface + `LibraryErrorCode`
  enum + per-app enum split; the library-shipped `ProblemDetail` carrier vs Spring's untyped one (typed
  `errorCode` in the spec); the clean replacement of `ApiError`; the `AccessDeniedException`→403
  mapping moved into the shared advice base; the `Location`-on-201 + intent-comment ride-alongs. `ml sync`
  (`.mulch`-only — `git restore --staged .` first); `ml doctor` clean.
- **Record the `autonomous-runs` reference record** (flow phase ④) with `--outcome-status`, capturing
  OUTCOME + PAUSE-CAUSE · CHECKPOINT/TICKET FRICTION · PLANNING-GAP→FIX · QA (per the repo `CLAUDE.md`
  `autonomous-runs` section).
- **Move** `docs/to-do/planning/REST-API-REFINEMENT/` → `docs/to-do/implemented/REST-API-REFINEMENT/`
  (`git mv`), flip the index `status/planning` → `status/done`, add a past-tense **Shipped** banner.

**Acceptance.** The guide reads as adopted (no §9 row for the two shipped items; §3/§4/§6 + the checklist
state them as the rule); `POC-ROADMAP` shows Phase 5.9 done; Mulch synced (`.mulch`-only) + `ml doctor`
clean + the `autonomous-runs` record written; the folder is under `implemented/` with the Shipped banner;
**clean-room scan clean** across all touched docs. No push.

**What NOT to touch.** ADR 0011 (immutable — Accepted; the guide references it, the rationale does not move
into the guide). The `00-DESIGN` / index history (the move preserves them). `opa-abac-core`. No code change
in this ticket.

> **Build-breaker: none.** T5 is docs + Mulch + a folder move. No compiled code changes.

---

## Cross-cutting acceptance (the whole slice)

- `./gradlew build` green (all library modules + **both** example apps + OpenAPI codegen + the existing
  Testcontainers ITs + `ddl-auto: validate` boot). The MockMvc/`@WebMvcTest` slice tests need no Postgres;
  the full build still runs the existing Testcontainers ITs — keep them green (the podman `DOCKER_HOST` +
  `TESTCONTAINERS_RYUK_DISABLED=true` caveat is environment, not code).
- **The contract is clean-replaced, not hybridized:** `ApiError` is **removed** from both specs and no
  longer referenced anywhere; the body carries `detail` (no legacy `message` alongside it); content type
  is `application/problem+json` on every error response.
- **`errorCode` is typed in the contract:** both specs declare `ProblemDetail` with `errorCode` as a typed
  `enum` (the union of codes that service emits); the generated client is typed; drift = build break.
- **The vocabulary is library-owned-and-extensible:** the library ships `ApiErrorCode` (interface) +
  `LibraryErrorCode` (its own codes); each app ships its own enum implementing the same interface;
  granularity is **semantic** (one code per distinct client-actionable failure — `TAG_VALUE_ILLEGAL` ≠
  `ROLE_SUBSET_VIOLATION`, both 422; the user-service 409s are split where a client would branch).
- **Fail-closed posture preserved verbatim (no fail-open introduced):** no error path changes which status
  it returns or widens access; the only change is the body shape + a typed `errorCode`. List endpoints
  still cut to empty on no-grant; `@OpaPreAuthorize` still denies on OPA error / unauth / unresolved
  subject (now as `ACCESS_DENIED` 403 `problem+json`); the catalog write still 503s rather than store
  untagged; a child via the wrong parent still 404s. The ungated bootstrap mutations stay ungated (only
  commented).
- **`opa-abac-core` untouched** (grep the diff — no `opa-abac-core/` file changes); it stays Spring-free.
- The e2e proves the **round-trip shape** (a live `problem+json` + the right `errorCode` on a negative; a
  live `Location` on a 201), not just status codes — extending existing matrices, no new collection.
- **Clean-room scan clean** across all new/changed files; **nothing pushed** (the maintainer pushes).

## Critical path

```
            ┌────────► T2 (catalog: codes + advice + spec + Location + IT) ─┐
T1 ─────────┤                                                              ├──► T4 (e2e extend) ──► T5 (docs+roadmap+Mulch+move)
(library)   └────────► T3 (user-svc: codes + advice + spec + Location +    ─┘
                              intent comments + IT)
```
- **T1 independently landable** — the `ApiErrorCode` interface + `LibraryErrorCode` enum + `ProblemDetail`
  carrier + the advice base are reusable library value with no app dependency (additive — both example
  modules still build green against the old `ApiError` until they adopt).
- **T2 ∥ T3** — once T1 lands, the two services adopt **independently** (each is a self-contained spec +
  advice + `Location` + IT edit confined to its own module; the `ApiError`→`ProblemDetail` build-breaker
  is local to each module's commit).
- **T4** extends the existing matrices once both services emit the new body; **T5** promotes the guide,
  flips the roadmap, records Mulch + the `autonomous-runs` retrospective, and moves the folder to
  `implemented/`.
