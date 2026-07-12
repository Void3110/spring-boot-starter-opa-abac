---
tags:
  - status/active
  - type/reference
  - area/api
  - audience/developer
---

# Team API

REST endpoints for the **Team** resource of the user-management service — the durable owner of a
resource via its **team-target**, and the anchor of the whole ABAC subject side.

---

## Overview

A **team governs a target resource.** Every team names a *team-target* — a `targetType` and a
`targetId` — and the team is the durable owner of *that* resource. In this demo the target is a
catalog: `targetType: "catalog"` paired with the catalog's `id`. Membership in the governing team is
**the access path** to the target: a caller's effective role on the catalog is resolved from their
role on the team that governs it (see [[TEAM-BASED-AUTHORIZATION]]), and the catalog spine reads that
resolved role through the role-definition supplier this service feeds.

The Team resource is deliberately small — four endpoints — because a team's *interesting* surface is
its **members** and its **roles**, which live on their own pages:

- who belongs and at what role → [`membership-api.md`](membership-api.md);
- what roles the team offers → [`role-definition-api.md`](role-definition-api.md).

This page covers listing, creating, and reading teams, plus the one control-plane mutation the team
object owns directly: **transfer-ownership**.

Two properties make this resource distinctive and are worth reading before the endpoints:

- **Owner-on-create.** Creating a team atomically makes the creator its **owner** — a team is never
  born ownerless. This is a bootstrap: there is no governing team to authorize the create against yet,
  so `POST /teams` is one of the deliberately un-gated bootstrap mutations noted in the
  [API index](README.md#authentication).
- **The owner is protected.** The owner role is never assigned or removed through the ordinary
  role-change path — [transfer-ownership](#transfer-ownership) is the *only* way to move ownership,
  owner-only and self-transfer-forbidden.

**Base Path**: `/api/v1/teams`

All requests reach the service **through the APISIX gateway** with a bearer token; the shared
authentication, error contract (`application/problem+json` with a typed `errorCode`), pagination
envelope, and `_actions` conventions are defined once in the [API index](README.md) and assumed here.
Each endpoint below calls out only where it deviates.

---

## Endpoints

| Method & path | Operation | Gated? |
|---------------|-----------|--------|
| `GET /api/v1/teams` | [List teams](#list-teams) | Bearer-only read |
| `POST /api/v1/teams` | [Create a team (owner-on-create)](#create-a-team-owner-on-create) | Un-gated bootstrap |
| `GET /api/v1/teams/{teamId}` | [Get a team](#get-a-team) | Un-gated bootstrap read |
| `POST /api/v1/teams/{teamId}/transfer-ownership` | [Transfer ownership](#transfer-ownership) | Owner only |

---

### List teams

Return a page of teams, or resolve the single team governing a given target.

```http
GET /api/v1/teams
```

**Query parameters**:

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `targetType` | string | No | — | Exact-match filter on the team-target's resource type (e.g. `catalog`). Paired with `targetId`. |
| `targetId` | uuid | No | — | Exact-match filter on the team-target's resource id. Paired with `targetType`. |
| `page` | integer | No | `0` | 0-based page index. A bounds violation is `400 VALIDATION_FAILED` (no clamping). |
| `perPage` | integer | No | `20` | Page size, `1`–`100`. A bounds violation is `400 VALIDATION_FAILED` (no clamping). |

The `targetType`/`targetId` pair is **all-or-nothing** — it is how you answer "which team governs this
catalog?":

- **Both present** → the governing team as a one-item page (`count` `0` or `1`; an unmatched pair is an
  empty page, **never `404`**).
- **Both absent** → the full paged list, unchanged.
- **Exactly one present** → `400 VALIDATION_FAILED` — never a silent fallthrough to the full list.

**Response** (`200 OK`) — the shared page envelope of `Team` objects:

```json
{
  "count": 1,
  "page": 0,
  "perPage": 20,
  "items": [
    {
      "id": "7c9e6a41-2b3d-4e5f-8a90-1c2d3e4f5a6b",
      "name": "Demo team",
      "targetType": "catalog",
      "targetId": "1f0c3c2a-8b7e-4d21-9a10-6e5b4c3d2a11",
      "_actions": {
        "list-members": true,
        "add-member": true,
        "remove-member": true
      }
    }
  ]
}
```

**On `_actions` here:** the team affordance map carries the **OPA-decidable control-plane subset only**
(`list-members`, `add-member`, `remove-member`). The escalation verbs that are *co-gated in Java* — the
owner-protected paths such as changing a member to a higher tier, or ownership transfer — are
deliberately **excluded**: OPA alone cannot decide them, so advertising them would over-promise. As
always, `_actions` is advisory read-side convenience and **absent means "could not compute"**, never
"you can do nothing" — see [Action enrichment](README.md#action-enrichment-_actions). Note that the
[single-team read](#get-a-team) omits the map entirely; see below.

**Status codes**: `200`, `400` (`VALIDATION_FAILED` — pagination bounds, or a half-supplied
target filter pair).

**Authentication**: bearer-only. Listing teams reveals which teams exist for a target; it grants
nothing — acting on a team still goes through the membership gate.

**cURL**:

```bash
# Full paged list
curl -s "http://localhost:8080/api/v1/teams?page=0&perPage=20" \
  -H "Authorization: Bearer $TOKEN"

# "Which team governs this catalog?" — the all-or-nothing target filter
curl -s "http://localhost:8080/api/v1/teams?targetType=catalog&targetId=1f0c3c2a-8b7e-4d21-9a10-6e5b4c3d2a11" \
  -H "Authorization: Bearer $TOKEN"
```

---

### Create a team (owner-on-create)

Create a team for a target resource and, **atomically, make the creator its owner**.

```http
POST /api/v1/teams
```

**Request body** (`CreateTeamRequest`):

```json
{
  "name": "Demo team",
  "targetType": "catalog",
  "targetId": "1f0c3c2a-8b7e-4d21-9a10-6e5b4c3d2a11",
  "creatorUserId": null
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `name` | string | Yes | Human name for the team (1–200 chars). |
| `targetType` | string | Yes | The governed resource's type (1–100 chars), e.g. `catalog`. |
| `targetId` | uuid | Yes | The governed resource's id. |
| `creatorUserId` | uuid \| null | No | **Explicit creator fallback**, used only until token-derived identity is wired. When the request is authenticated the creator is the token subject and this field is **ignored**. |

The creator becomes the team **owner** in the same transaction — a team is never created ownerless.
The creator is taken from the authenticated subject when present; `creatorUserId` exists so the demo
can name a creator before token-derived identity is fully wired, and is ignored once it is.

**Response** (`201 Created`) — the new `Team`. See the note below on `_actions` immediately after
create.

```json
{
  "id": "7c9e6a41-2b3d-4e5f-8a90-1c2d3e4f5a6b",
  "name": "Demo team",
  "targetType": "catalog",
  "targetId": "1f0c3c2a-8b7e-4d21-9a10-6e5b4c3d2a11"
}
```

**Status codes**: `201`; `400 VALIDATION_FAILED` (malformed body); `403 ACCESS_DENIED`;
`409 TEAM_TARGET_EXISTS` (a team already governs that `targetType`/`targetId` — one target has exactly
one governing team).

**Authentication**: this is one of the **un-gated bootstrap mutations**. There is no governing team to
authorize the create against yet — the create is what brings one into being — so it is not behind the
membership gate. It is the exception, not the rule; see the [API index](README.md#authentication).

**cURL** — the *editor* persona creates the Demo team over a catalog:

```bash
curl -s -X POST "http://localhost:8080/api/v1/teams" \
  -H "Authorization: Bearer $EDITOR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Demo team",
    "targetType": "catalog",
    "targetId": "1f0c3c2a-8b7e-4d21-9a10-6e5b4c3d2a11"
  }'
```

---

### Get a team

Read a single team by id.

```http
GET /api/v1/teams/{teamId}
```

**Path parameters**:

| Parameter | Type | Description |
|-----------|------|-------------|
| `teamId` | uuid | The team's id. |

**Response** (`200 OK`) — a `Team`. Note there is **no `_actions` map**:

```json
{
  "id": "7c9e6a41-2b3d-4e5f-8a90-1c2d3e4f5a6b",
  "name": "Demo team",
  "targetType": "catalog",
  "targetId": "1f0c3c2a-8b7e-4d21-9a10-6e5b4c3d2a11"
}
```

> **Why `_actions` is omitted here — the documented, correct degrade.** `getTeam` is an **un-gated
> bootstrap read** (it pairs with owner-on-create so a freshly created team is always readable). Because
> the read does not run the authorization path, the affordance enrichment **cache-misses**, and the
> advice is to **omit the map, never fabricate one**. An absent `_actions` means "we could not compute
> the affordances", not "you may do nothing" — a fabricated all-false map would be a positive lie. The
> list endpoint, which does run enrichment, is where a populated `_actions` appears. This is the
> intended behavior, not a bug — see [Action enrichment](README.md#action-enrichment-_actions).

**Status codes**: `200`; `404 RESOURCE_NOT_FOUND`.

**Authentication**: bearer-only, un-gated bootstrap read.

**cURL**:

```bash
curl -s "http://localhost:8080/api/v1/teams/7c9e6a41-2b3d-4e5f-8a90-1c2d3e4f5a6b" \
  -H "Authorization: Bearer $TOKEN"
```

---

### Transfer ownership

Move ownership of the team from the current owner to another **existing member**. This is the **only**
way to change who owns a team — the owner role is protected and is never assigned or removed through
the ordinary [role-change path](membership-api.md).

```http
POST /api/v1/teams/{teamId}/transfer-ownership
```

**Path parameters**:

| Parameter | Type | Description |
|-----------|------|-------------|
| `teamId` | uuid | The team whose ownership is being transferred. |

**Request body** (`TransferOwnershipRequest`):

```json
{
  "newOwnerUserId": "b2c3d4e5-6f70-4a81-9b2c-3d4e5f6a7b80"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `newOwnerUserId` | uuid | Yes | The **team member** to promote to owner. |

**Semantics** — a single atomic swap:

- The named member is **promoted to owner**.
- The former owner is **downgraded** (they do not leave the team; they cease to be its owner).

**Constraints**:

- **Owner only.** Only the current owner may transfer ownership; any other caller is `403`.
- **No self-transfer.** The current owner cannot name themselves as `newOwnerUserId`.
- A distinct endpoint from role-change **by design**: because the owner is protected, moving ownership
  is not expressible as an ordinary member role change — it has its own owner-gated verb.

**Response** (`204 No Content`) — success, no body.

**Status codes**: `204`; `403 ACCESS_DENIED` (a non-owner attempted the control-plane action, or a
self-transfer); `404 RESOURCE_NOT_FOUND` (unknown team, or the named user is not a member).

**Authentication**: owner-only. This is a control-plane mutation gated on the caller being the current
owner — the classic case where a `403` distinguishes "you are a member but not the owner" from a mere
read.

**cURL** — the *editor* (current owner) hands the Demo team to another member:

```bash
curl -s -X POST \
  "http://localhost:8080/api/v1/teams/7c9e6a41-2b3d-4e5f-8a90-1c2d3e4f5a6b/transfer-ownership" \
  -H "Authorization: Bearer $EDITOR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{ "newOwnerUserId": "b2c3d4e5-6f70-4a81-9b2c-3d4e5f6a7b80" }'
```

If the caller is a member but **not** the owner (e.g. the *viewer* persona), the same request is
rejected fail-closed:

```json
{
  "type": "/problems/access-denied",
  "title": "Access denied",
  "status": 403,
  "detail": "Only the current owner may transfer ownership of this team.",
  "instance": "/api/v1/teams/7c9e6a41-2b3d-4e5f-8a90-1c2d3e4f5a6b/transfer-ownership",
  "errorCode": "ACCESS_DENIED",
  "timestamp": "2026-06-09T10:15:30Z"
}
```

---

## Ownership at a glance

| What you want to do | Endpoint | Who may do it |
|---------------------|----------|---------------|
| Create a team and become its owner | `POST /teams` | Any authenticated caller (bootstrap) |
| Move ownership to another member | `POST /teams/{teamId}/transfer-ownership` | The current owner only |
| Change a *non-owner* member's role | `PUT /teams/{teamId}/members/{userId}` → [membership-api.md](membership-api.md) | Owner / administrator / senior, under the assignment gates |
| Add or remove a member | `POST` / `DELETE /teams/{teamId}/members/{userId}` → [membership-api.md](membership-api.md) | Owner / administrator / senior, under the target-tier gate |

The owner is never reachable through the members row — that is the whole point of a separate,
owner-gated transfer verb.

---

## Schema reference

### `Team`

```typescript
{
  id:         string;   // uuid, read-only (server-assigned)
  name:       string;
  targetType: string;   // the governed resource's type, e.g. "catalog"
  targetId:   string;   // uuid — the governed resource's id
  _actions?:  { [verb: string]: boolean };  // read-only, server-emitted; the OPA-decidable
                                            // control-plane subset only. ABSENT on the un-gated
                                            // getTeam read (omit, never fabricate).
}
```

`id` is `readOnly` (server-assigned). `_actions` is `readOnly` (server-emitted; ignored on input) and
**optional** — present only where enrichment ran; see the [get-a-team](#get-a-team) note.

### `CreateTeamRequest`

```typescript
{
  name:           string;          // 1–200 chars
  targetType:     string;          // 1–100 chars, e.g. "catalog"
  targetId:       string;          // uuid
  creatorUserId?: string | null;   // uuid — explicit creator fallback; ignored when authenticated
}
```

### `TransferOwnershipRequest`

```typescript
{
  newOwnerUserId: string;  // uuid — the existing member to promote to owner
}
```

### `ProblemDetail` (error body)

Every error is `application/problem+json`; the shape and the branch-on-`errorCode` rule are defined in
the [API index](README.md#error-contract-rfc-7807-problemjson). The codes this resource emits:

| `errorCode` | Status | When |
|-------------|--------|------|
| `VALIDATION_FAILED` | 400 | Malformed body, bad pagination bounds, or a half-supplied `targetType`/`targetId` filter pair. |
| `ACCESS_DENIED` | 403 | A non-owner attempted transfer-ownership (or the owner attempted a self-transfer); a denied create. |
| `RESOURCE_NOT_FOUND` | 404 | Unknown `teamId`, or a `newOwnerUserId` that is not a member of the team. |
| `TEAM_TARGET_EXISTS` | 409 | A team already governs that `targetType`/`targetId` (one target, one governing team). |

---

## Related documentation

- [API index](README.md) — the cross-cutting conventions (authentication, error contract, pagination
  envelope, `_actions`) every endpoint here assumes.
- [Membership API](membership-api.md) — add / change-role / remove members; the ordinary role-change
  path (from which the protected owner is excluded).
- [Role Definition API](role-definition-api.md) — the system role ladder and team-scoped custom roles a
  member can hold.
- [User API](user-api.md) — user profiles and the identity-directory search that finds the subjects you
  add to a team.
- [[TEAM-BASED-AUTHORIZATION]] — how a caller's effective role on the target resource is resolved from
  live team membership.
- ADR [[0016-action-enrichment-affordance-metadata|0016]] — the `_actions` affordance mechanism and the
  omit-never-fabricate degrade.

---

## Source of truth

This page is the narrative layer. The authoritative, machine-checked contract is the OpenAPI spec
`example-user-management-service/src/main/resources/openapi/user-mgmt-api.yaml` (the codegen source —
drift is a build break) and the running service's **Swagger UI** at
[`/swagger-ui.html`](http://localhost:8080/swagger-ui.html). When this page and the spec disagree, the
spec wins.
