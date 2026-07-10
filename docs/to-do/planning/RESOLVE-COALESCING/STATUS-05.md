---
tags:
  - status/implemented
  - type/project
  - area/abac
  - area/spring
---

# STATUS — T5: ActionEnrichmentAdvice batching pass

**Status:** ✅ DONE — one `lookupAll` per enriched page, degrade ladder intact — and the review gate
produced a **bound correction**: the multi-root page's honest wire shape is **1 single + 1 batch = 2
resolve calls**, not 1 (re-pinned with rationale, the batch-eval-2 pattern).

## What shipped

- `ActionEnrichmentAdvice.enrichGroup` reworked to the **two-pass flow** (ADR 0024 §5):
  - **Pass 1** — per row: verbs present? cached snapshot? ancestors via the (request-memoized) chain
    supplier → governing root; failed rows dropped (their `_actions` stays unset); the computable
    rows' **distinct** `ResolveTarget`s collected. `prepareRow` no longer resolves roles.
  - **One `roleDefinitionSupplier.lookupAll(subject, roots)`** — unconditional code (coalescing, not
    caching — not governed by the memo flag, invariant 4). A throw = whole-batch outage = the
    **whole group's** `_actions` omitted, response body intact (affordance never blocks).
  - **Pass 2** — per-row contexts from the returned map (`empty` → `role=null`, exactly the old
    `orElse(null)`), then the existing single `allowAll` per type + `i·V+j` refold + the
    all-`false`→omit rule — all byte-identical.
  - Defensive rung: a missing root entry in the returned map (impossible through the library —
    strict completeness is contract-enforced and memo-re-checked; only a raw misbehaving custom
    supplier could) → that row omitted, logged.
- Class javadoc rewritten to the pass-1/lookupAll/pass-2 flow; the degrade list now states the
  designed change: **a role outage moves from per-row omit to whole-group omit** (a batch outage has
  no per-target answers — a fully-degraded page over a mixed-snapshot one, ADR 0023's posture).

## Tests

- **I2** — `MultiRootEnrichmentIT` (Testcontainers Postgres, MockMvc, counting supplier UNDER the
  memo decorator, `GovernedScopeResolver` stub — the `CatalogListIsolationIT` idiom): a 20-row
  multi-root catalogs page issues exactly **one** `lookupAll` (19 misses; the first governed root is
  a memo hit from the authorizer's query-time single — asserted `doesNotContain`); one row's
  ancestor failure omits **that row only** (19 enriched, the failed row absent from the batch); a
  batch outage omits **all** `_actions` while the response stays `200` with full rows.
- **Every existing advice/enrichment test green unchanged** (`ActionEnrichmentAdviceTest`,
  `ActionEnrichmentListIT`, `ActionEnrichmentIT`, the newman-covered surfaces at T6) — the
  contract-preservation proof. The two single-row outage tests (`roleResolutionOutage_omits`,
  `ancestorSupplierThrows_omits`) hold under the new flow because group == row for them.
- Full `./gradlew build` GREEN.

## Architecture review + refactor

**The substantive finding — the multi-root resolve bound is 2, not 1.** `CatalogListAuthorizer`
resolves the caller's role on the **first governed id** at *query time* (the coarse role that drives
the `filter` residual — fail-closed and load-bearing; removing it would widen). The memo holds that
answer, the advice's batch excludes it as a hit, and the page's wire shape is **one single + one
batch**. This is the same "two questions at two lifecycle points" shape that re-pinned the list's
batch-eval bound to 2 (query-time inclusion vs response-time verbs): here it is the query-time
*coarse* role vs the response-time *per-root* roles. Same-root scenarios stay at **1** — every
caller shares one key, the memo collapses them, and a fully-hit batch never delegates (I3's proof).
Re-pinned in `amplification.py` (`multi-root-list: resolve 2`) with the rationale comment + the
offline fixture case updated; the T6 ledger rewrite carries the before/after as **51 → 2**.

Otherwise nothing to refactor: the refold/allowAll half of `enrichGroup` is untouched; the two new
records (`RowInputs`, `PreparedRow`) keep pass-1 state explicit.

## Integration / e2e

I2 + the full build; the live multi-root re-measurement and the newman fleet are T6.

## Decisions

- Role outage: per-row omit → **whole-group omit** (designed, ADR 0024 §5 — documented in the class
  javadoc and the ADR's degrade paragraph).
- The batch is **unconditional** — no flag gate (invariant 4); `resolve-memo.enabled=false` restores
  per-call *freshness*, not pre-7.3 call counts.
- The defensive missing-entry rung omits the row rather than throwing — a custom supplier's contract
  bug must not take down the page (affordance never blocks).

## Commit

`perf(resolve): ActionEnrichmentAdvice two-pass batching — one lookupAll per page (T5)` — see git.
