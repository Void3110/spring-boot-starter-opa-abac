---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
---

# STATUS — T6: Example adoption — JpaSpecificationExecutor, filtered list handlers, rego filter entrypoint (example + infra)

> Filled in at the T6 checkpoint during the autonomous run. See [[01-DECOMPOSITION]] T6 and the
> per-ticket loop in [[AUTONOMOUS-IMPLEMENTATION-PROMPT]].

**Status:** ✅ done

## What shipped

- **`infra/opa/policies/category.rego`** — a **`filter`** entrypoint (partial-eval friendly) + a **`bulk`**
  rule, both purely additive (the `allow` rule + `tags_satisfied` are byte-for-byte unchanged). `filter` is
  **role-definition-only** (`has_role_definition` first, **no** subject-roles fallback) and **flat-verb**
  (`category:read`). Its `filter_tags_satisfied` uses a **two-body** `filter_key_satisfied` —
  `attr == v` (scalar) + `v in attr` (array) — so it matches the same rows the single-decision `allow`
  does, and compiles to a clean DNF residual (`eq` + `member_2(literal,ref)`) the translator supports.
  `bulk := [allow per item]` over `input.items`.
- **`CategoryRepository`** extends `JpaSpecificationExecutor<CategoryEntity>` (additive; finders unchanged).
- **`CategoryListAuthorizer`** (example-app code on the library's public `AbacQueryService`) — resolves the
  role on the **governing catalog** (`lookup(subject, "catalog", catalogId)`, like `CategoryAuthorizer`),
  builds the query context (resource unknown), and calls `findAuthorized(repo, scope, ctx)` with the
  `catalogId` (+ `parentId`) path scope **AND-ed** in. Unauthenticated / no-role-def → empty list.
- **`CategoryController.listCategories`** now delegates to `CategoryListAuthorizer.readable(...)`; the coarse
  `@OpaPreAuthorize(category:read)` stays as the type-level gate (layer 2); the residual is the which-rows
  cut (layer 3).
- **Core (the one cross-module change this ticket needed):** `CompileResponseParser` now distinguishes the
  two `internal.member_2` operand orderings — `member_2(resourceRef, {literals})` → `IN`;
  `member_2(literal, resourceRef)` → **`CONTAINS`** (the `?` existence op, matches scalar **and** array). A
  negated membership → unsupported.

## Tests

- **`opa test infra/opa/policies/` → 60/60** (49 pre-existing + 11 new). New `filter`/`bulk` cases:
  unrestricted reads; tag-gated scalar match; tag-gated **array** match; tag-gated miss; **filter agrees
  with allow** (scalar + array); **U27 no-role-definition → filter false** (the fail-open guard) with the
  contrast that `allow` *does* grant the same read; filter requires the read permission; `bulk` positional
  `[true,false]`; `bulk` empty → `[]`.
- **Core unit:** added `conditional_containsFromMembership` (member_2 literal-left → `CONTAINS`);
  existing IN case still green.
- **`./gradlew build` green** — all modules + example + OpenAPI codegen + **`ddl-auto: validate` clean**
  (no schema change). `CatalogCrudIT` (incl. the list endpoint) + `ProductConcurrencyIT` unchanged-green
  under the permissive test profile (its stub `compile → allowAll()` returns all rows).
- **Compile-API probe (manual, against running OPA 1.10.1):** the tag-gated role compiles to the
  `CONTAINS` residual; a **no-role-definition input compiles to `{}` → DENY_ALL** (the leak guard, live).

## Architecture review + refactor

The audit-flagged ticket — all four footguns confirmed closed:

- **Fail-open list leak — CLOSED.** `filter` is role-definition-only; `opa test` U27 + the live Compile-API
  probe both prove a missing role definition → empty, never the whole table. The contrast test shows `allow`
  *would* have granted that read (so the fallback really was dropped).
- **AND, not replace — CONFIRMED.** `CategoryListAuthorizer` passes a `catalogId`(+`parentId`) scope to
  `findAuthorized`, which `scope.and(authzSpec)`s it; no bare `findAll(residual)`. Role resolved on the
  governing parent.
- **Flat-verb — CONFIRMED.** No category tokens in the rules (only a deferral comment).
- **Additive — CONFIRMED.** `git diff` shows **zero deletions** in `category.rego`; the `allow` rule + the
  `@OpaPreAuthorize` security module are untouched; no DB/migration change; no OpenAPI change.
- **One substantive refactor (the parser CONTAINS side):** surfaced when the IT/`opa test` showed the
  scalar-vs-array consistency gap; resolved per the maintainer's choice of **full consistency** — the
  `filter` residual now maps to `CONTAINS` (scalar+array), so list and single-GET agree. Re-tested green.

## Integration / e2e

`./gradlew build` (boot + `opa test` + ITs) is the integration proof; the full gateway e2e matrix is T7.

## Decisions recorded

- **The maintainer chose full scalar+array consistency** for the list filter (over scalar-only). The
  mechanism: a partial-eval-friendly `filter` tag match (two-body: scalar `==` + array `in`) compiling to a
  DNF that the parser maps to `EQ` + `CONTAINS`; the Postgres `?` operator matches both, so the list and a
  single-GET decide the same rows. Recorded as a Mulch pattern (the partial-eval-friendly `filter` rule +
  the member_2 operand-side → CONTAINS-vs-IN rule).

## Commit

`feat(data-filtering): T6 example adoption — filtered list handler + category.rego filter/bulk` — _(SHA at commit)_
