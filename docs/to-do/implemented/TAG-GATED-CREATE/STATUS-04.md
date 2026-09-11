---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/spring
  - area/catalog-service
---

# STATUS — T4: the tag matrix's 7a–7g on gated-writer (+TAG) and the gated-direct rebind, the matrix row (E1–E7)

**Status:** ✅ done — 2026-09-11 (collaborative build)

## What shipped

- `scripts/postman/run-tag-matrix.sh`: `gated-writer` gains `TAG` on the catalog; a second bootstrapped
  role `gated-direct` (`category`/`product` READ+WRITE+TAG, no catalog permission, the same `region`
  requirement); `--env-var gated_uid`; an **OPA restart + real-decision poll** block before the tokens
  are minted (the `run-supervised-scope-matrix.sh` shape; `OPA_CONTAINER`/`OPA_URL` overridable); the
  header comment's cell list gains 7a–7g.
- `scripts/postman/tag-abac-matrix.postman_collection.json`: 16 new items — `7a`/`7a-ii`, `7b`/`7b-ii`,
  `7c`/`7c-ii`, `7d-i`…`7d-iv`, the `[bind]` rebind (`POST {{user_service}}/internal/bootstrap/memberships`
  → 200), `7e`/`7e-ii`, `7f`, `7g`/`7g-ii`; every deny cell asserts the RFC-7807 body; every allow cell
  captures its id (`pm.collectionVariables.set`) and is followed by an owner cleanup (204); the two
  root cells re-list the root and assert the exact fixture set (count 3 + the three names — a positive
  assertion, never absence-only). Variables `gated_uid`, `match_product_id`, `movable_id`,
  `direct_product_id` declared. `info.description` gains the ADR 0034 sentence.
- `scripts/postman/README.md` (the matrix row) and `docs/guides/E2E-TESTING.md` §"Tag-based ABAC matrix"
  (the table gains 6a/6b and 7a–7g + the bind step; "9 requests / all 9 green" → 16 + the restart note).

## Tests

- `scripts/checks/check-collection-conformance.py`: 18 collections clean (no waiver needed);
  `scripts/checks/check-shell-guards.py`: 27 scripts clean; `bash -n` clean.
- **Live, through the gateway** (rig on the T3 catalog image + the T2 MCP image, OPA restarted by the
  runner): `run-tag-matrix.sh` → **25 requests, 46 assertions, 0 failed**. 6a/6b unchanged with TAG
  added (6b sends a name only). 7a: the gated writer under the untagged root → 403; 7b: a matching
  product under apac → 403, the owner's list count 0; 7c: under emea → 201 + cleanup 204; 7d: the
  nested create → 201, the move under apac → 403, the owner's GET shows the parent unchanged, cleanup
  204; `[bind]` → 200; 7e: the direct path, a matching payload under the untagged root → 403; 7f → 403;
  7g → 201 + cleanup 204. Teardown left the fixture world as found.
- `run-demo-world-matrix.sh --skip-matrices` green afterwards (E31 + the idempotency re-seed) — no shared
  fixture touched.

## Architecture review + refactor

Filled at the ticket's checkpoint: the review path used (the collaborative build's per-ticket read-through; the slice-level passes are recorded in STATUS-05), what it found, what was refactored (or "nothing substantive").

## Integration / e2e

The live run above is the integration proof; the rig runs `ENABLE_MCP=1 ./deploy.sh up --pods 2` on the rebuilt images (the first `deploy.sh build` died on the known in-container `UnknownHostException: github.com` DNS flake and succeeded on retry).

## Decisions

- The direct path gets its own persona by **rebinding** the same realm user (`bob`) rather than a fifth
  realm user: one role per user per team, the `permission-categories-matrix` precedent, no realm change.
- Each allow cell cleans up after itself (owner DELETE → 204) so the root-list cells can assert the
  exact fixture set and the matrix stays order-independent of the teardown cascade.
- The runner restarts OPA unconditionally (the placement clauses are what the cells assert); the poll
  waits on a real decision, not `/health`.

## Commit
