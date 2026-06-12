---
tags:
  - status/done
  - type/project
  - area/abac
  - area/spring
  - area/opa
---

# STATUS — T6: e2e: resource-resolution matrix (fixture 8888…) + whole-suite coexistence

**Status:** ✅ DONE

## What shipped

In `scripts/postman/`:

- `resource-resolution-matrix.postman_collection.json` + `run-resource-resolution-matrix.sh`
  (mirrors `run-tag-matrix.sh`'s in-network-token + bootstrap seeding; `bash -n` clean, JSON valid).
- **Fixture set (registered in the README registry):** `8888…8888` — the team-governed catalog
  (emea/apac root categories, a nested category under emea, one product under each root, products
  tagged via psql since they have no tag-assignment API); `8888…8889` — the team-**less** catalog
  (the live fallback cell; the registry warns it must never be granted on). Catalog rows are seeded
  **with their ltree `path`** (the run-pagination-matrix.sh model; see Decisions).
- **Subjects:** `viewer` (realm catalog-viewer) and `demo` (realm catalog-editor) both bound to
  `rr-regional-writer` (catalog/category/product read+write, `required_tags {region:[emea]}`
  ANY_OF); `editor` (realm catalog-editor) bound to `rr-catalog-reader` (**read on the catalog type
  only** — so E5's nested read can only pass via the inherited grant, and E3's write has the
  fallback disabled). Fixtures are created through the gateway by `demo` (creates are type-level and
  ride the realm fallback — the Phase-6.5 vocabulary question, unchanged here).
- README: tooling-table row + the two registry rows.

## Tests — the new matrix (12/12 assertions)

| Cell | Result |
|---|---|
| **E1 headline flip** — viewer-realm member, tag-matched write → **200** (pre-5.97: 403); response carries the applied update | ✓ |
| **E2 hole closes** — editor-realm member, tag-mismatched write → **403 ACCESS_DENIED** (pre-5.97: 200 via the tag-blind fallback) | ✓ |
| **E3 narrowing** — read-only role + editor realm → **403** (role definition present → fallback disabled) | ✓ |
| **E4 non-member unchanged** — editor realm, team-less catalog → **200** via the fallback (byte-identical cell) | ✓ |
| **E5 hierarchy parity** — catalog-root read grant authorizes the nested category **at the gate** → 200 | ✓ |
| **E6a/b product sibling (T5 live)** — apac product write → **403**; emea → **200** | ✓ |
| **Pinned semantic #1 live** — missing id behind the annotated endpoint → **403** problem+json (pre-5.97: 404) | ✓ |

## Integration / e2e — suite-wide coexistence (E7)

Rig: `./profile.sh up` → `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh build` → `… ./deploy.sh up
--pods 2`; OPA's loaded policies verified to carry the T5 conjunct via `/v1/policies` before running.

| Matrix | Assertions | Result |
|---|---|---|
| resource-resolution (new) | 12 | ✓ |
| catalog-e2e (`run-tests.sh`) | 19 | ✓ (after the one documented flip below) |
| catalog-abac (`run-matrix.sh`) | 19 | ✓ |
| team (`run-team-matrix.sh`) | 11 | ✓ — **B1 proven live** (user-mgmt byte-identical) |
| tag (`run-tag-matrix.sh`) | 12 | ✓ (tag cells now decided at the gate, same outcomes) |
| data-filter (`run-filter-matrix.sh`) | 16 | ✓ (list paths untouched) |
| hierarchy (`run-hierarchy-matrix.sh`) | 4 | ✓ |
| hierarchy-list (`run-hierarchy-list-matrix.sh`) | 10 + 10 | ✓ |
| pagination (`run-pagination-matrix.sh`) | 27 | ✓ (pinned counts unchanged) |

**404→403 flips, the complete list (one):** `catalog-e2e.postman_collection.json` → "Get product
after delete" — a **missing** (deleted) id through the annotated `getProduct` now answers the gate's
403 (pinned semantic #1). The cell was renamed and re-pinned to 403 with an explanatory comment. No
other cell in any matrix changed.

## Architecture review + refactor

- Every cell asserts the **decision** (an applied update, a problem `errorCode`, the contrast pair) —
  not just a status shape.
- Fixture discipline: dedicated ids only, both registered; the team-less `8889` carries a registry
  warning (the cross-matrix grant-collision lesson).
- The runner's seeding honors the suite conventions: in-network tokens, bootstrap API for
  users/team/roles, gateway creates for dictionary-validated tags, psql only for what has no API
  (catalog rows + product tags).
- Nothing refactored post-run; the one mid-run fix is below.

## Decisions

- **Catalog rows must be seeded WITH their ltree `path`** — the first run failed fail-closed
  ("parent catalog … has no path") because the seed was modeled on `run-tag-matrix.sh`, whose
  pathless insert only works when the row already exists (persistent volume) from earlier sessions.
  Fixed with the `run-pagination-matrix.sh` SQL shape (path + conflict-arm repair).
- The matrix includes the missing-id 403 cell beyond the pinned E1–E7 — it pins semantic #1 live
  where the flip class actually shows.
- E2 deliberately reuses `demo` as both fixture creator and the mismatched writer: creates are
  type-level (no role definition resolves for a null id), so membership does not affect them.

## Commit

`test(e2e): resource-resolution matrix + suite-wide coexistence (T6)` — see `git log` on
`feature/void3110/resource-resolution`.
