---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/spring
---

# RESOLVE-COALESCING — QA test cases

> Concrete cases; each becomes a ticket's *Acceptance* in [[01-DECOMPOSITION]]. U = unit, I =
> integration (Testcontainers Postgres — never H2; in-process `com.sun.net.httpserver.HttpServer`
> stub — no WireMock), E = e2e (asserts the actual cut, not just response shape), P = perf proof
> (trace-attributed / harness-recorded per [[0021-load-testing-methodology|ADR 0021]] — report-only,
> validity-gated). Contracts under test: [[0023-request-scoped-resolution-memoization|ADR 0023]] +
> [[0024-batch-role-resolution|ADR 0024]].

## Unit (U*)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| U1 | Role memo, three outcomes | With request attributes bound: a counting delegate returning `of(role)` / `empty` / throwing `RoleResolutionException` is hit **once** per key; repeats replay the same outcome — including the **re-thrown** outage. The memo replays, never reinterprets. | T2 |
| U2 | Memo key separation | Distinct `(userId, resourceType, resourceId)` never collide; `resourceId=null` (type-level) is its own key, distinct from every instance key of the same type. | T2 |
| U3 | Cross-request isolation | Two simulated requests (fresh request attributes each): the delegate is re-hit in the second — nothing survives a request. | T2 |
| U4 | No-request pass-through | With **no** request attributes bound: every call reaches the delegate (zero memoization) and the decorator **never throws from its own bookkeeping** — outcomes pass through byte-identically. | T2 |
| U5 | BPP conditional states | `ApplicationContextRunner`: flag default/on + web classes → beans wrapped (memo `instanceof`); `opa.abac.resolve-memo.enabled=false` → the raw app bean, unwrapped; no double-wrap on refresh. | T2 |
| U6 | **Supplier-flip consistency (the ADR 0023 disprover)** | A delegate that answers `of(A)` then `of(B)` within one request: every consumer sees **A** — one request, one answer per target. | T2 |
| U7 | Ancestor memo, both degrades | Chain memoized (`ancestorsOf` counted once per `(type,id)`); a memoized `AncestorResolutionException` **re-throws** on replay; the query-path caller still degrades to direct-only and the advice-path caller still omits the row — each catches the replayed throw itself. | T2 |
| U8 | `lookupAll` default loop | The default method returns exactly one entry per requested target (strict completeness on the happy path); empty set → empty map with **zero** `lookup` calls; any single `lookup` throw aborts the **whole batch**. | T3 |
| U9 | Memo × batch integration | Memoized keys are **excluded** from the delegated set (counting delegate); misses go down as **one** `lookupAll`; all results memoized; a whole-batch outage memoizes the **outage marker for every missed target** — a later single `lookup` of one replays the throw with the delegate untouched; the merged map is strictly complete. | T3 |
| U10 | Batch wire classification | `HttpRoleDefinitionSupplier.lookupAll` via the in-process stub, exchange-counted (**one** request per batch): 200+complete → map (`null`→`empty`); **missing entry** → `RoleResolutionException`; **extra/duplicate entry** → same; 200-blank/unparseable → same (permanent); 5xx/429 → retried inside the guard, exhausted → `RoleResolutionException`; **4xx → permanent, exactly 1 attempt**; breaker-open → `RoleResolutionException` without an exchange; empty target set → `Map.of()` with **zero** HTTP; target parts URL-encoded. | T4 |

## Integration (I*)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| I1 | Batch endpoint (user-mgmt, Testcontainers) | `GET /internal/effective-roles` with mixed targets (one resolved via a real team membership, one authoritative no-role) → `200`, **exactly one entry per requested target**, `role:null` for the no-role entry, never `204`; malformed target (`no-colon`, bad UUID) and missing `userId` → `400`. | T4 |
| I2 | Advice batching + degrade ladder | Real MVC + counting supplier + OPA stub, 20-row **multi-root** page: exactly **one** `lookupAll` per page; one row's ancestor failure omits **that row only** (others enriched); a `lookupAll` outage omits **all** `_actions` while the response stays `200` with full rows (affordance never blocks). | T5 |
| I3 | Whole-request resolve budget (same-root) | Counting supplier + counting ancestor resolver through the real gate + list finisher + enrichment on a same-root list: **exactly 1 wire-touching role resolve** for the whole request and **≤1 `ancestorsOf` per `(type,id)`** — the IT-level form of the amplification claim. | T2 |

## E2E (E*)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| E1 | Catalogs-list affordance cut (multi-root, through APISIX) | A team member's catalogs list carries `_actions` on their catalogs (the batch path live end-to-end); the contrast subject (e.g. the `gated-writer` / a non-member) shows the cut — different `_actions`/visibility for the same endpoint. Asserts the cut, not shape. (Verify an existing cell covers this; add to the appropriate matrix if absent.) | T6 |
| E2 | Full regression fleet | **Every** existing `scripts/postman/run-*.sh` matrix green with the slice at defaults — the advice rewrite touches `_actions` on every list surface, so the whole fleet is the contract-preservation proof. `opa test` count unchanged (zero Rego). | T6 |

## Perf proof (P*) — report-only, validity-gated (ADR 0021)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| P1 | Amplification bounds, re-measured | Jaeger-attributed, constant across sampled traces: single GET resolve **2→1**; 20-row same-root list **22→1**; 100-row enriched page **102→1**; multi-root list **M→1** (one `lookupAll` exchange); batch-eval **re-pinned 2** on lists (finisher + affordance — two questions, two lifecycle points), 1 on single GET; compile 1. | T6 |
| P2 | Ceiling ladder re-run | The **25 req/s stage passes** and **OPA survives the 50 req/s stage without OOM** (7.2 state: knee at the first 10 req/s stage; OOM at 50). Anything beyond is reported, not gated. | T6 |
| P3 | Multi-root before/after | T1's pre-change baseline (resolve = M per page) vs the post-T5 re-run (resolve = 1) — same scenario, same fixtures, artifacts kept under `scripts/load/results/`. | T1 + T6 |
| P4 | Gateway outage deny latency | `fault-opa` timeline re-run after the plugin `timeout:500`: deny p50 ≈ 0.5 s (was ~3.0 s), all failures still **typed 403**, recovery still sub-second. | T6 |

## Headline proof

1. **P1 + I3** — the resolve fan-out is gone at both altitudes: the trace-attributed wire counts hit
   the pinned bounds through the real rig, and the counting-fake IT pins the same budget forever at
   test level.
2. **U6 + U9/U10** — the two new contracts hold under fire: "one request, one answer per target"
   survives a flipping supplier, and no batch that is short, broken, or errored ever yields a partial
   role set — it lands whole-batch on the existing fail-closed degrades.
