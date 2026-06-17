---
tags:
  - status/done
  - type/project
  - area/abac
  - area/spring
  - area/opa
---

# STATUS — T1: Relocate `AbacResourceCache` → core + the `Enrichable` marker (build-breaker sweep)

**Status:** ✅ DONE

## What shipped

- **`AbacResourceCache` interface relocated** `opa-abac-spring-security`
  (`dev.dmitriikonovalov.opaabac.security`) → **`opa-abac-core`**
  (`dev.dmitriikonovalov.opaabac.core`). Signatures byte-identical (`<T> Optional<T> get(String,
  String, Class<T>)` / `void put(String, String, Object)`). Javadoc hardened to pin the two invariants
  this slice depends on: **"an attribute snapshot, never a verdict"** (presence ≠ authorization) and
  **"never consulted by decisions."** No Spring import (only `java.util.Optional`).
- **`RequestAttributesResourceCache` (the impl) STAYS** in `opa-abac-spring-security` (uses
  `RequestContextHolder`); now `implements` the relocated core interface via an added
  `import dev.dmitriikonovalov.opaabac.core.AbacResourceCache;`. Behavior byte-identical.
- **New marker `Enrichable`** in `opa-abac-spring-security`, package
  `dev.dmitriikonovalov.opaabac.security.web` (new package). Methods: `UUID getId()`,
  `Map<String,Boolean> getActions()`, `void setActions(Map<String,Boolean>)`, **plus** the two binding
  methods `String abacResourceType()` + `List<String> abacActions()` (abstract on the base; the per-type
  sub-interfaces supply them as `default`s in T5/T6 — declared on the base so the T2 advice can call them
  off an `Enrichable` reference). Javadoc carries the `x-implements` recipe, the registry-via-sub-interface
  note, and the omit-on-failure / `readOnly` degrade contract. No Spring import (only `java.util`).

### Build-breaker sweep (every reference relocated in this commit)

Grepped `AbacResourceCache` across all modules + tests. Touched files (import flip / FQN swap only — zero
behavior change):

| Module | File | Change |
|---|---|---|
| core | `AbacResourceCache.java` | **moved here** (package line `…security` → `…core`); javadoc hardened |
| spring-security | `RequestAttributesResourceCache.java` | added `import …core.AbacResourceCache` |
| spring-security | `ResourceResolutionSupport.java` | added `import …core.AbacResourceCache` (field + ctor + javadoc) |
| spring-security | `OpaPreAuthorizeAuthorizationManager.java` | added `import …core.AbacResourceCache` (javadoc `{@link}`) |
| spring-security (test) | `OpaPreAuthorizeAuthorizationManagerResolutionTest.java` | added import; the `RecordingCache implements AbacResourceCache` stub now resolves |
| starter | `OpaAbacAutoConfiguration.java` | import flipped `…security` → `…core` (bean return + ctor param follow) |
| starter (test) | `OpaAbacAutoConfigurationTest.java` | 4 fully-qualified refs swapped `…security` → `…core` (impl FQN `…security.RequestAttributesResourceCache` left as-is) |
| example-catalog | `CatalogController.java` / `CategoryController.java` / `ProductController.java` | import flipped `…security` → `…core` (the `ObjectProvider<AbacResourceCache>` handler-read sites follow) |

Stale-reference sweep after: **zero** `opaabac.security.AbacResourceCache` references remain;
12 references now resolve to `…core.AbacResourceCache`.

## Tests

- **U1 ✅** `./gradlew build` green (all modules + example app + OpenAPI codegen + **real-Postgres
  Testcontainers ITs**). Targeted run first: `:opa-abac-core:compileJava`,
  `:opa-abac-spring-security:test`, `:opa-abac-spring-boot-starter:test` all green — the relocated-cache
  consumers (`userResourceCacheWins`, `noBeans_whenDisabled`, the resolution-manager stub) pass
  **unmodified bar the import line**. No behavior change.
- **Import-set proof:** the relocated interface and the `Enrichable` marker both carry **no
  `springframework` import** (`AbacResourceCache` → only `java.util.Optional`; `Enrichable` → only
  `java.util.{List,Map,UUID}`).

## Architecture review + refactor

Ran the ★ gate. **Nothing substantive to refactor — a pure relocation + a new marker, exactly as scoped.**

- **Fail-closed:** T1 adds no decision logic; no branch synthesizes a verdict. The marker javadoc *pins*
  the omit-on-failure contract for T2's consumer.
- **Security:** no widening — the impl is byte-identical; the interface javadoc now *strengthens* the
  cache-as-snapshot / never-consulted-by-decisions invariant rather than relaxing it.
- **Wiring:** the relocated interface's consumers all recompiled + tested. `Enrichable` is intentionally
  consumer-less in T1 (its consumer is `ActionEnrichmentAdvice`, T2) — the by-design, ticket-scoped
  exception; it compiles standalone in the build.
- **Boundary/additivity:** `opa-abac-core` stays Spring-free (proven above); the one-way module flow
  (`core ← spring-data/spring-security ← starter`) holds; `spring-security` already declared
  `api(project(":opa-abac-core"))`, so the import flip compiled with no build-file change.
- **Module-layer separation:** interface in core; impl in spring-security; marker in
  `…security.web` — per ADR 0016 §5.

## Integration / e2e

No T1-specific IT/e2e — T1 is a library-internal relocation. The full `./gradlew build` already exercised
every relocated consumer through the existing catalog ITs (real Postgres), all green.

## Decisions

- **`abacResourceType()` + `abacActions()` declared on the `Enrichable` base** (abstract), with the
  per-type sub-interfaces supplying them as `default`s (T5/T6). ADR 0016 §3 sketches them only on the
  sub-interface, but the T2 advice operates on `Enrichable` references and must call them there — so they
  belong on the base contract. No semantic change; the sub-interface still *is* the registry.
- The relocated-interface javadoc absorbed the **cache-as-snapshot** wording (decomposition semantic #3)
  so the invariant lives at the interface, not only in the ADR.

## Commit

`refactor(core): relocate AbacResourceCache to core + add Enrichable marker` — to follow.
