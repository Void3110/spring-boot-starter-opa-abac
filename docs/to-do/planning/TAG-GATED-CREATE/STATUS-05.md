---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/opa
  - area/spring
  - area/catalog-service
---

# STATUS — T5: the pane row (E8), the close-out records, the review sequence

**Status:** ✅ done — 2026-09-11 (collaborative build; the review sequence recorded below)

## What shipped

- **E8, the console cell — measured in the Browser pane, 2026-09-11.** Setup through the user-service
  bootstrap API (no console login needed for it): `viewer` rebound to `alice-role` on the Demo team
  (WRITE+TAG on catalog/category/product, `region:[apac]` ∧ `sensitivity:[public]`, ALL_OF); the
  maintainer logged in as `viewer`, the agent drove the console.
  - The Demo catalog opens (the root-read exemption), "No categories visible to you in this catalog."
    (EMEA `region:emea` and APAC `region:apac` both fail ALL_OF); `+ New category` is offered.
  - **Name only** → the console shows `create failed: 403 — Access denied`, no optimistic row; the
    wire: `POST …/categories` → **403**, `application/problem+json`, `errorCode: ACCESS_DENIED`.
  - **The same form with `sensitivity: public` + `region: apac`** → **403** again (the untagged root:
    placement, not payload). A `fetch` probe on the viewer's own session with the exact payload
    `{"name":…,"tags":{"region":["apac"],"sensitivity":"public"}}` → **403** `ACCESS_DENIED` — the QA
    note's reproduction row 7, until now inferred, is **measured**.
  - **A product under EMEA** with the matching payload (the QA note's row 5) → **403**; `GET` EMEA → 403.
  - The Demo catalog's children after every attempt: exactly `EMEA region`, `APAC region` (DB count 2).
  - **Control:** the maintainer switched to `editor`; the same name-only form → **201**, the row rendered
    (`e8-control-editor`, no tags); removed via the editor's session (`DELETE` → **204**); the list is
    back to the two seeded categories. `viewer` rebound to `demo-viewer` (roster: editor/owner,
    demo/demo-editor, viewer/demo-viewer).
- Records: [[PRE-HABR-UI-QA-2026-09-10]] DEF-1 → fixed; ADR 0034 status → implemented; `USER-STORIES.md`
  C5 ✅; the roadmap line; the package index (status + ticket table).

## Tests

Every T1–T4 acceptance re-run on the final branch is recorded in the ship commit below; the pane cell is the only new measurement in this ticket.

## Architecture review + refactor

Filled at the ticket's checkpoint: the review path used (the collaborative build's per-ticket read-through; the slice-level passes are recorded in STATUS-05), what it found, what was refactored (or "nothing substantive").

## Integration / e2e

E8 above (the packaged SPA through the gateway, `viewer` and `editor` logins by the maintainer).

## Decisions

## Commit
