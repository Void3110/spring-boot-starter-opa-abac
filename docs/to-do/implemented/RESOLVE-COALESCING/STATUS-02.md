---
tags:
  - status/done
  - type/project
  - area/abac
  - area/spring
---

# STATUS — T2: Request-scoped memos (role + ancestor) + BPP wiring + flag

**Status:** ✅ DONE — the memo decorators are live bean-level, the whole-request budget is pinned at
IT level (**1 role resolve / ≤1 `ancestorsOf` per key** through the real gate + finisher +
enrichment), and the review gate surfaced a **pre-existing wiring hole** that was silently bypassing
every bean-level decorator on the gate path (fixed).

## What shipped

- `MemoizingRoleDefinitionSupplier` (`opa-abac-spring-security`, sibling of
  `RequestAttributesResourceCache`) — all **three** tri-state outcomes memoized per
  `(userId, resourceType, resourceId)` record key (`null` id = its own key, distinct from a literal
  `"null"`); the outage marker re-throws the same `RoleResolutionException` instance on replay;
  only the contractual exception is memoized (a delegate bug propagates un-memoized); bookkeeping
  failures degrade to pass-through (never a throw of its own); no request → pure pass-through.
- `MemoizingAncestorResolver` (starter `autoconfigure`, package-private — ADR 0023 §1 module
  constraint) — same shape per `(type, id)`: chain + `AncestorResolutionException` memoized;
  `subtreeOf` is a deliberate pass-through (a lazy JPA `Specification`, not a resolve).
- `OpaResolveMemoAutoConfiguration` (starter, `@Import`ed from `OpaAbacAutoConfiguration`) — two
  `static` `BeanPostProcessor`s (the `resilientOpaClientDecorator` idiom, `instanceof`
  double-wrap guard); role half guarded on `RequestContextHolder`, ancestor half additionally on
  Spring Data JPA; **one flag `opa.abac.resolve-memo.enabled` (default `true`) governs both**.
- `OpaAbacProperties.ResolveMemo` group (+ getter, `@NestedConfigurationProperty`) — documented in
  the class javadoc and the generated `spring-configuration-metadata.json` (test-asserted); the
  guide sweep is T6's deliverable.
- **The review-gate fix (library):** `OpaMethodSecurityConfiguration` now injects the gate manager
  via `ObjectProvider`, resolved lazily on the first decision (`DeferredAuthorizationManager` +
  `SingletonSupplier`). The advisor is `ROLE_INFRASTRUCTURE` and is instantiated during
  `BeanPostProcessor` registration; direct injection dragged the manager + its collaborator graph
  (`RoleDefinitionSupplier`, `OpaClient`, `ResourceResolutionSupport`) into that window where
  user-declared BPPs aren't registered yet — so the **gate's** references silently skipped every
  bean-level decorator (the ADR 0023 memo AND B3's `ResilientOpaClient` wrap, ADR 0017) while all
  later consumers got wrapped beans: one request, two supplier identities. Deferral (the idiom
  Spring Security's own method-security config uses) moves the manager's creation past refresh.

## Tests

- `MemoizingRoleDefinitionSupplierTest` — 12 tests (U1–U4, U6 incl. **the supplier-flip
  disprover**, the literal-"null"-id key case, the completed-request bookkeeping degrade, the
  non-contract-exception pass-through).
- `MemoizingAncestorResolverTest` — 6 tests (U7: both callers' degrades against the SAME replayed
  throw; the one-chain-per-request flip case; `subtreeOf` never memoized).
- `OpaResolveMemoAutoConfigurationTest` — 10 tests (U5: default-on wrap, user-bean wrap, flag-off
  raw `isSameAs`, no-double-wrap via direct BPP re-invocation, spring-web-absent back-off,
  JPA-absent = role-only, metadata key, property binding).
- `ResolveBudgetIT` (example, Testcontainers Postgres) — **I3**: one same-root categories list
  through the real gate + real allowlist-batch finisher (`compile` → `unsupported()`) + real advice:
  exactly **one** delegate role resolve for the whole request (key = the governing catalog), and
  **every** `(type,id)` ancestor chain resolved exactly once (was 2×N: query path + advice path).
- **Full `./gradlew build` GREEN** (all modules, all ITs).

## Architecture review + refactor

The gate produced three real findings, all fixed in this ticket:

1. **The advisor's eager manager injection (the substantive one)** — found because the first
   `ResolveBudgetIT` run measured **6** role resolves instead of 1 and the
   `BeanPostProcessorChecker` log named `countingRoleSupplier` as created during BPP registration.
   Diagnosis + fix above. Consequence worth naming: **B3's OPA-edge resilience wrap now actually
   guards the `@OpaPreAuthorize` gate path** (it silently never did) — so a gate deny now costs the
   ADR 0017-documented one extra fast sidecar hop (`ResilientOpaClient` deliberately retries the
   fail-closed `false` sentinel once), and the OPA-edge breaker now sees gate traffic.
2. **Test fallout of (1), pinned deliberately:** `TagDecisionGateIT` asserts WHICH decisions the
   gate dispatches in order — it now runs `opa.abac.resilience.opa.enabled=false` (the deny-retry
   would double every denied action in the capture); five `OpaAbacAutoConfigurationTest` cases that
   assert the raw selected impl (`NoOp`/`Ltree`/`Cte`/user overrides) pin
   `resolve-memo.enabled=false`, the exact precedent `userBeansWin` set for the B3 decorator.
3. **ThreadLocal request-attribute leakage in the suite:** MockMvc leaves `ServletRequestAttributes`
   bound after `perform()` (by design), so a later same-thread DIRECT `ancestorsOf` assert
   (`HierarchyAdoptionIT` re-parent flip) hit the memo's pre-reparent snapshot. Fixed with a
   `@BeforeEach` `RequestContextHolder.resetRequestAttributes()` — the suite asserts lineage
   mechanics, not per-request staleness semantics. (Live containers clear context holders per
   request; this hazard is test-harness-only.)

Also reviewed and held: subject separation (role memo key carries `userId`; the ancestor memo
deliberately does NOT — a resource's lineage is a subject-independent fact); the memo-map race
(two concurrent same-key lookups may both hit the delegate — last write wins, both outcomes from
the same delegate, no widening; preferred over holding a `computeIfAbsent` lock across a remote
call); memo hits never touch the resolve `CallGuard` (memo sits outside the app supplier that owns
the guard).

## Integration / e2e

`./gradlew build` — every module's tests + all example ITs green (the memo is default-on in every
IT, per the cross-cutting acceptance). Live e2e (newman fleet) runs at T6 with fresh images.

## Decisions

- Memo storage: a single request attribute holding a `ConcurrentHashMap<MemoKey, Object>` with
  record keys — no string-concat key (a literal `"null"` id can never collide with a type-level
  `null`).
- Only contractual exceptions (`RoleResolutionException` / `AncestorResolutionException`) are
  memoized; anything else is a bug, not an outcome, and propagates un-memoized.
- `subtreeOf` pass-through (not part of the measured double-resolve; a lazy Specification).
- The deferred-manager fix belongs to this ticket (the wiring check demanded the gate share the
  memoized bean), not a separate slice — but it also retro-fixes B3's gate-path wrap.

## Commit

`perf(resolve): request-scoped role + ancestor memos, BPP-wired under one flag (T2)` — see git.
