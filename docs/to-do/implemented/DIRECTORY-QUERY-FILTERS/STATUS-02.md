---
tags:
  - status/done
  - type/project
  - area/api
  - area/spring
---

# STATUS — T2: `?targetType`+`?targetId` filter on GET /api/v1/teams

**Status:** ✅ DONE

## What shipped

- `user-mgmt-api.yaml` — optional `targetType` (string) + `targetId` (uuid) query params on
  `listTeams`, documenting the **all-or-nothing pair contract** (both → one-item page; both absent →
  unchanged list; exactly one → 400 `VALIDATION_FAILED`). Regenerated `TeamApi`; the `TeamController`
  override moved in the same commit (the pinned build-breaker).
- `TeamController.listTeams` — the additive composite-key branch: an XOR guard
  (`hasType != hasId` → `IllegalArgumentException` → the existing `ApiExceptionHandler`
  `VALIDATION_FAILED` 400 `problem+json`, ADR 0011) **before any repository access**; both present →
  `findByTargetTypeAndTargetId` through `PageDefaults.onePage` (T1's helper — same one-item-page /
  past-the-end semantics); both absent → the byte-for-byte-unchanged paged `findAll`.

## Tests

- **U2 — `web/TeamControllerFilterTest`** (plain unit, stubbed collaborators): U2a matched pair →
  one-item page + `findAll` never consulted; U2b unmatched pair → **empty** page, never the full
  list; U2c half-specified (type-only / id-only / blank-type+id) → `IllegalArgumentException` with
  **neither** `findAll` **nor** the finder consulted; U2d both absent (null/empty/blank type) →
  unchanged paged `findAll`.
- **I2 — `DirectoryQueryFilterIT`** (+4 cases): matched pair → exactly the governing team `count=1`
  (a decoy team present); unmatched pair → `count=0`; `?targetType=catalog` alone → **400**
  `application/problem+json` with `errorCode: VALIDATION_FAILED`; no params → full paged list
  (envelope + both seeded teams present).
- `./gradlew :example-user-management-service:test` — green (module suite; IT class now 7/7).

## Architecture review + refactor

- **Fail-closed / no-widening:** the both-absent path is the previous method body verbatim behind
  the guards (U2d: finder never consulted). The half-specified pair **throws before any repository
  access** — the "filter mask over a full scan" fallthrough is structurally impossible, not merely
  untested (U2c asserts both repository methods are never touched). An unmatched pair is an empty
  page (U2b/I2), never the list.
- **Security:** the widening that matters here — one param silently degrading to the whole
  collection — is the XOR throw's whole purpose (§2.2 of the design). Authorization posture
  unchanged: `listTeams` stays the same ungated authenticated read; `createTeam` and its B4
  ownership gate untouched.
- **Concurrency / idempotency:** pure read; no mutation to guard.
- **Wiring:** every non-happy path has a named test — U2b (miss), U2c ×3 shapes (half-specified),
  I2's wire-shape 400 (typed `errorCode`, `problem+json` content type through the real advice).
- **Boundary / additivity:** spec + controller + tests only; no repository, service, or library
  change; the regenerated `TeamApi.listTeams` signature landed in the same commit.
- **Pattern reuse:** the 400 rides `IllegalArgumentException` → the existing
  `handleBadRequest` → `LibraryErrorCode.VALIDATION_FAILED` — no new exception type, no new handler,
  no new error code (exactly the ADR 0011 vocabulary). The envelope rides T1's `PageDefaults.onePage`
  — the one-item-page semantic is defined once.
- **Refactor applied:** none — **nothing substantive found**; the XOR guard is the minimal encoding
  of both-or-400. Tests re-ran green.

## Integration / e2e

I2 ran in the module test task (Testcontainers, real Postgres, real chain + advice) — green.
Gateway e2e (E2) is T5's newman extension.

## Decisions

- **Blank `targetType` + present `targetId` = half-specified → 400** (not "pair absent → full
  list"): blank-as-absent (T1's convention) composed with the XOR guard means a client that sent
  *something* for the pair gets told the pair is malformed — it never silently receives a different
  query's answer. Unit-tested (U2c third shape).
- An empty-string `targetId` arrives as `null` after Spring's UUID conversion, collapsing to the
  same half-specified 400 — no separate branch needed.

## Commit

`feat(usermgmt): add ?targetType+?targetId team-target filter to GET /teams (T2)` — see git log on
`feature/void3110/directory-query-filters`.
