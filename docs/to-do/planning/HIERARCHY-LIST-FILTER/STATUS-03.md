---
tags:
  - status/done
  - type/project
  - area/abac
  - area/spring-data
---

# STATUS T3 — `AbacQueryService`: 4-arg overload + `notDenied` + hierarchy-aware batch path

> Filled at the T3 checkpoint. One focused commit. ✅ shipped.

## What shipped

- **Additive 4-arg overload** `findAuthorized(repo, scope, queryContext, subtreeSpec)` owning the pure-SQL
  composition: `widened = (subtreeSpec == null) ? tagResidual : tagResidual.or(subtreeSpec)`; then
  `combined = scope.and(widened).and(notDenied())`; `repo.findAll(combined)`. The widening is OR-ed
  **inside** `scope.and(...)` (cannot escape scope); `notDenied` is AND-ed **outside** the OR (overrides the
  widening too).
- **3-arg `findAuthorized` preserved byte-compatible** — it now delegates to the 4-arg with
  `subtreeSpec = null` (→ exactly today's behavior). Every existing caller/test compiles + behaves
  identically.
- **`notDenied()` Specification** — `jsonb_extract_path_text(tags,'abac_deny') IS DISTINCT FROM 'true'`,
  expressed as `(value IS NULL) OR (value <> 'true')` (the exact IS-DISTINCT-FROM semantics). A row absent
  the tag → `NULL` → kept (matches the Rego `not denied` on an absent key); an `abac_deny=true` row →
  excluded from **both** branches. Reuses the JSONB Criteria machinery — no new dialect/operator.
- **Hierarchy-aware allowlist-batch path** — `withResource` now resolves each candidate row's ancestor
  chain (`ancestorResolver.ancestorsOf`) and builds the **4-arg** `Resource(type,id,attributes,ancestors)`,
  so `opaClient.allowAll` decides each row by the same `final_allow = (direct OR inherited) AND NOT denied`
  as a single-GET. `subtreeSpec` is **not** applied on the batch path (redundant with the per-row decision).
  Fail-closed: a per-row resolution failure → **empty** ancestors → that row decided on its **direct** grant
  only; a short/all-false `allowAll` still drops rows.

## Tests

- **`AbacQueryServiceTest`** — the 7 existing cases still green (3-arg byte-compat) + 6 new (U6–U12):
  - U6/U7 — 3-arg ≡ 4-arg-with-null;
  - U8 — 4-arg with a `subtreeSpec` composes a non-null spec, no batch (pure-SQL);
  - U10 — DENY_ALL residual + `subtreeSpec` still widens via the OR;
  - U11 — batch per-row `AbacContext.Resource` carries the row's ancestors (`ArgumentCaptor` on `allowAll`);
  - U12 — a per-row resolver failure → **empty** ancestors (direct-only); a short batch list drops rows.
- `./gradlew :opa-abac-spring-data:test` + `:opa-abac-spring-boot-starter:test` **green** (incl. the shipped
  `AbacQueryServiceIT` against real Postgres — the 3-arg ctor unchanged; the starter `ApplicationContextRunner`
  with the new `ObjectProvider<AncestorResolver>` injection).

## Architecture review + refactor (the ★ gate)

- **Fail-closed (load-bearing):** confirmed structurally — `scope.and(widened).and(notDenied())` with
  `widened = tagResidual.or(subtreeSpec)`. The widening is OR-ed **inside** `scope.and(...)` (no scope
  escape); `notDenied` AND-ed **outside** the OR (deny overrides the widening). `notDenied` is never silently
  `TRUE` for a real deny. Batch path only removes rows; resolution failure → empty ancestors.
- **Boundary/additivity:** 3-arg byte-compatible (existing tests + IT unchanged-green). The constructor
  gained a **nullable** `AncestorResolver` (hierarchy off → tag-only batch, fail-closed). `opa-abac-core`
  **untouched** (git-confirmed); residual model / `ResidualSpecificationFactory` / operator set /
  `RoleDefinition` untouched.
- **Build-breaker handled in this commit:** the constructor change. Because the 3-arg ctor is preserved,
  **no test construction site broke** — the only wiring update was the starter `abacQueryService` bean,
  which now injects `ObjectProvider<AncestorResolver>` (present only when hierarchy is enabled).
- **Pattern reuse:** the JSONB `cb.function("jsonb_extract_path_text", …)` style matches `JsonPathDialect`;
  the batch finisher + AND-with-scope match `mx-4e6071`.
- **Refactor applied:** none structural — the design held.

## Integration / e2e

- The composition's actual row-set behavior (the OR widening, the `notDenied` narrowing, the no-leak
  AND-with-scope, re-parent-on-list) is proven against **real Postgres** in **T4** (`HierarchyListFilterIT`).
  T3's own tests are mock-repo unit (the composed `Specification` is captured, not executed).

## Decisions

- **The `AncestorResolver` constructor arg is nullable, not required.** The `AbacQueryService` bean is wired
  whenever JPA is present (`DataFilteringAutoConfiguration`), but the `AncestorResolver` bean exists only
  when hierarchy is enabled. A required arg would force hierarchy on. Nullable → the batch path is
  hierarchy-aware when a resolver is present, direct-grant-only (fail-closed) when absent — the right
  opt-in default-off posture.

## Commit

`feat(spring-data): AbacQueryService 4-arg overload + notDenied + hierarchy-aware batch` on
`feature/void3110/hierarchy-list-filter`.
