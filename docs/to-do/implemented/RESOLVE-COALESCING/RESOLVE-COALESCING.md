---
tags:
  - status/done
  - type/index
  - area/abac
  - area/spring
---

# RESOLVE-COALESCING — Slice 7.3 (resolve-path performance)

> **Status: ✅ IMPLEMENTED (2026-07-10, the autonomous run, T1–T6) — measured proof: resolve
> 2/22/102/51 → 1/1/1/2; awaiting `/deep-review` → PR.**
> The pre-publish performance slice that consumes the [[LOAD-TESTING]] findings ledger (root
> `PERFORMANCE.md`): the measured per-request **role-resolve amplification** (2/22/102 identical calls
> per single-GET / 20-row list / 100-row enriched page; list knee at 10 req/s, OPA OOM at 50) is
> eliminated by **coalescing** — a request-scoped memo for duplicate targets + a batch `lookupAll` for
> distinct-root pages — plus the ancestor double-resolve fix and the gateway-timeout/docs tail. Pinned
> by ADRs [[0023-request-scoped-resolution-memoization|0023]] and [[0024-batch-role-resolution|0024]];
> the design is [[00-DESIGN]]; the work list is [[01-DECOMPOSITION]] (T1–T6), QA in
> [[10-QA-TEST-CASES]], the run prompt in [[AUTONOMOUS-IMPLEMENTATION-PROMPT]]. Runs **before** the
> Spring Boot 4 port (`docs/to-do/implemented/SPRING-BOOT-4-PORT/RESEARCH.md`) — semantic change on the
> proven 3.4 rig; the port re-baselines perf once, after both.

## Tickets

| # | Ticket | Module(s) | Status |
|---|--------|-----------|--------|
| **T1** | Multi-root k6 scenario + fixtures + **pre-change baseline** | `scripts/load` | ✅ (baseline: resolve **51**/page, 290 traces) |
| **T2** | Request-scoped memos (role + ancestor) + BPPs + flag | `opa-abac-spring-security` · starter | ✅ (I3: 1 resolve/request; + the deferred-manager wiring fix) |
| **T3** | `lookupAll` SPI + `ResolveTarget` (core) + memo batch integration | `opa-abac-core` · `opa-abac-spring-security` | ✅ (additive; full build green) |
| **T4** | Batch wire: `/internal/effective-roles` + `HttpRoleDefinitionSupplier.lookupAll` | example user-mgmt · example catalog | ✅ (one guarded GET; strict completeness) |
| **T5** | `ActionEnrichmentAdvice` batching pass | `opa-abac-spring-security` | ✅ (one `lookupAll`/page; multi-root bound re-pinned **2** = coarse single + batch) |
| **T6** | Gateway timeout + adopter notes + re-measurement + bounds re-pin + e2e | `infra` · `PERFORMANCE.md` · `scripts/postman` | ✅ (timeout 1000; fleet 14/14 green; ledger re-baselined) |

Critical path: **T1 → T2 → T3 → T4 → T5 → T6** (T1 first *by design* — its baseline must run against
pre-memo code; **T1+T2** are the independently-landable measured headline). See [[01-DECOMPOSITION]]
for per-ticket Goal/Deliverables/Acceptance/What-NOT-to-touch.

## What it is

Every role-resolve in a request hits the same `RoleDefinitionSupplier` bean — the gate, the list
authorizer, and one call per enriched row — almost always for the **identical target** (the governing
root). The slice coalesces the fan-out on two axes:

| Component | Fixes | Mechanism |
|---|---|---|
| **Request-scoped memo** (ADR 0023) | duplicate targets (the measured 2/22/102) | decorators over the `RoleDefinitionSupplier` **and** `AncestorResolver` beans (BPP-wired, one flag `opa.abac.resolve-memo.enabled`, default on) memoizing **all three** tri-state outcomes — "one request, one answer per target"; revocation latency bounded by request duration; pass-through outside a web request |
| **Batch resolve** (ADR 0024) | distinct-root pages (catalogs list: M roots, memo-proof) | `lookupAll` default method + `ResolveTarget` (core, pure JDK); two-state entries, **whole-batch outage**, strict completeness; one guarded `GET /internal/effective-roles`; the enrichment advice batches **unconditionally** |
| **Tail** (docs/config) | the 7.2 resilience findings | APISIX `opa` plugin `timeout:1000` (int ms — was 3000 default: the measured ~3 s outage-deny; 500 was measured too tight for a LOADED OPA — steady-state 403s at 10 req/s); breaker-recovery + OTEL-sampling **adopter notes** (document-only); batch-eval bound **re-pinned to 2** (finisher + affordance — a wrong pin, not wrong code) |

## The pins (from the grill-me; full rationale in [[00-DESIGN]] + the ADRs)

1. **Memoize all three outcomes** — `of` / `empty` / **the outage marker** (re-thrown on replay);
   otherwise a no-role caller's page keeps the full fan-out and a mid-request blip yields
   mixed-snapshot pages.
2. **The staleness contract is one sentence:** a resolve answer is a per-request snapshot; nothing
   survives the request. This is 7.4's audit target.
3. **Batch = two-state entries + whole-batch outage + strict completeness** (a short/extra/malformed
   body never yields partial roles) on a **GET** riding the same `resolveCallGuard` as one call.
4. **Batching is unconditional; the flag governs memoization only.** One flag for both memos.
5. **B2/B3 preserved verbatim** — single-target paths byte-identical; the guard wraps the batch
   exchange as one call; memo hits never touch the guard (the breaker samples real calls only).
6. **Rejected:** merging the list's two batch-evals (layering — ADR 0024), parallel per-row resolves,
   TTL/cross-request caching, per-entry tri-state on the wire, threading roles through signatures.

## Proof (the headline — MEASURED, 2026-07-10; the ledger is root `PERFORMANCE.md`)

- **P1 + I3** — resolve wire calls **2/22/102 → 1** on the same-root scenarios and **51 → 2** on the
  multi-root list (re-pinned: the authorizer's query-time coarse single + ONE response-time batch —
  two questions, two lifecycle points), trace-attributed through the rig (282–650 traces/scenario,
  median==max) + the counting-fake IT budget; batch-eval 2 on category lists (re-pinned), 1 on the
  catalogs list; compile 1. Gate delta +2.28 ms p50; guarded p99 36.8 → 11.0 ms; enrichment p50
  266 → 174 ms.
- **U6** — "one request, one answer per target" survives a supplier that flips answers mid-request.
- **P2 — half proven, half disproven (recorded honestly):** OPA **survives the 50 req/s stage**
  (was OOM-killed; saturation is now fail-closed page-shrinkage + typed denies) — but the knee stays
  at 10 req/s by the p99>1s definition (p99 AT the knee 3.59 s → **1.37 s**): the list is now bound
  by OPA bulk-eval latency, the next frontier. **P3** — multi-root before/after recorded (51 → 2;
  p50 136.7 → 117.1 ms). **P4** — gateway outage-deny p50 **1005 ms** (was 3005), typed 403s,
  recovery 0.36 s.
- **E2** — the full newman fleet green, 14/14 runners on their documented rig postures (+ the new
  catalogs-list `_actions` cut cells E7a/E7b).
- Two latent defects found and fixed by the re-measurement: the advisor's eager manager injection
  (every bean-level decorator silently skipped on the gate path) and `ResilientOpaClient.allowAll`
  retrying mixed verdict blocks (the sentinel is all-false).

## Scope boundaries

- **In:** the two memo decorators + BPPs + flag; `ResolveTarget` + `lookupAll` (core, additive);
  the batch wire pair (internal endpoint + supplier override); the advice batching pass; the
  multi-root k6 scenario + `dddd…` fixtures; the config/docs tail + `PERFORMANCE.md` re-measurement.
- **Out → later:** the SB4 port (next slice; re-baselines perf once, after both); breaker tuning
  (document-only here); any cross-request caching (no revocation story — deliberately unbuilt);
  CI-runs-the-rig (tracked follow-up).
- **Untouched:** all Rego (`opa test` count unchanged); `AbacQueryService`'s finisher;
  `AbacResourceCache`; the single-target resolve paths; `/internal/**` stays unrouted.

## 7.4 hand-forward (recorded in [[00-DESIGN]] so the handoff can't lose them)

1. ADR 0023's staleness contract (the mandatory stale-authorization scrutiny).
2. `/internal/effective-roles` — verify in-network-only reachability.
3. The multi-root load fixtures — registry hygiene (`dddd…` ids, `perf` memberships, clean teardown).

## Related

- ADRs [[0023-request-scoped-resolution-memoization|0023]] + [[0024-batch-role-resolution|0024]] —
  this slice's pins; [[0014-supplier-outage-error-distinct|0014]] (the tri-state preserved),
  [[0017-cross-service-http-resilience|0017]] (the guard the batch rides),
  [[0016-action-enrichment-affordance-metadata|0016]] (the advice contracts preserved),
  [[0021-load-testing-methodology|0021]] (the measurement discipline).
- [[LOAD-TESTING]] + root `PERFORMANCE.md` — the findings ledger this slice consumes.
- [[POC-ROADMAP]] — the Phase-7 route; [[USER-STORIES]] — story **F5**.
