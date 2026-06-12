---
tags:
  - status/planned
  - type/architecture
  - area/abac
  - area/spring
  - area/architecture
---

# 00 — Design: Resource resolution / attribute-rich pre-authorization (Phase 5.97)

> The design, written from a settled **ADR [[0013-attribute-rich-pre-authorization|0013]]** (which pins
> every fork below; interview 2026-06-12). A **gate-semantics** change scoped to the single-resource
> path: the `@OpaPreAuthorize` manager resolves the instance behind a declared `resourceId` and decides
> on real attributes + ancestors, with a request-scoped cache binding the handler to the authorized
> version. **Zero Rego changes; `AbacQueryService` and the four query paths untouched; user-mgmt
> untouched; opt-in by bean presence.**

## 1. The problem, precisely

The gate is reference-based — `(type, id)`, empty attributes, **leaf** role lookup. Today that means,
per surface (all verified in code, 2026-06-12):

| Surface | Today | Consequence |
|---|---|---|
| `GET /categories/{id}` | **no annotation at all** — load-then-check via `CategoryAuthorizer` → `HierarchicalAuthorizer` | the only place tag + inherited grants work per-instance |
| id'd writes (`PUT`/`DELETE` category, product) | annotated, but under the HTTP role source the leaf lookup finds no role (`ExactTeamTargetMatcher` matches targets exactly) → role-def `null` → the policy's **realm-role FALLBACK** decides, **tag-blind** | team/tag model does not govern id'd writes; a `catalog-editor` realm member writes regardless of tags |
| type-level checks (lists, creates) | realm fallback / coarse list-gate clause | out of scope here (Phase 6.5 owns the action-vocabulary question) |
| gate→handler | two loads on the path that has both; no version binding | TOCTOU window undetected |

## 2. The mechanism (ADR 0013 §1–§4)

### Core (`opa-abac-core` — Spring-free, additive)

```java
public interface AbacResourceResolver {
    Optional<AbacDataObject> resolve(String resourceType, String resourceId);
}
public interface AncestorChainSupplier {
    List<ParentRef> ancestorsOf(String resourceType, String resourceId);
}
public interface Versioned {
    Integer getVersion();
}
// VersionGuard.requireUnchanged(Versioned snapshot, Versioned current) throws VersionConflictException
```

### The manager flow (`opa-abac-spring-security`)

For an annotation with a declared, resolvable `resourceId`, when a resolver is wired **and**
`opa.abac.resource-resolution.enabled=true`:

1. `resolver.resolve(type, id)` — empty/throws → **DENY** (never an attribute-less fallback).
2. `ancestorChainSupplier.ancestorsOf(type, id)` — throws → **chain collapses to `List.of()`**
   (direct-grant-only; the 5.5 posture). Supplier absent → empty chain (flat resource).
3. Role looked up **once on the governing root**: `ancestors.isEmpty() ? leaf : ancestors.get(0)` —
   exactly `HierarchicalAuthorizer`'s rule.
4. `AbacContext` carries the instance's `abacAttributes()` **and** the chain → `opaClient.allow`.
5. On **allow**: `cache.put(type, id, instance)`. The `resource()`-SpEL path also puts. Deny puts
   nothing. The gate never *reads* the cache.

No resolver bean, or kill-switch off → steps 1–5 skipped; today's context, byte-identical. Type-level
checks (no `resourceId`) never engage the resolver.

### The cache (`opa-abac-spring-security`)

`AbacResourceCache` (interface + request-attributes default via `RequestContextHolder`):
`<T> Optional<T> get(String type, String id, Class<T> as)` / internal `put`. Outside a web request —
no-op (resolution still feeds the decision; only reuse is lost). Never consulted by decisions.

### The starter (`opa-abac-spring-boot-starter`)

Auto-config composes what the modules can't see of each other: when the app registers an
`AbacResourceResolver` and a 5.5 `AncestorResolver` bean exists, bind `AncestorChainSupplier` to
`AncestorResolver::ancestorsOf`; wire both + the cache into the manager. New property
`opa.abac.resource-resolution.enabled` (default `true`) on the existing `opa.abac` prefix.

### Version binding (ADR 0013 §5)

- Read handlers: `cache.get(...)` and return the snapshot — the response is the authorized state.
- Mutating handlers: load fresh in-transaction (as today), then
  `VersionGuard.requireUnchanged(snapshot, fresh)` — drift → `VersionConflictException` → advice maps to
  **`409 STATE_CONFLICT`** (existing `LibraryErrorCode`; existing problem+json plumbing). Never persist
  the snapshot (detached-merge footguns).
- `BaseModel extends Versioned` is **the only spring-data diff** (the method already exists on it).

## 3. Behavior matrix (the cells that change — and the ones that must not)

Subjects: under the HTTP role source, on **id'd** category/product endpoints.

| Caller | Today (leaf 204 → fallback) | After 5.97 (root role + tags) | Cell |
|---|---|---|---|
| Member, team role grants write, **tags match** | decided by realm role (tag-blind) — `viewer`-realm member: **403** | **200** | **headline flip** |
| Member, team role grants write, **tags mismatch** | `editor`-realm member: **200** (!) | **403** | **the fallback hole closes** |
| Member, team role read-only, `editor` realm role | **200** via fallback (!) | **403** (role-def present → fallback disabled) | intended narrowing |
| Non-member (any realm role) | fallback decides | fallback decides (no role-def at root either) | **unchanged** |
| Root-granted member, deep category, `GET` | 200 via layer-3 | 200 via the gate | parity (inherited grant survives) |
| Any caller, kill-switch off / no resolver bean | today's behavior | today's behavior | byte-identical |

`getCategory` gains the annotation it never had (`category:read`, `resourceId = "#categoryId"`), drops
the `categoryAuthorizer.require` call, and reads the entity from the cache. The URL-scope rule stays in
the handler against the cached instance (`entity.catalogId == path catalogId` else **404**, as
`findByIdAndCatalogId` enforces today — the resolver loads by id alone and must not absorb routing
semantics).

## 4. Catalog adoption (the example diff)

- One `CatalogResourceResolver` bean (~15 lines): switch over `catalog`/`category`/`product` repos.
- **`CategoryAuthorizer` deleted** (single call site). `HierarchicalAuthorizer` **stays** — it is the
  library's programmatic alternative for non-annotation flows and tests.
- Mutating flows adopt the version guard (`updateCategory`, `deleteCategory`, product/catalog
  equivalents); read flows adopt cache reuse.
- **user-mgmt registers nothing** — byte-identical, the live opt-in coexistence proof. Its
  `/internal/effective-role` API is untouched: the governing-root lookup sends `("catalog", id)`, which
  the exact matcher already resolves.

## 5. What this slice does NOT change

- **Zero Rego.** The fallback clause, list-gate clause, `tags_satisfied`, `inherited_grant` all stay;
  richer input feeds existing rules.
- **`AbacQueryService` + all four `findAuthorized` paths**; `CategoryListAuthorizer`; pagination.
  List-path cache population is **deferred to Phase 6** (its consumer).
- **Type-level gates** (lists, creates) and the `resource()` path semantics (it now also populates the
  cache, decisions unchanged).
- **Annotations on existing endpoints**: zero byte changes except `getCategory`, which *gains* one.
- **user-mgmt service** end to end; the demo role source; `AbacFilter`/extraction; error contract
  (reuses `STATE_CONFLICT`).
- **The check-then-act window for unversioned resources** — documented degrade, not silently accepted.

## 6. Proof obligations (QA skeleton — cases get ids in 10-QA)

- **Unit (manager, HttpServer OPA stub):** resolver absent / kill-switch off → context byte-identical;
  resolver present → attributes + ancestors + root-role lookup; resolver empty/throws → deny; ancestor
  throw → direct-only; cache put-on-allow + `resource()` path, none on deny; no request context → no-op.
- **IT (real Postgres):** the version-guard race, deterministically (resolve v1 → out-of-band bump →
  guard → 409 `STATE_CONFLICT`); catalog adoption — tag-restricted write allow/deny at the gate,
  `getCategory` parity incl. scope-mismatch 404.
- **e2e (new matrix, fixture catalog `88888888-8888-8888-8888-888888888888`):** the four flip/parity
  cells of §3 through APISIX, plus the whole existing suite green (coexistence). No kill-switch e2e cell
  (unit + IT cover it; a second rig deployment isn't worth the cell).

## 7. Forks already closed (do not reopen during decomposition)

ADR 0013's considered-options table: central rule registry · tags-only scope · app-assembled rich SPI ·
per-type beans · snapshot-persisting mutations · re-authorize-on-drift · ABAC-only version counter ·
`@RequestScope` cache · no kill-switch. Plus, from the interview record: Phase-6 list population
deferred; creates/`POST` stay type-level pending the 6.5 vocabulary; `STATE_CONFLICT` reused, no new
error code.

## Related

- ADR [[0013-attribute-rich-pre-authorization|0013]] — every fork above, with rejections.
- [[RESOURCE-RESOLUTION]] — the slice index (direction note).
- [[ACTION-ENRICHMENT]] (consumer) · [[POC-ROADMAP]] (order 5.97 → 6.5 → 6).
- Guides to touch: a new mechanism guide + [[TAG-BASED-AUTHORIZATION]] (the flip),
  [[HIERARCHICAL-AUTHORIZATION]] (gate vs programmatic), [[ABAC-AUTHORIZATION]] (layer description),
  the `OpaPreAuthorize` Javadoc "later phase" sentence.
