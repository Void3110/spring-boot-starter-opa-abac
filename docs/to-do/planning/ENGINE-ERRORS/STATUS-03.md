---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/spring-security
  - area/spring-data
---

# STATUS — T3: The data layer propagates the family

**Status:** ✅ DONE (2026-10-05, collaborative)

## What shipped

- **`AbacQueryService`** — no code path change was needed: it never caught around `compile`, the kill-switch
  `allow` or the allowlist `allowAll`, so since T1 the family already propagates from both `findAuthorized`
  overloads. What changed is the **contract text**: the class's "Never fail-open" invariant (now "never
  fail-open, and never a lie"), the paged overload's path list (a new "the engine could not decide" bullet; the
  `fromError` bullet re-worded to "no policy answer"), and the two `fromError` branch comments + the batch
  comment, which still described a failed call as an empty list.
- **`HierarchicalAuthorizer`** — the role-outage catch is gone (the outage propagates; still never the realm
  fallback); the `catch (RuntimeException) → false` around `opaClient.allow` rethrows the family first;
  `@return` / `@throws` and a class-doc paragraph say "could not decide is not a deny".
- **`SubtreeSpecResolver`** — `catch (DecisionIndeterminateException) { throw e; }` ahead of the
  no-widening catch; the class doc's B2 paragraph (which promised the outage collapses to no widening) rewritten.
- **`LtreeAncestorResolver`, `RecursiveCteAncestorResolver`** — the family rethrows ahead of the four source-SPI
  wraps (`ltree` `ancestorsOf` + `subtreeOf`, CTE `parentOf` + `childrenOf`); their own SQL classification into
  `AncestorResolutionException` is unchanged.

## Tests

- `AbacQueryServiceTest`: compile / kill-switch `allow` / allowlist batch each throwing → **both overloads**
  propagate the same instance (no repository call on the first two); the batch path's ancestor resolver throwing
  an opted-in subtype propagates with no batch call. The existing `fromError` cells (U26) are unchanged and green.
  **U25, U26, U29.**
- `HierarchicalAuthorizerTest`: role outage → propagates, OPA never called (rewritten); engine failure →
  propagates; a non-family OPA throw still `false`; the chain-collapse cells unchanged. **U27.**
- `SubtreeSpecResolverTest`: role outage → propagates, `subtreeOf` never reached (rewritten); a non-family
  resolution failure → still empty. **U28.**
- `AncestorResolverTest`: each of the four wrap sites lets an opted-in subtype through unwrapped; a plain throw
  is still wrapped / collapsed. **U33.**
- `./gradlew build`: **green** (1m10s); spring-data 151 tests, 0 failures. No example test needed moving — the
  catalog list authorizers resolve the role before the subtree resolver runs, and the per-request memo replays
  that success, so the new rethrow cannot surface there (the build agrees).

## Architecture review + refactor

Self-review: nothing substantive. One wording slip caught in review — `HierarchicalAuthorizer`'s new comment
had split across the `governingRoot` assignment; folded into step 2's comment.

## Integration / e2e

None at T3 (T5). Behaviour at the HTTP edge: a hierarchical check or a list over a failing engine or role
source now answers 503 through the base advice (T2) instead of 403 / an empty 200.

## Decisions

- `LtreeAncestorResolver.ancestorsOf` uses a multi-catch (`AncestorResolutionException | DecisionIndeterminateException`)
  for its existing pass-through — the two are unrelated types, so the union is legal and keeps one rethrow site.

## Commit

`f29bb64` — feat(data): list queries, the hierarchical check and the subtree widening propagate "could not
decide" (ENGINE-ERRORS T3).
