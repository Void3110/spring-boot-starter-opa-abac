---
tags:
  - status/done
  - type/project
  - area/abac
  - area/spring-data
  - area/opa
---

# STATUS T1 — SPI: `AncestorResolver.subtreeOf` + both impls (ltree pushdown · CTE bounded walk), fail-closed

> Filled at the T1 checkpoint. One focused commit. ✅ shipped.

## What shipped

- **`AncestorResolver.subtreeOf(String rootType, String rootId) → Specification<T>`** — one additive SPI
  method (the inverse of `ancestorsOf`): a JPA `Specification` selecting the rows in the subtree rooted at
  `(rootType, rootId)`, root-inclusive. Documented contract: **subtree-of**, **SQL pushdown where possible**,
  and **fail-closed to an always-false predicate (never throws)** — a deliberate departure from
  `ancestorsOf`'s throw-fail-closed, because `subtreeOf` is OR-ed into a query where a no-op widening is the
  safe collapse.
- **`LtreeAncestorResolver.subtreeOf`** → a pure `ltree` SQL pushdown. Reads the root's materialized path once
  via `LtreePathSource`, then returns `ltree_isparent(rootPath, entity.path)` — the **function form** of the
  `@>` operator (the reverse of `<@`), so the predicate stays inside JPA Criteria (no native-SQL operator
  string). The descendant id set is **never materialized** in Java. The bound root path is cast via
  `text2ltree(?)`. Fail-closed: missing/blank/over-deep root path or any source error → always-false.
- **`RecursiveCteAncestorResolver.subtreeOf`** → a bounded `id IN (…)` from a **downward `parent_id` walk**.
  BFS from the root collecting all descendant ids, **bounded by `maxDepth` levels**, cycle-guarded by a
  visited set. Fail-closed: no descendant source / depth breach / cycle / SQL error → always-false (never an
  unbounded `IN`). The bound ids are converted to `UUID` to match the `uuid` id column (see Decisions).
- **`DescendantIdSource`** — a new optional, downward (`childrenOf(type,id) → List<ParentRef>`) data-access
  SPI, the counterpart of `ParentLinkSource`. The CTE resolver gains a 3-arg constructor + `of(...)` factory
  taking it; the existing 2-arg constructor/factory are preserved byte-compatible (delegate with
  `descendantSource = null` → `subtreeOf` fails closed). An app that only needs single-resource hierarchy
  (5.5-A) or uses the ltree resolver simply does not supply it.

## Tests

- **New `SubtreeOfIT`** (Testcontainers, **real Postgres + ltree**, `@DataJpaTest` with a hierarchical
  `NodeEntity` test fixture) — 8 cases, all green:
  - I1 — `ltree.subtreeOf(catalog C)` selects C's whole subtree (root-inclusive), **excludes the sibling
    catalog D** (`ltree_isparent` confirmed against real Postgres);
  - I2 — `cte.subtreeOf(catalog C)` selects the **same** row set via the bounded `id IN` walk;
  - both-impls-agree; `subtreeOf(category)` selects only that branch;
  - I3 fail-closed — CTE depth breach → empty; CTE no-descendant-source → empty; ltree missing root path →
    empty; ltree over-deep root path → empty.
- `./gradlew :opa-abac-spring-data:test` **green** (full module suite, incl. all shipped ITs — no regression).
- Unit/IT for `ancestorsOf` unchanged and still green (the additive method did not touch the walk).

## Architecture review + refactor (the ★ gate)

- **Fail-closed:** verified every `subtreeOf` error/empty path lands on the always-false predicate (8 IT
  cases prove it). The throw-vs-swallow asymmetry vs `ancestorsOf` is deliberate and documented in the SPI
  Javadoc.
- **Boundary/additivity:** `opa-abac-core` **untouched** (git-confirmed); `ancestorsOf` byte-compatible; both
  shipped impls implement `subtreeOf`; the CTE 2-arg constructor preserved. `Specification` on the SPI is
  allowed (spring-data, not core).
- **Module separation:** the subtree predicate + the down-walk live in `data.hierarchy`; no composition
  logic here (that is T3). The ltree pushdown / CTE strategy stays **behind the SPI** — the caller gets a
  `Specification`, never learns which impl produced it.
- **Pattern reuse:** the always-false shape reuses the `cb.disjunction()` convention from
  `ResidualSpecificationFactory`; the JPA-Criteria-function style (`cb.function`) matches `JsonPathDialect`.
- **Refactor applied:** none structural — the design held. The one fix was a real bug (see Decisions).

## Integration / e2e

- Testcontainers IT only (T1 is library-internal; the gateway e2e is T6). Docker socket in this environment:
  the podman docker-API socket (gvproxy) — the build's `resolveDockerHost()` reports a stale `-api.sock`
  path; the live one had to be supplied via `DOCKER_HOST`. Pre-existing environment quirk, not a code issue.

## Decisions

- **CTE `id IN` must bind UUIDs, not Strings.** First IT run failed `operator does not exist: uuid =
  character varying` — `root.get("id").as(String.class).in(stringIds)` binds varchar literals against a
  `uuid` column. Fixed by converting each id to `UUID` when it parses as one (else verbatim), so the bound
  literals match the column type. This is the SQL-type-agreement analogue of the residual's scalar-vs-array
  care.
- **Downward walk needs a new SPI.** `ParentLinkSource` is upward-only (`parentOf`); the CTE `subtreeOf`
  needs children, so `DescendantIdSource` (`childrenOf → List<ParentRef>`, carrying types so the walk can
  recurse) was added — additive, optional, fail-closed-when-absent.

## Commit

`feat(spring-data): AncestorResolver.subtreeOf (ltree pushdown + CTE bounded walk), fail-closed` on
`feature/void3110/hierarchy-list-filter`.
