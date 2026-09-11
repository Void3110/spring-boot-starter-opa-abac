---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/opa
  - area/spring
  - area/catalog-service
---

# TAG-GATED-CREATE — QA test cases

> Concrete cases; each becomes a ticket's *Acceptance*. U = unit (**`opa test`** for the policy
> cells U1–U12, run in **both** `category_test.rego` and `product_test.rego`; JUnit for U13–U22),
> I = integration (Testcontainers Postgres — never H2; the in-process action-aware OPA stub that
> records what was asked), E = e2e — **E1–E7 through the gateway with newman** (the tag matrix's
> cells `7a`–`7g` plus one `[bind]` step; asserts the actual allow/deny and body, every `pm.test`
> throws), **E8 through the packaged SPA in the Browser pane**. Fixtures throughout: a **gated writer** = `catalog: [READ,
> WRITE, TAG]` with `required_tags {region: [emea]}`, `ANY_OF`, **stamped**
> `attributes.provenance = "membership"` (the e2e persona's shape minus its `category: [READ]`, which carries no create verb; the stamp is what lets the
> ADR 0031-confined inheritable grant fire on the catalog ancestor); a **direct writer** = `category:
> [READ, WRITE, TAG]` / `product: [READ, WRITE, TAG]` with the same requirement and no catalog
> permission (the verb granted on the child type itself — the `alice-role` shape); a **plain editor**
> = the existing `editor_role_def` (no requirement). **Every allow/deny cell U2–U8 and U11 runs on
> both tag-requiring fixtures** — the two grant paths reach the same strict clause.

## Unit — the policy (U1–U12, `opa test`, category and product alike)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| U1 | LIST is untouched | gated writer (stamped, catalog-only), `<type>:list`, type-level input with **no** `parent_attributes` and `attributes: {}` → **allow** (the coarse ancestor clause, exactly as today); the **direct** writer on the same input → **deny**, exactly as today (the direct list path matches the empty attribute map and fails the requirement — a pre-existing shape, neither widened nor narrowed); plain editor → allow; read-only role → allow; no role definition → deny; the `id: null` wire shape → same outcomes | T1 |
| U2 | Matching parent + matching payload → allow | gated writer, `<type>:create`, `parent_attributes {region: [emea]}`, `attributes {region: [emea]}` → **allow**; also with the parent carrying extra keys (`{region: [emea], sensitivity: internal}`) → allow | T1 |
| U3 | Matching payload under a mismatching parent → deny | gated writer, `parent_attributes {region: [apac]}`, `attributes {region: [emea]}` → **deny** — the sharper case the QA never measured, now pinned | T1 |
| U4 | Untagged payload under a matching parent → deny | gated writer, `parent_attributes {region: [emea]}`, `attributes {}` → **deny**; `attributes` key absent → deny (a creator must be able to read what it creates) | T1 |
| U5 | Mismatching payload under a matching parent → deny | gated writer, parent emea, `attributes {region: [apac]}` → deny; a payload with the key but a `false` value → deny (presence, not truthiness — the singleton `{false}` intersects nothing) | T1 |
| U6 | Absent parent → deny | gated writer, matching payload, the `parent_attributes` **key omitted** → deny (undefined, never an allow); the same with the coarse-era input shape `{"type": "category"}` (no id key) → deny; **no fallback**: a matching `root_attributes` with the parent absent → still deny (both fixtures — the `object.get(…, root_attributes)` mutant survives every other cell) | T1 |
| U7 | Empty parent → deny | gated writer, matching payload, `parent_attributes: {}` → deny (an untagged root/parent matches no requirement) — pinned as a **separate** cell from U6 so nobody collapses the two states | T1 |
| U8 | Non-object parent → deny | `parent_attributes: null` (present null), `"x"`, `[]`, `42` → each deny | T1 |
| U9 | A role without a requirement is unchanged | plain editor: absent parent + `attributes {}` → allow; absent parent + any payload → allow; the existing cells (category: `test_create_inheritable_grant_opens_gate`, `test_create_null_id_inheritable_grant_opens_gate`, `test_assign_tags_for_create_inheritable_opens`; product: `test_product_create_inheritable_opens`, `test_product_create_null_id_inheritable_opens`, `test_assign_tags_type_level_inheritable_opens` — today they pass through `direct_grant`, since `editor_role_def` names the type) pass **unmodified**; read-only role → deny (no create verb); no role definition → deny | T1 |
| U10 | The root-read flag never reaches a create | U7 with `data.config.root_read_tag_exemption` **true** → deny and with it **false** → deny; U2 under both → allow (pin the invariant, not the boolean) | T1 |
| U11 | The type-level assign-tags decision runs the same table | `<type>:assign-tags` with the U2 input → allow; U3 → deny; U4 → deny; U7 → deny; a gated writer **without TAG** (`catalog: [READ, WRITE]`) on the U2 input → deny (the verb, not the tags); a non-member (no role definition) → deny | T1 |
| U23 | `deny_reason` keys on the grant the request rides | a **supervised** role holding WRITE+TAG on the type with a tag requirement, a strict type-level create on a **production** root with a **mismatching** parent, not elevated → `deny_reason` **undefined** (no challenge is minted for a deny a second factor could never clear — ADR 0030 §7's loop) and `allow` false; the same request elevated (fresh `aal2`) → still `allow` false; both policies | T1 |
| U12 | The malformed and the unknown stay closed | a gated writer whose `required_tags` is a scalar (`"emea"`) with matching parent and payload → deny (the #122 guard, on both matches); `match_mode` missing → deny; a **future** type-level verb `<type>:frobnicate` — granted for real by overriding the expansion table (`with data.permission_categories as object.union(data.permission_categories, {"FROB": ["frobnicate"]})` and `FROB` on the fixture's `catalog` entry, so `effective_actions` yields `["frobnicate"]` rather than `[]`) — with an absent parent → deny (it lands on the strict path, not on "no grant"); `opa fmt --list` names neither policy file and `opa check` is clean on the corpus | T1 |

## Unit — `opa-abac-core` (U13–U15, JUnit)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| U13 | Additivity, on bytes | the 3-arg, 4-arg and **5-arg** constructors still compile and serialize **byte-for-byte** as before (the exact strings `AbacContextRootAttributesTest` pins, unchanged); `parentAttributes()` is `null` on every compat arity; `AbacContextRootAttributesTest` itself passes unmodified | T2 |
| U14 | The three states on the wire | `parentAttributes = null` → the key is **absent** from the JSON; `Map.of()` → `"parent_attributes":{}` present; `Map.of("region", List.of("emea"))` → the map; ordering: after `root_attributes` when both present; both present and equal (the top-level category case) serialize both | T2 |
| U15 | The compact constructor | `null` is preserved as `null` (never coerced to `{}`); a non-null map is defensively copied (mutating the caller's map after construction changes nothing; the component is unmodifiable) | T2 |

## Unit — the authorization manager (U16–U22, JUnit)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| U16 | Declared `attributes` reach a type-level decision | `attributes = "#request.tags"` on a type-level gate → the captured `AbacContext.resource().attributes()` equals the map; a `null` map → `{}`; the SpEL ternary pair `parentResourceType = "#request.parentId != null ? 'category' : 'catalog'"` / `parentResourceId = "#request.parentId != null ? #request.parentId : #catalogId"` resolves to `(category, parentId)` when set and `(catalog, catalogId)` when null — asserted on the resolver's received `(type, id)` | T2 |
| U17 | A non-Map `attributes` denies | `attributes = "'not-a-map'"` (a String) and `attributes = "42"` → **DENY**, `OpaClient.decide` never called | T2 |
| U18 | A declaration conflict denies | `attributes` declared together with `resourceId` (an instance form) → DENY, OPA never called, the resolver never called; the same with `resource()` | T2 |
| U19b | Confinement — a parent outside the governing target is unproven | a declared parent that resolves by id but whose ancestor chain roots at **another** catalog → `parentAttributes()` **null** (absent), the decision proceeds on the mock; a parent that is its own root and not the target → absent; an ancestor walk that **throws** → absent, no exception; **no chain supplier** → a category parent is unprovable (absent) while the catalog parent (the target itself) is proven | T2 |
| U19 | The parent is resolved and memoized | a declared parent whose resolver returns an `AbacResource` with `abacAttributes() = {region:[emea]}` → the captured context's `parentAttributes()` equals it **and** `rootAttributes()` is unchanged from today's behavior; when the parent **is** the role-resource target (top-level category create) the resolver is invoked **once** for that `(type, id)` (the `RecordingCache` read-through) and both fields carry the same map | T2 |
| U20 | A declared parent that resolves to null/blank denies | `parentResourceId = "#missing"` (an unbound SpEL variable) and `"''"` → DENY, OPA never called (the `roleResourceId` posture — never a silent "no parent") | T2 |
| U21 | Resolver failure → absent, never an exception | resolver returns `Optional.empty()` → the field **absent** (`parentAttributes() == null`), OPA **called**, the decision proceeds (allow under the mock); resolver **throws** → absent, OPA called, nothing propagates; `resolutionSupport == null` → absent; in each the resource's `attributes` are still the declared ones | T2 |
| U22 | Untagged parent → `{}`, distinguishable | a resolved parent whose `abacAttributes()` is `Map.of()` → `parentAttributes()` is `{}` (present-empty on the wire), not `null`; and a gate with **no** parent declaration produces a context byte-identical to today (the `OpaPreAuthorizeRootAttributeEnrichmentTest` suite passes unmodified) | T2 |

## Integration — the catalog service (I1–I6, Testcontainers + the action-aware OPA stub)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| I1 | A category create puts the placement parent on the wire | `POST …/catalogs/{c}/categories` under a catalog seeded with `{region:[emea]}`: the recorded `category:create` context carries `parent_attributes == {region:[emea]}`, `root_attributes` equal to it, and `attributes` == the request's tags (`{}` for a body without tags); a **nested** create (`parentId` = a category tagged `{region:[apac]}`) carries the parent **category's** map in `parent_attributes` while `root_attributes` still carries the catalog's | T3 |
| I2 | A product create carries the category's tags, not the catalog's | `POST …/categories/{k}/products` where the category is `{region:[apac]}` under an emea catalog: `parent_attributes == {region:[apac]}`, `root_attributes == {region:[emea]}`, `attributes` == the payload's tags | T3 |
| I3 | The second decision carries the same inputs; sequences unchanged | a create **with** tags asks `[category:create, category:assign-tags]` and the assign-tags context carries the same `parent_attributes` and `attributes`; a create **without** tags asks `[category:create]` alone (the existing `createWithoutTagsAsksCreateAlone` cell, unmodified); products mirrored | T3 |
| I4 | A deny persists nothing and leaks nothing | the stub denies `category:create` for a body carrying an **unknown** tag key → **403** `application/problem+json` with `errorCode: ACCESS_DENIED` (never the 422 vocabulary — authorization precedes validation), `categories.count()` unchanged; the same for a product | T3 |
| I5 | Re-parent is a placement | a `PUT` changing `parentId` asks `[category:update, category:create]` — the second with `parent_attributes` == the **new** parent's tags and `attributes` == the request's tags; the stub denying that `category:create` → **403**, the row's `parentId` **and** its ltree path unchanged (asserted on a re-read); a `PUT` with the **same** `parentId` asks `[category:update]` only (the existing cells unmodified); a `PUT` moving to the **root** (`parentId: null`) asks the placement with the catalog as parent | T3 |
| I6b | A parent in another catalog is unproven on the wire | a plain-editor create under catalog A with `parentId` = a tagged category of catalog **B** → the recorded context's `parent_attributes` is **null** (its chain roots at B, not A) while `root_attributes` carries A's tags; the body answers **404** — a foreign resource's tags never reach the decision, so a tag-requiring role's 403 reveals nothing about them | T3 |
| I6 | A missing parent is a 404 after the gate, never a 500 | a plain-editor create with a `parentId` that does not exist → the gate passes with `parent_attributes` **absent** (the resolver miss is "unproven"), the body's existence check answers **404**; the recorded context proves the field is absent, not `{}` | T3 |

## E2E — newman, through the gateway (E1–E7 = the collection's `7a`–`7g`)

> Rig: `./deploy.sh build` (the catalog image carries T3) then `ENABLE_MCP=1 ./deploy.sh up --pods
> 2` (SPA + directory default on; MCP on the same `up`) and `docker restart opa-abac-opa` (T1's
> policies; the runner restarts it itself). Persona: the matrix's own `gated-writer`
> (`catalog: [READ, WRITE, TAG]` + `category: [READ]`, `region ANY_OF [emea]` — the create verb reaches
> the child types only through the inheritable catalog grant) on the fixture catalog (untagged root) with
> its three seeded categories `match` (emea), `mismatch` (apac), `both` (emea + internal). Every
> cell asserts status **and** body; cleanup leaves the fixture world as found. Between `7d` and
> `7e` a `[bind]` item rebinds the same realm user to `gated-direct` (`category`/`product`
> `[READ, WRITE, TAG]`, the same requirement, no catalog permission — the direct path), via
> `POST {{user_service}}/internal/bootstrap/memberships` → 200, the `permission-categories-matrix`
> precedent.

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| E1 | `7a` — the gated writer cannot place a category under the untagged root | `POST …/catalogs/{{catalog_id}}/categories` `{"name":"placed-under-root"}` as `gated-writer` → **403**, `application/problem+json`, `errorCode: ACCESS_DENIED`, `status: 403`; the follow-up owner list of root-level categories does not contain `placed-under-root` (positive assertion on the fixture names it **does** contain); the same persona's 6a (root read 200) and 6b (root PUT 403) unchanged in the same run | T4 |
| E2 | `7b` — a matching payload under a mismatching parent | `POST …/categories/{{mismatch_category_id}}/products` `{"name":"emea-product-in-apac","tags":{"region":["emea"]}}` as `gated-writer` → **403** `ACCESS_DENIED`; the owner's product list of `mismatch` has `count: 0` for that name (asserted on the exact count of the fixture) | T4 |
| E3 | `7c` — a matching placement succeeds | the same body under `{{match_category_id}}` → **201**, a `Location` header, body `tags.region` includes `emea`, `categoryId == match`; the owner deletes it by the captured `match_product_id` (**204**) and the list is back to the fixture count | T4 |
| E4 | `7d` — moving a category under a mismatching parent is denied | as `gated-writer`: `POST …/categories` `{"name":"movable","parentId":"{{match_category_id}}","tags":{"region":["emea"]}}` → **201** (a matching placement, id captured); `PUT …/categories/{{movable_id}}` with `parentId: {{mismatch_category_id}}`, same name and tags → **403** `ACCESS_DENIED`; `GET …/categories/{{movable_id}}` as owner → `parentId == match` (the row did not move); cleanup: owner `DELETE` → 204 | T4 |
| E5 | `7e` — the direct path cannot place a **matching** payload under the untagged root | after the `[bind]` to `gated-direct` (200 asserted): `POST …/catalogs/{{catalog_id}}/categories` `{"name":"direct-under-root","tags":{"region":["emea"]}}` → **403** `ACCESS_DENIED` — the index's reproduction row 7, the escape a payload-only rule would have left open; the owner's list does not contain it | T4 |
| E6 | `7f` — the direct path, a matching payload under a mismatching parent | `POST …/categories/{{mismatch_category_id}}/products` `{"name":"direct-emea-in-apac","tags":{"region":["emea"]}}` → **403** `ACCESS_DENIED`; the owner's product list of `mismatch` unchanged (exact count) | T4 |
| E7 | `7g` — the direct path, a matching placement succeeds | the same body under `{{match_category_id}}` → **201**, `Location`, `tags.region` includes `emea`, `categoryId == match`; owner `DELETE` → 204 afterwards and the list is back to the fixture count | T4 |

## E2E — the packaged SPA in the Browser pane (E8)

> Precondition: the rig as above plus `scripts/postman/seed-demo-data.sh`; the pane at
> `http://localhost:9085`; the maintainer performs every login, the agent drives the authenticated
> console (the no-password rule). Setup per [[PRE-HABR-UI-QA-2026-09-10]] §fixtures: as `editor`,
> create a custom role with WRITE + TAG on catalog/category/product and `required_tags {region:
> [apac], sensitivity: [public]}` `ALL_OF`, bind `viewer` to it on the Demo catalog; restore
> `viewer`'s canonical `demo-viewer` role afterwards.

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| E8 | The create form answers the 403 honestly | as `viewer` (the tag-requiring writer) on the untagged Demo catalog: the "new category" form with a name only → the console shows the server's **403** in its error surface (no optimistic row, no silent swallow); the wire (network log or a `fetch` probe with the session token) shows `POST …/categories` → 403 `ACCESS_DENIED`; the same form with `region: apac, sensitivity: public` → still **403** (the root is untagged — placement, not payload); the category list after both attempts is unchanged; as `editor` the same form → 201 (the no-requirement control) | T5 |
