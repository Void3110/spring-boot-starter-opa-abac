---
tags:
  - status/active
  - type/decision
  - area/abac
  - area/opa
  - area/spring-data
---

# ADR 0008 — Hierarchical (N-level ancestor) resource authorization

> **Naming note (2026-07-08, pre-publish API polish):** the interface this ADR calls `AbacDataObject` was renamed **`AbacResource`** before the first publish — it names the role the object plays in the authorization query (`input.resource`), completing the `AbacResourceResolver`/`AbacResourceCache` naming family. The decision content is unchanged.


**Status:** Accepted (implemented — Slice 5.5-A, [[HIERARCHY-SINGLE-RESOURCE]])
**Date:** 2026-06
**Context tags:** ABAC, OPA, resource hierarchy, ancestor inheritance, partial-eval, fail-closed, ltree

> This ADR pins the model for **authorizing a resource by its place in an ancestor chain** —
> "a grant on a Catalog governs a Category and a Product nested under it, N levels deep." It is the
> general form of the one-step parent hop the [[TAG-DICTIONARY]] demo stubbed with the app-layer
> `CategoryAuthorizer` (a Category's role resolved on its governing Catalog) and the gap [[DATA-FILTERING]]
> explicitly deferred. The decision is **unified here**; it ships as **two sequenced slices** (single-resource
> first, hierarchy-aware list filter second — see *Consequences*). Built clean-room; the prior platform is
> study-only.

## Context

Our domain is a genuine tree with **independently meaningful nodes**: Catalog → Category (self-nesting via
`parentId`, unbounded depth) → Product. Today authorization is **leaf-only or one-step**:
`@OpaPreAuthorize` resolves the role on the **leaf type itself**; only the two hand-written authorizers
(`CategoryAuthorizer` / `CategoryListAuthorizer`) do a single hard-coded hop, resolving the role on
`("catalog", catalogId)`. A Product has **no** parent-governed path at all and does not even carry
`catalogId`. So a check on `catalog/{id}/category/{id}/product/{id}` cannot today consider the **whole**
ancestor chain — at best the root and the leaf, never the levels between.

The reference platform (study-only) accepts a root+leaf shape because its sub-resources are **parts of an
aggregate** (you don't restrict individual leaves). That is the wrong default for a **general library**
whose users' trees may have per-node governance. We need an **N-level ancestor walk** where a grant on any
ancestor can satisfy a check on a descendant — and it must compose with the shipped Phase-5 partial-eval
**list** filter (an ancestor grant must *widen* which rows a list returns), all while staying **fail-closed**
(no path returns more access on error than on success) and keeping `opa-abac-core` Spring-free.

Cross-platform research framed the design space: **Cedar** (entity `in` hierarchy, opt-in, deny-overrides,
app supplies the graph); **GCP IAM / Azure RBAC** (always-on additive inheritance, non-removable at a
child); **AWS SCP** (guardrail/intersection, never grants); **Zanzibar/ReBAC** (`viewer from parent`
tuple-to-userset, declared once); and the SQL tree models (adjacency-list, materialized-path/`ltree`,
nested-set, closure-table) with their read-vs-write-vs-reparent trade-offs.

## Decision

A resource is authorized against its **ancestor chain**, resolved app-side and supplied to OPA, with
**opt-in** inheritance, **deny-overrides**, and a strict **fail-closed** posture. Eight pinned choices:

1. **Parent linkage in core, the walk in spring-data.** `AbacResource` (core, Spring-free) gains an
   optional default `abacParent() → Optional<ParentRef>` (`ParentRef` = a neutral `(type, id)` record in
   core). It is the *declarative* source of truth for one hop. The **N-level walk** is an
   `AncestorResolver` **SPI** in `opa-abac-spring-data` — core learns the *concept* of a parent but holds
   no SQL.

2. **Chain reaches Rego as `input.resource.ancestors`.** A flat list `[{type,id}, …]`, **root-first,
   leaf excluded** (the leaf is already `resource.type`/`id`). The **role is resolved once on the
   governing root** (generalizing today's one-step). The decision = the **leaf's own tags** (exactly as
   today) **plus** an *inheritable-ancestor-grant* check over the chain. **Per-ancestor independent
   grants** (a mid-tree Category with its own team) are **out of scope** → the Phase-8 ReBAC concern.

3. **Inheritance is opt-in per relation, default-off.** A small **structural inheritance declaration**
   ("resource type T inherits authorization from ancestor type A") tells the library whether to build the
   chain / widen at all; **default is no inheritance** (each type authorized on itself, as today). What an
   ancestor grant *satisfies* is expressed in a **Rego clause** ("an ancestor's granted verb on its type
   satisfies a descendant's action"). **`RoleDefinition` is unchanged** — inheritability is a property of
   the type-relationship, not of a role's grant.

4. **Deny-overrides is a final narrowing AND.** An explicit deny (a `forbid` clause / a deny tag on the
   leaf) is evaluated **after** the inheritance widening and **wins**:
   `final_allow = (direct_leaf_grant OR inherited_grant) AND NOT denied`. A denied leaf is excluded even
   when an ancestor would grant it — deny strictly narrows, never widens.

5. **Adjacency-list source of truth + an `ltree` materialized-path index (default), behind an SPI with
   two impls.** `parent (type,id)` is authoritative. The **ltree** resolver (default) denormalizes a
   `path` column so "get the ancestor chain" is one indexed read and the list filter is a SQL-pushed
   `path <@`. The **recursive-CTE** resolver carries no denormalized column (reads live `parent_id`). The
   `AncestorResolver` SPI abstracts the choice; the app picks per its re-parent frequency.

6. **`path`/ltree maintenance is a library mechanism via an opt-in `AbstractHierarchicalEntity`.**
   A new base `AbstractHierarchicalEntity extends AbstractSecuredEntity` (in `opa-abac-spring-data`) adds
   the `ltree path` column, the `abacParent()` scaffolding, a **path-maintainer** (derive `path` from the
   parent on insert/update), and an **atomic `reparent()`**. Non-hierarchical secured entities keep
   extending `AbstractSecuredEntity` and pay nothing. The cost of being hierarchical is "extend this base
   + declare your parent." A Product's missing `catalogId` is solved **via the path** (it encodes the full
   lineage), not a redundant FK; the CTE resolver instead walks `product → category → catalog` live.

7. **Re-parenting is the first-class invalidation event.** Moving a node under a new parent must, for the
   ltree resolver, **rewrite the `path` of the entire moved subtree in the same transaction** as the
   `parent_id` change (fail-closed if it cannot complete — a concurrent decision must never see a
   half-rewritten tree). The CTE resolver is **correct-by-construction** on re-parent (no denormalized
   state). The slice's e2e **must include a re-parent test** that proves a decision flips after a subtree
   move.

8. **Fail-closed for the walk itself.** A **mandatory configurable depth bound** (e.g. `maxDepth`) and
   **mandatory cycle detection** (visited-set / CTE `CYCLE`); a breach yields **no widening**. The
   load-bearing semantic: a walk failure (cycle / broken chain / too-deep / SQL error / null-path)
   **collapses the *inherited* contribution to nothing but preserves the direct leaf grant** —
   `final_allow = direct_leaf_grant OR (walk_ok AND inherited_grant)`, so a failed walk degrades to
   *exactly* today's pre-hierarchy decision, never wider. For lists, a walk failure → an empty
   `subtreeSpec` → exactly today's tag-only rows.

### How it composes with the Phase-5 list filter (the load-bearing integration)

The shipped partial-eval residual is a DNF over the **row's own JSONB tags**; "is this row in an allowed
subtree" is a fact about **lineage**, not tags. Therefore **the OPA residual stays tag-only** (the
`CompileResponseParser` / `ResidualSpecificationFactory` / closed operator set are **unchanged**), and
hierarchy widening is a **separate, app-built `subtreeSpec`** OR-ed into the query:

```
combined = scope.and( tagResidual.or(subtreeSpec) ).and( notDenied )
```

`subtreeSpec` = `path <@ <allowed-subtree-roots>` (ltree) **or** `id IN (<descendant-id-set>)` (CTE),
where the **allowed-subtree-roots are resolved once per list call** from the subject's inheritable
ancestor grants. Fail-closed by construction: if hierarchy resolution fails, `subtreeSpec` is an empty
(false) predicate and the result falls back to the **narrower** tag-only filter — never wider.

## Considered options

| Option | Why not (here) |
|--------|----------------|
| **Always-on additive inheritance (GCP IAM / Azure RBAC)** — an ancestor grant *always* flows to all descendants, non-removable at the child | Too broad a default for a general library; surprising and hard to scope down. We take Cedar/ReBAC's **opt-in** stance instead (default-off, per-relation), which is safer and matches OPA's policy-as-code ethos. |
| **Per-ancestor role resolution** — resolve a role at *every* ancestor and OR them (a mid-tree node can carry its own independent team/grant) | N supplier calls per decision and it pushes the team graph into the decision — that **is** ReBAC-in-Rego (Phase 8). We resolve **once at the governing root** and supply the chain as context. |
| **Teach the residual model an ltree operator (Option A)** — emit `path <@` *inside* the OPA residual so hierarchy is "all in one residual" | Couples the **hardened**, closed Phase-5 operator set to a lineage column and expands the Compile-AST parser — exactly the machinery we kept small for fail-closed reasons. We keep the residual **tag-only** and add hierarchy as a **separate app-built `subtreeSpec`** (Option B). |
| **Post-fetch allowlist to add subtree rows (Option C)** | Structurally impossible: the allowlist finisher only *removes* rows; it cannot *add* the subtree rows the tag-residual excluded. Wrong tool for "ancestor grant widens." |
| **Push the whole hierarchy graph into OPA `data` + `graph.reachable`** | Self-contained fast decisions, but creates a data-freshness/bundle problem on **every re-parent** and makes the DB no longer authoritative. Research flagged this as the weakest fit for a DB-authoritative hierarchy. We keep the hierarchy in Postgres and resolve app-side. |
| **An `inherited_permissions` flag on `RoleDefinition`** | Makes the role carry transport-routing concerns and breaks the clean `{type → verbs}` model. Inheritability belongs to the **type-relationship**, so it lives in structural config + the policy; the role stays unchanged. |
| **A redundant `catalogId` FK on Product** | Duplicates lineage the `path` column already encodes and would need its own maintenance on re-parent. The `path` is the single denormalized lineage; the CTE resolver walks live instead. |
| **Nested-set / closure-table tree model** | Nested-set is bad under writes (renumbering); a closure table is the most flexible but its storage and write/move cost exceed materialized-path for our read-heavy, occasionally-moved tree. Adjacency-list + `ltree` is the sweet spot; the CTE impl covers the re-parent-heavy case. |
| **One big slice (single-resource + lists together)** | Entangles new machinery with the **hardened** Phase-5 filter in a single autonomous run. We **split**: ship + prove single-resource first (de-risk the walk/SPI/re-parent), then the list integration. |

## Consequences

- **Good:** a check on `catalog/{id}/category/{id}/product/{id}` considers the **whole** ancestor chain;
  inheritance is **opt-in** and **fail-closed** (a failed walk never widens, never strips a direct grant);
  the Phase-5 residual machinery is **untouched** (hierarchy is an additive `subtreeSpec`); `opa-abac-core`
  stays Spring-free; the `ltree`-vs-CTE trade-off is an **SPI choice** the app makes, not a baked-in
  assumption; and the feature is the strongest "fills a real gap vs naive OPA integration" artifact after
  partial-eval.
- **Cost:** a denormalized `path` column to maintain (the ltree impl) with an **atomic subtree rewrite on
  re-parent** — the dominant correctness risk, which is why re-parent is a tested first-class event; an
  ancestor-chain resolution per single-resource decision and an allowed-subtree-roots resolution per list
  call (both cheap with the path index / cache-able); a new opt-in entity base and a structural inheritance
  declaration the app must wire.
- **Boundary / sequencing:** ships as **two slices under this one ADR** —
  **Slice A (single-resource hierarchy):** `ParentRef`/`abacParent()` + `input.resource.ancestors`,
  `AbstractHierarchicalEntity` (ltree + atomic `reparent()`), the `AncestorResolver` SPI + both impls,
  cycle/depth guards, the Rego inheritance clause + deny-overrides, single-resource checks, e2e **incl. the
  re-parent test**.
  **Slice B (hierarchy-aware list filter):** the `subtreeSpec` composition into the Phase-5 residual
  (`path <@` / `id IN`), deny-overrides on lists, e2e (two subjects → different subtree row sets). Builds on
  A's proven resolver. A list-specific fork, if one surfaces during B's decomposition, earns its own
  ADR; until then this ADR governs both. *(Those forks surfaced — the `subtreeSpec` composition, the
  `subtreeOf` SPI extension, deny-overrides-as-SQL, and the hierarchy-aware allowlist batch — and are
  pinned in [[0010-hierarchy-aware-list-filter|ADR 0010]]. The number originally reserved here, "0009",
  was taken by [[0009-tag-requirement-subject-side|the tag-requirement decision]], so the list fork is
  0010.)*
- **Relation to ADR 0006:** this deepens **layer 2 (app, per-resource)** from one-step to N-level and makes
  **layer 3 (DB list filter)** hierarchy-aware — it does not change the three-layer model, it generalizes
  the resource-resolution within it.

## Related

- ADR [[0006-three-layer-enforcement-model|0006]] (the layers this deepens) · ADR
  [[0005-partial-eval-to-jpa-specification|0005]] (the list residual this composes with, kept tag-only) ·
  ADR [[0003-role-definitions-role-not-grant|0003]] (the role-resolved-on-parent backbone generalized to
  the governing root).
- [[POC-ROADMAP]] — the hierarchy slices (Phase 5.5 A/B) · Phase 8 (ReBAC-in-Rego — where per-node
  independent grants and the in-policy team join live).
- [[PARTIAL-EVALUATION-FILTERING]] (the shipped filter Slice B extends) · [[TAG-BASED-AUTHORIZATION]]
  (the per-resource tag grant the leaf decision still uses) · [[USER-STORIES]] (the hierarchy epic).
