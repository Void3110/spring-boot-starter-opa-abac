---
tags:
  - status/done
  - type/project
  - area/abac
  - area/spring-data
---

# STATUS T3 — `AbstractHierarchicalEntity` (ltree path + maintainer + atomic re-parent)

> Filled in at the T3 checkpoint during the autonomous run. One commit per ticket.

## What shipped

The opt-in persistent base + the path-maintenance mechanism, so the ltree resolver has real lineage:

- **`AbstractHierarchicalEntity extends AbstractSecuredEntity`** (`@MappedSuperclass`) — adds the
  denormalized `ltree path` column (`@Column(columnDefinition="ltree")` + `@ColumnTransformer(write="?::ltree")`
  so a bound `String` lands in the `ltree` column), re-declares `abstract Optional<ParentRef> abacParent()`,
  and exposes `getPath()/setPath()` + `selfLabel()` (`HierarchyLabels.label(type,id)`). **Opt-in, zero-cost
  otherwise**: a non-hierarchical secured entity keeps extending `AbstractSecuredEntity` and gets no `path`
  column. A Product's missing `catalogId` is solved by the path (full lineage), not a redundant FK.
- **`HierarchicalPathMaintainer`** (the write-centralized mechanism, off the entity — same posture as
  `AbstractCrudService.mutate`):
  - `assignPath(entity)` — derives `path = parentPath || self-label` from `abacParent()` (root → just the
    self-label); a declared-but-missing parent path is a **broken lineage → throw** (fail-closed), never a
    silent root path.
  - `reparent(table, oldSelfPath, newParent)` — **atomic** subtree path rewrite in one transaction via a
    single `ltree` UPDATE:
    `UPDATE <t> SET path = CASE WHEN nlevel(path)=oldDepth THEN newSelfPath ELSE newSelfPath || subpath(path, oldDepth) END WHERE path <@ oldSelfPath`.
    It rewrites the moved node + every descendant; a cycle (re-parent under one's own descendant) is
    rejected up-front; the table identifier is regex-validated (only interpolated token), all values bound.

## Tests

`:opa-abac-spring-data:test` **green — 75 tests, 0 failures** (T1+T2+T3 + all pre-existing):

- **Unit (`AbstractHierarchicalEntityTest`, plain JUnit)** — `selfLabel()` encodes `<type>_<dash-free-hex>`
  (matches the resolver decoder); path accessor round-trips; `abacParent()` declares one hop; still an
  `AbstractSecuredEntity` (tags/AbacDataObject inherited).
- **Integration (`AbstractHierarchicalEntityIT`, Testcontainers real Postgres + `ltree`)** — uses
  `TransactionTemplate` for committed units of work (not `@DataJpaTest`'s rollback) so the atomic re-parent
  and its rollback are observable across transactions:
  - **I6** insert derives the correct path from the parent (+ resolver decodes it root-first/leaf-excluded);
  - **I7** re-parent rewrites the whole subtree (category + product, 2 rows) → the resolver returns the
    **new** chain;
  - **I8** **atomicity** — a `UNIQUE(path)` collision *mid-rewrite* rolls the **whole** subtree UPDATE back;
    the tree is left exactly as before (no half-rewrite);
  - **I8a** a missing new-parent path throws **before** any write;
  - **I9** re-parent under one's own descendant is rejected (cycle guard).
- **I9 (non-hierarchical unaffected)** — the unchanged `AbstractSecuredEntityTest` is the regression guard:
  a plain secured entity has no `path` column and unchanged behavior.

## Architecture review + refactor (the ★ gate)

- **Module-layer separation:** the maintainer has **zero** `OpaClient`/`PartialResult`/`Specification`
  coupling (grep-proven); the entity uses JPA (correct — entity layer) + `core.ParentRef` + `HierarchyLabels`,
  no OPA/residual. Residual machinery untouched (no `filter/*` in the diff).
- **SQL-injection guard:** the re-parent's only interpolated token is the table identifier, regex-validated
  by `assertSafeIdentifier`; `newSelfPath`/`oldDepth`/`oldSelfPath` are all bound parameters.
- **Refactor applied (a real bug the IT caught, not churn):** the first re-parent SQL used
  `subpath(path, oldDepth)` uniformly, which Postgres rejects as `ERROR: invalid positions` for the moved
  node itself (offset == nlevel). Fixed to a `CASE` that returns `newSelfPath` for the node and
  `newSelfPath || subpath(path, oldDepth)` for descendants. Also removed a speculative unused
  `LtreeAncestorResolver.hasPath` helper carried from T2. Re-ran: green.

## Integration / e2e

Re-parent + atomicity proven against **real Postgres + the `ltree` extension** (above). Gateway e2e is T7.

## Decisions

- **Path maintenance lives off the entity** (in `HierarchicalPathMaintainer`), mirroring the
  `AbstractCrudService.mutate` write-centralization — a `@MappedSuperclass` can't run the native subtree
  UPDATE anyway, and keeping it in a service makes the atomic re-parent a single transactional unit.
- **ltree `?::ltree` write cast** (`@ColumnTransformer`) is the robust way to bind a `String` to an `ltree`
  column without relying on an implicit text→ltree cast.
- **Atomicity is a single-statement guarantee:** the whole subtree rewrite is one `UPDATE`, so Postgres'
  statement atomicity rolls it back wholly on any per-row failure — proven by the `UNIQUE(path)` collision
  test (I8).

## Commit

`feat(spring-data): add AbstractHierarchicalEntity + atomic ltree re-parent maintainer` — see the T3
commit on `feature/void3110/hierarchy-single-resource`.
