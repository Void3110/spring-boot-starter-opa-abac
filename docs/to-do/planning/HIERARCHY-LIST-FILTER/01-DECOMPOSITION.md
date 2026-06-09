---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/spring-data
  - area/opa
---

# 01 — Decomposition: hierarchy-aware list filter (Slice 5.5-B)

> The ordered work list for [[HIERARCHY-LIST-FILTER|Slice 5.5-B]], decomposed from [[00-DESIGN]] +
> ADR [[0010-hierarchy-aware-list-filter|0010]]. **6 tickets, one focused commit each.** Each ticket's
> *Acceptance* references a case in [[10-QA-TEST-CASES]]. Packages: library under
> `dev.dmitriikonovalov.opaabac.{data.hierarchy, data.filter}`; example under
> `dev.dmitriikonovalov.example.catalog.*`.
>
> **Critical path: T1 → T2 → T3 → T4 → T5 → T6.** T1 is **independently landable** (pure SPI + ITs, no
> app). T4 (the consolidated Testcontainers IT) lands after T3 — it proves T1–T3 against real Postgres
> before the example adoption (T5) and the gateway e2e (T6).

This slice **only** extends the **list** path. It is **additive** to the shipped seams: the 3-arg
`AbacQueryService.findAuthorized` stays byte-compatible; `opa-abac-core` is **not touched**; the tag-only
residual / `CompileResponseParser` / `ResidualSpecificationFactory` / closed operator set / `RoleDefinition`
are **unchanged**. Hierarchy widening is an app-built `subtreeSpec` only.

---

## T1 — `AncestorResolver.subtreeOf` + both impls (ltree pushdown · CTE bounded walk), fail-closed

**Goal.** Add the SPI method that produces the "rows in a root's subtree" predicate, each impl its own way,
both fail-closed. The reusable library core of the slice — independently landable.

**Deliverables.**
- `AncestorResolver` SPI (`opa-abac-spring-data`, `data.hierarchy`) gains **one additive method**:
  `<T> Specification<T> subtreeOf(String rootType, String rootId)` — returns a JPA `Specification` selecting
  the rows in the subtree rooted at `(rootType, rootId)`. (Spring `Specification` is allowed here — this is
  spring-data, not core.)
- **`LtreeAncestorResolver.subtreeOf`** → a `path <@ '<root-label>'` predicate, pushed entirely into SQL via
  the existing `LtreePathSource` / `HierarchyLabels` label convention (e.g. `catalog_<id>`). The descendant
  id set is **never materialized** in Java. Uses the same JSONB/ltree Criteria seam style as the shipped
  `ResidualSpecificationFactory` (bound literals, no SQL strings).
- **`RecursiveCteAncestorResolver.subtreeOf`** → an `id IN (<descendant ids>)` predicate from a **downward
  `parent_id` walk**, **bounded by the existing `maxDepth`** guard, **fail-closed**: a depth breach / cycle /
  SQL error → an **always-false** predicate (`(r,q,cb)->cb.disjunction()`), so the list falls back to the
  narrower tag-only result. Never an unbounded `IN`.
- A small shared helper for the always-false / always-true Specification shapes if not already present
  (reuse `ResidualSpecificationFactory`'s `disjunction()`/`conjunction()` convention; do not duplicate).

**Acceptance.** `./gradlew :opa-abac-spring-data:test` green **and** a Testcontainers IT (real Postgres)
proving, for **both** impls, the exact surviving row set of `subtreeOf(catalog, C)` over a seeded
catalog→category→product tree (I1–I3 in [[10-QA-TEST-CASES]]): the root's whole subtree is selected; a
sibling subtree is excluded; a `maxDepth`-exceeding / cyclic tree → empty (fail-closed), never the whole
table. The ltree IT confirms the predicate is a single `path <@` (no Java-side id enumeration); the CTE IT
confirms the `id IN` set equals the descendant set and is bounded.

**What NOT to touch.** `opa-abac-core` (untouched). The shipped `ancestorsOf` (unchanged — this is additive).
The residual model / `ResidualSpecificationFactory` translation of *tags* (this is a *lineage* predicate, a
separate code path). No `descendantIdsOf → Set<id>` public method (the predicate keeps the ltree pushdown +
the strategy behind the SPI).

---

## T2 — `SubtreeSpecResolver` (root-only resolution + inheritable gate → `subtreeOf`)

**Goal.** Decide *whether* to widen and produce the `subtreeSpec` — root-only, fail-closed to empty.

**Deliverables.**
- `SubtreeSpecResolver` (`opa-abac-spring-data`, `data.hierarchy`) holding the `AncestorResolver` +
  `RoleDefinitionSupplier` (+ the inheritance declaration / settings already wired in 5.5-A).
- A method `<T extends AbacDataObject> Optional<Specification<T>> subtreeSpec(AbacContext.Subject subject,
  ParentRef governingRoot, String verb)` that:
  1. resolves the role **once on `governingRoot`** via `RoleDefinitionSupplier.lookup` — exactly as
     `HierarchicalAuthorizer` does (reuse, do not duplicate the lookup semantics);
  2. applies the **inheritable-relation gate** — the relation must be declared inheritable (opt-in,
     default-off) **and** the root-resolved role must grant `verb` on the governing-root type;
  3. on grant → `Optional.of(ancestorResolver.subtreeOf(governingRoot.type(), governingRoot.id()))`;
  4. on no role / no inheritable grant / **any resolution exception** → `Optional.empty()` (fail-closed).
- **Root-only** (ADR 0010 §1): the only candidate subtree-root is the governing root; **no** per-node /
  mid-tree grant search (that is Phase 8).

**Acceptance.** `./gradlew :opa-abac-spring-data:test` green. Mock-based unit tests (U1–U5 in
[[10-QA-TEST-CASES]]): granted+inheritable → `Optional.of(subtreeSpec)`; not inheritable (default-off) →
empty; no role definition → empty; role lacks the verb → empty; resolver throws → empty (fail-closed). Assert
the role is resolved on the **governing root** (`ArgumentCaptor` on `RoleDefinitionSupplier.lookup`), once.

**What NOT to touch.** `opa-abac-core`. `AbacQueryService` (T3 wires this in). The `subtreeOf` impls (consume
read-only). No per-ancestor role resolution (root-only).

---

## T3 — `AbacQueryService`: 4-arg overload (composition) + `notDenied` + hierarchy-aware batch path

**Goal.** Compose `scope.and(tagResidual.or(subtreeSpec)).and(notDenied)` on the pure-SQL path, make the
allowlist-batch path hierarchy-aware, and keep the 3-arg signature byte-compatible.

**Deliverables.**
- An **additive 4-arg overload**
  `findAuthorized(JpaSpecificationExecutor<T> repo, Specification<T> scope, AbacContext queryContext,
  Specification<T> subtreeSpec)` that owns the composition on the **pure-SQL path**:
  `widened = (subtreeSpec == null) ? tagResidual : tagResidual.or(subtreeSpec)`; then
  `combined = scope.and(widened).and(notDenied)`; `repo.findAll(combined)`.
- The **3-arg `findAuthorized` is preserved byte-compatible** — it delegates to the 4-arg with
  `subtreeSpec = null` (→ exactly today's behavior). `AbacQueryService` gains **no** resolver dependency
  *on the composition path* (it receives a ready `Specification`).
- A **`notDenied` Specification** (`abac_deny IS DISTINCT FROM true` over the tags JSONB) AND-ed **outside**
  the OR; built via the existing `JsonPathDialect` JSONB Criteria seam (no new dialect/operator). A row
  **absent** the tag → not denied (matches Rego `not denied` on an absent key).
- **Hierarchy-aware batch path** (ADR 0010 §5): `batchFilter`/`withResource` build each per-row
  `AbacContext` **with the row's ancestor chain** (the 4-arg `Resource(type,id,attributes,ancestors)`) so
  `opaClient.allowAll` decides each row by the same Rego `final_allow` as the single-GET. This adds an
  `AncestorResolver` dependency **to the batch path** (constructor change). `subtreeSpec` is **NOT** applied
  on the batch path (the per-row decision already includes inheritance + deny). Ancestor resolution per
  candidate is cheap (the ltree `path` is already loaded); a per-row resolution failure → empty ancestors →
  that row decided on its **direct** grant only.

**Build-breaker (must land in this commit).** The `AbacQueryService` constructor gains an `AncestorResolver`
(for the batch path). Every construction site breaks until updated: the starter auto-config bean (T5
finalizes the wiring, but the constructor change is here) and any existing `AbacQueryService` unit test /
test bean. Update **all** construction sites in this same commit so `./gradlew build` stays green —
"additive to the contract" ≠ "zero-touch for constructors."

**Acceptance.** `./gradlew :opa-abac-spring-data:test` green **and `./gradlew build` green**. Mock repo +
3-method stub `OpaClient` unit tests (U6–U12 in [[10-QA-TEST-CASES]]): 3-arg path identical to today
(byte-compat — a captured `Specification` shape / a behavioral equivalence test); 4-arg with a `subtreeSpec`
→ the combined spec OR-s the widening **inside** `scope.and(...)` and AND-s `notDenied` **outside** (capture
+ assert the structure); `subtreeSpec=null` 4-arg ≡ 3-arg; `notDenied` excludes an `abac_deny=true` row from
both branches; the batch path builds per-row contexts **with ancestors** (`ArgumentCaptor` on `allowAll`
asserts each `Resource` carries the chain); a per-row resolver failure → that row falls to direct-grant
(empty ancestors); a short/all-false `allowAll` drops rows.

**What NOT to touch.** `opa-abac-core` (the 4-arg `Resource(...,ancestors)` ctor already exists from 5.5-A —
consume it, don't change core). The tag residual / `ResidualSpecificationFactory` (consumed read-only). The
kill-switch path (`partialEval.enabled=false`) — unchanged (no residual ⇒ hierarchy N/A). The
`@OpaPreAuthorize` interceptor / single-resource `HierarchicalAuthorizer`.

---

## T4 — spring-data IT (real Postgres): row-sets, `notDenied`, no-leak, re-parent-on-list

**Goal.** Prove the composed behavior end-to-end at the persistence layer against **real Postgres** — the
deterministic backbone before the gateway e2e.

**Deliverables.** A Testcontainers IT (real Postgres + ltree + JSONB; never H2) in `opa-abac-spring-data`
seeding a catalog→category→product tree with region-tagged rows + one `abac_deny=true` row, exercising
`AbacQueryService.findAuthorized` (4-arg) with a stub/real resolver for **both** impls. Asserts (I4–I8 in
[[10-QA-TEST-CASES]]):
- **Widening** — a role with no leaf-tag match but an inheritable catalog grant → the whole catalog subtree
  (MANDATORY).
- **Two subjects → different row sets** — a region-gated role sees only its region's rows; the
  inheritable-grant role sees all — a **different set** (proves the cut is in SQL).
- **`notDenied` AND-narrowing** — the `abac_deny=true` row is excluded **even from** the widened set
  (MANDATORY).
- **AND-with-scope no-leak** — a subtree widening AND a **foreign** `catalogId` scope → **empty** (the
  widening can't escape the caller's scope) (**MANDATORY** — the load-bearing fail-closed invariant).
- **Re-parent moves a row in/out of a widened list** — move a Category subtree under a different catalog (the
  shipped atomic `reparent()`), re-query → the rows leave one subject's widened list and enter another's
  (MANDATORY).

**Acceptance.** `./gradlew :opa-abac-spring-data:test` green incl. this IT. The spring-data test task carries
`TESTCONTAINERS_RYUK_DISABLED=true` + the resolved podman `DOCKER_HOST` (copy the example modules'
`resolveDockerHost()`); `@EnableJpaAuditing` `DateTimeProvider` returns `OffsetDateTime` (a `LocalDateTime`
provider throws — `createdAt` is `@CreatedDate nullable=false`).

**What NOT to touch.** No production code change here (this is test-only; any production fix it reveals lands
by amending the relevant T1–T3 commit, not a new behavior). `opa-abac-core`. The example app (T5).

> *Planner note:* T4 may absorb the impl-specific row-set ITs from T1 if cleaner, but the **no-leak** and
> **re-parent-on-list** ITs are mandatory here regardless.

---

## T5 — Starter wiring + example list-authorizer adoption (the 4-arg call)

**Goal.** Auto-configure `SubtreeSpecResolver` (conditional + overridable) and make the catalog list
endpoints widen via the 4-arg `findAuthorized`.

**Deliverables.**
- Starter: a `@ConditionalOnMissingBean` `SubtreeSpecResolver` bean (wired with the `AncestorResolver` +
  `RoleDefinitionSupplier` + the inheritance settings already present from 5.5-A); update the
  `AbacQueryService` bean for its new `AncestorResolver` constructor arg (from T3). No new property is needed
  — the inheritance declaration + `maxDepth` already exist.
- Example (`example-catalog-management-service`): `CategoryListAuthorizer` (and the product list path)
  resolve the role on the governing Catalog as today, call `SubtreeSpecResolver.subtreeSpec(...)`, and pass
  the result into the **4-arg** `findAuthorized`. The `catalogId(+parentId)` scope Specification is unchanged
  (still AND-ed first).

**Acceptance.** `./gradlew build` green; `ddl-auto: validate` clean (**no schema change** — 5.5-A already
added the ltree `path` column + GIN index). `ApplicationContextRunner` (U13–U14): the `SubtreeSpecResolver`
bean is present when hierarchy is enabled and **overridable**; the `AbacQueryService` bean still wires. The
existing catalog ITs (`CatalogCrudIT`, list ITs) stay green under the permissive test profile.

**What NOT to touch.** `opa-abac-core`. The 3-arg `findAuthorized` callers that don't widen (still valid). No
DB columns/migrations. No OpenAPI shape change (the list response shape is unchanged — same array, more rows).

---

## T6 — e2e matrix (through the gateway) + docs + roadmap/Mulch

**Goal.** Prove, through the full rig, that an ancestor grant widens a list, two subjects get different
subtree row sets, a deny still removes a row, the stranger gets `[]`, and a re-parent moves a row in/out.

**Deliverables.**
- A newman e2e suite (`scripts/postman/`, modeled on the shipped `run-filter-matrix.sh` /
  `data-filter-matrix.postman_collection.json`) asserting **list row SETS** (not single 200/403), with the
  full rig (`ENABLE_OIDC=1 ENABLE_USER_SERVICE=1`). The matrix (E1–E5 in [[10-QA-TEST-CASES]]):
  1. **Ancestor grant widens a list** — a subject with an inheritable catalog grant sees subtree rows their
     leaf-tags alone wouldn't.
  2. **Two subjects → different subtree row sets** — region-gated vs inheritable-grant → different sets.
  3. **Deny-overrides removes a row** — an `abac_deny=true` Category is absent from the widened list.
  4. **Unbound stranger → `[]`** — the standing no-membership user (fail-closed boundary).
  5. **Re-parent moves a row in/out** — move a Category subtree through the gateway; re-query → the rows
     enter/leave a subject's visible list.
- Seeding via the user-service `/internal/bootstrap/*` (a team + an inheritable-grant role + region-gated
  roles + the deliberately-unbound stranger), region-tagged Categories + one `abac_deny` Category created
  through the gateway with the owner token.
- Docs: update `docs/guides/PARTIAL-EVALUATION-FILTERING.md` (the hierarchy-aware list section) and
  `docs/guides/HIERARCHICAL-AUTHORIZATION.md` (the list analogue of the single-resource decision); update
  `docs/guides/E2E-TESTING.md` + `scripts/postman/README.md` for the new matrix. Update `POC-ROADMAP.md`
  (Phase 5.5 done — 5.5-B shipped). Move the folder to `docs/to-do/implemented/HIERARCHY-LIST-FILTER/` with a
  "Shipped" banner.
- Mulch: record the durable insights (the `subtreeSpec` composition + `subtreeOf` SPI pushdown-vs-CTE; the
  `notDenied`-outside-the-OR rule; the hierarchy-aware batch path; the no-leak invariant) and `ml sync`
  (`.mulch`-only). Record the **`autonomous-runs`** reference record with `--outcome-status`.

**Acceptance.** Rig up → `run-<matrix>.sh` green (all five assertions); `opa test` green (no rego behavior
change expected — confirm `filter`/`final_allow` still pass); `bash -n` clean; JSON valid;
docs/roadmap/Mulch updated; **clean-room scan clean**. **No push.**

**What NOT to touch.** The shipped residual / operator set / `RoleDefinition`. `opa-abac-core`. The gateway
`gateway.rego` coarse layer (unchanged). Don't add a newman CI job (tracked follow-up).

---

## Cross-cutting acceptance (the whole slice)

- `./gradlew build` green (all modules + example + Testcontainers ITs + `ddl-auto: validate` boot).
- `opa test infra/opa/policies/` green (no rego regression).
- The e2e matrix proves the **actual cut** (row sets), not just response shape.
- **Fail-closed audit** (from [[00-DESIGN]] §9) holds at every error/empty path: failed subtree resolution →
  narrower tag-only; `notDenied` never silently `TRUE`; batch only removes rows; `subtreeSpec` OR-ed
  **inside** `scope.and(...)` (never escapes scope).
- **Additive/byte-compatible:** the 3-arg `findAuthorized` unchanged; `subtreeOf` additive (both impls);
  `opa-abac-core` untouched; residual model / operator set / `RoleDefinition` unchanged.
- **Clean-room scan clean** across all new/changed files.

## Critical path

```
T1 ──► T2 ──► T3 ──► T4 ──► T5 ──► T6
(SPI)  (resolver) (compose)  (IT)  (wire+adopt) (e2e+docs)
```
- **T1 independently landable** — the `subtreeOf` SPI + both impls + their ITs are reusable library value
  with no app dependency.
- **T1+T2+T3** land the library composition; **T4** proves it against real Postgres; **T5** adopts it in the
  example; **T6** proves it through the gateway and ships the docs.
