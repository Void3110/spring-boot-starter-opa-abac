---
tags:
  - status/done
  - type/project
  - area/api
  - area/spring
---

# STATUS T1 — Library: `ApiErrorCode` interface + `LibraryErrorCode` enum + `ProblemDetail` carrier + advice base/mapping helper

> ✅ **Shipped.** One focused commit on `feature/void3110/rest-api-refinement`. The reusable library
> error-contract foundation — independently landable (pure library + unit tests, no app).

## What shipped

In `opa-abac-spring-security` (`dev.dmitriikonovalov.opaabac.security`), five new types + a package-info
doc edit:

- **`ApiErrorCode`** (interface) — the contract slot: `code()` (abstract), plus `default`
  `problemType()` (derives `/problems/<kebab-of-code>` — a stable **relative** opaque id, no host) and
  `default title()` (derives a Title Case phrase from `code()`). An implementor need only supply `code()`.
- **`LibraryErrorCode`** (enum implementing `ApiErrorCode`) — the seven library-owned codes, **each
  carrying its `HttpStatus`** via a `status()` accessor (so the advice resolves `(status, code)` from one
  source): `ACCESS_DENIED` 403, `DEPENDENCY_UNAVAILABLE` 503, `VALIDATION_FAILED` 400,
  `RESOURCE_NOT_FOUND` 404, `STATE_CONFLICT` 409, `TAG_VALUE_ILLEGAL` 422, `ROLE_SUBSET_VIOLATION` 422.
  Each overrides `title()` with a human, status-stable phrase; `code()` returns the enum name.
- **`ProblemDetail`** (record) — the library's own RFC-7807 carrier: seven members `type`, `title`,
  `status` (int), `detail`, `instance`, `errorCode`, `timestamp` (`OffsetDateTime`). `@JsonInclude(NON_NULL)`.
  **No `message`.** Deliberately NOT Spring's `org.springframework.http.ProblemDetail` (whose untyped
  `properties` map would carry `errorCode` untyped — ADR 0011 §5).
- **`ProblemDetailFactory`** — builds a `ProblemDetail` from `(HttpStatus, ApiErrorCode, detail, instance)`
  (`type=code.problemType()`, `title=code.title()`, `status=status.value()`, `errorCode=code.code()`,
  `timestamp=now()`) and a `ResponseEntity<ProblemDetail>` at
  `Content-Type: application/problem+json` (`MediaType.APPLICATION_PROBLEM_JSON`). No decision logic.
- **`AbstractProblemAdvice`** (abstract `@RestControllerAdvice` base each app advice extends) — owns the
  shared `ProblemDetailFactory` + `problem(...)` convenience builders **and** the
  `@ExceptionHandler({AccessDeniedException, AuthorizationDeniedException})` → `403`
  `LibraryErrorCode.ACCESS_DENIED` mapping. This is the seam that makes a denied `@OpaPreAuthorize` /
  `OpaAuthorizationManager` call render as `problem+json` (today Spring Security's default renders the
  403, NOT the per-app `ApiError` advice — so without this, 403s would miss the contract). It pulls
  `instance` from `HttpServletRequest.getRequestURI()`.

## Tests

`./gradlew :opa-abac-spring-security:test` — **green** (15 tests: 6 new + the 9 existing advice/manager
tests, no regression). New `ProblemDetailContractTest` proves U1–U6:

- **U1** every `LibraryErrorCode` exposes `code()`/`problemType()`/`title()` — `code()`==name;
  `problemType()`==`/problems/<kebab>` (no `_`, no `://` host); `title()` non-blank.
- **U2** each code → the right `HttpStatus` (403/503/400/404/409/422/422).
- **U3** the factory builds a `ProblemDetail` from the tuple — all seven members populated, `timestamp`≈now.
- **U4** the carrier serializes (JavaTimeModule mapper) to **exactly** `type,title,status,detail,instance,
  errorCode,timestamp` and **no `message`**; the `ResponseEntity` content type is `application/problem+json`.
- **U5** `AccessDeniedException` **and** `AuthorizationDeniedException` → `ACCESS_DENIED` at 403 `problem+json`.
- **U6** a foreign app enum implementing `ApiErrorCode` (`WIDGET_JAMMED`) plugs into the factory unchanged —
  `errorCode`/`type`/`title` all derive from the app code; no library change needed (the interface is the
  only seam, DIP proven).

## Architecture review + refactor (the ★ gate)

- **Fail-closed:** T1 adds **no decision logic**. The one behavioral addition —
  `AbstractProblemAdvice.handleAccessDenied` — *renders* a 403 already raised by the authorization layer;
  it never authorizes, never yields 200, never swallows the deny (Javadoc states this explicitly). No
  status change, no access widening.
- **Boundary / additivity:** `git diff --stat main...HEAD -- opa-abac-core/` is **empty** (core untouched,
  stays Spring-free). No example code changed (`git status` on both example modules empty). T1 ships only
  **new** types → both example modules still compile against the old generated `ApiError` until they adopt
  in T2/T3. `:opa-abac-spring-security:build` + `:opa-abac-spring-boot-starter:compileJava` green.
- **Module separation:** all five types live in `opa-abac-spring-security`; core learns nothing of the wire
  contract.
- **Pattern reuse / SOLID:** SRP across the four collaborators (factory builds the body, the enum names
  the failures, the interface is the seam, the base routes the shared deny); DIP via `ApiErrorCode`
  (proven by U6). Used the **library-shipped** `ProblemDetail`, not Spring's untyped one.
- **Refactored:** **nothing substantive** — the design landed cohesive on the first pass. (No invented churn.)

## Integration / e2e

T1 is library-internal — no rig. The per-service MockMvc ITs are T2/T3; the gateway e2e is T4.

## Decisions

- **Helper shape = factory + abstract base, both.** `ProblemDetailFactory` holds the (mock-free,
  unit-testable) build logic; `AbstractProblemAdvice` is the `@RestControllerAdvice` base the app advices
  extend (it carries the shared `AccessDeniedException` handler — which must be an `@ExceptionHandler` on
  an advice, so a base class is the natural home; a bare utility couldn't carry it).
- **Status carried on the `LibraryErrorCode` constant** (`status()` accessor) so the advice never
  re-invents the status at the call site — `(status, code)` resolve together.
- **`problemType()`/`title()` are `default` methods** deriving from `code()`; `LibraryErrorCode` overrides
  `title()` with curated phrasing, keeps the derived `problemType()`.

## Commit

`feat(spring-security): RFC-7807 ProblemDetail carrier + ApiErrorCode vocabulary + advice base` on
`feature/void3110/rest-api-refinement`.
