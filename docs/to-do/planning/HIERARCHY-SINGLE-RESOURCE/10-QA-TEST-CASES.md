---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/opa
  - area/spring-data
---

# Hierarchical single-resource — QA test cases (Slice 5.5-A)

> Concrete unit (U) / integration (I) / policy (P) / e2e (E) cases the implementation must satisfy. Each
> ticket's *Acceptance* in [[01-DECOMPOSITION]] references these. ITs run against **real Postgres via
> Testcontainers** (never H2); policies use `opa test` / `opa eval`. The load-bearing theme is
> **fail-closed**: enumerate every error/missing path and assert it lands on *direct-grant-only*, never wider.

## Unit (core / seam)

- **U1** `ParentRef` rejects a null `type` and a null `id` (compact-ctor).
- **U2** `AbacContext` **with** ancestors serializes `input.resource.ancestors` as the ordered, root-first,
  leaf-excluded `[{"type":…,"id":…}, …]` JSON.
- **U3** `AbacContext` with **no** ancestors serializes **byte-for-byte as before** — no `ancestors` key
  (back-compat).
- **U4** `abacParent()` defaults to `Optional.empty()` on a plain `AbacDataObject`; the back-compat
  `Resource(type,id,attributes)` ctor yields empty ancestors.
- **U5** The `HierarchicalAuthorizer` seam resolves the role on the **root** (chain's first element), not
  the leaf — `ArgumentCaptor` on `RoleDefinitionSupplier.lookup` asserts `(rootType, rootId)`.
- **U6** The seam builds the `AbacContext` with `resource.ancestors` set from the resolver and the leaf's
  tags as `resource.attributes` — `ArgumentCaptor` on `OpaClient.allow`.

## Integration — resolver (Testcontainers Postgres)

- **I1** Both `LtreeAncestorResolver` and `RecursiveCteAncestorResolver` return the **same** root-first,
  leaf-excluded chain for a seeded 3-level tree (catalog → category → product).
- **I2** A seeded **cycle** (A→B→A) → `AncestorResolutionException` for **both** impls (CTE `CYCLE` /
  visited-set; ltree malformed) — never an infinite loop, never a partial chain.
- **I3** A tree **deeper than `maxDepth`** → `AncestorResolutionException` (both impls); not a truncated
  chain.
- **I4** A **broken** parent link (a parent id that resolves to nothing) / a `NULL`/malformed ltree `path`
  → `AncestorResolutionException`.
- **I5** Leaf-exclusion + ordering pinned: the returned list is root-first and does **not** contain the
  leaf itself.

## Integration — hierarchical entity / re-parent (Testcontainers Postgres)

- **I6** Inserting a child derives the correct `ltree path` from its parent (`parent.path || self-label`).
- **I7** **Re-parenting a subtree rewrites every descendant's `path` atomically**: after
  `reparent(newParent)`, every moved descendant's `path` reflects the new lineage, and the
  `LtreeAncestorResolver` returns the **new** chain.
- **I8** **Atomicity:** a forced mid-rewrite failure (e.g. a constraint violation injected partway) leaves
  the tree **unchanged** — no half-rewritten paths (the rewrite shares the `parent_id` change's transaction).
- **I9** A non-hierarchical secured entity (still `AbstractSecuredEntity`) is unaffected — no `path` column,
  unchanged behavior (a regression guard).

## Integration — the single-resource check (stubbed OPA + supplier)

- **I10** A **Catalog grant authorizes a Product 3 levels down**: ancestors carried, role resolved on the
  root, `allow` → true.
- **I11** A resolver **failure** (throws) → the decision uses the **direct** leaf grant only
  (`direct OR (walk_ok AND inherited)`) — never wider; if there's no direct grant, deny.
- **I12** An **unresolved role** (supplier empty) → deny (fail-closed).
- **I13** **Opt-in off** (no `inheritable` config for the type) → no ancestors influence the decision →
  direct-only.

## Policy (`opa test` / `opa eval`)

- **P1** Direct grant: `verb in permissions[resource.type]` → allow (today's path, unchanged).
- **P2** Inherited grant: an ancestor in `input.resource.ancestors` whose type is `inheritable` for the
  leaf type, and the root role grants the verb on that ancestor type → allow.
- **P3** **Opt-in gate:** the same ancestor grant with **no** `inheritable[leaf][ancestor]` entry → **deny**
  (default-off).
- **P4** **Deny-overrides:** an explicit leaf deny (forbid clause / deny tag) → deny, **even when** an
  ancestor would grant.
- **P5** **No ancestors** in input → behaves exactly as the pre-hierarchy `allow` (direct-only).
- **P6** Malformed/absent `ancestors` → no inherited grant (never errors into allow).

## E2E (through the gateway, newman)

- **E1** A subject with a **Catalog** grant reads a **Product 3 levels down** (`catalog/{id}/category/{id}/
  product/{id}`) → 200.
- **E2** An explicit **deny** on one Category carves it out → 403 on that Category's product, while a sibling
  Category's product stays 200 (deny-overrides).
- **E3** **Re-parent flips a decision:** move Category 7 under a Catalog the subject can't see → the Product
  under it becomes **denied** (403); verify the ltree `path` was rewritten.
- **E4** A subject with a **direct** Product grant but a **broken/too-deep** ancestor chain still gets the
  **direct** access (200) — a failed walk never strips the direct grant, never widens beyond it.
- **E5** A subject with **no** grant anywhere on the chain → 403 (fail-closed; not a leak, not a 500).

## Fail-closed checklist (the review-gate eyeball)

Every one of these must land on **direct-grant-only or deny**, never a wider result:
compile/transport/parse error · resolver exception · cycle · depth-bound breach · broken link · `NULL`
path · opt-in off · unresolved role · no subject · malformed `ancestors`. The slice's headline proof is
**E1 + E3** (a deep grant works; a re-parent flips it) and **I8** (the re-parent is atomic).
