---
tags:
  - status/done
  - type/project
  - area/build
  - area/abac
---

# STATUS — Ticket 7: E2E (allow/deny matrix) + docs + roadmap/Mulch

> Filled in at the ticket-7 checkpoint. See [[01-DECOMPOSITION]] ticket 7.

**Status:** ✅ implemented (2026-06-01)

## What shipped
- **`scripts/postman/catalog-abac-matrix.postman_collection.json`** — a new collection with 4 folders:
  editor seeds catalog→category→product (201×3); **viewer reads** list/get-catalog/get-product (200);
  **viewer writes denied** create/update/delete (403); **editor writes** update/delete/cleanup
  (200/204). Per-request `Authorization: Bearer {{viewer_token}}` / `{{editor_token}}`; chained ids in
  COLLECTION scope (mx-ecc3ef).
- **`scripts/postman/run-matrix.sh`** — mints **both** tokens in-network (viewer + editor realm users)
  and injects `viewer_token` + `editor_token`. `bash -n` clean.
- **Env template** — `viewer_token` / `editor_token` added (blank, injected at run time).
- **Docs (new):** `docs/guides/ABAC-AUTHORIZATION.md` (the spine: fail-closed `HttpOpaClient`,
  extraction + **signature-trust posture & loud tradeoff**, `RoleDefinition`/`RoleDefinitionSupplier`
  demo→Phase-4 swap, `@OpaPreAuthorize`, starter properties, adoption recipe) and
  `docs/architecture/TWO-LAYER-AUTHORIZATION.md` (gateway-coarse vs app-fine, enricher retired, per-type
  rego, role-definition-driven).
- **Docs (reconciled):** `docs/guides/E2E-TESTING.md` (the new matrix + `run-matrix.sh`),
  `infra/README.md` (two-layer authz live; enricher retired), `POC-ROADMAP.md` (Phase 3 ✅ done; batch +
  partial-eval still Phase 5).
- **Folder moved** to `docs/to-do/implemented/LIBRARY-SPINE/` with a "Shipped" banner (this index).

## Tests
- **E2E matrix (rig up, `ENABLE_OIDC=1 ./deploy.sh up --pods 2`):** `cd scripts/postman && ./run-matrix.sh`
  → **12 requests, 13 assertions, 0 failed.** Viewer GET 200 (E1), viewer POST/PUT/DELETE **403** (E2),
  editor create→update→delete 201/200/204 (E3), tokens minted in-network (E4). **Stable across two reruns**
  (E5).
- Manual gateway smoke: viewer GET → 200, viewer POST → 403, editor POST → 201, no-token → 302.
- OPA loaded all per-type policies; direct decision checks confirm editor `product:write` allow=true,
  viewer `product:write` allow=false (both `has_role_definition:true`).
- `run-matrix.sh` `bash -n` clean; both collection + env JSON valid.

## Architecture review + refactor
- **Fail-closed proven end-to-end** — the viewer-write 403 is the role-definition-driven OPA deny flowing
  all the way through `@OpaPreAuthorize` → `AccessDeniedException`.
- **Two-layer model realized** — gateway validates + forwards; the app extracts + asks OPA; the enricher
  is gone. Documented in the two new guides with the signature-trust tradeoff stated loudly.
- **Refactor applied:** none — kept the matrix as a *separate* collection/runner (`run-matrix.sh`) rather
  than overloading the plumbing suite, so each suite stays single-purpose. No churn.

## Integration / e2e
This ticket **is** the integration/e2e validation (rig + newman). Green and stable; see above.

## Decisions recorded
Mulch **pattern** `mx-05b2c1` — "Dual-token ABAC allow/deny e2e matrix through the gateway" (mint a
viewer-only and an editor token in-network; assert 200/403/204; the demo user can't show the deny row).
`ml sync` → `.mulch`-only commit.

## Commit
`test(e2e): ABAC allow/deny matrix (viewer/editor) + ABAC + two-layer guides; ship library-spine`.
