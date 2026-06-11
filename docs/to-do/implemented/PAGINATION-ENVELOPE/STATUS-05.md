---
tags:
  - status/done
  - type/project
  - area/api
  - area/spring
  - area/abac
---

# STATUS — T5: e2e — pagination matrix + fixture set + suite-wide envelope migration

**Status:** ✅ DONE (2026-06-11)

## What shipped

- **`pagination-matrix.postman_collection.json`** (7 requests) + **`run-pagination-matrix.sh`** — the
  `run-filter-matrix.sh` model (in-network tokens, internal bootstrap, collection-scope runtime ids):
  - **E1** the count contrast: emea-reader `count == 5` vs apac-reader `count == 3` on the SAME URL,
    row sets disjoint — the numbers asserted, not just shape;
  - **E2** the paged walk at `perPage=2`: pages 0/1/2 disjoint, the concatenated walk `eql` the
    single-page list (order included — no repeat, no drop), `count` stable on every page, and
    past-the-end (`page=7`) → `200` + empty + the same exact count;
  - **E3** the live negative: `perPage=500` → `400` `application/problem+json`
    `errorCode=VALIDATION_FAILED` through APISIX.
- **The dedicated fixture set:** catalog `7777…` (new registry row) with 5 EMEA + 3 APAC categories,
  created via the gateway (sequential → deterministic `createdAt,id` order); roles `curator` /
  `emea-reader` / `apac-reader` on its own team. Shared fixtures untouched.
- **The suite-wide envelope migration:** the scout sweep found exactly **3 collections / 8 sites**
  consuming list bodies — `data-filter-matrix` (4× `.map`, pinned counts 1/1/≥3/0),
  `hierarchy-list-matrix` (3× `.map`, pinned 0), `catalog-e2e` (1× `.map`). All migrated
  `pm.response.json().map(…)` → `pm.response.json().items.map(…)` — **every numeric expectation
  unchanged**. The other four collections (catalog/tag/team/hierarchy-single) consume no list bodies —
  verified, untouched.
- **`scripts/postman/README.md`** — the new matrix row in the file table + the `7777…` registry entry.

## Tests

Full rig (`./profile.sh up` → `./deploy.sh build` → `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh
up --pods 2`), all 8 runners green:

| Runner | Assertions |
|---|---|
| `run-pagination-matrix.sh` (NEW) | **27/27** (×2 — rerun-stable, idempotent seed) |
| `run-filter-matrix.sh` | 16/16 (×2 — rerun-stable; pinned counts 1/1/≥3/0 intact) |
| `run-hierarchy-list-matrix.sh` | 20/20 (pre + post re-parent phases) |
| `run-tests.sh` (catalog-e2e) | 19/19 |
| `run-matrix.sh` | 19/19 |
| `run-tag-matrix.sh` | 12/12 |
| `run-team-matrix.sh` | 11/11 |
| `run-hierarchy-matrix.sh` | 4/4 + script-level re-parent flip checks |

`bash -n` clean on the new runner; collection JSON valid. **E4 holds: every pinned row count
numerically identical — the cut did not move, only the shape.**

## Architecture review + refactor

- **One fix-until-green loop (the run's only one):** the runner's direct-SQL catalog seed omitted the
  `ltree path` column → the first category create under it failed `500` with the **fail-closed**
  `AncestorResolutionException: parent has no path (broken lineage)`. Old matrices never trip this —
  their pre-5.5-A fixture rows were path-backfilled by the adoption migration; only *new* direct-SQL
  seeds do. Fixed the `run-hierarchy-matrix.sh` way (`path = CAST('catalog_' || replace(id,'-','') AS
  ltree)`), with the conflict arm also repairing `path` (a failed run leaves a NULL-path row).
  **App/library code untouched — T1–T4 stayed frozen**; the failure was the rig seed, and the
  exception firing is the hierarchy slice's fail-closed posture working as designed.
- Delegation per the policy: a read-only scout swept the suite for list-consuming assertions (its
  finding — 3 collections/8 sites — drove the migration); a validation agent ran the 8 log-noisy
  runners and reported the failure summary. All code/fixes written in the main loop.

## Integration / e2e

This ticket *is* the e2e proof — see the table above.

## Decisions

- E2 includes a past-the-end request (E2d): cheap, and it proves count-stability through the gateway,
  not just in ITs.
- Mulch: the NULL-path seed gotcha recorded immediately as an `opa-abac` failure record (the
  "rig gotcha discovered mid-run" class) + synced (`.mulch`-only commit); the slice's pattern records
  land in T6 as planned.

## Commit

`test(pagination): e2e pagination matrix + dedicated 7777 fixture set + suite-wide envelope migration`
