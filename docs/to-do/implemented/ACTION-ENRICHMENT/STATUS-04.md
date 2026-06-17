---
tags:
  - status/done
  - type/project
  - area/abac
  - area/spring
  - area/opa
---

# STATUS — T4: Starter auto-config wiring + `opa.abac.action-enrichment.enabled` kill-switch

**Status:** ✅ DONE

## What shipped

- **`OpaAbacProperties.ActionEnrichment`** group — `enabled` (default **true**), prefix
  `opa.abac.action-enrichment` (field + getter + `@NestedConfigurationProperty`, mirroring the
  `ResourceResolution` group). `spring-configuration-metadata.json` regenerates to carry
  `opa.abac.action-enrichment.enabled`.
- **`ActionEnrichmentAutoConfiguration`** nested class — registers the `ActionEnrichmentAdvice` bean
  (`@ConditionalOnMissingBean`), wired with the `OpaClient`, the `AbacResourceCache`, the
  `RoleDefinitionSupplier`, and the `AncestorResolver` bound as an `AncestorChainSupplier` via the same
  `ObjectProvider` idiom the 5.97 / data-filtering wiring uses (absent ⇒ flat). Conditions:
  `@ConditionalOnWebApplication(SERVLET)` + `@ConditionalOnProperty(...enabled, matchIfMissing=true)` +
  **`@ConditionalOnBean(AbacResourceResolver.class)`** (see Decisions — the cache-vs-resolver gate fix).
- **The `abacQueryService` bean** now injects `ObjectProvider<AbacResourceCache>` and passes the cache
  collaborator to `AbacQueryService` **only when `actionEnrichment.enabled`** — so a kill-switch-off boot
  wires the pre-Phase-6 `AbacQueryService` with no write-through (the byte-identical rollback path). The
  cache bean itself still comes from `ResourceResolutionAutoConfiguration`.

## Tests

- **U11–U13 ✅** `:opa-abac-spring-boot-starter:test` green (44 tests — 6 new + the full existing suite,
  no regression), via `ApplicationContextRunner`/`WebApplicationContextRunner`, mock-free (real configs):
  - **U11** defaults + web + a resolver → the advice bean is present; the cache bean exists.
  - **U12** `opa.abac.action-enrichment.enabled=false` → **no** advice bean.
  - **U11/U12** `writeThroughCollaborator_wiredOnlyWhenEnabled` — a behavioral probe: a coarse-path
    `findAuthorized` over a hand-rolled one-row repo + a recording cache **populates the cache when
    enabled** and **caches nothing when disabled** (proves the collaborator is wired iff enrichment is on).
  - **U13** non-web → no advice; a user-supplied advice bean overrides (`@ConditionalOnMissingBean`); the
    generated metadata carries `opa.abac.action-enrichment.enabled`.

## Architecture review + refactor

Ran the ★ gate. **One substantive fix during fix-until-green** (the `@ConditionalOnBean` ordering hazard,
below); the lenses otherwise pass.

- **Fix (caught by U11 failing):** the advice config first gated on `@ConditionalOnBean(AbacResourceCache
  .class)`, but the cache is produced by *another* auto-config bean (`ResourceResolutionAutoConfiguration`)
  — and `@ConditionalOnBean` evaluated against an auto-config-produced bean is **order-fragile** (Spring's
  documented hazard), so the advice config saw no cache yet and registered nothing. **Re-gated on
  `@ConditionalOnBean(AbacResourceResolver.class)`** — the *same* condition that produces the cache, and
  the resolver is the user-supplied bean (reliably present when the runner registers it), exactly how the
  cache bean itself is gated. U11 then passed.
- **Fail-closed:** the kill-switch off-state is the rollback — no advice bean **and** no write-through
  collaborator (both proven). No fabrication surface added.
- **Security:** wiring-only; off ⇒ byte-identical; gating on the resolver means enrichment never activates
  without the 5.97 cache feed (never runs against unresolved attributes).
- **Wiring:** every seam has a consumer + a non-happy-path test — advice bean (present/off/non-web/
  override), the property (off-state + metadata), the `AbacQueryService` cache collaborator (enabled→writes,
  disabled→dormant).
- **Boundary/additivity:** the 5.97 / data-filtering / hierarchy / security auto-configs + their conditions
  are untouched (an additive nested class + one bean-method parameter); no `SecurityFilterChain` ownership;
  the full existing 44-test suite passed unchanged.
- **Pattern reuse:** the advice's `ObjectProvider<AncestorResolver>` → `AncestorChainSupplier` binding is
  the exact 5.97 idiom; the kill-switch property mirrors `resource-resolution.enabled`.

## Integration / e2e

T4 is `ApplicationContextRunner`-proven (bean conditions + the write-through behavioral probe). Live
behavior (the advice actually attaching `_actions` over real Postgres) lands in **T5** once the catalog
ships an `Enrichable` DTO — that IT exercises T2+T3+T4 end-to-end.

## Decisions

- **Gate the advice on `@ConditionalOnBean(AbacResourceResolver.class)`, not `AbacResourceCache.class`.**
  The cache is an auto-config-produced bean, so `@ConditionalOnBean` on it is order-sensitive and silently
  failed (the bug U11 caught). The resolver is the user-supplied feature trigger (the same gate the cache
  bean uses), reliably present, and semantically correct: no resolver ⇒ no resolved attributes ⇒ nothing
  to enrich. Recorded as a Mulch `failure` insight.

## Commit

`feat(starter): wire action-enrichment advice + write-through behind the kill-switch` — to follow.
