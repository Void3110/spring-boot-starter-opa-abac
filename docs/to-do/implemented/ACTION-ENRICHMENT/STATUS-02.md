---
tags:
  - status/done
  - type/project
  - area/abac
  - area/spring
  - area/opa
---

# STATUS — T2: `ActionEnrichmentAdvice` (`ResponseBodyAdvice`) + P×V refold + omit-on-failure

**Status:** ✅ DONE

## What shipped

- **`ActionEnrichmentAdvice`** (`opa-abac-spring-security`, package
  `dev.dmitriikonovalov.opaabac.security.web`) — a `@RestControllerAdvice` implementing
  `ResponseBodyAdvice<Object>`. Constructor deps: `OpaClient`, `AbacResourceCache`,
  `RoleDefinitionSupplier`, and a nullable `AncestorChainSupplier` (flat deployment ⇒ each resource is
  its own governing root).
  - **`supports()` returns `true`** (cheap type-only gate); the *body* is inspected in
    `beforeBodyWrite` because a paged envelope's element type is not visible from the return type alone.
    A body that yields no `Enrichable` targets is returned unchanged with **no OPA call** (verified by
    U2 — `String` and `null`).
  - **`collectEnrichable(body)`** handles the three shapes: a single `Enrichable`, an
    `Iterable<Enrichable>` (a heterogeneous collection with a non-`Enrichable` element → treated as
    not-enrichable), and a **paged envelope** detected structurally via a no-arg `getItems()` returning a
    `List` (reflection — never a compile-time dependency on the example DTOs; the ADR-0012 `<Resource>Page`
    shape).
  - **One `allowAll` per resource type:** rows are grouped by `abacResourceType()` (defensive — today's
    responses are homogeneous), one batch per group, never a mixed batch.
  - **Per-row preparation** mirrors the gate's governing-root rule verbatim: resolve the cached snapshot
    (`cache.get(type, id, AbacDataObject.class)` → its `abacAttributes()`), resolve the ancestor chain
    (null supplier ⇒ empty), pick `governingRoot = ancestors.isEmpty() ? leaf : ancestors.get(0)`, look up
    the role on the governing root via `RoleDefinitionSupplier`.
  - **The flat `rows × verbs` context list** in row-major order (row *i*, verb *j* → index *i·V+j*), each
    context = the row's resolved `(type, id, attributes, ancestors)` + the re-qualified `"type:verb"`
    action + the subject + the governing-root role. **Re-fold** the positional `List<Boolean>` into a
    per-row `Map<verb,Boolean>` with **bare-verb keys**, `setActions(map)`.
  - **Subject** from `AbacAuthentication.getSubject()` (the same source the gate uses); no
    `AbacAuthentication` → omit all.

## Tests

- **U2–U9 ✅** `:opa-abac-spring-security:test` green (12 advice cases + the full existing suite, no
  regression). Drives `beforeBodyWrite` directly with stub `Enrichable` DTOs, a `MapResourceCache`
  double, a programmable `OpaClient` (positional `List<Boolean>`, or throws), and a stub
  `RoleDefinitionSupplier`; `AbacAuthentication` set on the `SecurityContextHolder` per test.
  - **U3** the P×V refold (3 rows × 4 verbs, a known 12-bool list → each row's correct map; **one** bulk).
  - **U4** single resource all-allowed — asserts the V contexts carry `"category:view/update/delete/
    assign-tags"` + the resolved `(type,id,attributes)`.
  - **U5** honest `false` (the headline) — `{view:true, update:false, delete:false, assign-tags:false}`.
  - **U6** `bulk` throws → omit; short list → omit; **all-`false` → omit** (the maintainer-decided branch).
  - **U7** cache miss for the middle row of a 3-row page → that row omitted, the other two enriched; the
    batch carries only the 2 cached rows (8 contexts).
  - **U8** ancestor-supplier throws → omit (no batch issued); `RoleResolutionException` → omit.
  - **U9** cache-is-snapshot — a cached instance never short-circuits; a fresh `allowAll` always runs.

## Architecture review + refactor

Ran the ★ gate. **Nothing substantive to refactor** — the one real design decision (below) was settled
with the maintainer and is implemented + tested; the rest of the lenses pass clean.

- **Fail-closed:** every failure class omits; `setActions` has **exactly one call site**, inside the
  `if (anyTrue)` guard. There is **no branch that synthesizes an all-`false` (or any) map on failure**
  (verified by grep + U6/U7/U8). A present map is complete (every verb keyed) and has ≥1 `true`.
- **Security:** all-`false`-as-deny removed (→ omit); the cache is read **only** for `abacAttributes()`,
  never as a verdict (U9); the `_actions` map is `Map<verb,Boolean>` only — no attribute can leak into it;
  `beforeBodyWrite` returns the same `body` reference and mutates only each DTO's `_actions` — never the
  status, never the body shape.
- **Concurrency/idempotency:** reads the snapshot written at gate/query time, never re-resolves (no drift
  between rows shown and verdicts); a pure read + per-DTO mutation, naturally idempotent.
- **Wiring:** the advice consumes the T1 `Enrichable` marker; its own registration is T4 (the starter's
  conditional `@Bean`). `@RestControllerAdvice` is only a discovery hint — with no no-arg ctor the bean
  exists only when the starter creates it (same idiom as the 0011 `PersistenceConflictProblemAdvice`); the
  library is auto-config-wired, not component-scanned, so it never self-registers unexpectedly.
- **Boundary/additivity:** the advice imports **only** core types + the `Enrichable` marker +
  `AbacAuthentication` (own module) + Spring web/security — **no `opa-abac-spring-data` import**, and
  `opa-abac-spring-security/build.gradle.kts` has **no spring-data dependency** (both verified by grep).
  `OpaClient` is reused verbatim (no new method).
- **Pattern-reuse:** governing-root rule mirrors `HierarchicalAuthorizer`; `allowAll` is the Phase-5
  primitive; `@RestControllerAdvice` mirrors the ADR-0011/0012 idiom.
- **Considered + rejected churn:** narrowing `supports()` by return type (can't see a page's element type
  → wouldn't help); guarding the `getItems()` reflection against per-response `NoSuchMethodException` (the
  cost is negligible beside the DB+OPA round-trip; the catch is the standard idiom). Left as-is.

## Integration / e2e

None T2-specific (the advice is unit-tested in isolation with a programmable `OpaClient`); the real-OPA-
stub ITs against Postgres land in **T5** (`ActionEnrichmentIT` / `ActionEnrichmentListIT`), which exercise
the advice end-to-end once the catalog DTOs are `Enrichable` and the starter wires the bean (T4).

## Decisions

- **The bulk-failure-detection fork (resolved with the maintainer):** `HttpOpaClient.allowAll` **never
  throws and never returns a short list** — it fails closed to a *full-length all-`false`* list on every
  error (non-200, timeout, malformed, mixed-type). So the advice cannot distinguish a transport failure
  from a genuine all-deny by the returned booleans alone, and the decomposition's assumed signals
  ("throws / short list") do not fire for the production client. **Decision: treat an all-`false` verb
  block as could-not-compute → omit** (zero `OpaClient` change; no extra round-trip; strictly fail-closed
  — it can never fabricate). Cost: a *genuinely* fully-denied resource (rare — a caller who reached
  enrichment already passed a gated read, so `view` is normally `true`) loses its honest all-`false` map.
  The advice still also handles a throwing custom client and a short/mismatched list (both → omit), so the
  contract holds for any `OpaClient`.

## Commit

`feat(spring-security): action enrichment advice (P×V refold, omit-on-failure)` — to follow.
