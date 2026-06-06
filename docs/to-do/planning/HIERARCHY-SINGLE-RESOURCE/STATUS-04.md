---
tags:
  - status/done
  - type/project
  - area/abac
  - area/spring-data
---

# STATUS T4 — single-resource hierarchical check (`direct OR (walk_ok AND inherited)`)

> Filled in at the T4 checkpoint during the autonomous run. One commit per ticket.

## What shipped

**`HierarchicalAuthorizer`** (`opa-abac-spring-data.hierarchy`) — the single-resource seam that ties the
resolver → `input.resource.ancestors` → OPA into one fail-closed decision, the single-GET analogue of
`AbacQueryService`. `isAllowed(subject, verb, leaf)`:

1. resolves the leaf's ancestor chain via `AncestorResolver` — an `AncestorResolutionException` collapses
   the chain to **empty** (fail-closed; the inherited path becomes unreachable);
2. resolves the role **once on the governing root** — the chain's first element, or the **leaf itself** when
   there is no inheritable lineage (today's one-step behavior generalized); never per-ancestor;
3. builds the `AbacContext` with the leaf's tags as `resource.attributes` **and** the chain as
   `resource.ancestors`, then calls `opaClient.allow`.

The policy decides `final_allow = direct_leaf_grant OR (walk_ok AND inherited_grant)`; this seam guarantees
the `walk_ok` half — a failed walk supplies no ancestors, so the decision can only come from the direct
grant, degrading to the pre-hierarchy result, never wider.

## Tests

`:opa-abac-spring-data:test` **green — 82 tests, 0 failures** (T1-T4 + pre-existing).

`HierarchicalAuthorizerTest` (mocked `OpaClient` + `RoleDefinitionSupplier`, stub `AncestorResolver`,
`ArgumentCaptor`):
- **I10 + U5 + U6** — a Catalog grant authorizes a deep Product: the role is resolved on the **root**
  (`(catalog, …)`, asserted `never` on the product), and the captured context carries the leaf's tags
  **and** the root-first/leaf-excluded chain, action `product:read`, role `catalog-viewer`.
- **I11** — a resolver **failure** → no ancestors → role resolved on the **leaf**, captured context has an
  empty `ancestors` (direct-grant-only, never wider); and resolver-failure + no direct grant → deny.
- **I12** — an unresolved role → deny, **no** OPA call.
- **I13** — opt-in off (resolver returns empty) → direct-only on the leaf, empty ancestors.
- no-subject → deny (no resolver/OPA call); an OPA-side error → deny.

## Architecture review + refactor (the ★ gate)

**Nothing substantive to refactor** — a clean mirror of the established `AbacQueryService` /
`CategoryAuthorizer` seam, with the fail-closed shape made explicit. Verified:
- **Role on the root, not per-ancestor:** exactly one `supplier.lookup` on the governing root (grep-proven);
  per-node independent grants stay Phase 8 / ReBAC.
- **Fail-closed everywhere:** no-subject → false; resolver exception → empty ancestors (degrade to
  direct-only); unresolved role → false; OPA error → false. The `inherited_grant` half only fires when the
  walk succeeds.
- **Module-layer:** the seam ties resolver→context→OPA; the residual model / `filter/*` is untouched (T4
  diff is just the new authorizer + its test). Constructor deps `Objects.requireNonNull`, matching the seam
  idiom.

## Integration / e2e

T4 is pure-unit by design (stub resolver + mocked OPA/supplier — the decomposition's acceptance). The
real-resolver, real-OPA path is exercised end-to-end in T6 (rego `opa test`) and T7 (gateway e2e).

## Decisions

- **Where the role resolves when the chain is empty:** on the **leaf itself** — this makes a failed/absent
  walk degrade to exactly the pre-hierarchy single-resource decision (`@OpaPreAuthorize`-style), which is
  the fail-closed contract. Confirmed against the design (governing-root = chain head, else the leaf).

## Commit

`feat(spring-data): add HierarchicalAuthorizer single-resource check (fail-closed)` — see the T4 commit on
`feature/void3110/hierarchy-single-resource`.
