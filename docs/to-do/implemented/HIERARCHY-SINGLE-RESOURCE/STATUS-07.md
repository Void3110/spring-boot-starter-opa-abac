---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/docs
---

# STATUS T7 — e2e (incl. the mandatory re-parent test) + docs + roadmap/Mulch

> Filled in at the T7 checkpoint during the autonomous run. One commit per ticket.

## What shipped

- **`run-hierarchy-matrix.sh` + `hierarchy-abac-matrix.postman_collection.json`** — the through-the-gateway
  allow/deny matrix for Phase 5.5-A. It grants the reader `read` on the **Catalog** and proves:
  - **inheritance** — a Category nested under the Catalog is readable (200);
  - **deny-overrides** — a Category carrying `abac_deny=true` is carved out (403) while a **sibling** stays
    readable (200);
  - **re-parent flips a decision** — the movable Category is readable while under the granted Catalog (200),
    and after it is re-parented under a foreign Catalog the reader can't see (an ltree subtree + product
    rewrite applied to the DB in one tx), the read **flips to 403**.
  It mints in-network tokens, bootstraps the team/role/membership via the user-service, creates the
  Categories through the gateway, runs the newman checks, then performs the re-parent + the flip assertion.
- **`docs/guides/HIERARCHICAL-AUTHORIZATION.md`** — the feature guide (the idea, the pieces, fail-closed,
  opt-in/default-off, the Rego clause, the ltree-vs-CTE resolver SPI, the `ltree` path + atomic/cross-table
  re-parent, the adoption recipe, what's out of scope). `scripts/postman/README.md` gains the matrix row.
- **`POC-ROADMAP`** — Phase 5.5 flipped to **🟡 5.5-A shipped · 5.5-B planned** (narrative + table row).
- **Mulch** — the resolver-SPI, the hierarchical-entity/atomic-reparent, the rego-inheritance-clause, the
  cross-table-reparent, the starter-wiring patterns + the `subpath` invalid-positions failure are recorded.

## Tests

- **`bash -n run-hierarchy-matrix.sh`** clean; the collection JSON is valid.
- **`opa test infra/opa/policies/` — 72/72**; **`./gradlew build` green** throughout (the library unit +
  Testcontainers ITs, the example `ddl-auto: validate` boot + `HierarchyAdoptionIT`, the starter
  `ApplicationContextRunner`).
- **Live newman run — ✅ GREEN through the gateway.** The full OIDC rig was brought up
  (`ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2`, image rebuilt with the hierarchy code —
  the pods booted clean, confirming the 0003 ltree migration ↔ entity mapping under `ddl-auto: validate`
  **in the deployed image**, and OPA served `data.product.inheritable`). `./run-hierarchy-matrix.sh` →
  **all 4 newman assertions pass + the re-parent flip passes**: (1) inheritance — a Catalog-granted member
  reads a nested Category → **200**; (2) deny-overrides — the `abac_deny` Category → **403**; (3) the
  sibling stays **200**; (4) the movable Category is **200** before the move; then re-parenting it under a
  foreign Catalog (ltree subtree + product rewrite) flips the read to **403**. (Earlier "Docker unreachable"
  was a false negative — `timeout` isn't on the shell PATH, so the daemon probe silently errored; the
  maintainer's podman→Docker-Desktop switch + probing the socket directly recovered it.)
- **One integration finding the live run surfaced:** the deny-overrides `abac_deny` tag is (correctly)
  rejected by the Phase-4.5 **tag-dictionary** validation on create — it's an operational control flag, not
  a user-facing dictionary tag. The matrix sets it **out-of-band via SQL** (the same way it seeds the
  catalogs), and `create_category` now surfaces a clear error on a non-`id` body instead of a bare
  Python traceback.

## Architecture review + refactor (the ★ gate)

- **Single-resource only** preserved end to end — the e2e exercises the Category single-GET (now the library
  walk via `CategoryAuthorizer`); the list filter is untouched (5.5-B).
- The re-parent in the e2e mirrors `CatalogHierarchyService.reparentCategory` (the same ltree subtree +
  product rewrite), applied via psql so the matrix needs no re-parent endpoint — a documented, deliberate
  choice consistent with how the other matrices seed runtime data.
- Nothing substantive to refactor in the e2e/docs; the script reuses the established matrix idioms (in-network
  token mint, user-service bootstrap, runtime-captured ids).

## Integration / e2e

The matrix is the through-the-gateway proof (to be run when the rig is up). The equivalent behaviors are
covered now by `opa test`/`opa eval` (policy) + `HierarchyAdoptionIT` (resolver + cross-table re-parent vs
real Postgres). See the *Tests* note above.

## Decisions

- **The e2e re-parent runs via psql, not a new endpoint** — it applies the exact ltree subtree+product
  rewrite the in-app service does, so the matrix proves "a re-parent flips a decision" without expanding the
  OpenAPI surface this slice.
- **Live newman deferred to the maintainer** (Docker unreachable here) — committed runnable, behaviors
  independently proven. Noted explicitly rather than silently skipped.

## Commit

`test(e2e),docs(hierarchy): hierarchy matrix + guide + roadmap (Slice 5.5-A shipped)` — see the T7 commit
on `feature/void3110/hierarchy-single-resource`. The package then moves to `docs/to-do/implemented/`.
