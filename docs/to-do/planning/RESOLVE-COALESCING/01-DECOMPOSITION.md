---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/spring
---

# RESOLVE-COALESCING — 01-DECOMPOSITION

> The ordered work list for Slice 7.3. Design: [[00-DESIGN]] (all forks settled, grill-me 2026-07-10).
> Pinned by ADRs [[0023-request-scoped-resolution-memoization|0023]] (the memo) and
> [[0024-batch-role-resolution|0024]] (the batch). Slice note: [[RESOLVE-COALESCING]]. QA cases:
> [[10-QA-TEST-CASES]]. **Six tickets, T1→T6.**

## Slice invariants (every ticket carries these forward)

1. **One request, one answer per target** (ADR 0023): the memo replays the first tri-state outcome —
   `of` / `empty` / **the throw** — per key for the request's lifetime; it replays outcomes, never
   reinterprets them. Outside a web request the decorators are pure pass-through (never a throw of
   their own).
2. **No memo or batch path ever widens.** A memoized outage replays as the outage; a batch that is
   short, malformed, extra-entried, non-200, or transport-failed is a **whole-batch outage** →
   `RoleResolutionException` → every caller's existing deny/omit degrade (ADR 0024 §2–3). Empty never
   fabricates a role.
3. **B2 preserved verbatim** (ADR 0014): the single-target `lookup()` path is byte-identical; the
   batch classification mirrors B2's strict rules (only 200+complete trusted; 4xx/malformed permanent;
   5xx/429/timeout transient inside the guard). Terminal signals are never retried.
4. **Batching is unconditional code; `opa.abac.resolve-memo.enabled` governs memoization only** —
   one flag for both memos, default `true` (ADR 0023 §5, ADR 0024 §5).
5. **`opa-abac-core` stays Spring-free.** `ResolveTarget` + the `lookupAll` default method are pure
   JDK; the decorators live in the Spring layers (role memo: `opa-abac-spring-security`; ancestor
   memo: the starter — the module-dependency constraint, ADR 0023 §1).
6. **`/internal/**` is never gateway-exposed** — the batch endpoint gets no APISIX route.
7. **Enrichment contracts intact** (ADR 0016 §7): omit-never-fabricate, the all-`false`→omit rule, the
   per-row vs whole-page degrade split, `AbacResourceCache` as snapshot-never-verdict — all unchanged.
8. **Zero Rego. Report-only perf posture** (ADR 0021): validity gates only, no latency thresholds; the
   durable story is deltas vs the recorded 7.2 baseline. Load fixtures use `dddd…` ids + the reserved
   `perf` identity, count-asserted, torn down on green.

## Critical path

```
T1 (multi-root k6 scenario + fixtures + PRE-change baseline)   ── harness only; MUST run before any
  │                                                               library change lands (the "before")
  ▼
T2 (role memo + ancestor memo + BPPs + flag)                   ── headline #1: the measured 2/22/102
  │                                                               same-target collapse (U1–U7, I3)
  ▼
T3 (lookupAll SPI + ResolveTarget in core + memo batch integration)
  │
  ▼
T4 (batch wire: /internal/effective-roles + HttpRoleDefinitionSupplier.lookupAll)
  │                                                            ── headline #2: the multi-root fix
  ▼
T5 (ActionEnrichmentAdvice batching pass)
  │
  ▼
T6 (gateway timeout + docs/adopter notes + full re-measurement + bounds re-pin + e2e)
                                                               ── headline #3: the re-measured proof
```

- **Sequential:** T1 → T2 → T3 → T4 → T5 → T6. T1 is chronologically first *by design* — its baseline
  run must execute against pre-memo code on this branch. T3 needs T2 (the memo decorator gains its
  `lookupAll` override); T4 needs T3 (the SPI exists); T5 needs T3 (it calls `lookupAll`; T4's wire
  override makes it one exchange, the default loop keeps it correct in between); T6 seals.
- **Independently landable:** **T1+T2** alone deliver the *measured* headline (every 7.2 scenario was
  same-root, so the memo collapses 2/22/102 → 1 by itself) plus the harness's multi-root blind-spot
  fix — standalone value if the window is short. T3–T5 add the multi-root batch; T6 the proof.
- **Build-breaker watch:** T3 adds a default method to a `@FunctionalInterface` — additive by
  construction, but the **full** `./gradlew build` in that commit is the proof that every existing
  lambda/impl (and every test stub) still compiles. No other ticket changes a public signature.

---

## T1 — Multi-root list scenario + fixtures + pre-change baseline (harness only)

**Goal.** Close the 7.2 harness's multi-root blind spot and record the "before": a catalogs-list
scenario whose rows are each their own governing root, measured against pre-memo code on this branch.

**Deliverables** (all under `scripts/load/` + seed tooling; no library/app code):
- `scenarios/multi-root-list.js` — k6 scenario: authenticated `GET /api/v1/catalogs` pages (the
  `perf` identity) at a low steady rate (below the known knee; the 7.2 steady-mode validity gates
  apply), plus a trace-sampled window for attribution.
- Fixture seeding for **M≈50 catalogs, each with its own team and a `perf` membership** (each row
  must actually resolve a role): extend the harness seed (`run-load.sh` seed functions or a sibling
  script) — `dddd…`-prefixed ids only, deterministic, idempotent, **count-asserted**, teardown-on-green
  (`KEEP_FIXTURES=1` honored). Note: teams/memberships live in the user-management service — seed via
  its API or bulk SQL, matching the existing seed idiom.
- `run-load.sh` gains a `multi-root` mode (preflight → seed → measured window → attribution export),
  wired into the same validity-gate framework.
- `amplification.py` extended to attribute the new scenario (resolve/compile/batch-eval counts per
  request from Jaeger, median==max discipline).
- `scripts/load/README.md` documents the scenario + fixtures.
- **The baseline run, executed and kept:** artifacts under `scripts/load/results/<run>/` recording the
  pre-change per-request resolve count (expected: **M per page** — the disprovable "before", QA **P3**).

**Acceptance.** The scenario runs green through the validity gates on the live rig; the attribution
export shows resolve = M (constant across sampled traces) pre-change; fixtures seed + tear down clean
(registry check: only `dddd…` ids, `perf` only). QA: **P3-before**.

**What NOT to touch.** No library or example-service code (invariants 1–7 don't bite yet — that's the
point: this ticket must land **before** T2 so the baseline is honest). No latency thresholds added
(invariant 8). No new realm users (the reserved `perf` identity only).

---

## T2 — Request-scoped memos (role + ancestor) + BPP wiring + the flag

**Goal.** Collapse every same-target resolution to one real call per request — the measured 2/22/102
headline — by memoizing all three tri-state outcomes at both SPI seams, bean-wrapped, flag-governed.

**Deliverables:**
- `MemoizingRoleDefinitionSupplier` (package `dev.dmitriikonovalov.opaabac.security`, sibling of
  `RequestAttributesResourceCache`) — implements `RoleDefinitionSupplier`; delegates + memoizes per
  `(userId, resourceType, resourceId)` (null `resourceId` is a distinct type-level key) in request
  attributes via `RequestContextHolder`; stores `of`/`empty`/an **outage marker** (re-thrown as
  `RoleResolutionException` on replay); pure pass-through when no request attributes are bound.
- `MemoizingAncestorResolver` (package `dev.dmitriikonovalov.opaabac.autoconfigure` — the starter is
  the only module seeing both `AncestorResolver` and spring-web; ADR 0023 §1) — same shape per
  `(type, id)`: memoizes the chain **and** `AncestorResolutionException` (re-thrown on replay);
  pass-through without a request. Covers the query path **and** the advice path via the starter's
  existing `AncestorChainSupplier` binding — verify that binding delegates to the *post-processed*
  bean (it must; that's the wiring check).
- `OpaResolveMemoAutoConfiguration` (starter) — two `BeanPostProcessor`s (the
  `resilientOpaClientDecorator` idiom): wrap any `RoleDefinitionSupplier` / `AncestorResolver` bean;
  guard `@ConditionalOnClass(RequestContextHolder)` + `@ConditionalOnProperty(
  "opa.abac.resolve-memo.enabled", matchIfMissing = true)`; skip double-wrapping (instanceof check);
  registered in `AutoConfiguration.imports`. **Named consumers:** the gate managers, the hierarchical
  authorizer, the enrichment advice, and the example's app-side list authorizers — all through the
  wrapped beans, zero call-site changes.
- Property documented in the starter's config reference (wherever `opa.abac.*` keys are listed).

**Acceptance.** `./gradlew :opa-abac-spring-security:test :opa-abac-spring-boot-starter:test` +
example ITs: QA **U1–U7** (three-outcome memo; key separation; cross-request isolation; no-request
pass-through; `ApplicationContextRunner` flag/classpath states; the **supplier-flip consistency**
test — the ADR 0023 disprover; ancestor memo with both callers' degrade semantics) and **I3** (the
whole-request budget: a same-root list makes exactly **1** wire-touching resolve and ≤1 `ancestorsOf`
per `(type,id)`, counted through real gate + finisher + enrichment).

**What NOT to touch.** `RoleDefinitionSupplier` / `AncestorResolver` interfaces unchanged (T3 does the
SPI). `AbacResourceCache` untouched (invariant 7). `ActionEnrichmentAdvice` untouched (T5). No
`opa-abac-core` change. The decorators must never reorder, suppress, or convert an outcome (invariant
1) — and must never throw from their own bookkeeping (a memo bug must not become a deny).

---

## T3 — `lookupAll` SPI + `ResolveTarget` (core) + memo batch integration

**Goal.** Give the resolve seam its batch form — two-state entries, whole-batch outage, strict
completeness — as an additive default method, and teach the memo to serve/populate it.

**Deliverables:**
- `ResolveTarget` — a `(resourceType, resourceId)` record in `opa-abac-core` (pure JDK; value
  semantics are the memo/batch key).
- `RoleDefinitionSupplier.lookupAll(String userId, Set<ResolveTarget> targets)` — **default** method
  returning `Map<ResolveTarget, Optional<RoleDefinition>>`; javadoc carries the ADR 0024 contract
  verbatim (two-state entries; whole-batch outage; **exactly one entry per requested target**; empty
  set → empty map, no lookup; the loop default aborts the whole batch on any single throw).
- `MemoizingRoleDefinitionSupplier.lookupAll` override (spring-security): memo hits excluded from the
  delegated set; misses forwarded as **one** `delegate.lookupAll(misses)`; every returned entry
  memoized; a whole-batch outage memoizes the **outage marker for every missed target** (a later
  single `lookup` of one of them replays the throw without touching the delegate); the merged
  (hits + fresh) map returned with strict completeness.
- **Named consumers:** T4's `HttpRoleDefinitionSupplier` override and T5's advice pass (this ticket's
  javadoc names both).

**Acceptance.** `./gradlew :opa-abac-core:test :opa-abac-spring-security:test`: QA **U8** (default
loop: complete map, order-independence, abort-on-first-throw, empty-set short-circuit) and **U9**
(memo integration: hits never delegated; batch outage marks all misses; strict completeness of the
merged result). **Build-breaker proof:** the **full** `./gradlew build` in this commit — every
existing supplier lambda/stub still compiles (`@FunctionalInterface` intact).

**What NOT to touch.** No Spring/Jackson import in core (invariant 5). No wire code (T4). No advice
change (T5). `lookup()`'s contract text unchanged — `lookupAll` references it, never redefines the
tri-state.

---

## T4 — Batch wire: `/internal/effective-roles` + `HttpRoleDefinitionSupplier.lookupAll`

**Goal.** Make the batch real over HTTP: one guarded GET resolving N targets against the
user-management service, classified under B2's strict rules with strict completeness.

**Deliverables:**
- `InternalResolveController` (user-management, `…example.usermgmt.web`): `GET
  /internal/effective-roles?userId=…&target=<type>:<id>&target=…` — loops
  `EffectiveRoleService.resolveForResource` per target; responds `200` with a JSON array of
  `{resourceType, resourceId, role|null}` — **exactly one entry per requested target, never `204`**;
  `400` on a missing `userId`, a malformed target (no colon / bad UUID), or an unknown type — and a
  `400` is a **permanent outage** client-side, per B2. Server errors stay 5xx (5xx-over-partial, ADR
  0024 §2). Mounted under the existing permitted `/internal/**` block; **no APISIX route**.
- `HttpRoleDefinitionSupplier.lookupAll` override (catalog, `…example.catalog.config`): empty set →
  `Map.of()` without HTTP; otherwise **one** exchange through the **existing `resolveCallGuard` as one
  guarded call** (the whole exchange is the retry unit); classification mirrors `lookup()`:
  200+complete → the map (`null` role → `Optional.empty()`); **missing/extra/duplicate entry →
  `RoleResolutionException`** (permanent — strict completeness); 200-blank/unparseable → permanent;
  5xx/429/timeout/connect → `TransientResolveException` → guard retries → exhausted →
  `RoleResolutionException`; 4xx → permanent, no retry; breaker-open (`CallNotPermittedException`) →
  `RoleResolutionException`. URL-encode each target part.
- A construction comment at the wrap point mirroring the existing no-lock / side-effect-free retry
  rationale (read-only GET, request thread, no transaction).

**Acceptance.** `./gradlew build` + targeted suites: QA **U10** (the full classification table via the
in-process `HttpServer` stub — one exchange asserted by request counting; every non-happy row) and
**I1** (Testcontainers IT on user-management: mixed resolved/no-role targets → one entry each with
`null` for no-role; malformed target → 400).

**What NOT to touch.** The single-target `lookup()` method and `/internal/effective-role` (singular)
are **byte-identical** — new code paths only (invariant 3). No gateway config (that's T6; and the
batch endpoint never gets a route — invariant 6). No retry semantics invented: the transient/permanent
split is B2's, reused.

---

## T5 — `ActionEnrichmentAdvice` batching pass

**Goal.** One `lookupAll` per enriched page: collect distinct governing roots first, resolve once,
build rows from the map — the degrade ladder byte-compatible with today.

**Deliverables:**
- `ActionEnrichmentAdvice` (spring-security, `…security.web`) reworked to the two-pass flow (ADR 0024
  §5): pass 1 per row — verbs present? cached snapshot? ancestors via the (memoized) chain supplier →
  governing root; collect the **distinct** `ResolveTarget`s of computable rows; **one
  `roleDefinitionSupplier.lookupAll(subject, roots)`**; pass 2 — per-row contexts from the returned
  map (`empty` → `role=null`, exactly as today's `orElse(null)`), then the existing single
  `allowAll` per type + refold + `anyTrue` rules unchanged.
- Degrade ladder preserved and **tested at each rung**: a row with no verbs / cache miss / ancestor
  failure → that row omitted, others proceed (pass-1 rung); `lookupAll` throwing (whole-batch outage)
  → the **group's** `_actions` omitted, response body otherwise intact (the existing
  omit-all-on-failure rung); the response is never blocked (affordance, not enforcement).
- Javadoc updated: the class doc's flow description reflects pass-1/lookupAll/pass-2 (it currently
  documents per-row resolution).

**Acceptance.** `./gradlew :opa-abac-spring-security:test` + example ITs: QA **I2** (counting
supplier: a 20-row multi-root page issues exactly **one** `lookupAll`; per-row ancestor failure omits
only that row; a batch outage omits all `_actions` while rows still return 200) — plus **every
existing advice/enrichment test green unchanged** (the contract-preservation proof).

**What NOT to touch.** No flag-gating of the batch pass (invariant 4). `AbacQueryService` / the list
finisher untouched (the two-batch bound is design — ADR 0024 rejected the merge). `Enrichable` /
`AbacResourceCache` APIs unchanged. Omit-never-fabricate and the all-`false`→omit rule intact
(invariant 7).

---

## T6 — Gateway timeout + adopter notes + full re-measurement + bounds re-pin + e2e

**Goal.** Land the config/docs tail, then prove the slice with the re-measured numbers and the full
e2e suite — the "after" that closes T1's "before".

**Deliverables:**
- `infra/apisix/init-routes.sh`: the `opa` plugin block gains `"timeout":500` — **integer
  milliseconds** (verified against the live APISIX 3.11 admin schema: integer, default 3000, min 1);
  `infra/README.md` notes the knob + the deny-latency-under-outage rationale.
- `PERFORMANCE.md`: a **7.3 delta section** — re-measured amplification tables (expected bounds:
  resolve wire calls **1** on all four scenarios incl. T1's multi-root; batch-eval **re-pinned to 2**
  for lists with the finisher-vs-affordance rationale; compile 1), the ceiling ladder re-run, steady
  list/enrichment deltas vs 7.2, the multi-root before/after (T1's artifacts vs this run), and the
  fault-opa timeline re-run showing the gateway deny latency drop (~3 s → ~0.5 s). Plus **adopter
  notes**: breaker recovery knobs (`waitDurationInOpenState`, half-open pacing — document-only, no
  config change), and production trace sampling with a concrete OTEL line
  (`parentbased_traceidratio` at a low ratio).
- Guides reconciled: [[ACTION-ENRICHMENT]] (the batching flow + the memo), the resilience guide
  (memo-above-guard interplay: the breaker samples real calls only), the resolve/authorization guide
  that documents `RoleDefinitionSupplier` (the `lookupAll` contract + the flag). The starter property
  table gains `opa.abac.resolve-memo.enabled`.
- e2e: **verify an existing newman cell asserts `_actions` on the catalogs list** (the multi-root
  path); add one to the appropriate matrix if absent — member sees `_actions` on their catalogs vs a
  gated user's contrast (assert the cut). Then the **full suite**: every `scripts/postman/run-*.sh`
  matrix green.
- `[[USER-STORIES]]` F5 ticked; index + roadmap finalized; STATUS notes completed.

**Acceptance.** QA **E1** (catalogs-list `_actions` cut through the gateway), **E2** (full newman
suite green), **P1** (amplification bounds table met — trace-attributed, constant across samples),
**P2** (ceiling: the 25 req/s stage passes; OPA survives the 50 req/s stage without OOM), **P3**
(multi-root before/after delta recorded), **P4** (fault-opa deny p50 ≈ 0.5 s, typed 403s throughout).
Full `./gradlew test` green (never targeted-only). Perf protocol: fresh rig + fresh trace store,
quiesced machine, `REPS=3` for re-run headline scenarios (the `PERFORMANCE.md` "Rerun it" recipe).

**What NOT to touch.** No library/app behavior change in T6 (config + docs + measurement + e2e cells
only). Report-only posture (invariant 8): the knee claim is slice acceptance, not a CI gate. The
breaker configuration is **not** tuned (document-only — 00-DESIGN F5).

---

## Cross-cutting acceptance (the whole slice)

- **The full build is green** (`./gradlew build` and the complete `./gradlew test` — never
  targeted-only) with the flag at its default in every IT.
- **The amplification claim holds end-to-end** — P1's table (resolve wire calls = 1 everywhere,
  batch-eval = 2 on lists, compile = 1), backed by I3's counting-fake budget at the IT level.
- **"One request, one answer per target" is a tested contract** — U6 (supplier flip) is the direct
  disprover; U1/U7/U9 pin the three-outcome + batch-outage memo semantics.
- **No widening on any failure path** — U4 (pass-through never throws), U8–U10 (whole-batch outage,
  strict completeness, breaker-open), I2 (advice degrade ladder), all landing on deny/omit.
- **Kill-switch honesty** — U5: flag off ⟺ per-call resolution semantics (batching remains — that's
  design, ADR 0024 §5).
- **Boundary** — `opa-abac-core` gains only pure-JDK types; zero Rego (`opa test` count unchanged);
  `/internal/**` unrouted; single-target paths byte-identical.
- **Headline tickets:** **T2** (the measured same-root collapse), **T4+T5** (the multi-root batch),
  **T6** (the re-measured proof + the fleet of matrices green).
