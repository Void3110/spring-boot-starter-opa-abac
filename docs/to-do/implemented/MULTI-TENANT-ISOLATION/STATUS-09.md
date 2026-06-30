---
tags:
  - status/done
  - type/project
  - area/abac
---

# STATUS — T9: demo users alice/bob/carol + seed + e2e isolation matrix + docs

**Status:** ✅ DONE

## What shipped

- **The headline isolation matrix** — `scripts/postman/isolation-matrix.postman_collection.json` +
  `scripts/postman/run-isolation-matrix.sh`. E1–E7 (20 assertions) prove Slice B4 end-to-end through
  the gateway: membership is the **sole** access path + safe self-service.
  - E1 alice (fresh, `catalog-editor`, no team) `GET /catalogs` → `[]` (no fallback leak).
  - E2 alice POST catalog → POST team(her catalog) → `GET /catalogs` → `[hers]` (self-service works).
  - E3 bob (fresh) → `[]`. E4 alice adds bob → bob → `[Alice's]` (scoped to the team, not his own).
  - E5 carol (own pre-seeded team + member of Alice's) → **2** catalogs (multi-team union).
  - E6 bob deep-links Carol's catalog by id → **403** (no direct-id leak).
  - E7 bob `POST /teams` targetId=Alice's catalog (squat) → **403 ACCESS_DENIED** (T7 ownership).
  - The runner **self-resets** (wipes Alice Co / Carol Co catalogs+teams by name first) so it is
    idempotent — the matrix itself is not (E2 creates Alice's catalog LIVE).
- **Demo cast** — `infra/keycloak/realm-export.json` gains **alice / bob / carol** (`catalog-editor`),
  so a fresh rig build carries them; on a running Keycloak they were added via the admin API.
- **The type-level-gate fix** (the e2e surfaced three regressions the design's blast-radius missed —
  all from T1's realm-fallback removal breaking type-level gates under the http/membership profile):
  1. **A new library seam** — `@OpaPreAuthorize(roleResourceType, roleResourceId)` (in
     `OpaPreAuthorize.java`, applied in `OpaPreAuthorizeAuthorizationManager.withRoleResourceOverride`):
     a type-level gate (no resourceId) resolves the caller's **role on a governing parent** while the
     decided resource/policy stays the child. Fail-closed when the override is declared but unresolvable.
     Wired on `CategoryController` list+create, `ProductController` list+create, and
     `TagDecisionGate.requireCategoryAssignTagsForCreate(UUID catalogId)`. The pre-existing verb-agnostic
     `list_inheritable_grant` clause does the rest; `product.rego` got the same clause added. A non-member
     resolves no role → still denied (isolation intact). +2 security unit tests
     (`roleResourceOverride_resolvesRoleOnParent`, `…_unresolvable_deny`).
  2. **`id: null` vs absent in Rego** — `AbacContext.Resource` serializes a type-level null id as
     `"id":null`, and `not input.resource.id` is **UNDEFINED (not true)** for an explicit null, so the
     type-level `allow` clause never fired for a tag-gated reader. Fixed with a null-safe
     `is_type_level_request` helper (two bodies) in `category.rego` + `product.rego`. Core untouched
     (fixed in rego, per the slice invariant).
  3. **Honest assertion updates** — `data-filter-matrix` "stranger" cell `200+[]` → **403** (a no-role
     subject is denied at the coarse gate — the B4 behavior, *more* secure); `resource-resolution` E4
     `200` → **403** (a non-member of a separately-governed catalog is denied — the realm fallback that
     used to decide there is gone).
- **Two fixture-matrix reworks** (their SETUPS created fixtures via the removed fallback):
  - `run-permission-categories-matrix.sh` — added a `pc-creator` catalog-WRITE+TAG role + bound the
    fixture creator, so the per-cell category creates resolve a role and inherit `create`.
  - `run-resource-resolution-matrix.sh` + its collection — the "free" catalog now has its **own** team
    + a `rr-free-creator` membership for the fixture creator; the E4 subject is a non-member there → 403.
- **Docs reconciled** — `docs/guides/TEAM-BASED-AUTHORIZATION.md` + `PARTIAL-EVALUATION-FILTERING.md`
  (squat closed; the catalog list is membership-scoped). `infra/README.md` gains an isolation-matrix
  entry. `POC-ROADMAP` B4 row → ✅ SHIPPED.

## Tests

- **`opa test infra/opa/policies/` → 197/197**; `opa check --strict` clean (new create/list/assign-tags
  inheritable-grant tests + non-member-denied tests in `category_test.rego` / `product_test.rego`).
- **`./gradlew build` green** — all library modules + example app + ITs, incl. the security suite with
  the 2 new `roleResource` tests.

## Integration / e2e (live rig, through the gateway)

The whole existing suite was re-run against the B4 rig — **fully green**, 0 failures:

| Matrix | Assertions | Note |
|--------|-----------:|------|
| **isolation** (the headline) | 20 | E1–E7; now self-resetting |
| permission-categories | 31 | reworked (pc-creator membership) |
| resource-resolution | 12 | reworked (free-catalog team; E4 200→403) |
| filter | 15 | stranger cell honestly updated 200→403 |
| tag | 12 | |
| hierarchy | 4 | |
| hierarchy-list | 9 | |
| pagination | 27 | |
| action-enrichment | 14 | |
| team | 12 | |

> **resilience (B3) is excluded** — it runs on a **mutually-exclusive rig profile**
> (`ENABLE_RESILIENCE_STUB=1` repoints the catalog at `resolve-stub:8080` instead of the real
> user-service; B4 needs `ENABLE_USER_SERVICE=1`). B4 made no resilience change; running it here would
> require tearing down the user-service rig, which would break every B4 matrix. Not a regression.

## Architecture review + refactor

- **The single most important finding:** T1's realm-fallback removal was load-bearing for **every
  type-level `@OpaPreAuthorize` gate** under the http profile — list AND create AND tag-on-create — not
  just the list-rows the design called out. The fix is a clean library seam (resolve the role on the
  governing parent) rather than re-opening any fallback; a non-member still resolves no role and is
  denied, so isolation is preserved.
- **Honest assertions over green-at-any-cost:** two cells that encoded the OLD fallback semantics were
  flipped to the (more secure) B4 behavior rather than papered over — both deny a no-role subject.
- **Fail-closed preserved throughout.** `opa-abac-core` untouched (the `id:null` issue was fixed in
  rego, not by adding `@JsonInclude` to core).

## Decisions

- **T4 list gate:** the `CatalogListAuthorizer` (governed-scope ∧ filter-residual, fail-closed to empty)
  is the **sole** authority for the catalog list; the coarse `@OpaPreAuthorize(catalog:list)` gate was
  dropped (no second gate to keep in sync).
- **Type-level role resolution:** resolve the caller's role on the **parent catalog** (the governing
  root) via the new annotation override, rather than re-introducing a type-level fallback.
- **resource-resolution E4 reframed:** under B4 every catalog is governed, so the old "team-less catalog
  rides the fallback" cell becomes "a non-member of a separately-governed catalog is denied (403)."

## Commit

One commit on `feature/void3110/multi-tenant-isolation` (identity `Void3110`):
`feat(isolation): e2e isolation matrix + demo cast + type-level-gate fix (B4 T9)`.
