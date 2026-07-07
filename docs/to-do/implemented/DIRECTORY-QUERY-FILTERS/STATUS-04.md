---
tags:
  - status/done
  - type/project
  - area/api
  - area/spring
---

# STATUS — T4: Bootstrap displayName upsert (/internal/bootstrap/users)

**Status:** ✅ DONE

## What shipped

- `InternalBootstrapController.ensureUser` — find-or-create → **upsert of `displayName` only**: on a
  `findBySubject` hit with a differing, non-null incoming name, set + save the same managed row; an
  identical (or null-name) re-post is a **no-op** (no save issued); a miss creates exactly as
  before. Same `{userId}` response shape, `@Transactional` kept. Mirrors the `map`/`orElseGet`
  upsert idiom `ensureMembership` already uses in the same controller.

## Tests

- **U4 — `web/InternalBootstrapUpsertTest`** (plain unit, stubbed repos): changed name → converges on
  the same row (same id, one save, **subject never re-pointed**); identical re-post → `save` never
  invoked; null name → stored name kept, no save; miss → creates.
- **I4 — `InternalBootstrapIT.ensureUserUpsertsDisplayNameOnRerun`** (Testcontainers real Postgres):
  `{S,"A"}` → row with "A"; `{S,"B"}` → **same `userId`, `displayName` now "B"**, no duplicate row;
  `{S,"B"}` again → no-op, same row, still "B".
- `./gradlew :example-user-management-service:test` — green, **194 tests, 0 failures** (module).

## Architecture review + refactor

- **Fail-closed / no-widening:** N/A to read shapes — this is the internal seed path. The **public
  `POST /users` create path is untouched** (§2.5; the diff touches only the internal controller).
- **Security (cannot escalate):** the upsert writes **only `displayName`** on the row already keyed
  by the same subject — it never re-points a subject (unit-asserted), never changes an id, never
  touches memberships/roles (structurally: the method uses only `users`). The endpoint stays under
  `/internal/**` — in-network, `permitAll`, never gateway-exposed (the `internal-blocked` route).
- **Concurrency / idempotency:** `@Transactional` kept; an identical re-post is a no-op; a
  changed-name re-post converges with no duplicate (the subject unique constraint is the backstop).
  Two *concurrent* first-seeds of the same new subject could both miss and race the insert — the
  unique constraint fails one; that is the pre-existing find-or-create behavior, unchanged by this
  ticket (the seed script is sequential).
- **Wiring:** named consumer — `scripts/postman/seed-demo-data.sh` re-runs this endpoint; the
  changed-name re-seed is what makes E4 (T5) deterministic. Non-happy paths tested: null name,
  identical re-post, miss.
- **Boundary / additivity:** one method in the internal controller; response shape unchanged
  (IT-asserted); no schema change (`ddl-auto: validate` boots clean in every IT).
- **Refactor applied:** none — nothing substantive; the change is the file's own established idiom.

## Integration / e2e

I4 ran in the module test task — green. Gateway-side proof (E4: re-seed then read back through
`?subject=`) is T5's newman extension.

## Decisions

- **Null incoming `displayName` keeps the stored name** (defensive: the seed script always sends a
  name; a partial payload must not blank a row). Unit-tested.

## Commit

`feat(usermgmt): upsert displayName on bootstrap re-seed (T4)` — see git log on
`feature/void3110/directory-query-filters`.
