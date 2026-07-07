---
tags:
  - status/planned
  - type/project
  - area/api
  - area/spring
---

# DIRECTORY-QUERY-FILTERS — QA test cases

> Concrete cases; each becomes a ticket's *Acceptance*. U = unit/slice, I = integration
> (Testcontainers real Postgres — never H2), E = e2e (newman through the rig; asserts the actual cut —
> row counts / status codes — not just response shape). All in `example-user-management-service` (+ the
> newman suite under `scripts/postman/`).

## Unit / slice (U*)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| U1a | `listUsers(subject=<known>)` | returns a one-item page, `count=1`, the matching profile | T1 |
| U1b | `listUsers(subject=<unknown>)` | returns an **empty** page, `count=0` — **not** 404, **not** the full list | T1 |
| U1c | `listUsers(subject=null/blank)` | falls through to the unchanged `findAll(page, perPage)` paged result | T1 |
| U2a | `listTeams(targetType, targetId)` both present, matching | one-item page, `count=1`, the governing team | T2 |
| U2b | `listTeams` both present, unmatched pair | empty page, `count=0` — not a fallthrough | T2 |
| U2c | `listTeams` exactly one of the pair present | **400** (validation error → `application/problem+json`) | T2 |
| U2d | `listTeams` both absent | unchanged `findAll(page, perPage)` paged result | T2 |
| U4 | `ensureUser` upsert logic (existing subject, new displayName) | updates `displayName`, same id; identical re-post is a no-op | T4 |

## Integration (I*) — Testcontainers real Postgres

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| I1 | Seed 3 users spanning 2 pages; `GET /users?subject=<known>`, `?subject=<unknown>`, no param | one row `count=1` / empty `count=0` / full paged list byte-for-byte unchanged | T1 |
| I2 | Seed 2 teams on distinct `(type,id)`; `GET /teams?targetType=catalog&targetId=<known>`, unmatched pair, `?targetType` alone, no params | one team `count=1` / empty `count=0` / **400 problem+json** / full paged list unchanged | T2 |
| I3 | Each of the 4 204-ops (`transferOwnership`, `deleteRoleDefinition`, `deleteTeamTagDefinition`, `removeMember`) called with `Accept: application/json` (and with no `Accept`) | **204** (not **406**), empty body, both times | T3 |
| I4 | `POST /internal/bootstrap/users {S,"A"}` → row; again `{S,"B"}`; again `{S,"B"}` | second call: same `userId`, `displayName="B"`, no duplicate row; third: no-op same row | T4 |

## E2E (E*) — newman through the gateway (extend the existing user-service matrix; no new collection)

| ID | Flow | Asserts (the actual cut) | → Ticket |
|---|---|---|---|
| E1 | `GET /api/v1/users?subject=<seeded>` (bearer, in-network token) | exactly **1** item, its subject == the query | T5 (proves T1) |
| E2 | `GET /api/v1/teams?targetType=catalog&targetId=<seeded>`; then `?targetType=catalog` alone | one team `count=1`; then **400** | T5 (proves T2) |
| E3 | A DELETE/204 op (e.g. `removeMember`) with `Accept: application/json` | HTTP **204**, empty body (no 406) | T5 (proves T3) |
| E4 | Re-seed subject `S` with a changed display name, then `GET /users?subject=S` | the read reflects the **new** display name (upsert took) | T5 (proves T4) |

## Headline proof

**U1a/b + I1** (the `?subject` one-shot lookup, matched → 1 / unmatched → empty, full-list unchanged) and
**U2a/c + I2** (the `?targetType`+`?targetId` lookup + the both-or-400 guard). These two are the slice's
reason to exist — they replace the `listAll*` page-walks with a correct, non-truncating, non-widening
single request. **E1/E2** prove the same cut through the gateway.
