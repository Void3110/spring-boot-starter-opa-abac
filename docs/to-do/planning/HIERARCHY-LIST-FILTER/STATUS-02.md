---
tags:
  - status/done
  - type/project
  - area/abac
  - area/spring-data
---

# STATUS T2 — `SubtreeSpecResolver` (root-only resolution + inheritable gate → `subtreeOf`)

> Filled at the T2 checkpoint. One focused commit. ✅ shipped.

## What shipped

- **`SubtreeSpecResolver`** (`opa-abac-spring-data`, `data.hierarchy`) — holds the `AncestorResolver` + the
  `RoleDefinitionSupplier` + the **inheritance declaration** (a plain `Map<String,List<String>>` of
  `childType -> [ancestorType…]`). One method:
  `<T extends AbacDataObject> Optional<Specification<T>> subtreeSpec(subject, childType, governingRoot, verb)`.
- **Root-only resolution (ADR 0010 §1):**
  1. **Inheritable-relation gate** — the queried `childType` must declare `governingRoot.type()` inheritable
     (opt-in, default-off) — checked *first*, before any lookup, so a non-inheritable list never even hits
     the role supplier.
  2. Resolve the role **once on the governing root** via `RoleDefinitionSupplier.lookup` (never per-ancestor).
  3. **Verb gate** — the root-resolved role must grant `verb` on the **governing-root type**
     (`verb in permissions[governingRoot.type()]`).
  4. On grant → `Optional.of(ancestorResolver.subtreeOf(root.type, root.id))`; else / any exception →
     `Optional.empty()`.
- The inheritance declaration is held as a **plain `Map`**, not the starter's `OpaAbacProperties`, so
  `opa-abac-spring-data` keeps no dependency on the starter (the starter passes the map at wiring time, T5).

## Tests

- **New `SubtreeSpecResolverTest`** (mock-based, no DB) — 11 cases, all green (U1–U5, U5b + edges):
  - U1 — granted + inheritable → `Optional.of(subtree)`; `subtreeOf` called once;
  - U5b — role resolved on the **governing root**, never on a child (`verify`);
  - U2 — not inheritable (unknown child type / wrong ancestor type) → empty, **no supplier call**;
  - U3 — no role definition → empty; U4 — role grants only `write` on catalog / no catalog grant → empty;
  - U5 — supplier throws → empty (swallowed); null subject → empty; empty inheritance map → never widens.
- `./gradlew :opa-abac-spring-data:test` **green** (full module, no regression).

## Architecture review + refactor (the ★ gate)

- **Fail-closed:** every non-grant path → `Optional.empty()` (unit-proven across all branches). A
  `try/catch(RuntimeException)` around the lookup + inheritance read guarantees a thrown supplier never
  escapes as a widening.
- **Root-only:** verified the role is resolved once on the governing root, never per-ancestor — no
  mid-tree / per-node search (that is Phase 8).
- **Rego-agreement:** the gate mirrors `category.rego`'s `inherited_grant` exactly —
  `inheritable[child][ancestor]` AND `verb in permissions[ancestor.type]` — which is the construction that
  makes the widened list and a single-GET decide the same rows.
- **Boundary:** `opa-abac-core` untouched; no spring-data→starter dependency (the declaration is a plain
  `Map`). The `AbacQueryService` is not touched here (T3 wires the resolver in).
- **Refactor applied:** none structural — the design held.

## Integration / e2e

- N/A for T2 (mock-based unit ticket; the composition IT is T4, the gateway e2e is T6).

## Decisions

- **The inheritable grant lives in `permissions[ancestorType]`, not `permissions[childType]`.** The first
  test run failed because the fixtures put `read` under `permissions["category"]`; per the Rego
  `inherited_grant` (`verb in permissions[ancestor.type]`), a catalog-inherited grant is `read` under
  `permissions["catalog"]` (the root type), and the `category -> [catalog]` declaration flows it down. The
  *resolver* (`grantsVerb(role, governingRoot.type(), verb)`) was correct; the **test fixtures** were fixed
  to match the inheritance semantics. This is the load-bearing "list == single-GET by construction" point.

## Commit

`feat(spring-data): SubtreeSpecResolver (root-only inheritable gate -> subtreeOf), fail-closed` on
`feature/void3110/hierarchy-list-filter`.
