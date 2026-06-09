---
tags:
  - status/done
  - type/project
  - area/abac
  - area/spring-data
---

# STATUS T5 — Starter wiring + example list-authorizer adoption (the 4-arg call)

> Filled at the T5 checkpoint. One focused commit. ✅ shipped.

## What shipped

- **Starter** — a new `SubtreeSpecResolver` `@Bean` in the `HierarchyAutoConfiguration`, gated
  `@ConditionalOnBean(AncestorResolver.class)` + the existing `hierarchy.enabled=true` (so it follows the
  same opt-in, default-off posture as the rest of 5.5-A) and `@ConditionalOnMissingBean` (overridable). It is
  wired with the same `AncestorResolver` + `RoleDefinitionSupplier` as the single-resource authorizer, plus
  the inheritance declaration `properties.getHierarchy().getInheritable()`. No new property — the inheritance
  map + `maxDepth` already exist from 5.5-A.
- **Starter** — the `abacQueryService` bean already (T3) injects `ObjectProvider<AncestorResolver>`; no
  further change here.
- **Example** — `CategoryListAuthorizer` injects `ObjectProvider<SubtreeSpecResolver>` and, on each
  `readable(catalogId, parentId)` call, asks it whether the subject's role on the governing Catalog
  inheritably grants `category:read`; the resolved `subtreeSpec` (or `null`) is passed into the **4-arg**
  `findAuthorized`. The `catalogId(+parentId)` scope is unchanged (still AND-ed first). With hierarchy off /
  no inheritable grant → `null` → exactly the prior tag-only behavior.

## Tests

- **Starter `OpaAbacAutoConfigurationTest`** — 3 new (U13–U14): the `SubtreeSpecResolver` bean is present
  when hierarchy is enabled + a source is supplied; **absent by default** (off); **overridable** by an
  app-supplied bean. All green.
- **`./gradlew build` green** — all modules + **both** example apps + Testcontainers ITs + the
  `ddl-auto: validate` boot. The catalog app boots clean with the new `SubtreeSpecResolver` wiring and the
  4-arg adoption — confirming **no schema change** and no OpenAPI shape change.
- The existing catalog ITs (`CatalogCrudIT`, list ITs) stay green under the permissive test profile.

## Architecture review + refactor (the ★ gate)

- **Module separation:** the `SubtreeSpecResolver` + the composition live in the library; the example only
  *calls* the 4-arg overload via an `ObjectProvider` (absent → tag-only). No OPA-wire knowledge added to the
  example beyond what's shipped.
- **Conditional + overridable:** `@ConditionalOnBean(AncestorResolver)` + `@ConditionalOnMissingBean`
  (U13/U14 prove default-off + override). Same opt-in/default-off as 5.5-A.
- **Fail-closed:** the example's `resolveSubtreeSpec` returns `null` when no resolver bean / no inheritable
  grant → tag-only (never wider). The widening is still AND-ed inside the `catalogId` scope (T3's composition).
- **No schema/contract change:** `ddl-auto: validate` boot passed; the list response is the same array.
- **Refactor applied:** none structural — the design held.

## Integration / e2e

- The full `./gradlew build` runs the example ITs + the boot. The **gateway** e2e (real row-set widening
  through APISIX) is **T6**.

## Decisions

- **The product list is not adopted in this slice.** `ProductController.listProducts` does **not** use the
  Phase-5 `AbacQueryService` partial-eval path at all — it is a plain scoped query
  (`products.findByCategoryId`) under the type-level `@OpaPreAuthorize` gate. Widening it would first require
  giving it the Phase-5 residual filter (a separate Phase-5-style adoption), which is out of this slice's
  minimal scope. The **category** list is the concrete, proven widening path (and the one the T6 e2e
  exercises). Noted here rather than silently expanding scope — the design's "(and the product list path)"
  is deferred with this rationale.

## Commit

`feat(starter,example): wire SubtreeSpecResolver + category list adopts the 4-arg findAuthorized` on
`feature/void3110/hierarchy-list-filter`.
