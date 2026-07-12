---
tags:
  - status/active
  - type/reference
  - area/api
  - audience/developer
---

# Membership API

REST reference for **team membership** on the user-management-service — the grant that binds a user
to a role on a team. Membership is the ABAC *subject* side: adding a member grants access, removing
one revokes it, and the assignment gates keep delegation safe.

---

## Overview

A **membership** binds one `userId` to one team via a `roleCode` — the code of a role definition that
lives on that team (a **system** role like `owner`/`administrator`/`senior`/`member`/`reader`, or a
team-scoped **custom** role). The role definition, not the membership row, carries the permissions;
the membership is purely the *grant* of that role to that user (ADR [[0003-role-definitions-role-not-grant|0003]]).

**Membership is the sole access path to a team's governed resource.** A caller's effective role for a
catalog (and everything under it) is resolved from live team membership — there are no denormalized,
standalone grants. So:

- **Adding a member grants access** — the new `roleCode` immediately becomes that user's effective
  role for the team-target.
- **Removing a member revokes access** — every permission derived through that membership disappears
  the moment the row is deleted; the resolve path always re-derives from membership (never a stale
  cached grant).

Every mutating endpoint here is `@OpaPreAuthorize`-gated against the **calling** subject (the actor of
the grant, not the service identity — the confused-deputy guard), and every grant mutation decides
under the team-row lock so a concurrent demotion of the actor cannot slip between the check and the
write.

**Base Path**: `/api/v1/teams/{teamId}/members`

> **Personas used in the examples.** `editor` is the team **owner** (owner-on-create). `alice` is a
> directory user being granted access. `viewer` holds the `reader` role. `demo-editor` is a
> team-scoped **custom** role (no control-plane category). `outsider` is a subject with no membership
> on the team. These are illustrative names, not seeded fixtures.

---

## The assignment gates (read this before add / change-role / remove)

Three server-enforced gates bound *who* an actor may grant a role to and *to what tier* — this is the
"no self-escalation / seniority ceiling" contract (ADR [[0007-coarse-grained-permission-categories|0007]],
five-tier ladder; the control-plane categorization is ADR [[0015-control-plane-vocabulary-categorization|0015]]).
The tier ladder read from `attributes.role_level` is:

| Tier | `role_level` |
|------|--------------|
| `reader` | 10 |
| `member` | 20 |
| `senior` | 25 |
| `administrator` | 30 |
| `owner` | 40 |

1. **Cross-tier ceiling (everyone).** An actor may grant only a role **strictly below** their own
   tier — `actorLevel > candidateLevel`. An **administrator can assign any role strictly below
   administrator** but **cannot mint another administrator** (the seniority ceiling); the `owner`
   tier is never assignable here at all. A missing or non-numeric level on **either** side rejects
   (never "treat as 0 and pass").
2. **Senior subset (at tier 25 only).** A `senior` acting as actor may additionally only grant a role
   whose effective actions are a **subset** of the senior's own, per resource type — decided by OPA's
   `data.role.assignable` verdict over the raw role snapshots. Any OPA non-answer (error/timeout)
   rejects (fail-closed by indistinguishability).
3. **Target-tier gate (change-role / remove only).** When acting on an **existing** member, a target
   whose *current* tier is **above** the actor's cannot be demoted or removed by them — a `senior`
   cannot demote or remove an `administrator`. Peers stay manageable (an administrator can remove a
   peer administrator). The asymmetry is deliberate: an unreadable **target** level never outranks (a
   corrupted role must stay removable), while an unreadable **actor** level still rejects.

Every violation of gates 1–3 is the **one `422 ROLE_SUBSET_VIOLATION`** contract — the same code
whether it was the cross-tier compare, the senior subset verdict, the target-tier rule, or an OPA
outage during the verdict. See [[PERMISSION-MODEL]] and [[TEAM-BASED-AUTHORIZATION]] for the full
mechanism.

**Two fences sit outside the gates.** The `owner` role is **protected** — it is never granted or
changed through this API; ownership changes only via **transfer-ownership** on the [Team API](team-api.md).
And **authoring** role definitions (not granting them) is **owner-only** — a member with a
non-owner role who tries a control-plane mutation their role category does not permit is denied by
OPA **before** the gates are ever reached, as `403 ACCESS_DENIED` (see [change-role](#change-a-members-role)).

---

## Endpoints

| Method | Path | Operation | Summary |
|--------|------|-----------|---------|
| `GET` | `/api/v1/teams/{teamId}/members` | `listMembers` | List a team's members (paginated). |
| `POST` | `/api/v1/teams/{teamId}/members` | `addMember` | Grant a user a role on the team. |
| `PUT` | `/api/v1/teams/{teamId}/members/{userId}` | `changeMemberRole` | Change an existing member's role. |
| `DELETE` | `/api/v1/teams/{teamId}/members/{userId}` | `removeMember` | Revoke a member's access. |

---

### List team members

Return a page of the team's memberships.

```http
GET /api/v1/teams/{teamId}/members
```

**Path Parameters**:
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `teamId` | string (uuid) | Yes | The team whose members to list. |

**Query Parameters**:
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `page` | integer | No | 0 | 0-based page index. Out of bounds is a `400 VALIDATION_FAILED` (no clamping). |
| `perPage` | integer | No | 20 | Page size, 1–100. Out of bounds is a `400 VALIDATION_FAILED` (no clamping). |

**Response** (`200 OK`) — the shared page envelope `{count, page, perPage, items}` (ADR
[[0012-pagination-envelope|0012]]). `count` is **subject-relative**: the number of members the
calling subject is authorized to see, across all pages — never `items.length`. Rows are ordered
`createdAt ASC, id ASC`.

```json
{
  "count": 3,
  "page": 0,
  "perPage": 20,
  "items": [
    {
      "id": "6b1e2f3a-4c5d-4e6f-8a9b-0c1d2e3f4a5b",
      "teamId": "1f0c3c2a-8b7e-4d21-9a10-6e5b4c3d2a11",
      "userId": "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d",
      "roleCode": "owner"
    },
    {
      "id": "7c2f3a4b-5d6e-4f7a-9b0c-1d2e3f4a5b6c",
      "teamId": "1f0c3c2a-8b7e-4d21-9a10-6e5b4c3d2a11",
      "userId": "2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e",
      "roleCode": "reader"
    }
  ]
}
```

**Authorization**: Requires the team's `list-members` control-plane action (an OPA-decided verb; see
`_actions` on a `Team`). A page past the end is `200` with empty `items` and the exact `count` —
never `404`.

**cURL Example**:
```bash
curl -s "http://localhost:8080/api/v1/teams/$TEAM_ID/members?page=0&perPage=20" \
  -H "Authorization: Bearer $TOKEN"
```

---

### Add a member

Grant a user a role on the team — the access-granting operation.

```http
POST /api/v1/teams/{teamId}/members
```

**Path Parameters**:
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `teamId` | string (uuid) | Yes | The team to add the member to. |

**Request Body** (`AddMemberRequest`):
| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `userId` | string (uuid) | Yes | The user to grant a role to. |
| `roleCode` | string | Yes | The code of a role definition on the team to bind (1–100 chars). |
| `actorUserId` | string (uuid) \| null | No | Explicit actor fallback, used only until token-derived identity is wired. When the request is authenticated, the actor is the subject and this field is **ignored**. |

The owner (`editor`) grants `alice` the `reader` role:

```json
{
  "userId": "aa11bb22-cc33-4d44-8e55-6f7788990011",
  "roleCode": "reader"
}
```

**Response** (`201 Created`) — the new `Membership`:
```json
{
  "id": "8d3f4a5b-6c7d-4e8f-9a0b-1c2d3e4f5a6b",
  "teamId": "1f0c3c2a-8b7e-4d21-9a10-6e5b4c3d2a11",
  "userId": "aa11bb22-cc33-4d44-8e55-6f7788990011",
  "roleCode": "reader"
}
```

**Authorization & gates**: Requires the team's `add-member` control-plane action (OPA-decided). The
requested `roleCode` is additionally bounded by the **cross-tier ceiling** and, at the senior tier,
the **senior subset** gate (above). Granting `owner` is never permitted here.

**Errors**:
| Status | `errorCode` | When |
|--------|-------------|------|
| `400` | `VALIDATION_FAILED` | Malformed body or an unknown/blank `roleCode` shape. |
| `403` | `ACCESS_DENIED` | The actor's role does not permit `add-member` on this team (OPA deny). |
| `409` | `MEMBERSHIP_CONFLICT` | The user is already a member of this team. |
| `422` | `ROLE_SUBSET_VIOLATION` | The requested tier violates the cross-tier ceiling or the senior subset gate (or an OPA outage during the verdict). |

**cURL Example**:
```bash
curl -s -X POST "http://localhost:8080/api/v1/teams/$TEAM_ID/members" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "aa11bb22-cc33-4d44-8e55-6f7788990011",
    "roleCode": "reader"
  }'
```

---

### Change a member's role

Re-bind an existing member to a different role on the team.

```http
PUT /api/v1/teams/{teamId}/members/{userId}
```

**Path Parameters**:
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `teamId` | string (uuid) | Yes | The team. |
| `userId` | string (uuid) | Yes | The member whose role changes. |

**Request Body** (`ChangeRoleRequest`):
| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `roleCode` | string | Yes | The role definition code to re-bind to (1–100 chars). |
| `actorUserId` | string (uuid) \| null | No | Explicit actor fallback (same semantics as on add — ignored when the request is authenticated). |

```json
{
  "roleCode": "member"
}
```

**Response** (`200 OK`) — the updated `Membership` (same shape as add).

**Authorization & gates**: This change is **server-enforced** through the full gate stack. Both the
**cross-tier ceiling** (the new `roleCode`) and — for an existing member — the **target-tier gate**
(the member's *current* tier vs. the actor's) apply, and at the senior tier the **senior subset**
gate as well. Two things are firmly outside this endpoint:

- **`owner` is protected** — you cannot change a member *to* or *from* `owner` here; ownership moves
  only via **transfer-ownership** on the [Team API](team-api.md).
- **A role that lacks the control-plane category cannot change roles at all.** A **custom non-owner
  role** — e.g. `demo-editor`, whose category set permits catalog work but not member management —
  attempting a change-role is denied by **OPA first**, as `403 ACCESS_DENIED` (the control-plane
  category, ADR [[0015-control-plane-vocabulary-categorization|0015]]). The request never reaches the
  escalation gates; it is a plain "you may not manage members" deny, distinct from the `422` an
  authorized-but-over-reaching actor would get.

A `demo-editor` member tries to change another member's role and is refused:

```json
{
  "type": "/problems/access-denied",
  "title": "Access denied",
  "status": 403,
  "detail": "The caller is not authorized to change member roles on this team.",
  "instance": "/api/v1/teams/1f0c3c2a-8b7e-4d21-9a10-6e5b4c3d2a11/members/2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e",
  "errorCode": "ACCESS_DENIED",
  "timestamp": "2026-07-13T10:15:30Z"
}
```

**Errors**:
| Status | `errorCode` | When |
|--------|-------------|------|
| `403` | `ACCESS_DENIED` | The actor's role does not permit member management on this team (OPA control-plane deny) — e.g. the `demo-editor` case above. |
| `404` | `RESOURCE_NOT_FOUND` | The team or the addressed member does not exist (or is not visible). |
| `422` | `ROLE_SUBSET_VIOLATION` | The requested/target tier violates the cross-tier, senior-subset, or target-tier gate (or an OPA outage during the verdict). |

> An authorized manager attempting to change roles **to/from `owner`** is refused as a protected-role
> violation, not an ordinary change — use transfer-ownership.

**cURL Example**:
```bash
curl -s -X PUT "http://localhost:8080/api/v1/teams/$TEAM_ID/members/$USER_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{ "roleCode": "member" }'
```

---

### Remove a member

Revoke a member's access by deleting the membership.

```http
DELETE /api/v1/teams/{teamId}/members/{userId}
```

**Path Parameters**:
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `teamId` | string (uuid) | Yes | The team. |
| `userId` | string (uuid) | Yes | The member to remove. |

**Response** (`204 No Content`) — empty body. The member **immediately loses all access** derived
through this membership; the resolve path re-derives from live membership, so there is no stale grant
to clean up.

**Authorization & gates**: Requires the team's `remove-member` control-plane action (OPA-decided),
additionally bounded by the **target-tier gate** — a member whose current tier is above the actor's
cannot be removed by them (peers stay removable). The actor is resolved from the authenticated
subject.

**Errors**:
| Status | `errorCode` | When |
|--------|-------------|------|
| `403` | `ACCESS_DENIED` | The actor's role does not permit `remove-member` on this team (OPA deny). |
| `404` | `RESOURCE_NOT_FOUND` | The team or the addressed member does not exist (or is not visible). |

**cURL Example**:
```bash
curl -s -X DELETE "http://localhost:8080/api/v1/teams/$TEAM_ID/members/$USER_ID" \
  -H "Authorization: Bearer $TOKEN" -i
```

---

## Actions & required control-plane permission

Which control-plane action each endpoint gates on. The team's `CONTROL` category expands to these
member-management verbs (ADR [[0015-control-plane-vocabulary-categorization|0015]]); the tiers that
hold them are the ones at or above the action's floor, subject to the escalation gates above.

| Action | Endpoint | Decided by | Enriched in `_actions`? |
|--------|----------|------------|-------------------------|
| `list-members` | `GET .../members` | OPA only | ✅ Yes |
| `add-member` | `POST .../members` | OPA + cross-tier / senior-subset gates | ✅ Yes |
| `remove-member` | `DELETE .../members/{userId}` | OPA + target-tier gate | ✅ Yes |
| `change-role` | `PUT .../members/{userId}` | OPA + cross-tier / senior-subset / target-tier gates | ❌ No (Java-co-gated) |

**Why `change-role` is absent from `_actions`.** A `Team` response carries an `_actions` affordance
map (ADR [[0016-action-enrichment-affordance-metadata|0016]]), but it enumerates **only fully
OPA-decided verbs** — `list-members`, `add-member`, `remove-member`. `change-role` (like
`define-roles` and `transfer-ownership`) is **co-gated in Java** by the escalation gates, so OPA alone
would say `true` for a member whose category permits it while the specific escalation still rejects.
Enumerating it would over-promise; excluding it preserves the invariant **`_actions` true ⇒ the caller
can actually do it**. A team's affordance map therefore looks like:

```json
{ "list-members": true, "add-member": true, "remove-member": false }
```

(An un-gated `getTeam` bootstrap read omits `_actions` entirely — the documented, correct degrade.)

---

## Schema Reference

### AddMemberRequest
```typescript
{
  userId: string;              // UUID — the user to grant a role to
  roleCode: string;            // 1–100 chars — a role definition code on the team
  actorUserId?: string | null; // UUID — explicit actor fallback; ignored when authenticated
}
```

### ChangeRoleRequest
```typescript
{
  roleCode: string;            // 1–100 chars — the role to re-bind to
  actorUserId?: string | null; // UUID — explicit actor fallback; ignored when authenticated
}
```

### Membership
```typescript
{
  id: string;        // UUID (readOnly)
  teamId: string;    // UUID (readOnly)
  userId: string;    // UUID
  roleCode: string;  // the bound role definition's code, e.g. "owner" | "reader" | a custom role
}
```

### ProblemDetail
An RFC-7807 `application/problem+json` object — five standard members plus two documented extensions
(`errorCode`, `timestamp`). `status` and `errorCode` are always present; a client branches on the
typed `errorCode`, not the human `detail`. See the [error contract](README.md#error-contract-rfc-7807-problemjson)
in the index (ADR [[0011-error-contract-problem-json|0011]]). Codes this endpoint family emits:
`VALIDATION_FAILED`, `ACCESS_DENIED`, `RESOURCE_NOT_FOUND`, `MEMBERSHIP_CONFLICT`,
`ROLE_SUBSET_VIOLATION`.

---

## Related Documentation

- [API index](README.md) — cross-cutting conventions (auth, error contract, pagination, `_actions`).
- [Team API](team-api.md) — the team a membership grants a role on; owner-on-create and owner-only
  **transfer-ownership** (the only way `owner` moves).
- [Role Definition API](role-definition-api.md) — the role definitions a `roleCode` binds to (system
  ladder + team-scoped custom roles, categories, deny-overrides, tag requirements).
- [User API](user-api.md) — the user profiles and directory search behind a member's `userId`.
- Guides: [[TEAM-BASED-AUTHORIZATION]] (resolving an effective role from live membership),
  [[PERMISSION-MODEL]] (the five-tier ceiling and the two assignment gates), [[ACTION-ENRICHMENT]]
  (the `_actions` affordance mechanism).
- ADRs: [[0003-role-definitions-role-not-grant|0003]] (role ≠ grant),
  [[0007-coarse-grained-permission-categories|0007]] (five-tier ceiling),
  [[0015-control-plane-vocabulary-categorization|0015]] (control-plane verbs),
  [[0016-action-enrichment-affordance-metadata|0016]] (`_actions`).

---

## Source of truth

This page is the **narrative layer**. The authoritative contract is the OpenAPI spec
(`example-user-management-service/src/main/resources/openapi/user-mgmt-api.yaml`, the codegen source —
drift is a build break) and the running service's **Swagger UI** at
[`/swagger-ui.html`](http://localhost:8080/swagger-ui.html). When this page and the spec disagree, the
spec wins.
