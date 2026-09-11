---
tags:
  - status/planned
  - type/architecture
  - area/abac
  - area/opa
  - area/spring
  - area/catalog-service
---

# TAG-GATED-CREATE — 00-DESIGN

> The **collaborative fix** for DEF-1 of [[PRE-HABR-UI-QA-2026-09-10]]: a role that holds WRITE **and**
> a tag requirement can today create categories and products it is denied to read, and can move a
> category under a parent it cannot see. Settled 2026-09-11 (grill-me: eight forks, all recorded in
> §Considered-and-rejected). **Not an autonomous slice**: a mini package + a collaborative build (§6),
> the ADR written up front as [[0034-tag-gated-placement-input-contract|ADR 0034]].

## The defect in one paragraph

`category.rego` and `product.rego` decide every **type-level** request (no resource id — a list, a
create, an assign-tags before the instance exists) through one verb-agnostic clause:
`is_type_level_request; not denied; list_inheritable_grant`. The grant only asks whether the verb is in
the role's effective actions on an inheritable ancestor type (the catalog the role was resolved on).
For **LIST** that is correct — the rows are cut afterwards in SQL by the same tag match. For **CREATE**
nothing cuts afterwards: `tags_satisfied` is never consulted, and the decision could not consult it
anyway because a type-level input carries an **empty attribute map** (the payload never reaches OPA)
and nothing about the **placement parent** — the catalog's tags do arrive as `root_attributes` (ADR
0032 threads the `roleResource` override target on exactly these gates), but a nested category's
parent category and a product's parent category are fetched nowhere. Measured with `alice-role`
(`{catalog, category, product: [READ, WRITE, TAG]}`, `required_tags {region:[apac],
sensitivity:[public]}`, `ALL_OF`) on the untagged Demo catalog: `POST …/categories {name}` → **201**
(then her own GET of it → 403: a write-only spawn); `POST …/categories/{EMEA}/products` → **201**
inside a category her GET answers 403 for. Two facts found at design time widen the class: a category
create may carry `parentId` (so the placement parent is a *category*, not the catalog), and a category
update may **change** `parentId` with only an existence check — so a create-time fix alone is
bypassable (create under a readable parent, then move under EMEA). No escalation beyond the role's own
WRITE — instance updates and deletes stay tag-gated — but a direct contradiction of the published
contract: "mutating verbs on the root and everything below the root stay fully tag-gated" (ADR 0022)
and "a role may act on resources whose tags match" (ADR 0009).

## What this builds on, and what it must not break

- **ADR 0009** — the requirement lives on the role; each resource is matched against its **own** tags;
  an untagged resource matches no requirement (fail-closed). Unchanged; this slice applies it at the
  one moment it was skipped.
- **ADR 0022** — the root-read exemption widens **reads** of the governing root, never mutations.
  A create under the root is a mutation of the root's subtree; this slice makes the policy say so.
- **ADR 0018 / Slice B4** — membership is the sole access path; the create gates resolve the role on
  the parent **catalog** through the `roleResource` override. Unchanged — the parent's tags are a
  *second* input, not a second role lookup.
- **ADR 0031** — `list_inheritable_grant` opens only for membership-derived roles. The new clauses
  sit *behind* it and inherit that confinement.
- **ADR 0032** — `root_attributes`: manager-side, memoized, three-state (absent = unproven, `{}` =
  untagged, map = tagged), `NON_NULL`. **The mechanism this slice generalizes**: the same resolve,
  the same memo, the same three states, one more field.
- **ADR 0016** — `_actions` excludes `list`/`create` by design (collection-level), so the new
  conjuncts cannot desynchronize the affordance map; the SPA's create panels already render the
  server's 403.
- **#122** — a present-but-malformed `required_tags` keeps denying; the parent match reuses the same
  presence guards.

**Must not break:** LIST end to end (the coarse gate, the SQL residual, both `filter` entrypoints —
byte-identical); every instance decision; `catalog.rego`; the wire for every existing caller (absent
fields serialize as today); the demo personas (none of `owner`/`editor`/`viewer`/`outsider`/`pm-demo`/
`sup-demo` is a tag-requiring writer, so the demo world's behavior is unchanged); the tag matrix's
existing cells; the `HierarchicalAuthorizer` seam (no type-level create goes through it).

## The slice boundary

**In**: the rule (§1); the input contract — `parent_attributes` and a declared `attributes` on the
annotation, ADR 0034 (§2); the policy split with LIST as the only coarse verb (§3); the example
gates, including the placement call on re-parent (§4); the three-tier ratchet (§5); the living-doc
sync (§7).

**Out, deliberately**:
- **Catalog create** — no parent, no tag-on-create; unchanged.
- **Product moves** — impossible by route shape (`categoryId` is path-bound); nothing to gate.
- **A distinct denial reason** for "parent unproven" vs "parent mismatch" — both deny; a reason
  string is a later nicety on the #122 denial axis.
- **Any change to `filter`, the SQL residual, or the list gates.**
- **The SPA** — no client change; one UI QA row proves the create form answers the 403 honestly.
- **The Central cut** — 1.3.0-SNAPSHOT stays open; this ships with the next release.

## The design

### 1. The rule: placement by tags

A type-level **create** or **assign-tags** on `category`/`product` by a role that carries a tag
requirement is allowed only when **both** hold, in addition to the grant on the verb — whether the role
names the child type **directly** or reaches it through an inheritable ancestor (both paths exist
today, and both must carry the conjuncts; see §3):

1. **The placement parent satisfies the requirement.** The parent is the resource the new one hangs
   from — the parent *category* when `parentId` is set, the *catalog* otherwise; for a product,
   always the category. Its tag map is matched under the role's `match_mode` exactly as an instance's
   own tags would be. **The root is included**: a category created directly under an untagged catalog
   by a tag-requiring role is **denied, in both states of `root_read_tag_exemption`** — the exemption
   widens reads, and a create is a mutation of the root's subtree, the same class as the `PUT` on the
   catalog that already answers 403.
2. **The payload satisfies the requirement.** The tag-on-create map (`{}` when the request carries
   none) is matched the same way. A creator must be able to read what it creates; an untagged create
   by a tag-requiring role is denied.

A role **without** a requirement passes both vacuously — nothing changes for it. The rule is
verb-pure: it never demands READ, so a WRITE-only role keeps creating wherever its tags match.

**Re-parenting is a placement.** When an update changes a category's `parentId`, the app asks the
same type-level create-shaped question on the **new** parent before any mutation; the instance update
decision itself is untouched (no new "absent means no move" rule leaks into the instance path).

### 2. The input contract: `parent_attributes` + declared `attributes` (ADR 0034)

**`AbacContext.Resource` gains a sixth, additive component** on the ADR 0032 pattern:

```java
@JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("parent_attributes")
Map<String, Object> parentAttributes
```

compat constructors keep every caller compiling; absent serializes byte-for-byte as today.
**Three states, all distinguishable in policy:** key absent = the parent was declared but could not be
proven (resolver empty/threw, no resolution support); `{}` = fetched, untagged; a map = the parent's
tags. `NON_NULL`, never `NON_EMPTY` — the ADR 0032 argument verbatim.

**`@OpaPreAuthorize` gains three SpEL attributes**, all optional, all blank by default:

| Attribute | Meaning | Resolves to null/blank |
|---|---|---|
| `parentResourceType` | the placement parent's type | declared but unresolvable ⇒ **deny** (the `resourceId` posture: silently degrading would widen) |
| `parentResourceId` | the placement parent's id | same |
| `attributes` | the decided resource's attribute map for a **type-level** check (the raw request tags) | `null` ⇒ `{}`; a non-`Map` value ⇒ **deny**; declared on an instance form (`resourceId`/`resource()`) ⇒ a declaration conflict ⇒ **deny** |

**Population is manager-side**, after the role-resource override, through the resolve that already
serves `root_attributes` (`resolveRootAttributes` becomes the generic
`resolveAttributesOf(type, id)`, read-through-memoized in the request cache): a declared parent that
resolves through the app's `AbacResourceResolver` SPI contributes its `abacAttributes()`; any failure
leaves the field **absent**. For a top-level category create the parent *is* the role-resource
target, so the memo makes the second resolve free and `root_attributes` and `parent_attributes` carry
the same map — by design, because the policy reads **only `parent_attributes`** for placement.
**There is no fallback** from an absent `parent_attributes` to `root_attributes`: a fallback is exactly
the fail-open ambiguity this slice closes.

The library still fetches only through the app's resolver SPI; which entity a `(type, id)` names stays
with the application.

### 3. The policy: LIST is the exception

In `category.rego` and `product.rego` the type-level decision gets one strict clause, and the two
clauses that can reach a type-level create today are scoped so that it becomes the **only** path
there. *(Amended at decomposition, 2026-09-11 — the adversarial pass proved with `opa eval` that the
instance clause `granted` is not id-scoped: `direct_grant` = "verb in the role's effective actions on
THIS type ∧ `tags_satisfied`", so a role naming the child type directly (the `alice-role` shape,
`category: [WRITE, TAG]`) reaches a type-level create through it. Today that path denies a
tag-requiring role only because the attribute map is empty; the moment T2 puts the payload on the
wire, a matching payload would pass it with no parent check. The first draft gated only the
inheritable path.)*

```rego
# INSTANCE decisions + type-level LIST via the direct path: today's semantics, byte-identical —
# `granted` (direct_grant / inherited_grant ∧ tags_satisfied) merely stops applying to a strict
# type-level request, which the third clause owns.
allow if {
	granted
	not denied
	not strict_type_level
}

# COARSE LIST GATE — unchanged semantics, now scoped to the one verb whose rows are cut afterwards.
allow if {
	is_type_level_request
	verb == "list"
	not denied
	list_inheritable_grant
}

# PLACEMENT GATE — every other type-level verb (create, assign-tags, and anything future) is strict,
# whichever way the verb is granted.
allow if {
	strict_type_level
	not denied
	type_level_verb_grant
	parent_tags_satisfied
	tags_satisfied
}

strict_type_level if {
	is_type_level_request
	verb != "list"
}

# The verb is granted on the decided type directly …
type_level_verb_grant if {
	verb in permissions.effective_actions(input.role_definition, input.resource.type)
}

# … or on a declared inheritable ancestor (membership-derived roles only, ADR 0031).
type_level_verb_grant if {
	list_inheritable_grant
}
```

For a **no-requirement** role every conjunct but the grant is vacuous, so the existing type-level
cells (which pass through `direct_grant` today — `editor_role_def` names `category`) keep passing.
For a **tag-requiring** role naming only the child type, type-level LIST stays what it is today: the
direct list path still matches the empty attribute map and denies — a pre-existing shape this slice
neither widens nor narrows.

`parent_tags_satisfied` mirrors `tags_satisfied` over `input.resource.parent_attributes`: the two
share one parameterized helper (`attribute_values(attrs, key)` — array ⇒ elements, scalar ⇒ singleton,
absent key ⇒ empty, key **presence** not truthiness; the existing `resource_tag_values(key)` stays as a
one-line wrapper over it, because two shipped test pins call it directly), the `ANY_OF`/`ALL_OF` rules
and the #122 `has_required_tags` guard, so the match-mode and malformed-requirement semantics cannot drift between
the two. `parent_tags_satisfied` is vacuously true for a role without a requirement; for a role with
one it is **undefined** when `parent_attributes` is absent or not an object, and false when the map
matches nothing (`{}` included) — absent and empty both deny, and the tests pin them as separate
cells so nobody collapses the states. The verb split is fail-closed by construction: a future
type-level verb lands on the strict path.

The type-level **assign-tags** decision stays a **separate second decision** (it is the only place the
TAG verb is checked — a WRITE-without-TAG role must still be refused a tag-on-create); it carries the
same declarations and runs the same strict clause. `filter`, `denied`, the instance clauses,
`catalog.rego`, `team.rego`, `role.rego`: untouched.

### 4. The example app: the declarations, and the placement call on re-parent

`CategoryController.createCategory`:

```java
@OpaPreAuthorize(action = "category:create", resourceType = "'category'",
        roleResourceType = "'catalog'", roleResourceId = "#catalogId",
        parentResourceType = "#request.parentId != null ? 'category' : 'catalog'",
        parentResourceId = "#request.parentId != null ? #request.parentId : #catalogId",
        attributes = "#request.tags")
```

`ProductController.createProduct`: `parentResourceType = "'category'"`, `parentResourceId =
"#categoryId"`, `attributes = "#request.tags"`. The two `TagDecisionGate.require*AssignTagsForCreate`
methods gain the parent and the tags as parameters and carry the same declarations.

**Re-parent**: `TagDecisionGate.requireCategoryPlacement(catalogId, parentType, parentId, tags)` — a
type-level `category:create` decision (`roleResourceType = 'catalog'`, `roleResourceId = #catalogId`,
like every create gate) with the **new** parent declared and `attributes` = the request's full tag
map (a `PUT` carries the category's post-update tags, so "may you place a resource with these tags
here" is the right question). `updateCategory` calls it when `entity.getParentId()` differs from
`request.getParentId()`, **after** the existing update/assign-tags dispatch and **before**
`guardGateSnapshot` and any mutation — every decision still precedes every write.

**Order is part of the contract**: authorization runs on the **raw submitted** tag map, dictionary
validation (`validateAndBuild`, 422) runs after allow — the order the assign-tags decision already
follows, kept so a 403 never leaks whether a key exists. Safe because validation never rewrites
values: the decision's view equals the stored form for every payload that survives it.

### 5. Validation: the three-tier ratchet

- **`opa test` per policy** (category and product alike; two tag-requiring fixtures — a **stamped**
  catalog-only writer (`catalog: [READ, WRITE, TAG]`, `attributes.provenance = "membership"`, without
  which the inheritable clause is undefined — ADR 0031) and a **direct** writer naming
  `category`/`product` itself, both with `required_tags {region:[emea]}`): list is unchanged on every
  row; create with a **mismatching**
  parent, an **absent** parent, an **empty** parent, an **untagged** payload, a **mismatching**
  payload, and the **matching payload under a mismatching parent** all deny; matching parent +
  matching payload allows; the **no-requirement** role is unchanged on every cell (vacuous truth);
  `root_read_tag_exemption` on and off give the **same** create outcome (pin the invariant, not the
  boolean); the assign-tags decision runs the same table; the placement call for re-parent is its own
  cell; a malformed `required_tags` still denies. `opa fmt`/`opa check` clean.
- **Starter unit tests** (`opa-abac-spring-security`): the three states of `parent_attributes` on the
  wire (absent / `{}` / map); the memo hit (one resolver call for a top-level category create); a
  declared parent that resolves to null ⇒ deny; a resolver throw ⇒ absent, never an exception; a null
  `attributes` ⇒ `{}`; a non-`Map` ⇒ deny; `attributes` on an instance form ⇒ deny; existing contexts
  serialize byte-identically. The Java diff runs through the local Sonar gate before it lands.
- **Example IT + e2e**: an IT on the catalog service that asserts the **wire** (the action-aware OPA
  stub decides by action name, so the IT pins what the gates declare — `parent_attributes`,
  `attributes`, the decision sequences, the 403-before-422 order, the unmoved row on a denied
  re-parent — never the tag semantics, which `opa test` owns); seven newman cells in
  `run-tag-matrix.sh`: `7a`–`7d` on the matrix's existing `gated-writer` (`catalog: [READ, WRITE, TAG]` + `category: [READ]` — no create verb on the child
  type, so the verb arrives only through the inheritable path) and `7e`–`7g` on the same realm user rebound to a `gated-direct` role naming
  `category`/`product` (the direct path), covering placement under the untagged root, a matching
  payload under a mismatching parent, a matching placement, and a denied re-parent; one row in the UI
  QA list: the console's create form under a tag-requiring role answers 403 honestly. The demo-world
  and hierarchy matrices stay green untouched.

### 6. Ship shape

A **mini package**: this note, `01-DECOMPOSITION` (ordered tickets — the policy + tests first, then
the starter contract, then the example gates, then the runners and docs), `STATUS-NN` stubs — **no
autonomous-implementation prompt, no orchestrator**. Built together on
`feature/void3110/tag-gated-create`, ticket by ticket. **Review routing for this slice (maintainer,
2026-09-11 — a per-slice choice under the weekly usage limits, not a change to the standing rule):**
the multi-lens `/deep-review` workflow runs with its lenses on **Opus 5**, followed by **one
single-agent review pass on Fable** and a `/security-review delta` on the annotation and context
change (the published surface). The fallback if the pool is short: the single-agent Fable pass plus
the security review alone. Then push · PR · merge.

### 7. Living docs touched in-branch

`docs/guides/TAG-BASED-AUTHORIZATION.md` §"Layer 3" (the create/placement case, the
`parent_attributes` shape, the 403-before-422 order); `docs/guides/ABAC-AUTHORIZATION.md` (the input
schema: the sixth component and its three states); `docs/guides/ATTRIBUTE-RICH-PRE-AUTHORIZATION.md`
(the three new annotation attributes); `docs/guides/DEMO-CONSOLE-WALKTHROUGH.md` §3.4 (the "Known gap
(DEF-1, planned)" callout becomes the shipped behavior — T3); `docs/guides/E2E-TESTING.md` §"Tag-based
ABAC matrix" (the cell table and its "9 requests / all 9 green" counts — T4); ADR 0022's and ADR
0009's status lines (already pointing at ADR 0034); `scripts/postman/README.md` (the matrix row);
the pre-Habr QA note's DEF-1 row (→ fixed, PR link); the roadmap line; `docs/to-do/planning/USER-STORIES.md`
C5 (added at decomposition, flipped to ✅ at ship).

## Considered and rejected (the grill, 2026-09-11)

- **"Readable parent" instead of "parent tags satisfy the role"** (Q1) — rejected: the two diverge
  only at the root, where the read formulation inherits ADR 0022's exemption and makes the create
  outcome depend on a read-side flag; it would also add a hidden READ requirement to a WRITE-only
  role. Placement by tags is flag-independent and verb-pure.
- **Parent = always the catalog** (Q2) — rejected: a nested category hangs from a category, and the
  reproduction under the root was only the simplest case. **Re-parent out of scope** — rejected: the
  create rule alone is bypassable by create-then-move. **Re-parent as a new conjunct on the instance
  update decision** — rejected: it needs "absent parent means no move" on the instance path, which is
  fail-open the day an adopter forgets to declare it; the second placement call reuses the strict,
  fail-closed create-shaped decision instead.
- **Reuse `root_attributes` for the parent** (Q3) — rejected: right only for a top-level category,
  wrong for nested categories and products. **Attributes on `ancestors`/`ParentRef`** — rejected by
  ADR 0030 §4 already. **Smuggling the parent's tags into `resource.attributes` under a reserved key**
  — rejected: a security-relevant input hidden behind a convention, colliding with a client-influenced
  namespace. **A policy fallback from absent `parent_attributes` to `root_attributes`** — rejected:
  the exact ambiguity the slice exists to close.
- **Deciding on the payload after validation, from a gate method via `resource()`** (Q4) — rejected:
  the controller annotation with an empty map would deny every tag-requiring creator before the body
  runs, so the decision would have to leave the declaration site; the allow-side cache write would
  also see a null id. A declared `attributes` keeps "the decision is the annotation" and the existing
  403-before-422 order.
- **Enumerating the strict verbs `{create, assign-tags}`** (Q5) — rejected: a future type-level verb
  would land on the open path; scoping the coarse clause to `list` alone is fail-closed by
  construction.
- **Folding the TAG check into the create decision** (Q6) — rejected: one verb name would cover two
  permissions, breaking the one-verb-one-decision model `_actions` is built on.
- **Amending ADR 0009 instead of a new ADR** (Q7) — rejected: 0009 is about *where* the requirement
  lives; a published input-contract and annotation-surface change deserves its own record. **Numbering
  0035 to honor the audience slice's earmark** — rejected: the earmark was a sentence in a to-do note,
  and filing 0035 before 0034 exists reads like a lost ADR; the audience note now says 0035.
- **A distinct denial reason for unproven vs mismatch** (Q8) — deferred: both deny; the states stay
  distinguishable and pinned in tests for the day a reason string is wanted.
- **The standing layer-3 routing verbatim (one Fable-orchestrated multi-lens pass)** (post-grill,
  maintainer) — replaced for this slice by Opus-5 lenses + a Fable single-agent pass + the security
  review, to keep the Fable sub-cap for the passes where it matters; recorded as per-slice, the
  standing rule unchanged.
- **Gating only the inheritable path** (the first draft of §3; caught by the decomposition's
  adversarial pass, 2026-09-11) — rejected: `direct_grant` is not id-scoped, so a role naming the
  child type would reach a type-level create on the payload alone. The strict clause now owns every
  non-list type-level request whichever way the verb is granted (§3).
