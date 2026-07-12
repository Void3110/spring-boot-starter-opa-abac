---
tags:
  - status/active
  - type/reference
  - area/api
  - audience/developer
---

# Role Definition API

REST reference for the **RoleDefinition** resource of the user-management-service — the
authorization vocabulary the whole ABAC model decides on. A role definition is a **reusable named
permission set**; binding a *person* to a role on a *team* is a separate fact (the membership). See
ADR [[0003-role-definitions-role-not-grant|0003]] (role ≠ grant) and ADR
[[0007-coarse-grained-permission-categories|0007]] (the coarse permission categories and the
five-tier ladder) for the design behind this page.

> **Source of truth.** This page is hand-written from the OpenAPI spec
> [`example-user-management-service/src/main/resources/openapi/user-mgmt-api.yaml`](../../example-user-management-service/src/main/resources/openapi/user-mgmt-api.yaml).
> The spec is the codegen source and always wins on a discrepancy; browse it live at
> **`/swagger-ui.html`** on the running service. See the [`docs/api/` conventions](README.md) for how
> these reference pages are organized.

---

## Overview

A **role definition** answers "what may a holder of this role do?" — a `{resourceType: [categories]}`
grant, optional deny-overrides, an optional tag requirement, and a tier on a fixed ladder. It is
*not* a grant to any particular person: the membership (see [Membership API](membership-api.md)) is
the fact that binds *(user, team) → role*. The same role can be bound to many people on many teams.

There are **two kinds of role, one model**:

- **System roles** (`system: true`, `teamId: null`) — the immutable global ladder, seeded once. They
  are the five tiers below and cannot be created, edited, or deleted through the API (an attempt is a
  `409 ROLE_IMMUTABLE`).
- **Team-scoped custom roles** (`system: false`, `teamId` set) — authored at runtime by the team
  **owner**, scoped to one team. A `code` is unique *within its scope*, so a team may define its own
  `reader` independently of the system `reader`.

The endpoints on this page manage that second kind. **Authoring role definitions is owner-only** —
it is the control-plane capability that shapes the access ladder itself, deliberately not delegated
to administrators (ADR [[0003-role-definitions-role-not-grant|0003]] §self-escalation). The
list endpoint is readable by any team member; **create / update / delete are owner-only** and return
`403 ACCESS_DENIED` to everyone else.

Access is attribute-based (ABAC). Every request reaches the service through the APISIX gateway,
which terminates OIDC; the caller presents `Authorization: Bearer <jwt>`, the application re-derives
the subject, resolves the caller's effective team role, and asks OPA for the decision. There is no
anonymous access.

**Base Path**: `/api/v1/teams/{teamId}/role-definitions`

**Media types**: requests and success bodies are `application/json`; every error is
`application/problem+json` (an RFC-7807 [`ProblemDetail`](#problemdetail)).

| Method | Path | Operation | Summary |
|--------|------|-----------|---------|
| `GET` | `/api/v1/teams/{teamId}/role-definitions` | `listRoleDefinitions` | List a team's roles — system + this team's custom (paginated) |
| `POST` | `/api/v1/teams/{teamId}/role-definitions` | `createRoleDefinition` | Define a team-scoped custom role (owner) |
| `PUT` | `/api/v1/teams/{teamId}/role-definitions/{code}` | `updateRoleDefinition` | Update a team-scoped custom role (owner) |
| `DELETE` | `/api/v1/teams/{teamId}/role-definitions/{code}` | `deleteRoleDefinition` | Delete a team-scoped custom role (owner) |

---

## The permission model in one screen

Everything on this page is easier to read once these two tables are in view. Both come from ADR
[[0007-coarse-grained-permission-categories|0007]].

### The five-tier ladder

Authoring a role starts by **picking a level**. The level is a **ceiling**: it auto-selects the
categories the role may hold, and the owner then refines **downward only** via
[`deniedActions`](#denied-overrides). `roleLevel` is also the single source of
`attributes.role_level` — any `role_level` supplied inside `attributes` is overwritten from it.

| Level | `roleLevel` | Ceiling (grantable categories) | Authorable? |
|-------|-------------|--------------------------------|-------------|
| **reader** | `10` | `{READ}` | yes |
| **member** | `20` | `{READ, WRITE, TAG}` | yes |
| **senior** | `25` | `{READ, WRITE, TAG}` | yes |
| **administrator** | `30` | `{READ, WRITE, TAG, GRANT}` | yes |
| **owner** | `40` | reserved root | **never** — `owner` is not authorable |

- **`GRANT` is only grantable at level `30`+.** Below administrator, listing `GRANT` in
  `permissions` is a `422 ROLE_DEFINITION_INVALID` (see the [worked 422](#error-a-422-for-granting-grant-below-level-30)).
- **`owner` (40) is never authorable** — it is the reserved root that transfers via the team's
  ownership-transfer flow, not by defining a role at level 40. A create/update with `roleLevel: 40`
  is rejected `422 ROLE_DEFINITION_INVALID`.

### The four permission categories

A role grants **whole categories** — you do not grant action-by-action. Each category expands, in
OPA, to a set of fine actions; you refine by *subtracting* fine actions with `deniedActions`, never
by adding them.

| Category | Expands to (fine actions) | The line it draws |
|----------|---------------------------|-------------------|
| **`READ`** | `view`, `list` | see content |
| **`WRITE`** | `create`, `update`, `delete` | mutate content |
| **`TAG`** | `define-tags`, `assign-tags` | curate the tag vocabulary + apply tags |
| **`GRANT`** | `assign-roles` | assign existing roles to members (level `30`+ only) |

> **Grant-by-bucket philosophy.** You pick a **level** + the **categories** within its ceiling, then
> refine **downward** via `deniedActions`. There is no à-la-carte "grant just `update`" — the only
> grant shape is a category, and the only refinement is a strict subtraction. This is what keeps a
> denied action from ever being silently re-granted through a second path.

---

## Endpoints

### List role definitions

Return a page of the roles visible for this team — the immutable **system** ladder plus **this
team's** custom roles. The page is subject-relative in the usual sense: `count` is the total across
all pages, never `items.length`. Rows are ordered `createdAt ASC, id ASC`.

```http
GET /api/v1/teams/{teamId}/role-definitions?page=0&perPage=20
```

**Path parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `teamId` | string (uuid) | Yes | The team whose roles to list (its custom roles + the system ladder). |

**Query parameters**:

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `page` | integer | No | `0` | 0-based page index. A bounds violation is `400 VALIDATION_FAILED` — no clamping. |
| `perPage` | integer | No | `20` | Page size, 1–100. A bounds violation is `400 VALIDATION_FAILED` — no clamping. |

**Response** (`200 OK`, `RoleDefinitionPage`):

```json
{
  "count": 3,
  "page": 0,
  "perPage": 20,
  "items": [
    {
      "code": "reader",
      "system": true,
      "teamId": null,
      "roleLevel": 10,
      "attributes": { "role_level": 10 },
      "permissions": { "catalog": ["READ"] },
      "deniedActions": {},
      "requiredTags": {},
      "matchMode": null
    },
    {
      "code": "member",
      "system": true,
      "teamId": null,
      "roleLevel": 20,
      "attributes": { "role_level": 20 },
      "permissions": { "catalog": ["READ", "WRITE", "TAG"] },
      "deniedActions": {},
      "requiredTags": {},
      "matchMode": null
    },
    {
      "code": "demo-editor",
      "system": false,
      "teamId": "b7c1e2f3-4a5b-4c6d-8e9f-0a1b2c3d4e5f",
      "roleLevel": 20,
      "attributes": { "role_level": 20 },
      "permissions": { "catalog": ["READ", "WRITE", "TAG"] },
      "deniedActions": { "catalog": ["delete"] },
      "requiredTags": {},
      "matchMode": null
    }
  ]
}
```

- A **system** row has `system: true` and `teamId: null`; a **custom** row has `system: false` and a
  concrete `teamId`. Both appear in a team's list.
- `demo-editor` above is a member-level custom role that grants `READ, WRITE, TAG` on `catalog` but
  **subtracts** the `delete` fine action — so its holder can create and update products but not
  delete them. That is the grant-by-bucket-then-refine pattern in one row.

**Authorization**: any member of the team may list its roles. A non-member is `403 ACCESS_DENIED`.

**cURL**:

```bash
curl -s "http://localhost:8080/api/v1/teams/$TEAM_ID/role-definitions?page=0&perPage=20" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/json"
```

---

### Create role definition

Define a **team-scoped custom role**. Owner-only, and subject to the full
[authoring contract](#the-authoring-contract).

```http
POST /api/v1/teams/{teamId}/role-definitions
```

**Path parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `teamId` | string (uuid) | Yes | The team the new role is scoped to. |

**Request body** (`RoleDefinitionRequest`):

| Field | Type | Required | Constraints |
|-------|------|----------|-------------|
| `code` | string | Yes | 1–100 chars. Unique within this team's scope (a duplicate is `409 ROLE_CODE_CONFLICT`). |
| `roleLevel` | integer | **Yes** | The ladder tier: `10` / `20` / `25` / `30`. **Required, enforced as `422 ROLE_DEFINITION_INVALID`** (not a schema constraint — see the [authoring contract](#the-authoring-contract)). `40` (owner) is never authorable. Bounds the grantable categories and is the single source of `attributes.role_level`. |
| `permissions` | object | Yes | `resourceType → [categories]`. Only the four category tokens (`READ`/`WRITE`/`TAG`/`GRANT`), each within the `roleLevel` ceiling. |
| `attributes` | object | No | Extensible role attributes. `role_level` is always overwritten from `roleLevel`. |
| `deniedActions` | object | No | `resourceType → [fine actions]` subtracted after category expansion. Each denied action must be granted by that type's categories (strict subtraction). |
| `requiredTags` | object | No | `tagKey → [acceptable values]` — an optional resource-tag requirement. |
| `matchMode` | string | No | `ANY_OF` \| `ALL_OF` — how required keys combine. `null` when there is no tag requirement. |

**Example — a custom `reader` (read-only on the catalog domain)**:

```json
{
  "code": "reader",
  "roleLevel": 10,
  "permissions": { "catalog": ["READ"] }
}
```

**Example — a `demo-editor` (read + write + tag, minus delete)**:

```json
{
  "code": "demo-editor",
  "roleLevel": 20,
  "permissions": { "catalog": ["READ", "WRITE", "TAG"] },
  "deniedActions": { "catalog": ["delete"] }
}
```

The `reader` example grants exactly `{view, list}` on `catalog`. The `demo-editor` example grants
`READ, WRITE, TAG` (which expands to `{view, list, create, update, delete, define-tags, assign-tags}`)
and then **subtracts** `delete` — leaving a role that can do everything a member can except delete
content. Both stay inside their level's ceiling.

**Response** (`201 Created`, `RoleDefinition`): the stored role, with `system: false`, the resolved
`teamId`, and `attributes.role_level` normalized from `roleLevel`:

```json
{
  "code": "demo-editor",
  "system": false,
  "teamId": "b7c1e2f3-4a5b-4c6d-8e9f-0a1b2c3d4e5f",
  "roleLevel": 20,
  "attributes": { "role_level": 20 },
  "permissions": { "catalog": ["READ", "WRITE", "TAG"] },
  "deniedActions": { "catalog": ["delete"] },
  "requiredTags": {},
  "matchMode": null
}
```

**Authorization**: **owner-only.** Administrators (and everyone below) get `403 ACCESS_DENIED` —
authoring role definitions is the fenced, non-delegable capability that shapes the access ladder.

**cURL**:

```bash
curl -s -X POST "http://localhost:8080/api/v1/teams/$TEAM_ID/role-definitions" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
        "code": "demo-editor",
        "roleLevel": 20,
        "permissions": { "catalog": ["READ", "WRITE", "TAG"] },
        "deniedActions": { "catalog": ["delete"] }
      }'
```

---

### Update role definition

Replace a **team-scoped custom role** by its `code`. Owner-only; same
[authoring contract](#the-authoring-contract) as create. **System roles are immutable** — updating
one is rejected.

```http
PUT /api/v1/teams/{teamId}/role-definitions/{code}
```

**Path parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `teamId` | string (uuid) | Yes | The team the role is scoped to. |
| `code` | string | Yes | The role's code within this team. |

**Request body** (`RoleDefinitionUpdate`) — same fields as create **minus `code`** (the code is in
the path):

| Field | Type | Required | Constraints |
|-------|------|----------|-------------|
| `roleLevel` | integer | **Yes** | Same contract as on create — the tier and the single source of `attributes.role_level` (`422 ROLE_DEFINITION_INVALID` if absent or `40`). |
| `permissions` | object | Yes | `resourceType → [categories]`, ceiling-bounded. |
| `attributes` | object | No | `role_level` always overwritten from `roleLevel`. |
| `deniedActions` | object | No | Deny-overrides — must strictly subtract from the granted expansion. |
| `requiredTags` | object | No | `tagKey → [acceptable values]`. |
| `matchMode` | string | No | `ANY_OF` \| `ALL_OF`; `null` when no tag requirement. |

```json
{
  "roleLevel": 20,
  "permissions": { "catalog": ["READ", "WRITE", "TAG"] },
  "deniedActions": { "catalog": ["delete"] }
}
```

**Response** (`200 OK`, `RoleDefinition`): the updated role.

**Authorization**: **owner-only** → `403 ACCESS_DENIED` otherwise. A missing custom role is
`404 RESOURCE_NOT_FOUND`; targeting a **system** role is `409 ROLE_IMMUTABLE` (the system ladder
cannot be edited).

**cURL**:

```bash
curl -s -X PUT "http://localhost:8080/api/v1/teams/$TEAM_ID/role-definitions/demo-editor" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
        "roleLevel": 20,
        "permissions": { "catalog": ["READ", "WRITE", "TAG"] },
        "deniedActions": { "catalog": ["delete"] }
      }'
```

---

### Delete role definition

Delete a **team-scoped custom role** by its `code`. Owner-only. **System roles are immutable** and
cannot be deleted.

```http
DELETE /api/v1/teams/{teamId}/role-definitions/{code}
```

**Path parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `teamId` | string (uuid) | Yes | The team the role is scoped to. |
| `code` | string | Yes | The role's code within this team. |

**Response**: `204 No Content` — empty body on success. (The spec declares an empty
`application/json` on the 204 only so content negotiation admits a bare `Accept: application/json`;
no representation is produced.)

**Authorization**: **owner-only** → `403 ACCESS_DENIED` otherwise. A missing custom role is
`404 RESOURCE_NOT_FOUND`; targeting a **system** role is `409 ROLE_IMMUTABLE`. A role still bound to
members may be rejected `409 STATE_CONFLICT`.

**cURL**:

```bash
curl -s -o /dev/null -w '%{http_code}\n' \
  -X DELETE "http://localhost:8080/api/v1/teams/$TEAM_ID/role-definitions/demo-editor" \
  -H "Authorization: Bearer $TOKEN"
```

---

## Cross-cutting behavior

### The authoring contract

Every create and update is validated against one **authoring contract**, enforced uniformly as
`422 ROLE_DEFINITION_INVALID`. It is expressed as a domain rule (a `422`), deliberately **not** as
schema constraints, so the whole contract answers with one code a client can branch on:

1. **`roleLevel` is required.** Absent → `422`. (It is not a schema-required field precisely so this
   answer is uniform with the rest of the contract, rather than a generic schema `400`.)
2. **Categories only, within the ceiling.** `permissions` values accept only the four category tokens
   (`READ`/`WRITE`/`TAG`/`GRANT`). A token outside the `roleLevel` ceiling → `422`. In particular
   **`GRANT` is only valid at level `30`+**.
3. **`owner` (40) is never authorable.** `roleLevel: 40` → `422`.
4. **`deniedActions` must strictly subtract.** Each denied fine action must be one the granted
   categories actually expand to for that resource type. A denied action that was never granted →
   `422` (there is nothing to subtract, so the request is meaningless and fails closed).

The philosophy is **grant by bucket, then refine downward**: pick a level, pick categories inside its
ceiling, subtract fine actions with `deniedActions`. You never grant an individual action, and a
subtraction can only remove something that was actually granted.

### System roles are immutable

The five-tier ladder (`reader`/`member`/`senior`/`administrator`/`owner`) is **system** (`system:
true`, `teamId: null`) and seeded once. It appears in every team's list but cannot be created,
updated, or deleted through the API — an attempt on a system role is a `409 ROLE_IMMUTABLE`. Custom
roles live alongside it, scoped to one team, and are the only roles these mutating endpoints touch.

### Errors

Every error is `application/problem+json` — an RFC-7807 [`ProblemDetail`](#problemdetail). Branch on
the machine-stable `errorCode`, never the human `detail`.

| Status | `errorCode` | When |
|--------|-------------|------|
| `400` | `VALIDATION_FAILED` | Malformed request — e.g. `page`/`perPage` out of bounds (no clamping). |
| `403` | `ACCESS_DENIED` | The caller is not the owner (create/update/delete), or not a team member (list). |
| `404` | `RESOURCE_NOT_FOUND` | No such custom role at this `code` in this team. |
| `409` | `ROLE_CODE_CONFLICT` | A custom role with this `code` already exists in the team's scope (create). |
| `409` | `ROLE_IMMUTABLE` | The target is a **system** role — the ladder cannot be edited or deleted. |
| `409` | `STATE_CONFLICT` | A state conflict, e.g. deleting a role still bound to members. |
| `422` | `ROLE_DEFINITION_INVALID` | The [authoring contract](#the-authoring-contract) was violated (missing/`40` `roleLevel`, an out-of-ceiling category, `GRANT` below `30`, or a non-subtracting `deniedActions`). |

#### Error: a 422 for granting `GRANT` below level 30

`GRANT` (the assign-roles category) tops out at **administrator** (level `30`). Requesting it on a
member-level role violates the ceiling rule — a request like:

```json
{
  "code": "sneaky-editor",
  "roleLevel": 20,
  "permissions": { "catalog": ["READ", "WRITE", "GRANT"] }
}
```

is rejected fail-closed with `422 ROLE_DEFINITION_INVALID`:

```json
{
  "type": "/problems/role-definition-invalid",
  "title": "Role definition invalid",
  "status": 422,
  "detail": "Category 'GRANT' is not grantable at roleLevel 20 (GRANT requires level 30+).",
  "instance": "/api/v1/teams/b7c1e2f3-4a5b-4c6d-8e9f-0a1b2c3d4e5f/role-definitions",
  "errorCode": "ROLE_DEFINITION_INVALID",
  "timestamp": "2026-06-09T10:15:30Z"
}
```

This is the anti-escalation ceiling made concrete: a member-tier role can never carry the power to
assign roles. The same code answers a missing `roleLevel`, a `roleLevel: 40`, and a
`deniedActions` entry that does not subtract from the granted expansion — one contract, one code.

---

## Schema reference

Exact field lists come from the [OpenAPI spec](../../example-user-management-service/src/main/resources/openapi/user-mgmt-api.yaml);
`readOnly`/server-derived fields are never accepted meaningfully on input.

### RoleDefinition

```typescript
{
  code: string;
  system: boolean;         // true = immutable global ladder; false = team-scoped custom role
  teamId?: string | null;  // uuid; null for system roles, set for custom roles
  roleLevel?: number | null; // ladder tier (10/20/25/30/40), read from attributes.role_level
  attributes?: {           // extensible; role_level is always overwritten from roleLevel
    [key: string]: unknown;
  };
  permissions: {           // resourceType -> [category tokens: READ | WRITE | TAG | GRANT]
    [resourceType: string]: string[];
  };
  deniedActions?: {        // deny-overrides: resourceType -> [fine actions], subtracted last
    [resourceType: string]: string[];
  };
  requiredTags?: {         // optional tag requirement: tagKey -> [acceptable values]
    [tagKey: string]: string[];
  };
  matchMode?: "ANY_OF" | "ALL_OF" | null; // how required keys combine; null when no tag requirement
}
```

Required: `code`, `system`, `permissions`.

### RoleDefinitionRequest

```typescript
{
  code: string;            // 1–100 chars, unique within the team scope
  roleLevel: number;       // REQUIRED (enforced as 422 ROLE_DEFINITION_INVALID); 10/20/25/30 — 40 never authorable
  permissions: {           // resourceType -> [category tokens], ceiling-bounded; GRANT only at 30+
    [resourceType: string]: string[];
  };
  attributes?: {           // role_level always overwritten from roleLevel
    [key: string]: unknown;
  };
  deniedActions?: {        // must strictly subtract from the granted category expansion
    [resourceType: string]: string[];
  };
  requiredTags?: {
    [tagKey: string]: string[];
  };
  matchMode?: "ANY_OF" | "ALL_OF" | null;
}
```

Required: `code`, `permissions`. (`roleLevel` is required by the **authoring contract** — a `422`,
not a schema `400`.)

### RoleDefinitionUpdate

```typescript
{
  roleLevel: number;       // same contract as RoleDefinitionRequest (422 if absent or 40)
  permissions: {
    [resourceType: string]: string[];
  };
  attributes?: {
    [key: string]: unknown;
  };
  deniedActions?: {
    [resourceType: string]: string[];
  };
  requiredTags?: {
    [tagKey: string]: string[];
  };
  matchMode?: "ANY_OF" | "ALL_OF" | null;
}
```

Required: `permissions` (`code` is taken from the path; `roleLevel` required by the authoring
contract).

### RoleDefinitionPage

```typescript
{
  count: number;    // int64 — total rows the caller may see across all pages (subject-relative)
  page: number;     // 0-based, echoes the request
  perPage: number;  // 1–100, echoes the request
  items: RoleDefinition[];
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
  errorCode:          // the machine-stable code a consumer branches on (the service's full union)
    | "ACCESS_DENIED"
    | "RESOURCE_NOT_FOUND"
    | "VALIDATION_FAILED"
    | "ROLE_SUBSET_VIOLATION"
    | "TEAM_TARGET_EXISTS"
    | "MEMBERSHIP_CONFLICT"
    | "ROLE_CODE_CONFLICT"
    | "ROLE_IMMUTABLE"
    | "TAG_KEY_CONFLICT"
    | "TAG_DEFINITION_IMMUTABLE"
    | "TAG_DEFINITION_INVALID"
    | "ROLE_DEFINITION_INVALID"
    | "STATE_CONFLICT";
  timestamp?: string; // date-time — when the error was produced (correlation)
}
```

Required: `status`, `errorCode`.

---

## Related documentation

- [Membership API](membership-api.md) — the grant that binds a user to one of these roles on a team
  (add / change-role / remove, under the assignment gates).
- [Tag Definition API](tag-definition-api.md) — the dynamic tag dictionary behind a role's
  `requiredTags` and the `TAG` category's `define-tags`/`assign-tags`.
- [Team API](team-api.md) — teams and owner-on-create; being the owner is what unlocks authoring
  these role definitions.
- [`docs/api/` conventions](README.md) — how these reference pages are written and kept in sync.
- ADR [[0007-coarse-grained-permission-categories|0007]] — the coarse permission categories, the
  five-tier ceiling ladder, and the deny-override model.
- ADR [[0003-role-definitions-role-not-grant|0003]] — role ≠ grant, system + team-scoped roles, and
  owner-only authoring.
- The OpenAPI spec — [`user-mgmt-api.yaml`](../../example-user-management-service/src/main/resources/openapi/user-mgmt-api.yaml)
  — is the codegen source of truth; Swagger UI at `/swagger-ui.html` renders it live.
