---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/user-service
---

# STATUS — T7: e2e: the permission-categories matrix + the suite-wide payload migration

**Status:** ✅ DONE (2026-06-12)

## What shipped

- `run-permission-categories-matrix.sh` + `permission-categories-matrix.postman_collection.json`
  (27 items) on the dedicated fixture catalog **`99999999-9999-9999-9999-999999999999`** (README
  registry row added). Hygiene: `set -euo pipefail`, in-network token mint, **OPA restart +
  30×1s health-poll** (this slice rewrites every policy), idempotent re-run, `bash -n` + `jq .`
  clean. Novelties this matrix needed:
  - **One ladder subject, REBOUND in-collection** between cell groups via the internal bootstrap
    API (one role per user per team; four realm users ≪ the role count).
  - **Two sanctioned DB-seeded bypass roles** (the E5 class): `pc-stale` (flat tokens — the
    authoring API rejects them by design) and `pc-super20` (`GRANT` at level 20 — unauthorable;
    E3d's subset-violating candidate, the only way to exceed a wildcard senior's effective set).
  - **The admin-denial window** (E4c, the T5 carry-note resolved): the SYSTEM administrator row
    temporarily gains `denied_actions {"*":["delete"]}` (a custom level-30 code has no
    `team:manage`), **trap-reverted on ANY exit** — verified reverted post-run.
- The cells, all green: E1 deny-overrides (PUT 200 / DELETE 403); E2 the TAG/WRITE boundary in
  BOTH directions (the delta dispatch live through the gateway); E3 senior delegation — the
  member-level grant proves **`data.role.assignable` LIVE** (201), peers/admin/subset-violating →
  three 422 `ROLE_SUBSET_VIOLATION` cells; E4 admin tier (below 201, peer 422, **the designed
  cell** 200); E5 the stale flat role denies everywhere (∅-expansion); E6 ladder parity
  (reader 200/403/403; member 200/200/204).
- **The nine-runner payload migration** (pinned semantic #3, mechanical): every `custom-roles`
  payload across `run-team`/`tag`/`filter`/`pagination`/`hierarchy`/`hierarchy-list`/
  `resource-resolution` runners — `["read"]` → `["READ"]` + `roleLevel 10`;
  `["read","write"]` → `["READ","WRITE","TAG"]` + `roleLevel 20`; `{}` stays (+ 10). Plus the
  one `roleCode "viewer"` → `"reader"` binding (team matrix). Collections verified wire-shape-
  agnostic on permissions (zero edits).
- `scripts/postman/README.md`: the new runner row + the `9999…` fixture-registry row.

## Integration / e2e — the acceptance run (rig rebuilt from this branch)

`./deploy.sh build` + the **explicit usermgmt image build** + `ENABLE_OIDC=1
ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2`. Liquibase 0006 ran against the persistent
usermgmt DB (migration + the defensive flat-token sweep, observed in the boot log).

| Runner | Assertions | Failed |
|---|---|---|
| permission-categories (NEW), pass 1 | 27 | 0 |
| permission-categories (NEW), pass 2 — idempotency | 27 | 0 |
| run-tests.sh (lifecycle) | 19 | 0 |
| run-matrix.sh (role) | 19 | 0 |
| run-team-matrix.sh | 11 | 0 |
| run-tag-matrix.sh | 12 | 0 |
| run-filter-matrix.sh | 16 | 0 |
| run-hierarchy-matrix.sh | 4 | 0 |
| run-hierarchy-list-matrix.sh | 10 | 0 |
| run-pagination-matrix.sh | 27 | 0 |
| run-resource-resolution-matrix.sh | 12 | 0 |

**E7: zero flipped cells** across the whole migrated suite.

## Architecture review + refactor (the fix-until-green log)

- **Loop #1 (collection shape)**: newman rejects a bare `{"raw": …}` url object ("request url is
  empty") — regenerated every url with `host` + `path` arrays (the existing collections' shape).
- **Loop #2 (THE stop-and-investigate)**: E1b's first run answered **204, not 403** — the
  fixture's catalog-wide `WRITE` let `inherited_grant` cover the category delete through the
  ANCESTOR type's effective set, bypassing the category-type denial. Investigated against
  00-DESIGN §2.9: **per-type subtraction with inheritance through the ancestor type's effective
  set is exactly what the design pins** (and `test_inherited_grant_respects_ancestor_denial`
  covers the ancestor-side denial); the QA E1 cell's letter is "grants WRITE **on category**" —
  the fixture had over-granted. Fixed the fixture (catalog → READ only), documented the adopter-
  facing subtlety in the runner: **a leaf-type denial does not veto an ancestor-type grant — to
  fence an action subtree-wide, deny it on every granted type (or on `"*"`)**. Carried to T8's
  PERMISSION-MODEL guide.
- E6's create cell consciously omitted: type-level creates resolve no team role (5.97 design —
  the supplier needs an instance to find the governing root) and ride the realm fallback —
  documented in the runner header; instance-level update/relabel/delete pin the member tier.

## Decisions

- The in-collection rebind pattern; the two DB-bypass roles; the trap-reverted admin-denial
  window; the E1 fixture semantics (all above).

## Commit

`feat(e2e): permission-categories matrix + suite-wide category-token payload migration (Phase 6.5 T7)`
