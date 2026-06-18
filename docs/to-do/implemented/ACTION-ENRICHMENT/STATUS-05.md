---
tags:
  - status/done
  - type/project
  - area/abac
  - area/spring
  - area/opa
---

# STATUS — T5: Catalog adoption: 3 `<Type>Enrichable` sub-interfaces + 3 schema blocks + codegen + ITs

**Status:** ✅ DONE

## What shipped

- **Three app-owned sub-interfaces** in `dev.dmitriikonovalov.example.catalog.security` (the package the
  decomposition/ADR specify), each `extends Enrichable` with `default abacResourceType()` +
  `default abacActions()`:
  - `CatalogEnrichable` → `catalog`, `[view, update, delete]`
  - `CategoryEnrichable` → `category`, `[view, update, delete, assign-tags]`
  - `ProductEnrichable` → `product`, `[view, update, delete]`
  Verb sets are the **VERIFIED** ones (the scout confirmed the live `@OpaPreAuthorize` endpoints —
  `category:assign-tags` is dispatched via `TagDecisionGate`; catalog/product carry no tags, no
  assign-tags endpoint).
- **`catalog-api.yaml`:** `x-implements: [ …security.<Type>Enrichable ]` + a `readOnly` `_actions`
  property (`type: object, additionalProperties: {type: boolean}`) added to the `Catalog`, `Category`,
  and `Product` schemas. `CatalogRequest`/`CategoryRequest`/`ProductRequest` untouched (`_actions` is
  `readOnly`, never on input).
- **ITs** (`ActionEnrichmentIT` + `ActionEnrichmentListIT`, real Postgres + a context-aware OPA stub whose
  `allowAll` decides each `(action, resolved-attributes/ancestors)` per context).

## Codegen fit — RESOLVED (the slice's one open mechanism)

The generated DTOs come out **exactly** as ADR 0016 §2 predicted, with **no generator config change**:

```java
public class Category implements dev.dmitriikonovalov.example.catalog.security.CategoryEnrichable {
    private Map<String, Boolean> actions = new HashMap<>();
    @JsonProperty("_actions")
    public Map<String, Boolean> getActions() { ... }
    public void setActions(Map<String, Boolean> actions) { ... }
    public UUID getId() { ... }
}
```

- The `spring` generator (v7.11.0, `interfaceOnly=true`) reads `x-implements` → `implements
  <Type>Enrichable`. **Confirmed for all three** (Catalog/Category/Product).
- A property named **`_actions`** → the generator strips the leading underscore for the JavaBeans
  accessors → **`getActions()`/`setActions()`** (matching the `Enrichable` contract exactly), with
  `@JsonProperty("_actions")` binding the wire key. `type: object, additionalProperties: {type: boolean}`
  → `Map<String, Boolean>` (matching `Enrichable`). **No `additionalModelTypeAnnotations` / naming config
  needed** — the default mapping is the right one.

## Tests

- **I1–I6 ✅** `./gradlew build` green (all modules + both examples + OpenAPI codegen + every real-Postgres
  IT).
  - **`ActionEnrichmentIT`** (I3/I2/I4/I5/I6): the honest-`false` headline (`view:true, update:false,
    delete:false, assign-tags:false` on one read-only category); full-allow map; the map mirrors resolved
    tags (apac → `update:false`); the deep `product` reflects the governing-root role (the enrichment
    context carried the catalog root in its ancestors); `_actions` round-trips as the wire key `_actions`
    coexisting with the resource fields; **catalog & product verb sets exclude `assign-tags`** (the key is
    absent), category keeps it.
  - **`ActionEnrichmentListIT`** (I1/I2): **the no-second-SELECT write-through proof** — a
    `GET /categories` page enriches each row from the T3 write-through cache, and the `@MockitoSpyBean`
    `CategoryRepository.findById` is **never** called by the advice; per-row maps reflect each row's own
    tags (emea `update:true`, apac `update:false`) on one page.
- **`opa test` 177/177 PASS, unmodified** (zero Rego change — clean git status on the policy dirs).

## Architecture review + refactor

Ran the ★ gate. **One substantive regression caught by the full build, fixed; two reusable insights
recorded.**

- **The regression (the headline finding — caught by `./gradlew build`, 33 catalog ITs failing):**
  enabling the example DTOs activated `ActionEnrichmentAutoConfiguration` for the *first time* against the
  real app, and exposed that the T4 condition was wrong. The catalog ships a `CatalogResourceResolver`
  `@Component` (always present), so the advice config — gated only on `@ConditionalOnBean(resolver)` —
  activated even in the **resolution-OFF** IT suites (`SupplierOutageGateIT`, the CRUD/pagination suites,
  which boot with `resource-resolution.enabled=false`). With resolution off, **no `AbacResourceCache` bean
  is produced**, so the advice's required cache parameter failed context startup
  (`NoSuchBeanDefinitionException`). **Fix:** gate the advice on `@ConditionalOnBean(AbacResourceResolver)`
  **AND** an `AllNestedConditions` ANDing `resource-resolution.enabled` + `action-enrichment.enabled`
  (both `matchIfMissing=true`) — the exact pair of conditions under which the cache bean exists.
  (`@ConditionalOnBean(AbacResourceCache.class)` was re-tried and **re-confirmed order-unreliable** — U11
  failed again — so the bean-condition path stays rejected; gate on the user-supplied resolver + the
  properties instead.) Added starter test `actionEnrichmentAdviceAbsent_whenResolutionDisabled` to lock it.
- **The test-coupling finding:** `ResourceResolutionGateIT` runs with resolution ON and asserts the
  governing-root role is looked up **exactly once** (by the gate). With enrichment on, a GET *also* runs
  the read-side advice, which independently resolves the governing-root role per enriched row — correct,
  but a second lookup. **Fix:** that gate suite now sets `action-enrichment.enabled=false` (it pins the
  *gate's* semantics in isolation; enrichment has its own ITs). A behavioral truth worth noting: with
  enrichment on, a single GET does the gate's role lookup **and** the advice's — two lookups, by design.
- **Fail-closed / security / boundary:** unchanged from T2/T3 and re-proven live — the honest-`false` cell
  (I3), the verb-set exclusions (I6), `_actions` additive + `readOnly` (the `*Request` schemas untouched),
  every `@OpaPreAuthorize` byte-identical (all gate ITs green), zero Rego change (`opa test` unmodified).

## Integration / e2e

`./gradlew build` (real Postgres) is green end-to-end. The live cross-service e2e through APISIX is **T6**.

## Decisions

- **Package `…catalog.security`** for the sub-interfaces (per the decomposition/ADR), not the generated
  `…openapi.model` tree nor `…config` — a focused app-owned package, regeneration-safe.
- **The auto-config condition is the resolver bean + both properties (`AllNestedConditions`)** — see the
  regression above. This supersedes the T4 single-`@ConditionalOnProperty` + `@ConditionalOnBean(resolver)`
  shape (T4's STATUS noted the resolver gate; T5 found it insufficient for resolver-present-resolution-off
  and hardened it). Recorded into the existing Mulch `@ConditionalOnBean` failure record.

## Commit

`feat(example): adopt action enrichment in catalog (3 markers + schema + ITs)` — to follow (folds the
starter auto-config condition fix, since it is what makes the example ITs boot).

## Open follow-ups (non-blocking)

- Starter test `EnrichmentRow` (a record used as a `JpaSpecificationExecutor<EnrichmentRow>` stub) draws a
  benign "type parameter S should not be bounded by final type" warning on the never-called `findBy`
  override — test-only, harmless; left as-is.
