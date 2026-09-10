---
tags:
  - status/active
  - type/guide
  - area/abac
  - area/spring
---

# From the example to your application — a recipe, with prompts for your AI assistant

The example services are a **worked shape**, not a template to `sed`. This guide says which parts of
the shape carry over to your domain, in what order to change them, which invariant each step must
keep, which gate proves it — and gives you a prompt to hand your assistant at every step. The running
example maps the catalog onto an internal-developer-platform domain, because that is the most common
question the maintainer gets:

| The example's noun | Its role in the shape | A platform domain | Other domains that fit the same slot |
|---|---|---|---|
| **Catalog** | the **governing root** — the thing a *team* is bound to; the unit of ownership | **Project** | workspace · tenant · account · portfolio |
| **Category** (nested) | a mid-level container that *inherits* the root's grant | **Application** or **component group** | folder · environment · service |
| **Product** | the leaf you act on most | **Component** (a service, a library, a pipeline) | document · dataset · ticket |
| the **Team** (user-service) | who governs a root, with a role ladder | the project's team | the same |
| **Tags** on any type | attributes a role can *require* | `tier`, `env`, `data-class`, `region` | classification · jurisdiction · cost-centre |

> **Before this guide:** the README's "Adopting the starter — three things you must do" is the
> library-level contract (a `SecurityFilterChain` with the `AbacFilter`, `@EnableMethodSecurity`,
> the gateway-trust switch). This guide is about the *example* around the library: keep it, reshape
> it, or take only its pieces.

## 0. Decide what you are taking

- **Path A — the starter as a dependency, your own app.** Recommended when you already have a
  service and a domain. You take the library modules from Maven Central, implement the seams in §2,
  and use the example only as a reference for how each seam looks when wired. Most of this guide
  still applies: the shape, the invariants, the prompts.
- **Path B — the example as scaffolding.** Fork the repo, rename the hierarchy, delete what you do
  not need. Faster to a running rig, and you inherit opinions: Postgres with `ltree` paths, Liquibase,
  OpenAPI codegen, a separate user-management service, APISIX + Keycloak in front. Take it if those
  opinions are close to yours; otherwise Path A.

Either way, **run the demo first** ([[RUN-THE-DEMO]]) so every later change has a green baseline to
diff against.

## 1. The shape, in domain-neutral terms

- One **root type** per governance unit. Teams bind to a root (`Team.targetType` + `targetId` in the
  user-service); membership is the *only* way to reach anything under it (ADR 0018). Choose the root
  as *the thing you would put a team on* — in a platform, the project.
- **Mid and leaf types** hang under the root through a materialized path (`ltree`) and an
  `AncestorResolver`; a grant on the root **inherits** down through the per-type
  `<type>_inheritable.json` tables (ADR 0008/0010).
- **One policy document per type** (`<type>.rego`, package = type), all built on the shared
  `permissions.rego` and the coarse `permission_categories.json` (READ/WRITE/TAG/GRANT/CONTROL →
  fine verbs). The service posts `{"input": <AbacContext>}` to `/v1/data/<type>` and reads
  `result.allow`; lists use partial evaluation of the same document (ADR 0005/0007).
- **Actions are `<type>:<verb>`** on the controller (`@OpaPreAuthorize(action = "product:update", …)`).
  Type-level verbs (create, list) resolve the role on the **parent** via `roleResourceType` /
  `roleResourceId`.
- **Tags** are attributes on any type, validated against a runtime **dictionary** (global keys +
  team keys); a role may **require** tags (ADR 0009); reads of the root are exempt by default
  (ADR 0022).
- Everything past that — the supervisor path, the production tier, the MCP tool surface, the console —
  is **additive** and removable.

## 2. The rename surface — where every noun lives

Measured on the repo at the time of writing (case-insensitive hits of catalog/category/product):

| Area | Files | Hits | What is there |
|---|---|---|---|
| `example-catalog-management-service/src/main/java` | 46 | ~1.2k | `CatalogEntity`/`CategoryEntity`/`ProductEntity` + repositories, services, controllers, `CatalogResourceResolver` (the `AbacResourceResolver`), the role suppliers, `SecurityConfig` |
| `…/src/main/resources` | 6 | ~280 | the OpenAPI spec (`/api/v1/catalogs/{id}/categories/{id}/products`), Liquibase changelogs, `application.yml` (`opa.abac.*`, the user-service URLs) |
| `example-user-management-service/src/main` | 36 | ~200 | `Team.targetType` (`"catalog"`), role definitions keyed by type (`{catalog:[…], category:[…], product:[…]}`), the seeds/bootstrap, ownership checks |
| `infra/opa/policies` | 16 | ~2k | `catalog.rego`, `category.rego`, `product.rego` (+ `_test.rego`), `category_inheritable.json`, `product_inheritable.json`, `permission_categories.json`, `config.json`, `step_up.json`; `team.rego`/`role.rego` (user-service), `gateway.rego`, `agent_tools.rego` |
| `example-demo-ui/src` | 14 | ~550 | `api.ts` (paths + types), `App.tsx` (screens), `teams.tsx`, `tags.tsx` |
| `example-mcp-server/src/main` | 20 | ~250 | the `@McpTool` proxies (`list_catalogs`, `get_catalog`, `list_categories`, `get_product`) and their descriptors |
| `scripts/postman` | 42 | ~4k | the newman matrices, runners, the fixture-id registry |
| `docs/guides` | 17 | ~800 | prose |

Two consequences: **do not rename the e2e suite** — re-derive one matrix per cut from the existing
ones (§3, step 8); and **rename type by type, root first**, because everything below a type depends
on its policy package and its `roleResource` rule.

## 3. The order of work

Each step: what changes · the invariant it must keep · the gate that proves it · the prompt.

### Step 1 — Name your hierarchy (no code yet)

Fill the table in the header for your domain. Decide the root (the team target), the verbs per type
(the example uses view/list/create/update/delete/assign-tags; add `deploy`, `promote`, `approve`… as
**fine verbs under a coarse category**, never as new categories), and which types are taggable.

> **Prompt.** *"Here is the example's hierarchy: catalog (root, the team target) → category (mid,
> inherits) → product (leaf). My domain is ⟨describe it⟩. Propose the mapping as a table: my type ·
> its slot (root/mid/leaf) · the verbs it needs · whether teams bind to it · which tags it carries.
> Flag anything that does not fit the shape — a leaf that needs its own team, a verb that is really a
> workflow state, two roots — and tell me which ADR the misfit collides with (0008, 0018, 0022)."*

### Step 2 — The root type

Rename `CatalogEntity`/repository/service/controller/resolver → your root; the OpenAPI paths; the
Liquibase changelog; `catalog.rego` → `<root>.rego` with `package <root>` and its tests;
`Team.targetType` in the user-service and its seeds; the role definitions' permission key.

**Invariant:** the root document's `allow` is still `verb ∈ effective_actions ∧ tags_satisfied ∧
¬denied`; `root_read_tag_exemption` still names *view/list only*; `<root>:create` stays the single
realm-role verb.
**Gate:** `opa test infra/opa/policies` green; `./gradlew :example-…:test`; the root's newman cells
(allow for a member, `{count:0}` for a stranger, `403` on a deep link).

> **Prompt.** *"Rename the root type `catalog` → `⟨root⟩` across the catalog service, the OPA policy
> `catalog.rego` (package, rule names, tests), the inheritable tables, the user-service `targetType`
> and seeds, and the OpenAPI spec. Keep the policy's decision logic byte-for-byte except for the
> names; keep `⟨root⟩:create` as the only realm-role-granted verb; keep `data.config.root_read_tag_exemption`
> scoped to view/list. After the rename run `opa test infra/opa/policies` and the module's tests and
> show me both outputs. List every file you touched with the number of replacements, and separately
> list any occurrence of `catalog` you deliberately left (docs, historical notes, the e2e suite)."*

### Step 3 — The mid and leaf types

Repeat for `category` → your mid type and `product` → your leaf: entities, controllers, the two
policy documents, and their `<type>_inheritable.json` (which ancestor types a grant inherits from).
Keep `roleResourceType = '<root>'` on type-level verbs.

**Invariant:** `list_inheritable_grant` only *opens* the coarse type-level gate; the row cut stays in
SQL. Know the standing gap: a **tag-requiring** WRITE role can create children it cannot read
(`docs/to-do/planning/TAG-GATED-CREATE/`) — if your domain leans on required tags, take that fix
early.
**Gate:** the hierarchy matrix (a grant on the root reaches the leaf; a stranger sees nothing at any
depth).

> **Prompt.** *"Add my mid type `⟨mid⟩` under `⟨root⟩` exactly the way `category` sits under
> `catalog`: entity with an `ltree` path, repository, service, controller with `@OpaPreAuthorize`
> actions `⟨mid⟩:view|list|create|update|delete|assign-tags`, `roleResourceType='⟨root⟩'` for the
> type-level verbs, the policy document `⟨mid⟩.rego` cloned from `category.rego` with a test file,
> and `⟨mid⟩_inheritable.json` declaring inheritance from `⟨root⟩`. Then write the three contrast
> tests I will keep forever: a root-team member reads a ⟨mid⟩ (allow), a non-member is denied on the
> single GET (403), and the list for the non-member is `{count:0}`. Do not touch the e2e collections."*

### Step 4 — Roles, teams, and the role source

Keep the user-service if you want teams, a role ladder, custom roles and a tag dictionary out of the
box. Otherwise implement `RoleDefinitionSupplier` yourself (the example's `HttpRoleDefinitionSupplier`
and `DemoRoleDefinitionSupplier` are the two shapes) and `GovernedScopeResolver` for the list base
scope.

**Invariant:** the supplier returns a **role definition** (permissions per type + `required_tags` +
`denied_actions`), never a bare role name; an outage is an **error**, never an empty grant (ADR 0014);
a custom role can never hold CONTROL verbs (ADR 0015).
**Gate:** `SupplierOutageGateIT`-style tests: source down → deny; source empty → deny; source
malformed → deny (never 500).

> **Prompt.** *"Implement `RoleDefinitionSupplier` against ⟨my role store⟩. The contract: given
> (subject, root type, root id) return the role definition or empty; throw on transport/parse
> failures so the gate fails closed (ADR 0014). Port the three outage tests from
> `SupplierOutageGateIT`. Then show me how `denied_actions` and `required_tags` flow from my store
> into the OPA input unchanged."*

### Step 5 — Tags for your domain

Define your global keys (`env`, `tier`, `data-class`…) in the dictionary seed; mark the ones only an
operator may set as `operatorManaged` (the example's `env`); put `required_tags` on the roles that
should be scoped by attribute.

**Invariant:** unknown key or value → `422 TAG_VALUE_ILLEGAL`; `operatorManaged` keys have no client
write path; a role without a requirement is never tag-gated.
**Gate:** the tag matrix's contrast pair (same role, two siblings differing only in a tag → 200/403).

> **Prompt.** *"Add the tag keys ⟨list⟩ to the global dictionary seed with these value sets; make
> `⟨key⟩` operator-managed like `env`. Give role `⟨role⟩` `required_tags` `⟨map⟩` with match mode
> ⟨ANY_OF|ALL_OF⟩. Write the paired contrast test: the same member reads two `⟨leaf⟩`s that differ
> only in `⟨key⟩` — 200 and 403 — and the list under that role returns exactly the matching one."*

### Step 6 — Gateway and realm

Usually unchanged: routes proxy `/api/*`; APISIX validates the bearer; the app trusts the forwarded
JWT. Rename the Keycloak clients if you like (`catalog-spa` → `⟨app⟩-spa`) and **add an audience
binding** before anyone but you uses the rig — the example does not have one yet
(`docs/to-do/planning/GATEWAY-AUDIENCE-BINDING/`).

### Step 7 — The console (optional)

Rename the paths and types in `api.ts`, the screen nouns in `App.tsx`; or delete `example-demo-ui`
and `ENABLE_SPA=0`. The `_actions` and `_provenance` contracts are what the screens render — keep the
enrichment and the console follows.

### Step 8 — The e2e suite: re-derive, do not rename

Keep the example's collections as the reference and write **one matrix per cut** for your nouns,
each cell an allow/deny contrast that asserts the cut ([[E2E-TESTING]]): membership, hierarchy,
tags, the error contract. The runners' token minting and the fixture-id registry are reusable as-is.

> **Prompt.** *"Using `scripts/postman/run-hierarchy-matrix.sh` and its collection as the template,
> write `run-⟨root⟩-matrix.sh` + a collection for my hierarchy: mint the same four personas, seed one
> ⟨root⟩ with one ⟨mid⟩ and one ⟨leaf⟩, and assert these cells: member reads all three (200); a
> non-member's list is `{count:0}` at every level; a non-member's deep link is 403 problem+json at
> every level; the viewer role has `_actions` mutations false. Every cell must be able to FAIL —
> run `scripts/checks/check-collection-conformance.py` and show me it is clean."*

### Step 9 — Remove what you do not need

The MCP server (`example-mcp-server`, `ENABLE_MCP`), the supervised/step-up path (ADRs 0029–0033:
the `/internal/supervised-targets` edge, `step_up.json`, the console's chip), the resilience stub —
each is behind a flag or a module and comes out cleanly. Delete rather than leave dormant.

## 4. Two prompts to keep for every step

**The adversarial review.**
> *"Review the diff of this step as a reviewer trying to widen access. Look for: a fail-open on a
> missing/malformed input (empty role definition, absent tags, a null id read as type-level), a
> realm-role fallback that reappeared, a list that returns rows the single GET would deny, a type-level
> verb that skipped `tags_satisfied`, a 500 where a 403 was promised. For each, give me the request
> that proves it. Then run `opa test` and the module tests and paste the summary."*

**Explain a denial.**
> *"Request ⟨method path⟩ as ⟨persona⟩ answered ⟨status⟩. Trace it: what the subject extractor
> read from the token, what the role supplier returned for the governing root, the exact OPA input,
> and which clause of `⟨type⟩.rego` decided. Say whether the answer is correct under the ADRs, and if
> not, which layer is wrong."*

## 5. Pitfalls the maintainer has already paid for

- Removing the realm-role fallback broke **every** type-level gate, not just lists — the fix was the
  `roleResource` parent-resolution seam, not a special case (Slice B4).
- Resolving a type-level ceiling by asking for the *requested* type under-approximates when grants
  live on the root; enumerate the root's grants too (Mulch `spring-security-integration`).
- OPA does not watch its policy mount and `deploy.sh up` rebuilds only *absent* images — after a
  policy or code change, restart OPA and `./deploy.sh build` (Mulch `opa-abac-rig-deploy-ops`).
- A tag-requiring WRITE role can create what it cannot read (`TAG-GATED-CREATE`).
- The gateway checks signature, expiry and issuer, not audience (`GATEWAY-AUDIENCE-BINDING`).
- `opa.abac.subject.trust-forwarded-jwt=true` is only safe behind a signature-validating gateway;
  the starter refuses to extract subjects until you say so.
