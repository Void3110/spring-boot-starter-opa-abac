---
tags:
  - status/done
  - type/project
  - area/abac
  - area/spring-data
---

# STATUS T2 — `AncestorResolver` SPI + ltree & recursive-CTE impls (cycle + depth, fail-closed)

> Filled in at the T2 checkpoint during the autonomous run. One commit per ticket.

## What shipped

A new `dev.dmitriikonovalov.opaabac.data.hierarchy` package — the fail-closed ancestor-chain walk, behind
an SPI with two interchangeable impls:

- **`AncestorResolver`** — `List<ParentRef> ancestorsOf(String leafType, String leafId)`; documented
  contract: **root-first, leaf-excluded**, empty for a root, and **throw `AncestorResolutionException`
  (never truncate)** on a cycle / broken link / depth breach / malformed lineage / SQL error.
- **`AncestorResolutionException`** — the fail-closed signal; its javadoc spells out that callers treat it
  as "no inheritance ⇒ direct grant only," never "allow."
- **Two data-access SPIs** (the per-app seam the library doesn't own): `LtreePathSource`
  (`Optional<String> pathOf(type,id)`) and `ParentLinkSource` (`Optional<ParentRef> parentOf(type,id)`).
  The resolver owns the *walk algorithm*; the source owns the *lookup*.
- **`LtreeAncestorResolver` (default)** — reads the leaf's denormalized `ltree path` in one indexed query
  and decodes it root-first, leaf-excluded; throws on missing/`NULL`/blank path, malformed label, a
  leaf-label that doesn't match the requested leaf (row inconsistency), or depth > `maxDepth`.
- **`RecursiveCteAncestorResolver`** — walks the live `parent_id` adjacency hop-by-hop (the source is
  CTE-backed); **visited-set cycle detection** (incl. self-loop) + a **depth counter**; correct-by-
  construction on re-parent (no denormalized state).
- **`HierarchyLabels`** — the shared encode/decode for the `ltree` label convention `<type>_<id>`.
  Because `ltree` labels permit only `[A-Za-z0-9_]`, a UUID id is encoded as its **32 dash-free hex
  digits** and restored to canonical dashed form on decode (split on the *first* `_` → `(type, id)`).
  The maintainer (T3) and the decoder (here) share this one class so they can never drift.

`maxDepth` is a constructor arg on both resolvers (validated `>= 1`), wired from starter config in T5.

## Tests

`:opa-abac-spring-data:test` **green** (full module suite + the new cases; pre-existing filter/model/
service tests unaffected):

- **Unit (`AncestorResolverTest`, plain JUnit, in-memory sources)** — both impls: root-first +
  leaf-exclusion; root → empty chain; ltree missing/blank-path → throw, malformed label → throw,
  leaf-mismatch → throw, depth breach → throw, source error → fail-closed; CTE cycle (A→B→A) + self-loop
  → throw, depth breach → throw, source error → fail-closed; both-impls-agree; invalid `maxDepth` rejected.
- **Integration (`AncestorResolverIT`, Testcontainers real Postgres + `ltree` extension)** — I1 both
  impls return the same chain off a seeded 3-level tree read through a **real `ltree` column** (ltree
  resolver) and a **real adjacency walk** (CTE resolver); I2 a seeded cycle → throw; I3 depth > `maxDepth`
  → throw (both); I4 a `NULL` path → throw, an inconsistent path leaf → throw; I5 root → empty.

## Architecture review + refactor (the ★ gate)

What the review verified, then one small refactor applied:

- **Module-layer separation (the key win):** the `hierarchy` package imports only `core.ParentRef` +
  `java.util` — **zero** `OpaClient` / `PartialResult` / `ResidualSpecificationFactory` / `Specification`
  coupling (grep-proven). The resolver owns the fail-closed walk; the SPI sources own data access; the OPA
  wire format and the list residual are untouched by this ticket.
- **Residual untouched:** `git diff --name-only` for the code is entirely under `hierarchy/` (plus a
  test-only `build.gradle.kts` driver-dependency promotion — `testRuntimeOnly` → `testImplementation` so
  the IT can use `PGSimpleDataSource` directly). `filter/*` is not touched.
- **Fail-closed audit:** every enumerated failure path **throws** (no branch returns a partial chain);
  asserted by the cycle / self-loop / depth / broken / malformed / SQL-error tests.
- **Refactor applied:** removed a speculative unused `LtreeAncestorResolver.hasPath(Optional)` helper (and
  its now-dead `Optional` import) — keeping the public surface minimal. Re-ran the suite: green.

## Integration / e2e

Resolver ITs run against **real Postgres + the `ltree` extension** via Testcontainers (never H2) — see
`AncestorResolverIT` above. No gateway e2e at this ticket (that's T7, once the example adopts the walk).

## Decisions

- **Resolver data-access boundary.** Rather than bake table knowledge into the library, the resolver is
  parameterized by a tiny lookup SPI (`LtreePathSource` / `ParentLinkSource`). This keeps both resolvers
  app-table-agnostic and unit-testable with in-memory stubs, while T3/T6 back them with real entity/SQL
  reads. The resolver still owns all the correctness-critical logic (ordering, leaf-exclusion, cycle,
  depth, throw-never-truncate).
- **ltree label encoding for UUIDs.** `ltree` labels disallow `-`; encode a UUID as 32 hex digits, decode
  back to canonical dashed form. Shared in `HierarchyLabels` so the path-maintainer and the decoder agree.

## Commit

`feat(spring-data): add AncestorResolver SPI + ltree & recursive-CTE impls (fail-closed)` — see the T2
commit on `feature/void3110/hierarchy-single-resource`.
