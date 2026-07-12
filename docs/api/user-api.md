---
tags:
  - status/active
  - type/reference
  - area/api
  - audience/developer
created: 2026-07-13
updated: 2026-07-13
---

# User API

REST reference for the **User** resource and the **identity-directory search** on the
example `user-management-service`. A *user* here is a **provisioned profile** — a row that
links an IdP subject (Keycloak `sub`) to a renderable display name. That profile is the
**join key** team memberships bind to: you can only add someone to a team once a profile
exists for their subject.

Two distinct surfaces live under `/api/v1/users`, and keeping them apart is the whole point
of this page:

- **The provisioned list** (`GET /users`, `POST /users`, `GET /users/{userId}`) — the
  profiles this service *owns*, returned in the shared page envelope.
- **The identity-directory search** (`GET /users/search`) — a read-through into the IdP's
  full account list (**every realm account**, not just provisioned ones), returned as a
  bounded plain list. This is how the SPA finds a person who has never logged in, so it can
  provision them.

---

## Overview

The `user-management-service` owns the subject side of the ABAC demo: user profiles, teams,
memberships, role definitions, and the tag dictionary. This page covers only the **User**
resource and the **directory search**; teams and memberships are documented in
[team-api.md](team-api.md) and [membership-api.md](membership-api.md).

**Base Path**: `/api/v1/users`

**Authentication**: All endpoints are **bearer-only** — a valid OIDC access token (issued by
Keycloak, terminated at the gateway) is required, but neither listing/reading profiles nor
searching the directory is gated by an ABAC policy. Finding or reading a user **grants
nothing**: the authorization boundary for *acting* on a user (adding them to a team, changing
their role) lives on the team-membership gates in [membership-api.md](membership-api.md), not
here.

**Errors**: failures use `application/problem+json` (RFC 7807). A client branches on the
machine-stable `errorCode`, never on the human `detail`. See
[Error model](#error-model) below.

---

## Endpoints

| Method & path | Operation | Returns |
|---|---|---|
| `GET /api/v1/users` | List **provisioned** profiles (paged) | `UserPage` |
| `POST /api/v1/users` | Provision a profile | `User` |
| `GET /api/v1/users/{userId}` | Get one profile | `User` |
| `GET /api/v1/users/search` | Search the **identity directory** | `DirectoryUserList` |

---

### List Users

List the **provisioned** profiles — the rows this service owns — in the shared page envelope.
This is *not* the directory: an account that exists in Keycloak but was never provisioned does
**not** appear here.

```http
GET /api/v1/users
```

**Query Parameters**:

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `subject` | string | No | — | Exact-match filter on the IdP subject. When present, the result is a one-item page (`count` 0 or 1) in the same envelope — an unmatched subject is an **empty page, never 404**. Absent or blank returns the full paged list. |
| `page` | integer | No | `0` | 0-based page index. A bounds violation is a `400 VALIDATION_FAILED` (**no clamping**). |
| `perPage` | integer | No | `20` | Page size, `1`–`100`. A bounds violation is a `400 VALIDATION_FAILED` (**no clamping**). |

**Response** (`200 OK`) — `UserPage` (a page envelope):

```json
{
  "count": 2,
  "page": 0,
  "perPage": 20,
  "items": [
    {
      "id": "b1e9c0a2-7c3d-4a1e-9f5b-2a6d8e0f1c34",
      "subject": "3f8a1d20-9b7e-4c11-8a2f-0e6d5c4b3a21",
      "displayName": "alice"
    },
    {
      "id": "c2fad1b3-8d4e-4b2f-a06c-3b7e9f102d45",
      "subject": "7c1b2a30-4d5e-4f61-9a8b-1c2d3e4f5a6b",
      "displayName": "carol"
    }
  ]
}
```

`count` is the total rows the caller may see **across all pages** (never `items.length`).
Rows are ordered `createdAt ASC, id ASC` — a fixed total order the client does not choose.

**cURL**:

```bash
# Full first page
curl -s "http://localhost:8080/api/v1/users" \
  -H "Authorization: Bearer $TOKEN"

# Exact-subject lookup (one-item page, or an empty page — never 404)
curl -s "http://localhost:8080/api/v1/users?subject=3f8a1d20-9b7e-4c11-8a2f-0e6d5c4b3a21" \
  -H "Authorization: Bearer $TOKEN"
```

---

### Create User (provision a profile)

Provision a profile for an IdP subject. This is the step that makes a Keycloak account
**usable** by the ABAC domain: until a profile exists, the subject cannot be a team member.
The SPA reaches this endpoint via the **provision-on-select** flow — search the directory,
pick an account, provision it (see [Workflow](#workflow-provision-on-select)).

```http
POST /api/v1/users
```

**Request Body** — `UserRequest`:

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `subject` | string | Yes | 1–255 chars | The IdP subject (Keycloak `sub`) to link. |
| `displayName` | string | Yes | 1–200 chars | A renderable name for the profile. |

```json
{
  "subject": "3f8a1d20-9b7e-4c11-8a2f-0e6d5c4b3a21",
  "displayName": "alice"
}
```

**Response** (`201 Created`) — `User`:

```json
{
  "id": "b1e9c0a2-7c3d-4a1e-9f5b-2a6d8e0f1c34",
  "subject": "3f8a1d20-9b7e-4c11-8a2f-0e6d5c4b3a21",
  "displayName": "alice"
}
```

The server assigns `id` (a read-only UUID). A profile is a 1:1 link to a subject, so
**provisioning the same subject twice is a conflict** — a duplicate provision is rejected with
a `409 Conflict` (`application/problem+json`) rather than creating a second profile. Treat an
already-provisioned subject as success-equivalent: the SPA's provision-on-select is
idempotent-by-intent (it provisions only when the picked subject has no profile yet).

**cURL**:

```bash
curl -s -X POST "http://localhost:8080/api/v1/users" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "subject": "3f8a1d20-9b7e-4c11-8a2f-0e6d5c4b3a21",
    "displayName": "alice"
  }'
```

---

### Get User

Get a single provisioned profile by its profile `id` (the UUID from create/list — **not** the
IdP subject).

```http
GET /api/v1/users/{userId}
```

**Path Parameters**:

| Parameter | Type | Description |
|-----------|------|-------------|
| `userId` | string (uuid) | The profile id. |

**Response** (`200 OK`) — `User`; `404 RESOURCE_NOT_FOUND` (`application/problem+json`) when no
profile has that id.

**cURL**:

```bash
curl -s "http://localhost:8080/api/v1/users/b1e9c0a2-7c3d-4a1e-9f5b-2a6d8e0f1c34" \
  -H "Authorization: Bearer $TOKEN"
```

---

### Search the Identity Directory

Search the **identity directory** — the IdP's full account list (**every realm account**, not
just provisioned profiles) — through the library's `UserDirectory` port (ADR 0020). This is
how the SPA finds a teammate who has **never logged in**, so it can provision them.

```http
GET /api/v1/users/search
```

This surface is deliberately **not** the provisioned list, and deliberately **not** a page
envelope. Three properties define its contract:

- **Bounded plain list.** The response is `DirectoryUserList` — `{ items, limit }` with **no
  `count` and no page fields**. The directory has no cheap server-side cursor, so there is no
  honest total to report; you narrow by typing, not by paging.
- **Type-bounded disclosure ceiling.** Each result is a `DirectoryUser` exposing **exactly**
  `{ subject, displayName }` — never email, roles, or attributes. The privacy control is the
  *type*: no implementation can widen it. Finding an account discloses only that a username
  matched a fragment.
- **Fail-closed, no-oracle.** A blank `q`, zero matches, and a directory outage all return the
  **same** `200` with empty `items`. The response never reveals directory state (up/down, realm
  size); outage vs. genuine-empty differs only in an operator-facing WARN log. A blank/absent
  `q` returns empty **without consulting the directory** — the realm is not enumerable.

**Query Parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `q` | string | No | The search text (a username/name prefix or fragment). Blank or absent → empty `items`, no directory call. |
| `limit` | integer | No | Max rows to return. **Clamped**: absent or non-positive → `20`; anything above `50` → `50` (the hard max — the directory is never unbounded). The response echoes the effective value. |

**Response** (`200 OK`) — `DirectoryUserList`:

```json
{
  "items": [
    {
      "subject": "3f8a1d20-9b7e-4c11-8a2f-0e6d5c4b3a21",
      "displayName": "alice"
    }
  ],
  "limit": 20
}
```

`limit` echoes the **effective (clamped)** value the search actually ran with. `displayName`
is the account's username, falling back to the `subject` when the directory holds no name (so
it is always renderable).

**Empty result** (blank `q`, zero matches, or a directory outage — indistinguishable by
design):

```json
{
  "items": [],
  "limit": 20
}
```

**cURL**:

```bash
# Find accounts whose username matches "alice"
curl -s "http://localhost:8080/api/v1/users/search?q=alice&limit=20" \
  -H "Authorization: Bearer $TOKEN"

# limit is clamped server-side: this runs with limit=50, and the response echoes 50
curl -s "http://localhost:8080/api/v1/users/search?q=al&limit=999" \
  -H "Authorization: Bearer $TOKEN"
```

> **The directory module is optional.** The `UserDirectory` port ships in the starter with an
> always-empty `NoOpUserDirectory` default (**fail-closed**). The concrete Keycloak-admin
> implementation lives in the optional `opa-abac-keycloak-directory` module and is wired only
> when it is on the classpath **and** `opa.abac.directory.keycloak.enabled=true`. When the
> module or the flag is absent, `search` still returns `200` — with **empty `items`** every
> time. See ADR 0020 and the [User Directory guide](../guides/USER-DIRECTORY.md).

---

## Workflow: provision-on-select

The provisioned list can only offer people who already have a profile. To add a teammate who
has never logged in, the SPA searches the **directory**, then provisions the chosen account.
The directory search and the provisioning `POST` are two separate calls — the port only
*searches*; it never provisions or joins to the profile table.

```bash
#!/bin/bash
TOKEN="your-access-token"
BASE="http://localhost:8080/api/v1/users"

# 1. Search the identity directory for the person (e.g. "alice")
RESULT=$(curl -s "$BASE/search?q=alice&limit=20" \
  -H "Authorization: Bearer $TOKEN")

echo "Directory matches:"
echo "$RESULT" | jq '.items[] | {subject, displayName}'

# 2. Pick a subject from the results
SUBJECT=$(echo "$RESULT" | jq -r '.items[0].subject')
NAME=$(echo "$RESULT"    | jq -r '.items[0].displayName')

# 3. Provision a profile for the chosen account (idempotent-by-intent:
#    only provision when no profile exists yet — a duplicate is a 409)
curl -s -X POST "$BASE" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"subject\": \"$SUBJECT\", \"displayName\": \"$NAME\"}"

# 4. The profile's id is now the join key you pass to the membership API
#    (POST /api/v1/teams/{teamId}/members) to add the person to a team.
```

The profile `id` returned in step 3 is what the membership API binds to — see
[membership-api.md](membership-api.md).

---

## Error model

Errors are `application/problem+json` (RFC 7807): the standard members plus two documented
extensions, `errorCode` (the machine-stable code a client branches on) and `timestamp`.

```json
{
  "type": "/problems/validation-failed",
  "title": "Validation failed",
  "status": 400,
  "detail": "perPage must be between 1 and 100",
  "instance": "/api/v1/users",
  "errorCode": "VALIDATION_FAILED",
  "timestamp": "2026-07-13T10:00:00Z"
}
```

Codes relevant to this resource, drawn from the service-wide `errorCode` union:

| HTTP | `errorCode` | When |
|------|-------------|------|
| `400` | `VALIDATION_FAILED` | `page`/`perPage` out of bounds, or a malformed request body. |
| `404` | `RESOURCE_NOT_FOUND` | `GET /users/{userId}` for an unknown profile id. |
| `409` | `STATE_CONFLICT` | Provisioning a profile for a subject that already has one. |

> **Note.** `GET /users/search` has **no error path** by contract — every edge (blank `q`,
> zero matches, directory outage) is the same `200` with empty `items` (fail-closed,
> no-oracle). It never returns `4xx`/`5xx` for a search miss.

---

## Schema Reference

### User (`GET`/`POST` response)

```typescript
{
  id: string;          // UUID, read-only (server-assigned)
  subject: string;     // The IdP subject (Keycloak `sub`) this profile links to
  displayName: string;
}
```

### UserRequest (`POST` body)

```typescript
{
  subject: string;      // 1–255 chars — the Keycloak `sub` to link
  displayName: string;  // 1–200 chars
}
```

### DirectoryUserList (`GET /users/search` response)

```typescript
{
  items: DirectoryUser[];
  limit: number;        // The effective (clamped) limit this search ran with.
                        // Deliberately NO `count` and NO page fields — not a page envelope.
}
```

### DirectoryUser (one directory account)

```typescript
{
  subject: string;      // The IdP subject (Keycloak `sub`) — the join key a profile provisions against
  displayName: string;  // A renderable name (username; the subject when the directory holds none)
  // The disclosure ceiling: exactly these two fields — never email, roles, or attributes.
}
```

### UserPage (`GET /users` response)

```typescript
{
  count: number;    // Total rows the caller may see, across all pages (int64)
  page: number;     // 0-based page index, echoing the request
  perPage: number;  // Page size (1–100), echoing the request
  items: User[];
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

- [Team API](team-api.md) — teams (the durable owner of a resource via its team-target).
- [Membership API](membership-api.md) — the grant binding a provisioned user to a role on a
  team; the authorization boundary for acting on a user.
- [API reference index](README.md) — all written API references for the example services.
- [User Directory guide](../guides/USER-DIRECTORY.md) — how the `UserDirectory` port and the
  optional Keycloak module work.
- [ADR 0020 — Pluggable user-directory port](../architecture/adr/0020-user-directory-port.md)
  — the identity-search seam, its fail-closed / no-oracle contract, and the disclosure ceiling.
