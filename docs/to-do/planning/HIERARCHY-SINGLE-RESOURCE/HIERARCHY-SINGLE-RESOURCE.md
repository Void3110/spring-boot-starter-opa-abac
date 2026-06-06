---
tags:
  - status/planned
  - type/index
  - area/abac
  - area/opa
  - area/spring-data
---

# Hierarchical authorization — single-resource (Slice 5.5-A)

> 📋 **PLANNED.** The first of two slices for [[POC-ROADMAP]] **Phase 5.5**, pinned by ADR
> [[0008-hierarchical-resource-authorization|0008]]. This slice generalizes the one-step parent hop (a
> Category's role resolved on its governing Catalog) into a **full N-level ancestor-chain walk** for
> **single-resource** checks: a grant on a Catalog governs a Category and a Product nested under it, N
> levels deep — **opt-in per relation, deny-overridable, fail-closed**. The **hierarchy-aware list
> filter** is the sibling slice **5.5-B** ([[HIERARCHY-LIST-FILTER]], not yet scaffolded), which builds on
> this slice's resolver.

This package mirrors the five shipped slices ([[DOMAIN-MODEL-FOUNDATION]], [[LIBRARY-SPINE]],
[[USER-MANAGEMENT-SERVICE]], [[TAG-DICTIONARY]], [[DATA-FILTERING]]) 1:1 in structure. The design half
(this index + [[00-DESIGN]]) is written from a settled [[0008-hierarchical-resource-authorization|ADR
0008]]; the decomposition half (`01-DECOMPOSITION` + `10-QA-TEST-CASES` + the autonomous prompt + STATUS
stubs) is produced by the **slice-planner** skill.

## Why this slice

Today a deep path like `catalog/{id}/category/{id}/product/{id}` is authorized **leaf-only or one-step**:
`@OpaPreAuthorize` resolves the role on the *leaf type itself*, and only two hand-written authorizers
(`CategoryAuthorizer` / `CategoryListAuthorizer`) do a single hard-coded hop to `("catalog", catalogId)`.
A Product has **no** parent-governed path at all. So the levels between root and leaf are never
considered. For a **general library** whose users' trees have per-node governance, that's the wrong
default. This slice makes a grant on **any ancestor** able to satisfy a check on a descendant, across the
**whole** chain — the general form of what the [[TAG-DICTIONARY]] demo stubbed and [[DATA-FILTERING]]
deferred.

> **The differentiator.** After partial-eval data filtering, **N-level hierarchical authorization** is the
> piece almost nobody ships cleanly in a Spring-native OPA integration. This slice is the headline; 5.5-B
> extends it to lists.

## What this slice delivers

In the library:

- **`opa-abac-core`** (Spring-free, additive) — `AbacDataObject` gains an optional default
  `abacParent() → Optional<ParentRef>` (`ParentRef` = a neutral `(type, id)` record); `AbacContext.Resource`
  gains an `ancestors` list serialized as `input.resource.ancestors`. No SQL, no Spring.
- **`opa-abac-spring-data`** — an **`AncestorResolver` SPI** (given a leaf, produce the root-first,
  leaf-excluded ancestor chain, with **cycle detection** + a **depth bound**) and **two impls**: an
  **`ltree` materialized-path** resolver (default) and a **recursive-CTE** resolver. Plus an opt-in
  **`AbstractHierarchicalEntity extends AbstractSecuredEntity`** that adds the `ltree path` column, the
  `abacParent()` scaffolding, a **path-maintainer** (derive `path` from the parent on insert/update), and an
  **atomic `reparent()`**. The single-resource hierarchical check:
  `final_allow = direct_leaf_grant OR (walk_ok AND inherited_grant)`, deny-overrides as a final narrowing AND.
- **`opa-abac-spring-boot-starter`** — wire the resolver SPI (default ltree), the **structural inheritance
  declaration** (which type inherits from which ancestor type; **default-off**), and `maxDepth`/cycle config,
  all conditional + overridable.

In the example + infra:

- **catalog** — `CategoryEntity` and `ProductEntity` extend `AbstractHierarchicalEntity`; a Liquibase
  migration adds the `ltree path` column + GIN index + backfills existing rows; the two hard-coded
  authorizers are replaced by the library walk; a deep `product`-by-id endpoint exercises the chain. A
  **re-parent** operation (move a Category subtree) is exposed for the e2e.
- **`category.rego` / `product.rego`** — an **inheritance clause** ("an ancestor's granted verb on its type
  satisfies a descendant's action") reading `input.resource.ancestors`, plus deny-overrides; `opa test`.
- an **e2e** proving: a grant on a Catalog authorizes a Product three levels down; an explicit deny on one
  Category carves it out (deny-overrides); a **subtree re-parent flips a decision**; a broken/too-deep chain
  yields the **direct grant only** (never wider).

## What this slice does NOT do (held for 5.5-B / later)

- **Hierarchy-aware list filtering** — an ancestor grant widening which rows `GET …/products` returns is
  **Slice 5.5-B** ([[HIERARCHY-LIST-FILTER]]); it composes this slice's resolver with the shipped
  [[DATA-FILTERING]] residual via an app-built `subtreeSpec`. This slice is **single-resource only**.
- **Per-node independent grants** (a mid-tree Category with its *own* team, resolved independently of the
  root) — that pushes the team graph into the decision and is **Phase 8** (ReBAC-in-Rego). Here the role is
  resolved **once on the governing root**.
- Any change to the shipped partial-eval **residual model / operator set** — untouched (the residual stays
  tag-only; this slice adds no residual machinery).

## File glossary

| File | Role |
|------|------|
| `HIERARCHY-SINGLE-RESOURCE.md` | This index — what the slice delivers, the glossary, the ticket status table, conventions. |
| `00-DESIGN.md` | The design: `ParentRef`/`abacParent()`, `input.resource.ancestors`, the `AncestorResolver` SPI + ltree/CTE impls, `AbstractHierarchicalEntity` + atomic re-parent, the Rego inheritance clause + deny-overrides, the fail-closed walk, considered-&-rejected. |
| `01-DECOMPOSITION.md` | The ordered tickets (Goal / Deliverables / Acceptance / What-NOT-to-touch) + the critical path. **The work list.** *(produced by slice-planner)* |
| `AUTONOMOUS-IMPLEMENTATION-PROMPT.md` | The self-contained prompt. *(produced by slice-planner)* |
| `10-QA-TEST-CASES.md` | Concrete unit / integration / e2e cases. *(produced by slice-planner)* |
| `STATUS-0N.md` | One per ticket, filled at each checkpoint during the autonomous run. *(produced by slice-planner)* |

## Ticket status

| # | Ticket | Module | Status |
|---|--------|--------|--------|
| T1 | Core: `ParentRef` + `abacParent()` + `Resource.ancestors` (Spring-free, additive) | core | ✅ |
| T2 | `AncestorResolver` SPI + ltree & recursive-CTE impls (cycle + depth, fail-closed) | spring-data | 📋 |
| T3 | `AbstractHierarchicalEntity` (ltree path + maintainer + atomic re-parent) | spring-data | 📋 |
| T4 | Single-resource hierarchical check (`direct OR (walk_ok AND inherited)`) | spring-data | 📋 |
| T5 | Starter wiring: resolver SPI + inheritance config (default-off) + `maxDepth` | starter | 📋 |
| T6 | Example adoption + rego inheritance clause + Liquibase ltree migration | example + infra | 📋 |
| T7 | e2e (incl. the mandatory re-parent test) + docs + roadmap/Mulch | e2e + docs | 📋 |

**Critical path:** T1 → T2 → T3 → T4 → T5 → T6 → T7. T1 is independently landable (pure core). T1+T2+T3
land the reusable library core (parent model + resolver SPI + both impls + hierarchical entity) before the
example adoption (T6) and e2e (T7).

## Conventions (same as every prior slice)

- **Clean-room IP boundary.** Original neutral names only; the prior platform is **study-only**. Never copy
  proprietary source/names/paths.
- **`opa-abac-core` stays Spring-free** — `ParentRef` + `abacParent()` + the `ancestors` field are plain
  Java; the walk + ltree live in `opa-abac-spring-data`.
- **Fail-closed everywhere** — a failed/cyclic/too-deep walk collapses the *inherited* contribution but
  preserves the direct leaf grant (`direct OR (walk_ok AND inherited)`), degrading to today's pre-hierarchy
  decision; never wider. Inheritance is **opt-in, default-off**.
- **Additive where possible** — `abacParent()` is a default; `ancestors` is omitted when empty;
  `AbstractHierarchicalEntity` is opt-in (non-hierarchical secured entities are untouched). `RoleDefinition`
  is **unchanged**.
- **Commit identity** `Void3110 <void31102025@gmail.com>`; **one focused commit per ticket**; **do not
  push**. Mulch sync commits touch `.mulch/` only.

## Related

- ADR [[0008-hierarchical-resource-authorization|0008]] — the pinned decision this slice implements.
- [[POC-ROADMAP]] — Phase 5.5 (this slice + 5.5-B); Phase 8 (ReBAC, where per-node grants live).
- [[DATA-FILTERING]] — the shipped list filter that **5.5-B** extends (this slice leaves it untouched).
- [[USER-STORIES]] — Epic H (the hierarchy stories; H1–H4/H6 are this slice, H5 is 5.5-B).
- ADR [[0006-three-layer-enforcement-model|0006]] — this deepens layer 2 (app, per-resource) to N levels.
