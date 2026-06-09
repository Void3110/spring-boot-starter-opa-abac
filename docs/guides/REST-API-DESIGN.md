---
tags:
  - status/active
  - type/guide
  - area/api
  - area/architecture
  - audience/developer
---

# REST API design — the example services

> **Scope.** The conventions the two example services in this repo follow —
> [[USER-MANAGEMENT-SERVICE|user-management-service]] and the catalog-management-service — so a new
> endpoint looks like the existing ones and a reviewer has one bar to check against. Every rule here is
> grounded in code or the OpenAPI spec (`*/src/main/resources/openapi/*.yaml`); where the bar is a
> *target we have not adopted yet*, it is flagged **◓ Target** with the reason, so the guide describes
> reality and the roadmap in the same place.
>
> These services are **demonstrations** of the library, not products. Some choices (no pagination, open
> public mutations on the user service) are deliberate scope-cuts that keep the demo small. Where that is
> the case the guide says so — a demo simplification is a documented decision, not an accident.

The companion review of the current API against this guide is
[[REST-API-DESIGN-REVIEW]] (in `docs/code-review/`).

---

## Table of contents

1. [Spec-first: the OpenAPI contract is the source of truth](#1-spec-first)
2. [URL design](#2-url-design)
3. [HTTP methods and status codes](#3-http-methods-and-status-codes)
4. [Request and response bodies](#4-request-and-response-bodies)
5. [Authorization on an endpoint](#5-authorization-on-an-endpoint)
6. [Error handling](#6-error-handling)
7. [Pagination](#7-pagination)
8. [The internal (in-network) API surface](#8-the-internal-api-surface)
9. [Targets — conventions we have not adopted yet](#9-targets)
10. [Checklist for a new endpoint](#10-checklist)

---

## 1. Spec-first

The API contract is the **OpenAPI document**, not the Java. Each service keeps one spec at
`src/main/resources/openapi/<service>-api.yaml`; the Gradle `org.openapi.generator` plugin
(`generatorName=spring`, `interfaceOnly=true`) generates the `*Api` interface + the request/response model
classes at build time, and the controller **implements** the generated interface:

```java
@RestController
public class CategoryController implements CategoryApi {   // CategoryApi is generated from the spec
    @Override
    public ResponseEntity<Category> getCategory(UUID catalogId, UUID categoryId) { ... }
}
```

**Why spec-first.** The spec is the reviewable artifact and the single place paths, status codes, and DTOs
are declared. `implements` makes the compiler enforce that the controller matches the contract — a signature
drift is a build break, not a runtime surprise. DTOs are never hand-written.

**Consequences for an endpoint author:**
- Add the path/operation/schema to the YAML **first**; `./gradlew build` regenerates the interface; then
  implement the new method the interface now requires.
- Validation constraints (`minLength`, `maxLength`, `required`, `minimum`, `pattern`) live in the spec and
  generate Bean-Validation annotations on the DTO — they are enforced *before* the controller body runs
  (see [§6](#6-error-handling)).
- The spec's `info.version` tracks the API maturity (`0.1.0` today); the **URL** version (`/api/v1`) is a
  separate, slower-moving axis ([§2](#2-url-design)).

---

## 2. URL design

### Nouns, not verbs

A resource is a thing; the HTTP method is the verb. The URL names the resource.

```
GOOD: GET    /api/v1/catalogs/{catalogId}
GOOD: POST   /api/v1/catalogs/{catalogId}/categories
BAD:  POST   /api/v1/catalogs/{catalogId}/createCategory
BAD:  GET    /api/v1/getCatalogById/{catalogId}
```

**Custom operations** that don't map to CRUD are modelled as a **noun sub-resource**, not a verb. The one
example today is ownership transfer on the user service:

```
POST /api/v1/teams/{teamId}/transfer-ownership      ← the transfer is the sub-resource
```

The noun form leaves room to later add `GET /transfer-ownership` (e.g. read a pending transfer) without a
URL redesign. Prefer this over `POST /teams/{id}/transfer`.

### Versioning

Version lives **in the path**: every public path is under `/api/v1`. This is the coarse, rarely-bumped
axis — a breaking change to the wire contract is what justifies `/api/v2`, run side-by-side. The spec's
`info.version` (semver, `0.1.0`) moves independently for non-breaking maturity.

### Naming

| Element | Convention | Examples |
|---|---|---|
| Collections | **plural** noun | `/catalogs`, `/categories`, `/products`, `/teams`, `/members`, `/role-definitions`, `/tag-definitions` |
| Identifiers | path parameter | `/catalogs/{catalogId}`, `/teams/{teamId}/members/{userId}` |
| Natural-key identifiers | path parameter (the key itself) | `/role-definitions/{code}`, `/tag-definitions/{key}` |
| Filtering | query parameter | `/categories?parentId={id}`, `/tag-definitions?teamId={id}` |
| Custom operation | singular noun sub-resource | `/teams/{teamId}/transfer-ownership` |

A path parameter is an **opaque UUID** for surrogate keys, or the **natural key** where the resource has
one (`{code}` for a role definition, `{key}` for a tag definition) — the natural key is the addressable
identity, so the URL uses it directly.

### Nesting expresses ownership

Nesting mirrors the resource tree and scopes the operation ("categories *of this catalog*"):

```
/api/v1/catalogs/{catalogId}/categories/{categoryId}/products/{productId}
            1                     2                       3            ← 3 owned levels
```

**Guideline: ≤ 3 owned levels.** Each level adds URL length, client coupling (the client must know every
parent id), and an authorization scoping point. Three is the practical ceiling; the catalog tree hits it
exactly (catalog → category → product).

Two important caveats, both visible in the code:

1. **Nesting is data-scoping, not the authorization hierarchy.** A nested URL says "this product lives under
   this category under this catalog" — it does **not** mean every level is authorized in turn. The library's
   model resolves the decision against the **leaf** resource (with hierarchical *inheritance* as an explicit,
   opt-in widening — see [[HIERARCHICAL-AUTHORIZATION]]), not by walking each path segment as a separate
   check. The URL is for routing and scoping; [[ABAC-AUTHORIZATION|the ABAC model]] decides access.

2. **Scope is validated, not assumed.** When a child is addressed through its parents, the controller
   confirms the child actually belongs to them, and returns `404` if not — e.g. `getCategory` loads via
   `findByIdAndCatalogId(categoryId, catalogId)`, so `/catalogs/A/categories/{a-category-of-B}` is a `404`,
   never a leak of B's data. Don't trust the path; verify the lineage.

### When a path wants a 4th level

If a resource grows its own identity and lifecycle, promote it to a top-level collection (flat URL) and let
its parent be a field, rather than nesting a 4th level. Products are *not* promoted (they have no identity
outside their category), which is why the 3-level path is correct for them.

---

## 3. HTTP methods and status codes

### Methods

| Method | Purpose | Idempotent | Body |
|---|---|---|---|
| `GET` | read a resource or a collection | yes | no |
| `POST` | create a resource, or invoke a custom operation | no | yes |
| `PUT` | full replace of a resource | yes | yes |
| `DELETE` | remove a resource | yes | no |

`PATCH` is **not** used — updates are full replacement via `PUT` (the request DTO is the complete new
state). This keeps the contract simple; adopt `PATCH` only if partial update becomes a real need.

### Status codes in use

**Success**

| Code | When | Example |
|---|---|---|
| `200 OK` | `GET`, and `PUT` that returns the updated body | `GET /catalogs/{id}` · `PUT /teams/{id}/members/{userId}` |
| `201 Created` | `POST` that creates a resource (returns the created body) | `POST /catalogs` · `POST /teams/{id}/members` |
| `204 No Content` | `DELETE`, and a `POST` custom-op with no body | `DELETE /categories/{id}` · `POST /teams/{id}/transfer-ownership` |

**Client / server errors**

| Code | When | Example |
|---|---|---|
| `400 Bad Request` | request **syntax**/shape invalid (Bean-Validation failure, unparseable body, illegal argument) | empty `name`, `priceCents` < 0 |
| `403 Forbidden` | the ABAC decision **denied** access | non-owner calling `team:manage` |
| `404 Not Found` | the resource (or its claimed parent) doesn't exist | unknown `catalogId`; child not under the parent |
| `409 Conflict` | the request collides with current **state** (a uniqueness/immutability/lifecycle conflict) | duplicate team target; editing a system (immutable) role |
| `422 Unprocessable Entity` | the request is **syntactically valid but semantically rejected** by a domain rule | a tag value not in the dictionary; the role-subset rule |
| `503 Service Unavailable` | a required dependency was unreachable and the operation **fails closed** | the tag dictionary could not be fetched |

**No `401`.** Authentication happens at the gateway (APISIX OIDC) before a request reaches a service; a
service only sees already-authenticated requests, and an authorization denial is a `403`. (The library's
`AbacFilter` lets an *anonymous* request through to the security layer, which then denies — also `403`,
never `401`.)

### The decision rules that matter

These three boundaries are the ones that get argued about; pin them the same way every time.

**`400` vs `422` — syntax vs semantics.**
- `400` = the request is *malformed*: a missing required field, a too-long string, a negative price. The
  generated Bean-Validation catches these before your code runs.
- `422` = the request is *well-formed but breaks a domain rule*: a tag value the dictionary doesn't allow,
  a role assignment that violates the subset rule. The shape is fine; the meaning is not.
- Rule of thumb: if the validator could have caught it from the schema alone, it's `400`; if it took
  domain knowledge (the dictionary, the actor's own role) to reject it, it's `422`.

**`409` vs `422` — state-conflict vs rule-violation.**
- `409` = collides with *existing state*: a unique key already taken, a resource that is immutable, a
  lifecycle that forbids the transition. Re-sending later (after the state changes) could succeed.
- `422` = violates a *rule about the request itself*, independent of stored state. Re-sending unchanged
  will always fail the same way.
- Rule of thumb: if "wait and retry" could ever make it succeed, it's `409`; if the request is just wrong,
  it's `422`.

**`503` and fail-closed.** When an operation depends on an external attribute source and that source is
unreachable, **reject the operation** rather than proceed without the attributes. The catalog write path
throws `503` if the tag dictionary can't be fetched — it will *not* store a resource untagged, because an
untagged resource could later read as more-permissive than intended. Failing closed on a dependency outage
is the same invariant the whole library is built on; an API endpoint must not be the place it leaks.

**A status alone is not enough — pair it with a typed `errorCode`.** Two `422`s (a bad tag value vs the
role-subset rule) or several `409`s (a duplicate target vs an immutable role) are different problems a
client handles differently. The status answers "which bucket"; the `errorCode` (see
[§6](#6-error-handling)) answers "which failure within the bucket" — give a distinct code to each
client-actionable failure the handlers discriminate, not one code per status.

---

## 4. Request and response bodies

- **Content type** is `application/json` for every request and response body — **except error responses**,
  which are `application/problem+json` (see [§6](#6-error-handling)).
- **Requests** are always a typed DTO (`CatalogRequest`, `AddMemberRequest`, …) — never loose maps or
  primitives in the body. Validation constraints are on the DTO, from the spec.
- **A single resource** is returned as the bare resource DTO (`Catalog`, `Team`, `Membership`).
- **A collection** is returned as a bare JSON array of the resource DTO (`Catalog[]`, `[Membership]`).
  There is no list envelope today — see [pagination](#7-pagination) and [targets](#9-targets).
- **Creation** returns `201` with the **full created resource** in the body, including its
  server-assigned `id`, so the client never needs a follow-up `GET`. Every `201` also carries a
  **`Location` header** pointing at the new resource's canonical URL (`Location: /api/v1/<collection>/<id>`,
  keyed by the resource's addressable identifier — built via `ServletUriComponentsBuilder`). *(Adopted in
  Phase 5.9.)*

### Readonly and server-owned fields

Fields the server owns (`id`, `createdAt`, and parent back-references like `catalogId`/`categoryId` on the
child DTO) are marked `readOnly` in the spec: they appear in responses, are ignored in requests. A client
never sets an id; the server mints it.

---

## 5. Authorization on an endpoint

Authorization is the point of this whole repo, so an endpoint's auth is a first-class part of its design.
There are three mechanisms; pick by *what the decision needs to see*. The full model is in
[[ABAC-AUTHORIZATION]]; this is the endpoint-author's view.

### a) Type-level pre-authorization — `@OpaPreAuthorize`

The default. A method annotation runs **before** the controller body, deciding from the subject + a
coarse `action` + `resourceType` (and optionally a `resourceId` from the path):

```java
@OpaPreAuthorize(action = "category:read", resourceType = "'category'")
public ResponseEntity<List<Category>> listCategories(UUID catalogId, UUID parentId) { ... }

@OpaPreAuthorize(action = "team:manage", resourceType = "'team'", resourceId = "#teamId")
public ResponseEntity<Membership> addMember(UUID teamId, AddMemberRequest request) { ... }
```

- `resourceType` is a SpEL **string literal** (`"'team'"`) → the OPA policy document for that type.
- `resourceId` is a SpEL expression over the method args (`"#teamId"`) when the decision is about a
  specific instance's *identity* (e.g. resolve the caller's role on that team).
- This gate sees the subject and the type/id — **not** the resource's stored attributes. It answers "may
  this subject act on this *kind* of thing here", not "...given this row's tags".
- On deny it raises `AccessDeniedException` → `403`. On any OPA error it **fails closed** → deny.

Use it for: every write, every type-level read, and any decision that resolves purely from the subject's
role on a parent (the whole user-service management surface is this pattern, keyed on `#teamId`).

### b) Load-then-check — when the resource's own attributes decide

When the decision depends on the **stored resource's tags** (per-instance, attribute-based), the
annotation can't help — it runs before the row is loaded. Load first, then authorize the instance so its
tags reach OPA:

```java
public ResponseEntity<Category> getCategory(UUID catalogId, UUID categoryId) {
    var entity = requireCategory(catalogId, categoryId);   // load (404 if missing / wrong parent)
    categoryAuthorizer.require("read", entity);            // the entity's TAGS drive the decision
    return ResponseEntity.ok(CatalogMapper.toDto(entity));
}
```

Use it for: reads/writes whose grant is **tag-based** (a clearance against the row's tags). Note the
ordering — *load, then check* — and that a missing row is a `404` before authz even runs, so existence
isn't leaked through a `403`/`404` difference for a row the caller could never see anyway.

> **Consistency note (flagged in the review):** today only `getCategory` uses load-then-check;
> `getCatalog`/`getProduct` use annotation-only `@OpaPreAuthorize(..., resourceId=...)`. That asymmetry is
> intentional for the demo (only categories carry dictionary tags), but an endpoint author adding a
> tag-bearing resource should reach for load-then-check. See [[REST-API-DESIGN-REVIEW]].

### c) Partial-evaluation list filter — which rows a collection returns

A list endpoint must return only the rows the subject may see. The type-level `@OpaPreAuthorize` is the
coarse "may read this type at all" gate; the **row cut** happens in SQL via an OPA partial-evaluation
residual AND-ed with the path scope:

```java
@OpaPreAuthorize(action = "category:read", resourceType = "'category'")   // coarse gate (layer 2)
public ResponseEntity<List<Category>> listCategories(UUID catalogId, UUID parentId) {
    requireCatalog(catalogId);
    var entities = categoryListAuthorizer.readable(catalogId, parentId);  // residual ∧ scope (layer 3)
    ...
}
```

The load-bearing property: a subject with **no role definition** gets an **empty list**, never the full
table — the residual is `DENY_ALL` when there's nothing to widen from. A list endpoint that returned
unfiltered rows on a missing/empty decision would be the worst fail-open in the system. See
[[PARTIAL-EVALUATION-FILTERING]].

### The fail-closed rule for any endpoint

Whatever mechanism: **every error, missing-input, or unresolved-subject path must end in deny / empty /
`404`, never in wider access.** This is the one bug class the library exists to prevent; an endpoint is
where it would most easily slip in.

---

## 6. Error handling

### The error envelope — RFC-7807 `application/problem+json`

Errors are returned as a canonical [RFC-7807](https://www.rfc-editor.org/rfc/rfc7807)
`application/problem+json` object via a `@RestControllerAdvice` per service. Both specs declare it as
`ProblemDetail` (adopted in **Phase 5.9**, ADR [[0011-error-contract-problem-json|0011]]):

```json
{
  "type":      "/problems/tag-value-illegal",
  "title":     "Tag value not permitted by the dictionary",
  "status":    422,
  "detail":    "Unknown tag key: reglon",
  "instance":  "/api/v1/catalogs/7b/categories",
  "errorCode": "TAG_VALUE_ILLEGAL",
  "timestamp": "2026-06-09T10:15:30Z"
}
```

| Member | Type | Meaning |
|---|---|---|
| `type` | string | a stable, **relative**, opaque identifier for the problem kind (`/problems/<kebab>`). **Not dereferenced** — no hosted registry; it is a stable id, not a live docs URL. |
| `title` | string | a short, **status-stable** summary of the problem *kind* (one per `errorCode`). |
| `status` | int | the HTTP status code, repeated in the body. |
| `detail` | string | the human, instance-specific explanation (RFC-7807's own field — replaces the old `message`). |
| `instance` | string | the request path that produced the error (correlation). |
| `errorCode` | string (typed enum in the spec) | the **machine-stable** code a consumer branches on — see below. |
| `timestamp` | date-time (ISO-8601) | when the error was produced (an extension member, kept for correlation). |

Content type is **`application/problem+json`** on every error response. There is **no legacy `message`
field** — the body is canonical RFC-7807 plus the two documented extensions (`errorCode`, `timestamp`).

### The `errorCode` vocabulary — library-owned and typed

A consumer branches on `errorCode`, not on the human `detail`. The vocabulary is **typed and extensible**:

- the library (`opa-abac-spring-security`) ships an **`ApiErrorCode` interface** (`code()` / `status()` /
  `problemType()` / `title()`) and a **`LibraryErrorCode`** enum for the failures it raises / the generic
  ones — `ACCESS_DENIED` (403), `DEPENDENCY_UNAVAILABLE` (503), `VALIDATION_FAILED` (400),
  `RESOURCE_NOT_FOUND` (404), `STATE_CONFLICT` (409), `TAG_VALUE_ILLEGAL` / `ROLE_SUBSET_VIOLATION` (422);
- **each service ships its own enum** implementing the same interface for its domain failures (the
  user-service splits the 409 conflict group into `TEAM_TARGET_EXISTS`, `MEMBERSHIP_CONFLICT`,
  `ROLE_CODE_CONFLICT`, `ROLE_IMMUTABLE`, `TAG_KEY_CONFLICT`, `TAG_DEFINITION_IMMUTABLE`, plus
  `TAG_DEFINITION_INVALID` at 422; the catalog reuses library codes only);
- granularity is **semantic** — one code per *distinct, client-actionable* failure the handlers
  discriminate, **not** one per HTTP status (`TAG_VALUE_ILLEGAL` ≠ `ROLE_SUBSET_VIOLATION`, both 422);
- `errorCode` is a **typed `enum` member of each spec's `ProblemDetail` schema** (the union of codes that
  service emits), so the generated client is typed and the vocabulary self-documents. (This is why the
  library ships its own small `ProblemDetail` DTO rather than Spring's `org.springframework.http.ProblemDetail`,
  whose untyped `properties` map would carry `errorCode` untyped.)

### How the advice maps exceptions

Each service has an `ApiExceptionHandler` extending the library's `AbstractProblemAdvice`; each
`@ExceptionHandler` resolves its exception to an `ApiErrorCode` and builds the `ProblemDetail` body at
`application/problem+json` (the base also maps Spring Security's `AccessDeniedException` →
`ACCESS_DENIED` 403, so a denied `@OpaPreAuthorize` call also lands as a problem body). The status mapping
**is** the `400/403/404/409/422/503` policy from [§3](#3-http-methods-and-status-codes) — keep it there,
not scattered through controllers. Representative mappings:

| Exception (example) | Status | `errorCode` |
|---|---|---|
| Bean-Validation (`MethodArgumentNotValidException`), `IllegalArgumentException` | `400` | `VALIDATION_FAILED` |
| `NotFoundException` (and per-domain `*NotFoundException`) | `404` | `RESOURCE_NOT_FOUND` |
| a uniqueness / immutability / lifecycle conflict (`*ConflictException`, `*ImmutableException`) | `409` | `STATE_CONFLICT` or a service refinement |
| a domain-rule violation (`IllegalTagAssignmentException` / `SubsetRuleViolationException` / `InvalidTagDefinitionException`) | `422` | `TAG_VALUE_ILLEGAL` / `ROLE_SUBSET_VIOLATION` / `TAG_DEFINITION_INVALID` |
| a dependency-unreachable, fail-closed condition (`TagDefinitionFetchException`) | `503` | `DEPENDENCY_UNAVAILABLE` |
| an OPA-deny / unauthenticated / unresolved subject | `403` | `ACCESS_DENIED` |

A new error condition gets a **typed exception** + a handler entry mapped to an `ApiErrorCode`, not an
ad-hoc `ResponseEntity` in the controller. The controller throws; the advice formats.

### Never leak internals

The `detail` is for a developer/operator, but the body must never carry a stack trace, an SQL string,
an internal hostname, or a token. A generic `500` for an unexpected exception is correct; a detailed one
is a disclosure bug.

---

## 7. Pagination

**Today there is none.** Every list endpoint returns a bare, unbounded array. For the demo's data volumes
this is fine and keeps the example focused.

This is a **documented limitation**, not a pattern to copy into a real service — an unbounded list is a
latency and memory risk at scale, and it interacts with the partial-eval filter (the filter decides
*which* rows, pagination would decide *how many per page*). When pagination is adopted it becomes a
[target](#9-targets) below with a concrete envelope; until then, **don't** add ad-hoc `limit`/`offset` to
one endpoint — adopt the shared envelope once, everywhere.

---

## 8. The internal API surface

The user-management-service exposes a second, **in-network-only** surface under `/internal/**`, distinct
from the public `/api/v1/**`:

| Endpoint | Purpose |
|---|---|
| `GET /internal/effective-role` | the catalog service's ABAC supplier resolves a caller's effective role on a resource |
| `GET /internal/tag-definitions` | the catalog service fetches the tag dictionary applicable to a resource |
| `POST /internal/bootstrap/*` | idempotent test/demo seeding with runtime-known ids (subjects, resource ids) |

Rules for this surface:
- **Path-prefixed and `permitAll()`** in the service's `SecurityConfig` — these are *not* `@OpaPreAuthorize`-gated.
- **Never gateway-fronted.** APISIX routes only `/api/v1/**`; `/internal/**` is reachable only inside the
  compose/cluster network. Its security is **network isolation**, by design and by deployment, not a token
  check. This must be stated wherever the endpoints are defined, because an internal endpoint accidentally
  exposed through the gateway would be an unauthenticated hole.
- **Returns core types / plain shapes**, and uses `204 No Content` for a "no result" (e.g. no membership →
  no effective role) so the caller treats it as "empty", not an error.

Keep public and internal strictly separated: a public endpoint is authenticated-at-the-gateway +
ABAC-gated; an internal one is network-isolated. Never blur the two onto one path.

---

## 9. Targets

Conventions the portal-grade bar includes that **these services have not adopted yet**. Each is listed so
the guide is honest about the gap and the review has something concrete to point at. Adopting any of these
is a deliberate slice, not a drive-by.

| ◓ Target | What it would add | Why it's deferred / what it buys |
|---|---|---|
| **Pagination envelope** | a shared `{count, page, perPage, items}` wrapper + `page`/`perPage` params on every list | Bounds unbounded lists; must be adopted *once, everywhere* and composed with the partial-eval filter, so it's a slice not a patch. **Next up — Phase 5.95** (see [[POC-ROADMAP]]). |
| **ABAC `actions` / `pageActions` metadata** | each resource response carries `actions: [{action, allowed, reason}]`; each list carries `pageActions` | Lets a UI render "can I click this button" without a second round-trip. This is **Phase 6 — action enrichment** (already on the roadmap, see [[POC-ROADMAP]]); it's a target here only to point at it. |
| **`Retry-After` on `503`** | a hint for when to retry a fail-closed dependency outage | Small polish on the `503` path once a real backoff story exists. |

> **Adopted in Phase 5.9** (and moved into the body above): the **RFC-7807 `application/problem+json`
> error envelope + a typed, library-owned `errorCode`** (now [§6](#6-error-handling), ADR
> [[0011-error-contract-problem-json|0011]]) and the **`Location` header on every `201`** (now
> [§4](#4-request-and-response-bodies)).
>
> When a target is adopted, move its row out of this section and into the body as a normal rule, the same
> way the portal guide retired its own "Missing" notes as features landed.

---

## 10. Checklist

### Any new endpoint
- [ ] Path/operation/schema added to the **OpenAPI spec first**; build regenerates the interface.
- [ ] URL uses **plural nouns**, version under `/api/v1`, custom ops as **noun sub-resources**.
- [ ] Owned nesting **≤ 3 levels**; child-under-parent lineage **verified** (→ `404` on mismatch).
- [ ] Correct **method** + **status code** per [§3](#3-http-methods-and-status-codes); `400/422/409/503`
      decided by the rules, not by feel.
- [ ] Request is a **typed DTO** with validation constraints in the spec.
- [ ] **Authorization chosen deliberately**: type-level `@OpaPreAuthorize`, load-then-check (tag-based),
      or the partial-eval list filter — and **every error path fails closed**.
- [ ] Errors go through the **`ApiExceptionHandler`** (extends `AbstractProblemAdvice`; typed exception →
      status + a typed `errorCode` in an RFC-7807 `ProblemDetail` at `application/problem+json`), no
      internals leaked.

### A `POST` that creates
- [ ] Returns `201` with the **full created resource** (incl. server-assigned `id`).
- [ ] **`Location` header** to the new resource (`/api/v1/<collection>/<id>`).

### A list endpoint
- [ ] Coarse `@OpaPreAuthorize` gate **+** a row-level cut (partial-eval residual ∧ scope) — empty list,
      never the full table, for a subject with no grant.
- [ ] *(target)* paginated via the shared envelope rather than a bare array.

### An internal endpoint
- [ ] Under `/internal/**`, `permitAll()`, **never** gateway-fronted; its isolation documented at the
      definition site.

---

## References

- [[ABAC-AUTHORIZATION]] — the single-decision authorization spine (`@OpaPreAuthorize`, the OPA call).
- [[PARTIAL-EVALUATION-FILTERING]] — the list-filtering residual that backs collection reads.
- [[TAG-BASED-AUTHORIZATION]] — the dictionary + the per-instance tag decision behind load-then-check.
- [[HIERARCHICAL-AUTHORIZATION]] — why nesting is scoping, and how inheritance widens a decision.
- [[TWO-LAYER-AUTHORIZATION]] — gateway (coarse) ↔ app (fine), and why the app never trusts the gateway.
- [[REST-API-DESIGN-REVIEW]] — the current API assessed against this guide.
- [[POC-ROADMAP]] — where action-enrichment (the `actions` metadata target) sits.
- [RFC 7807 — Problem Details for HTTP APIs](https://www.rfc-editor.org/rfc/rfc7807) ·
  [RFC 9110 — HTTP Semantics](https://www.rfc-editor.org/rfc/rfc9110) ·
  [Google AIP-121 Resource-oriented design](https://google.aip.dev/121) ·
  [AIP-136 Custom methods](https://google.aip.dev/136)
