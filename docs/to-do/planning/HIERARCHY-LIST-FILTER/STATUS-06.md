---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
---

# STATUS T6 — e2e matrix (through the gateway) + docs + roadmap/Mulch

> Filled at the T6 checkpoint. One focused commit. ✅ shipped.

## What shipped

- **The coarse list-gate rego clause** (`infra/opa/policies/category.rego`) — a small additive `allow` clause
  for a **list** request (no resource id): it passes the type-level `@OpaPreAuthorize(<type>:read)` gate when
  the subject's role inheritably grants the verb on a declared ancestor type (`data.category.inheritable`),
  so an inheritable-catalog-grant subject can reach the list and the **fine** which-rows cut still happens in
  SQL (the `subtreeSpec`). Scoped to a list (no id) → single-resource decisions unchanged; opt-in/default-off;
  a true stranger still denied. (See **Decisions** — this gap was surfaced by the e2e and settled with the
  maintainer.)
- **The e2e suite** — `scripts/postman/hierarchy-list-matrix.postman_collection.json` +
  `scripts/postman/run-hierarchy-list-matrix.sh` (modeled on the shipped `run-filter-matrix.sh` +
  `run-hierarchy-matrix.sh`). It seeds an **inherit reader** (read on the catalog only — no category tag
  grant), a **region reader** (`category:read` gated to `region=emea`), and leaves the `outsider` **unbound**;
  three Categories (emea / apac / one `abac_deny`); runs newman PRE re-parent, then re-parents the apac
  Category under a foreign catalog (the same ltree subtree rewrite `CatalogHierarchyService.reparentCategory`
  does), then newman POST.
- **Docs** — `docs/guides/PARTIAL-EVALUATION-FILTERING.md` (a new hierarchy-aware list section) +
  `docs/guides/HIERARCHICAL-AUTHORIZATION.md` (the list analogue) + `docs/guides/E2E-TESTING.md` (the new
  matrix table) + `scripts/postman/README.md` (the new runner row). `POC-ROADMAP.md` Phase 5.5 marked
  **✅ DONE (5.5-A + 5.5-B)**.

## Tests

- **`opa test infra/opa/policies/` — 77/77** (was 72/72): the 5 new list-gate tests (passes for an
  inheritable-ancestor grant; denies when inheritance is off; denies the stranger; respects deny; does **not**
  loosen single-resource decisions). `opa check` clean.
- **The e2e matrix — ran GREEN through the full gateway** (APISIX → catalog pod with the 5.5-B code → OPA →
  Postgres), **10/10 assertions across the pre + post re-parent passes**: E1 (PRE) inherit reader → the whole
  subtree (emea **+** apac, minus the denied row); E2 region reader → only emea (a different set); E4 stranger
  → empty list; E1 (POST) → the re-parented apac row **left** catalog C's widened list. The inherit reader
  passed the coarse gate via the new `allow` clause (confirmed `data/category/allow` → `true` for it).
- The whole slice's deterministic backbone — `./gradlew build` green (all modules + both example apps + the
  Testcontainers ITs incl. **`HierarchyListFilterIT`** proving widening / two-subjects / `notDenied` /
  **no-leak** / **re-parent-on-list** against real Postgres) — is the proof the e2e demonstrates.

## Architecture review + refactor (the ★ gate)

- **Fail-closed:** the list-gate clause **only opens the coarse gate**, never widens rows (the SQL cut is
  unchanged); it AND-s `not denied`; a stranger with no inheritable grant is still denied (`opa test` proves
  all three). It is scoped to a list (no resource id), so single-resource `allow` is byte-unchanged (a
  dedicated test pins this).
- **Boundary:** `opa-abac-core` untouched; the shipped residual / operator set / `RoleDefinition` /
  `filter` / `bulk` rego rules untouched (the clause is additive to `allow`). The `inheritable` OPA data is
  the same one 5.5-A ships — no new data.
- **Refactor applied:** none beyond the clause the e2e required.

## Integration / e2e

- **Ran GREEN through the full local rig** (`ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2`,
  `./deploy.sh build` to force the 5.5-B catalog code into the pods, under Docker Desktop): the
  `run-hierarchy-list-matrix.sh` matrix passed **10/10** assertions across the pre + post re-parent passes.
  The deterministic equivalent (`HierarchyListFilterIT`, real Postgres) proves the identical cuts via the
  real composition at the persistence layer.

## Decisions

- **The coarse list gate needed a rego clause (settled with the maintainer).** The e2e exposed a gap the
  planning docs didn't pin: the type-level `@OpaPreAuthorize(category:read)` on `listCategories` evaluates
  `allow` with **no ancestors**, so a `{catalog:[read]}` inheritable subject (the one the widening exists for)
  was denied 403 at the gate before `subtreeSpec` could run (`opa eval` confirmed `allow=false`). Of the
  options (add a list `allow` clause / give the subject `category:[read]` / drop the gate), the maintainer
  chose **add a list `allow` clause** — the most design-faithful (keeps the coarse gate meaningful, the fine
  cut in SQL, the stranger denied). This is the run's one design fork the docs didn't cover; it is now pinned
  in `category.rego` + 5 tests + the guides. *(Feeds back into planning: a list-widening slice must also
  open the coarse list gate for inheritable grants.)*

## Commit

`feat(opa,e2e): coarse list-gate clause + hierarchy-list e2e matrix + docs + roadmap (5.5-B shipped)` on
`feature/void3110/hierarchy-list-filter`.
