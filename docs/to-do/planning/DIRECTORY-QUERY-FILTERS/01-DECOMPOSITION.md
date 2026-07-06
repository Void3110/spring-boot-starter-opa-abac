---
tags:
  - status/planned
  - type/project
  - area/api
  - area/spring
---

# DIRECTORY-QUERY-FILTERS — decomposition

> The ordered work list for [[DIRECTORY-QUERY-FILTERS|Slice 1]], decomposed from [[00-DESIGN]] +
> [[0012-pagination-envelope|ADR 0012]] (the one-item-page envelope) + [[0011-error-contract-problem-json|ADR 0011]]
> (the 400 body). **5 tickets, one focused commit each.** Each ticket's *Acceptance* references a case in
> [[10-QA-TEST-CASES]]. All work is in `example-user-management-service`
> (`dev.dmitriikonovalov.example.usermgmt.{web,domain}` + generated `…openapi.*`) plus the SPA's
> `example-demo-ui/src/*` (T5 only). **No library module, no rego, no schema change.**

## Critical path

```
T1 ─┐
T2 ─┤
T3 ─┼──► T5   (T1–T4 are mutually independent; T5 depends on all four)
T4 ─┘
```

**T1, T2, T3, T4 are mutually independent** — each is a separate endpoint/spec/seed change with no shared
code, so they may land in any order (or in parallel). **T1 and T2 are the headline** (they kill the
`listAll*` walks); each is **independently landable** (a filter + IT, provable without the SPA). **T5**
is the integration cap: it extends the newman matrix to prove all four through the gateway, flips the SPA
lookups to one-shot calls, reconciles the docs, and moves the folder. T5 depends on T1–T4 being green.

Recommended order **T1 → T2 → T3 → T4 → T5** (headline value first; the two smaller fixes next; e2e/docs
last), but the ★ review + checkpoint after each ticket is mandatory regardless of order.

---

## T1 — `?subject` filter on `GET /api/v1/users`

**Goal.** A single-request lookup of a user profile by IdP subject, returned as a one-item page — killing
the SPA's `listAllUsers()` walk in `ensureUser`.

**Deliverables.**
- `user-mgmt-api.yaml` — add an **optional** `subject` query parameter (string) to the `listUsers`
  operation. Regenerate (`./gradlew :example-user-management-service:openApiGenerate` runs in `build`).
- `UserController.listUsers` (`…usermgmt.web`) — branch: when `subject` is non-blank, resolve via
  `UserRepository.findBySubject(subject)` and map to a **one-item page** (`count` 0 or 1) through the
  existing `UserMgmtMapper.toUserPage`-shaped envelope; when absent/blank, the **unchanged**
  `users.findAll(PageDefaults.pageRequest(page, perPage))` path. (`findBySubject` already exists — no
  repository change.)
- Consumers named: the new branch's caller is the same `GET /api/v1/users` route (gateway `usermgmt-users`);
  the SPA adoption of it is **T5**, not here.

**Acceptance.** [[10-QA-TEST-CASES]] **U1** (unit/slice: `subject` present → one-item page; unmatched →
empty page; blank/absent → paged findAll unchanged) + **I1** (Testcontainers IT: seed 3 users across 2
pages, `?subject=<known>` returns exactly that row with `count=1`; `?subject=<unknown>` returns
`count=0`; no `subject` returns the full paged list unchanged). `./gradlew :example-user-management-service:test`.

**What NOT to touch.** The absent-filter `findAll` path stays **byte-for-byte** (additive branch, not a
replacement — §3 back-compat). A miss is an **empty page, never 404, never a full-list fallthrough**
(§2.3, the no-widening invariant). No change to `POST /users`, to memberships, or to the repository.
Build-breaker: regenerating the API interface adds the `subject` param to `UserApi.listUsers` — the
`UserController` override signature must match in the **same commit**.

---

## T2 — `?targetType` + `?targetId` filter on `GET /api/v1/teams`

**Goal.** A single-request lookup of the team governing a `(targetType, targetId)`, returned as a
one-item page — killing the SPA's `listAllTeams()` team-by-target walk.

**Deliverables.**
- `user-mgmt-api.yaml` — add **optional** `targetType` (string) + `targetId` (uuid) query params to the
  `listTeams` operation. Regenerate.
- `TeamController.listTeams` (`…usermgmt.web`) — branch: when **both** present, resolve via
  `TeamRepository.findByTargetTypeAndTargetId(targetType, targetId)` → one-item page; when **both
  absent**, the unchanged `teams.findAll(...)` path; when **exactly one** present, throw a
  validation error → **400 `application/problem+json`** (ADR 0011) via the existing `ApiExceptionHandler`.
  (`findByTargetTypeAndTargetId` already exists — no repository change.)
- Consumers named: same `GET /api/v1/teams` route (gateway `usermgmt-teams`); SPA adoption is **T5**.

**Acceptance.** [[10-QA-TEST-CASES]] **U2** (unit/slice: both present → one-item page; both absent →
paged findAll; exactly-one-present → 400) + **I2** (Testcontainers IT: seed 2 teams on distinct targets;
`?targetType=catalog&targetId=<known>` → that team, `count=1`; unmatched pair → `count=0`; `?targetType`
alone → 400 problem+json; no params → full paged list unchanged). `./gradlew :example-user-management-service:test`.

**What NOT to touch.** The both-absent `findAll` path stays byte-for-byte. **Exactly-one-present is a 400,
never a fallthrough to the full list** (§2.2 — the guard that stops a filter mask over a full scan). No
change to `createTeam`, its ownership gate, or the repository. Build-breaker: the regenerated
`TeamApi.listTeams` gains two params — the `TeamController` override must match in the **same commit**.

---

## T3 — `produces` spec fix: 204 endpoints accept a JSON `Accept`

**Goal.** The four 204-only operations stop returning **406** to a request carrying a bare
`Accept: application/json`.

**Deliverables.**
- `user-mgmt-api.yaml` — for `transferOwnership`, `deleteRoleDefinition`, `deleteTeamTagDefinition`,
  `removeMember` (204 responses at ~L226/319/459/500), make the success path JSON-acceptable: either a
  shared `NoContent` response component that declares an empty `application/json` success content, or an
  equivalent per-op declaration, so content negotiation admits a JSON `Accept`. Keep the **204 status +
  empty body** (no behavior change — the body stays absent; only the negotiated producible type widens).
  Regenerate.
- Consumers named: the four generated controller methods (`MembershipController.removeMember`,
  `RoleDefinitionController.deleteRoleDefinition`, `TagDefinitionController.deleteTeamTagDefinition`,
  `TeamController.transferOwnership`) — their signatures/behavior are unchanged; only the spec's
  produced-type declaration changes.

**Acceptance.** [[10-QA-TEST-CASES]] **I3** (Testcontainers IT / MockMvc: each of the four ops, called
with `Accept: application/json`, returns **204** — not 406 — with an empty body; called with no `Accept`
still returns 204). `./gradlew :example-user-management-service:test`.

**What NOT to touch.** The response **status (204) and empty body** are unchanged — this is a
content-negotiation fix, not a body change. No new fields, no `application/problem+json` change on the
error responses. `POST`/`DELETE` semantics unchanged.

---

## T4 — Bootstrap `displayName` upsert (`/internal/bootstrap/users`)

**Goal.** Re-seeding a known subject with a new `displayName` refreshes the stored name instead of
silently keeping the stale one.

**Deliverables.**
- `InternalBootstrapController.ensureUser` (`…usermgmt.web`, L52–58) — change find-or-create to
  **upsert**: on a `findBySubject` hit, if the incoming `displayName` differs from the stored one, set it
  and save; on a miss, create as today. Return the same `{userId}` shape. Keep `@Transactional`.
- Consumers named: `scripts/postman/seed-demo-data.sh` re-runs this endpoint idempotently — the upsert is
  what makes a re-seed with a changed display name deterministic (relevant to the T5 e2e).

**Acceptance.** [[10-QA-TEST-CASES]] **I4** (Testcontainers IT: POST bootstrap `{subject:S, displayName:"A"}`
→ row A; POST again `{subject:S, displayName:"B"}` → **same userId, displayName now "B"**, no duplicate
row; POST identical again → no-op, same row). `./gradlew :example-user-management-service:test`.

**What NOT to touch.** This is the **internal** seed endpoint only (`/internal/**`, never gateway-exposed
— the `internal-blocked` route). The **public** `POST /users` create path stays create-only (§2.5). The
upsert writes only `displayName`; it never re-points a subject, changes an id, or touches
memberships/roles (§3 — cannot escalate).

---

## T5 — e2e (newman) + SPA one-shot adoption + docs + folder move

**Goal.** Prove all four changes end-to-end through the gateway, collapse the SPA's `listAll*` **lookups**
to one-shot filtered calls, reconcile the docs, and move the folder to `implemented/`.

**Deliverables.**
- **e2e (newman):** extend the existing user-service matrix under `scripts/postman/` (no new collection)
  with: `GET /api/v1/users?subject=<seeded>` → one row; `GET /api/v1/teams?targetType=catalog&targetId=<seeded>`
  → one team; `?targetType` alone → 400; a DELETE/204 op with `Accept: application/json` → 204 (not 406);
  a re-seed of a known subject with a new display name reflected on the next read. Assert the **actual
  cut** (row counts, status codes), not just response shape.
- **SPA one-shot adoption** (`example-demo-ui/src/api.ts`, `teams.tsx`): replace the `listAllUsers()`
  walk in `ensureUser` with a single `GET /users?subject=<sub>`; replace the `listAllTeams()` team-by-target
  walk (`teams.tsx:42`) with a single `GET /teams?targetType=&targetId=`. Leave the **directory picker**
  as-is (that's Slice 2). Remove the now-dead `listAll*` helpers **only if** they have no other caller
  (grep first; a sibling may still use them).
- **Docs:** reconcile `docs/guides/REST-API-DESIGN.md` (the filtered-list convention + the both-target
  params 400) and note the 204/`Accept` fix. Tick the [[DIRECTORY-QUERY-FILTERS]] status table. Link the
  slice from [[POC-ROADMAP]] if missing.
- **Folder move:** `git mv docs/to-do/planning/DIRECTORY-QUERY-FILTERS docs/to-do/implemented/`, flip the
  index frontmatter `status/planned → status/done`, add a past-tense **Shipped** banner.

**Acceptance.** [[10-QA-TEST-CASES]] **E1–E4** (the newman assertions above, green through the rig) +
`./gradlew build` green (all modules + ITs). The SPA `ensureUser` and team-by-target lookups issue **one**
request each (verified in the browser preview: network shows a single `?subject=`/`?targetType=` call, no
page-walk).

**What NOT to touch.** Do **not** rewrite the directory **picker** (Slice 2). Do not remove a `listAll*`
helper that still has a caller. Honor the rig caveats: mint tokens **in-network**, restart OPA only if a
rego changes (none here). Do NOT push / open a PR / touch `main`.

---

## Cross-cutting acceptance

- `./gradlew build` green — all modules + both example apps + OpenAPI codegen + Testcontainers ITs (JDK 21).
- The user-service newman matrix green through the rig (`ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2`).
- **Back-compat holds:** every absent-filter call returns byte-for-byte today's paged result; no filter
  ever widens to a full-list fallthrough (empty page on a miss; 400 on a half-specified `/teams` pair).
- **No `opa-abac-core` / library change, no rego, no schema change** — `ddl-auto: validate` boots clean.
