---
tags:
  - status/done
  - type/project
  - area/abac
  - area/spring
  - area/opa
---

# STATUS — T3: List-path write-through into the cache (all `findAuthorized` paths)

**Status:** ✅ DONE

## What shipped

- **`AbacQueryService` gains an optional `AbacResourceCache resourceCache`** collaborator via a **new
  5-arg constructor** `(OpaClient, ResidualSpecificationFactory, PartialEvalSettings, AncestorResolver,
  AbacResourceCache)`. The existing 3-arg and 4-arg constructors are unchanged; the 4-arg now delegates to
  the 5-arg with `resourceCache = null` → **byte-identical** to before when no cache is wired.
- **The write-through caches every post-filter survivor** keyed `(abacResourceType(), abacResourceId())`,
  on **all survivor-returning paths of both the unpaged and paged `findAuthorized`**:
  - unpaged: kill-switch coarse-allow, allowlist-batch (`batchFilter` survivors), pure-SQL;
  - paged: kill-switch coarse-allow, allowlist (`sliceInMemory` content), pure-SQL.
  Implemented as two thin `cacheSurvivors(List)` / `cacheSurvivors(Page)` wrappers around the existing
  survivor expressions (so the decision paths are untouched) delegating to a single
  `cacheEach(Iterable)` (no-op when no cache). **The empty/denied/fromError return points are NOT wrapped**
  — nothing is cached when nothing is returned.
- The write caches the **same instance** the query returned (no re-resolve → no attribute drift).
  **Denied/dropped rows are never written** (the allowlist `batchFilter` drops `false` rows before
  `cacheSurvivors` sees them) — the cache stays an authorized-snapshot store, consistent with the gate's
  allow-only write.

## Tests

- **U10 ✅** `:opa-abac-spring-data:test` green (7 new write-through cases + the full existing suite incl.
  the real-Postgres ITs, no regression):
  - pure-SQL path → both survivors cached `(category,a),(category,b)`;
  - allowlist path → only the `true` survivors cached (`a`,`c`); **the dropped row `b` is never cached**;
  - kill-switch path → the coarse-allow survivor cached;
  - paged pure-SQL → the page content rows cached;
  - **cache absent → no write, no NPE, return value byte-identical**;
  - fromError → empty result, **nothing cached**.
- The **no-second-SELECT IT (I1)** is T5's `ActionEnrichmentListIT` (it needs the advice + a real cache +
  Postgres) — T3's contract is unit-proven here; the query-count proof lands in T5.

## Architecture review + refactor

Ran the ★ gate. **One small refactor applied** (deduplication); the rest passes clean.

- **Refactor:** the two `cacheSurvivors` overloads had a duplicated write loop → extracted a single
  private `cacheEach(Iterable<? extends AbacDataObject>)` (the null-guard + loop in one place). Re-ran the
  U10 suite → green.
- **Fail-closed:** the write-through wraps **only** survivor-returning expressions; the deny / `fromError`
  / `Page.empty` return points are unwrapped → nothing cached on those (proven by
  `writeThrough_fromError_cachesNothing`). It can never fabricate or widen — it only writes rows being
  returned.
- **Security:** a denied row can never enter the cache (allowlist drops `false` rows pre-cache;
  `writeThrough_allowlistPath_cachesOnlySurvivors` proves it) → the advice can never enrich an
  unauthorized row into looking authorized. The cache is still **never read by any decision** (the gate
  invariant holds; the advice reads it only for attributes).
- **Concurrency/idempotency:** caches the same instance the query returned (no drift between the rows
  shown and the cached snapshot); a pure additive write after materialization; never changes which rows
  are returned (every test asserts the returned ids unchanged) — matches the decide/act-on-the-same-
  snapshot invariant.
- **Wiring:** the `resourceCache` field's producer is the 5-arg constructor (the starter wires it in T4,
  only when enrichment is enabled). Non-happy path tested (absent → no-op; empty → nothing cached).
- **Boundary/additivity:** no new module dependency (`opa-abac-spring-data` already depends on
  `opa-abac-core`, where the cache interface now lives); the residual composition, the allowlist decision,
  pagination/count, the unsorted-`Pageable` guard, and the kill-switch are all **byte-identical** (the full
  existing suite passed unchanged); the write-through only *adds* a cache write.
- **Pattern reuse:** the nullable-collaborator-via-constructor-overload mirrors the existing
  `ancestorResolver` opt-in exactly (mx-ecca43).

## Integration / e2e

The list-path no-second-SELECT proof (I1) is delivered in **T5** (`ActionEnrichmentListIT`, real Postgres
+ a query-count hook) — it needs the advice (T2), the starter wiring (T4), and an `Enrichable` catalog DTO
(T5) to exercise read-after-write end-to-end. T3's write is unit-verified here.

## Decisions

- **Six survivor sites, not three.** The decomposition says "all three paths"; with the paged overload
  there are in fact **six** survivor-returning sites (pure-SQL / allowlist / kill-switch × unpaged + paged).
  All six are wrapped so a paged list enriches exactly as an unpaged one does (the slice includes both
  list and page). No new decision — purely covering every return point.

## Commit

`feat(spring-data): list-path cache write-through for action enrichment` — to follow.
