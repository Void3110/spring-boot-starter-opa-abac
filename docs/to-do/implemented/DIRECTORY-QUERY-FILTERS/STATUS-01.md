---
tags:
  - status/done
  - type/project
  - area/api
  - area/spring
---

# STATUS — T1: `?subject` filter on GET /api/v1/users

**Status:** ✅ DONE

## What shipped

- `user-mgmt-api.yaml` — optional `subject` (string) query param on `listUsers`, filter-param-first
  (the `listTagDefinitions` ordering idiom), documenting the one-item-page / empty-on-miss / unchanged-
  when-absent contract. Regenerated `UserApi` gains the param; the `UserController` override moved in
  the same commit (the pinned build-breaker).
- `UserController.listUsers` — an **additive guard branch**: non-blank `subject` resolves
  `UserRepository.findBySubject` into a one-item page; absent/blank falls through to the
  byte-for-byte-unchanged `findAll(PageDefaults.pageRequest(...))` path.
- `PageDefaults.onePage(Optional<T>, PageRequest)` — the unique-key-lookup envelope helper: match ⇒
  single row on page 0 with `count=1`; miss ⇒ empty page `count=0`; a window past the single result ⇒
  empty `items` + exact `count` (ADR 0012 §3 past-the-end, so the row never repeats across pages).
  Placed in `PageDefaults` (web layer, package-private) so T2 reuses the same semantic instead of
  re-deriving it.

## Tests

- **U1 — `web/UserControllerFilterTest`** (plain unit, stubbed repo): U1a matched → one-item page +
  `findAll` never consulted; U1b unmatched → **empty** page, never the full list; U1c null/empty/blank →
  unchanged paged `findAll` + `findBySubject` never consulted; plus the past-the-end window
  (`page=1` → empty items, `count=1`).
- **I1 — `DirectoryQueryFilterIT`** (Testcontainers real Postgres, real security chain): 3 seeded users
  spanning 2 pages at `perPage=2`; `?subject=<known>` → exactly that row, `count=1`, one request;
  `?subject=<unknown>` → `count=0` empty; no param → full paged list (envelope + defaults + all seeded
  rows present; 2-wide window slices to exactly 2).
- `./gradlew :example-user-management-service:test` — green (all 4 + 3 new cases ran; module suite green).

## Architecture review + refactor

- **Fail-closed / no-widening:** the absent/blank path is the previous method body verbatim behind a
  guard clause (a new branch, not a replacement) — pinned by U1c asserting `findBySubject` is never
  consulted. A miss is an empty page (`count=0`), pinned by U1b/I1 asserting `findAll` is never
  consulted on the filter branch. No half-specified-pair case exists for a single param.
- **Security:** the only widening that would matter — a miss degrading to the full collection — is
  structurally impossible (the branch returns `onePage(...)` unconditionally) and negative-tested. The
  endpoint's authorization posture is untouched (authenticated-only, ungated read, same as before);
  `?subject` reveals nothing the unfiltered list didn't already enumerate to the same caller.
- **Concurrency / idempotency:** pure read; no mutation to guard.
- **Wiring:** the branch's non-happy paths each have a named test (miss → U1b/I1; blank → U1c);
  consumer is the same `usermgmt-users` gateway route; SPA adoption deliberately deferred to T5.
- **Boundary / additivity:** no library/module change; spec + controller + web-layer helper + tests
  only. Byte-for-byte-unchanged surfaces: the `findAll` paged path and every other operation. The one
  mechanical cost (regenerated `UserApi.listUsers` signature) landed in the same commit.
- **Pattern reuse:** `PageDefaults.pageRequest` + `UserMgmtMapper.toUserPage` + spec-first param —
  no new envelope shape, no Java-side `produces`/mapping invention.
- **Refactor applied:** none — **nothing substantive found**; the change is a guard clause + a
  10-line helper. Re-ran tests anyway (green).

## Integration / e2e

I1 ran in the same module test task (Testcontainers, real Postgres, real chain) — green. Gateway e2e
(E1) is T5's newman extension.

## Decisions

- **Past-the-end on the filter branch** (`?subject=x&page=1`): the decomposition didn't pin it; resolved
  from ADR 0012 §3's pinned past-the-end semantic (200 + empty `items` + exact `count`) rather than
  repeating the row on every window — determinism by construction (no repeat/vanish across pages).
  Unit-tested, not left implicit.
- Blank (`""`/whitespace) `subject` = absent (U1c) — deliberately no `minLength` on the param so a
  blank never 400s, matching the pinned fallthrough contract.

## Commit

`feat(usermgmt): add ?subject exact-match filter to GET /users (T1)` — see git log on
`feature/void3110/directory-query-filters`.
