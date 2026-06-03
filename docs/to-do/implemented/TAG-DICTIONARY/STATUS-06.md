---
tags:
  - status/done
  - type/project
  - area/abac
  - area/user-service
---

# STATUS — Ticket 06: e2e matrix (tag-gated allow/deny) + docs + roadmap/Mulch + ship

> Filled in at the ticket-06 checkpoint during the autonomous run. See [[01-DECOMPOSITION]] ticket 6.

**Status:** ✅ done

## What shipped

The full loop proven through the gateway, the docs that tell the story, and the slice shipped.

- **Infra / seed (run-time):** `scripts/postman/run-tag-matrix.sh` mints in-network tokens, seeds a demo
  catalog as a team-target, bootstraps two tag-gated roles (`regional-reader` requiring `region` ANY_OF
  `[emea]`; `strict-reader` requiring `region:[emea]` AND `sensitivity:[public,internal]` ALL_OF), and
  creates three differently-tagged Categories through the gateway. The internal
  `bootstrap/custom-roles` endpoint was extended **additively** to carry `requiredTags`/`matchMode`.
- **e2e (`tag-abac-matrix.postman_collection.json`, 7 requests):** tag-match read → 200; **the SAME member
  reads the non-matching-tag Category → 403** (the decisive case); ALL_OF both-satisfied → 200 /
  partial → 403; owner defines a team key → 201 / member → 403; illegal assignment → 422.
- **The per-instance load-then-check (`CategoryAuthorizer`):** the decisive proof needs the resource's
  TAGS at decision time, which the type-level `@OpaPreAuthorize` can't see (it runs pre-invocation). So
  the catalog's `getCategory` loads the Category and calls a `CategoryAuthorizer` — **example-app code on
  the library's `OpaClient` + `RoleDefinitionSupplier` beans** — that resolves the role on the governing
  Catalog and passes the loaded Category (tags included) to OPA. The general per-instance/hierarchy path
  is Phase 5; this is the minimal in-scope demo.
- **Docs:** new guide `docs/guides/TAG-BASED-AUTHORIZATION.md` (the three layers, scope, value-type/
  cardinality, ANY_OF/ALL_OF, the Rego match, the load-then-check, who-manages-what, the decisive demo);
  `docs/guides/E2E-TESTING.md`, `scripts/postman/README.md`, `infra/README.md`, and the roadmap
  reconciled. (`docs/TAG-SYSTEM.md` is the *Obsidian* tag vocabulary, unrelated to the ABAC dictionary —
  deliberately left untouched.)
- **Ship:** the index carries a SHIPPED banner; the folder moves to `docs/to-do/implemented/TAG-DICTIONARY/`.

## Tests

- **Tag matrix through the gateway: 7/7 assertions green, twice (stable across reruns).** Run against the
  full rig (`ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2`) with the rebuilt catalog +
  user-service images and the reloaded `category.rego`.
- **Manual `opa eval` probe** (from T5) re-confirmed live: same `regional-reader`, `region=[emea]` → allow,
  `region=[apac]` → deny.
- **Whole-repo `./gradlew build` green** after the `CategoryAuthorizer` + `getCategory` change — every
  pre-existing test (incl. the catalog `CatalogCrudIT`, which exercises `getCategory`) unchanged.
- **`ddl-auto: validate` clean** — the user-service booted in the rig against the Liquibase schema with the
  new `tag_definition` table + the role-def `required_tags`/`match_mode` columns (CC7); the seeded global
  keys and the new columns verified in the DB.

## Architecture review + refactor

- **Additivity / boundary:** `CategoryAuthorizer` is example-app code on the library's public beans — **no
  library change**; the `bootstrap/custom-roles` extension is additive (existing callers omit the new
  fields). ✅
- **Fail-closed:** the authorizer denies on no-subject / no-role / OPA error; the matrix confirms 403/422
  through the gateway. ✅
- **Three-layer separation:** the authorizer performs the **grant** check only (load-then-check passing
  tags to OPA); define/assign untouched. ✅
- **The decisive demo holds** — two Categories, one role, different tags → 200 vs 403, in Rego, through the
  gateway. ✅
- **Documented boundary (not a refactor):** `CategoryAuthorizer` does a minimal Catalog→Category hierarchy
  step + a per-instance check because the *library's* `@OpaPreAuthorize` is type-level by design; the
  general per-instance/hierarchical path is genuinely Phase 5. This is the right place to draw the line —
  the slice demonstrates the grant without pulling Phase-5 work into the library. No churn invented.
- One operational lesson (cost me a rebuild cycle, not a code change): tearing the rig down/up while the
  shared compose network is in use can drop the whole pool — bring base Postgres up first, then the rig,
  then re-run.

## Integration / e2e

The rig + newman tag matrix above (green twice). Clean-room scan of the whole T6 diff clean.

## Decisions recorded

`ml record opa-abac --type pattern` — the **per-instance, tag-aware load-then-check**: when a decision
needs the resource's attributes (tags), the pre-invocation type-level `@OpaPreAuthorize` can't supply them;
do an explicit app-layer check after loading the entity — resolve the role on the governing parent and
call the library `OpaClient` with the loaded entity as the resource so its tags reach
`input.resource.attributes`. Fail-closed on no-subject/no-role/error. Plus the e2e shape: seed tag-gated
roles + differently-tagged resources at run time and assert the same-role/different-tags 200-vs-403
contrast. Relates to the dual-token matrix (`mx-05b2c1`) and the Phase-4.5 design (`mx-94e70d`). `ml sync`
touched `.mulch/` only.

## Commit

One focused commit on `feature/void3110/tag-dictionary`: `test(e2e): tag-based ABAC matrix through the
gateway + per-instance load-then-check, docs, ship (T6)`. The maintainer pushes.
