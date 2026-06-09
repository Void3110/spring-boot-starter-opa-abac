---
tags:
  - status/planned
  - type/architecture
  - area/api
  - area/architecture
  - area/spring
---

# 00 — Design: REST API refinement — the error contract (Phase 5.9)

> The design, written from a settled **ADR [[0011-error-contract-problem-json|0011]]** (which pins every
> fork below) and the [[REST-API-DESIGN-REVIEW|REST API design review]] (findings #1, #2, #4). A
> **contract-shape** change: the error body becomes canonical RFC-7807 `application/problem+json` with a
> typed, library-owned `errorCode` vocabulary. **No authorization behavior changes; `opa-abac-core` is not
> touched.**

## 1. The problem, precisely

Both example services return errors through a per-service `@RestControllerAdvice`
(`…/web/ApiExceptionHandler.java`) that builds the same generated `ApiError` model:

```java
// example-catalog-management-service/.../web/ApiExceptionHandler.java (today)
private ResponseEntity<ApiError> error(HttpStatus status, String message) {
    var body = new ApiError().status(status.value()).message(message).timestamp(OffsetDateTime.now());
    return ResponseEntity.status(status).body(body);   // content type: application/json
}
```

declared in both OpenAPI specs as:

```yaml
# .../openapi/catalog-api.yaml  (and user-mgmt-api.yaml, identical)
ApiError:
  type: object
  required: [status, message]
  properties: { status: int32, message: string, timestamp: date-time }
```

The mapping is clean and the **statuses are right** (the review confirmed `400/403/404/409/422/503`
discipline is correct and fail-closed). Two gaps remain for a *published* library:

1. **No machine-stable `errorCode`.** A consumer must branch on the human `message` string. The handlers
   already discriminate distinct failures at the **same** status — e.g. in the catalog service an
   `IllegalTagAssignmentException` and a Bean-Validation miss are different problems; in the user-service
   several distinct exceptions all map to `409` (conflict) and several to `422` (domain rule) — but the wire
   body collapses each status to one undifferentiated shape. A client cannot tell them apart.
2. **Not `application/problem+json`.** The body is a near-subset of RFC-7807 served as `application/json`.

The existing exception → status map (the vocabulary the codes must cover), read from the two advices:

| Service | Exception(s) today | Status | → `errorCode` (semantic) |
|---|---|---|---|
| both | `NotFoundException` / not-found | `404` | `RESOURCE_NOT_FOUND` |
| both | `MethodArgumentNotValidException`, `IllegalArgumentException` | `400` | `VALIDATION_FAILED` |
| catalog | `IllegalTagAssignmentException` | `422` | `TAG_VALUE_ILLEGAL` |
| catalog | `TagDefinitionFetchException` (fail-closed) | `503` | `DEPENDENCY_UNAVAILABLE` |
| user-svc | conflict exceptions (duplicate target, immutable role, …) | `409` | `STATE_CONFLICT` *(+ app-specific refinements)* |
| user-svc | domain-rule exceptions (subset rule, …) | `422` | `ROLE_SUBSET_VIOLATION` *(+ refinements)* |
| library | OPA-deny / unauth / unresolved subject | `403` | `ACCESS_DENIED` |

> **Library-vs-app split.** `RESOURCE_NOT_FOUND` / `VALIDATION_FAILED` / `STATE_CONFLICT` / `ACCESS_DENIED` /
> `DEPENDENCY_UNAVAILABLE` and the library-raised domain rejections (`TAG_VALUE_ILLEGAL`,
> `ROLE_SUBSET_VIOLATION`) are **library-owned** (the failures the library itself raises or that are generic
> across services). Each service's *own* distinct conflicts/rules (e.g. "duplicate team target" vs "immutable
> system role" if a client would branch on them) are **app-owned** codes. The exact app split is a
> decomposition detail; the seam is fixed here.

## 2. The target body (ADR 0011 §1)

A canonical RFC-7807 object — five standard members + two documented extensions:

```json
{
  "type":      "/problems/tag-value-illegal",
  "title":     "Tag value not permitted by the dictionary",
  "status":    422,
  "detail":    "Unknown tag key: reglon",
  "instance":  "/api/v1/catalogs/7b…/categories",
  "errorCode": "TAG_VALUE_ILLEGAL",
  "timestamp": "2026-06-09T10:15:30Z"
}
```

| Member | Origin | Notes |
|---|---|---|
| `type` | new | a **stable, relative, opaque** identifier (`/problems/<kebab-code>`). **No hosted registry** — not dereferenced. (ADR 0011 §1.) |
| `title` | new | a short, **status-stable** summary of the problem *kind* (not the instance). One per `errorCode`. |
| `status` | kept | the HTTP status, as today. |
| `detail` | **renamed** from `message` | the human, instance-specific text. |
| `instance` | new | the request path (correlation). |
| `errorCode` | new **extension** | the typed machine-stable code — §3. |
| `timestamp` | kept as **extension** | retained for correlation. |

Content type: **`application/problem+json`** on every error response.

## 3. The `errorCode` vocabulary (ADR 0011 §3–5)

Typed **and** open to app extension. A Java `enum` can't be subclassed, so the shared **contract** is an
interface; the library and each app each ship an enum implementing it.

```java
// opa-abac-spring-security — the contract slot
public interface ApiErrorCode {
    String code();           // the stable wire value, e.g. "TAG_VALUE_ILLEGAL"
    String problemType();    // the stable relative `type` URI, e.g. "/problems/tag-value-illegal"
    String title();          // the status-stable human title
    // (default methods may derive problemType()/title() from code() — a decomposition detail)
}

// opa-abac-spring-security — the library's own codes (the failures it raises / generic ones)
public enum LibraryErrorCode implements ApiErrorCode {
    ACCESS_DENIED, DEPENDENCY_UNAVAILABLE, VALIDATION_FAILED,
    RESOURCE_NOT_FOUND, STATE_CONFLICT, TAG_VALUE_ILLEGAL, ROLE_SUBSET_VIOLATION;
    // … code()/problemType()/title()
}

// each example service — its own domain codes
public enum CatalogErrorCode implements ApiErrorCode { /* catalog-specific … */ }
```

- **Semantic granularity** (ADR 0011 §4): one code per *distinct, client-actionable* failure the handlers
  already discriminate — not one per HTTP status (which would add nothing over `status`).
- **Typed in the contract** (ADR 0011 §5): `errorCode` appears in each spec's `ProblemDetail` schema as a
  **typed enum** (the union of codes that service can emit), so the generated client is typed and the
  vocabulary self-documents. This is why the library ships its own small `ProblemDetail` DTO rather than
  reusing Spring's built-in `org.springframework.http.ProblemDetail` (whose `properties` map would carry
  `errorCode` untyped, and which would couple the wire contract to a Spring type).

## 4. Where the pieces live

```
opa-abac-spring-security/                  (the library — advice-mappable exceptions already live here)
  ApiErrorCode            (interface — the contract slot)
  LibraryErrorCode        (enum impl — the library's own codes)
  ProblemDetail           (the carrier DTO — typed errorCode member)        ← or a shared advice base
  <advice base / mapping helper>  (maps a (status, ApiErrorCode, detail, path) → ProblemDetail body)

example-catalog-management-service/
  CatalogErrorCode        (enum impl — catalog's own codes)
  web/ApiExceptionHandler (remapped: each @ExceptionHandler → an ApiErrorCode + builds ProblemDetail)
  openapi/catalog-api.yaml (ApiError → ProblemDetail schema with the typed errorCode enum)
  web/*Controller          (Location header on every 201)

example-user-management-service/
  UserMgmtErrorCode       (enum impl — user-service's own codes)
  web/ApiExceptionHandler (remapped)
  openapi/user-mgmt-api.yaml (ProblemDetail schema)
  web/*Controller          (Location on 201; intent comments at the ungated bootstrap mutations)
```

> **`opa-abac-core` is untouched** — the error contract is HTTP/Spring-MVC-shaped, so it lives in
> `opa-abac-spring-security` and the example web layers. Core's Spring-free boundary is preserved.

## 5. Clean replacement, not a hybrid (ADR 0011 §2)

`ApiError` is **removed** from both specs and replaced by `ProblemDetail`; `message` does **not** survive
alongside `detail`. Pre-publication there are **no external consumers** — the only client is the repo's own
newman e2e — so the only cost of the break is updating our own assertions, which we want to update anyway to
pin the new `errorCode`. The result is the canonical contract a reference service should model, not a
transitional near-7807 body.

Because the schema is in **both** specs and is **codegen'd**, the replacement is a coordinated, per-service
edit: the spec change regenerates the model, the advice builds the new body, the build proves the contract
(drift = build break). This is the natural T2/T3 split (one ticket per service, each independent once the
library carrier + interface land in T1).

## 6. The two ride-along items (review #2, #4)

- **`Location` on every `201`** (review #2). Each create already has the new id and the path template, so
  emitting `Location: /api/v1/<collection>/<id>` is mechanical — `CatalogController`/`CategoryController`/
  `ProductController` (catalog) and `UserController`/`TeamController`/`MembershipController`/
  `RoleDefinitionController`/`TagDefinitionController` (user-service). No body change.
- **Intent comments at the deliberately-ungated bootstrap mutations** (review #4). `UserController` and
  `POST /teams` carry **no `@OpaPreAuthorize`** by design (a new user must bootstrap their own record + first
  team *before* they have a membership to authorize against — a chicken-and-egg). This slice adds a one-line
  comment ("bootstrap: pre-membership, authenticated-only by design") at each such site so the absence reads
  as a **decision**, not a forgetting. **No behavior change — a comment only.** Its "acceptance" is a
  grep/review check that each named site carries the line.

## 7. Fail-closed posture (unchanged — there is none to add)

This slice introduces **no decision logic** and **no fail-open**. The fail-closed invariants the review
verified are **preserved verbatim**: list endpoints still cut to empty on no-grant; `@OpaPreAuthorize` still
denies on OPA error / unauth / unresolved subject; the catalog write still throws `503` (now
`DEPENDENCY_UNAVAILABLE`) rather than store untagged; a child via the wrong parent still `404`s. Every error
path lands on the **same status** as before — it merely now carries a typed `errorCode`. The one thing to
keep honest (per the review): the ungated bootstrap mutations and the `/internal` `permitAll` rest on
**deployment** invariants the guide states — this slice does not change that; it only documents the
bootstrap intent in-code.

## 8. Proof posture (ADR 0011 §Consequences)

A contract-shape change with no decision logic is proven below the gateway; e2e only confirms the shape
survives the round-trip:

| Layer | Asserts | Weight |
|---|---|---|
| **Unit (library + each app advice)** | each handled exception → the right `(status, errorCode)`; the `ProblemDetail` carrier serializes the canonical members + `errorCode`/`timestamp`; content type `application/problem+json`. | **Must-have, exhaustive** — one assertion per error case. |
| **OpenAPI codegen** | both specs declare `ProblemDetail` with the typed `errorCode` enum; `./gradlew build` codegen clean (drift = build break). | **Automatic** (the build proves it). |
| **Integration (MockMvc / `@WebMvcTest`, per service)** | a representative error per status (`400/403/404/409/422/503`) returns a well-formed `problem+json` with the expected `errorCode`; a `201` carries `Location`. | **Must-have but lean** — one focused slice test per service. |
| **E2E (newman, through the gateway)** | **extend the existing matrices'** negative cases to assert `problem+json` + the right `errorCode`; assert `Location` on existing `201`s. **No new collection.** | **Light touch** — no new rig surface. |
| **Intent comments (#4)** | grep/review: each named ungated mutation carries the one-line comment. | check, not a test. |

## 9. Considered & rejected (from ADR 0011 — recorded here for the decomposition)

| Option | Why not |
|---|---|
| Full RFC-7807 with a hosted, dereferenceable **type registry** | Publication *infrastructure* (hosting + a maintained registry), not a code slice. Stable opaque `type` ids give the contract value with none of the tail. |
| Lean on Spring's built-in **`ProblemDetail`** | `errorCode` lands in the untyped `properties` map → loses first-class typing in the OpenAPI schema (the thing consumers branch on) and couples the wire contract to a Spring type. |
| **Back-compat additive superset** (keep `message` + `application/json`) | Muddy near-7807 body with both `message` and `detail`. No external consumers → a clean break is free. |
| **Free-string `errorCode`** (no typed vocabulary) | Re-introduces stringly-typed branching — the problem the slice exists to kill. The interface keeps it open *and* typed. |
| **Per-service enums only; library ships no codes** | The library should *name* the authorization failures it owns (deny / fail-closed 503 / subset / tag). Interface + base-enum lets it. |
| **Coarse codes (one per status)** | Adds nothing over `status`. Codes must discriminate *within* a status. |
| **Fold in pagination / actions** | Pagination → slice 5.95 (touches the partial-eval residual); actions → Phase 6. This slice is the error contract only — one cohesive unit. |

## 10. What this slice does NOT do

- **Pagination** → [[POC-ROADMAP|Phase 5.95]] (its own slice; composes with the partial-eval residual).
- **`actions`/`pageActions`** → **Phase 6** ([[ACTION-ENRICHMENT]]).
- **A hosted problem-type registry** — `type` ids are stable + opaque.
- **Any authorization change** — load-then-check vs annotation-only (review #3) is documentation (done in
  the guide); the ungated bootstrap mutations stay ungated by design (only commented).
- **Touch `opa-abac-core`** — the contract is HTTP-shaped; core stays Spring-free.

## Related

- ADR [[0011-error-contract-problem-json|0011]] — the decision this design implements.
- [[REST-API-DESIGN-REVIEW]] (findings #1/#2/#4) · [[REST-API-DESIGN]] (§3/§4/§9 — the guide this advances).
- ADR [[0006-three-layer-enforcement-model|0006]] — the app layer this shapes.
- [[POC-ROADMAP]] — Phase 5.9 (this); 5.95 (pagination); 6 (actions). · [[USER-STORIES]] — Epic F (F3).
