---
tags:
  - status/done
  - type/project
  - area/spring
  - area/abac
---

# STATUS — T5: Starter wiring — beans, partialEval properties, overridable (starter)

> Filled in at the T5 checkpoint during the autonomous run. See [[01-DECOMPOSITION]] T5 and the
> per-ticket loop in [[AUTONOMOUS-IMPLEMENTATION-PROMPT]].

**Status:** ✅ done

## What shipped

`opa-abac-spring-boot-starter`:

- **`OpaAbacProperties`** gains a nested `partialEval` group (`PartialEval{enabled=true,
  allowlistFallback=true}`). The `spring-configuration-metadata.json` regenerates with
  `opa.abac.partial-eval.enabled` + `.allowlist-fallback` (confirmed in the generated metadata).
- **`OpaAbacAutoConfiguration.DataFilteringAutoConfiguration`** (nested `@Configuration`,
  `@ConditionalOnClass("…JpaSpecificationExecutor")`) registers two `@ConditionalOnMissingBean` beans:
  - `ResidualSpecificationFactory` (no-arg);
  - `AbacQueryService` — injects the existing `OpaClient` + factory and maps the `partialEval` properties
    onto `AbacQueryService.PartialEvalSettings(enabled, allowlistFallback)`.
- The data-filtering config is **security-independent** (it needs JPA, not the web/security stack) and
  sits alongside the existing security-gated nested config. **No `SecurityFilterChain`** is registered.

## Tests

`./gradlew :opa-abac-spring-boot-starter:test` green (6 new + all pre-existing U30–U34).
`ApplicationContextRunner` slice tests:

- **I3** JPA on classpath + enabled → `ResidualSpecificationFactory` + `AbacQueryService` present.
- **I4** JPA removed (`FilteredClassLoader(JpaSpecificationExecutor.class)`) → both absent, the rest of the
  spine unaffected.
- **I5** a user-supplied `ResidualSpecificationFactory` / `AbacQueryService` override the auto ones
  (`@ConditionalOnMissingBean` — `isSameAs` the user factory, `isInstanceOf(StubQueryService)`).
- **I6** `opa.abac.partial-eval.enabled=false` + `allowlist-fallback=false` bind; **I6b** the defaults are
  both on.

## Architecture review + refactor

- **Starter doesn't seize the chain — verified:** no `@Bean` returns a `SecurityFilterChain` (the only
  `SecurityFilterChain` tokens are javadoc + the security config's `@ConditionalOnClass` name).
- **Conditional + overridable — verified:** the nested config is `@ConditionalOnClass(JpaSpecificationExecutor)`
  (I4) and both beans are `@ConditionalOnMissingBean` (I5).
- **No existing default changed; core/security main untouched** (`git diff`).
- Wiring follows the established starter idioms exactly — no refactor.

## Integration / e2e

`ApplicationContextRunner` is the integration check for wiring; the example app boots with these beans in T6.

## Decisions recorded

None new — the wiring is conventional (covered by the existing starter Mulch records). No new record.

## Commit

`feat(data-filtering): T5 starter wiring — partialEval beans + properties, conditional & overridable` — _(SHA at commit)_
