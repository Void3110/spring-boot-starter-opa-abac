---
tags:
  - status/planned
  - type/architecture
  - area/abac
  - area/opa
  - area/spring-data
---

# Hierarchical single-resource authorization — design (Slice 5.5-A)

> The design for [[HIERARCHY-SINGLE-RESOURCE]], implementing the single-resource half of ADR
> [[0008-hierarchical-resource-authorization|0008]]. Settled via a focused grill-me session (8 forks).
> The decomposition ([[01-DECOMPOSITION]]) and the autonomous prompt are produced from this by the
> slice-planner skill.

## The shape in one paragraph

A secured resource declares its **immediate parent** as a neutral `(type, id)` (`abacParent()` on
`AbacDataObject`, core, Spring-free). A spring-data **`AncestorResolver`** walks that linkage to produce
the **full ancestor chain** (root-first, leaf excluded), with a **depth bound** and **cycle detection**.
The chain travels to OPA as `input.resource.ancestors`; the **role is resolved once on the governing
root**; the policy decides `direct_leaf_grant OR (inheritable ancestor grant)`, then **deny-overrides**
narrows. A failed walk collapses the *inherited* contribution to nothing but keeps the **direct** grant —
never wider. Inheritance is **opt-in per relation, default-off**.

## 1. Parent linkage (core, Spring-free, additive)

```java
// opa-abac-core — no Spring/JPA imports
public record ParentRef(String type, String id) { /* both non-null */ }

public interface AbacDataObject {
    String abacResourceType();
    String abacResourceId();
    default Map<String, Object> abacAttributes() { return Map.of(); }
    default Optional<ParentRef> abacParent() { return Optional.empty(); }   // NEW — optional, default empty
}
```

`abacParent()` is the **declarative source of truth for one hop**. A non-hierarchical entity says nothing
(default empty); a hierarchical one returns its immediate parent. The default keeps the change **purely
additive** — every existing `AbacDataObject`/secured entity compiles and behaves unchanged.

`AbacContext.Resource` gains an `ancestors` list (omitted when empty, like `role_definition`):

```java
public record Resource(String type, String id, Map<String,Object> attributes, List<ParentRef> ancestors) { … }
// serialized: input.resource.ancestors = [ {type:"catalog",id:"1"}, {type:"category",id:"7"} ]  (root-first, leaf EXCLUDED)
```

## 2. The walk — `AncestorResolver` SPI (spring-data)

```java
public interface AncestorResolver {
    /** Root-first, leaf-excluded chain for the leaf (type,id). Empty when no inheritable lineage.
     *  MUST be fail-closed: a cycle / broken link / depth-bound breach / SQL error throws
     *  AncestorResolutionException (callers treat it as "no inheritance", never "allow"). */
    List<ParentRef> ancestorsOf(String leafType, String leafId);
}
```

Two impls, chosen by the app (ADR 0008 §5):

- **`LtreeAncestorResolver` (default).** Reads the leaf row's denormalized `ltree path` and decodes it into
  the `(type,id)` chain in **one indexed query**. Naturally acyclic if paths are maintained correctly; a
  malformed/`NULL` path → throws (fail-closed). Depth = path length, bounded.
- **`RecursiveCteAncestorResolver`.** No `path` column; a recursive CTE walks live `parent_id`/parent
  linkage up to the root. **Cycle detection** via the CTE `CYCLE` clause (or a visited-set); **depth bound**
  via a depth column / `maxDepth` guard. Correct-by-construction on re-parent (reads live state).

Both honor a configurable **`maxDepth`**; a breach throws (no partial chain returned). `maxDepth` and the
resolver choice are starter config (default ltree, `maxDepth` e.g. 32).

## 3. The opt-in hierarchical entity (spring-data)

```java
@MappedSuperclass
public abstract class AbstractHierarchicalEntity extends AbstractSecuredEntity {
    // ltree 'path' column (e.g. catalog_1.category_7.product_42), GIN-indexed
    // path-maintainer: on insert/update derive path = parent.path || self-label, from abacParent()
    // reparent(newParent): ATOMIC subtree path rewrite in the SAME tx as the parent change
    public abstract Optional<ParentRef> abacParent();   // each hierarchical entity declares its one hop
}
```

- **Non-hierarchical** secured entities keep extending `AbstractSecuredEntity` and pay nothing (no `path`).
- **Re-parent (the invalidation event, ADR 0008 §7).** `reparent(newParent)` updates `parent_id` **and**
  rewrites the `path` of the **entire moved subtree** —
  `UPDATE … SET path = <newPrefix> || subpath(path, <oldDepth>) WHERE path <@ <oldSubtreeRoot>` — **in one
  transaction**, fail-closed if it cannot complete (a concurrent decision must never see a half-rewritten
  tree). The recursive-CTE resolver needs no rewrite (correct-by-construction).
- **Product's missing `catalogId`** is solved by the `path` (it encodes the full lineage `catalog → category
  → product`), not a redundant FK. The CTE resolver instead walks `product → category → catalog` live.

## 4. Input to OPA + the Rego inheritance clause

The single-resource check builds the context, sets `resource.ancestors` from the resolver, resolves the
role **once on the governing root** (the chain's first element), and calls `allow`. OPA `input`:

```json
{ "subject": {…}, "action": "product:read",
  "resource": { "type":"product", "id":"42", "attributes": { …leaf tags… },
                "ancestors": [ {"type":"catalog","id":"1"}, {"type":"category","id":"7"} ] },
  "role_definition": { …resolved on catalog:1… } }
```

Rego (per-type policy) — additive to the shipped `allow`:

```rego
# direct grant (today's path) OR an inheritable ancestor grant satisfies the action; then deny-overrides.
default allow := false

allow if { direct_grant; not denied }
allow if { inherited_grant; not denied }

direct_grant if { verb in role_definition.permissions[input.resource.type] }   # as today

# OPT-IN: only ancestor types declared inheritable for this resource type count.
inherited_grant if {
    some anc in input.resource.ancestors
    inheritable[input.resource.type][anc.type]        # the structural inheritance declaration, in OPA data/config
    verb in role_definition.permissions[anc.type]     # the root-resolved role grants the verb on that ancestor type
}

denied if { … explicit forbid / deny tag on the leaf … }   # deny-overrides — final narrowing
```

The **opt-in** is `inheritable[childType][ancestorType]` (ADR 0008 §3) — a structural declaration
(default-off: absent ⇒ no inheritance). `RoleDefinition` is **unchanged**; inheritability is a property of
the type-relationship, expressed in policy data + the clause.

## 5. Fail-closed semantics (the load-bearing invariant)

```
final_allow = direct_leaf_grant OR (walk_ok AND inherited_grant)        # then AND NOT denied
```

- The Java check calls the resolver; on **any** `AncestorResolutionException` (cycle / broken / too-deep /
  SQL error / null-path), it supplies **no ancestors** → `inherited_grant` is unreachable → the decision
  falls back to **`direct_leaf_grant` only** = exactly today's pre-hierarchy behavior. **Never wider, never
  strips a direct grant.**
- A **mandatory depth bound** and **mandatory cycle detection** live in the resolver; a breach is a throw,
  not a truncated chain (a truncated chain could under- or over-grant).
- Deny-overrides is a **final narrowing AND** — a denied leaf is excluded even when an ancestor would grant.

## 6. Example adoption + e2e

- `CategoryEntity`/`ProductEntity` → `AbstractHierarchicalEntity`; Liquibase adds `ltree path` + GIN index
  + backfills; `abacParent()` returns `("catalog", catalogId)` for a root Category / `("category", parentId)`
  for a nested one / `("category", categoryId)` for a Product.
- Replace `CategoryAuthorizer`/`CategoryListAuthorizer`'s hard-coded hop with the library walk; add a deep
  `GET …/products/{id}` that exercises a 3-level chain; expose a Category **reparent** operation.
- **e2e proves:** (a) a Catalog grant authorizes a Product 3 levels down; (b) an explicit deny on one
  Category carves it out; (c) **a subtree re-parent flips a decision** (move Category 7 under a Catalog the
  subject can't see → the Product becomes denied; atomic path rewrite verified); (d) a broken/too-deep chain
  → **direct grant only**, never wider.

## Considered & rejected (slice-level; the model-level rejects live in ADR 0008)

| Option | Why not here |
|--------|--------------|
| Chain entity-loads (walk by loading each ancestor entity to read its `abacParent()`) | N entity loads per decision; the resolver does it in one query (ltree) or one CTE. `abacParent()` stays the *declaration*; the resolver is the *efficient reader*. |
| Put hierarchy on `AbstractSecuredEntity` for all | Forces a `path` column on non-hierarchical secured entities (Catalog/Team/User). A **separate opt-in base** keeps them free. |
| Resolve a role at every ancestor | N supplier calls + pushes the team graph into the decision = Phase 8 ReBAC. Resolve **once at the root**. |
| Truncate-and-decide on depth breach | A partial chain can mis-grant. Breach **throws** → fall back to direct grant only. |

## Related

- ADR [[0008-hierarchical-resource-authorization|0008]] (the pinned decision) · [[HIERARCHY-SINGLE-RESOURCE]]
  (the index) · [[HIERARCHY-LIST-FILTER]] (Slice 5.5-B, builds on this resolver) · [[DATA-FILTERING]]
  (the shipped filter 5.5-B extends; untouched here) · [[USER-STORIES]] (Epic H).
