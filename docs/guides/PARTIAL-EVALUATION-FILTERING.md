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

## What this slice does NOT do

Hierarchical ancestor-walk authorization (a Category inheriting its Catalog's grant — it filters by the
row's *own* tags) · action enrichment (Phase 6) · coarse permission categories (Phase 6.5) ·
ReBAC-in-Rego (Phase 8) · a non-Postgres `JsonPathDialect` · partial-eval result caching.

## Related

- ADR [[adr/0005-partial-eval-to-jpa-specification|0005]] (the pinned fork) · ADR
  [[adr/0006-three-layer-enforcement-model|0006]] (the three layers — this is the DB layer) ·
  [[TWO-LAYER-AUTHORIZATION]] · [[ABAC-AUTHORIZATION]] · [[TAG-BASED-AUTHORIZATION]] · [[E2E-TESTING]] ·
  [[POC-ROADMAP]].
