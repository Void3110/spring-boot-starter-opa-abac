---
tags:
  - status/active
  - type/guide
  - area/abac
  - area/opa
  - area/spring-data
---

# Hierarchical authorization — N-level ancestor inheritance

> How a grant on a **Catalog** governs a **Category** and a **Product** nested under it, N levels deep —
> **opt-in per relation, deny-overridable, fail-closed**. This is the single-resource half (Slice 5.5-A) of
> ADR [[0008-hierarchical-resource-authorization|0008]]. The hierarchy-aware **list** filter is the sibling
> Slice 5.5-B.

## The idea in one paragraph

A secured resource declares its **immediate parent** as a neutral `(type, id)` (`abacParent()` on
`AbacResource`, core, Spring-free). An `AncestorResolver` walks that linkage into the **full ancestor
chain** (root-first, leaf-excluded), with a **depth bound** and **cycle detection**. The chain travels to
OPA as `input.resource.ancestors`; the **role is resolved once on the governing root**; the policy decides
`direct_leaf_grant OR (inheritable ancestor grant)`, then **deny-overrides** narrows. A failed walk
collapses the *inherited* contribution to nothing but keeps the **direct** grant — never wider, never
strips a direct grant. Inheritance is **opt-in per relation, default-off**.

## The pieces

| Layer | What |
|-------|------|
| **`opa-abac-core`** (Spring-free) | `ParentRef(type,id)`; `AbacResource.abacParent() → Optional<ParentRef>` (default empty); `AbacContext.Resource.ancestors` serialized as `input.resource.ancestors` (root-first, leaf-excluded, omitted when empty). |
| **`opa-abac-spring-data`** | The `AncestorResolver` SPI (`ancestorsOf(leafType, leafId)`) + two impls — `LtreeAncestorResolver` (default, decodes a denormalized `ltree` path in one indexed read) and `RecursiveCteAncestorResolver` (walks live `parent_id` adjacency). The opt-in `AbstractHierarchicalEntity` (ltree `path` column + path-maintainer + **atomic `reparent`**). The `HierarchicalAuthorizer` seam tying resolver → context → OPA. |
| **`opa-abac-spring-boot-starter`** | Auto-config (`opa.abac.hierarchy.*`): `enabled` (default **false**), `resolver` (`ltree`/`cte`), `maxDepth`, the `inheritable` map. Wires the resolver once the app supplies a data-access source bean. |
| **The policy (Rego)** | The `inherited_grant` clause + `deny-overrides`, gated by opt-in `data.<pkg>.inheritable[leaf][ancestor]`. |

## Fail-closed (the load-bearing invariant)

```
final_allow = direct_leaf_grant OR (walk_ok AND inherited_grant)        # then AND NOT denied
```

The resolver **throws** `AncestorResolutionException` on any breach — a cycle, a broken link, a depth-bound
breach, a `NULL`/malformed path, or a SQL error — **never** a truncated chain (a truncation could mis-grant).
The `HierarchicalAuthorizer` treats a throw as "no ancestors," so the decision can only come from the
**direct** leaf grant — degrading to exactly the pre-hierarchy result. A failed walk never widens and never
strips a direct grant. An unresolved role / no subject → deny.

## Opt-in, default-off

Inheritance is off until two things are true:

1. `opa.abac.hierarchy.enabled=true` (so the library wires the resolver + authorizer), and
2. the relation is declared **inheritable** in OPA data: `data.<type>.inheritable[<leaf type>][<ancestor type>]`.

```yaml
# application.yml — the structural declaration, mirrored into OPA data
opa:
  abac:
    hierarchy:
      enabled: true
      resolver: ltree        # ltree (default) | cte
      max-depth: 32
      inheritable:
        category: [catalog]
        product: [category, catalog]
```

`RoleDefinition` is **unchanged** — inheritability is a property of the *type-relationship*, not of a role's
grant.

## The Rego clause

```rego
# direct grant (today's path) OR an inheritable ancestor grant; then deny-overrides narrows.
allow if { granted; not denied }

granted if { direct_grant }
granted if { inherited_grant }

direct_grant if { verb in permissions.effective_actions(input.role_definition, input.resource.type) }

# OPT-IN: only ancestor types declared inheritable for this leaf type count. The role is root-resolved,
# so role_definition.permissions is keyed by the ANCESTOR type.
inherited_grant if {
    some ancestor in input.resource.ancestors
    data.<pkg>.inheritable[input.resource.type][ancestor.type]
    verb in permissions.effective_actions(input.role_definition, ancestor.type)
}

# deny-overrides: an explicit leaf deny wins over any grant.
denied if { input.resource.attributes.abac_deny == true }
```

> **`opa test` gotcha.** Bundling the `inheritable` data as JSON under the policies dir makes inheritance
> always-on for tests, so the *opt-in-off* cases must explicitly override `with data.<pkg>.inheritable as {}`.

## The resolver SPI — ltree vs CTE

Both produce the same root-first, leaf-excluded chain; the app picks one by `hierarchy.resolver` and supplies
the matching **data-access source** (the library can't know the app's tables):

- **`LtreeAncestorResolver` (default)** — reads a denormalized `ltree path` (`catalog_<hex>.category_<hex>.…`)
  in **one indexed query** and decodes it. Needs a `LtreePathSource` bean (`pathOf(type,id) → Optional<String>`).
  Naturally acyclic; a malformed/`NULL` path or over-`maxDepth` depth throws. Best for read-heavy trees.
- **`RecursiveCteAncestorResolver`** — walks the live `parent_id` linkage hop-by-hop, **correct-by-construction
  on re-parent** (no denormalized state). Needs a `ParentLinkSource` bean (`parentOf(type,id) → Optional<ParentRef>`).
  Visited-set cycle detection + a depth counter. Best for re-parent-heavy trees.

## The `ltree` path + re-parent

`AbstractHierarchicalEntity` adds an `ltree path` column (mapped as a `String` with `@ColumnTransformer(write="?::ltree")`)
encoding the full lineage as `<type>_<dash-free-hex-uuid>` labels (ltree labels permit only `[A-Za-z0-9_]`,
so a UUID's hyphens are stripped; `HierarchyLabels` is the shared encode/decode). The `HierarchicalPathMaintainer`:

- **`assignPath(entity)`** — derives `path = parent.path || self-label` on create (a missing parent path is a
  broken lineage → throw).
- **`reparent(table, oldSelfPath, newParent)`** — **atomic** subtree rewrite in one `ltree` UPDATE
  (`SET path = CASE WHEN nlevel(path)=oldDepth THEN newSelfPath ELSE newSelfPath || subpath(path, oldDepth) END
  WHERE path <@ oldSelfPath`). The `CASE` is required because `subpath(path, nlevel(path))` is an invalid ltree
  position for the moved node's own row. A re-parent under one's own descendant is rejected (cycle).
- **`reparentDescendantsInTable(table, …)`** — for a hierarchy spanning **multiple tables** (a Category subtree
  whose leaves are Products in another table), rewrite the descendants there too, in the same transaction.

> Use a **GiST** index on the `path` column — ltree's `<@` containment operator is GiST-indexed (GIN is for the
> JSONB tags). Run `CREATE EXTENSION ltree` before the column; backfill nested paths with a recursive CTE.

## Adoption recipe (the catalog example)

1. Extend `AbstractHierarchicalEntity` and implement `abacParent()` (Catalog → empty; Category → its parent
   Category or its Catalog; Product → its Category).
2. Add the Liquibase ltree migration (extension + `path` column + GiST index + backfill).
3. Provide a `LtreePathSource` bean (reads `path::text` from the type's table) — the starter wires the resolver
   + `HierarchicalAuthorizer`. Provide the `HierarchicalPathMaintainer` (the starter exposes the seam).
4. Assign the path on create; expose re-parent via a service (rewrite the subtree + descendants in one tx).
5. Replace any hard-coded one-step parent hop with the `HierarchicalAuthorizer` walk.
6. Add the `inherited_grant` + `deny-overrides` Rego clause and ship the `inheritable` OPA data.

> **Gate vs programmatic (since Phase 5.97).** With an `AbacResourceResolver` registered
> ([[ATTRIBUTE-RICH-PRE-AUTHORIZATION]]), an id'd `@OpaPreAuthorize` makes this same decision **at the
> gate**: the starter binds the gate's ancestor source to the 5.5 `AncestorResolver`, the role is
> looked up once on the governing root (this guide's rule, verbatim), and inherited grants pass
> declaratively — the catalog example's per-instance handler check was deleted on that flip.
> `HierarchicalAuthorizer` remains the **programmatic** seam for non-annotation flows (services,
> batch jobs, tests).

## The list analogue (Slice 5.5-B)

The decision above is **single-resource** (`GET …/{id}`). Its **list** counterpart — an ancestor grant
widening which rows `GET …/categories` returns — ships in **Slice 5.5-B**, which composes this resolver with
the Phase-5 partial-eval residual:

- the OPA residual stays **tag-only**; hierarchy widening is a separate app-built **`subtreeSpec`** OR-ed into
  the query, from a new additive `AncestorResolver.subtreeOf(rootType, rootId) → Specification` (ltree
  `path <@` pushdown / CTE bounded `id IN`, both fail-closed);
- a `SubtreeSpecResolver` applies the **same root-only inheritable gate** as this seam — resolve the role once
  on the governing root, check it inheritably grants the verb — so **the widened list and a single-GET decide
  the same rows by construction**;
- the leaf deny is mirrored as SQL (`abac_deny IS DISTINCT FROM true`) AND-ed outside the widening OR, inside
  the caller's scope (no cross-catalog leak); the allowlist-batch path carries each row's ancestor chain;
- a small additive `allow` list clause lets an inheritable-ancestor-grant subject pass the **coarse**
  type-level list gate (the fine which-rows cut stays in SQL).

See [[PARTIAL-EVALUATION-FILTERING]] (the hierarchy-aware list section) and ADR
[[0010-hierarchy-aware-list-filter|0010]]. Proven by `HierarchyListFilterIT` (real Postgres) and
`scripts/postman/run-hierarchy-list-matrix.sh`.

## What this does NOT do

- **Per-node independent grants** — a mid-tree Category with its *own* team is **Phase 8** (ReBAC). Here the
  role is resolved **once on the governing root**.

## Related

- ADR [[0008-hierarchical-resource-authorization|0008]] (the pinned decision) · ADR
  [[0010-hierarchy-aware-list-filter|0010]] (the list analogue, Slice 5.5-B) · ADR
  [[0006-three-layer-enforcement-model|0006]] (the layers this deepens) · [[ABAC-AUTHORIZATION]] (the spine) ·
  [[TAG-BASED-AUTHORIZATION]] (the leaf tag grant the decision still uses) ·
  [[PARTIAL-EVALUATION-FILTERING]] (the list filter it composes with) · [[E2E-TESTING]] (the
  `run-hierarchy-matrix.sh` + `run-hierarchy-list-matrix.sh` matrices).
