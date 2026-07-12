---
tags:
  - status/active
  - type/reference
  - area/api
  - audience/developer
---

# Product API

REST API endpoints for the **Product** resource of the example catalog-management-service — the
leaf of the `Catalog → Category → Product` hierarchy.

> **Source of truth.** This page is a hand-written companion to the OpenAPI contract at
> `example-catalog-management-service/src/main/resources/openapi/catalog-api.yaml` and the running
> service's **Swagger UI** at `/swagger-ui.html`. Where this page and the spec ever disagree, the
> spec (and the generated client) win. See the sibling pages
> [`catalog-api.md`](catalog-api.md) and [`category-api.md`](category-api.md), and the
> [`docs/api/README.md`](README.md) index.

---

## Overview

A **Product** is a sellable item nested under a category, which is itself nested under a catalog.
Every product endpoint is addressed through its full ancestor path — there is no top-level
`/products` collection. Products are **taggable** (dictionary-validated tags, ADR 0025): unlike a
catalog, a new product already has a governing team (its catalog's), so tags are accepted **on
create as well as on update**.

Authorization is attribute-based (ABAC), evaluated by Open Policy Agent (OPA):

- **Single-resource** reads/writes run an `@OpaPreAuthorize` check against the product (or, for
  `list`/`create`, against the governing catalog as the role-resource) before the handler body runs.
- **`GET …/products`** applies the **partial-evaluation data-filter cut**: OPA compiles the policy
  to a SQL residual that is AND-ed with the `categoryId` path scope, so the page contains **only the
  rows the caller is authorized to see**. The list envelope's `count` is therefore
  **subject-relative** — the caller's authorized total across all pages, never `items.length`. An
  inheritable grant on an **ancestor** (the catalog) can *widen* the visible products beyond the
  caller's direct grants.
- Every returned product carries a server-emitted **`_actions`** affordance map — which of
  `view` / `update` / `delete` / `assign-tags` the caller may perform. It is advisory; the real gate
  denies on its own terms.

Personas used in the examples below are neutral: an **editor** (may create/update products), a
**viewer** (read-only), an **owner** (full control over a catalog subtree), and an **outsider** (no
grant — sees an empty list and `403` on direct access).

**Base Path**: `/api/v1/catalogs/{catalogId}/categories/{categoryId}/products`

All error responses are `application/problem+json` (RFC-7807) with a typed `errorCode` a client
branches on — see [Errors](#errors) and the [ProblemDetail](#problemdetail) schema.

---

## Endpoints

| Method | Path | Operation |
|--------|------|-----------|
| `GET` | `…/products` | [List products](#list-products) |
| `POST` | `…/products` | [Create a product](#create-a-product) |
| `GET` | `…/products/{productId}` | [Get a product](#get-a-product) |
| `PUT` | `…/products/{productId}` | [Update a product](#update-a-product) |
| `DELETE` | `…/products/{productId}` | [Delete a product](#delete-a-product) |

---

### List products

Get a page of products in a category. The page is the **cut** — only the rows the caller is
authorized to read.

```http
GET /api/v1/catalogs/{catalogId}/categories/{categoryId}/products
```

**Path Parameters**:
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `catalogId` | string (uuid) | Yes | The catalog that governs this subtree. |
| `categoryId` | string (uuid) | Yes | The category whose products to list. |

**Query Parameters**:
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `page` | integer | No | 0 | 0-based page index. A bounds violation is a `400 VALIDATION_FAILED` (no clamping). |
| `perPage` | integer | No | 20 | Page size (1–100). A bounds violation is a `400 VALIDATION_FAILED` (no clamping). |

**Response** (`200 OK`):

The shared page envelope — `{ count, page, perPage, items }`. Rows are ordered `createdAt ASC, id ASC`
(a fixed total order; clients do not choose the sort). `count` is the caller's authorized total across
all pages, not the length of `items` on this page.

```json
{
  "count": 2,
  "page": 0,
  "perPage": 20,
  "items": [
    {
      "id": "b1e7c9a4-2f3d-4a11-9c0e-6d2f8a1b4c30",
      "categoryId": "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
      "name": "Demo widget",
      "description": "A demonstration widget.",
      "sku": "WIDGET-001",
      "priceCents": 1999,
      "currency": "USD",
      "tags": {
        "region": "EMEA"
      },
      "_actions": { "view": true, "update": true, "delete": true, "assign-tags": true }
    },
    {
      "id": "c2f8dab5-3a4e-4b22-8d1f-7e3f9b2c5d41",
      "categoryId": "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
      "name": "Demo gadget",
      "description": "A demonstration gadget.",
      "sku": "GADGET-002",
      "priceCents": 4950,
      "currency": "EUR",
      "tags": {
        "region": "EMEA"
      },
      "_actions": { "view": true, "update": false, "delete": false, "assign-tags": false }
    }
  ]
}
```

**Authorization**: `product:list`, evaluated against the governing catalog as the role-resource. The
which-rows cut is the partial-eval residual AND-ed with the `categoryId` scope. An inheritable grant
on the ancestor **catalog** may widen the set (subtree widening) beyond the caller's direct
grants. The path is **fail-closed**: an unauthenticated caller, a role-source outage, or no matching
role definition yields the **empty page**, never the full table — so an **outsider** gets
`{ "count": 0, …, "items": [] }`, not a `403`.

**cURL Example**:
```bash
curl -s "http://localhost:8080/api/v1/catalogs/$CATALOG_ID/categories/$CATEGORY_ID/products?page=0&perPage=20" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/json"
```

---

### Create a product

Create a product in a category. Tags are accepted here (tag-on-create).

```http
POST /api/v1/catalogs/{catalogId}/categories/{categoryId}/products
```

**Path Parameters**:
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `catalogId` | string (uuid) | Yes | The governing catalog. |
| `categoryId` | string (uuid) | Yes | The parent category the product is created under. |

**Request Body** (`ProductRequest`):
| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `name` | string | Yes | 1–200 chars | Product name. |
| `description` | string | No | ≤ 2000 chars | Free-text description. |
| `sku` | string | No | ≤ 64 chars | Stock-keeping unit. |
| `priceCents` | integer (int64) | Yes | ≥ 0 | Price in **minor units** (e.g. cents). |
| `currency` | string | Yes | exactly 3 chars | ISO 4217 currency code (e.g. `USD`). |
| `tags` | object | No | dictionary-validated | Tags to assign; each value is a string (single-valued key) or an array of strings (multi-valued key). An unknown key or illegal value is rejected `422 TAG_VALUE_ILLEGAL` — nothing is silently dropped. |

```json
{
  "name": "Demo widget",
  "description": "A demonstration widget.",
  "sku": "WIDGET-001",
  "priceCents": 1999,
  "currency": "USD",
  "tags": {
    "region": "EMEA"
  }
}
```

**Response** (`201 Created`):

The created `Product`, with a `Location` header pointing at the new resource. `id` and `categoryId`
are server-assigned (read-only); `_actions` is server-emitted.

```json
{
  "id": "b1e7c9a4-2f3d-4a11-9c0e-6d2f8a1b4c30",
  "categoryId": "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
  "name": "Demo widget",
  "description": "A demonstration widget.",
  "sku": "WIDGET-001",
  "priceCents": 1999,
  "currency": "USD",
  "tags": {
    "region": "EMEA"
  },
  "_actions": { "view": true, "update": true, "delete": true, "assign-tags": true }
}
```

**Authorization**: `product:create`, evaluated against the governing catalog as the role-resource. A
request that **carries tags** additionally requires the type-level `assign-tags` decision resolved
through the governing catalog's team (tag-on-create) — an **editor** without tag authority creating a
tagged product is denied `403 ACCESS_DENIED`, while the same editor creating an untagged product
succeeds. Tag validation happens against the dictionary before persistence; an illegal value is
`422 TAG_VALUE_ILLEGAL`, and a tag-dictionary fetch failure is fail-closed `503 DEPENDENCY_UNAVAILABLE`.

**cURL Example**:
```bash
curl -s -X POST "http://localhost:8080/api/v1/catalogs/$CATALOG_ID/categories/$CATEGORY_ID/products" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Demo widget",
    "sku": "WIDGET-001",
    "priceCents": 1999,
    "currency": "USD",
    "tags": { "region": "EMEA" }
  }'
```

---

### Get a product

Get a single product by ID.

```http
GET /api/v1/catalogs/{catalogId}/categories/{categoryId}/products/{productId}
```

**Path Parameters**:
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `catalogId` | string (uuid) | Yes | The governing catalog. |
| `categoryId` | string (uuid) | Yes | The parent category. |
| `productId` | string (uuid) | Yes | The product to fetch. |

**Response** (`200 OK`): the `Product` object (same shape as a list item, including `_actions`).

**Authorization**: `product:view`, evaluated against the product. A caller with no grant — an
**outsider**, or a **viewer** on a different subtree — gets `403 ACCESS_DENIED`. A product whose
ancestors do not match the `{catalogId}/{categoryId}` in the URL is `404 RESOURCE_NOT_FOUND`.

**cURL Example**:
```bash
curl -s "http://localhost:8080/api/v1/catalogs/$CATALOG_ID/categories/$CATEGORY_ID/products/$PRODUCT_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/json"
```

---

### Update a product

Replace a product's editable fields. Tags supplied here are validated and applied
(dictionary-validated).

```http
PUT /api/v1/catalogs/{catalogId}/categories/{categoryId}/products/{productId}
```

**Path Parameters**: `catalogId`, `categoryId`, `productId` — all `string (uuid)`, all required.

**Request Body** (`ProductRequest`): same schema as [create](#create-a-product) — `name`,
`priceCents`, `currency` required; `description`, `sku`, `tags` optional. An illegal tag value is
`422 TAG_VALUE_ILLEGAL`.

**Response** (`200 OK`): the updated `Product`.

**Authorization**: the write is gated per the caller's grant on the product; a delta that changes
`tags` additionally exercises the `assign-tags` decision, so a role that may edit content but not
relabel tags is denied on a tag-changing update (`403 ACCESS_DENIED`) while its content-only edits
succeed. A missing product is `404 RESOURCE_NOT_FOUND`; a tag-dictionary fetch failure is fail-closed
`503 DEPENDENCY_UNAVAILABLE`.

**cURL Example**:
```bash
curl -s -X PUT "http://localhost:8080/api/v1/catalogs/$CATALOG_ID/categories/$CATEGORY_ID/products/$PRODUCT_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Demo widget (rev 2)",
    "sku": "WIDGET-001",
    "priceCents": 2199,
    "currency": "USD",
    "tags": { "region": "EMEA" }
  }'
```

---

### Delete a product

Delete a product.

```http
DELETE /api/v1/catalogs/{catalogId}/categories/{categoryId}/products/{productId}
```

**Path Parameters**: `catalogId`, `categoryId`, `productId` — all `string (uuid)`, all required.

**Response** (`204 No Content`): empty body on success.

**Authorization**: `product:delete`, evaluated against the product. A caller without the grant — a
**viewer** or **outsider** — gets `403 ACCESS_DENIED`; a missing product is `404 RESOURCE_NOT_FOUND`.

**cURL Example**:
```bash
curl -s -X DELETE "http://localhost:8080/api/v1/catalogs/$CATALOG_ID/categories/$CATEGORY_ID/products/$PRODUCT_ID" \
  -H "Authorization: Bearer $TOKEN"
```

---

## Errors

Every error is an `application/problem+json` (RFC-7807) body. A client branches on the typed
`errorCode`, not the human `detail`.

| Status | `errorCode` | When |
|--------|-------------|------|
| `400` | `VALIDATION_FAILED` | Malformed body, or a `page`/`perPage` bounds violation (no clamping). |
| `403` | `ACCESS_DENIED` | The OPA gate denied — no grant, an unauthenticated/unresolved subject, or a tag decision the caller lacks. |
| `404` | `RESOURCE_NOT_FOUND` | No such product under the given `catalogId`/`categoryId`. |
| `422` | `TAG_VALUE_ILLEGAL` | A tag key/value the dictionary does not permit (on create or update). |
| `503` | `DEPENDENCY_UNAVAILABLE` | A required dependency (e.g. the tag dictionary) was unreachable — fail-closed; the request was rejected, not applied. |

> Note the `list` path is fail-closed to an **empty page**, not a `403` — an unauthorized `list`
> caller sees `{ "count": 0, …, "items": [] }`. A `403` on `list` means the request itself was
> rejected (e.g. an unresolved subject at the gate), not that the caller simply has zero visible rows.

**Example — `403` on a direct read by an outsider** (`GET …/products/{productId}`):

```json
{
  "type": "/problems/access-denied",
  "title": "Access denied",
  "status": 403,
  "detail": "Not authorized to view this product.",
  "instance": "/api/v1/catalogs/7b3e.../categories/3f25.../products/b1e7...",
  "errorCode": "ACCESS_DENIED",
  "timestamp": "2026-07-13T10:15:30Z"
}
```

---

## Schema Reference

### Product

The response representation. `id`, `categoryId`, and `_actions` are `readOnly` (server-assigned /
server-emitted). Required members: `id`, `categoryId`, `name`, `priceCents`, `currency`.

```typescript
{
  id: string;            // uuid, readOnly
  categoryId: string;    // uuid, readOnly
  name: string;
  description?: string;
  sku?: string;
  priceCents: number;    // int64 — price in minor units (e.g. cents)
  currency: string;      // ISO 4217 code, e.g. "USD"
  tags?: {               // dictionary-validated; value is a string or string[]
    [key: string]: string | string[];
  };
  _actions?: {           // readOnly affordance map (server-emitted; absent when not computable)
    [action: string]: boolean;   // view | update | delete | assign-tags
  };
}
```

### ProductRequest

The create/update body. Required members: `name`, `priceCents`, `currency`.

```typescript
{
  name: string;          // 1–200 chars
  description?: string;   // ≤ 2000 chars
  sku?: string;           // ≤ 64 chars
  priceCents: number;     // int64, ≥ 0 — minor units
  currency: string;       // exactly 3 chars, ISO 4217
  tags?: {                // dictionary-validated; 422 on unknown key / illegal value
    [key: string]: string | string[];
  };
}
```

### ProductPage

The list envelope — the shared `PageEnvelope` plus `items`.

```typescript
{
  count: number;    // int64 — total rows the CALLER is authorized to see, across all pages
  page: number;     // 0-based page index, echoing the request
  perPage: number;  // page size (1–100), echoing the request
  items: Product[]; // this page's rows, ordered createdAt ASC, id ASC
}
```

### ProblemDetail

The RFC-7807 error body (`application/problem+json`). Required members: `status`, `errorCode`.

```typescript
{
  type?: string;      // stable, relative, opaque problem-kind id (e.g. "/problems/tag-value-illegal") — not dereferenced
  title?: string;     // short, status-stable summary of the problem kind
  status: number;     // int32 — the HTTP status code
  detail?: string;    // human, instance-specific explanation
  instance?: string;  // the request path that produced the error
  errorCode: "ACCESS_DENIED" | "RESOURCE_NOT_FOUND" | "VALIDATION_FAILED"
           | "TAG_VALUE_ILLEGAL" | "DEPENDENCY_UNAVAILABLE" | "STATE_CONFLICT";
  timestamp?: string; // date-time — when the error was produced (correlation)
}
```

---

## Related Documentation

- [Catalog API](catalog-api.md) — the top-level catalog resource (governs the subtree).
- [Category API](category-api.md) — the category tree between catalog and product.
- [`docs/api/README.md`](README.md) — the API-reference index.
- OpenAPI spec: `example-catalog-management-service/src/main/resources/openapi/catalog-api.yaml` —
  the codegen source of truth.
- Swagger UI: the running service's `/swagger-ui.html`.
- Guides: [`../guides/PARTIAL-EVALUATION-FILTERING.md`](../guides/PARTIAL-EVALUATION-FILTERING.md)
  (the data-filter cut), [`../guides/ACTION-ENRICHMENT.md`](../guides/ACTION-ENRICHMENT.md)
  (the `_actions` map), [`../guides/TAG-BASED-AUTHORIZATION.md`](../guides/TAG-BASED-AUTHORIZATION.md)
  (dictionary-validated tags).
