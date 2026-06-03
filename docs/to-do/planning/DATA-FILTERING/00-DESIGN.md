---
tags:
  - status/planned
  - type/architecture
  - area/spring-data
  - area/abac
  - area/opa
---

# Data filtering — design (partial evaluation + batch evaluation)

> Phase 5 of [[POC-ROADMAP]]. The single-decision spine ([[LIBRARY-SPINE]]) answers "may this subject
> do X to *this* resource?". This slice answers the **list** question — "of N rows, which may they
> see?" — without N round-trips or a full-table-scan-then-filter, by pushing authorization **into the
> SQL `WHERE` clause** via OPA partial evaluation, with a **batch** call for the residue that doesn't
> reduce to SQL. Index: [[DATA-FILTERING]]. Mechanism background: [[RESEARCH-AUTOTAG-AND-FILTERING]] §3.

## The problem, concretely

`GET /api/v1/catalogs/{id}/categories` today does one coarse type-level `category:read` check
(`@OpaPreAuthorize`) and then returns **every** category under the catalog. That is correct for "is
this subject allowed to read categories *at all*", but it does **not** filter rows by the resource's
own attributes. The moment a grant is conditioned on the row — "you may read categories tagged
`region=emea`" (exactly the [[TAG-DICTIONARY]] grant, but now over a *collection*) — the coarse check
is wrong: it returns rows the subject must not see, or (if we flipped to deny) hides rows they may.

The naive fixes are both bad:

1. **Fetch-all-then-filter in memory** — pulls the whole table over the wire, then drops most of it.
   O(table) I/O, leaks row counts, defeats pagination.
2. **One OPA call per row** — O(N) round-trips; a 200-row page is 200 decisions.

The right answer: ask OPA **once** to *partially evaluate* the policy with the subject known and the
**row unknown**, get back the residual condition on the row, and let Postgres apply it.

## The two mechanisms

### A. Partial evaluation → JPA `Specification` (the headline)

OPA's **Compile API** (`POST /v1/compile`) evaluates a query as far as it can with the given `input`,
treating a declared set of **`unknowns`** as symbolic, and returns the **residual** — the set of
conditions, in disjunctive normal form, that the unknowns must satisfy for the query to hold. We
declare the *resource* (the row) unknown and the *subject / role_definition* known. The residual is
exactly "the WHERE clause for this subject".

```
            input = { subject, action, role_definition }   (KNOWN)
            unknowns = [ "input.resource" ]                 (the row — SYMBOLIC)
                              │
   POST /v1/compile  { query: "data.category.filter == true", input, unknowns }
                              │
                              ▼
        residual (DNF):  region == "emea"  OR  sensitivity in {"public"}
                              │  PartialResult { CONDITIONAL, [Condition…] }
                              ▼
   ResidualSpecificationFactory → Specification<CategoryEntity>
                              │   (predicate over the `tags` JSONB column)
                              ▼
   categoryRepository.findAll(catalogScope.and(residual))   →  only the rows the subject may see
```

**Three outcomes** collapse the residual into a decision the `Specification` layer understands:

| Compile result | `PartialResult.Decision` | `Specification` |
|----------------|--------------------------|-----------------|
| residual is **trivially true** (no conditions; the subject may see all) | `ALLOW_ALL` | no predicate added (match all) |
| residual is **empty / unsatisfiable** (policy can never hold for any row) | `DENY_ALL` | always-false predicate (`1=0`) → match none |
| residual is a **set of conditions** | `CONDITIONAL` | OR-of-ANDs predicate over the row's columns/tags |

This is the single most differentiating piece in the repo. The one comparable OSS project
(`opa-data-filter`) is unmaintained; a clean, Spring-Data-native version is the artifact (mx-15ee3e).

### B. Batch evaluation → post-fetch allowlist (the residue)

Some conditions don't reduce to a SQL predicate — e.g. a per-row check that needs a PIP lookup, or a
shape the residual translator deliberately doesn't support (kept narrow on purpose; see "Translation"
below). For those rows, after the SQL pre-filter narrows the candidate set, we make **one** batch OPA
call — `allowAll(List<AbacContext>)` over OPA's bulk-input shape — and drop the rows that come back
`false`. This is the **optional second layer**: SQL does the heavy cut at the DB; batch does the exact
per-row finish on the (now small) candidate set. One round-trip, not N.

> **Why both, not just batch?** Batch alone still fetches the whole table before filtering. Partial
> eval alone can't express conditions that aren't SQL-expressible. Together: the DB does what it does
> best (cheap, indexed set reduction over JSONB), OPA does what only it can (the exact policy on the
> survivors). The two layers compose; either is usable alone.

## Core changes (`opa-abac-core`, stays Spring-free)

### `OpaClient` gains two methods (additive — `allow` is unchanged)

```java
public interface OpaClient {
    boolean allow(AbacContext context);                       // unchanged (LIBRARY-SPINE)

    // Phase 5 — partial evaluation. Declares the resource unknown, calls /v1/compile,
    // returns the residual. Fails CLOSED to PartialResult.denyAll().
    PartialResult compile(AbacContext context);

    // Phase 5 — batch decision. One round-trip for N contexts; result[i] = decision for context[i].
    // Fails CLOSED: any transport/parse error → all-false (a list of `false` of the same length).
    List<Boolean> allowAll(List<AbacContext> contexts);
}
```

Both have **default methods** on the interface? **No** — they're abstract, but `HttpOpaClient` is the
only production impl and a test stub implements all three. (Keeping them abstract makes a custom
`OpaClient` consciously decide its filtering story rather than silently inheriting an allow-all-shaped
default — fail-closed by construction.)

### The residual model (neutral, no OPA types leak out)

```java
// PartialResult — the compiled residual in a shape the spring-data layer can translate
// without knowing anything about OPA's AST.
public record PartialResult(Decision decision, List<Conjunction> clauses) {
    public enum Decision { ALLOW_ALL, DENY_ALL, CONDITIONAL }
    public static PartialResult allowAll() { … }   // clauses ignored
    public static PartialResult denyAll()  { … }   // the fail-closed value
    // CONDITIONAL holds clauses as DNF: (c0 AND c1) OR (c2) OR …
}

// A single AND-group of conditions (one disjunct of the DNF).
public record Conjunction(List<Condition> conditions) {}

// One leaf condition over a row attribute. `path` is the dotted resource attribute path
// (e.g. "tags.region", "categoryId"); `operator` is a small closed set; `value` is a literal
// or a list (for IN / array-membership).
public record Condition(String path, Operator operator, Object value) {
    public enum Operator { EQ, NEQ, IN, CONTAINS }   // CONTAINS = JSONB array membership
}
```

- **DNF (OR-of-ANDs)** matches what OPA's compile output gives (a set of `support` rules, each a
  conjunction of expressions; the set is the disjunction). The translator maps it straight to
  `predicate = OR( AND(conds) )`.
- The `Operator` set is **deliberately small and closed**. An expression the parser does not recognize
  is **not** silently dropped — it forces the whole `PartialResult` to a conservative fallback (see
  "Unsupported residuals" below). Narrow-but-honest beats wide-but-wrong.

### Compile-API call (`HttpOpaClient.compile`)

- Same JDK `HttpClient` + Jackson + `OpaClientConfig` as `allow` (mx — the fail-closed JDK client).
- Request to `<baseUrl>/v1/compile`:
  ```json
  {
    "query": "data.<policyPrefix>.<resourceType>.filter == true",
    "input": { "subject": …, "action": …, "role_definition": … },
    "unknowns": ["input.resource"]
  }
  ```
  The query path reuses `PolicyPathResolver` (per-type), suffixed with the **filter entrypoint** rule
  name (`filter`) — see "Rego" below. `input.resource` is **omitted** from `input` (it's the unknown).
- Parse OPA's `result.queries` / `result.support` into the DNF `PartialResult`:
  - **`result` absent or `{}`** → query is unconditionally true → `ALLOW_ALL`.
  - **`result.queries == []`** (empty array) → query can never hold → `DENY_ALL`.
  - **`result.queries` non-empty** → each query is a conjunction of expressions; translate the
    `eq`/`equal`/`internal.member_2` etc. terms whose operands reference `input.resource.…` into
    `Condition`s → `CONDITIONAL`.
- **Fail closed:** non-200 / IOException / timeout / malformed / an expression we cannot parse →
  `PartialResult.denyAll()`. Logs WARN without the token. (Tradeoff noted under "Unsupported residuals".)

### Batch (`HttpOpaClient.allowAll`)

OPA has no first-class "N inputs, N decisions" endpoint, so we POST to the **filter/decision rule with
a list input** and let a tiny bulk rule fan out — OR (simpler, chosen) iterate `allow` over the list
**client-side but on one connection** via the JDK `HttpClient` with HTTP/2 multiplexing. **Decision:**
ship the **list-input bulk rule** (`data.<type>.bulk` → `[allow per element]`) so it's genuinely one
round-trip and the per-row evaluation stays in the policy. Fail-closed: any error → `List.of(false × N)`.

## Spring-data changes (`opa-abac-spring-data`)

### `ResidualSpecificationFactory` — the translator

```java
public final class ResidualSpecificationFactory {
    // Build a Specification from a compiled residual. ALLOW_ALL → conjunction()(=no-op);
    // DENY_ALL → disjunction()(=always false); CONDITIONAL → OR-of-AND predicate.
    public <T> Specification<T> from(PartialResult residual);
}
```

Translation of one `Condition` over the **`tags` JSONB column** (the GIN-indexed column from
[[DOMAIN-MODEL-FOUNDATION]]) — Postgres-dialect, via JPA Criteria `function(...)`:

| `Condition` | SQL (Postgres JSONB) | Criteria |
|-------------|----------------------|----------|
| `tags.region EQ "emea"` | `jsonb_extract_path_text(tags,'region') = 'emea'` | `cb.equal(cb.function("jsonb_extract_path_text", String.class, tagsPath, lit("region")), "emea")` |
| `tags.region IN ["emea","amer"]` | `… IN ('emea','amer')` | `extractText(...).in(values)` |
| `tags.region CONTAINS "emea"` (array tag) | `tags -> 'region' ? 'emea'` (the `?` existence op) | `cb.isTrue(cb.function("jsonb_exists", Boolean.class, tagsArrow('region'), lit("emea")))` |
| a non-`tags` path, e.g. `categoryId EQ <uuid>` | `category_id = ?` | `cb.equal(root.get("categoryId"), value)` — an **intrinsic column**, not JSONB |

- **`ALLOW_ALL`** → `Specification.where(null)` (no predicate; the caller's own scope filter — e.g.
  `catalogId = ?` — still applies).
- **`DENY_ALL`** → `(root, q, cb) -> cb.disjunction()` (an empty OR = always false = match none).
- The factory is **dialect-aware but Postgres-only for this slice** (documented; the catalog rig is
  Postgres). A pluggable `JsonPathDialect` seam is noted as a follow-up, not built.
- **Scalar-vs-array tags:** `EQ`/`IN` use `jsonb_extract_path_text` (scalar); `CONTAINS` uses the `?`
  existence op (array membership) — mirroring the [[TAG-DICTIONARY]] `resource_tag_values` normalize.

### `AbacQueryService` — the seam

```java
public class AbacQueryService {
    // Build the context (subject from SecurityContext + role_definition + action + resourceType),
    // compile it, translate to a Specification, and run it through a JpaSpecificationExecutor.
    // Optional post-fetch allowlist (batch) for residuals flagged not-fully-SQL.
    public <T> List<T> findAuthorized(
        JpaSpecificationExecutor<T> repo,
        Specification<T> scope,             // the caller's own scoping (e.g. catalogId = ?)
        AbacContext queryContext);          // subject + action + resourceType, resource UNKNOWN
}
```

Keeps the controller thin: build the query-context, call `findAuthorized`, return the rows. The
spring-data module already depends on `spring-data-jpa`; this seam is the natural place a future
**facade** slots in (mx-b17da2 left the seam open for exactly this).

## Example + infra changes

- **Repositories** add `JpaSpecificationExecutor<…>` (`CategoryRepository`, `ProductRepository`,
  `CatalogRepository`) — additive, no behavior change to existing finders.
- **List handlers** (`CategoryController.listCategories`, `ProductController.listProducts`,
  `CatalogController.listCatalogs`) build the query-context and delegate to `AbacQueryService`, AND-ing
  the residual with the existing scope filter (`catalogId` / `categoryId`). The per-type `:read`
  `@OpaPreAuthorize` stays as the coarse "may read this *type* at all" gate; the residual is the
  *which-rows* layer **inside** it — two-layer, same spirit as the gateway↔app split.
- **`category.rego`** gains a **`filter`** rule — a partial-eval-friendly entrypoint whose body
  references `input.resource.tags[...]` as the unknown so compile returns row residuals. It reuses the
  `tags_satisfied` shape from [[TAG-DICTIONARY]] but written to leave `input.resource` symbolic. The
  single-decision `allow` rule is untouched. A `bulk` rule (list input → list of decisions) backs
  `allowAll`. `opa test` keeps existing cases green + adds compile/filter cases (`opa eval --partial`).
- **`deploy.sh`** — no new env needed (`/v1/compile` is the same OPA on the same base URL); document
  the partial-eval toggle.

## Starter wiring

- New beans, `@ConditionalOnMissingBean`: `ResidualSpecificationFactory`, `AbacQueryService`.
- `OpaAbacProperties` gains a `partialEval` group: `enabled` (default `true`),
  `allowlistFallback` (default `true` — run the batch post-filter for not-fully-SQL residuals).
- When `partialEval.enabled=false`, `AbacQueryService.findAuthorized` degrades to "apply scope only +
  a single coarse `allow` check" (back-compat with the pre-Phase-5 behavior) — so the toggle is a true
  kill-switch, never a fail-open.

## Considered & rejected

| Option | Why rejected |
|--------|--------------|
| **Fetch-all then filter in memory** | O(table) I/O, leaks counts, breaks pagination — the anti-pattern this slice exists to replace. |
| **One `allow` call per row** | O(N) round-trips; a page of 200 = 200 decisions. Batch + partial-eval is the point. |
| **Skip partial-eval; do batch only** | Still fetches the whole table before filtering; can't push the cut into SQL. Batch is the *finisher*, not the primary filter. |
| **A general residual→SQL translator (all operators, arbitrary AST)** | Huge surface, easy to get subtly wrong (a mistranslated predicate = a silent data leak). Ship a **small closed operator set** over the known `tags` JSONB + intrinsic columns; anything else → conservative fallback. Narrow-but-correct. |
| **Translate to native SQL strings** | Loses dialect-portability and invites injection. Use JPA Criteria `function(...)` with bound literals. |
| **A new `opa-abac-data-filter` module** | The translation belongs with the rest of the JPA layer in `opa-abac-spring-data`; a fourth module is premature. |
| **Make `compile`/`allowAll` default methods returning allow-all** | A custom `OpaClient` would silently inherit a **fail-open** filter. Abstract methods force a deliberate, fail-closed implementation. |
| **Fail-open on a compile error (return all rows)** | A transport/parse failure must never *widen* visibility. Compile failure → `DENY_ALL` (empty page), exactly as the single-decision path fails closed. |
| **Hierarchical ancestor-walk now** | Different mechanism (load-then-walk-parents), larger; the tag demo's `CategoryAuthorizer` stub stays until its own slice. Phase 5 filters by the row's *own* tags. |

## Unsupported residuals (the honest edge)

If compile returns an expression the parser doesn't recognize (an operator outside the closed set, a
reference that isn't `input.resource.tags.*` or a known intrinsic column):

- **Default (safe): `DENY_ALL`** for that whole `PartialResult` — the row set is empty rather than
  wrong. Logged WARN with the unparsed expression shape (no token, no PII).
- **With `allowlistFallback=true`:** instead of denying, fall through to **batch** — apply only the
  *recognized* conjuncts as a coarse SQL pre-filter (or no pre-filter if none recognized), then run the
  batch `allowAll` over the candidates to get the exact answer. This is the escape hatch that keeps a
  policy we can't fully compile from silently emptying the list, **without** ever fetching-all blindly
  (the recognized conjuncts still cut the candidate set first).

This is the design's load-bearing safety property: **every failure mode lands on deny or on an exact
batch re-check — never on "return everything".**

## Test posture

- **Core unit (no WireMock):** in-process `com.sun.net.httpserver.HttpServer` stub returns canned
  `/v1/compile` bodies — trivially-true → `ALLOW_ALL`; empty `queries` → `DENY_ALL`; a one-condition
  and a DNF body → `CONDITIONAL` with the expected `Condition`s; malformed / 500 / timeout / unparsable
  expression → `DENY_ALL`. `allowAll`: bulk body round-trip + fail-closed all-false. One test pins the
  `/v1/compile` request shape (`query`, `unknowns:["input.resource"]`, `input` without `resource`).
- **spring-data unit:** `ResidualSpecificationFactory` over a mock `CriteriaBuilder`/`Root` (or a
  lightweight Criteria capture) — `EQ`/`IN`/`CONTAINS` produce the expected `function(...)` calls;
  `ALLOW_ALL`→no predicate; `DENY_ALL`→disjunction. Plus a **Testcontainers** IT that actually runs the
  generated `Specification` against real Postgres + JSONB rows and asserts the row set.
- **rego:** `opa test` stays green; new cases prove `data.category.filter` compiles to the expected
  residual for a tag-gated role and to unconditional-true for an unrestricted role
  (`opa eval --partial --unknowns input.resource`).
- **e2e:** the list-filtering matrix — two subjects, same endpoint, different row sets; an allow-all
  subject sees all; assert the cut is in SQL (row counts, not just response filtering).
- **ITs unchanged-green:** `CatalogCrudIT` / `ProductConcurrencyIT` stay as-is under the permissive
  test profile; the new filtering path is opt-in on the list endpoints.

## Deferred (noted, not built this slice)

Hierarchical ancestor-walk authorization · ReBAC-in-Rego (Phase 7) · `@AutoTag` auto-population · a
pluggable non-Postgres `JsonPathDialect` · partial-eval result caching · a partial-eval CI job · pushing
the residual through pagination/count queries (the `Specification` already composes with `Pageable`; a
dedicated authorized-`Page` helper is a follow-up).
