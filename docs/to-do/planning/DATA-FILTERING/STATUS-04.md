---
tags:
  - status/done
  - type/project
  - area/spring-data
  - area/abac
---

# STATUS — T4: AbacQueryService seam + optional post-fetch allowlist (spring-data)

> Filled in at the T4 checkpoint during the autonomous run. See [[01-DECOMPOSITION]] T4 and the
> per-ticket loop in [[AUTONOMOUS-IMPLEMENTATION-PROMPT]].

**Status:** ✅ done

## What shipped

- **`AbacQueryService`** (`dev.dmitriikonovalov.opaabac.data.filter`) —
  `findAuthorized(JpaSpecificationExecutor<T> repo, Specification<T> scope, AbacContext queryContext)` where
  `<T extends AbacDataObject>` (the bound lets the allowlist build per-row contexts):
  1. `compile(queryContext)` → residual; `ResidualSpecificationFactory.from(...)` → `authzSpec`;
  2. `repo.findAll(scope.and(authzSpec))` — **AND-ed with** the caller's scope, never replacing it;
  3. allowlist finish: when the residual is **flagged not-fully-SQL** *and* `allowlistFallback` is on, fetch
     the scoped candidates and `allowAll(...)` over per-row contexts, dropping `false` rows.
  - `partialEval.enabled=false` kill-switch → coarse path (one `allow` check, then scope-only) — a true
    kill-switch, deny → empty, **never fail-open**.
  - `PartialEvalSettings(enabled, allowlistFallback)` record (`defaults()` = both on) — the starter binds its
    `partialEval` properties onto it in T5.
- **Core (additive):** `PartialResult` gains a `fullySupported` flag + a `PartialResult.unsupported()`
  factory (a fail-closed `DENY_ALL` **flagged** not-fully-SQL). The 2-arg `(decision, clauses)` constructor is
  preserved (defaults `fullySupported=true`), so T1/T3 are byte-compatible. `CompileResponseParser` now
  returns `unsupported()` (not plain `denyAll()`) when it hits an untranslatable term — so the allowlist
  escape hatch can engage; a clean empty result stays a fully-supported `denyAll()`.

## Tests

`./gradlew :opa-abac-spring-data:test` + `:opa-abac-core:test` green.

- **`AbacQueryServiceTest`** (mock repo + stub client, **U20–U24**): `ALLOW_ALL`→scope only;
  `DENY_ALL`→empty; `CONDITIONAL`→`scope.and(authzSpec)` captured, batch **not** called; allowlist ON +
  flagged→`allowAll` drops `false` rows; allowlist OFF→deny, no batch; `enabled=false`→coarse `allow` path,
  **no `compile`**; coarse deny→empty (no `findAll`).
- **`AbacQueryServiceIT`** (real Postgres via Testcontainers, **I2**): two subjects (gated emea vs apac),
  same table, same call → **different, disjoint row sets**; plus an explicit **AND-with-scope** proof (authz
  emea ∧ scope apac → empty, proving the residual composes with the scope rather than replacing it).
- **Core:** added `failClosed_onUnsupportedOperator` now asserts `fullySupported()==false`, and
  `emptyResult_isFullySupportedDeny` asserts a clean empty result stays `fullySupported()==true`.

## Architecture review + refactor

- **Core change additive — verified.** Security module untouched; the 2-arg `PartialResult` constructor is
  preserved, so the flag is invisible to existing callers.
- **AND, never replace — verified.** The only `findAll` with an authz predicate uses `scope.and(authzSpec)`
  (line 80); the IT proves a cross-scope row cannot leak.
- **Never fail-open — verified.** The kill-switch denies → `List.of()` *before* any fetch; the allowlist
  engages only on `!fullySupported() && allowlistFallback()`, and a short/mismatched batch result (the
  client's all-false-on-error) drops rows.
- One **design improvement over the plan** (kept): `findAuthorized` is bounded `<T extends AbacDataObject>`
  so per-row contexts can be built for the allowlist — the plan's bare `<T>` couldn't. No other refactor.

## Integration / e2e

`AbacQueryServiceIT` is the I2 proof (real Postgres, two subjects, different rows). Full e2e through the
gateway is T7.

## Decisions recorded

- **The `fullySupported` flag is how the allowlist escape hatch stays fail-closed.** The parser collapses an
  untranslatable residual to `DENY_ALL` *flagged* not-fully-SQL; with the allowlist off it simply denies
  (empty), with it on the service does an **exact batch re-check** over the scoped candidates — never a wider
  set, never a blind fetch-all. This realizes the design's load-bearing safety property: every failure mode
  lands on deny or on an exact batch re-check. Recorded as a Mulch pattern.

## Commit

`feat(data-filtering): T4 AbacQueryService seam + post-fetch allowlist + kill-switch` — _(SHA at commit)_
