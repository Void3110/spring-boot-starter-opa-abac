---
tags:
  - status/done
  - type/project
  - area/abac
  - area/spring
  - area/opa
---

# STATUS — T6: user-mgmt adoption: `TeamEnrichable` (OPA-decided subset) + cross-service e2e

**Status:** ✅ DONE

## What shipped

- **`TeamEnrichable`** (`…usermgmt.security`) `extends Enrichable` → `team`,
  `["list-members","add-member","remove-member"]` — the **OPA-decided subset only**; the Java-co-gated
  escalation verbs (`change-role`/`define-roles`/`transfer-ownership`) are excluded (affordance honesty,
  ADR 0016 §8; a comment cites the rule). The second registry shape (control plane) — proves the per-type
  sub-interface generalizes.
- **`user-mgmt-api.yaml`:** `x-implements: [ …usermgmt.security.TeamEnrichable ]` + the `readOnly`
  `_actions` property on `Team`; regenerated → `Team implements TeamEnrichable` with
  `@JsonProperty("_actions")` `getActions`/`setActions`. **`Membership` is NOT enriched** (out of scope).
- **`TeamResourceResolver`** (`…usermgmt.config`, `@Component`) over `TeamRepository` — the resolver bean
  that activates enrichment for user-mgmt. `Team` already extends the secured base (`abacResourceType()
  ="team"`).
- **The three missing `bulk` rules** (the planning-gap fix — see Decisions): added the identical
  `bulk := [allow with input as item | some item in input.items]` to `catalog.rego`, `product.rego`, and
  `team.rego` (+ the user-mgmt bundle copy, byte-identical), mirroring `category.rego`. Mirrored
  `opa test` cases in each `*_test.rego`. ADR 0016 §6 corrected.
- **e2e:** `action-enrichment-matrix.postman_collection.json` + `run-action-enrichment-matrix.sh` (fixture
  catalog `aaaa…`, registered in the README), plus the README script-list + fixture-registry rows.

## Tests

- **The action-enrichment matrix — 14/14 GREEN, live through APISIX:**
  - **E1a** reader → honest `{view:true, update:false, delete:false, assign-tags:false}` (the headline).
  - **E1b** writer → every verb `true` (the decision contrast).
  - **E2** writer `CategoryPage` → each row a complete `_actions` map, one bulk.
  - **E3** reader page ↔ single agree (same honest map both ways).
  - **E5** **affordance ≠ enforcement** — the reader's `update:false` matches a **real 403** (RFC-7807
    `ACCESS_DENIED`); the gate decided independently.
  - **E6** catalog verb set = `[view,update,delete]`, **no `assign-tags`** key.
  - **E4** (script-level, OPA paused) → no 5xx, no fabricated all-false map (omit-on-failure holds live).
- **The team-degrade, live:** `GET /api/v1/teams/{id}` (ungated) on the fresh usermgmt → **200, `_actions`
  absent from the wire** (the cache-miss degrade — omit, never fabricate). (The team endpoints route to
  usermgmt, not through the catalog gateway, so this cell is asserted direct + by the user-mgmt
  `ActionEnrichmentIT`; the gateway collection covers the catalog cells.)
- **Coexistence — every existing matrix GREEN, 0 failures** (the additive proof): run-tests 19/0,
  role-matrix 19/0, tag 12/0, team 12/0, filter 16/0, hierarchy 4/0, hierarchy-list 10/0, pagination 27/0,
  resource-resolution 12/0, permission-categories 31/0. Row counts + decisions numerically unchanged.
- **`opa test`** 183/183 (infra) + 32/32 (user-mgmt bundle) — the +6 new bulk tests; the two `team.rego`
  rule files byte-identical.
- **Unit/IT:** the user-mgmt `ActionEnrichmentIT` (the ungated-getTeam wire degrade + the team verb-set
  exclusion) + `./gradlew build` green throughout.

## Architecture review + refactor

Ran the ★ gate. **The headline finding was the missing-`bulk` planning-gap (below), caught only by the
live e2e.** The fail-closed/security lenses re-proved live; one library serialization fix landed.

- **Serialization fix (a slice-wide correctness bug the e2e wire surfaced):** the generated DTO
  initializes `_actions` to `new HashMap<>()`, so an UNSET map serialized as `{}` — which a client reads
  as a fabricated all-deny ("no actions"), violating omit-never-fabricate. **Fix:** `@JsonInclude(
  JsonInclude.Include.NON_EMPTY)` on the **library** `Enrichable.getActions()` (Jackson honors the
  interface-declared annotation on the generated getter) → an unset/degraded `_actions` is omitted from
  the wire for **every** adopter. Proven: the team wire has no `_actions` key; the catalog present-maps
  still serialize.
- **Fail-closed (re-proven live):** E4 (OPA paused) → no 5xx, no all-false map; the team ungated read
  omits. **Affordance ≠ enforcement (E5):** a `_actions:false` matched a real gate 403.
- **Affordance honesty:** the team set excludes the Java-co-gated verbs (the IT asserts the exact subset);
  catalog excludes `assign-tags` (E6).
- **Boundary/additivity:** every existing matrix numerically unchanged (coexistence); `Membership`
  unenriched; the `bulk` additions add no decision.
- **Pattern reuse:** `TeamResourceResolver` mirrors `CatalogResourceResolver`; the `bulk` rule mirrors
  `category.rego` byte-for-byte.

## Integration / e2e

Full live rig on **real Docker** (`ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2` +
`./deploy.sh build` for the catalog and an explicit `docker build --network=host` for usermgmt; OPA
restarted to load the new `bulk` rules). All matrices + the team-degrade asserted above.

## Decisions

- **The missing-`bulk` planning-gap (resolved with the maintainer; the slice's keystone friction).** The
  decomposition pinned "zero Rego change" on the false premise that a per-type `bulk` rule already existed
  for every enriched type. It did **not** — only `category.rego` had `bulk` (the one type whose list used
  the allowlist-batch). So Catalog/Product/Team all silently degraded (omit). **Decision: add the three
  identical, decision-preserving `bulk` entrypoints (+ tests) and correct ADR 0016 §6** to "zero change to
  existing decision logic; the `bulk` primitive is extended to every enriched type." Caught **only** by
  the live e2e — the unit/IT layers stub `allowAll` and never exercise the real `bulk` rule's absence.
- **The fixture role model.** A tag-gated role gates the **whole** decision (incl. `view`), so a
  tag-mismatched row is not readable at all — the per-verb affordance contrast must be **grant-based**
  (writer vs read-only role), not tag-based. The matrix uses a plain `ae-writer` + `ae-reader`.
- **Rig gotcha (recorded):** `./deploy.sh up` reuses an existing image — a stale (pre-Phase-6) image ran
  until `./deploy.sh build` (catalog) + an explicit `docker build` (usermgmt) forced fresh images; the
  in-image Gradle build needs `--network=host` to fetch the Gradle distribution.

## Commit

`feat(example): user-mgmt action enrichment (TeamEnrichable) + bulk rules + cross-service e2e` — to follow.
