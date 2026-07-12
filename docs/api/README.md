---
tags:
  - status/active
  - type/index
  - area/api
  - audience/developer
---

# API reference — the two example services

The hand-written REST reference for the two demo services the starter secures: the
**catalog-management-service** (product catalogs) and the **user-management-service** (the ABAC
attribute source — teams, roles, memberships, and the dynamic tag dictionary). The **OpenAPI specs**
(`example-*/src/main/resources/openapi/*.yaml`) and each service's **Swagger UI** at
[`/swagger-ui.html`](http://localhost:8080/swagger-ui.html) remain the source of truth; these pages
add the human narrative — what each resource is *for*, how the endpoints compose, and the
cross-cutting conventions every response obeys. Both services expose their surface under the
`/api/v1` base path.

## Per-resource pages

Each resource has its own page as a sibling of this index.

### Catalog management service

| Page | Resource | What it covers |
|------|----------|----------------|
| [`catalog-api.md`](catalog-api.md) | Catalog | Top-level catalogs — the ABAC hierarchy root and the team-target a catalog's governing team owns. List / get / create / update / delete. |
| [`category-api.md`](category-api.md) | Category | The self-referencing category tree inside a catalog (`parentId` for a child, null for a root). The residual-filtered list demo. |
| [`product-api.md`](product-api.md) | Product | Products within a category — name, `sku`, `priceCents`/`currency`, dictionary-validated tags. Taggable on create (ADR [[0025-taggable-products|0025]]). |

### User management service

| Page | Resource | What it covers |
|------|----------|----------------|
| [`user-api.md`](user-api.md) | User | User profiles (linked to the IdP `subject`) plus the bearer-only identity-directory search (`/users/search`) over the `UserDirectory` port. |
| [`team-api.md`](team-api.md) | Team | Teams — the durable owner of a resource via its team-target. Owner-on-create and owner-only ownership transfer. |
| [`membership-api.md`](membership-api.md) | Membership | Team membership — the grant binding a user to a role on a team. Add / change-role / remove, under the assignment gates. |
| [`role-definition-api.md`](role-definition-api.md) | RoleDefinition | The immutable system role ladder plus team-scoped custom roles (categories, deny-overrides, tag requirements). |
| [`tag-definition-api.md`](tag-definition-api.md) | TagDefinition | The dynamic tag dictionary — global system keys plus team-scoped custom keys that constrain what tags resources may carry. |

## Cross-cutting conventions

These hold across **every** endpoint of both services. A per-resource page assumes them and only calls
out where a resource deviates.

### Authentication

Every request reaches a service **through the APISIX gateway**. The gateway terminates OIDC and the
caller presents a bearer token:

```http
Authorization: Bearer <JWT>
```

The application extracts the subject (`sub`) from the validated token, resolves the caller's effective
**team role** for the target resource, and asks OPA for the authorization decision. **There is no
anonymous access** — a request with no bearer token never reaches an authorized handler. Finding a
subject (e.g. via `/users/search`) grants nothing on its own; the authorization boundary for *acting*
on a resource is always the team-membership gate.

> A handful of bootstrap mutations are deliberately un-gated for the demo (e.g. `POST /teams`
> owner-on-create, which has no governing team until it creates one). Those are documented on their
> resource pages; they are the exception, not the rule.

### Error contract (RFC-7807 `problem+json`)

Every error response is `application/problem+json` — a canonical [RFC-7807](https://www.rfc-editor.org/rfc/rfc7807)
problem object with a machine-stable **`errorCode`** a consumer branches on (never the human `detail`).
See ADR [[0011-error-contract-problem-json|0011]] for why the vocabulary is typed and library-owned.

The `ProblemDetail` shape (five standard members plus two documented extensions, `errorCode` and
`timestamp`):

```jsonc
{
  "type":      "string",  // stable, relative, opaque problem-kind id, e.g. /problems/tag-value-illegal (not dereferenced)
  "title":     "string",  // short, status-stable summary of the problem kind
  "status":    422,       // the HTTP status
  "detail":    "string",  // human, instance-specific explanation
  "instance":  "string",  // the request path that produced the error
  "errorCode": "TAG_VALUE_ILLEGAL", // the machine-stable code the consumer branches on
  "timestamp": "2026-06-09T10:15:30Z" // when the error was produced (correlation)
}
```

`status` and `errorCode` are always present; the other members are best-effort. A real body — an
attempt to write an illegal tag value on the catalog service:

```json
{
  "type": "/problems/tag-value-illegal",
  "title": "Tag value illegal",
  "status": 422,
  "detail": "Value 'ultra' is not permitted for key 'tier'.",
  "instance": "/api/v1/catalogs/1f0c3c2a-8b7e-4d21-9a10-6e5b4c3d2a11",
  "errorCode": "TAG_VALUE_ILLEGAL",
  "timestamp": "2026-06-09T10:15:30Z"
}
```

`errorCode` is a **typed enum** in each spec — the union of the codes that service can emit — so the
generated client stays typed. The two services have overlapping-but-distinct vocabularies:

- **Catalog service:** `ACCESS_DENIED`, `RESOURCE_NOT_FOUND`, `VALIDATION_FAILED`, `TAG_VALUE_ILLEGAL`,
  `DEPENDENCY_UNAVAILABLE`, `STATE_CONFLICT`.
- **User-management service:** `ACCESS_DENIED`, `RESOURCE_NOT_FOUND`, `VALIDATION_FAILED`,
  `ROLE_SUBSET_VIOLATION`, `TEAM_TARGET_EXISTS`, `MEMBERSHIP_CONFLICT`, `ROLE_CODE_CONFLICT`,
  `ROLE_IMMUTABLE`, `TAG_KEY_CONFLICT`, `TAG_DEFINITION_IMMUTABLE`, `TAG_DEFINITION_INVALID`,
  `ROLE_DEFINITION_INVALID`, `STATE_CONFLICT`.

Codes are **semantic**, not per-status: two failures that share a `422` (e.g. a role authoring-contract
violation, `ROLE_DEFINITION_INVALID`, vs. the membership subset rule, `ROLE_SUBSET_VIOLATION`) carry
distinct codes precisely so a client can tell them apart.

### Pagination envelope

Every public list returns the shared envelope `{count, page, perPage, items}` (ADR
[[0012-pagination-envelope|0012]]):

```json
{
  "count": 137,
  "page": 0,
  "perPage": 20,
  "items": [ /* … */ ]
}
```

- **`count` is subject-relative.** It is the number of rows **the calling subject is authorized to
  see, across all pages** — *never* `items.length`. Two callers paging the same URL legitimately see
  different `count`s. This is the teachable headline: the count is the count of rows *you* may see.
- **`page` / `perPage` echo the request** verbatim. `page` is **0-based** (default `0`); `perPage` is
  `1`–`100` (default `20`). A bounds violation is a **`400 VALIDATION_FAILED`** — there is **no
  clamping** (silently returning 100 rows when 500 were asked for would change meaning without telling
  the client).
- **A page past the end is `200` with empty `items` and the exact `count`** — never `404`; under ABAC
  the last page is subject-relative.
- **Order is a fixed total order: `createdAt ASC, id ASC`.** Clients do not choose the sort. The `id`
  tiebreaker makes the order total so rows never repeat or vanish across pages.

Two list shapes deliberately opt out of the envelope, documented on their pages:
`GET /users/search` returns a bounded plain `DirectoryUserList` (`{items, limit}` — no count, no
pages, since the directory has no cheap server-side cursor).

### Action enrichment (`_actions`)

List and single-resource responses for enriched types carry a server-emitted **`_actions`** map — the
affordances the caller may perform on *that* resource — so a UI renders exactly the buttons the user
can use (ADR [[0016-action-enrichment-affordance-metadata|0016]]):

```json
{ "view": true, "update": false, "delete": false, "assign-tags": true }
```

- Keys are **bare fine-action verbs**; the resource type is implicit from the object the map sits on.
- The map is **`readOnly`** — server-emitted, **never accepted on input** (sending it is ignored).
- **Present ⇒ a complete, honest per-verb verdict** — every registered verb appears with a `true`/`false`
  value (honestly reporting `delete:false`, which a role-permissions-only source could not).
- **Absent ⇒ enrichment could not be computed** (a failure or a cache miss). The advice **omits the
  map rather than fabricating an all-false one** — an all-false map would be a positive "you can do
  nothing" assertion that lies when the truth is "we could not check". The client falls back to its own
  default affordance.
- **Affordance is not enforcement.** `_actions` is advisory read-side convenience; the real
  `@OpaPreAuthorize` gate decides every request independently. Enriched types differ by design — a
  catalog/category/product enumerates domain CRUD + `assign-tags`; a team enumerates only the
  OPA-decidable control-plane subset (`list-members`, `add-member`, `remove-member`), because the
  Java-co-gated escalation verbs would over-promise. A `Team` returned by the un-gated `getTeam`
  bootstrap read omits `_actions` — the documented, correct degrade.

### Status codes

The set both services draw from, and what each means here:

| Status | Meaning in these services |
|--------|---------------------------|
| **200 OK** | The read or update succeeded; the body is the resource (or the page envelope). |
| **201 Created** | A create succeeded; the body is the new resource. |
| **204 No Content** | A delete or a state-only mutation (e.g. `transfer-ownership`) succeeded; no body. |
| **400 Bad Request** | Malformed or out-of-bounds request — `VALIDATION_FAILED`. Includes pagination bounds and the all-or-nothing filter-pair rules. |
| **403 Forbidden** | The ABAC decision denied the action — `ACCESS_DENIED`. |
| **404 Not Found** | The addressed resource does not exist (or is not visible) — `RESOURCE_NOT_FOUND`. |
| **409 Conflict** | A uniqueness/state conflict (user-mgmt only): e.g. `TEAM_TARGET_EXISTS`, `MEMBERSHIP_CONFLICT`, `ROLE_CODE_CONFLICT`, `TAG_KEY_CONFLICT`, or the `*_IMMUTABLE` edits. |
| **422 Unprocessable Entity** | The request is well-formed but violates a domain rule: an illegal tag value (`TAG_VALUE_ILLEGAL`), the role subset rule (`ROLE_SUBSET_VIOLATION`), a role/tag authoring-contract violation (`ROLE_DEFINITION_INVALID` / `TAG_DEFINITION_INVALID`). |
| **503 Service Unavailable** | A required dependency (e.g. the tag dictionary) was unavailable — `DEPENDENCY_UNAVAILABLE`. The request is **rejected, fail-closed** (catalog service). |

Not every endpoint can return every code; each per-resource page lists the codes that endpoint
actually emits, and the specs pin the exact set.

## Source of truth

These pages are the **narrative layer**. The authoritative, machine-checked contract is:

- the OpenAPI specs — `example-catalog-management-service/src/main/resources/openapi/catalog-api.yaml`
  and `example-user-management-service/src/main/resources/openapi/user-mgmt-api.yaml` (the codegen
  source; drift is a build break), and
- each running service's **Swagger UI** at `/swagger-ui.html`.

When a page and the spec disagree, the spec wins — please open an issue (or fix the page).

## Related

The guides in [`../guides/`](../guides/) explain the *mechanisms* behind these conventions:

- [[REST-API-DESIGN]] — the API design conventions both services follow (spec-first, status codes,
  the list envelope).
- [[ABAC-AUTHORIZATION]] — how a bearer token becomes an OPA decision (the auth story above).
- [[PERMISSION-MODEL]] — coarse permission categories, deny-overrides, and safe delegation (behind
  `RoleDefinition`).
- [[TEAM-BASED-AUTHORIZATION]] — resolving a caller's effective role from live team membership.
- [[TAG-BASED-AUTHORIZATION]] — the dynamic tag dictionary and tag-based grants (behind
  `TagDefinition` and the `tags` fields).
- [[ACTION-ENRICHMENT]] — the `_actions` affordance mechanism in depth.
- [[USER-DIRECTORY]] — the `UserDirectory` port behind `/users/search`.
- [[HIERARCHICAL-AUTHORIZATION]] — the Catalog → Category → Product hierarchy the ABAC model walks.

Architecture and the dated decision records live in [`../architecture/`](../architecture/) (ADRs in
[`../architecture/adr/`](../architecture/adr/)).
