---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/spring-data
  - area/opa
---

# 10 — QA test cases: hierarchy-aware list filter (Slice 5.5-B)

> The concrete cases each [[01-DECOMPOSITION|ticket]]'s *Acceptance* references. **U** = unit (mock-based,
> no DB), **I** = integration (Testcontainers **real Postgres** — never H2; `path <@` and `jsonb` are
> Postgres-only), **E** = e2e through the gateway (newman). Every case asserts the **actual cut** (row sets /
> allow-vs-deny), not just response shape. The fail-closed cases are load-bearing — they prove no error path
> widens the result.

## Conventions
- Unit tests: a **mock `OpaClient`** (3 methods: `allow`/`compile`/`allowAll` programmable) + a mock
  `RoleDefinitionSupplier` + a mock/stub `AncestorResolver`; `ArgumentCaptor` to assert the `Specification`
  composition + the per-row `AbacContext` shape.
- IT: real Postgres via Testcontainers; the spring-data test task carries `TESTCONTAINERS_RYUK_DISABLED=true`
  + the resolved podman `DOCKER_HOST` (copy the example modules' `resolveDockerHost()`); `@EnableJpaAuditing`
  `DateTimeProvider` returns `OffsetDateTime`.
- e2e: full rig `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2`; mint tokens **in-network**
  (issuer `keycloak:8888`); `./deploy.sh build` to force new app code; restart OPA after a rego edit; keep
  runtime ids in **collection** variable scope. In OPA 1.x `in` is reserved — don't name a test var `in`.
- The seeded tree (shared mental model): `catalog C` → categories `cat-emea` (tag `region=emea`),
  `cat-apac` (`region=apac`), `cat-amer` (`region=amer`), `cat-deny` (`region=emea`, `abac_deny=true`);
  catalog `D` (a foreign scope) with its own categories.

---

## Unit — `SubtreeSpecResolver` (T2)

| # | Case | Expected |
|---|------|----------|
| **U1** | Role on the governing root grants the verb **and** the relation is inheritable | `Optional.of(subtreeSpec)`; `subtreeOf(rootType, rootId)` called once |
| **U2** | Relation **not** declared inheritable (default-off) | `Optional.empty()` — no widening |
| **U3** | No role definition resolved on the root | `Optional.empty()` (fail-closed) |
| **U4** | Role resolved but does **not** grant the verb on the root type | `Optional.empty()` |
| **U5** | `AncestorResolver.subtreeOf` / the lookup throws | `Optional.empty()` (fail-closed — swallow, never propagate as widening) |
| **U5b** | Role is resolved on the **governing root** (not a leaf) | `ArgumentCaptor` on `RoleDefinitionSupplier.lookup` shows `(subjectId, rootType, rootId)`, resolved **once** |

## Unit — `AbacQueryService` 4-arg overload + `notDenied` + batch (T3)

| # | Case | Expected |
|---|------|----------|
| **U6** | 3-arg `findAuthorized` (no subtreeSpec) | behaves **identically to today** — `combined = scope.and(tagResidual)` (byte-compat; captured-spec or behavioral equivalence) |
| **U7** | 4-arg with `subtreeSpec = null` | identical to U6 (the 3-arg delegates here) |
| **U8** | 4-arg with a non-null `subtreeSpec`, `CONDITIONAL` residual | `combined = scope.and( tagResidual.or(subtreeSpec) ).and(notDenied)` — widening OR-ed **inside** `scope.and`, `notDenied` AND-ed **outside** (capture + assert structure) |
| **U9** | `notDenied` shape | a row with `abac_deny=true` is excluded from **both** the tag-branch and the subtree-branch; a row **absent** the tag is **kept** (`IS DISTINCT FROM true`) |
| **U10** | Residual `DENY_ALL` + a `subtreeSpec` | the subtree branch still widens (OR), so `combined` returns the subtree rows minus denied — DENY_ALL tag-branch does **not** suppress the widening |
| **U11** | Batch path (`!fullySupported` + allowlist on): per-row context | `ArgumentCaptor` on `allowAll` — **each** per-row `AbacContext.Resource` carries the row's `ancestors` (4-arg form); `subtreeSpec` **not** applied on this path |
| **U12** | Batch path, a candidate's ancestor resolution throws | that candidate's context has **empty** ancestors (decided on direct grant only); a short/all-false `allowAll` **drops** rows (fail-closed) |
| **U12b** | Kill-switch (`partialEval.enabled=false`) | unchanged — coarse `allow` + scope-only; hierarchy N/A (no residual) |

## Integration — `subtreeOf` impls + composition (T1, T4; real Postgres)

| # | Case | Expected |
|---|------|----------|
| **I1** | `LtreeAncestorResolver.subtreeOf(catalog, C)` over the seeded tree | selects **all** of C's descendants (cat-emea/apac/amer/deny + their products); a single `path <@ catalog_C` predicate (no Java id enumeration) |
| **I2** | `RecursiveCteAncestorResolver.subtreeOf(catalog, C)` | `id IN (<C's descendant ids>)` equals the same row set as I1; bounded by `maxDepth` |
| **I3** | `subtreeOf` on a too-deep / cyclic tree (CTE) | **always-false** predicate → empty result (fail-closed); never the whole table. (ltree: a malformed/missing `path` → empty, never unscoped) |
| **I4** | **Widening** — a role with no leaf-tag match but an inheritable catalog grant, via the 4-arg `findAuthorized` | sees the **whole** catalog-C subtree (the headline) |
| **I5** | **Two subjects → different sets** — region-emea-gated role vs inheritable-grant role | emea role → only `region=emea` rows; inheritable role → all C rows — a **different set** (proves SQL cut) |
| **I6** | **`notDenied` narrowing** — the inheritable-grant subject over the widened set | `cat-deny` (abac_deny=true) is **excluded** even though it's in the subtree (MANDATORY) |
| **I7** | **AND-with-scope no-leak** — subtree widening for catalog C **AND** a `scope = (catalogId == D)` | **empty** — the widening cannot surface catalog-D rows (MANDATORY; the load-bearing invariant) |
| **I8** | **Re-parent on list** — move `cat-apac`'s subtree from C to D (atomic `reparent()`), then re-query both subjects' lists | the moved rows **leave** the C-scoped widened list and **enter** the D-scoped list; both impls (ltree path rewrite + CTE live walk) agree (MANDATORY) |

## e2e — through the gateway (T6)

| # | Case (GET the category/product list) | Expected |
|---|------|----------|
| **E1** | A subject with an **inheritable catalog grant** lists categories | sees subtree rows their leaf-tags alone wouldn't (widened) |
| **E2** | **Two subjects, same endpoint** — region-gated vs inheritable-grant | **different row sets** (the decisive SQL-cut proof) |
| **E3** | The inheritable-grant subject, with a `cat-deny` (`abac_deny=true`) row present | `cat-deny` is **absent** from the widened list (deny-overrides) |
| **E4** | The **unbound stranger** (no membership → no role definition) | `[]` — the fail-closed boundary (the `filter` rule has no subject-roles fallback) |
| **E5** | **Re-parent flip** — move a Category subtree to another catalog through the gateway, re-list | the rows move in/out of the relevant subject's visible list |

## Fail-closed checklist (must all hold — the audit from [[00-DESIGN]] §9)

- [ ] Failed/too-deep/cyclic subtree resolution → empty `subtreeSpec` → **narrower** tag-only result (U5, I3).
- [ ] No role / no inheritable grant → empty `subtreeSpec` (U2–U4); no role definition → `[]` (E4).
- [ ] `abac_deny=true` row excluded from a widened list; absent tag → kept (U9, I6, E3).
- [ ] Batch path: per-row decision includes ancestors; resolution failure → direct-grant-only; short/all-false `allowAll` drops rows (U11–U12).
- [ ] `subtreeSpec` OR-ed **inside** `scope.and(...)` — widening never escapes the caller's scope (U8, I7).
- [ ] 3-arg `findAuthorized` byte-compatible; `opa-abac-core` untouched; residual/operator-set/`RoleDefinition` unchanged (U6–U7, build green).

## Related
- [[01-DECOMPOSITION]] (the tickets these cases gate) · [[00-DESIGN]] (the design + the fail-closed audit) ·
  ADR [[0010-hierarchy-aware-list-filter|0010]].
- The shipped templates: `docs/to-do/implemented/DATA-FILTERING/10-QA-TEST-CASES.md` (the list-filter e2e
  shape) · `docs/to-do/implemented/HIERARCHY-SINGLE-RESOURCE/10-QA-TEST-CASES.md` (the hierarchy IT shape).
