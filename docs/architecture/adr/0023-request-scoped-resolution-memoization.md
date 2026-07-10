---
tags:
  - status/active
  - type/decision
  - area/abac
  - area/architecture
  - area/spring
---

# ADR 0023 — Request-scoped resolution memoization ("one request, one answer per target")

**Status:** Accepted (planned — Slice 7.3, [[RESOLVE-COALESCING]])
**Date:** 2026-07-10
**Context tags:** `RoleDefinitionSupplier`, `AncestorResolver`, request scope, memoization, staleness,
fail-closed, `BeanPostProcessor`, `opa.abac.resolve-memo.enabled`

> This ADR pins the **memoization forks** for Slice 7.3: a request-scoped memo over the two resolution
> SPIs whose per-request amplification the 7.2 baseline measured (resolve **2/22/102** identical calls
> per single-GET / 20-row list / 100-row enriched page — [[0021-load-testing-methodology|ADR 0021]]'s
> attribution tables, `PERFORMANCE.md` §3). Scope was settled in a planning interview (grill-me
> 2026-07-10). The **batch** half of the slice is [[0024-batch-role-resolution|ADR 0024]].

## Context

Every role-resolve call in a request hits the same `RoleDefinitionSupplier` bean — the gate, the
type-level manager, the list authorizer, and one call per enriched row — and within one request these
calls target the **same** `(userId, resourceType, resourceId)` almost always (the governing root).
Likewise a list request resolves each row's **ancestor chain twice** (the query path's per-row
`withResource` and the enrichment advice's `prepareRow` both call the same underlying
`AncestorResolver`). The measured consequence: the filtered-list path saturates at 10 req/s and OPA is
OOM-killed at 50 req/s — an order of magnitude below the single-GET path.

Any caching of an authorization artifact raises the staleness question. The constraint this ADR
operates under: **fail-closed is load-bearing**, and the repo's posture is that authorization state is
re-derived per decision. The narrowest cache that fixes the measured amplification is one whose
lifetime is a single request.

## Decision

### 1. A memoizing decorator at each SPI seam, wrapping the **bean**, wired by a `BeanPostProcessor`

Two decorators, one per SPI:

- **Role memo** — implements `RoleDefinitionSupplier`, lives in `opa-abac-spring-security` as the
  sibling of `RequestAttributesResourceCache` (same storage idiom: request attributes via
  `RequestContextHolder`).
- **Ancestor memo** — implements `AncestorResolver`. It lives in the **starter** (`autoconfigure`
  package): `opa-abac-spring-data` has no spring-web dependency and must not gain one for this, and
  `opa-abac-spring-security` cannot see `AncestorResolver` (sibling modules) — the starter is the only
  module that sees both the SPI and the request-attributes storage. Because the starter's
  `AncestorChainSupplier` binding delegates to the same bean, one decorator covers **both** the query
  path and the enrichment path.

Both are wired by `BeanPostProcessor`s in the starter (the `resilientOpaClientDecorator` precedent),
guarded `@ConditionalOnClass(RequestContextHolder)`. Wrapping the **bean** — not the library's
injection points — is load-bearing: app-side consumers that inject the supplier directly (the
example's list authorizers) must hit the same memo. Decoration order composes with B3:
`memo(app supplier(CallGuard inside))` — a memo hit never touches the guard, so the breaker sees at
most one real call per key per request.

### 2. All **three** tri-state outcomes are memoized — including the outage

Per `(userId, resourceType, resourceId)` key (role memo) / `(type, id)` key (ancestor memo), the first
outcome of the request is stored and replayed:

- `Optional.of(role)` / the resolved chain — the perf headline;
- `Optional.empty()` (authoritative no-role) — without it, a **no-role** caller's 100-row page still
  fans out 102 calls: a deny-path DoS shape;
- the **outage** (`RoleResolutionException` / `AncestorResolutionException`) — stored as a marker and
  **re-thrown** on repeat lookups in the same request.

Memoizing the throw is what makes the contract crisp: **one request sees exactly one resolve answer
per target.** Without it, a supplier blip mid-page yields rows enriched under the pre-blip role, rows
omitted, and rows resolved again post-recovery — one page, two role answers. Each caller keeps its own
degrade rule when the memoized throw replays (gate → deny; advice → omit the row/page; query path →
direct-grant-only) — the memo replays outcomes, it never reinterprets them.

### 3. The staleness contract, stated plainly

**A resolve answer is a per-request snapshot. A mid-request role change — including a revocation —
takes effect at the next request boundary.** The staleness window is bounded by request duration
(milliseconds under normal operation), and nothing survives the request: entries live as request
attributes, which die with it. This is the entire staleness surface the 7.4 delta security review must
audit; there is no TTL, no cross-request store, no invalidation protocol to get wrong.

### 4. Outside a web request: pure pass-through

No `RequestContextHolder` attributes bound (async executors, schedulers, non-web callers, plain unit
tests) → the decorators delegate every call, memoizing nothing and never throwing on their own — the
`RequestAttributesResourceCache` degrade language verbatim ("callers lose the reuse, never the
decision"). The "one request, one answer" contract holds exactly when there is a request.

### 5. One flag: `opa.abac.resolve-memo.enabled`, default `true`, governs memoization only

One knob, one axis — the flag covers **both** memos (a supported half-state would double the test
matrix for no adopter benefit). Default-on because the memo is the fix for a measured defect; shipping
it off would document a knee at 10 req/s and disable the cure. `false` restores per-call resolution
(snapshot-freshness semantics), **not** pre-7.3 call counts — the enrichment advice's batch collection
([[0024-batch-role-resolution|ADR 0024]]) is unconditional code.

## Considered & rejected

- **Threading a resolved role through call paths** (resolve once in the gate, pass it down) — touches
  every signature on the path, misses app-side consumers, and cannot help the advice (which runs after
  the handler returns). The decorator is transparent to all call sites.
- **Memoizing only positive results** — leaves the no-role page at full fan-out (the deny-path DoS
  shape) and leaves mixed-snapshot pages possible on outage recovery.
- **Not memoizing the outage** — allows intra-request inconsistency (two role answers in one page) and
  hammers a struggling supplier ~100× through the guard per request. Rejected: a fully-degraded page
  is preferable to a mixed-snapshot page, and request lifetimes are milliseconds.
- **A TTL / cross-request cache** — a real staleness window with an invalidation problem, exactly what
  the fail-closed posture forbids without a revocation story. Out of scope by design.
- **Riding the ancestor chain through `AbacResourceCache`** (the snapshot carries its chain) — changes
  the cache's value shape, and forces the advice to inherit the query path's failure semantics (the
  two callers deliberately degrade differently). The second decorator preserves both.
- **Two flags (role memo / ancestor memo separately)** — two half-states nobody asked for; the axis is
  "request-scoped resolution memoization."

## Consequences

- The measured 2/22/102 same-target resolves collapse to **1 wire call per request**; the expected
  amplification bounds are re-pinned accordingly (`PERFORMANCE.md`, Slice 7.3 acceptance).
- **Intra-request consistency becomes a tested claim** (a supplier that flips answers mid-request is
  pinned to the first answer — the disproving test for this ADR).
- Memo hits bypass the B3 guard: the resolve breaker samples only real calls (at most one per key per
  request) — strictly fewer breaker events, no semantic change.
- The 7.4 delta security review gains a named target: this ADR's staleness contract (§3) and the
  outage-marker semantics (§2).
- Revocation latency is request-bounded — adopters with a stricter requirement disable the flag and
  pay the measured amplification.
