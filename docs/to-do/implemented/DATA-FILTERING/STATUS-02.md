---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
---

# STATUS — T2: Batch decision — OpaClient.allowAll (bulk input) (core)

> Filled in at the T2 checkpoint during the autonomous run. See [[01-DECOMPOSITION]] T2 and the
> per-ticket loop in [[AUTONOMOUS-IMPLEMENTATION-PROMPT]].

**Status:** ✅ done

## What shipped

The `OpaClient.allowAll(List<AbacContext>) → List<Boolean>` **signature** landed with T1 (so the three
test stubs compiled against the widened interface); T2 ships the **`HttpOpaClient.allowAll` body** plus its
dedicated tests:

- **`HttpOpaClient.allowAll`** — POSTs `{"input":{"items":[<ctx>,…]}}` to
  `<baseUrl>/v1/data/<path>/bulk` (the per-type `bulk` rule, authored in T6) and reads `result` as a
  boolean list of length N, mapped positionally (`result.get(i)` ↔ `contexts.get(i)`).
- **General-purpose by design** — a plain public `OpaClient` method, **no** filtering/spring-data coupling,
  so Phase-6 action enrichment ([[ACTION-ENRICHMENT]]) reuses the same primitive (and the same `bulk` rego
  rule). Building it general here avoids building batch twice (ADR [[0005-partial-eval-to-jpa-specification|0005]]).
- **Fail-closed:** non-200 / IOException / timeout / malformed / length-mismatch / a non-boolean element →
  `allFalse(N)` (an immutable list of `false` × N). An **empty/null input** short-circuits to an empty list
  **before** path resolution or any HTTP — no call is made.

## Tests

`./gradlew :opa-abac-core:test` green. New `HttpOpaClientAllowAllTest` (in-process `HttpServer` stub)
covers **U10–U12**: a mixed `[true,false,true]` body maps positionally (+ the `/v1/data/catalog/category/bulk`
path and `{"input":{"items":[…]}}` shape pinned); 500 / refused / timeout / malformed / wrong-length /
non-boolean-element → all-false of length N; empty input → empty list with the stub's call-counter
asserting **zero** HTTP calls; null input → empty.

## Architecture review + refactor

- **Fail-closed, every path — verified.** Every non-success branch returns `allFalse(n)`; the empty-input
  guard is the *first* statement, before path resolution / serialization / HTTP, so the "no call on empty"
  guarantee is structural (asserted by a call-counter, not just by output).
- **General primitive — verified.** No `Specification`/JPA/filtering type appears in the method; it is a
  pure `List<AbacContext> → List<Boolean>` over the `bulk` rule, ready for the T4 allowlist finisher *and*
  Phase-6 enrichment.
- **Pattern reuse.** Same JDK-`HttpClient` + Jackson + WARN-without-token fail-closed shape as
  `allow`/`compile`.
- No substantive churn — nothing to refactor.

## Integration / e2e

Deferred to T6 (the `bulk` rego rule + `opa test`) and T4 (the allowlist finisher that consumes
`allowAll`). T2 is pure-core, unit-tested against the in-process stub.

## Decisions recorded

- The bulk wire shape is `{"input":{"items":[<ctx>,…]}}` → the `bulk` rule iterates `input.items` and emits
  `[allow per item]`. Chosen over N single calls (the point of the primitive) and over a client-side loop
  (keeps per-row evaluation in the policy, one round-trip). Covered by the T1 Mulch pattern (mx-a932a0);
  no separate record needed.

## Commit

`feat(data-filtering): T2 batch allowAll — bulk round-trip + fail-closed tests` — _(SHA at commit)_
