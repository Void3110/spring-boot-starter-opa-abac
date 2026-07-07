---
tags:
  - status/planned
  - type/architecture
  - area/api
  - area/spring
---

# DIRECTORY-QUERY-FILTERS — design

> The settled design for [[DIRECTORY-QUERY-FILTERS]] — split off from the user-directory plan-of-record
> during the 2026-07-06 grill-me (Slice 2 = the Keycloak-admin `UserDirectory` port, designed and
> decomposed separately). This is the **safe, one-deployable half**: purely additive query filters + two
> correctness fixes, all inside `example-user-management-service`. No library-module change, no external
> dependency, no authorization-behavior change.

## 1. The mechanism

The demo SPA needs three single-resource lookups the user-service list endpoints can't answer directly,
so it emulates each with a client-side **`listAll*` page-walk** that is O(collection) and **silently
truncates** past page 0 (a lookup wrongly reports "not found" → a duplicate profile is provisioned):

- **find a user by IdP subject** — `ensureUser` (`example-demo-ui/src/api.ts:298`) walks
  `listAllUsers()` then `.find(subject)`.
- **find the team governing a target** — `teams.tsx:42` walks `listAllTeams()` then filters by
  `(targetType, targetId)`.

Add **exact-match query filters** to the two list endpoints; each is backed by a repository finder that
**already exists** (no new query). Present ⇒ return the single match as a **one-item page** (the ADR 0012
envelope, `count` = 0 or 1); absent ⇒ today's paged `findAll`, **unchanged**.

| Endpoint | New param(s) | Backing finder (exists) | Present ⇒ | Absent ⇒ |
|---|---|---|---|---|
| `GET /api/v1/users` — [`UserController.listUsers`](../../../../example-user-management-service/src/main/java/dev/dmitriikonovalov/example/usermgmt/web/UserController.java) | `subject` (string) | [`UserRepository.findBySubject`](../../../../example-user-management-service/src/main/java/dev/dmitriikonovalov/example/usermgmt/domain/UserRepository.java) | one-item page (0 or 1) | today's `findAll` page |
| `GET /api/v1/teams` — [`TeamController.listTeams`](../../../../example-user-management-service/src/main/java/dev/dmitriikonovalov/example/usermgmt/web/TeamController.java) | `targetType` + `targetId` (both) | [`TeamRepository.findByTargetTypeAndTargetId`](../../../../example-user-management-service/src/main/java/dev/dmitriikonovalov/example/usermgmt/domain/TeamRepository.java) | one-item page (0 or 1) | today's `findAll` page |

Two correctness fixes ride along (same service, same publication-hygiene spirit):

- **`produces` spec fix.** Four 204-only operations — `transferOwnership`, `deleteRoleDefinition`,
  `deleteTeamTagDefinition`, `removeMember` (`user-mgmt-api.yaml` responses at ~L226/319/459/500) —
  declare a bodyless `204` plus error responses that are `application/problem+json`. The generated
  controller therefore advertises **no `application/json` producible representation** for the success
  path, so a request carrying a bare `Accept: application/json` is **406**'d by content negotiation
  *before* the handler runs. Fix at the spec so a JSON `Accept` is admitted on these ops.
- **Bootstrap `displayName` upsert.** `InternalBootstrapController.ensureUser`
  (`InternalBootstrapController.java:52`) find-or-creates by subject, but `orElseGet` runs only on a
  **miss**, so re-seeding an existing subject with a new `displayName` silently keeps the stale name.
  Make it an **upsert**: update `displayName` when the row exists and the incoming value differs.

## 2. Decided forks

### 2.1 Filter semantics — exact match, single result, one-item page
`?subject` and `?targetType`+`?targetId` are unique keys (both repos back them with `Optional` finders +
a unique constraint). A filtered response is a **one-item page** in the existing envelope (not a bare
object), so the SPA's `Page<T>` client code is unchanged and `count` is honest (0 or 1). *Rejected:* a
new dedicated `by-subject` sub-path under `/api/v1/users` — more surface, breaks the "list endpoint +
filter" REST convention.

### 2.2 `/teams` requires BOTH target params together (else 400)
`targetType` alone or `targetId` alone is a **400** (`application/problem+json`, ADR 0011) — a
half-specified composite key is a client error, **not** a silent fallthrough to the full list (which
would re-introduce a whole-collection scan wearing a filter mask). Absent = both missing = today's paged
`findAll`. This is externally visible, so it is pinned here.

### 2.3 `?subject` unmatched ⇒ empty page, not 404
A list endpoint whose filter matches nothing returns an **empty page** (`count` = 0), consistent with
every other filtered list in the suite. 404 is for a *path* id (`GET /users/{id}`), not a *query* filter.

### 2.4 `produces` fix is spec-level, minimally scoped to the four 204 ops
Give each 204 op's success a JSON-acceptable declaration (an explicit empty `application/json` success
content, or a shared `NoContent` response component) so content negotiation admits a JSON `Accept`.
*Rejected:* a global controller `produces` override in Java — drifts from spec-first (the OpenAPI is the
source of truth) and would mask, not fix, the contract.

### 2.5 Upsert scoped to the internal seed endpoint only
`/internal/bootstrap/users` is in-network, `permitAll`, e2e-seed-only (never gateway-exposed — the
`internal-blocked` route). The **public** `POST /users` create path is **untouched** (create-only by
design; changing it is out of scope).

## 3. Fail-closed posture

**This slice introduces no authorization behavior and no fail-open.** It is a read-shape + seed-hygiene
change; the invariant to hold is **back-compat + no accidental widening**:

- **Absent filter ⇒ byte-for-byte today's behavior.** The `findAll` paged path is unchanged; the filter
  is a new *branch*, never a replacement. A client that sends no filter sees no difference.
- **A filter NARROWS, never widens.** A present-but-unmatched filter yields an **empty** page (0 rows),
  never a fallthrough to the full list. The both-params-required guard (§2.2) exists precisely so a
  partially-filled composite key cannot degrade to "return everything."
- **The upsert cannot escalate.** It writes only `displayName` on a row already keyed by the same
  subject; it never re-points a subject, changes an id, or touches memberships/roles.
- **No core/library change; no schema change.** Both finders and the unique constraints already exist,
  so `ddl-auto: validate` stays clean.

## 4. Considered & rejected

| Option | Why rejected |
|---|---|
| A new dedicated `by-subject` sub-path under `/api/v1/users` | Extra surface; breaks the list-endpoint-with-filter REST convention already used suite-wide. |
| `/teams` with one target param falling through to the full list | Re-introduces the whole-collection scan the slice exists to kill — a filter mask over a full scan. Both-or-400 instead (§2.2). |
| `?subject` miss ⇒ 404 | 404 is for a path id, not a query filter; every other filtered list returns an empty page. |
| Global Java `produces` override for the 406 fix | Drifts from spec-first; masks the contract instead of fixing the OpenAPI source of truth. |
| Making the public `POST /users` an upsert too | Out of scope; the public create path is create-only by design — widening it is a separate decision. |

## Related

- [[DIRECTORY-QUERY-FILTERS]] · [[01-DECOMPOSITION]] · [[10-QA-TEST-CASES]]
- [[0012-pagination-envelope]] — the one-item-page envelope. · [[0011-error-contract-problem-json]] — the 400 body.
- [[POC-ROADMAP]] — Phase 7.
