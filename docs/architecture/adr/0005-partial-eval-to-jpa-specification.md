---
tags:
  - status/active
  - type/architecture
  - area/spring-data
  - area/abac
  - area/opa
---

# ADR 0005 — Partial evaluation → JPA `Specification`: residual conditions in the SQL `WHERE`

**Status:** Accepted (implemented — Phase 5, [[DATA-FILTERING]])
**Date:** 2026-06
**Context tags:** spring-data, list filtering, OPA partial evaluation, ABAC, fail-closed

> Authored **up front** as part of the Phase-5 decomposition (per the ADR convention in the
> [[adr/README|ADR README]]). It pins the central fork of the [[DATA-FILTERING]] slice; the design
> detail lives in [[DATA-FILTERING/00-DESIGN|00-DESIGN]], the rationale and rejected alternatives here.

## Context

Every authorization decision shipped so far (ADR 0003 role-definition-driven `@OpaPreAuthorize`, ADR
0004 tag-gated grants) answers **one question at a time**: "may this subject do X to *this* resource?"
That is correct for a single GET/POST. It is the **wrong** model for a **list** endpoint, where the
honest question is "of the N rows in the table, *which* may this subject see?" The catalog's list
endpoints today run one coarse type-level `:read` check and return **every** row — so the moment a
grant is conditioned on the row (the tag grant from ADR 0004, now over a collection), the list is
wrong: it returns rows the subject must not see.

The two naive fixes are both bad: **fetch-all-then-filter-in-memory** (O(table) I/O, leaks counts,
breaks pagination) and **one OPA call per row** (O(N) round-trips). We need the row filter pushed into
the database, decided by the same policy that decides single resources.

## Decision

Use OPA's **Compile API** (`POST /v1/compile`) to **partially evaluate** the policy with the *subject*
known and the *resource* declared **unknown** (`unknowns: ["input.resource"]`). OPA returns the
**residual** — the conditions, in disjunctive normal form, the row must satisfy. The library translates
that residual into a Spring Data JPA **`Specification<T>`** over the existing `tags` JSONB column
(GIN-indexed since ADR's domain-model slice) and pushes it into the SQL `WHERE` clause. A **batch**
decision call (`allowAll`) finishes any residue that does not reduce to SQL, on the (now small)
candidate set, in one round-trip.

Concretely:

1. **Two additive, abstract `OpaClient` methods** — `compile(AbacContext) → PartialResult` and
   `allowAll(List<AbacContext>) → List<Boolean>`. `allow` and the whole `@OpaPreAuthorize` path are
   **byte-for-byte unchanged**. The methods are **abstract, not `default`**, so a custom `OpaClient`
   cannot silently inherit a fail-**open** filter.
2. **A neutral residual model in `opa-abac-core`** (Spring-free): `PartialResult{Decision
   ALLOW_ALL|DENY_ALL|CONDITIONAL, List<Conjunction>}` as DNF (OR-of-ANDs);
   `Condition{path, Operator EQ|NEQ|IN|CONTAINS, value}`. Mapping — **corrected 2026-08-06; the
   original text here had the first two outcomes inverted, which as written would be a fail-open
   whole-table leak (the shipped parser was never wrong — see the boxed correction in
   [[PARTIAL-EVALUATION-FILTERING]])**: an **empty `result` (`{}`) means UNSATISFIABLE → `DENY_ALL`**;
   an explicit **empty conjunction (`queries: [[]]`) means unconditionally true → `ALLOW_ALL`**;
   non-empty conditions → `CONDITIONAL`.
3. **`ResidualSpecificationFactory` in `opa-abac-spring-data`** translates the residual to a
   `Specification`: `EQ`/`IN` over `jsonb_extract_path_text(tags,'k')`; `CONTAINS` (array tag) via the
   `?` existence op (`jsonb_exists(tags->'k','v')`) — the same scalar-vs-array normalize as the ADR-0004
   Rego match; an intrinsic column (e.g. `categoryId`) via `root.get(...)`. `ALLOW_ALL` → no predicate;
   `DENY_ALL` → `cb.disjunction()` (always-false).

> The match a role expresses in Rego for a single resource (ADR 0004 `some`/`every`) and the residual
> OPA compiles for a list are **the same policy** evaluated two ways — single-decision vs partial. One
> policy, two entry points (`allow` and a `filter` rule).

**Fail-closed is the load-bearing invariant.** A compile/transport/parse failure → `DENY_ALL` (empty
page). A batch failure → all-false. An expression the translator does not recognize → `DENY_ALL` (or,
with `allowlistFallback` on, an **exact batch re-check** — the fallback fetches all *scoped* candidates
and batch-decides each row; the untranslatable conjuncts do not pre-narrow the fetch). **No code path
may return more rows on an error than on success.** The operator set is deliberately small and closed:
a mistranslated predicate is a silent data leak, so narrow-but-correct beats wide-but-wrong.

> **Amendment (2026-08-06, foreign-type folding).** One exception to "an unrecognized expression
> poisons the residual": a DNF disjunct guarded by `eq(<type>, input.resource.type)` against a
> *different* definite string type is identically false for rows of the queried type and is **dropped**
> (narrowing-safe), unrecognized siblings included. A residual whose disjuncts *all* fold away is
> reported **not fully supported** (→ the batch re-check), because an all-foreign `filter` residual
> cannot speak for policy-side inheritance. See [[PARTIAL-EVALUATION-FILTERING]] §"Multi-type roles
> fold".

## Considered options

| Option | Why not |
|--------|---------|
| **Fetch-all then filter in memory** | The anti-pattern this exists to replace — O(table) I/O, leaks row counts, breaks pagination. |
| **One `allow` call per row** | O(N) round-trips; a 200-row page = 200 decisions. Batch + partial-eval is the point. |
| **Batch only (no partial-eval)** | Still fetches the whole table before filtering; can't push the cut into SQL. Batch is the *finisher* on survivors, not the primary filter. |
| **A general residual→SQL translator** (every operator, arbitrary AST) | Huge surface, easy to get subtly wrong; a mistranslation silently leaks data. Ship a **small closed operator set** + a conservative fallback. |
| **Emit native SQL strings** | Loses dialect portability, invites injection. Use JPA Criteria `function(...)` with bound literals. |
| **A new `opa-abac-data-filter` module** | The translation belongs with the JPA layer in `opa-abac-spring-data`; a fourth module is premature. |
| **`compile`/`allowAll` as `default` methods returning allow-all** | A custom `OpaClient` would inherit a **fail-open** filter. Abstract methods force a deliberate, fail-closed impl. |
| **Fail-open on a compile error (return all rows)** | A transport/parse failure must never *widen* visibility. Compile failure → `DENY_ALL`, exactly as the single-decision path fails closed. |
| **Hierarchical ancestor-walk in this slice** | A different mechanism (load-then-walk-parents), larger; Phase 5 filters by the row's *own* tags. The ADR-0004 `CategoryAuthorizer` load-then-check stub stays until its own slice. |

## Consequences

- **Good:** the library's headline differentiator — authorization pushed into the SQL `WHERE` clause; the
  one comparable OSS project (`opa-data-filter`) is unmaintained. The single-decision and list paths share
  one policy. `opa-abac-core` stays Spring-free (the residual model carries no JPA import). The change to
  `OpaClient` is purely additive.
- **Cost:** the residual translator is **Postgres-only** for this slice (a `JsonPathDialect` seam is noted,
  a second dialect is not built). The closed operator set means a policy expressing an unsupported predicate
  degrades to deny-or-batch, not to a richer SQL filter — an intentional safety/coverage trade.
- **Follow-on:** action **enrichment** (ADR-to-come / Phase 6) is the first real consumer of the batch
  `allowAll` primitive. Hierarchical ancestor-walk filtering and ReBAC-in-Rego (Phase 8) build on the same
  compile path.

## Related
- ADR 0006 (the three-layer enforcement model this is the DB layer of) · ADR 0004 (the tag grant the
  residual generalizes to a list) · ADR 0003 (the role definition that is the known half of the compile input)
- [[DATA-FILTERING]] (the planned slice) · [[DATA-FILTERING/00-DESIGN|00-DESIGN]] (the mechanism) ·
  [[RESEARCH-AUTOTAG-AND-FILTERING]] (§3, the study background) · [[POC-ROADMAP]] (Phase 5)
