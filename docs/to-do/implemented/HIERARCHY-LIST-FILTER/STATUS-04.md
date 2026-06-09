---
tags:
  - status/done
  - type/project
  - area/abac
  - area/spring-data
---

# STATUS T4 — spring-data IT (real Postgres): row-sets · `notDenied` · no-leak · re-parent-on-list

> Filled at the T4 checkpoint. One focused commit. ✅ shipped. **Test-only** (no production change).

## What shipped

- **`HierarchyListFilterIT`** (Testcontainers, **real Postgres + ltree + JSONB**, `@DataJpaTest`) — the
  consolidated end-to-end composition test. Wires the real `SubtreeSpecResolver` (T2) over a real
  `LtreeAncestorResolver.subtreeOf` (T1) into the real `AbacQueryService` 4-arg `findAuthorized` (T3), over a
  seeded tree: catalog C → {cat-emea (region=emea), cat-apac (region=apac), cat-deny (region=emea +
  abac_deny)}; catalog D → {cat-d (region=apac)} (a foreign scope).
- **Two subjects, tag-only residuals** (as `category.rego`'s `filter` compiles): SUBJ_REGION →
  `region=emea` residual + no inheritable grant; SUBJ_INHERIT → `DENY_ALL` residual (no tag grant) + an
  inheritable catalog `read` grant — so the widening is the *only* thing that lets it see rows (the exact
  scenario `subtreeSpec` exists for; the e2e-CUT-isolation discipline from `mx-eae6ac`).

## Tests (all green, real Postgres)

- **I4/I5 — widening + two subjects → different sets**: SUBJ_REGION sees only `cat-emea`; SUBJ_INHERIT is
  widened to the whole C subtree minus deny (`cat-emea` + `cat-apac`) — a different SQL cut, and it sees
  `cat-apac` which its leaf-tags alone never would.
- **I6 — `notDenied` narrowing (MANDATORY)**: `cat-deny` (abac_deny=true) is excluded **even from the
  widened set**, and from the region subject's list.
- **I7 — AND-with-scope no-leak (MANDATORY)**: SUBJ_INHERIT's grant on C, list scoped to catalog D →
  **empty** — the C subtree widening cannot escape the D scope; no C row and no D row leaks in.
- **I8 — re-parent on list (MANDATORY)**: move `cat-apac`'s ltree path from C to D, re-query → `cat-apac`
  **leaves** C's widened list and **enters** D's.
- `./gradlew :opa-abac-spring-data:test` **green** (the full module, all three filter ITs coexisting).

## Architecture review + refactor (the ★ gate)

- **Fail-closed (load-bearing) — proven against real Postgres**, not just asserted: the no-leak (I7) and the
  deny-narrowing (I6) are the two places the composition could silently leak, both mandatory and green.
- **Boundary:** **no production change** in T4 (git-confirmed — `src/test` only). `opa-abac-core` untouched.
- **Refactor applied:** two **test-only** fixes surfaced by the ★ gate's full-suite run (see Decisions) —
  neither touched production code.

## Integration / e2e

- This *is* the integration ticket (real Postgres). The gateway e2e is T6.

## Decisions

- **`subtreeOf` reads the root's path from wherever the root lives — not the queried table.** First run:
  SUBJ_INHERIT's widened list came back **empty** because the test seeded only category rows, so
  `LtreePathSource.pathOf("catalog", C)` found no row → `subtreeOf` failed closed. Fix (test-only): the IT's
  `LtreePathSource` **synthesizes** a catalog's path from its label (`catalog_<id>`) and reads a category's
  path from its row — mirroring the real app, where each type lives in its own table with a `path` column.
  This clarifies a real contract point: `subtreeOf` needs the *root's* path, and the root may be a different
  type/table than the rows being listed.
- **Three `@DataJpaTest` ITs in one package need explicit `@ContextConfiguration`.** Adding a third
  `@SpringBootApplication` (`HierarchyListFilterIT.TestApp`) to the `data.filter` package made
  `@DataJpaTest`'s package auto-scan ambiguous ("Found multiple @SpringBootConfiguration") — surfaced only by
  the **full-suite** run, not the new IT alone. Fixed by pinning all three filter ITs
  (`ResidualSpecificationIT`, `AbacQueryServiceIT`, `HierarchyListFilterIT`) to their own `TestApp` via
  `@ContextConfiguration` (disables the scan). Test-only.

## Commit

`test(spring-data): HierarchyListFilterIT — widening, no-leak, deny, re-parent on real Postgres` on
`feature/void3110/hierarchy-list-filter`.
