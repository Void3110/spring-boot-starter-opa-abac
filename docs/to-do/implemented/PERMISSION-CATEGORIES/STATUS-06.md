---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/user-service
---

# STATUS — T6: catalog: the action-string sweep + the conditional `assign-tags` second decision

**Status:** ✅ DONE (2026-06-12)

## What shipped

- **The 15-annotation sweep**: GET-one → `<type>:view`, GET-list → `<type>:list`, POST →
  `<type>:create`, DELETE → `<type>:delete` across `CatalogController`/`CategoryController`/
  `ProductController`. **Catalog and product PUTs keep a static `<type>:update`** (see Decisions);
  the **category PUT dropped its static annotation** for delta dispatch. The programmatic sweep:
  `CategoryListAuthorizer`'s compile-path action → `category:list` (+ doc comments in
  `TagAssignmentService`).
- **`config/TagDecisionGate`** (new bean — the manager seam reused; zero library change): three
  annotated methods — `requireCategoryUpdate(id)` (`category:update`), `requireCategoryAssignTags(id)`
  (`category:assign-tags`), `requireCategoryAssignTagsForCreate()` (type-level) — calls cross the
  bean boundary (never self-invocation).
- **`CategoryController.updateCategory`**: deltas computed first — tags = raw request map vs the
  entity's current tags (null = empty; clearing IS a change; raw-side compare can only over-ask),
  content = name/description/parentId — then dispatch: content → `update`; tags → `assign-tags`;
  both → both (update first); **empty → `update`** (the conservative default). All decisions run
  before the version guard, the tag validation (so an unauthorized caller learns nothing from the
  422 vocabulary), and any mutation. The 5.97 `VersionGuard` flow is unchanged.
- **`createCategory`**: static `category:create` + iff the request carries tags, the type-level
  `assign-tags` decision before anything persists.

## Tests

`./gradlew build` green (all modules). **`TagDecisionGateIT`** (new; the ResourceResolutionGateIT
harness shape with a per-ACTION programmable stub recording the asked sequence):
- I13 tags-delta-only → 200, sequence exactly `[category:assign-tags]` (update would deny), tags
  persisted; I14 content-only → `[category:update]`; both deltas → `[update, assign-tags]` in
  order; I15 denied `assign-tags` → 403, row byte-identical (name+tags+version) — the deny
  precedes mutation; **the mirror cell** (TAG-only holder edits content → 403 via `update`, row
  untouched); I16 create-with-tags denied at type level → 403 + nothing persisted; bare create →
  `[create]`; empty-delta PUT → `[update]`; GET-one/GET-list/DELETE → `view`/`list`/`delete`.
- `ResourceResolutionGateIT` re-pinned: the I4 gate-window race moved to the CATALOG PUT (the
  category update no longer has an annotation→handler window); the missing-id PUT half → **404**
  (see Decisions); the I6 GET half keeps the 403 pin.
- Token hygiene: `HttpRoleDefinitionSupplierTest` wire fixtures → category tokens;
  `CategoryTagAssignmentIT` comment updated. The CRUD/concurrency suites ride the allow-all stub
  (action-agnostic) — unchanged-green.

## Architecture review + refactor

- **Reachability finding (design-level, documented)**: only `CategoryRequest` carries `tags` on
  the wire — catalog/product requests have no tags field, so a tags delta is **not constructible**
  for them and their delta dispatch would have an unreachable `assign-tags` branch (dead code by
  the wiring rule). The dispatch therefore lives on the category handlers only; catalog/product
  PUTs keep static `:update`. The decomposition's "three update handlers" assumed all three carry
  tags; the REST surface is pinned unchanged, so this is the faithful reading.
- **Contract change surfaced**: the category PUT's missing-id answer flips 403 → **404** — the
  5.97 pin ("missing id behind an ANNOTATED resourceId → 403") no longer has its precondition on
  this handler (the load necessarily precedes the dispatch). The e2e pinned cell is a GET
  (unchanged); no runner pins a missing-id PUT. Carried to T8's guide reconciliation.
- **Review-added cell**: the boundary's update-denied direction (above) was untested — added.
- **Maintenance coupling noted**: if `CategoryRequest` ever gains a non-tag field, the content
  delta must include it (the dispatch comment says so).

## Decisions

- Dispatch on the **category** handlers only (reachability, above).
- Tags delta compares the RAW request map (over-asks at worst — fail-closed direction).
- Missing-id category PUT = 404 (above).

## Commit

`feat(catalog): fine-verb action sweep + delta-aware assign-tags dispatch (Phase 6.5 T6)`
