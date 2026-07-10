---
tags:
  - status/active
  - type/decision
  - area/abac
  - area/architecture
  - area/spring
  - area/user-service
---

# ADR 0024 — Batch role resolution (`lookupAll`: two-state entries, whole-batch outage)

**Status:** Accepted (planned — Slice 7.3, [[RESOLVE-COALESCING]])
**Date:** 2026-07-10
**Context tags:** `RoleDefinitionSupplier`, `lookupAll`, `ResolveTarget`, batch, tri-state,
strict completeness, `/internal/effective-roles`, `resolveCallGuard`, `ActionEnrichmentAdvice`

> This ADR pins the **batch-resolve forks** for Slice 7.3. The request-scoped memo
> ([[0023-request-scoped-resolution-memoization|ADR 0023]]) collapses **duplicate** resolve targets —
> but a page whose rows have **distinct governing roots** (the catalogs list: no ancestors, so every
> row is its own root) defeats any duplicate-collapsing memo: M rows = M distinct sequential
> cross-service resolves. The fix is coalescing round-trips: one wire exchange resolving N targets.
> Settled in the same 2026-07-10 grill-me. Pre-1.0 is the last cheap moment for this SPI surface.

## Context

`RoleDefinitionSupplier.lookup` is a strict tri-state ([[0014-supplier-outage-error-distinct|ADR
0014]]): resolved / authoritative-no-role / **throws** on outage — the throw is what stops an outage
from widening access. A batch API must preserve exactly this tri-state without inventing a fourth
state, and it travels as **one HTTP exchange** on an edge that B3 ([[0017-cross-service-http-resilience|
ADR 0017]]) made resilient under the invariant "retry is safe because resolve is a read-only GET."

## Decision

### 1. SPI: a `default lookupAll` on `RoleDefinitionSupplier` + a `ResolveTarget` record (core, pure JDK)

```java
default Map<ResolveTarget, Optional<RoleDefinition>> lookupAll(String userId, Set<ResolveTarget> targets)
```

`ResolveTarget` is a small `(resourceType, resourceId)` record in `opa-abac-core` — no Spring, no
Jackson. The **default implementation loops over `lookup()`**, so the interface stays a
`@FunctionalInterface` and every existing lambda/impl remains valid unchanged. In the loop default,
**any single throw aborts the whole batch** (consistent with §2). An empty target set returns an empty
map without any lookup.

### 2. Entry semantics are **two-state**; the outage is **whole-batch**

The exchange split of the tri-state: *resolved vs no-role* belongs to each entry
(`Optional.of` / `Optional.empty`); the *outage* belongs to the exchange. Any transport failure,
non-200, or unparseable body → `RoleResolutionException` **for the whole batch** — every target is
unknown, every caller applies its existing fail-closed degrade. There is **no per-entry error state**:
a server that cannot compute an entry answers 5xx for the request rather than fabricate a partial
body.

### 3. Strict completeness: the response carries **exactly one entry per requested target**

A missing, extra, or duplicated entry is a **malformed response → whole-batch outage** — the
`allowAll` length-mismatch idiom ("short/mismatched list → omit all") applied to a keyed map. This is
the disprovable pin: a partial body can never silently yield partial roles.

### 4. Wire (example): `GET /internal/effective-roles`, repeated `target=<type>:<id>` params

Staying a **GET** keeps ADR 0017 §3's retry-safety invariant verbatim — no amendment, no pinned-POST
side-effect-free carve-out. Response: `200` with one entry per target (`role` object or explicit
`null` for no-role), **never** `204` (no-role travels in-body per entry). Classification mirrors B2's
strict rules: only a 200-with-complete-body is trusted; 5xx/429/timeout are transient (retried inside
the guard); 4xx and malformed/incomplete 200 are permanent outages. The exchange runs through the
**same `resolveCallGuard`** as the single-target path, as **one** guarded call — one breaker event per
page instead of N. Batch size is bounded by the page-size max, so URL length is trivially safe. The
endpoint stays under `/internal/**` — in-network only, never gateway-exposed.

### 5. The enrichment advice batches **unconditionally**

`ActionEnrichmentAdvice` collects the page's distinct governing roots (pass 1, through the memoized
ancestor path), issues **one `lookupAll`**, then builds the rows×verbs contexts from the returned map
(pass 2). Batching is call-coalescing with identical semantics — one point-in-time exchange — not
caching; it has no staleness story, so it is **not** gated by `opa.abac.resolve-memo.enabled` (a flag
gate would fork the advice into two code paths forever). The per-row degrade ladder is unchanged: a
row's ancestor failure omits that row; a batch outage omits `_actions` for the page (the advice's
existing whole-group degrade); omit-never-fabricate ([[0016-action-enrichment-affordance-metadata|ADR
0016]] §7) holds throughout.

## Considered & rejected

- **Per-entry tri-state on the wire** (the server reports "resolved A, failed B") — buys resilience
  only when the server can resolve some targets while erroring on others within one request
  (realistically a request-poisoning failure anyway), at the cost of a third result state every
  consumer must classify. The fail-closed reasoning stays two-valued per entry.
- **POST with a JSON body of targets** — cleaner for large N, but the retry-safety invariant is pinned
  on "read-only GET"; page-size-bounded target lists fit a GET comfortably. Revisit only if a
  legitimate consumer outgrows URL limits.
- **Parallelizing per-row `lookup` calls** — multiplies burst load on the supplier and the breaker,
  reduces no work, and drags `SecurityContext` propagation across threads into scope.
- **Gating the advice's batching behind the memo flag** — batching has no staleness semantics to opt
  out of; the flag governs memoization only ([[0023-request-scoped-resolution-memoization|ADR 0023]]
  §5).
- **A batch-aware `AbacResourceCache` / merging with the list finisher's batch** — the finisher and
  the affordance batch answer different questions at different lifecycle points (query-time row
  inclusion vs response-time verb map), and the verb sets live on web DTOs the data layer must not see
  ([[0006-three-layer-enforcement-model|ADR 0006]]). The two list batch-evals are the design bound —
  re-pinned to 2, not merged.

## Consequences

- A multi-root page costs **one** resolve exchange instead of M sequential ones; with ADR 0023, the
  full expected bound becomes: resolve wire calls = 1 per request on every measured scenario.
- `RoleDefinitionSupplier` gains its first default method — additive, `@FunctionalInterface`
  preserved; 1.0 freezes this shape.
- The example user-management service gains an internal batch endpoint whose contract (one entry per
  target, 5xx-over-partial) the 7.4 delta review verifies as in-network-only.
- HTTP-backed suppliers that skip the override still work (the default loops) — they simply keep
  per-target round-trips; the memoizing decorator batches around them either way (memo hits excluded,
  misses delegated as one `lookupAll`).
