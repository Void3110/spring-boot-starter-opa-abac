---
tags:
  - status/active
  - type/guide
  - area/abac
  - area/opa
  - area/spring
---

# Attribute-rich pre-authorization — resource resolution at the gate

> How an id'd `@OpaPreAuthorize` decision stops being reference-based: with one app-registered
> resolver bean, the gate loads the **instance** behind a declared `resourceId` and decides on its
> real attributes (tags) and ancestor chain, the role looked up once on the **governing root** — so
> tag grants, tag-keyed denies, and inherited grants are decided **declaratively, before the handler
> runs**. Phase 5.97, pinned by ADR [[adr/0013-attribute-rich-pre-authorization|0013]].

## The problem it solves

Pre-5.97 the gate named a resource by `(type, id)` with **empty attributes** and looked the role up
on the **leaf** id. Three consequences:

1. **Tag rules were undecidable at the gate** — per-instance tag checks needed a post-load handler
   check (the catalog example's `CategoryAuthorizer` existed solely for this).
2. **Id'd member decisions leaked to the realm fallback.** Under the HTTP role source, team targets
   match exactly, so a leaf lookup (`("category", id)`) resolved no role and the policy's fallback
   decided from JWT realm roles, **tag-blind** — a member's realm role granted writes their team
   role's tags deny.
3. **The gate-then-handler double load**, with no detection of state changing in between (TOCTOU).

## The mechanism

```
@OpaPreAuthorize(resourceId = "#id")            AbacResourceResolver (the app's ONE bean)
        │                                                │
        ▼                                                ▼
  gate resolves the INSTANCE ──► ancestors (starter-bound to the 5.5 AncestorResolver)
        │                                                │
        ▼                                                ▼
  role looked up ONCE on the governing root (ancestors[0], else the leaf)
        │
        ▼
  AbacContext { resource.attributes = instance.abacAttributes(), resource.ancestors = chain } ──► OPA
        │
        ▼ on ALLOW
  AbacResourceCache.put(type, id, instance)     — the handler reuses the authorized snapshot
```

- **`AbacResourceResolver`** (core, Spring-free): `Optional<AbacDataObject> resolve(type, id)` — the
  app implements **one** dispatching bean (the catalog example: a 15-line switch over three
  repositories, loading **by id alone** — URL scoping stays in the handler).
- **`AncestorChainSupplier`** (core): bound by the **starter** to the hierarchy module's
  `AncestorResolver` when one is configured — apps never implement it.
- **`AbacResourceCache`** (spring-security): request-scoped (request attributes), write-through on
  allow, a clean no-op outside web requests, **never read by decisions** — every evaluation resolves
  fresh.
- **Version binding**: read handlers return the snapshot (the response is the state the decision
  saw); mutating handlers load fresh in-transaction and call
  `VersionGuard.requireUnchanged(snapshot, fresh)` **before any write** — drift throws
  `VersionConflictException` → **`409 STATE_CONFLICT`** problem+json; the client re-reads and
  retries, and the retry's gate decides on the new state. The one JPA `@Version` is the version; the
  snapshot is never persisted. The starter additionally maps `OptimisticLockingFailureException` /
  `DataIntegrityViolationException` → 409 (`PersistenceConflictProblemAdvice`), so post-guard races
  surface as conflicts, never 500s.

## The split failure semantics (fail-closed, never confused)

| Failure | Result | Why |
|---|---|---|
| Instance resolution returns empty / throws | **DENY**, no OPA call | an attribute-less context could skip attribute-keyed deny rules — that would *widen* |
| Ancestor resolution throws / no supplier | chain = `[]`, decision proceeds **direct-only** | the 5.5 posture: never strips a direct grant, never a partial chain, never widens |
| No resolver bean / kill-switch off | the pre-5.97 reference-based context, **byte-identical** | opt-in and additive; proven by serialization equality |

## Adoption recipe

1. Register **one** `AbacResourceResolver` bean dispatching on `resourceType` → your repositories.
   That is the whole opt-in — **zero annotation changes** (an annotation that already declares
   `resourceId` becomes attribute-rich automatically).
2. Keep URL-scope rules in the handler (e.g. *category belongs to the path's catalog* → 404): the
   resolver loads by id alone and must not absorb routing semantics.
3. Read handlers: `AbacResourceCache.get(type, id, Entity.class)` with a repository fallback.
4. Mutating handlers: load fresh as always, then guard the gate snapshot before any write (inside
   the same lock you write under, where one exists — see [[CONCURRENCY-AND-LOCKING]] Rules 1–2).
5. Hierarchy stays declarative: with `opa.abac.hierarchy.enabled=true` and an `AncestorResolver`
   wired, the gate consumes the same chain the programmatic `HierarchicalAuthorizer` walks.

## Caveats (read before relying on it)

1. **The kill-switch restores the baseline.** `opa.abac.resource-resolution.enabled=false` (default
   `true`) reverts to the reference-based gate with beans untouched — the rollback for a buggy or
   slow resolver (whose failures otherwise fail closed into mass 403s). While off, attribute-keyed
   **deny** rules are not enforced at the gate — flipping the switch trades attribute enforcement
   for availability, knowingly.
2. **Unversioned resources can't be guarded.** A resource whose `getVersion()` is `null` makes the
   guard fail loud (409), and a resource that never reaches the guard keeps today's
   load-then-check window — documented degrade, never silent.
3. **The supplier-outage scope-out (retro-audit fold-in #2, tracked).** A `RoleDefinitionSupplier`
   outage is indistinguishable from an authoritative "no role", and the policies' realm fallback
   then decides — under the HTTP role source an outage can therefore *widen* a member's decision to
   their realm role. This slice deliberately does **not** change the supplier contract
   (`HttpRoleDefinitionSupplier` is byte-identical); making outages error-distinct is a tracked
   follow-up.
4. **A missing id behind an annotated `resourceId` answers `403`, not `404`** (the pinned
   anti-enumeration posture: resolver empty → deny, the handler never runs). An *existing* resource
   under the wrong URL scope is still the handler's `404` — and that 404 is only reachable by
   callers the policy already authorized for the resource, so the split is not an existence oracle.

## What stayed exactly as it was

The list paths (`AbacQueryService`, all four `findAuthorized` overloads, pagination,
`CategoryListAuthorizer`) — list-path cache population is Phase 6's, with action enrichment as the
cache's first read-side consumer. Type-level checks (lists, creates) — the create-targets-the-parent
question belongs to the Phase-6.5 action vocabulary. The `resource()`-SpEL branch's decision inputs
(it now also populates the cache). user-mgmt registers nothing — the live opt-in coexistence proof.
The Rego mechanism needs **zero policy changes**; the only 5.97 policy diff is the audit-mandated
`tags_satisfied` conjunct ported to `product.rego`/`catalog.rego` (a pure narrowing, vacuous for
tag-free roles).

## Related

- ADR [[adr/0013-attribute-rich-pre-authorization|0013]] (every fork, with rejections) · ADR
  [[adr/0006-three-layer-enforcement-model|0006]] (the redrawn 2/3 boundary — not superseded) · ADR
  [[adr/0008-hierarchical-resource-authorization|0008]] (the chain the gate consumes) · ADR
  [[adr/0011-error-contract-problem-json|0011]] (`STATE_CONFLICT`).
- [[ABAC-AUTHORIZATION]] (the spine) · [[TAG-BASED-AUTHORIZATION]] (the rules that became
  gate-decidable) · [[HIERARCHICAL-AUTHORIZATION]] (gate vs programmatic) ·
  [[CONCURRENCY-AND-LOCKING]] (decide under the protection you act under) · [[E2E-TESTING]] (the
  resource-resolution matrix).
