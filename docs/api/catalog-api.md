---
tags:
  - status/active
  - type/reference
  - area/api
  - audience/developer
---

# Catalog API

REST reference for the **Catalog** resource of the catalog-management-service — the top-level
node of the product-catalog hierarchy (Catalog → Category tree → Product).

> **Source of truth.** This page is hand-written from the OpenAPI spec
> [`example-catalog-management-service/src/main/resources/openapi/catalog-api.yaml`](../../example-catalog-management-service/src/main/resources/openapi/catalog-api.yaml).
> The spec is the codegen source and always wins on a discrepancy; browse it live at
> **`/swagger-ui.html`** on any running service. See the [`docs/api/` conventions](README.md)
> for how these reference pages are organized.

---

## Overview

A **catalog** is the root container of the product hierarchy. Creating one makes you its
**owner** (owner-on-create, below); owning the catalog's governing team is what lets you invite
other members, manage roles, and — because tags are gated through that team — assign tags to the
catalog on a later update.

Access is attribute-based (ABAC). The gateway validates the caller's token and forwards the
`Authorization: Bearer <jwt>`; the application re-derives the subject and asks OPA whether the
requested **action** is permitted on the **catalog** resource — so the endpoints below are all
subject-relative. A caller who may not perform an action gets a `403` with a typed `errorCode`
(see [Errors](#errors)); a list returns only the rows the caller is authorized to see, with a
subject-relative `count`.

Personas used in the examples — **owner** (created the catalog, governs its team), **editor**
(may mutate), **viewer** (read-only member), **outsider** (no membership) — are illustrative
roles, not part of the wire contract.

**Base Path**: `/api/v1/catalogs`

**Media types**: requests and success bodies are `application/json`; every error is
`application/problem+json` (an RFC-7807 [`ProblemDetail`](#problemdetail)).

| Method | Path | Operation | Summary |
|--------|------|-----------|---------|
| `GET` | `/api/v1/catalogs` | `listCatalogs` | List catalogs (paginated, subject-relative) |
| `POST` | `/api/v1/catalogs` | `createCatalog` | Create a catalog (owner-on-create) |
| `GET` | `/api/v1/catalogs/{catalogId}` | `getCatalog` | Get one catalog |
| `PUT` | `/api/v1/catalogs/{catalogId}` | `updateCatalog` | Replace a catalog (tags accepted here) |
| `DELETE` | `/api/v1/catalogs/{catalogId}` | `deleteCatalog` | Delete a catalog |

---

## Endpoints

### List catalogs

Return a page of catalogs. The page is **subject-relative**: `items` holds only the catalogs the
caller is authorized to see on this page, and `count` is the total number of catalogs the caller
may see across **all** pages (never `items.length`). Rows are ordered `createdAt ASC, id ASC` — a
fixed total order the client does not choose.

```http
GET /api/v1/catalogs?page=0&perPage=20
```

**Query parameters**:

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `page` | integer | No | `0` | 0-based page index. A bounds violation is `400 VALIDATION_FAILED` — no clamping. |
| `perPage` | integer | No | `20` | Page size, 1–100. A bounds violation is `400 VALIDATION_FAILED` — no clamping. |

**Response** (`200 OK`, `CatalogPage`):

```json
{
  "count": 2,
  "page": 0,
  "perPage": 20,
  "items": [
    {
      "id": "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
      "name": "Demo catalog",
      "description": "Public demo storefront",
      "createdAt": "2026-02-11T09:14:22Z",
      "tags": {
        "region": ["emea", "apac"]
      },
      "_actions": {
        "view": true,
        "update": true,
        "delete": true,
        "assign-tags": true
      }
    },
    {
      "id": "8a1b3c4d-5e6f-4071-8123-9f0a1b2c3d4e",
      "name": "Seasonal catalog",
      "description": "Time-boxed promotions",
      "createdAt": "2026-03-02T17:41:05Z",
      "tags": {
        "region": "emea"
      },
      "_actions": {
        "view": true,
        "update": false,
        "delete": false,
        "assign-tags": false
      }
    }
  ]
}
```

- `count = 2` means the caller may see exactly two catalogs in total — a viewer would still get
  its own `count`, an outsider `count: 0` with an empty `items`.
- The `_actions` map is **server-emitted** and per-row: here the caller may view both catalogs but
  only mutate the first. See [The `_actions` affordance map](#the-_actions-affordance-map).

**Authorization**: any authenticated caller may call the endpoint; the result set is filtered to
what the caller may see. An unauthenticated or route-rejected caller gets `403 ACCESS_DENIED`.

**cURL**:

```bash
curl -s "http://localhost:8080/api/v1/catalogs?page=0&perPage=20" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/json"
```

---

### Create catalog

Create a catalog. The caller becomes its **owner** (see [owner-on-create](#owner-on-create)).

```http
POST /api/v1/catalogs
```

**Request body** (`CatalogRequest`):

| Field | Type | Required | Constraints |
|-------|------|----------|-------------|
| `name` | string | Yes | 1–200 chars |
| `description` | string | No | ≤ 2000 chars |
| `tags` | object | No | **Rejected on create — see below.** |

```json
{
  "name": "Demo catalog",
  "description": "Public demo storefront"
}
```

> **Tags are not accepted on create — a create carrying `tags` is rejected `422`.**
> The type-level *assign-tags* decision resolves **through the governing team**, and a brand-new
> catalog has no team until the owner-on-create step binds one. There is no team to authorize the
> tag assignment against at create time, so the request fails closed with
> `422 TAG_VALUE_ILLEGAL`. Create the catalog first, then attach tags with
> [`updateCatalog`](#update-catalog) — where the governing team now exists.
>
> (This differs from products: a new product already has a governing team — its catalog's — so a
> product *can* carry tags on create. That asymmetry is specific to the root of the hierarchy.)

**Response** (`201 Created`, `Catalog`):

```json
{
  "id": "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
  "name": "Demo catalog",
  "description": "Public demo storefront",
  "createdAt": "2026-02-11T09:14:22Z",
  "_actions": {
    "view": true,
    "update": true,
    "delete": true,
    "assign-tags": true
  }
}
```

The freshly created catalog has no `tags` yet. Because the caller is now the owner, its
`_actions` report full control, including `assign-tags: true` — the affordance that makes the
follow-up tag update succeed.

**Authorization**: any authenticated caller may create a catalog; on success they are bound as its
owner. Denied callers get `403 ACCESS_DENIED`.

**cURL**:

```bash
curl -s -X POST "http://localhost:8080/api/v1/catalogs" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{ "name": "Demo catalog", "description": "Public demo storefront" }'
```

---

### Get catalog

Fetch a single catalog by id.

```http
GET /api/v1/catalogs/{catalogId}
```

**Path parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `catalogId` | string (uuid) | Yes | The catalog id. |

**Response** (`200 OK`, `Catalog`):

```json
{
  "id": "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
  "name": "Demo catalog",
  "description": "Public demo storefront",
  "createdAt": "2026-02-11T09:14:22Z",
  "tags": {
    "region": ["emea", "apac"]
  },
  "_actions": {
    "view": true,
    "update": true,
    "delete": false,
    "assign-tags": true
  }
}
```

**Authorization**: requires a `view` verdict on this catalog. A caller who may not see it gets
`403 ACCESS_DENIED`; a caller who could otherwise see nothing at this id gets `404 RESOURCE_NOT_FOUND`.

**cURL**:

```bash
curl -s "http://localhost:8080/api/v1/catalogs/3f2504e0-4f89-41d3-9a0c-0305e82c3301" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/json"
```

---

### Update catalog

Replace a catalog's mutable fields. **This is the only place `tags` are accepted** — the catalog
now has a governing team, so the *assign-tags* decision has a team to resolve through.

```http
PUT /api/v1/catalogs/{catalogId}
```

**Path parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `catalogId` | string (uuid) | Yes | The catalog id. |

**Request body** (`CatalogRequest`):

| Field | Type | Required | Constraints |
|-------|------|----------|-------------|
| `name` | string | Yes | 1–200 chars |
| `description` | string | No | ≤ 2000 chars |
| `tags` | object | No | Dictionary-validated. Each value is a string (single-valued key) or an array of strings (multi-valued key), per the tag definition. An unknown key or an illegal value is rejected `422 TAG_VALUE_ILLEGAL` — nothing is silently dropped. |

```json
{
  "name": "Demo catalog",
  "description": "Public demo storefront",
  "tags": {
    "region": ["emea", "apac"]
  }
}
```

**Response** (`200 OK`, `Catalog`): the updated catalog, including the accepted `tags` and a
recomputed `_actions` map.

**Authorization**: requires an `update` verdict on the catalog; assigning `tags` additionally
requires the *assign-tags* verdict (resolved through the governing team). Denied → `403 ACCESS_DENIED`;
missing → `404 RESOURCE_NOT_FOUND`; a required dependency being unavailable fails closed with
`503 DEPENDENCY_UNAVAILABLE` (the request is rejected, not partially applied).

**cURL**:

```bash
curl -s -X PUT "http://localhost:8080/api/v1/catalogs/3f2504e0-4f89-41d3-9a0c-0305e82c3301" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
        "name": "Demo catalog",
        "description": "Public demo storefront",
        "tags": { "region": ["emea", "apac"] }
      }'
```

---

### Delete catalog

Delete a catalog.

```http
DELETE /api/v1/catalogs/{catalogId}
```

**Path parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `catalogId` | string (uuid) | Yes | The catalog id. |

**Response**: `204 No Content` — empty body on success. (The spec declares an empty
`application/json` on the 204 only so content negotiation admits a bare `Accept: application/json`;
no representation is produced.)

**Authorization**: requires a `delete` verdict on the catalog. Denied → `403 ACCESS_DENIED`;
missing → `404 RESOURCE_NOT_FOUND`.

**cURL**:

```bash
curl -s -o /dev/null -w '%{http_code}\n' \
  -X DELETE "http://localhost:8080/api/v1/catalogs/3f2504e0-4f89-41d3-9a0c-0305e82c3301" \
  -H "Authorization: Bearer $TOKEN"
```

---

## Cross-cutting behavior

### owner-on-create

Creating a catalog binds the caller as the **owner** of the catalog's governing team. Ownership is
what unlocks the control-plane actions on that team (managing members and roles) and, transitively,
the ability to **assign tags** to the catalog — because the type-level *assign-tags* decision is
resolved through the governing team. This is precisely why tags cannot ride along on the create
request: at the moment the create body is validated, the team does not yet exist. The owner is the
first member; other members (editors, viewers) are added afterward through team management.

### The `_actions` affordance map

Every `Catalog` in a response carries a **read-only, server-emitted** `_actions` map — the set of
**instance-scoped** actions the caller may perform on *that specific* catalog, each with an honest
`true`/`false` verdict:

```json
"_actions": { "view": true, "update": false, "delete": false, "assign-tags": true }
```

- Keys are **bare verbs** (the resource type is implicit from the object the map sits on).
- The map is **complete** for the type when present — every registered verb appears with a verdict.
  A verb reading `false` is meaningful (e.g. `delete: false` for a viewer), which a
  granted-permissions-only source could not express.
- `list` and `create` never appear — they are collection/type-level, not actions on a specific
  instance.
- The map is **absent entirely** only in the degrade case (enrichment could not be computed). A
  present map is never partial.

Clients should drive UI affordances off `_actions` (e.g. show a delete button only when
`delete: true`) rather than re-deriving permissions client-side.

### Pagination envelope

All list endpoints return the shared `PageEnvelope` shape:

```json
{ "count": <int64>, "page": <int>, "perPage": <int>, "items": [ /* … */ ] }
```

Under ABAC the `count` is **subject-relative** — the number of rows *the caller* is authorized to
see across all pages, not the raw table size and not `items.length`. `page` and `perPage` echo the
request. Rows are ordered `createdAt ASC, id ASC`.

### Errors

Every error is `application/problem+json` — an RFC-7807 [`ProblemDetail`](#problemdetail). Branch
on the machine-stable `errorCode`, not the human `detail`.

| Status | `errorCode` | When |
|--------|-------------|------|
| `400` | `VALIDATION_FAILED` | Malformed request — e.g. `page`/`perPage` out of bounds (no clamping). |
| `403` | `ACCESS_DENIED` | The caller may not perform this action on this resource. |
| `404` | `RESOURCE_NOT_FOUND` | No such catalog (or not visible to the caller). |
| `422` | `TAG_VALUE_ILLEGAL` | A tag key/value was rejected — including **any** `tags` on create. |
| `422` | `VALIDATION_FAILED` | A domain rule rejected an otherwise well-formed request. |
| `409` | `TAG_OPERATOR_MANAGED` | A `tags` payload would assign, re-value or strip an **operator-managed** tag (the `env` tier is written only through the operator path). |
| `409` | `STATE_CONFLICT` | The resource changed after it was authorized, or a lifecycle rule forbids the transition. |
| `503` | `DEPENDENCY_UNAVAILABLE` | A required dependency was unavailable; the request was rejected (fail-closed). |

A representative `403` body when an outsider tries to read a catalog:

```json
{
  "type": "/problems/access-denied",
  "title": "Access denied",
  "status": 403,
  "detail": "The caller may not view catalog 3f2504e0-4f89-41d3-9a0c-0305e82c3301.",
  "instance": "/api/v1/catalogs/3f2504e0-4f89-41d3-9a0c-0305e82c3301",
  "errorCode": "ACCESS_DENIED",
  "timestamp": "2026-02-11T09:15:03Z"
}
```

---

## Schema reference

Exact field lists come from the [OpenAPI spec](../../example-catalog-management-service/src/main/resources/openapi/catalog-api.yaml);
`readOnly` fields are server-emitted and never accepted on input.

### Catalog

```typescript
{
  id: string;          // uuid, readOnly (server-assigned)
  name: string;
  description?: string;
  createdAt: string;   // date-time, readOnly (server-assigned)
  tags?: {             // dictionary-validated; value is string | string[] per the tag definition
    [key: string]: string | string[];
  };
  _actions?: {         // readOnly, server-emitted affordance map; absent only on degrade
    [verb: string]: boolean;   // "view" | "update" | "delete" | "assign-tags"
  };
}
```

Required: `id`, `name`, `createdAt`.

### CatalogRequest

```typescript
{
  name: string;         // 1–200 chars
  description?: string; // ≤ 2000 chars
  tags?: {              // accepted on UPDATE only — a create carrying tags is rejected 422
    [key: string]: string | string[];
  };
}
```

Required: `name`.

### CatalogPage

```typescript
{
  count: number;    // int64 — total rows the caller may see across all pages (subject-relative)
  page: number;     // 0-based, echoes the request
  perPage: number;  // 1–100, echoes the request
  items: Catalog[];
}
```

Required: `count`, `page`, `perPage`, `items`.

### ProblemDetail

RFC-7807 problem object (`application/problem+json`) — five standard members plus two documented
extensions (`errorCode`, `timestamp`).

```typescript
{
  type?: string;      // stable, relative, opaque id for the problem kind — not dereferenced
  title?: string;     // short, status-stable summary
  status: number;     // int32 — the HTTP status code
  detail?: string;    // human, instance-specific explanation
  instance?: string;  // the request path that produced the error
  errorCode:          // the machine-stable code a consumer branches on
    | "ACCESS_DENIED"
    | "RESOURCE_NOT_FOUND"
    | "VALIDATION_FAILED"
    | "TAG_VALUE_ILLEGAL"
    | "DEPENDENCY_UNAVAILABLE"
    | "STATE_CONFLICT"
    | "TAG_OPERATOR_MANAGED"
    | "STEP_UP_REQUIRED";
  timestamp?: string; // date-time — when the error was produced (correlation)
}
```

Required: `status`, `errorCode`.

---

## Related documentation

- [Category API](category-api.md) — hierarchical categories within a catalog (self-referencing tree).
- [Product API](product-api.md) — products within a category (tags accepted on create *and* update).
- [`docs/api/` conventions](README.md) — how these reference pages are written and kept in sync.
- The OpenAPI spec — [`catalog-api.yaml`](../../example-catalog-management-service/src/main/resources/openapi/catalog-api.yaml)
  — is the codegen source of truth; Swagger UI at `/swagger-ui.html` renders it live.
