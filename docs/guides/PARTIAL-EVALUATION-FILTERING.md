---
tags:
  - status/done
  - type/guide
  - area/spring-data
  - area/abac
  - area/opa
---

# Partial-evaluation data filtering (OPA → JPA `Specification`)

> Phase 5. How **list** endpoints answer "of the N rows, *which* may this subject see?" without N
> round-trips or a fetch-all-then-filter — by pushing authorization into the SQL `WHERE` clause via OPA
> **partial evaluation**, with a **batch** call for the residue that doesn't reduce to SQL. Builds on the
> [[ABAC-AUTHORIZATION]] spine and the [[TAG-BASED-AUTHORIZATION]] grant. The DB layer (layer 3) of the
> three-layer model ([[TWO-LAYER-AUTHORIZATION]]); pinned by ADR [[adr/0005-partial-eval-to-jpa-specification|0005]].

## Why

Every decision so far is **single-resource** (`@OpaPreAuthorize` — "may this subject do X to *this* one
resource?"). That is wrong for a **list**: the catalog's list endpoints used to run one coarse type-level
`category:read` check and return **every** row. The moment a grant is conditioned on the row (the tag grant
from Phase 4.5, now over a collection), the coarse check is wrong — it returns rows the subject must not
see. The two naive fixes are both bad: **fetch-all-then-filter** (O(table) I/O, leaks counts, breaks
pagination) and **one OPA call per row** (O(N) round-trips). The right answer pushes the row filter into
the database, decided by the same policy that decides single resources.

## The two mechanisms

### A. Partial evaluation → `Specification` (the headline)

OPA's **Compile API** (`POST /v1/compile`) evaluates the policy as far as it can with the *subject* known
and the *resource* declared **unknown** (`unknowns: ["input.resource"]`), and returns the **residual** — the
conditions, in disjunctive normal form, the row must satisfy. The library translates that residual into a
Spring Data JPA `Specification<T>` over the `tags` JSONB column and pushes it into the SQL `WHERE`.

```
   input = { subject, action, role_definition }   (KNOWN)
   unknowns = [ "input.resource" ]                 (the row — SYMBOLIC)
            │
   POST /v1/compile  { query: "data.category.filter == true", input, unknowns }
            │
            ▼
   residual (DNF):  region == "emea"  OR  "emea" ∈ region
            │  PartialResult { CONDITIONAL, [Condition…] }
            ▼
   ResidualSpecificationFactory → Specification<CategoryEntity>
            │   (predicate over the `tags` JSONB column)
            ▼
   categoryRepository.findAll(catalogScope.and(residual))   →  only the rows the subject may see
```

Three outcomes collapse the residual:

| Compile result | `PartialResult.Decision` | `Specification` |
|----------------|--------------------------|-----------------|
| an **empty** residual conjunction (`queries: [[]]`) — the subject may see all | `ALLOW_ALL` | no predicate (match all) |
| an **empty** result (`{}`) — the query can never hold for any row | `DENY_ALL` | `cb.disjunction()` (match none) |
| a set of conditions | `CONDITIONAL` | OR-of-ANDs over the row's columns/tags |

> **The fail-closed boundary (verified against OPA 1.x).** An empty `{"result": {}}` means the query is
> *unsatisfiable* → `DENY_ALL`, **not** allow-all. `ALLOW_ALL` is produced *only* by an explicit,
> satisfiable, condition-free residual. An absent/ambiguous compile output therefore denies — by
> construction. (This corrects an inverted reading that would be a fail-open whole-table leak.)

### B. Batch evaluation → post-fetch allowlist (the residue)

Some conditions don't reduce to a SQL predicate (a shape the translator deliberately doesn't support — the
operator set is small and closed on purpose). For those, after the SQL pre-filter narrows the candidate
set, one **batch** call — `allowAll(List<AbacContext>)` over the `bulk` rule — drops the rows that come back
`false`. One round-trip, not N. `allowAll` is a **reusable primitive** (action enrichment, Phase 6, consumes
the same method).

## The `OpaClient` additions (core, Spring-free)

```java
public interface OpaClient {
    boolean allow(AbacContext context);                       // unchanged (the spine)
    PartialResult compile(AbacContext context);               // Phase 5 — partial eval, fails closed to denyAll()
    List<Boolean> allowAll(List<AbacContext> contexts);       // Phase 5 — batch, fails closed to all-false
}
```

Both new methods are **abstract, not `default`** — a custom client cannot silently inherit a fail-**open**
filter. The residual model (`PartialResult`/`Conjunction`/`Condition`, operators `EQ`/`NEQ`/`IN`/`CONTAINS`)
carries no OPA or Spring types.

## The JSONB translation (`opa-abac-spring-data`)

`ResidualSpecificationFactory` maps each `Condition` over the `tags` JSONB column (Postgres dialect, JPA
Criteria `function(...)`, bound literals — no SQL strings):

| `Condition` | SQL |
|-------------|-----|
| `tags.region EQ "emea"` | `jsonb_extract_path_text(tags,'region') = 'emea'` |
| `tags.region IN [...]` | `jsonb_extract_path_text(...) IN (...)` |
| `tags.region CONTAINS "emea"` | `jsonb_exists(tags->'region','emea')` (the `?` op) |
| a non-`tags` path (`categoryId`) | `root.get("categoryId")` (intrinsic column) |

**Scalar-vs-array consistency.** The Postgres `?` operator matches a JSONB *string* scalar (string
equality) **and** an array element — so a `CONTAINS` residual matches both a scalar `region="emea"` row and
an array `region=["emea","amer"]` row, **agreeing** with the single-decision Rego (whose `resource_tag_values`
normalizes a scalar to a singleton set). The list and a single-GET decide the **same** rows.

## The rego `filter` rule (the fail-closed boundary)

`category.rego` gains a **`filter`** entrypoint (and a `bulk` rule), additive — `allow` is untouched. Two
deliberate differences from `allow`:

1. **Role-definition-only.** `filter` requires `has_role_definition` and has **no** subject-roles fallback
   (unlike `allow`, which grants read from JWT roles when no role definition is present). A list request
   with no role definition compiles to an unsatisfiable residual → `DENY_ALL` → an **empty list**, never the
   whole table. Modelled on `team.rego`.
2. **Partial-eval-friendly tag match.** A two-body helper (`attr == v` for a scalar, `v in attr` for an
   array) compiles to a clean DNF the translator supports, instead of the single-decision `tags_satisfied`'s
   `is_array`/set-comprehension shape (which doesn't reduce to SQL).

`filter` stays **flat-verb** (`category:read`); coarse category expansion (`READ`/`WRITE`/`TAG`/`GRANT`) is a
later additive retrofit (ADR [[adr/0007-coarse-grained-permission-categories|0007]] / Phase 6.5).

## The adoption recipe (the catalog)

1. The repository extends `JpaSpecificationExecutor<T>` (additive — existing finders unchanged).
2. The list handler builds a query context (subject from the `SecurityContext`, `action=<type>:read`,
   resource **unknown**), resolves the role on the **governing parent**, and calls
   `AbacQueryService.findAuthorized(repo, scope, ctx)` where `scope` is the existing path filter
   (`catalogId`/`categoryId`).
3. **AND, never replace** — the residual is AND-ed with the scope, so no cross-scope row leaks.

```java
@OpaPreAuthorize(action = "category:read", resourceType = "'category'")   // coarse gate (layer 2)
public ResponseEntity<List<Category>> listCategories(UUID catalogId, UUID parentId) {
    requireCatalog(catalogId);
    var entities = categoryListAuthorizer.readable(catalogId, parentId);  // residual cut in SQL (layer 3)
    return ResponseEntity.ok(entities.stream().map(CatalogMapper::toDto).toList());
}
```

## Wiring + the kill-switch

The starter auto-configures `ResidualSpecificationFactory` + `AbacQueryService` (`@ConditionalOnMissingBean`,
gated on `JpaSpecificationExecutor` on the classpath). Properties:

```yaml
opa:
  abac:
    partial-eval:
      enabled: true             # a true kill-switch — off ⇒ coarse path (scope + one allow), never fail-open
      allowlist-fallback: true  # batch re-check for residuals that don't fully reduce to SQL
```

## The load-bearing safety property

**Every failure mode lands on deny or on an exact batch re-check — never on "return everything."** A
compile error → `DENY_ALL` (empty page); a batch error → all-false; an unsupported residual → deny, or (with
the allowlist on) an exact batch re-check over a recognized-conjunct pre-filter; a list with no role
definition → empty. The operator set is small and closed because a mistranslated predicate is a silent data
leak: narrow-but-correct beats wide-but-wrong.

## Proven by

- **`opa test`** (60/60): the `filter`/`bulk` cases, the no-role-definition → empty guard, and
  filter-agrees-with-allow for scalar **and** array tags.
- **Testcontainers ITs** (real Postgres + JSONB): the `ResidualSpecificationFactory` over each operator, and
  `AbacQueryService` returning **different row sets** for two subjects + the AND-with-scope no-leak proof.
- **The e2e filter matrix** ([[E2E-TESTING]]): two tag-gated readers hit the same list endpoint through the
  gateway and get different row sets; an allow-all owner sees all; a stranger with no role definition sees
  none. Run with `scripts/postman/run-filter-matrix.sh`.

## Hierarchy-aware list widening (Slice 5.5-B)

The residual above filters by the row's **own** tags. An inheritable grant on an **ancestor** — which Slice
5.5-A made authorize a single `GET …/{id}` — does not widen the list by itself. Slice 5.5-B closes that gap:
"is this row in an allowed subtree" is a fact about **lineage**, not tags, so the residual stays **tag-only**
and hierarchy widening is a separate, app-built **`subtreeSpec`** OR-ed into the query, with the leaf deny
mirrored as SQL:

```
combined = scope.and( tagResidual.or(subtreeSpec) ).and( notDenied )
```

- **`subtreeSpec`** — "the rows in the governing root's subtree", produced by `SubtreeSpecResolver` **iff**
  the subject's role, resolved once on that root, **inheritably** grants the verb (else empty). Root-only;
  mid-tree per-node grants are Phase 8. It comes from a new additive
  `AncestorResolver.subtreeOf(rootType, rootId) → Specification`: the **ltree** impl pushes a
  `path <@ '<root>'` predicate entirely into SQL (the descendant id set is never materialized); the **CTE**
  impl materializes a `maxDepth`-bounded `id IN (…)` from a downward walk. Both **fail closed to an
  always-false predicate** on any breach.
- **`notDenied`** — `abac_deny IS DISTINCT FROM true` over the tags JSONB, the SQL mirror of the Rego deny.
  AND-ed **outside** the OR so a leaf deny overrides the inherited widening too; a row absent the tag is kept
  (matches Rego's `not denied` on an absent key).
- **Placement is load-bearing:** `subtreeSpec` is OR-ed **inside** `scope.and(...)` so the widening can never
  escape the caller's `catalogId` scope (no cross-catalog leak); `notDenied` is AND-ed **outside** the OR so
  deny can't be re-admitted by the subtree branch.
- **The allowlist-batch path is independently hierarchy-aware:** each per-row `AbacContext` carries the row's
  ancestor chain, so `opaClient.allowAll` decides each row by the same `final_allow = (direct OR inherited)
  AND NOT denied` as a single-GET (`subtreeSpec` is not applied there — it would be redundant).
- **The coarse list gate:** the type-level `@OpaPreAuthorize(<type>:read)` on a list endpoint evaluates
  `allow` with only a resource type (no ancestors), so a subject whose role grants read only on an
  inheritable **ancestor** type would be denied at the gate before the widening runs. A small additive
  `allow` clause (`category.rego`) lets such a subject pass the **coarse** "may you read `<type>` at all"
  gate when its role inheritably grants the verb — the **fine** which-rows cut still happens in SQL. It is
  scoped to a list request (no resource id), so single-resource decisions are unchanged; a true stranger is
  still denied.

The 3-arg `findAuthorized` stays byte-compatible (it delegates with `subtreeSpec = null`); `opa-abac-core`,
the residual model, the operator set, and `RoleDefinition` are untouched. Wiring is opt-in, default-off (the
`SubtreeSpecResolver` bean is gated on `opa.abac.hierarchy.enabled` + an `AncestorResolver`). Pinned by ADR
[[adr/0010-hierarchy-aware-list-filter|0010]]; the single-resource analogue is in
[[HIERARCHICAL-AUTHORIZATION]]. Proven by `HierarchyListFilterIT` (real Postgres: widening, two-subjects,
`notDenied`, AND-with-scope no-leak, re-parent-on-list) and the e2e
`scripts/postman/run-hierarchy-list-matrix.sh`.

## What this slice does NOT do

Action enrichment (Phase 6) · coarse permission categories (Phase 6.5) · ReBAC-in-Rego / mid-tree per-node
grants (Phase 8) · a non-Postgres `JsonPathDialect` · partial-eval result caching · widening a list that does
not already use this partial-eval path (e.g. the product list's plain scoped query — a separate adoption).

## Related

- ADR [[adr/0005-partial-eval-to-jpa-specification|0005]] (the pinned fork) · ADR
  [[adr/0006-three-layer-enforcement-model|0006]] (the three layers — this is the DB layer) · ADR
  [[adr/0010-hierarchy-aware-list-filter|0010]] (the hierarchy-aware list widening above) ·
  [[TWO-LAYER-AUTHORIZATION]] · [[ABAC-AUTHORIZATION]] · [[TAG-BASED-AUTHORIZATION]] ·
  [[HIERARCHICAL-AUTHORIZATION]] · [[E2E-TESTING]] · [[POC-ROADMAP]].
