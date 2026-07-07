---
tags:
  - status/done
  - type/project
  - area/api
  - area/spring
---

# STATUS — T5: e2e (newman) + SPA one-shot adoption + docs + folder move

**Status:** ✅ DONE

## What shipped

- **e2e (newman):** the existing user-service matrix (`team-abac-matrix.postman_collection.json`, no
  new collection) extended with 8 requests (9–12): E1 `?subject` matched → `count=1` + the row's id
  captured; unmatched → **empty page**; E2 `?targetType`+`?targetId` matched → the governing team
  (`id == team_id`); unmatched pair → empty; half-specified → **400 `VALIDATION_FAILED`
  `problem+json`**; E4 re-seed with a changed display name → **same `userId`** then the next
  `?subject` read reflects the new name; E3 (last — destructive) `removeMember` with a bare
  `Accept: application/json` → **204, empty body**. `run-team-matrix.sh` passes the two new vars
  (`owner_sub`, `viewer_uid`). All asserts are the actual cut (counts, ids, status codes).
- **SPA one-shot adoption** (`example-demo-ui`): `ensureUser` → one `findUserBySubject` call
  (`?subject=`); the `teams.tsx` team-by-target walk → one `lookupTeamByTarget` call
  (`?targetType&targetId`, page-envelope shape so `useAsync`'s null-means-loading survives). Dead
  `listAllTeams` **and** its now-caller-less `listTeams` removed (grep-verified; the file keeps no
  speculative helpers). The member-picker `listAllUsers` walk deliberately **stays** — that is
  Slice 2's `UserDirectory` rewrite. `tsc` clean.
- **Docs:** [[REST-API-DESIGN]] reconciled — §7 gains the *exact-match lookup filter* convention
  (one-item page / empty-on-miss / both-or-400 / additive branch), §3 gains the 204-must-admit-JSON-
  `Accept` note (the schema-less `NoContent` convention), the list-endpoint checklist gains the
  filter line. [[POC-ROADMAP]] Slice-1 row flipped to shipped.
- **Folder move:** `git mv` to `docs/to-do/implemented/`, index frontmatter → `status/done`,
  Shipped banner added.

## Tests / proof

- **Full build:** `./gradlew build` green — all modules + both example apps + codegen + ITs (JDK 21).
- **Module suite:** 194 tests, 0 failures (includes the slice's 16 new unit/IT cases).
- **Gateway e2e:** `./run-team-matrix.sh` — **17 requests, 20 assertions, 0 failed** (the 9
  pre-existing matrix requests all green on the new image — the effective-role resolve path is
  regression-checked; then E1–E4). Rig: Docker, `ENABLE_SPA=1 ./deploy.sh up --pods 2`, in-network
  tokens honored, **no OPA restart** (no rego change).
- **Browser (dev preview, signed in as `editor` via PKCE):** the network log shows exactly
  `GET /api/v1/users?subject=<sub>` for `ensureUser` and
  `GET /api/v1/teams?targetType=catalog&targetId=<id>` for the team panel — **one filtered request
  each, no page-walk** (per StrictMode render); the remaining `/users?page=0&perPage=100` is the
  member-picker directory (Slice 2). Team roster + affordances render correctly.

## Architecture review + refactor

- **Fail-closed / no-widening:** the e2e negatives pin the cut *through the gateway* (unmatched →
  empty page, half-specified → 400) — the same invariants the unit/IT layers pin in-process. No SPA
  call sends a half-specified pair (the two params travel together by construction).
- **Security:** the E4 upsert request targets `{{user_service}}` (host-mapped `:28090`) because
  `/internal/**` is **gateway-blocked** — the collection itself demonstrates the boundary (reads ride
  `{{gateway}}`, the seed rides the internal seam). No token, role, or route change.
- **Wiring:** every new SPA helper has a named consumer (`ensureUser`, `TeamPanel`); the removed
  helpers had none (grep in STATUS + commit).
- **Pattern-reuse:** the matrix extension follows the collection's flat numbered-request idiom and
  variable scoping; the SPA helpers reuse the existing `request<T>`/`Page<T>` plumbing.
- **Refactor applied:** none beyond the dead-helper removal the ticket itself mandates. Nothing
  substantive found.

## Decisions / notes

- **`listTeams` removed along with `listAllTeams`:** after the one-shot adoption it had zero callers,
  and `api.ts` keeps no speculative helpers (verified: every other exported helper has a caller).
- **The catalog-e2e (`run-tests.sh`) lifecycle suite fails on THIS rig flavor — pre-existing, not a
  regression:** its documented prerequisite is the OIDC-only rig (static role supplier); on the
  user-service rig (`role-source=http` + B4 membership isolation) a fresh, team-less catalog 403s by
  design. The user-service-era suites (team matrix et al.) are the green path — and the team matrix's
  9 pre-existing requests passing on the new image is the actual regression check for this slice.
- One junk row (`subject='{{owner_sub}}'`) created by accidentally running the collection through the
  wrong runner mid-session was deleted from the local usermgmt DB (fixture hygiene; local only).

## Commit

`test(e2e)+docs(directory-query-filters): E1–E4 matrix, SPA one-shot lookups, guide reconciliation,
folder move (T5)` — see git log on `feature/void3110/directory-query-filters`.
