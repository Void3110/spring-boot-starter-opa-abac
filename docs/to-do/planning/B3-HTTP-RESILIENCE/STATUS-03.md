---
tags:
  - status/done
  - type/project
  - area/abac
  - area/spring
---

# STATUS — T3: App-side resolve + tag wrappers (catalog) — B2-preserving

**Status:** ✅ DONE

## What shipped

The two example HTTP edges now run their exchange through the resolve / tag `CallGuard`s, retrying
transients **before** their B2 fail contracts fire — preserving B2 exactly. All in
`example-catalog-management-service`, `…catalog.config`:

- **`HttpRoleDefinitionSupplier`** — `lookup(...)` runs through the injected **resolve** `CallGuard`. The
  exchange + B2 classification is extracted into `exchangeAndClassify(...)`, which:
  - returns for the two terminal success signals (**204→empty, 200+valid→resolved** — un-retried);
  - throws `RoleResolutionException` straight for a **permanent** failure (4xx, 200-blank, malformed-200)
    → non-retryable → the guard re-throws at once;
  - throws **`TransientResolveException`** for the **transient** subset (5xx/429, timeout,
    connection-refused) → the guard retries; an **exhausted** instance is mapped back to
    `RoleResolutionException` (B2's outcome). An open resolve breaker → `RoleResolutionException`.
- **`TagDefinitionClient`** — `fetchApplicable(...)` runs through the injected **tag** `CallGuard`, same
  shape: 200+valid→definitions; permanent (4xx/blank/malformed)→`TagDefinitionFetchException` at once;
  transient→retry, exhausted→`TagDefinitionFetchException` (→503); breaker-open→503.
- **`CatalogResilienceConfig`** — wires `resolveCallGuard` + `tagCallGuard` beans from the starter's
  `OpaAbacProperties` resilience tree (`resilience.resolve.*` / `…tag.*`, master switch folded in) — the
  same R4j, same knobs, same config shape as the OPA decorator (the honest "uniform posture"). Independent
  per-endpoint breakers.
- **`CallGuards.disabled(name)`** — the test/demo helper; the edges' 3-arg constructors use it so the
  **existing edge unit tests keep their exact one-shot behavior** (a single unguarded attempt). The
  production beans use the new `@Autowired` 4-arg `(…, CallGuard)` constructors.

Each wrap point carries a construction note: the **no-lock** invariant (request thread, outside any write
tx — `TagAssignmentService` is not `@Transactional`) and the **side-effect-free** retry rationale
(read-only GETs).

## Tests

`:example-catalog-management-service:test` → **87 passed, 0 failed** (the full suite, incl. all ITs against
real Postgres with resilience **on** at defaults — **I1 no-regression**: B3 is transparent on the happy
path). The T3-specific cases:

- **U9** (`EdgeResilienceTest`, in-process `HttpServer` stub, **virtual time**): (a) transient 503 then
  200+valid → **recovers** to the resolved `RoleDefinition`; (b) exhausting 5xx → throws
  `RoleResolutionException` after **3 attempts** (2 retries + 1); (c) 4xx → throws after **exactly 1
  attempt**; (d) 204 → `Optional.empty()` **1 attempt** (terminal); (e) 200+valid → resolved **1 attempt**;
  plus connection-refused → retries then throws.
- **U10** (`EdgeResilienceTest`): (a) transient then 200 → recovers to definitions; (b) exhausting 5xx →
  `TagDefinitionFetchException` after 3 attempts; (c) 4xx → throws after **1 attempt**.
- The **existing** `HttpRoleDefinitionSupplierTest` (10) + `TagDefinitionClientTest` (5) pass **unchanged**
  on the disabled-guard path — B2 byte-identical.

**I2** (the resolve-blip-recovers-to-resolved-role mirror of E1) — its substance is `U9a`
(`resolve_transientThenRecovers` returns the resolved `owner` role, **not** empty→realm-fallback). The
**full gateway proof through real Postgres** is the e2e headline **E1/E2 in T4**; a separate
`role-source=http` Spring IT would duplicate the unit coverage without adding confidence the e2e doesn't
provide, so it is folded into T4's real proof (flagged, not silently dropped).

## Architecture review + refactor

_Filled at the ★ gate._ Findings:
- **One simplification applied:** the guard's error predicate was
  `t instanceof Transient…Exception || RetryableClassification.isRetryableError(t)`. The `|| isRetryableError`
  clause is dead — the exchange body catches every transport `IOException` and re-throws it **as** the
  `Transient…Exception`, so no raw `IOException` ever escapes the body. Simplified both predicates to just
  the `instanceof` check (and corrected the Javadoc that implied a cause-chain match). Removes a misleading
  "we also retry IOExceptions" implication.
- **Build-breaker found + fixed in this commit:** adding the 4-arg `(…, CallGuard)` constructor gave each
  `@Component` edge two public constructors → Spring "No default constructor / ambiguous". Fixed by
  `@Autowired` on the production 4-arg ctor (the test/demo 3-arg one stays for unit tests). The full IT
  suite then loads the context green.
- **B2 preserved exactly** (the load-bearing invariant): the set that throws `RoleResolutionException` /
  `TagDefinitionFetchException` is **unchanged** — retry only slots ahead of the transient throw; 204/200
  terminal and 4xx-immediate are pinned by **attempt-count** assertions (1 attempt each). No throw was
  replaced by a fallback; the realm fallback is never reached on an outage.
- **No-lock / side-effect-free:** both edges run on the request thread outside any write tx; both are
  read-only GETs (documented at each wrap point).
- Nothing else substantive — no invented churn.

## Integration / e2e

I1 (no-regression) ✅ via the full IT suite green with resilience on at defaults. I2's unit-level mirror ✅
via `U9a`; the real-Postgres-through-the-gateway proof is **T4 E1/E2**.

## Decisions

- **One retryable signal per edge** (`Transient{Resolve,Tag}Exception`): the exchange body classifies once
  and emits a single retryable type for the whole transient subset, so the guard's error predicate is a
  clean `instanceof` and B2's permanent-throw path is untouched.
- **Disabled-guard 3-arg constructor** keeps the existing edge unit tests byte-identical (one attempt) while
  the production beans get the real per-edge budget — so resilience is additive, not a rewrite of the tests.

## Commit

`feat(resilience): app-side resolve + tag wrappers, B2-preserving (T3)`
