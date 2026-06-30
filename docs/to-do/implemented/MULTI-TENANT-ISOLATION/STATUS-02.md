---
tags:
  - status/done
  - type/project
  - area/abac
---

# STATUS — T2: GovernedScopeResolver SPI + HttpGovernedScopeResolver (catalog)

**Status:** ✅ DONE

## What shipped

- **`opa-abac-spring-data`** — `interface GovernedScopeResolver`
  (`dev.dmitriikonovalov.opaabac.data.filter`):
  `<T extends AbacDataObject> Specification<T> governedScope(String subject, String resourceType)` +
  a shared static `denyAll()` (the fail-closed sink: `cb.disjunction()` = always-false). Lives in
  spring-data because it returns a Spring Data `Specification` (so `opa-abac-core` stays Spring-free).
  The generic bound is `<T extends AbacDataObject>` (the design's `<T>` shorthand) to compose with
  `AbacQueryService.findAuthorized`, mirroring `SubtreeSpecResolver`.
- **`example-catalog-management-service`** (`…catalog.config`) — `HttpGovernedScopeResolver implements
  GovernedScopeResolver`: calls `GET {userService}/internal/governed-targets?subject&resourceType`,
  parses `["uuid",…]`, returns `id IN (ids)` or `denyAll()`. `@ConditionalOnProperty(catalog.role-source
  =http)` + the same `catalog.user-service.{base-url,timeout-ms}` coords as `HttpRoleDefinitionSupplier`.
  JDK `HttpClient` + Jackson (no Feign/RestTemplate/WebClient).

## Tests

- `HttpGovernedScopeResolverTest` (in-process `com.sun.net.httpserver.HttpServer` stub, no WireMock) —
  **11/11 pass**. U1 (`["id1","id2"]` → `id IN`, exact ids captured via a mocked Criteria probe), U2
  (`[]` → deny-all), U3 (5xx / 404 / connection-refused / timeout → deny-all, **no throw** — the
  keystone), U4 (malformed body / non-UUID element / blank body → deny-all), null/blank subject → deny-all
  with **no HTTP call**, and the request-URL shape.
- The probe invokes the returned `Specification` against a mocked `CriteriaBuilder`/`Root` and asserts
  which predicate it builds (`root.get("id").in(Collection)` vs `cb.disjunction()`) — distinguishes the
  governed path from the deny-all floor without a DB. (The `Path#in` overload that fires is
  `in(Collection)`, not the varargs — stub `any(Collection.class)`.)
- `./gradlew :opa-abac-spring-data:compileJava` + both modules' `compileTestJava` green.

## Architecture review + refactor

- **Fail-closed (keystone):** every non-affirmative outcome → `denyAll()`, **no throw** — unlike
  `HttpRoleDefinitionSupplier`'s tri-state-throw (B2). Rationale recorded in the javadoc: this resolver
  supplies the *base scope of a list*, so the only safe failure value is the empty list; a throw would
  500 the list instead of emptying it. A transport outage yields the SAME empty page as "governs
  nothing" — never the whole table. Proven by U2–U4 + the connection-refused/timeout cells.
- **Security:** a single bad element in the array discards the WHOLE result (no partial-parse widening);
  a non-200 never trusts the body; null/blank coords never even call out.
- **Module separation:** SPI in spring-data (returns `Specification`); impl in the catalog example
  `config`; `opa-abac-core` untouched. No Spring type in core.
- **Pattern reuse:** mirrors `HttpRoleDefinitionSupplier` (client style, `@ConditionalOnProperty`,
  `catalog.user-service.*`) and the `SubtreeSpecResolver`/`AncestorResolver` fail-closed floor.
- **Found, no churn:** review surfaced two trimmable unused imports in the test (removed) and the
  `Path#in` overload subtlety (fixed). No structural refactor needed — the SPI mirrors precedent cleanly.

## Wiring / open item (honest checkpoint note)

The SPI's **consumer is T4** (`CatalogListAuthorizer` will inject it via `ObjectProvider` and pass the
governed scope as the base scope to `findAuthorized`). T2 ships the seam + impl + the full non-happy-path
test set; the call site lands in T4 per the decomposition's critical path (T2→T3→T4). The
`HttpGovernedScopeResolver` bean is conditional on `catalog.role-source=http` (so the demo profile, the
default, gets no bean → T4's `ObjectProvider` resolves to absent → empty page, fail-closed). This is the
designed slice boundary, not a gap.

## Integration / e2e

N/A for T2 (pure unit, no Postgres). The different-row-sets cut (I1) is the T4 IT against real Postgres;
this ticket pins the SPI's fail-closed contract in isolation.

## Decisions

- **Fail-closed-to-empty (no throw), not tri-state-throw** — the SPI contract is "never throws,
  always-false on any breach", because it supplies a list's base scope (the outcome IS the safe value).
  B3-style resilience-retrying the GET is a possible later refinement (it would change only how often a
  transient blip lands on the empty floor, not the floor itself) — explicitly out of scope here.
- **`denyAll()` as a shared static on the SPI** — one fail-closed sink (`cb.disjunction()`), so every
  impl lands on the same empty-list floor rather than re-deriving an always-false predicate.

## Follow-up flagged (out of scope, spawned as a task)

`HttpRoleDefinitionSupplier`'s class javadoc (and some guides) still describe the now-removed blanket
realm fallback as a live access path. Spawned a tracked doc-reconciliation task (no behavior change) so
T2's commit stays focused.

## Commit

(see branch `feature/void3110/multi-tenant-isolation`)
