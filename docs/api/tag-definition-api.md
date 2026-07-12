---
tags:
  - status/active
  - type/reference
  - area/api
  - audience/developer
created: 2026-07-13
updated: 2026-07-13
---

# Tag Definition API

REST reference for the **Tag Definition** resource on the example `user-management-service` —
the **dynamic tag dictionary** that governs what tags a resource may carry. A *tag definition*
is a dictionary row that names a **tag key** (e.g. `sensitivity`, `region`) and pins its shape:
what kind of value it accepts (`valueType`), how many (`cardinality`), and — for a closed set —
the exact values allowed (`allowedValues`). Nothing may write a tag whose key is not in this
dictionary, and no value may be written that the key's definition forbids.

This is the **"user-managed tags done properly"** story. A team defines its **own** tag keys at
runtime, and a key defined this second governs assignment **and** authorization decisions the
next — no redeploy, no code change, no hardcoded tag list (ADR
[[0004-dynamic-tag-dictionary|0004]]). The dictionary has two scopes:

- **`GLOBAL`** keys — seeded, system-owned, immutable. Every team sees them; no one edits them.
- **`TEAM`** keys — defined by a team's owner/administrator, scoped to that team, editable by them.

---

## Overview

The dictionary is **governance**, and it is deliberately separated from the two things it
constrains (ADR 0004's three-layer split):

1. **Definition** — *this API*. Curating the vocabulary: which keys exist, and the legal shape
   of each. Authoring team keys is an owner/administrator control-plane act.
2. **Assignment** — attaching **values** to a resource, on the **catalog** service (a catalog's,
   category's, or product's `tags`). A normal `write` — the dictionary only constrains *legality*.
3. **Requirement** — a role's `requiredTags`, matched in Rego, that grants access to resources
   whose tags satisfy it (behind [role-definition-api.md](role-definition-api.md)).

This page covers layer 1. The **enforcement** the dictionary drives happens in layer 2: when a
value is assigned on the catalog service, it is validated against the applicable definitions, and
an illegal value is rejected `422 TAG_VALUE_ILLEGAL` (see [How the dictionary is
enforced](#how-the-dictionary-is-enforced-at-assignment-time)).

**Base Path**: `/api/v1` (the resource lives at two shapes — a **read** surface rooted at
`/api/v1/tag-definitions`, and a team-scoped **authoring** surface under
`/api/v1/teams/{teamId}/tag-definitions`).

**Authentication**: every request arrives **through the APISIX gateway** with a bearer token
(see the [API index](README.md#authentication)). The **read** endpoints are bearer-only —
listing or reading a definition grants nothing. The **authoring** endpoints (create / update /
delete under a team) are gated by the **`team:define-tags`** control-plane decision: only a
team's **owner** or **administrator** may curate that team's keys. A role that holds only `TAG`
*assign* power on the catalog plane — but not `define-tags` — **cannot author keys** and is
denied `403 ACCESS_DENIED` (ADR [[0015-control-plane-vocabulary-categorization|0015]]).
`GLOBAL`/`system` keys are **immutable**: an attempt to edit or delete one is a `409 Conflict`.

**Errors**: `application/problem+json` (RFC 7807); a client branches on the machine-stable
`errorCode`, never on the human `detail`. See [Error model](#error-model).

---

## The dictionary model

Four axes define a tag key. They are the whole vocabulary this API curates (ADR 0004).

| Axis | Values | Meaning |
|------|--------|---------|
| **`scope`** | `GLOBAL` \| `TEAM` | `GLOBAL` = a seeded system key every team shares; `TEAM` = a key one team defined, scoped to that team (`teamId` set). |
| **`valueType`** | `STRING` \| `ENUM` | `ENUM` = a **closed set** — a value must be one of `allowedValues`. `STRING` = free text, optionally constrained by `valuePattern`. |
| **`cardinality`** | `SINGLE` \| `MULTI` | `SINGLE` = one value (stored as a scalar). `MULTI` = a set of values (stored as an array). |
| **`system`** | `true` \| `false` | `true` = an immutable seeded key (always `GLOBAL`); `false` = a team-authored key. A `system` key cannot be updated or deleted. |

- **`allowedValues`** is the closed value set for an `ENUM` key. It is **required, non-empty** for
  `ENUM`; **empty** for `STRING`.
- **`valuePattern`** is an **optional** regex that constrains a `STRING` value. It is meaningless
  for `ENUM` (the set already closes the value).

`scope` and `system` are related but not identical: every `system` key is `GLOBAL`, but a `GLOBAL`
key is what teams share and only the seeded system keys are `system: true`. In this demo the
seeded keys are the `system` `GLOBAL` set; teams add `TEAM` keys on top.

---

## Endpoints

| Method & path | Operation | Auth | Returns |
|---|---|---|---|
| `GET /api/v1/tag-definitions` | List applicable definitions (globals, plus a team's when `teamId` given) | bearer-only | `TagDefinitionPage` |
| `GET /api/v1/tag-definitions/{id}` | Get one definition by id | bearer-only | `TagDefinition` |
| `GET /api/v1/teams/{teamId}/tag-definitions` | List a team's applicable keys (globals + this team's) | bearer-only | `TagDefinitionPage` |
| `POST /api/v1/teams/{teamId}/tag-definitions` | Define a team-scoped key | `team:define-tags` | `TagDefinition` |
| `PUT /api/v1/teams/{teamId}/tag-definitions/{key}` | Update a team-scoped key | `team:define-tags` | `TagDefinition` |
| `DELETE /api/v1/teams/{teamId}/tag-definitions/{key}` | Delete a team-scoped key | `team:define-tags` | — (`204`) |

---

### List Tag Definitions

List the **applicable** tag definitions in the shared page envelope. Without `teamId` this is the
**global** dictionary — the seeded `system` keys every team shares. With `teamId`, the page
includes **that team's** keys **alongside** the globals — the exact set legal to assign on a
resource governed by that team.

```http
GET /api/v1/tag-definitions
```

**Query Parameters**:

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `teamId` | string (uuid) | No | — | When present, include this team's `TEAM` keys alongside the `GLOBAL` keys. Absent → the global dictionary only. |
| `page` | integer | No | `0` | 0-based page index. A bounds violation is a `400 VALIDATION_FAILED` (**no clamping**). |
| `perPage` | integer | No | `20` | Page size, `1`–`100`. A bounds violation is a `400 VALIDATION_FAILED` (**no clamping**). |

**Response** (`200 OK`) — `TagDefinitionPage` (a page envelope). Here the global dictionary holds
one seeded key, `sensitivity`:

```json
{
  "count": 1,
  "page": 0,
  "perPage": 20,
  "items": [
    {
      "id": "a1c2e3f4-5b6d-4a7e-8f90-1b2c3d4e5f60",
      "key": "sensitivity",
      "scope": "GLOBAL",
      "teamId": null,
      "valueType": "ENUM",
      "cardinality": "SINGLE",
      "allowedValues": ["public", "internal", "confidential"],
      "valuePattern": null,
      "system": true
    }
  ]
}
```

`count` is the total rows the caller may see **across all pages** (never `items.length`). Rows are
ordered `createdAt ASC, id ASC` — a fixed total order the client does not choose.

**cURL**:

```bash
# The global dictionary (seeded system keys only)
curl -s "http://localhost:8080/api/v1/tag-definitions" \
  -H "Authorization: Bearer $TOKEN"

# Globals + one team's keys (the set legal on that team's resources)
curl -s "http://localhost:8080/api/v1/tag-definitions?teamId=7c9e6a1b-2d3f-4e50-9a6b-8c1d2e3f4a5b" \
  -H "Authorization: Bearer $TOKEN"
```

---

### Get Tag Definition

Get a single tag definition by its `id` (the UUID from list/create).

```http
GET /api/v1/tag-definitions/{id}
```

**Path Parameters**:

| Parameter | Type | Description |
|-----------|------|-------------|
| `id` | string (uuid) | The tag-definition id. |

**Response** (`200 OK`) — `TagDefinition`; `404 RESOURCE_NOT_FOUND` (`application/problem+json`)
when no definition has that id.

**cURL**:

```bash
curl -s "http://localhost:8080/api/v1/tag-definitions/a1c2e3f4-5b6d-4a7e-8f90-1b2c3d4e5f60" \
  -H "Authorization: Bearer $TOKEN"
```

---

### List a Team's Tag Definitions

List the keys **applicable to a team** — the `GLOBAL` keys **plus** this team's own `TEAM` keys —
in the page envelope. This is the effective dictionary a writer sees when tagging a resource that
team governs.

```http
GET /api/v1/teams/{teamId}/tag-definitions
```

**Path Parameters**:

| Parameter | Type | Description |
|-----------|------|-------------|
| `teamId` | string (uuid) | The team whose keys to include (alongside the globals). |

**Query Parameters**: `page`, `perPage` — same contract as [List Tag Definitions](#list-tag-definitions).

**Response** (`200 OK`) — `TagDefinitionPage`. Two applicable keys: the seeded global `sensitivity`,
and the team's own `region`:

```json
{
  "count": 2,
  "page": 0,
  "perPage": 20,
  "items": [
    {
      "id": "a1c2e3f4-5b6d-4a7e-8f90-1b2c3d4e5f60",
      "key": "sensitivity",
      "scope": "GLOBAL",
      "teamId": null,
      "valueType": "ENUM",
      "cardinality": "SINGLE",
      "allowedValues": ["public", "internal", "confidential"],
      "valuePattern": null,
      "system": true
    },
    {
      "id": "b2d3f405-6c7e-4b8f-9a01-2c3d4e5f6a70",
      "key": "region",
      "scope": "TEAM",
      "teamId": "7c9e6a1b-2d3f-4e50-9a6b-8c1d2e3f4a5b",
      "valueType": "ENUM",
      "cardinality": "SINGLE",
      "allowedValues": ["emea", "amer", "apac"],
      "valuePattern": null,
      "system": false
    }
  ]
}
```

**cURL**:

```bash
curl -s "http://localhost:8080/api/v1/teams/7c9e6a1b-2d3f-4e50-9a6b-8c1d2e3f4a5b/tag-definitions" \
  -H "Authorization: Bearer $TOKEN"
```

---

### Create a Team Tag Definition

Define a **new team-scoped tag key**. This is the dynamic dictionary in action: the key is a
runtime row, and once created it governs assignment **and** decisions immediately — no redeploy.

```http
POST /api/v1/teams/{teamId}/tag-definitions
```

**Authorization**: the **`team:define-tags`** control-plane decision — **owner or administrator**
only (ADR 0015). A role that can *assign* tags (`TAG`) but not *define* them is denied
`403 ACCESS_DENIED`. Curating the vocabulary is a stricter act than using it.

**Request Body** — `TagDefinitionRequest`:

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `key` | string | Yes | 1–100 chars; kebab-case recommended | The tag key, e.g. `tier`. Must be unique among this team's keys **and** not collide with a global key. |
| `valueType` | string | Yes | `STRING` \| `ENUM` | `ENUM` = a closed set (`allowedValues` required); `STRING` = free text. |
| `cardinality` | string | Yes | `SINGLE` \| `MULTI` | `SINGLE` = one value; `MULTI` = a set of values. |
| `allowedValues` | string[] | for `ENUM` | non-empty when `valueType=ENUM` | The closed set for an `ENUM` key. Omitted/empty for `STRING`. |
| `valuePattern` | string | No | a regex | Optional constraint on a `STRING` value. Meaningless for `ENUM`. |

Define a new `ENUM`, `SINGLE` key `tier` for this team:

```json
{
  "key": "tier",
  "valueType": "ENUM",
  "cardinality": "SINGLE",
  "allowedValues": ["standard", "premium"]
}
```

**Response** (`201 Created`) — `TagDefinition`. The server assigns `id`, stamps `scope: "TEAM"`,
fills `teamId` from the path, and sets `system: false` (a team-authored key is never `system`):

```json
{
  "id": "c3e4f506-7d8f-4c90-ab12-3d4e5f6a7b80",
  "key": "tier",
  "scope": "TEAM",
  "teamId": "7c9e6a1b-2d3f-4e50-9a6b-8c1d2e3f4a5b",
  "valueType": "ENUM",
  "cardinality": "SINGLE",
  "allowedValues": ["standard", "premium"],
  "valuePattern": null,
  "system": false
}
```

**Conflict / validation**:

- A key that already exists (for this team, or as a global) is `409 TAG_KEY_CONFLICT`.
- A malformed definition — e.g. `ENUM` with empty `allowedValues`, or an unparseable
  `valuePattern` — is `422 TAG_DEFINITION_INVALID`.

**cURL**:

```bash
curl -s -X POST "http://localhost:8080/api/v1/teams/7c9e6a1b-2d3f-4e50-9a6b-8c1d2e3f4a5b/tag-definitions" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "key": "tier",
    "valueType": "ENUM",
    "cardinality": "SINGLE",
    "allowedValues": ["standard", "premium"]
  }'
```

---

### Update a Team Tag Definition

Update a team-scoped key's shape, addressed by its `key`. Use this to widen a closed set (add a
value to `allowedValues`), change a `valuePattern`, or adjust `valueType`/`cardinality`.

```http
PUT /api/v1/teams/{teamId}/tag-definitions/{key}
```

**Path Parameters**:

| Parameter | Type | Description |
|-----------|------|-------------|
| `teamId` | string (uuid) | The owning team. |
| `key` | string | The tag key to update (as authored, e.g. `tier`). |

**Authorization**: `team:define-tags` — **owner or administrator** only (ADR 0015).

**Global / system keys are immutable.** Addressing a `GLOBAL`/`system` key through a team's
authoring path — or any attempt to edit a seeded key — is a `409 TAG_DEFINITION_IMMUTABLE`.

**Request Body** — `TagDefinitionUpdate` (same shape as create, without `key` — the path carries
it):

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `valueType` | string | Yes | `STRING` \| `ENUM`. |
| `cardinality` | string | Yes | `SINGLE` \| `MULTI`. |
| `allowedValues` | string[] | for `ENUM` | The closed set (non-empty for `ENUM`). |
| `valuePattern` | string | No | Optional `STRING` regex. |

Widen `tier` to add a third allowed value:

```json
{
  "valueType": "ENUM",
  "cardinality": "SINGLE",
  "allowedValues": ["standard", "premium", "enterprise"]
}
```

**Response** (`200 OK`) — the updated `TagDefinition`. A missing key is `404 RESOURCE_NOT_FOUND`; a
malformed body is `422 TAG_DEFINITION_INVALID`.

**cURL**:

```bash
curl -s -X PUT "http://localhost:8080/api/v1/teams/7c9e6a1b-2d3f-4e50-9a6b-8c1d2e3f4a5b/tag-definitions/tier" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "valueType": "ENUM",
    "cardinality": "SINGLE",
    "allowedValues": ["standard", "premium", "enterprise"]
  }'
```

---

### Delete a Team Tag Definition

Delete a team-scoped key, addressed by its `key`.

```http
DELETE /api/v1/teams/{teamId}/tag-definitions/{key}
```

**Authorization**: `team:define-tags` — **owner or administrator** only (ADR 0015).

**Response** (`204 No Content`) — no body. A missing key is `404 RESOURCE_NOT_FOUND`. A
`GLOBAL`/`system` key **cannot be deleted** — it is `409 TAG_DEFINITION_IMMUTABLE`.

**cURL**:

```bash
curl -s -X DELETE "http://localhost:8080/api/v1/teams/7c9e6a1b-2d3f-4e50-9a6b-8c1d2e3f4a5b/tag-definitions/tier" \
  -H "Authorization: Bearer $TOKEN" -o /dev/null -w "%{http_code}\n"
```

---

## How the dictionary is enforced (at assignment time)

The dictionary is authored here, but **it does its work on the catalog service**. When a caller
assigns tags to a catalog, category, or product, the catalog service fetches the *applicable*
definitions (globals + the governing team's keys) and validates the submitted values against them
**before persistence** (ADR 0004, layer 2). The checks:

- **Unknown key** — a `tags` entry whose key is in neither the globals nor the governing team's
  keys → `422 TAG_VALUE_ILLEGAL`. Nothing is silently dropped.
- **ENUM value outside `allowedValues`** → `422 TAG_VALUE_ILLEGAL`.
- **STRING value failing `valuePattern`** → `422 TAG_VALUE_ILLEGAL`.
- **Dictionary fetch failure** (the user-management-service unreachable) → **fail-closed**
  `503 DEPENDENCY_UNAVAILABLE`: the write is rejected, never applied against an empty (all-allowed)
  set.

Assigning `tier: "ultra"` when the `tier` key only allows `standard`/`premium`/`enterprise` — a
write on the **catalog** service — is therefore rejected:

```json
{
  "type": "/problems/tag-value-illegal",
  "title": "Tag value illegal",
  "status": 422,
  "detail": "Value 'ultra' is not permitted for key 'tier'.",
  "instance": "/api/v1/products/1f0c3c2a-8b7e-4d21-9a10-6e5b4c3d2a11",
  "errorCode": "TAG_VALUE_ILLEGAL",
  "timestamp": "2026-07-13T10:15:30Z"
}
```

> **Two services, two vocabularies.** `TAG_VALUE_ILLEGAL` is a **catalog-service** code (the
> enforcement point). The **user-management-service** — where keys are authored — emits
> `TAG_KEY_CONFLICT`, `TAG_DEFINITION_IMMUTABLE`, and `TAG_DEFINITION_INVALID` (below). Authoring a
> key never returns `TAG_VALUE_ILLEGAL`; assigning a value never returns the authoring codes.

For the assignment surface — where `tags` is submitted and validated — see
[category-api.md](category-api.md) and [product-api.md](product-api.md).

---

## Error model

Errors are `application/problem+json` (RFC 7807): the standard members plus two documented
extensions, `errorCode` (the machine-stable code a client branches on) and `timestamp`.

```json
{
  "type": "/problems/tag-definition-invalid",
  "title": "Tag definition invalid",
  "status": 422,
  "detail": "An ENUM key requires a non-empty allowedValues.",
  "instance": "/api/v1/teams/7c9e6a1b-2d3f-4e50-9a6b-8c1d2e3f4a5b/tag-definitions",
  "errorCode": "TAG_DEFINITION_INVALID",
  "timestamp": "2026-07-13T10:00:00Z"
}
```

Codes relevant to this resource, drawn from the user-management-service `errorCode` union:

| HTTP | `errorCode` | When |
|------|-------------|------|
| `400` | `VALIDATION_FAILED` | `page`/`perPage` out of bounds, or a malformed request body. |
| `403` | `ACCESS_DENIED` | The caller lacks `team:define-tags` (create/update/delete) — e.g. a role with `TAG` assign power but no `define-tags`. |
| `404` | `RESOURCE_NOT_FOUND` | `GET /tag-definitions/{id}` for an unknown id, or an authoring call on a `key` this team has not defined. |
| `409` | `TAG_KEY_CONFLICT` | Creating a key that already exists (for this team, or as a global). |
| `409` | `TAG_DEFINITION_IMMUTABLE` | Updating or deleting a `GLOBAL`/`system` key — the seeded keys are read-only. |
| `422` | `TAG_DEFINITION_INVALID` | A malformed definition — `ENUM` with empty `allowedValues`, an unparseable `valuePattern`, etc. |

> **Note.** `TAG_VALUE_ILLEGAL` is **not** emitted here — it is the catalog service's assignment
> code (see [How the dictionary is enforced](#how-the-dictionary-is-enforced-at-assignment-time)).

---

## Schema Reference

### TagDefinition (`GET`/`POST`/`PUT` response)

```typescript
{
  id: string;               // UUID, read-only (server-assigned)
  key: string;              // The tag key, e.g. "sensitivity" or "region"
  scope: "GLOBAL" | "TEAM"; // GLOBAL = seeded system key; TEAM = team-defined
  teamId: string | null;    // null for GLOBAL; the owning team for TEAM
  valueType: "STRING" | "ENUM";
  cardinality: "SINGLE" | "MULTI";
  allowedValues: string[];  // The closed set for an ENUM key; empty for STRING
  valuePattern: string | null; // Optional regex constraining a STRING value
  system: boolean;          // true = an immutable seeded global key
}
```

### TagDefinitionRequest (`POST` body)

```typescript
{
  key: string;              // 1–100 chars; kebab-case recommended, e.g. "tier"
  valueType: "STRING" | "ENUM";
  cardinality: "SINGLE" | "MULTI";
  allowedValues?: string[]; // Required (non-empty) for ENUM; omitted/empty for STRING
  valuePattern?: string | null; // Optional regex for a STRING key
}
```

### TagDefinitionUpdate (`PUT` body)

```typescript
{
  // key is carried in the path, not the body
  valueType: "STRING" | "ENUM";
  cardinality: "SINGLE" | "MULTI";
  allowedValues?: string[];
  valuePattern?: string | null;
}
```

### TagDefinitionPage (`GET` list response)

```typescript
{
  count: number;    // Total rows the caller may see, across all pages (int64)
  page: number;     // 0-based page index, echoing the request
  perPage: number;  // Page size (1–100), echoing the request
  items: TagDefinition[];
}
```

### ProblemDetail (`application/problem+json`)

```typescript
{
  type?: string;       // Stable, relative, opaque problem-kind id (not dereferenced)
  title?: string;      // Short, status-stable summary
  status: number;      // HTTP status code
  detail?: string;     // Human, instance-specific explanation
  instance?: string;   // The request path that produced the error
  errorCode: string;   // The machine-stable code a consumer branches on
  timestamp?: string;  // ISO 8601 — when the error was produced
}
```

---

## Related Documentation

- [Role Definition API](role-definition-api.md) — where a role's `requiredTags` consume the
  dictionary: the tag *requirement* half of ABAC, matched in Rego.
- [Category API](category-api.md) · [Product API](product-api.md) — the **assignment** surface:
  where `tags` are submitted on a resource and validated against the dictionary
  (`422 TAG_VALUE_ILLEGAL`).
- [Catalog API](catalog-api.md) — catalogs are taggable on update (the type-level *assign-tags*
  decision resolves through the governing team).
- [API reference index](README.md) — all written API references, and the cross-cutting conventions
  (auth, error contract, pagination) every endpoint obeys.
- [ADR 0004 — The dynamic tag dictionary](../architecture/adr/0004-dynamic-tag-dictionary.md) — the
  three separable layers (definition / assignment / requirement), global + team scope, and the
  match-in-Rego design.
- [ADR 0015 — Control-plane vocabulary categorization](../architecture/adr/0015-control-plane-vocabulary-categorization.md)
  — why `define-tags` is an owner/administrator control-plane act, distinct from `TAG` assign power.
