---
tags:
  - status/planned
  - type/design
  - area/abac
  - area/spring
---

# 7.3 RESOLVE-COALESCING — settled design (grill-me 2026-07-10)

> **What this is.** The phase-① settled design for Slice 7.3, produced by a grill-me over the
> [PERFORMANCE.md](../../../../PERFORMANCE.md) findings ledger (7.2 baseline, 2026-07-08). Every fork
> below is **decided**; `/decompose` turns this into the ticket package + the two ADRs. Ordering
> context: 7.3 runs **before** the Spring Boot 4 port (semantic change lands on the proven 3.4 rig;
> the port re-baselines PERFORMANCE.md once, after both).

## Problem (measured, not assumed)

- Every role-resolve call in a request hits the same SPI; the gate, the list authorizer, and each
  enriched row resolve independently → **2 / 22 / 102 identical resolves** per single-GET /
  20-row-list / 100-row-enriched request (Jaeger-attributed, constant across samples).
- The list path saturates at **10 req/s** (p99 3.59 s) and OPA is **OOM-killed at 50 req/s** — an
  order of magnitude below the single-GET path. This is resolve amplification, not the residual.
- **Memo-proof gap found during the grill:** the measured scenarios are same-root (categories under
  one catalog). A **catalogs list** page has a *distinct governing root per row* (`prepareRow`:
  empty ancestors → the row is its own root) → M distinct sequential cross-service resolves that no
  duplicate-collapsing memo can merge. The 7.2 harness never measured this shape.
- Hidden cost not in the outbound-op table: each row's **ancestor chain is resolved twice per list
  request** (query path `AbacQueryService.withResource` + advice path `prepareRow`) — in-process DB
  work, 2×N per page, invisible to Jaeger's wire counts.
- The "batch-eval 2 vs pinned 1 — EXCEEDED" verdict is a **wrong pin, not wrong code**: the two
  evals are two different questions at two lifecycle points (the allowlist finisher at query time —
  it holds the 7.0.5 list↔GET agreement invariant — and the affordance batch at response time).

## Scope

**In:**
1. **Request-scoped resolve memo** (library) — the headline fix for duplicate targets.
2. **Batch resolve seam** (library SPI + example wire impl) — the fix for distinct-root pages.
3. **Ancestor memo** (library) — kills the 2×N double-resolve, same decorator family.
4. **Enrichment-advice batching pass** (library) — one `lookupAll` per page.
5. **Config/docs tail** — gateway plugin timeout tune (rig), breaker-recovery + trace-sampling
   adopter notes (docs-only), bounds re-pin.
6. **Harness extension** — multi-root catalogs-list k6 scenario + fixtures, before/after.

**Out (decided, do not re-litigate):**
- Merging the two list batch-evals (finisher + enrichment) — **rejected: layering.** The finisher
  must run at query time (pagination depends on survivors); verb sets live on web DTOs the data
  layer must not see (ADR 0006 separation). Saves one ~1–3 ms round-trip; not worth the coupling.
- Parallelizing per-row resolves — rejected: multiplies supplier burst load, no work reduction,
  drags SecurityContext propagation into scope.
- Tuning the resolve breaker's open-state duration — docs-only (see tail below).
- Any cross-request caching. Staleness window is **one request**, nothing longer.

## Settled forks

### F1 — Memo semantics (ADR 0023 material)
- **Memoize all three tri-state outcomes** per `(userId, resourceType, resourceId)`:
  `Optional.of` (resolved), `Optional.empty` (authoritative no-role — without this, a no-role
  caller's page still fans out 102 calls: a deny-path DoS shape), and the **outage**
  (`RoleResolutionException` → marker, re-thrown on repeat lookups in the same request).
- **ADR-level contract: "one request sees exactly one resolve answer per target."** Intra-request
  consistency is a correctness improvement (no mixed pre/post-blip pages), and the flip side is
  pinned explicitly: a mid-request role change (incl. revocation) takes effect at the next request
  boundary — **revocation latency is bounded by request duration.**
- Outside a web request: **pure pass-through** (no memoization, never a throw) — the
  `RequestAttributesResourceCache` degrade language verbatim.

### F2 — Memo placement & wiring
- Decorator class in `opa-abac-spring-security`, sibling of `RequestAttributesResourceCache`
  (entries as request attributes via `RequestContextHolder`).
- Wired by a **`BeanPostProcessor` in the starter wrapping the app's `RoleDefinitionSupplier`
  bean** — the `resilientOpaClientDecorator` precedent. Bean-level (not injection-point) wrapping is
  load-bearing: the example's own `CatalogListAuthorizer`/`CategoryListAuthorizer` inject the bean
  directly and must hit the same memo. Order composes: memo(app supplier(CallGuard inside)) — a memo
  hit never touches the guard; the breaker sees ≤1 real call per key per request.
- **Kill-switch `opa.abac.resolve-memo.enabled`, default `true`** — governs memoization only (see
  F4); one flag covers both the role memo and the ancestor memo (one knob, one axis:
  request-scoped resolution memoization).

### F3 — Batch resolve contract (ADR 0024 material)
- **SPI:** `default Map<ResolveTarget, Optional<RoleDefinition>> lookupAll(String userId,
  Set<ResolveTarget> targets)` on `RoleDefinitionSupplier`; `ResolveTarget` =
  `(resourceType, resourceId)` record. Default implementation loops over `lookup()` —
  `@FunctionalInterface` and every existing impl stay valid. In the loop default, any single throw
  aborts the whole batch.
- **Entry semantics: two-state** (`of` = resolved, `empty` = authoritative no-role). No per-entry
  error state exists.
- **Outage is whole-batch:** transport failure, non-200, unparseable body →
  `RoleResolutionException` for all targets; every caller fails closed (advice omits `_actions` for
  the whole page — its existing degrade). Server side mirrors: can't compute → 5xx, never a
  fabricated partial body.
- **Strict completeness pin (disprovable):** the returned map contains exactly one entry per
  requested target; missing/extra entries = malformed = whole-batch outage (the `allowAll`
  length-mismatch idiom).
- **Wire (example):** `GET /internal/effective-roles?userId=…&target=<type>:<id>&target=…` —
  staying a GET keeps ADR 0017 §3's retry-safety invariant verbatim; response `200` with one entry
  per target (`role` or explicit `null`), never `204`; rides the **same `resolveCallGuard`** (one
  breaker event per page instead of N). Batch size bounded by the page-size max → URL length safe.
  Internal-only: `/internal/**` never gateway-exposed (standing constraint).

### F4 — Advice batching + ancestor memo
- **Advice flow:** pass 1 — per row, fetch ancestors (memoized) → derive governing root → collect
  distinct roots; **one `lookupAll(userId, distinctRoots)`**; pass 2 — build rows×verbs contexts
  from the returned map. Per-row failure semantics unchanged (row's ancestor failure omits that
  row; batch outage omits all rows).
- **Batching is unconditional code, not flag-gated.** It is call-coalescing with identical
  semantics (one point-in-time exchange), not caching. `resolve-memo.enabled=false` restores
  snapshot-freshness semantics, **not** pre-7.3 call counts; before/after measurement compares
  against the recorded 7.2 baseline artifacts (cross-commit — PERFORMANCE.md's existing framing).
- **Ancestor memo:** second request-scoped decorator, same BPP pattern, on the **`AncestorResolver`
  bean** — the starter's `AncestorChainSupplier` binding delegates to that same bean, so one
  decorator covers the query path *and* the advice path. Memoizes **both** the chain and the
  `AncestorResolutionException` (re-thrown on repeat); each caller keeps its own degrade rule
  (query → direct-grant-only; advice → omit row). Corollary: the filter and the enrichment can
  never see two different chains for one row in one request.

### F5 — Config/docs tail (findings 2/3 + sampling)
- **Gateway plugin timeout — tune the rig + document:** add `timeout: 500ms` (attribute name
  verified at decompose against APISIX 3.11 docs) to the `opa` plugin block in
  `infra/apisix/init-routes.sh` (currently unset → 3 s default, matching the measured 3,005 ms
  outage-deny). Semantics unchanged (already denies typed); only the wait shortens. PERFORMANCE.md
  §4 note: gateway edge is timeout-bound, library edge is breaker-bound; tune against your OPA p99.
- **Breaker recovery lag — document-only:** the ~9–10 s is `waitDurationInOpenState` + half-open
  pacing, a deliberate stability-vs-freshness tradeoff. PERFORMANCE.md adopter note naming the R4j
  knobs + a pointer comment at the example's config site. No config change.
- **Span amplification — adopter note with a concrete sampler** (e.g. `parentbased_traceidratio`
  at a low ratio), expanding the §3 corollary into executable advice.
- One docs/config ticket; no library code.

## Acceptance (the claims under proof)

1. **Trace-attributed bounds** (Jaeger, constant-across-samples like 7.2):

   | scenario | resolve (wire) | batch-eval | compile |
   |---|---|---|---|
   | single GET | 2 → **1** | 1 | — |
   | 20-row same-root list | 22 → **1** | **2** (re-pinned from 1; finisher+enrichment rationale) | 1 |
   | 100-row enriched page | 102 → **1** | 2 | 1 |
   | multi-root catalogs list (M rows) — NEW | M → **1** | 2 | 1 |

   Ancestor + role call counts additionally asserted at IT level with counting fakes
   (per list request: `ancestorsOf` ≤ 1 per `(type,id)`; wire-touching role lookups = 1).
2. **Before/after for the new scenario via ticket ordering:** the k6 catalogs-list scenario +
   fixtures (M≈50 catalogs, `dddd…` ids, one team each, `perf` membership) land as an **early
   ticket**; its baseline runs against the pre-memo branch code; the closing ticket re-runs it.
3. **Ceiling re-run, pinned conservative knee claim:** the **25 req/s stage passes** and **OPA
   survives the 50 req/s stage without OOM** (previous: knee at stage 1, OOM at 50). Everything
   beyond is reported, not gated. Steady latencies reported as deltas; no absolute-ms gates
   (ADR 0021 posture).
4. **Functional:** full `./gradlew test` (never targeted-only) + new ITs — memo three-outcome
   semantics, key separation, cross-request isolation, no-request pass-through, flag-off =
   per-call behavior; batch strict-completeness violation → whole-batch outage; **mid-request
   supplier-flip IT** (directly disproves "one request, one answer"); ancestor-memo invocation
   counts with both degrade semantics. E2e: **full newman suite** (the advice rewrite touches
   `_actions` on every list surface). At decompose: verify an existing cell asserts `_actions` on
   the *catalogs* list (the multi-root path); add one if absent.
5. **Perf protocol:** fresh rig + fresh trace store, quiesced machine, REPS=3 for re-run headline
   scenarios (PERFORMANCE.md "Rerun it" recipe). PERFORMANCE.md updated in place (7.3 delta
   section, amplification table refresh, bounds re-pin).

## Paper trail

- **ADR 0023 — Request-scoped resolution memoization** (F1/F2/F4-ancestor; must state the
  staleness contract in its own words — this is 7.4's audit target).
- **ADR 0024 — Batch role resolution** (F3/F4-batching; the pre-1.0 public-SPI decision).
- Both written **up front** at decomposition (repo convention).
- Doc sweep at decompose: PERFORMANCE.md, starter property/reference docs, `infra/README`,
  `scripts/load/README`, POC-ROADMAP.
- Branch: `feature/void3110/resolve-coalescing` off `origin/main` `--no-track`.

## 7.4 hand-forward (recorded now so the handoff can't lose them)

1. **ADR 0023 memo semantics** — the mandatory stale-authorization scrutiny (three-outcome memo,
   outage memoization, revocation-at-request-boundary).
2. **`/internal/effective-roles`** — verify in-network-only reachability (never gateway-exposed).
3. **New multi-root load fixtures** — registry hygiene (`dddd…` ids, `perf` memberships, clean
   teardown).

## Decompose-level to-dos (not forks — details the tickets must nail)

- APISIX `opa` plugin timeout attribute name/format on 3.11.
- `target=<type>:<id>` param encoding + server-side validation posture (unknown type → 400 →
  permanent outage classification on the client, per B2 strict rules).
- Server-side batch implementation (`EffectiveRoleService`): loop-per-target is acceptable
  (in-process); batch SQL only if trivially available.
- Skip-wrapping `NoOpRoleDefinitionSupplier` in the BPP (or not — harmless either way).
- Catalogs-list `_actions` e2e cell existence check.
- Naming of the decorator classes; ADR numbering collision check.
