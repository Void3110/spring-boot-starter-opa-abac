---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/spring
  - area/catalog-service
---

# STATUS — T3: the example gates, the placement call on re-parent, TagGatedCreateIT (I1–I6)

**Status:** ✅ done — 2026-09-11 (collaborative build)

## What shipped

- `CategoryController.createCategory`: the annotation declares the placement parent
  (`parentResourceType = "#request.parentId != null ? 'category' : 'catalog'"`,
  `parentResourceId = "#request.parentId != null ? #request.parentId : #catalogId"`) and the raw payload
  (`attributes = "#request.tags"`); two private helpers (`placementParentType` / `placementParentId`)
  compute the same pair for the gate methods. `updateCategory`: when `parentId` changes, a
  `requireCategoryPlacement(catalogId, newParentType, newParentId, request.getTags())` call **after** the
  update/assign-tags dispatch and **before** `guardGateSnapshot` — every decision precedes every write;
  an unchanged parent asks nothing. A `CATALOG_TYPE` constant replaces the tripled `"catalog"` literal.
- `ProductController.createProduct`: `parentResourceType = "'category'"`, `parentResourceId =
  "#categoryId"`, `attributes = "#request.tags"`.
- `TagDecisionGate`: `requireCategoryAssignTagsForCreate(catalogId, parentType, parentId, tags)` and
  `requireProductAssignTagsForCreate(catalogId, categoryId, tags)` carry the same declarations
  (`#parentType` / `#parentId` / `#tags`); new `requireCategoryPlacement(catalogId, parentType, parentId,
  tags)` — a type-level `category:create` with the new parent declared.
- Docs: `TAG-BASED-AUTHORIZATION.md` §"Getting the tags to OPA" gains the create declarations, the
  re-parent rule and the 403-before-422 order; `DEMO-CONSOLE-WALKTHROUGH.md` §3.4's "Known gap (DEF-1,
  planned)" callout is now the shipped behavior.

## Tests

- `TagGatedCreateIT` (Testcontainers Postgres; 11 cells — I1–I6) on the `TagDecisionGateIT` harness,
  whose `ActionAwareOpaClient` now records the decided contexts (`askedContexts`) beside the action
  names: a top-level create carries the catalog as `parent_attributes` (and `root_attributes`, unchanged)
  with an empty payload; a nested create carries the parent **category**; a product create carries the
  category, not the catalog; the assign-tags decision carries the same inputs and the sequences are
  unchanged (`[create, assign-tags]` with tags, `[create]` without); a denied create with an **unknown**
  tag key answers 403 `ACCESS_DENIED` (never 422) with the raw map on the wire and nothing persisted
  (category and product); a re-parent asks `[category:update, category:create]` with the **new** parent's
  tags, a denied placement leaves `parentId` **and the ltree path** unchanged; the same parent asks no
  placement; a move to the root asks the placement with the catalog as parent; a missing parent is
  **absent** on the wire and a 404 from the body.
- Module suite: **289 tests, 0 failures** (was 278 + 11); `TagDecisionGateIT` 22 unchanged,
  `CategoryTagAssignmentIT` / `ProductTagAssignmentIT` unchanged. Local Sonar: one S1192 (the `"catalog"`
  literal ×3) → constant → **CLEAN**.

## Architecture review + refactor

Filled at the ticket's checkpoint: the review path used (the collaborative build's per-ticket read-through; the slice-level passes are recorded in STATUS-05), what it found, what was refactored (or "nothing substantive").

## Integration / e2e

The rig: `ENABLE_MCP=1 ./deploy.sh build` (the catalog image now carries T3; the MCP image the T2 starter) + `docker restart opa-abac-opa` (T1's policies) — done at the end of this ticket so T4's cells run against it.

## Decisions

- The placement call sits after the update/assign-tags dispatch: the existing questions keep their
  order and their cells (`TagDecisionGateIT` unchanged); the placement is a third question only when
  the parent actually moves.
- The gate methods grew parameters rather than a request object — the SpEL on the annotation reads
  `#parentType` / `#parentId` / `#tags` by parameter name, which is the shape the rest of
  `TagDecisionGate` already uses.
- I5 asserts `getPath()` (the ltree lineage) as well as `parentId` on the denied move — a re-parent
  that rewrote the path but not the adjacency would still be a move.

## Commit
