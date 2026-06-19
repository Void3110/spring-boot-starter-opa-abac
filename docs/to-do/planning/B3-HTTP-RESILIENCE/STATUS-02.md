---
tags:
  - status/done
  - type/project
  - area/abac
  - area/spring
---

# STATUS — T2: Resilient `OpaClient` decorator + starter auto-config (the library feature)

**Status:** ✅ DONE

## What shipped

The published-library resilience feature — a resilient `OpaClient` auto-configured only when Resilience4j
is on the classpath and not disabled, fail-closed-identical to the plain client in every state.

- **`ResilientOpaClient implements OpaClient`** (`opa-abac-spring-security`, `…security.resilience`) — wraps
  a delegate `OpaClient` + the OPA-edge `CallGuard`. Each method runs the delegate through the guard:
  - `allow` retries on the `false` sentinel; `compile` retries on `fromError()`; `allowAll` retries when any
    element is `false`.
  - On **breaker-open** (delegate not called) it synthesizes the delegate's fail-closed value by hand:
    `false` / **`PartialResult.error()`** / n×`false`. **Never** `denyAll()`/`allowAll()`.
  - Empty batch → `List.of()` (mirrors the delegate's no-HTTP short-circuit). A real `OpaClient` — all three
    implemented by hand, no `default` fail-open.
- **`OpaResilienceAutoConfiguration`** (starter, `autoconfigure`) — `@ConditionalOnClass` R4j `CircuitBreaker`
  + `@Conditional(ResilienceEnabled)` (an `AllNestedConditions` over `resilience.enabled` +
  `resilience.opa.enabled`, both default true). A `BeanPostProcessor` **decorates** whatever `OpaClient` the
  context has (the starter's `HttpOpaClient` *or* a user-supplied client) — transparent to every injection
  point, no bean-name competition. The OPA-edge `CallGuard` is built lazily inside the BPP from
  `resilience.opa.*` (master switch folded in). `@Import`ed by `OpaAbacAutoConfiguration`.
- **`OpaAbacProperties.Resilience` / `Edge` / `Breaker`** — the config tree: master `enabled` + `opa` /
  `resolve` / `tag` edges (asymmetric defaults: OPA **1 retry / 50ms / 2.5s**; resolve+tag **2 / 50ms / 6s**;
  breaker 5 failures / 10s open / 1 probe). `Edge.toConfig(masterEnabled)` folds the master switch in and
  produces the `ResilienceConfig` the guard reads. The resolve/tag blocks are defined here too (T3 consumes
  them).
- R4j stays an **optional** starter dependency — it arrives transitively as `api` from spring-security (the
  same honest caveat as web/security, documented in the starter build); the `@ConditionalOnClass` back-off
  fires when a consumer excludes R4j. U8 proves both classpath states via `FilteredClassLoader`.

## Tests

`:opa-abac-spring-security:test` (119, 0 failed) + `:opa-abac-spring-boot-starter:test` (53, 0 failed). The
T2-specific cases:

- **U3** (`ResilientOpaClientTest`, in-process `HttpServer` stub) — fail-closed identity: decorator exhausted
  == plain `HttpOpaClient` for all three methods (`allow`→false, `compile`→`error()` with `fromError==true`,
  `allowAll`→n×false). Plus a **recovery** case: a transient 503 then 200+allow → the decorator returns the
  real `allow=true` (the headline, at unit level).
- **U4** — breaker forced open: all three methods fail closed **without** invoking the delegate (proven via a
  counting delegate: `compileCalls==0`, `allowAllCalls==0`, `allowCalls` unchanged).
- **U5** — breaker-open `compile` is `error()` (`fromError==true`), asserted `!= denyAll()` **and**
  `!= allowAll()` (the widening landmine).
- **U8** (`OpaAbacAutoConfigurationTest`, `ApplicationContextRunner`) — R4j present+enabled →
  `ResilientOpaClient`; R4j absent (`FilteredClassLoader`) → plain `HttpOpaClient`; master off → plain; OPA
  edge off → plain; a user `OpaClient` is still wrapped; properties bind; defaults are the asymmetric
  budgets; the config metadata carries `opa.abac.resilience.enabled`.

## Architecture review + refactor

_Filled at the ★ gate._ Findings — **nothing substantive to refactor; no invented churn.**
- **Build-breaker found + fixed in this commit:** the BPP wraps a user-supplied `OpaClient` too (correct —
  resilience applies to whatever client is present), which broke the existing `userBeansWin()` (U32) assertion
  that the bean is directly a `StubOpaClient`. Fixed by running that test with `resilience.enabled=false` (it
  tests the `@ConditionalOnMissingBean` override mechanism, orthogonal to B3) and adding a dedicated
  `userOpaClient_isWrapped_whenResilient()` for the wrap-the-user-bean case. The build-breaker landed in the
  same commit (DATA-FILTERING-T1 discipline).
- **Fail-closed / security:** every error/exhausted/breaker-open path lands on the delegate's fail-closed
  value; `compile` is `error()` in every failure state, never `denyAll()`/`allowAll()` (U5).
- **Boundary:** `HttpOpaClient`/`OpaClient`/`PartialResult` byte-for-byte unchanged; the starter wires a
  plain client when R4j is absent/disabled (U8).
- **BPP lazy guard init** via `volatile` + null-check is the correct pattern: it defers property access until
  after `@ConfigurationProperties` binding (a BPP is created before binding completes). One OPA breaker per
  decorator instance, shared across the three methods (Fork 5). Reviewed — left as is.

## Integration / e2e

N/A for T2 (the library feature is unit-proven incl. the recovery headline). The e2e headline is T4.

## Decisions

- **Retry on the fail-closed sentinel** (settled with the maintainer mid-run): the plain `HttpOpaClient`
  swallows every failure into its fail-closed value and never throws, so the decorator retries on the
  *returned* sentinel — `compile` on `fromError()` (exact), `allow`/`allowAll` on `false`. A genuine deny also
  retries, but the OPA gate is a local sidecar at 1 retry/~50ms and the decision is deterministic (a real
  deny stays `false`, never widens) → one extra fast hop on a deny, fail-closed intact; a transient blip
  recovers the real answer (the 00-DESIGN matrix "policy answer (retried)" row).
- **`BeanPostProcessor` over a second `@Bean OpaClient`** — decorates whatever client the context has
  (starter default or adopter bean) without competing for the bean name; the wrapper is transparent.

## Commit

`feat(resilience): resilient OpaClient decorator + starter auto-config (T2)`
