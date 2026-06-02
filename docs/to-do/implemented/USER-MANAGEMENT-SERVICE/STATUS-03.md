---
tags:
  - status/done
  - type/project
  - area/user-service
  - area/abac
---

# STATUS — Ticket 03: Owner-on-create (atomic team-target + owner membership)

> Filled in at the ticket-03 checkpoint. See [[01-DECOMPOSITION]] ticket 3.

**Status:** ✅ done

## What shipped

Owner-on-create — and with it, the **`…usermgmt.service/` package** (the decided layered divergence).

- `TeamService.createWithOwner(creatorUserId, name, targetType, targetId)` — one `@Transactional`:
  guard the creator exists → guard one-team-per-target → resolve the seeded `owner` role → save the
  `Team` → save the `owner` `TeamMembership`. Any failure rolls the whole thing back; there is never a
  grant-less resource.
- `TeamTargetExistsException` (→ 409) for the one-team-per-`(targetType,targetId)` guard.
- `CallerIdentity` — resolves the **acting** user from the authenticated subject (`sub` → `User` by
  its `subject` column), with an explicit fallback id until the production security chain lands (T4).
  The seam that keeps every grant tied to the *actor*, never the service identity (hard rule 5).
- `POST /api/v1/teams` (`createTeam`) — delegates to `TeamService`; the controller stays thin.
- OpenAPI: `CreateTeamRequest` + the `409 Conflict` response.
- `ApiExceptionHandler` extended: `TeamTargetExistsException` → 409, `IllegalArgumentException` → 400.

## Tests

`:example-user-management-service:test` → **green** (12 tests; +4 this ticket). `OwnerOnCreateIT`:
- **O1** — `createWithOwner` yields exactly one `Team` + one `owner` membership for the creator
  (role id = the stable seeded `owner` id).
- **O2** — forced failure mid-create persists **nothing**: a `@MockitoSpyBean` makes the membership
  write throw *inside* the transaction; the team write rolls back with it (`teams.count()` unchanged,
  the target unresolvable). Proves the bootstrap is one unit of work.
- **O4** — a second team for the same `(targetType,targetId)` → `TeamTargetExistsException`.
- Unknown creator → `IllegalArgumentException`.
- (O3 — "creator resolves as owner" — is re-checked end-to-end in T7's resolve API.)

`./gradlew build` (whole repo) → green.

## Architecture review + refactor

- **Owner-on-create atomic** ✅ — the O2 rollback test is the proof; a single `@Transactional`
  boundary, no partial writes.
- **Authorize the actor** ✅ (seam) — `CallerIdentity` makes the *subject* the acting user (subject
  wins over the fallback). Full `@OpaPreAuthorize` enforcement of who may create lands in T4 — correct
  for this ticket's scope.
- **Layering** ✅ — `service/` introduced exactly as decided; controller delegates; the multi-entity
  invariant lives in the service, not the controller.
- **Fail-closed** ✅ — unknown creator / duplicate target throw (400 / 409); no silent success.

**No refactor applied** — the design held; no invented churn. (`createWithOwner` resolves the owner
role by code per call; left as-is — correctness over a micro-optimization, and it reads clearly.)

## Integration / e2e

ITs against real Postgres (Testcontainers); the O2 atomicity test exercises a genuine transaction
rollback against the real DB. No rig/newman yet (T9).

## Decisions recorded

Nothing non-obvious beyond what's already captured (owner-on-create is in the cross-platform reference
`mx-7d3605`; the layering decision is `mx-b17da2`). No new Mulch record — skipped per the rule, no
ritual filler.

## Commit

`feat(user-mgmt): owner-on-create + service layer (T3)` — code + tests + this note.
