---
tags:
  - status/active
  - type/reference
  - area/api
  - audience/developer
---

# Category API

REST reference for the **Category** resource of the catalog-management-service — the middle
tier of the catalog hierarchy (`Catalog → Category → Product`).

> **Source of truth.** This page is hand-written from the OpenAPI contract at
> `example-catalog-management-service/src/main/resources/openapi/catalog-api.yaml` and is kept
> in step with it. When the two disagree, the spec (and the live **Swagger UI** at
> `/swagger-ui.html`) wins — the codegen builds handlers and DTOs from the spec, not from this
> file. Report drift as a bug.

---

## Overview

A **category** is a node in a self-referencing tree that lives inside one catalog. Every
category endpoint is nested under its catalog (`/catalogs/{catalogId}/categories/…`) — a
category never exists on its own. Within a catalog, categories form a **hierarchy**: a
category's `parentId` links it to a parent category, and `parentId: null` marks a *root*
category directly under the catalog. This tree is exactly the shape attribute-based access
control (ABAC) is built for, so authorization is **hierarchy-aware** — see
[Authorization model](#authorization-model) below.

Two contract details set categories apart from the sibling resources and are worth reading
before you call the write endpoints:

- **Tags are accepted on `POST` (create).** Unlike a [catalog](catalog-api.md) — where a create
  carrying `tags` is rejected `422`, because a brand-new catalog has no governing team yet — a
  category's *catalog already has a team*. The category therefore has a governing root the
  moment it is created, so the type-level `assign-tags` decision can be resolved and
  **tag-on-create is allowed**. (Products behave like categories here; catalogs are the
  exception.) See [Tag-on-create](#tag-on-create-categories-vs-catalogs).
- **List is subject-relative and hierarchy-aware.** `GET …/categories` returns the
  `{count, page, perPage, items}` envelope, where `count` is the number of categories *this
  caller is authorized to see* (not `items.length`), and a grant on the parent catalog can
  widen that set — see [List categories](#list-categories).

**Base Path**: `/api/v1/catalogs/{catalogId}/categories`

**Media types**: request/success bodies are `application/json`; every error is
`application/problem+json` (RFC-7807 — see [Errors](#errors)).

---

## Authorization model

The starter authorizes each category request through OPA before the handler runs, and enriches
each returned resource afterward. Two behaviors surface in the responses documented below:

- **Hierarchy-aware visibility.** A category is reachable when the caller has a grant on the
  category itself *or* on an ancestor — its catalog (or a parent category). This is why the
  `count` on a list can exceed the categories you were granted individually: a grant on the
  **parent catalog** widens the visible set to the categories beneath it. Access is decided per
  row; the list only ever contains rows the caller may see.
- **`_actions` affordance map.** Every `Category` in a response carries a server-emitted
  `_actions` object — a per-verb map of which actions *this caller* may perform on *that*
  category: `view`, `update`, `delete`, `assign-tags`. It is **advisory** (a UI hint for
  showing/hiding controls); the real gate re-decides on its own terms, so an `_actions` value
  of `false` corresponds to a genuine `403` if you call anyway. The map is **server-emitted and
  read-only** — never send it on a request; it is ignored on input. When the affordance cannot
  be computed (e.g. a dependency outage), `_actions` is **omitted** rather than emitted as an
  all-`false` map.

Personas used in the examples below are neutral: an **editor** (may create/update categories),
a **viewer** (read-only), an **owner** (full control of a catalog subtree), and an **outsider**
(no grant).

---

## Endpoints

| Method | Path | Operation | Summary |
|--------|------|-----------|---------|
| `GET` | `/api/v1/catalogs/{catalogId}/categories` | `listCategories` | List categories in a catalog (paginated) |
| `POST` | `/api/v1/catalogs/{catalogId}/categories` | `createCategory` | Create a category (tags allowed) |
| `GET` | `/api/v1/catalogs/{catalogId}/categories/{categoryId}` | `getCategory` | Get one category |
| `PUT` | `/api/v1/catalogs/{catalogId}/categories/{categoryId}` | `updateCategory` | Update a category |
| `DELETE` | `/api/v1/catalogs/{catalogId}/categories/{categoryId}` | `deleteCategory` | Delete a category |

---

### List Categories

Return a page of the categories in a catalog that the caller is authorized to see.

```http
GET /api/v1/catalogs/{catalogId}/categories
```

**Path Parameters**:
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `catalogId` | string (uuid) | Yes | The catalog the categories belong to |

**Query Parameters**:
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `parentId` | string (uuid) | No | — | When set, returns only the **direct children** of this category |
| `page` | integer | No | `0` | 0-based page index. Out of bounds is a `400 VALIDATION_FAILED` (no clamping) |
| `perPage` | integer | No | `20` | Page size, `1`–`100`. Out of bounds is a `400 VALIDATION_FAILED` (no clamping) |

**Response** (`200 OK`) — the shared page envelope (`CategoryPage`):
```json
{
  "count": 2,
  "page": 0,
  "perPage": 20,
  "items": [
    {
      "id": "0a7e3c1e-8b1f-4f6a-9d2e-1c5b7a904f11",
      "catalogId": "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
      "parentId": null,
      "name": "EMEA region",
      "description": "Products offered in Europe, the Middle East, and Africa.",
      "tags": { "region": "emea" },
      "_actions": { "view": true, "update": true, "delete": false, "assign-tags": true }
    },
    {
      "id": "1b8f4d2f-9c20-4a71-8e3f-2d6c8b015022",
      "catalogId": "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
      "parentId": null,
      "name": "APAC region",
      "description": "Products offered across Asia-Pacific.",
      "tags": { "region": "apac" },
      "_actions": { "view": true, "update": false, "delete": false, "assign-tags": false }
    }
  ]
}
```

- **`count` is subject-relative** — the total categories *this caller* may see across all pages,
  never `items.length`. Rows are ordered `createdAt ASC, id ASC` (a fixed total order; clients
  do not choose the sort).
- **Hierarchy widens the set.** Because visibility follows the tree, a caller with a grant on
  the parent **catalog** sees every category beneath it here, even ones they were not granted
  individually. Pass `parentId` to scope the listing to one subtree's direct children.

**Authorization**: the caller must be permitted to list categories under the catalog; each row
returned is one the caller may `view`.

**cURL Example**:
```bash
# All root-and-below categories the caller can see in a catalog
curl -s "http://localhost:8080/api/v1/catalogs/3f2504e0-4f89-41d3-9a0c-0305e82c3301/categories?page=0&perPage=20" \
  -H "Authorization: Bearer $TOKEN"

# Only the direct children of one category
curl -s "http://localhost:8080/api/v1/catalogs/3f2504e0-4f89-41d3-9a0c-0305e82c3301/categories?parentId=0a7e3c1e-8b1f-4f6a-9d2e-1c5b7a904f11" \
  -H "Authorization: Bearer $TOKEN"
```

---

### Create Category

Create a category under a catalog. **Tags may be assigned on create** (see
[Tag-on-create](#tag-on-create-categories-vs-catalogs)).

```http
POST /api/v1/catalogs/{catalogId}/categories
```

**Path Parameters**:
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `catalogId` | string (uuid) | Yes | The catalog to create the category in |

**Request Body** (`CategoryRequest`):
| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `name` | string | Yes | 1–200 chars | Display name |
| `parentId` | string (uuid) \| null | No | — | Parent category id; omit or `null` for a **root** category |
| `description` | string | No | ≤ 2000 chars | Free-text description |
| `tags` | object | No | dictionary-validated | Tags to assign; each value is a string (single-valued key) or an array of strings (multi-valued key). An unknown key or an illegal value is rejected `422 TAG_VALUE_ILLEGAL` — nothing is silently dropped |

A root category tagged on create — `parentId` omitted, `tags` supplied:
```json
{
  "name": "EMEA region",
  "description": "Products offered in Europe, the Middle East, and Africa.",
  "tags": { "region": "emea" }
}
```

**Response** (`201 Created`) — the created `Category`, with a `Location` header pointing at the
new resource and a server-emitted `_actions` map:
```json
{
  "id": "0a7e3c1e-8b1f-4f6a-9d2e-1c5b7a904f11",
  "catalogId": "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
  "parentId": null,
  "name": "EMEA region",
  "description": "Products offered in Europe, the Middle East, and Africa.",
  "tags": { "region": "emea" },
  "_actions": { "view": true, "update": true, "delete": true, "assign-tags": true }
}
```

**Authorization**: requires permission to create a category under the catalog; when the request
carries `tags`, the type-level `assign-tags` decision is also evaluated — through the catalog's
**governing team** (see below).

**cURL Example**:
```bash
curl -s -X POST "http://localhost:8080/api/v1/catalogs/3f2504e0-4f89-41d3-9a0c-0305e82c3301/categories" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "APAC region",
    "tags": { "region": "apac" }
  }'
```

#### Tag-on-create — categories vs. catalogs

**Categories accept `tags` on create; catalogs do not.** The difference is *who governs the
tag decision at create time*:

- A **catalog** has **no governing team** the instant it is created — the team is bound by the
  owner-on-create step *after* the row exists. There is no root through which the type-level
  `assign-tags` decision can resolve, so a catalog create carrying `tags` is rejected
  `422` and tags are assigned later on **update** only.
- A **category** is created *inside a catalog that already has a team*. The category therefore
  has a governing root from the start, the `assign-tags` decision resolves through it, and
  **tag-on-create is permitted**. (Products share this property — their catalog's team governs
  them too.)

Either way, tags are validated against the tag dictionary: an unknown key or a value the
dictionary forbids is a `422 TAG_VALUE_ILLEGAL` and the whole create is rejected — see
[the 422 example](#example-422-tag_value_illegal).

---

### Get Category

Fetch one category by id.

```http
GET /api/v1/catalogs/{catalogId}/categories/{categoryId}
```

**Path Parameters**:
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `catalogId` | string (uuid) | Yes | The catalog the category belongs to |
| `categoryId` | string (uuid) | Yes | The category to fetch |

**Response** (`200 OK`): a single `Category` object (same shape as a list `items[]` entry),
including its `_actions` affordance map.

**Authorization**: the caller must be permitted to `view` the category (directly or via an
ancestor grant). A category the caller may not see returns `404` — not `403` — so existence is
not leaked; a category that exists but is denied on other grounds returns `403`.

**cURL Example**:
```bash
curl -s "http://localhost:8080/api/v1/catalogs/3f2504e0-4f89-41d3-9a0c-0305e82c3301/categories/0a7e3c1e-8b1f-4f6a-9d2e-1c5b7a904f11" \
  -H "Authorization: Bearer $TOKEN"
```

---

### Update Category

Replace the mutable fields of a category. Tags supplied here are validated and applied as a
tags delta (dispatching the `assign-tags` decision).

```http
PUT /api/v1/catalogs/{catalogId}/categories/{categoryId}
```

**Path Parameters**:
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `catalogId` | string (uuid) | Yes | The catalog the category belongs to |
| `categoryId` | string (uuid) | Yes | The category to update |

**Request Body** (`CategoryRequest`): same schema as [create](#create-category) — `name`
(required), `parentId`, `description`, `tags`. Reparenting is expressed by changing `parentId`
(within the same catalog tree).

**Response** (`200 OK`): the updated `Category`.

**Authorization**: requires permission to `update` the category; a `tags` change additionally
requires the `assign-tags` decision to allow, resolved through the governing team.

**cURL Example**:
```bash
curl -s -X PUT "http://localhost:8080/api/v1/catalogs/3f2504e0-4f89-41d3-9a0c-0305e82c3301/categories/1b8f4d2f-9c20-4a71-8e3f-2d6c8b015022" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "APAC region",
    "description": "Products offered across Asia-Pacific (updated).",
    "tags": { "region": "apac" }
  }'
```

---

### Delete Category

Delete a category.

```http
DELETE /api/v1/catalogs/{catalogId}/categories/{categoryId}
```

**Path Parameters**:
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `catalogId` | string (uuid) | Yes | The catalog the category belongs to |
| `categoryId` | string (uuid) | Yes | The category to delete |

**Response** (`204 No Content`): empty body on success.

**Authorization**: requires permission to `delete` the category.

**cURL Example**:
```bash
curl -s -X DELETE "http://localhost:8080/api/v1/catalogs/3f2504e0-4f89-41d3-9a0c-0305e82c3301/categories/1b8f4d2f-9c20-4a71-8e3f-2d6c8b015022" \
  -H "Authorization: Bearer $TOKEN" -i
```

---

## Errors

Every error response is `application/problem+json` — a canonical [RFC-7807](https://www.rfc-editor.org/rfc/rfc7807)
`ProblemDetail` object with two extension members (`errorCode`, `timestamp`). **A client branches
on the typed `errorCode`, not on the human `detail`.**

| Status | `errorCode` | When |
|--------|-------------|------|
| `400` | `VALIDATION_FAILED` | Malformed request — a bad `page`/`perPage` bound, a malformed uuid, a body that fails schema validation |
| `401` | `STEP_UP_REQUIRED` | Step-up (RFC 9470): the caller is authorized except for authentication freshness — a supervisor reading production content before the second factor. The `WWW-Authenticate` header carries the challenge; re-authenticating at the challenged ACR clears the deny. Reads only (`list`/`get`) |
| `403` | `ACCESS_DENIED` | The caller is authenticated but not permitted for this action on this category |
| `404` | `RESOURCE_NOT_FOUND` | The catalog or category does not exist, or is not visible to the caller |
| `409` | `TAG_OPERATOR_MANAGED` | A `tags` payload would assign, re-value or strip an **operator-managed** tag (the `env` tier is written only through the operator path) |
| `422` | `TAG_VALUE_ILLEGAL` | A `tags` payload used an unknown key or a value the dictionary forbids (on create or update) |
| `503` | `DEPENDENCY_UNAVAILABLE` | A required dependency (e.g. the policy engine) was unavailable — the request is **fail-closed** (rejected, not allowed) |

Which statuses each endpoint can emit is defined in the spec: `list` → `400/401/403`; `create` →
`400/403/404/409/422/503`; `get` → `401/403/404`; `update` → `403/404/409/422/503`; `delete` →
`403/404`.

<a id="example-422-tag_value_illegal"></a>
**Example — `422 TAG_VALUE_ILLEGAL`** (a create or update whose `tags` violate the dictionary):
```json
{
  "type": "/problems/tag-value-illegal",
  "title": "Tag value not permitted by the dictionary",
  "status": 422,
  "detail": "Tag key 'region' does not permit value 'antarctica'.",
  "instance": "/api/v1/catalogs/3f2504e0-4f89-41d3-9a0c-0305e82c3301/categories",
  "errorCode": "TAG_VALUE_ILLEGAL",
  "timestamp": "2026-07-13T10:15:30Z"
}
```

The `type` is a **stable, relative, opaque** identifier (`/problems/<kebab>`) — it is an id, not
a dereferenceable URL. The full `errorCode` set this service can emit is the enum on
`ProblemDetail` in the spec.

---

## Schema Reference

Field-level truth is the OpenAPI spec; these are the shapes as documented there.

### Category
```typescript
{
  id: string;                       // uuid, server-assigned (read-only)
  catalogId: string;                // uuid, the owning catalog (read-only)
  parentId: string | null;          // uuid; null = root category under the catalog
  name: string;
  description?: string;
  tags?: {                          // dictionary-validated
    [key: string]: string | string[];
  };
  _actions?: {                      // server-emitted, read-only; omitted when uncomputable
    [action: string]: boolean;      // keys: view, update, delete, assign-tags
  };
}
```

### CategoryRequest
```typescript
{
  name: string;                     // required, 1–200 chars
  parentId?: string | null;         // uuid; omit/null for a root category
  description?: string;             // ≤ 2000 chars
  tags?: {                          // accepted on CREATE and UPDATE (unlike a catalog)
    [key: string]: string | string[];
  };
}
```

### CategoryPage
```typescript
{
  count: number;                    // int64 — total categories the caller may see (subject-relative)
  page: number;                     // 0-based, echoes the request
  perPage: number;                  // 1–100, echoes the request
  items: Category[];
}
```

### ProblemDetail (error envelope)
```typescript
{
  type?: string;                    // stable, relative, opaque id: /problems/<kebab>
  title?: string;                   // short, status-stable summary of the problem kind
  status: number;                   // HTTP status, repeated in the body
  detail?: string;                  // human, instance-specific explanation
  instance?: string;                // the request path that produced the error
  errorCode:                        // machine-stable code to branch on
    | "ACCESS_DENIED"
    | "RESOURCE_NOT_FOUND"
    | "VALIDATION_FAILED"
    | "TAG_VALUE_ILLEGAL"
    | "DEPENDENCY_UNAVAILABLE"
    | "STATE_CONFLICT"
    | "TAG_OPERATOR_MANAGED"
    | "STEP_UP_REQUIRED";
  timestamp?: string;               // ISO-8601, when the error was produced (correlation)
}
```

---

## Related Documentation

- [Catalog API](catalog-api.md) — the parent resource; note the **tag-on-create contrast**
  (catalogs reject tags on create, categories accept them).
- [Product API](product-api.md) — the leaf resource beneath a category.
- [`docs/api/README.md`](README.md) — API-reference conventions (media types, pagination
  envelope, the `_actions` affordance map, the error contract).
- [REST API design](../guides/REST-API-DESIGN.md) — the cross-cutting design rules (page
  envelope, `problem+json`, the `errorCode` vocabulary).
- [Action enrichment](../guides/ACTION-ENRICHMENT.md) — how the `_actions` affordance map is
  computed and attached.
- [ABAC authorization](../guides/ABAC-AUTHORIZATION.md) — the hierarchy-aware authorization
  spine behind these endpoints.
- **Contract source of truth**: the OpenAPI spec
  (`example-catalog-management-service/src/main/resources/openapi/catalog-api.yaml`) and each
  running service's **Swagger UI** at `/swagger-ui.html`.
