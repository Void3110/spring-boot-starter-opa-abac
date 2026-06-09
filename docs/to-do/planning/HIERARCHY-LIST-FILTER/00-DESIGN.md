---
tags:
  - status/planned
  - type/architecture
  - area/abac
  - area/opa
  - area/spring-data
---

# 00 — Design: hierarchy-aware list filter (Slice 5.5-B)

> The design, written from a settled **ADR [[0008-hierarchical-resource-authorization|0008]]** +
> **ADR [[0010-hierarchy-aware-list-filter|0010]]** (which pins every fork below). This slice composes the
> **shipped** [[HIERARCHY-SINGLE-RESOURCE|5.5-A]] resolver with the **shipped** [[DATA-FILTERING|Phase-5]]
> partial-eval list filter so an **ancestor grant widens which rows a list returns** — fail-closed, the
> residual untouched, `opa-abac-core` not touched at all.

## 1. The problem, precisely

A list endpoint today (`CategoryListAuthorizer` → `AbacQueryService.findAuthorized`) cuts rows by the
**row's own JSONB tags** (the Phase-5 partial-eval residual) AND-ed with the caller's path scope. A grant
**inherited from an ancestor** — which 5.5-A made authorize a *single* `GET …/{id}` — does **not** widen the
list. So for the same subject and data, the single-GET and the list **disagree**: a Product the subject can
fetch by id (via a catalog-level inherited grant) is **absent** from the Product list. This slice makes them
agree.

The shipped seam (unchanged behavior shown):

```java
// AbacQueryService.findAuthorized(repo, scope, queryContext) today, line 79-81:
Specification<T> authzSpec = specificationFactory.from(residual);   // tag-only residual
Specification<T> combined  = scope == null ? authzSpec : scope.and(authzSpec);
return repo.findAll(combined);
```

## 2. The core composition (ADR 0010)

"Is this row in an allowed subtree" is a fact about **lineage**, not tags — so the OPA residual stays
**tag-only** and hierarchy widening is a **separate, app-built `subtreeSpec`** OR-ed in:

```
combined = scope.and( tagResidual.or(subtreeSpec) ).and( notDenied )
```

| Term | What it is | Source |
|------|-----------|--------|
| `scope` | the caller's path scoping (`catalogId = ?`, optional `parentId`) — **AND-ed first, never replaced** | the list controller, as today |
| `tagResidual` | the shipped Phase-5 partial-eval residual over the row's tags — **untouched** | `ResidualSpecificationFactory.from(residual)` |
| `subtreeSpec` | "rows in the governing root's subtree" **iff** the root-resolved role inheritably grants the verb, else empty | **new** — `SubtreeSpecResolver` → `AncestorResolver.subtreeOf` |
| `notDenied` | the SQL form of the leaf deny `abac_deny == true`, AND-ed **outside** the OR | **new** — a small Specification |

Three invariants this preserves:

1. **AND, never replace** — `scope.and(...)`, so no row escapes the caller's scope (no cross-catalog leak).
2. **OR widens, AND-outside narrows** — `subtreeSpec` can only *add* subtree rows; `notDenied` can only
   *remove* denied rows, and because it is **outside** the OR it overrides the widening too.
3. **Fail-closed** — a failed subtree resolution → empty (false) `subtreeSpec` → falls back to the
   **narrower** tag-only result; a deny that can't be SQL-expressed never becomes silently `TRUE`.

## 3. Where the subtree roots come from — **root-only** (ADR 0010 §1)

A list has no leaf to walk *up* from; the resolution **inverts**. Given the subject + the list's **governing
root** (the `catalogId` the list scopes to):

- resolve the subject's role **once on that root** — exactly as `CategoryListAuthorizer` /
  `HierarchicalAuthorizer` already do;
- if that role **inheritably** grants the verb (the catalog→category / category→product relation is declared
  inheritable — **opt-in, default-off**), then `subtreeSpec` selects the **whole governing-root subtree**;
- otherwise `subtreeSpec` is **empty**.

So `subtreeSpec` is **binary**: *whole governing-root subtree* or *nothing*. A row is widened-in iff it is in
the governing-root subtree **and** the root-resolved role inheritably grants the verb — **exactly** the
condition `HierarchicalAuthorizer.isAllowed` returns `true` on for any single row in that subtree. The list
and the single-GET agree **by construction**. Mid-tree per-node grants (a grant on an intermediate Category
widening only its sub-subtree) are **Phase 8** — not built here (it would need per-node resolution = ReBAC).

> **Consequence:** root-only needs **no new "which nodes does the subject hold grants on" search** — it
> reuses the root role lookup that already exists. The only genuinely new resolution is turning "yes,
> granted" into the subtree **predicate** (§5).

## 4. The seam — `SubtreeSpecResolver` + an additive 4-arg overload (ADR 0010 §2)

Two new pieces, with the composition owned in one fail-closed place:

```java
// NEW — opa-abac-spring-data. Owns root-only resolution + the inheritable gate + the SPI call.
public class SubtreeSpecResolver {
    // holds AncestorResolver + RoleDefinitionSupplier (+ the inheritance declaration / settings)
    <T extends AbacDataObject> Optional<Specification<T>> subtreeSpec(
            AbacContext.Subject subject, ParentRef governingRoot, String verb);
    // → resolve role on root; if inheritable-grant for verb → Optional.of(ancestorResolver.subtreeOf(root))
    //   else Optional.empty(); any resolution failure → Optional.empty() (fail-closed)
}
```

```java
// AbacQueryService — NEW additive overload; the existing 3-arg method delegates with subtreeSpec = null.
public <T extends AbacDataObject> List<T> findAuthorized(
        JpaSpecificationExecutor<T> repo, Specification<T> scope,
        AbacContext queryContext, Specification<T> subtreeSpec) {
    // ... kill-switch + batch branches (see §6) ...
    Specification<T> authzSpec  = specificationFactory.from(residual);          // tag residual
    Specification<T> widened    = subtreeSpec == null ? authzSpec : Specification.where(authzSpec).or(subtreeSpec);
    Specification<T> notDenied  = notDeniedSpec();                              // §7
    Specification<T> combined   = and(scope, widened, notDenied);              // scope.and(widened).and(notDenied)
    return repo.findAll(combined);
}

// PRESERVED byte-compatible — existing tag-only callers compile + behave identically.
public <T extends AbacDataObject> List<T> findAuthorized(
        JpaSpecificationExecutor<T> repo, Specification<T> scope, AbacContext queryContext) {
    return findAuthorized(repo, scope, queryContext, null);
}
```

- `AbacQueryService` receives a **ready `Specification`** → it gains **no** `AncestorResolver` /
  `RoleDefinitionSupplier` dependency on the composition path; it stays the single owner of the
  AND/OR/deny composition (the fail-closed-sensitive bit, kept in one place).
- The ltree-vs-CTE knowledge stays in `SubtreeSpecResolver` → the SPI (§5), per ADR 0008 §5.
- **Rejected:** a self-contained `HierarchicalQueryService` wrapper (splits the composition across two
  services) and an app-side-only `subtreeSpec` (duplicates library-grade logic in the demo). See ADR 0010.

## 5. The subtree predicate on the SPI — `subtreeOf` (ADR 0010 §4)

`AncestorResolver` gains **one additive method** returning a **`Specification` predicate** (not an id set):

```java
/** A predicate selecting rows in the subtree rooted at (type,id) — the inheritance widening.
 *  Fail-closed: a depth breach / cycle / SQL error → an always-false predicate (no widening). */
<T> Specification<T> subtreeOf(String rootType, String rootId);
```

| Impl | `subtreeOf` returns | Notes |
|------|--------------------|-------|
| **ltree (default)** | `path <@ '<root-label>'` — a pure **SQL pushdown** | the descendant id set is **never materialized** in Java (ltree's whole advantage). Needs the root's `path` label (the root entity / a label lookup). |
| **recursive-CTE** | `id IN (<descendant ids>)` — from a **downward `parent_id` walk** | **bounded by the same `maxDepth`** guard from 5.5-A; **fail-closed to an always-false predicate** on breach/cycle/SQL error. |

Returning a `Specification` (not `descendantIdsOf → Set<id>`) keeps the ltree pushdown intact **and** keeps
the strategy choice **behind the SPI** — the caller gets a predicate, never learns which impl produced it.
`Specification` (a Spring Data type) on the SPI is fine: `AncestorResolver` lives in `opa-abac-spring-data`,
**not** core. **Rejected:** `descendantIdsOf → Set<id>` (forces even ltree to enumerate). See ADR 0010.

## 6. The allowlist-batch path becomes hierarchy-aware (ADR 0010 §5)

`AbacQueryService` has three internal paths. The `subtreeSpec` composition (§4) is the **pure-SQL path**.
The other two:

- **kill-switch** (`partialEval.enabled=false`) → coarse `allow` + scope-only. **Unchanged** — it is the
  pre-Phase-5 degrade; hierarchy doesn't apply (no residual at all). Still fail-closed (deny → empty).
- **allowlist-batch** (`!residual.fullySupported() && allowlistFallback`) → fetch scoped candidates →
  per-row `opaClient.allowAll`. **CHANGED**: `batchFilter`/`withResource` now build each per-row
  `AbacContext` **with the row's ancestor chain** (the 4-arg `Resource(type,id,attributes,ancestors)`), so
  the per-row OPA decision is the **same** `final_allow = (direct OR inherited) AND NOT denied` as the
  single-GET. Therefore:
  - the batch path is **hierarchy-correct and deny-correct natively** — `subtreeSpec` is **not** applied
    there (it would be redundant);
  - resolving each candidate's ancestors is **cheap** — candidates are `AbstractHierarchicalEntity` rows
    whose ltree `path` is already loaded, so the ltree resolver derives ancestors from the in-memory column
    with no extra query;
  - **fail-closed**: a per-row resolution failure → empty ancestors → that row decided on its **direct**
    grant only (never wider); a short/all-false `allowAll` still drops rows.

This is the change that keeps the **pure-SQL path and the batch path consistent** (same subject + data →
same row set regardless of which branch the residual takes). It costs `AbacQueryService` an
`AncestorResolver` dependency **on the batch path** (a constructor change). **Rejected:** keeping the batch
path tag-only — fail-closed but inconsistent (a documented "depends which branch you hit" discrepancy a
`/deep-review` would flag). See ADR 0010.

## 7. Deny-overrides on a list — `notDenied` as SQL (ADR 0010 §3)

The shipped Rego deny is one closed predicate: `denied if input.resource.attributes.abac_deny == true`
(`category.rego`). The list `notDenied` is the **SQL form of that same predicate**:

```
abac_deny IS DISTINCT FROM true        -- over the row's tags JSONB column
-- e.g. jsonb_extract_path_text(tags,'abac_deny') IS DISTINCT FROM 'true'
```

AND-ed **outside** the OR so it narrows **both** the tag-branch and the subtree-branch:

```
combined = scope.and( tagResidual.or(subtreeSpec) ).and( abac_deny IS DISTINCT FROM true )
```

- Deny **cannot** live inside the OR-ed `tagResidual` — the `subtreeSpec` branch would re-admit a denied row
  → deny would stop overriding. It must be the **separate outer AND**.
- **Fail-closed:** a row **absent** the deny tag → `NULL IS DISTINCT FROM true` → `TRUE` → not denied —
  matching Rego's `not denied` on an absent key. Single-GET and list agree on "denied."
- `abac_deny` is a closed scalar boolean tag → always SQL-expressible. A **future non-scalar deny** the SQL
  can't express must **not** be silently `TRUE` (fail-open) — the fail-closed rule is to route such a row
  through the per-row allowlist batch (§6), where full Rego deny applies. **B ships only the scalar form.**
- Reuses the existing `JsonPathDialect` / JSONB Criteria machinery — no new dialect, no new operator.

## 8. The decision, end to end (a worked trace)

`GET /catalogs/{C}/categories` for subject **S**, where the catalog→category relation is **inheritable**:

1. The list controller resolves S's role on `("catalog", C)` and builds the query `AbacContext`
   (resource UNKNOWN — it is the row), with `scope = (catalogId == C)`.
2. `SubtreeSpecResolver.subtreeSpec(S, ("catalog",C), "read")`:
   - role grants `category:read` via the inheritable catalog grant → `subtreeOf("catalog", C)` →
     `path <@ catalog_C` (ltree) **or** `id IN (descendants of C)` (CTE) → `Optional.of(subtreeSpec)`;
   - else → `Optional.empty()` → tag-only behavior.
3. `findAuthorized(repo, scope, ctx, subtreeSpec)`:
   - `tagResidual` = the Phase-5 residual over the row's tags (e.g. `region == emea` for a region-gated role);
   - `combined = (catalogId == C).and( tagResidual.or(path <@ catalog_C) ).and( abac_deny IS DISTINCT FROM true )`.
4. Result: S sees **(rows their tags allow) ∪ (the whole catalog-C subtree)**, **minus** any `abac_deny`
   row, **all within** catalog C. A subject with **no role / no inheritable grant** → `subtreeSpec` empty →
   tag-only rows (or `[]` if no role definition). The **unbound stranger** → `[]`.

This is the **same** outcome `HierarchicalAuthorizer.isAllowed` would give row-by-row — ADR 0005/0006
list↔single-GET consistency, now hierarchy-aware.

## 9. Fail-closed summary (the load-bearing audit)

| Failure / edge | Behavior | Why safe |
|----------------|----------|----------|
| Subtree resolution throws (cycle / too-deep / SQL) | `subtreeOf` → always-false predicate; `SubtreeSpecResolver` → empty | falls back to **narrower** tag-only; never wider |
| No role / no inheritable grant on the root | `subtreeSpec` empty | tag-only (or `[]` if no role def — the Phase-5 `filter` rule has no fallback) |
| Inheritance relation not declared (default-off) | `subtreeSpec` always empty | exactly today's tag-only list |
| Row absent `abac_deny` tag | `NULL IS DISTINCT FROM true` → not denied | matches Rego `not denied` on absent key |
| `abac_deny == true` row inside a widened subtree | excluded by the outer `.and(notDenied)` | deny overrides the inherited widening |
| Residual `not fullySupported` (batch path) | per-row `allowAll` with ancestors; short/all-false drops rows | same Rego `final_allow`; only removes rows |
| Caller scope vs widening (foreign catalog) | `scope.and(...)` AND-ed first | widening can't escape scope — no cross-catalog leak |

## 10. Considered & rejected (summary; full rationale in ADR 0010)

- **Mid-tree per-node subtree roots** → Phase 8 (ReBAC); root-only reuses the existing root lookup.
- **`HierarchicalQueryService` wrapper** → splits the fail-closed composition; the overload keeps it in one place.
- **App-side-only `subtreeSpec`** → duplicates library-grade logic in the demo.
- **Deny folded into the tag residual** → the OR re-admits denied rows; deny must be a separate outer AND.
- **Defer deny-on-lists entirely** → loses the headline "widen-yet-deny-still-removes" e2e proof.
- **`descendantIdsOf → Set<id>`** → discards the ltree pushdown; `subtreeOf → Specification` keeps it.
- **Teach the residual an `ltree` operator** → already rejected by ADR 0008 (couples the hardened operator set).
- **Keep the batch path tag-only** → fail-closed but inconsistent with the SQL path.
- **Split B into two slices** → the widening + the deny narrowing are one atomic correctness unit.

## 11. Scope boundary

| In B | Out (later) |
|------|-------------|
| Root-only subtree widening on lists, fail-closed | Mid-tree per-node grants (Phase 8 / ReBAC) |
| `subtreeOf` SPI (ltree pushdown + CTE bounded walk) | Any change to the tag-only residual / operator set |
| `notDenied` as the scalar `abac_deny` SQL mirror | Non-scalar / richer deny models (future slice) |
| Hierarchy-aware allowlist batch path | Per-ancestor role resolution / independent ancestor teams |
| 4-arg overload (3-arg byte-compatible) | Touching `opa-abac-core` (not touched at all) |

## Related

- ADR [[0010-hierarchy-aware-list-filter|0010]] (every fork above) · ADR
  [[0008-hierarchical-resource-authorization|0008]] (the hierarchy model) ·
  ADR [[0005-partial-eval-to-jpa-specification|0005]] (the residual kept tag-only) ·
  [[0006-three-layer-enforcement-model|0006]] (layer 3 completed).
- [[HIERARCHY-SINGLE-RESOURCE]] (the resolver this composes) · [[DATA-FILTERING]] /
  [[PARTIAL-EVALUATION-FILTERING]] (the filter this extends) · [[HIERARCHICAL-AUTHORIZATION]] (the
  single-resource decision mirrored for lists).
- [[HIERARCHY-LIST-FILTER]] (the slice index) · [[POC-ROADMAP]] (Phase 5.5-B) · [[USER-STORIES]] (story H5).
