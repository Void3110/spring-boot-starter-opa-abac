---
tags:
  - status/done
  - type/project
  - area/security
  - area/api
---

# STATUS — T6: e2e (newman) + SPA picker rewrite + docs + folder move

**Status:** ✅ DONE

## What shipped

- **e2e:** four cells appended to `team-abac-matrix.postman_collection.json` (no new collection),
  run by **`run-team-matrix.sh`** — the user-service matrix runner (the prompt's `run-tests.sh`
  mention is the *catalog* runner, red-by-design on a user-service rig; noted for planning). Cells 13
  + 13a are the headline pair: the directory search returns **carol** (a never-provisioned realm
  account, disclosure ceiling asserted as exactly `[displayName, subject]`) and the provisioned
  `?subject` lookup for the **same captured subject** answers an **empty page** — the
  directory-vs-provisioned cut in one run. Cell 14: blank `q` → empty items + the echoed default 20.
  Cell 15: `limit=1000` → echoed 50, at most 50 rows.
- **SPA:** `api.ts` gains `DirectoryUser`/`DirectoryUserList` + `searchDirectory(q, limit)`;
  `teams.tsx`'s `AddMemberForm` now does a **debounced (250 ms, stale-guarded) server-side directory
  search** instead of client-filtering the provisioned pool; select → **`ensureUser` (provision) →
  `addMember`**, then the users list reloads so the new profile resolves in the roster. Transport
  errors render **identically to zero matches** (the no-oracle rule — no "directory down" state
  exists in the UI). Empty-state copy no longer says "only provisioned profiles"; candidates already
  on the team are filtered by subject.
- **Docs:** new guide [[USER-DIRECTORY]] (the seam layer-by-layer, the fail-closed/no-oracle table,
  authorization+disclosure, adoption yaml, the rig flag); ADR 0020 flipped to shipped in the file +
  index row; roadmap Slice-2 row updated to shipped; root `CLAUDE.md` gains the module in the layout
  + a build/run note (`ENABLE_DIRECTORY=1`); `infra/README.md` gains the flag paragraph.
- **Folder move:** `git mv docs/to-do/planning/USER-DIRECTORY-PORT docs/to-do/implemented/`, index
  frontmatter → `status/done`, ✅ Shipped banner added.

## Tests

- **Matrix (newman through the gateway): 21 requests, 24 assertions, 0 failed** — all pre-existing
  cells plus the four new directory cells green in one run.
- **`./gradlew build` green** (whole-build ticket: all library modules incl. the new one, both example
  apps, codegen, Testcontainers ITs).
- **SPA:** `tsc -b` + `vite build` clean.

## Architecture review + refactor

Nothing substantive to refactor in this ticket's own code. Findings worth recording:

- **Browser verification surfaced a pre-existing seed/UX gap (not a regression):** on the *seeded demo
  team*, add-member 403s for every current member — the seed builds only custom roles
  (`demo-admin/editor/viewer`, no `owner` row, no CONTROL grants) while the picker's role dropdown
  falls back to the system ladder (role-defs listing is owner-only), so the granted code doesn't exist
  on that team. The **self-service flow** (create a catalog → owner → add) completes fine and is the
  shipped design (ADR 0019's squat-gate path). Verified end-to-end that way; the seeded-team UX gap is
  flagged as follow-up material, out of this slice's scope.
- **No-oracle in the UI:** the three quiet states (blank query / zero matches / outage) share the
  empty list; only wording between "type to search" and "no accounts match" differs — by query
  presence, never by backend state.
- **Boundary:** the SPA rewrite touches exactly the picker + api client; `MemberRow`, roster
  management, and role editing untouched.

## Integration / e2e (browser, the T6 acceptance)

Preview browser (Vite `:3000` → gateway): PKCE login as `editor`; typing `car` issued **one**
debounced `GET` of the search sub-path (`q=car&limit=20`, seen in the network trace); carol —
never provisioned — appeared from the live directory; on the self-service team, select + Add ran
`ensureUser` (both paths exercised in the trace: `201 Created` on first provision, exact-match reuse
on the second) then `addMember → 201`, and **carol appeared in the roster** with the success notice.
Screenshot captured at the checkpoint.

## Decisions

- e2e runner: `run-team-matrix.sh` (the real user-service matrix), not the prompt's stale
  `run-tests.sh` pointer.
- The picker filters already-member candidates by **subject** (directory rows have no profile id until
  provisioned).

## Commit

`test(e2e)+feat(spa)+docs(user-directory-port): directory e2e cells, picker on the live directory, guide + folder move`
