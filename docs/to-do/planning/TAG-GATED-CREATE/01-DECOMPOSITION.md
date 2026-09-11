---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/opa
  - area/spring
  - area/catalog-service
---

# TAG-GATED-CREATE — decomposition

> T1…T5, in order. Each ticket is one focused commit's worth of work, **built collaboratively** —
> the maintainer and the agent on one branch, ticket by ticket. The design these decompose is
> [[00-DESIGN]]; the contract is [[0034-tag-gated-placement-input-contract|ADR 0034]]; the mechanism
> being generalized is [[0032-root-attribute-enrichment-input-contract|ADR 0032]] (unchanged by this
> slice). **Sizing (flow guide §2a):** three deployables are touched (policies, the starter, the
> catalog service) but every consumer of the changed clause is named (six annotation sites, three
> type-level verbs), the library change is additive with no build-breakers, and the count is five —
> no split smell fires; the collaborative build is the containment.

## Critical path

```
T1 (policy + opa test) ──┐
                         ├──► T3 (example gates + re-parent + IT) ──► T4 (e2e cells) ──► T5 (pane row + close-out)
T2 (starter contract) ───┘
```

- **T1 and T2 are independent** and may land in either order (T1 is Rego, T2 is Java in
  `opa-abac-core` + `opa-abac-spring-security`). Both are **independently landable**:
  - after **T1 alone** the rig's type-level creates by a **tag-requiring** role are denied outright
    (no `parent_attributes` on the wire yet ⇒ the strict clause is undefined) — the fail-closed
    interim; every no-requirement role, and therefore every demo persona, is unchanged;
  - after **T2 alone** nothing observable changes — the field stays absent until an annotation
    declares a parent.
- **T3 needs both** (it declares the T2 attributes and its IT pins the wire the T1 clause reads).
- **T4 needs T3** on the rig (`./deploy.sh build` + an OPA restart), **T5 needs T4**.

## T1 — the placement gate in `category.rego` + `product.rego`, with its `opa test` cells

**Goal.** LIST is the only coarse type-level verb; every other type-level verb (create, assign-tags,
anything future) passes only when the inheritable grant holds **and** the placement parent's tags
**and** the payload's tags satisfy the role's requirement. A role without a requirement is unchanged.

**Deliverables.**
- `infra/opa/policies/category.rego` and `product.rego` (mirrored, as every prior policy change):
  the ADR 0034 §3 reference shape **as amended at decomposition**: the `granted` consumer clause gains
  `not strict_type_level` (instance decisions and type-level LIST via the direct path stay
  byte-identical), the coarse ancestor clause is scoped to `verb == "list"`, and one strict clause
  owns every other type-level verb — `strict_type_level; not denied; type_level_verb_grant;
  parent_tags_satisfied; tags_satisfied` — where `type_level_verb_grant` is the verb on the decided
  type directly **or** `list_inheritable_grant`. The bodies of `direct_grant`, `inherited_grant` and
  `list_inheritable_grant` (with the ADR 0031 provenance conjunct) are untouched. The "verb-agnostic"
  comment paragraph above the clause is rewritten to say what is now true (LIST coarse, the rest
  strict, both grant paths, why).
- `parent_tags_satisfied`: **undefined** when `input.resource.parent_attributes` is absent or not an
  object and the role carries a requirement; vacuously true (`not has_required_tags`) for a role
  without one; otherwise the same `ANY_OF`/`ALL_OF` match as `tags_satisfied` over the parent's map.
  **No fallback to `root_attributes`** — the clause never reads it.
- The helper refactor that makes the two matches one: `resource_tag_values(key)` is generalized to
  `attribute_values(attrs, key)` — and **kept as a one-line wrapper**
  (`resource_tag_values(key) := attribute_values(object.get(input.resource, "attributes", {}), key)`),
  because the boolean-false pins `test_tag_boolean_false_attribute_denies_without_conflict` in **both**
  test files call it directly and must keep passing unmodified (`opa check` fails without it) — (array ⇒ elements, scalar ⇒ singleton, absent key ⇒ empty, key
  **presence** not truthiness — the 2026-08-23 `false`-value guard preserved), `key_satisfied(attrs,
  key, acceptable)`, and `tags_match(attrs)` carrying the mode rules; `tags_satisfied :=
  tags_match(object.get(input.resource, "attributes", {}))`, `parent_tags_satisfied` reads
  `input.resource.parent_attributes` **without** a default (absence must stay undefined). `filter_tags_satisfied`
  (the partial-eval shape) is **not** routed through the helper — its membership/equality form is
  what folds into SQL and must stay byte-identical.
- Tests in `category_test.rego` + `product_test.rego`: three fixtures — `gated_writer_any`
  (`catalog: [READ, WRITE, TAG]`, `required_tags {region: [emea]}`, `ANY_OF`, **stamped**
  `"attributes": {"provenance": "membership"}` — without the ADR 0031 stamp `list_inheritable_grant`
  is undefined and no allow-cell can pass; the e2e persona gets the stamp at runtime from
  `EffectiveRoleService`, a unit fixture must carry it as `cat_root_role` does), `gated_writer_no_tag`
  (the same without TAG), and `gated_writer_direct` (`category: [READ, WRITE, TAG]` / `product:
  [READ, WRITE, TAG]`, the same requirement, no stamp needed — the direct path); every allow/deny
  cell runs on **both** the stamped and the direct fixture; an input helper `placement_input(action, role_def, parent, attrs)` building
  `{"type": <t>, "id": null, "attributes": attrs, "parent_attributes": parent}` and a variant that
  **omits** the `parent_attributes` key (absent ≠ null: `"parent_attributes": null` is a present
  non-object and is pinned as its own deny). Every cell runs with `data.<type>.inheritable` set as the
  existing type-level cells do. `opa fmt --write` on the **two policy files**, `opa check`, `opa test
  infra/opa/policies -v` green (the shipped **test** files carry pre-existing `opa fmt` drift under
  1.10.1 — a `with`-continuation line-join that arrived with the #122 pins, 2026-08-24 — deliberately
  left untouched, the practice SUPERVISED-SCOPE's STATUS-03 set for drift outside a ticket's boundary;
  `opa fmt --diff` exits 0 either way, so the gate is `opa fmt --list`); the existing cells (category: `test_create_inheritable_grant_opens_gate`,
  `test_create_null_id_inheritable_grant_opens_gate`, `test_assign_tags_for_create_inheritable_opens`;
  product: `test_product_create_inheritable_opens`, `test_product_create_null_id_inheritable_opens`,
  `test_assign_tags_type_level_inheritable_opens` — all on `editor_role_def`, which names the type
  directly and carries no requirement, so today they pass through `direct_grant`) pass **unchanged**;
  the no-requirement invariant of the new clause itself is U9's plain-editor cell.
- Doc delta (same commit): `docs/guides/TAG-BASED-AUTHORIZATION.md` §"Layer 3" → "The match in Rego"
  gains the placement clause (LIST coarse / the rest strict; the parent map's three states; "a
  creator can read what it creates, where it creates"). The rig note: **OPA never watches the mount
  — `docker restart opa-abac-opa` after this lands** (Mulch `rego-policy` mx-4e1191).
- Seam verification: rego paths checked with `opa test` against `infra/opa/policies/` (OPA 1.10.1 on
  PATH — verified); `data.config.root_read_tag_exemption` read from `config.json`.

**Acceptance.** **U1–U12** (each cell in both `category_test.rego` and `product_test.rego`). `opa
test infra/opa/policies` green, `opa fmt --list infra/opa/policies` names neither policy file, `opa
check` clean. No diff under
`catalog.rego`, `team.rego`, `role.rego`, `agent_tools.rego`, `permissions.rego`.

**What NOT to touch.** `filter` and `filter_tags_satisfied` (the SQL residual is byte-identical — LIST
end to end is unchanged); `denied`/`denied_other` (the tier clauses); the bodies of `direct_grant`, `inherited_grant` and
`list_inheritable_grant` (the `granted` consumer clause only gains `not strict_type_level`); the ADR
0031 confinement; `catalog.rego` (catalog create has no
parent); `data.config` (the exemption flag is never consulted by the new clauses — **pin the
invariant, not the boolean**). No `root_attributes` read anywhere in the new clauses.

## T2 — the starter contract: `parent_attributes` on the wire + three `@OpaPreAuthorize` attributes

**Goal.** The published input gains the additive `parent_attributes` component with ADR 0032's
three-state semantics, and the annotation gains `parentResourceType` / `parentResourceId` /
`attributes`, populated manager-side through the existing memoized resolve — with every failure
edge pinned in the fail-closed direction.

**Deliverables.**
- `opa-abac-core` — `AbacContext.Resource` gains a **sixth** component
  `@JsonInclude(NON_NULL) @JsonProperty("parent_attributes") Map<String, Object> parentAttributes`;
  the compact constructor is **null-preserving** for it (`null` = unproven, `{}` = untagged) and
  defensively copies a non-null map; the **5-arg constructor stays as a compat constructor**
  (delegating `parentAttributes = null`) beside the existing 3- and 4-arg ones. **Build-breakers:
  none by construction** — the census (main + test, `build/` excluded): 3-arg ×~33; 4-arg ×8
  (`HierarchicalAuthorizer:115`, `AbacQueryService:390`, the manager's `:471`,
  `ActionEnrichmentAdvice:227`, four in the core tests); 5-arg ×6 (the manager's `:390`, five in
  `AbacContextRootAttributesTest`); every arity keeps a constructor, so all compile unchanged —
  verified by `./gradlew build`. Core stays Spring-free (Jackson annotations only, as today).
- `opa-abac-spring-security` — `OpaPreAuthorize` gains three optional SpEL attributes with javadoc
  stating the fail-closed edges: `parentResourceType()`, `parentResourceId()` (the placement parent;
  **declared but resolving to null/blank ⇒ deny** at the manager — the `roleResourceId` posture,
  never a silent degrade to "no parent"; only **one** of the pair declared ⇒ deny, the `roleResource`
  pair's precedent at `withRoleResourceOverride`), `attributes()` (the decided resource's attribute
  map for a **type-level** check; SpEL `null` ⇒ `{}`; a value that is not a `java.util.Map` ⇒ deny; a
  map carrying a `null` value ⇒ deny — the record's `Map.copyOf` rejects it inside the manager's
  fail-closed catch, as it already does for every attribute map today; declared together with
  `resourceId()` or `resource()` ⇒ a **declaration conflict ⇒ deny**).
- `OpaPreAuthorizeAuthorizationManager`: the type-level branch of `resolveCheck` evaluates
  `attributes()` and builds `new AbacContext.Resource(type, null, attrs)`; a new
  `enrichWithParentAttributes(check, annotation, spelContext)` runs **after** `withRoleResourceOverride`
  and `enrichWithRootAttributes` — the SpEL context is local to `resolveCheck` today and the root
  enrichment is called from `authorize()`, so either the context is hoisted or the parent declaration
  is evaluated inside `resolveCheck` and its `(type, id)` threaded out on `ResolvedCheck` (a small
  named refactor; the **order** of enrichment is the contract) — resolves the declared parent through the renamed generic
  `resolveAttributesOf(type, id)` (the former `resolveRootAttributes`, byte-identical behavior: cache
  read-through, decision-independent put, `null` on any failure) and threads the map in as
  `parentAttributes`; no declaration ⇒ the field absent. **Root-attribute enrichment is untouched** —
  for a top-level category create both fields carry the catalog's map and the memo makes the second
  resolve a cache hit (asserted). The parent is neither a role coordinate nor the cached instance —
  if `ResolvedCheck` grows a component to carry the declared `(type, id)`, it is that and nothing else.
- Tests: `opa-abac-core` `AbacContextParentAttributesTest` (the `AbacContextRootAttributesTest`
  shape — additivity on bytes, the three states, null-preservation); `opa-abac-spring-security`
  `OpaPreAuthorizeParentAttributesTest` on the `OpaPreAuthorizeRootAttributeEnrichmentTest` pattern
  (mock `OpaClient` capturing the `AbacContext`, mock `AbacResourceResolver`, the `RecordingCache`,
  a `SampleController` whose methods carry the new declarations — including one with the SpEL
  ternary `#request.parentId != null ? 'category' : 'catalog'` against a small request record, so
  T3's exact expression is proven here first). Every existing test in both modules green, byte-level
  regressions included.
- Doc delta (same commit): `docs/guides/ABAC-AUTHORIZATION.md` gains "Placement-parent enrichment —
  `input.resource.parent_attributes` (ADR 0034)" as a sibling of the ADR 0032 subsection (the three
  states, the no-fallback rule, the wrong-shape warning inherited from 0032);
  `docs/guides/ATTRIBUTE-RICH-PRE-AUTHORIZATION.md` §"The mechanism" documents the three attributes
  and §"The split failure semantics" the deny-vs-absent split. The starter's input-schema example
  wherever `root_attributes` is shown gains the sibling field.
- Seam verification: repo-internal only — signatures read from source (`resolveCheck`,
  `withRoleResourceOverride`, `enrichWithRootAttributes`, `resolveRootAttributes`, `evaluate`,
  `bindArguments`, `AbacResourceCache.get/put`); SpEL's ternary operator is exercised by U16's
  test rather than assumed.
- **The local Sonar gate** (`./.sonar-local/sonar-local.sh`) CLEAN on the changed files before the
  commit — the Sonar rig must be up first (`docker compose -f .sonar-local/docker-compose.yml up -d`;
  it is down since the 2026-09-10 Docker restart).

**Acceptance.** **U13–U22**. `./gradlew :opa-abac-core:test :opa-abac-spring-security:test
-Dorg.gradle.jvmargs=-Xmx2g` green (the `-D` is the Bash-sandbox trust-store workaround; a
terminal needs no flag); `./gradlew build` green across modules (the compat-constructor claim);
Sonar CLEAN.

**What NOT to touch.** `HierarchicalAuthorizer` (the programmatic seam handles no type-level create
— documented as out of reach, the ADR 0032 precedent); `AbacQueryService` and the residual path;
`ActionEnrichmentAdvice` (bulk inputs carry no parent); the allow-side write-through of the decided
instance; no new SPI, no new config property; `attributes()` never overrides a **resolved** instance's
attributes (that is the conflict deny). `NON_NULL`, never `NON_EMPTY`.

## T3 — the example gates: the declarations on create/assign-tags, the placement call on re-parent, the IT

**Goal.** The catalog service puts the placement parent and the raw payload on the wire for every
type-level create and assign-tags-for-create, asks the placement question again when an update moves
a category, and keeps authorization ahead of dictionary validation.

**Deliverables.**
- `CategoryController.createCategory`: the annotation gains
  `parentResourceType = "#request.parentId != null ? 'category' : 'catalog'"`,
  `parentResourceId = "#request.parentId != null ? #request.parentId : #catalogId"`,
  `attributes = "#request.tags"` (the generated `CategoryRequest` exposes `getParentId()` /
  `getTags()` — verified in the controller's existing body). `ProductController.createProduct`:
  `parentResourceType = "'category'"`, `parentResourceId = "#categoryId"`, `attributes = "#request.tags"`.
- `TagDecisionGate`: `requireCategoryAssignTagsForCreate(UUID catalogId, String parentType, UUID
  parentId, Map<String, Object> tags)` and `requireProductAssignTagsForCreate(UUID catalogId, UUID
  categoryId, Map<String, Object> tags)` carry the same declarations (the parent and `attributes =
  "#tags"`); the two create bodies pass the resolved parent and the raw request map. New
  `requireCategoryPlacement(UUID catalogId, String parentType, UUID parentId, Map<String, Object>
  tags)` — a type-level `category:create` with `roleResourceType = "'catalog'"`, the **new** parent
  declared, `attributes = "#tags"`.
- `CategoryController.updateCategory`: when `entity.getParentId()` differs from
  `request.getParentId()`, call `requireCategoryPlacement(catalogId, <new parent type>, <new parent
  id or catalogId>, request.getTags())` **after** the existing update/assign-tags dispatch and
  **before** `guardGateSnapshot` — every decision precedes every write; an unchanged parent asks
  nothing new. The existence check on the new parent stays where it is (after authorization).
- Order pinned in code comments: authorization on the **raw** submitted map, `validateAndBuild` (422)
  after allow — unchanged order, now stated as a contract (a 403 never leaks whether a key exists).
- Tests: `TagGatedCreateIT` (Testcontainers Postgres, the `TagDecisionGateIT` harness — extend
  `ActionAwareOpaClient` to record the decided `AbacContext`s beside `askedActions`, so the wire is
  asserted, not inferred); no existing action-aware IT sends a re-parent `PUT` (`parentId` appears
  nowhere in `TagDecisionGateIT`; the resolution and hierarchy ITs do not capture actions), so I5 is
  the **first** such cell and no existing cell changes; `CategoryTagAssignmentIT`/
  `ProductTagAssignmentIT` green unchanged.
- Doc delta (same commit): `docs/guides/TAG-BASED-AUTHORIZATION.md` §"Getting the tags to OPA —
  resolved at the gate" gains the create/placement paragraph (what the gate declares, the re-parent
  second call, the 403-before-422 order); `docs/guides/DEMO-CONSOLE-WALKTHROUGH.md` §3.4 — the
  "**Known gap (DEF-1, planned)**" callout is replaced by one sentence of the shipped behavior (a
  tag-requiring writer's create answers 403 under the untagged root and under any parent its tags do
  not match; ADR 0034).
- Seam verification: the request DTOs from the generated sources under
  `example-catalog-management-service/build/generated/openapi/`; the annotation attributes from T2's
  source; the SpEL ternary proven by T2's U16.
- Rig: `./deploy.sh build` (the catalog image) + `docker restart opa-abac-opa` (T1's policies) before
  any live check; the local Sonar gate CLEAN on the changed files.

**Acceptance.** **I1–I6**. `./gradlew :example-catalog-management-service:test
-Dorg.gradle.jvmargs=-Xmx2g` green; `./gradlew build` green; Sonar CLEAN.

**What NOT to touch.** The two list gates and both list authorizers; `CatalogController` (no
parent, no tag-on-create); `requireCatalog`/`requireCategory` (existence stays a 404 **after** the
gate for a no-requirement role); the update/assign-tags delta dispatch and its order;
`guardGateSnapshot`; `TagAssignmentService`; the OpenAPI spec (no contract change — a 403 where a 201
was). No SPA change.

## T4 — the e2e ratchet: seven tag-matrix cells on both grant paths, the persona's TAG, the matrix row

**Goal.** The tag matrix proves ADR 0034 through the gateway on both grant paths: the persona that
already proves ADR 0022 (`gated-writer` — catalog READ+WRITE+TAG, category READ — the inheritable path) cannot place under the
untagged root, cannot place under a mismatching category, can place a matching product under a
matching category, and cannot move a category under a mismatching parent; and the same realm user
rebound to a role naming the child types directly (the direct path) is closed the same way.

**Deliverables.**
- `scripts/postman/run-tag-matrix.sh`: `gated-writer` gains `TAG` on the catalog
  (`{"catalog":["READ","WRITE","TAG"],"category":["READ"]}`) so a matching tag-on-create is decided
  by the placement rule, not by a missing verb; **6a/6b keep their expectations** (6b sends a name
  only — a `catalog:update`, still tag-gated) and the run proves it. A second bootstrapped role
  `gated-direct` (`{"category":["READ","WRITE","TAG"],"product":["READ","WRITE","TAG"]}`, the same
  `region ANY_OF [emea]`, **no catalog permission**) exercises the direct path; the collection rebinds
  the same realm user (`bob`, the `gated_token`) to it between `7d` and `7e` with a `[bind]` item —
  `POST {{user_service}}/internal/bootstrap/memberships` `{teamId, userId, roleCode}` → 200, the
  `permission-categories-matrix` precedent (one role per user per team; the runner passes its `$GATED_UID` as a newman `--env-var`, the
  `run-permission-categories-matrix.sh` `ladder_uid` precedent). The header comment's cell list gains 7a–7g. The runner **does
  not restart OPA today — add the restart + real-decision poll block** from
  `run-supervised-scope-matrix.sh` (T1 edited both policies; a stale bundle would decide the new
  cells for the wrong reason). `scripts/checks/check-shell-guards.py` green.
- `scripts/postman/tag-abac-matrix.postman_collection.json`, seven cells named `7a`–`7g` plus the
  `[bind]` item (the collection's own numbering; the package ids are E1–E7), each with **positive**
  assertions on status
  and body (`ACCESS_DENIED` problem+json on the denies; `Location` + the echoed tags on the allow) so
  none is ABSENCE-ONLY, and names that claim only what the request reads:
  - **7a** gated-writer `POST …/catalogs/{{catalog_id}}/categories` `{"name":"placed-under-root"}`
    → **403** (the parent is the untagged root — placement under the root is a mutation of its
    subtree, denied in both flag states);
  - **7b** gated-writer `POST …/categories/{{mismatch_category_id}}/products`
    `{"name":"…","tags":{"region":["emea"]}}` → **403** (a matching payload under a mismatching
    parent — the sharper case);
  - **7c** gated-writer `POST …/categories/{{match_category_id}}/products` with the same payload →
    **201**, the body's `tags.region` contains `emea`; a follow-up owner `DELETE` on the captured
    `match_product_id` → **204** leaves the fixture world as found (teardown's catalog cascade would
    clean it regardless);
  - **7d** gated-writer `POST …/categories` `{"name":"movable","parentId":"{{match_category_id}}",
    "tags":{"region":["emea"]}}` → **201** (a matching placement), then `PUT` the new category with
    `parentId: {{mismatch_category_id}}` (same name/tags) → **403** (the placement call on the new
    parent), then a `GET` proving `parentId` is still `match`; cleanup by the owner;
  - **`[bind]`** the `gated` user → `gated-direct` (200), then **7e** `POST …/categories`
    `{"name":"direct-under-root","tags":{"region":["emea"]}}` → **403** (the direct path, a
    **matching** payload under the untagged root — the escape the first draft left open, and the
    index's reproduction row 7); **7f** `POST …/categories/{{mismatch_category_id}}/products` with a
    matching payload → **403**; **7g** the same under `{{match_category_id}}` → **201** + cleanup.
  - `match_product_id`, `movable_id` and `direct_product_id` declared in the collection's `variable` list and captured at
    runtime with `pm.collectionVariables.set` (the `catalog-abac-matrix` precedent).
  - `scripts/checks/check-collection-conformance.py` green (no waiver needed).
- `scripts/postman/README.md`: the `run-tag-matrix.sh` **matrix row** gains the seven cells and the
  ADR 0034 sentence; `docs/guides/E2E-TESTING.md` §"Tag-based ABAC matrix" **does** enumerate the
  cells ("Then 9 requests", the table, "All 9 green") — the table gains rows 7a–7g and both counts
  become 16 (plus the bind step).
- Seam verification: the endpoints from `catalog-api.yaml` (`POST /catalogs/{id}/categories`,
  `POST …/categories/{id}/products`, `PUT …/categories/{id}`); the persona/role JSON from the
  runner's existing bootstrap lines; the problem+json shape from cell 6b.

**Acceptance.** **E1–E7**. `scripts/postman/run-tag-matrix.sh` green end to end on the rebuilt rig
(`./deploy.sh build`, OPA restarted); `scripts/checks/check-collection-conformance.py` and
`check-shell-guards.py` green; `run-demo-world-matrix.sh --skip-matrices` still green (no shared
fixture touched).

**What NOT to touch.** Cells 1–6b (assertions unchanged; only the persona's permission set grows);
every other runner's fixtures and personas; the seed (`seed-demo-data.sh`) — no demo persona is a
tag-requiring writer, and it stays that way.

## T5 — the pane row, the close-out records

**Goal.** The console's create form under a tag-requiring writer answers the 403 honestly, observed
in the Browser pane and recorded; the slice's paper trail is closed and the review sequence run.

**Deliverables.**
- The pane cell **E8**, run against `./deploy.sh up --pods 2` (defaults: SPA + directory on) with
  `ENABLE_MCP=1` on the same `up`, the seed (`scripts/postman/seed-demo-data.sh`), and the QA note's
  recipe for a tag-requiring writer (a
  custom role with WRITE + TAG and `required_tags`, bound to `viewer`, as
  [[PRE-HABR-UI-QA-2026-09-10]] did with `alice-role`); the maintainer logs in, the agent drives the
  console; the observation goes to STATUS-05 with the request/response captured off the wire.
- Records: [[PRE-HABR-UI-QA-2026-09-10]]'s DEF-1 row → fixed, PR link; ADR 0034 status → shipped;
  `USER-STORIES.md` C5 ✅; the roadmap line → ✅ SHIPPED; the package index status; Mulch —
  `rego-policy` (the placement-gate pattern, LIST-is-the-exception), `spring-security-integration`
  (the parent enrichment beside the root one), `autonomous-runs` (the run record — collaborative;
  what the planning pinned that the build did not have to ask); `git restore --staged .` before
  `ml sync`.
- The review sequence recorded in STATUS-05: `/deep-review` with the lenses on **Opus 5**, then one
  single-agent review pass on Fable, then `/security-review delta` on the annotation and context
  change — findings folded before the ship commit (`docs(tag-gated-create): ship — …`).

**Acceptance.** **E8**. Every T1–T4 acceptance still green on the final branch: `./gradlew build`,
`opa test infra/opa/policies`, the tag matrix, the demo-world check, conformance + shell-guard
checks, Sonar CLEAN; the three review passes recorded.

**What NOT to touch.** No new feature work in T5 — a finding becomes a ratcheted test and a fix to a
surface T1–T4 owns; anything larger is a recorded follow-up. The `GATEWAY-AUDIENCE-BINDING` note
(already renumbered to ADR 0035) is not this slice's.

## Cross-cutting acceptance (every ticket, the final branch)

- `./gradlew build` green (all modules, the Testcontainers ITs); `opa test infra/opa/policies` green;
  `opa fmt --list` names neither policy file; the local Sonar gate CLEAN on every changed `.java`.
- **LIST byte-identical end to end**: no diff in `filter`/`filter_tags_satisfied`, the list gates, the
  list authorizers; `run-hierarchy-list-matrix.sh` and `run-filter-matrix.sh` green if run.
- **The wire for existing callers byte-identical** (U13): every context without a declared parent
  serializes as before; the `AbacContextRootAttributesTest` and `OpaPreAuthorizeRootAttributeEnrichmentTest`
  suites green unchanged.
- **Fail-closed at every new edge**: absent parent, empty parent, non-object parent, unresolvable
  declared parent, non-Map attributes, a declaration conflict — each is a pinned deny (U6–U8, U17,
  U18, U20), never a widening and never a 500.
- **Flag invariance**: the create outcome is identical with `root_read_tag_exemption` on and off (U10).
- Clean-room: neutral names only; `scripts/planning/verify-package.sh TAG-GATED-CREATE` green
  (`[1]`/`[5]` stand down — the index declares `**Build: collaborative**`).

## Related

[[TAG-GATED-CREATE]] (index) · [[00-DESIGN]] · [[10-QA-TEST-CASES]] ·
[[0034-tag-gated-placement-input-contract|ADR 0034]] · [[0032-root-attribute-enrichment-input-contract|ADR 0032]] ·
[[0022-root-read-tag-exemption|ADR 0022]] · [[0009-tag-requirement-subject-side|ADR 0009]] · [[POC-ROADMAP]]
