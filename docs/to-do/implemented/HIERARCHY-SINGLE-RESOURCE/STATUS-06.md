---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/catalog
---

# STATUS T6 — example + infra: catalog adoption + rego inheritance clause + Liquibase ltree migration

> Filled in at the T6 checkpoint during the autonomous run. One commit per ticket.

## What shipped

The catalog app + policies are hierarchical end-to-end:

- **Entities** — `CatalogEntity` (a root), `CategoryEntity`, `ProductEntity` now extend
  `AbstractHierarchicalEntity` with `abacParent()`: a Catalog has no parent; a Category → its parent
  Category (nested) or its Catalog (root); a Product → its Category. The Product's missing `catalogId` is
  solved by the `ltree` path encoding the full lineage.
- **Liquibase `0003-add-ltree-hierarchy-path.yaml`** — `CREATE EXTENSION ltree` + a `path ltree` column on
  all three tables + **GiST** indexes (ltree's `<@` operator needs GiST, not GIN) + a **backfill** computing
  each row's lineage (a recursive CTE for the nested categories). `ddl-auto: validate` boots clean.
- **`HierarchyConfig`** — the app's `LtreePathSource` bean (reads `path::text` from the type's table),
  which makes the starter wire the default `LtreeAncestorResolver` + `HierarchicalAuthorizer`; plus the
  `HierarchicalPathMaintainer` bean. `application.yml` sets `opa.abac.hierarchy.enabled=true`,
  `resolver=ltree`, and the `inheritable` declaration.
- **`CatalogHierarchyService`** — assigns a row's path on create (wired into all three create flows) and the
  **atomic re-parent** of a Category subtree (rewrite paths + adjacency in one tx). Because the catalog
  hierarchy spans **two tables**, the re-parent rewrites the `category` subtree **and** its descendant
  `product` rows (see the library addition below).
- **`CategoryAuthorizer`** now delegates to the library `HierarchicalAuthorizer` (the full N-level walk),
  replacing the previous single hard-coded `("catalog", catalogId)` hop.
- **`category.rego` / `product.rego`** — restructured to `final_allow = (direct OR inherited OR fallback)
  AND NOT denied`. The `inherited_grant` reads `input.resource.ancestors`, gated by opt-in
  `data.<pkg>.inheritable[leaf][ancestor]` (default-off); `denied` is `attributes.abac_deny == true`. The
  tag match, the subject-roles fallback, and the `filter`/`bulk` entrypoints are unchanged. `inheritable`
  OPA data ships as `category_inheritable.json` / `product_inheritable.json` under `/policies`.

### Library addition (additive, surfaced by the example)

`HierarchicalPathMaintainer.reparentDescendantsInTable(table, oldSelfPath, newParent)` — rewrite a moved
subtree's descendant rows that live in a **different table** (a hierarchy spanning multiple tables: a
Category subtree whose leaves are Products). The single-table `reparent` signature is **unchanged**; the
internal path math was extracted into `computeNewSelfPath`/`rewriteSubtree` (behavior-preserving).

## Tests

- **`opa test infra/opa/policies/` — 72/72** (60 baseline + 12 new): direct grant; inherited grant via an
  ancestor; **opt-in off ⇒ deny** (explicit `with data.<pkg>.inheritable as {}` to model "not declared",
  since the bundled JSON now declares it); deny-overrides beats an ancestor grant; no-ancestors ⇒
  direct-only; the inherited path still respects the **leaf tag** requirement (category). `opa check` clean,
  `opa fmt` applied. An `opa eval` probe confirms a Catalog grant satisfies a deep Product action.
- **`./gradlew build` green** — incl. the example app boot with **`ddl-auto: validate`** (the entity↔ltree
  migration agreement is the proof) and all existing ITs unchanged-green.
- **`HierarchyAdoptionIT`** (Testcontainers real Postgres + the real Liquibase ltree migration): insert
  derives the path and the wired resolver decodes the full `catalog → category → product` chain;
  **re-parenting a Category under a sibling flips the resolved lineage** for its descendant Product (the
  cross-table rewrite), with the moved Category's own path verified under the new parent.

## Architecture review + refactor (the ★ gate)

- **Single-resource only:** `CategoryListAuthorizer` + `AbacQueryService` + the residual machinery are NOT
  in the diff — the list filter stays Phase-5 tag-only (5.5-B scope preserved).
- **Refactor / fix applied (real, not churn):** the example IT surfaced that a single-table subtree UPDATE
  doesn't reach descendants in another table — re-parenting a Category left its Products pointing at the old
  lineage. Fixed by the additive `reparentDescendantsInTable`; the `reparent` signature is unchanged and the
  T3 library ITs stay green. Also fixed `HierarchyConfig`'s path lookup to use `getResultList()` (an eager
  read) — `getResultStream()` raised "ResultSet is closed" across the re-parent's cleared context.
- **opt-in default-off is honored in policy:** the bundled `inheritable` JSON makes the demo inherit, but
  the policy still denies when the relation is absent (proven by the opt-in-off tests).

## Integration / e2e

The full library+app path runs against **real Postgres + the real ltree migration** in `HierarchyAdoptionIT`
(above). The through-the-gateway allow/deny + re-parent-flips-a-decision proof is the newman e2e (T7).

## Decisions

- **The catalog hierarchy spans two tables**, so a Category re-parent must rewrite the `category` subtree
  AND the descendant `product` rows. This is a general need, so the cross-table rewrite is a **library**
  method (`reparentDescendantsInTable`), not app-only glue.
- **`inheritable` ships as OPA data files** under `/policies` (loaded with the policies) and is mirrored in
  `application.yml` for the app's reference; the rego reads `data.<pkg>.inheritable`.
- **GiST, not GIN, for ltree** — the `<@` containment operator is GiST-indexed.

## Commit

`feat(example,opa): adopt N-level hierarchy — ltree migration + inheritance clause + cross-table re-parent`
— see the T6 commit on `feature/void3110/hierarchy-single-resource`.
