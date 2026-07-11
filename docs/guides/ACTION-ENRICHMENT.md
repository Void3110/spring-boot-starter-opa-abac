---
tags:
  - status/active
  - type/guide
  - area/abac
  - area/spring-security
  - area/api
---

# Action enrichment — affordance metadata on responses

> Phase 6 (ADR [[0016-action-enrichment-affordance-metadata|0016]]). After a handler returns a resource
> (or a page of resources), a library `ResponseBodyAdvice` attaches an `_actions` map — *which actions the
> caller may perform on each* — so a UI renders exactly the buttons the user can use. This is the shipped
> contract; the design record is the ADR + [[ACTION-ENRICHMENT]].

## Why — the third question

Three distinct authorization questions live in this library; enrichment answers the third:

1. **"May I do this one action?"** — enforcement, the `@OpaPreAuthorize` gate ([[ABAC-AUTHORIZATION]]).
2. **"Which rows may I see?"** — data filtering, `AbacQueryService.findAuthorized`
   ([[PARTIAL-EVALUATION-FILTERING]]).
3. **"For the resource(s) I got back, which actions on each?"** — **affordance**, this slice.

Affordance is the question most directly visible to a user: it's what makes a UI *feel* correct — no
button that 4xx, no missing button the user could use. It is **read-side convenience, never enforcement**:
it never blocks a request; the three enforcement layers of ADR 0006 still decide independently. A present
`_actions` map is **advisory** — the real gate denies on its own terms.

```jsonc
{
  "id": "…", "name": "Widgets", "tags": { "region": "emea" },
  "_actions": { "view": true, "update": true, "delete": false, "assign-tags": true }
}
```

Bare-verb keys (the resource type is implicit from the object the map sits on); the advice re-qualifies to
`"category:view"` when it builds the OPA context.

## The mechanism

A library **`ActionEnrichmentAdvice`** (`opa-abac-spring-security`, package `…security.web`) — a
`@RestControllerAdvice` implementing `ResponseBodyAdvice<Object>`. For a returned `Enrichable`, an
`Iterable<Enrichable>`, or a paged envelope whose `getItems()` are `Enrichable` (the [[PAGINATION-ENVELOPE]]
`<Resource>Page` shape, detected structurally — never a compile-time dependency on the example DTOs), it:

1. Collects the enrichable DTOs and groups them **by resource type**.
2. **Pass 1** (per row): reads its **resolved snapshot** from the request-scoped `AbacResourceCache`
   (`cache.get(type, id)` — the Phase-5.97 cache; the advice never re-loads), resolves the ancestor chain
   (through the Slice-7.3 request memo — one real resolution per `(type,id)` per request even though the
   list's query path asked first), and derives the **governing root**
   (`ancestors.isEmpty() ? leaf : ancestors[0]` — the same rule the gate / `HierarchicalAuthorizer` use).
   The computable rows' **distinct** roots are collected.
3. Resolves the roots' roles in **one `RoleDefinitionSupplier.lookupAll` batch** (Slice 7.3,
   [[0024-batch-role-resolution|ADR 0024]]) — one wire exchange per page instead of one per row, even on
   a page where every row is its own root (the multi-root catalogs list). Unconditional code
   (call-coalescing, not caching — a single point-in-time exchange, so there is nothing for the
   `opa.abac.resolve-memo.enabled` flag to govern here).
4. **Pass 2**: builds the flat `rows × verbs` context list in row-major order (row *i*, verb *j* →
   index *i·V+j*) from the returned map (an `empty` entry → `role=null`).
5. Issues **one `OpaClient.allowAll` batch call per resource type** — reusing the Phase-5 batch primitive
   verbatim (no new `OpaClient` method).
6. Re-folds the positional `List<Boolean>` into a per-row `Map<verb,Boolean>` and `setActions(map)`.

The first real consumer of the Phase-5 `allowAll` primitive: one OPA round-trip returns the verdict for a
resource's whole action set, instead of one call per action. Degrade note (Slice 7.3): a **role-source
outage now omits the whole group's `_actions`** (`lookupAll` is a whole-batch outage by contract — there
are no per-target answers), where pre-7.3 it omitted the row that hit it; per-row rungs (no verbs, cache
miss, ancestor failure) are unchanged, and the response body is never blocked.

## Opt-in: the `Enrichable` marker + the `x-implements` codegen recipe

Adoption per type is **one tiny sub-interface + two schema lines** — no per-endpoint annotation, no DTO
hand-editing. Each enriched schema declares two things in its OpenAPI spec:

```yaml
Category:
  x-implements: [ dev.dmitriikonovalov.example.catalog.security.CategoryEnrichable ]
  properties:
    _actions: { type: object, additionalProperties: { type: boolean }, readOnly: true }
    # … the resource's own properties
```

`org.openapi.generator` (the `spring` generator) reads `x-implements` and makes the generated POJO
`implements CategoryEnrichable`, and generates the `_actions` property as
`@JsonProperty("_actions") private Map<String,Boolean> actions;` with accessors. A property named
`_actions` becomes `getActions()`/`setActions()` (JavaBeans strips the leading underscore) — matching the
`Enrichable` contract exactly. **No generator naming config is needed.**

The per-type sub-interface (app-owned) **is the action registry and the validation allowlist** — no
separate SPI bean:

```java
public interface CategoryEnrichable extends Enrichable {
    default String abacResourceType() { return "category"; }
    default List<String> abacActions() { return List.of("view", "update", "delete", "assign-tags"); }
}
```

The base `Enrichable` (library) carries `getId()` / `getActions()` / `setActions()` **and** the abstract
`abacResourceType()` / `abacActions()` the advice reads off an `Enrichable` reference; the sub-interface
supplies the latter two as `default`s.

## The verb sets (verified, not assumed)

Instance-scoped verbs only, **verified against the live `@OpaPreAuthorize` endpoints** (the discipline
caught two corrections):

| Type | `abacActions()` | Note |
|---|---|---|
| `catalog` | `[view, update, delete, assign-tags]` | `assign-tags` dispatched on the tags-delta `PUT` (ADR 0022, taggable roots) |
| `category` | `[view, update, delete, assign-tags]` | `assign-tags` dispatched on the tags-delta `PUT` |
| `product` | `[view, update, delete, assign-tags]` | `assign-tags` dispatched on the tags-delta `PUT` (taggable products) |
| `team` | `[list-members, add-member, remove-member]` | the **OPA-decided subset** only (see honesty, below) |

Excluded by design: `list`/`create` (collection-level) · `define-tags` (control-plane) · `assign-roles`
(structurally unreachable for catalog).

## The three load-bearing invariants

1. **Omit, never fabricate.** On *any* failure — a `bulk` error/timeout, a cache miss for a row, an
   ancestor- or role-resolution failure, **or an all-`false` verdict block** — the advice **leaves
   `_actions` unset** for the affected resource(s). It never emits a fabricated all-`false` map (a positive
   "you can't do anything" that lies when the truth is "couldn't check," and that a convention-inverting
   client reads as "show everything"). **`_actions` present ⇒ a complete, real per-verb verdict with ≥1
   `true`; absent ⇒ enrichment could not be computed** — the client falls back to its own default. The
   all-`false`→omit rule exists because the production `OpaClient.allowAll` fails closed to a full-length
   all-`false` list on a transport error, indistinguishable from a genuine all-deny by the booleans alone.
   On the wire this is enforced by `@JsonInclude(NON_EMPTY)` on `Enrichable.getActions()` — an unset map
   (the generated DTO defaults it to `{}`) is **omitted from the JSON**, never serialized as `{}`.

2. **Affordance honesty — only fully-OPA-decided verbs are enumerated.** A verb belongs in a type's
   `abacActions()` only if **OPA alone decides it**, so `true` means *the caller can actually do it*.
   Verbs co-gated in Java are excluded: for `team`, that drops `change-role`/`define-roles`/
   `transfer-ownership` (the 6.7 `MembershipService` escalation gates + the owner-only-by-code fence decide
   the *specific* attempt, which OPA cannot see). OPA alone would say `change-role:true` for any member
   whose role category permits it while the Java gate still rejects the escalation — so enumerating them
   would over-promise and the UI button would 4xx, breaking the exact property enrichment delivers.

3. **The cache is an attribute snapshot, never a verdict.** *Presence ≠ authorized.* The advice reads the
   cache only for **resolved attributes**; every per-action verdict is computed fresh from `bulk`. The
   gate still never reads the cache to decide (the 5.97 invariant). The list path
   ([[PARTIAL-EVALUATION-FILTERING]]) write-throughs its **post-filter survivor rows** into the same cache,
   so the advice has one read path for both single-GET and list — no double-load, no attribute drift
   (it caches the same instance the query returned). Denied/dropped rows are never written.

## The batch primitive (`bulk`) — extended to every enriched type

Enrichment reuses the Phase-5 per-type `bulk` Rego rule (`bulk := [allow with input as item | some item
in input.items]`) — `allow` mapped over a list, **no new decision logic**. Phase 5 originally added `bulk`
only to `category.rego` (the one type whose list used the allowlist-batch path); this slice **extended the
identical entrypoint** to `catalog.rego`, `product.rego`, and `team.rego` (mirrored byte-for-byte, with
mirrored `opa test` cases) so every enriched type has it. `OpaClient` is unchanged. (ADR 0016 §6.)

## Wiring + the kill-switch

The starter auto-config registers `ActionEnrichmentAdvice` for a servlet web app, gated on the
`AbacResourceResolver` bean **and** both `opa.abac.resource-resolution.enabled` + the kill-switch
`opa.abac.action-enrichment.enabled` (both default **true**) — the exact conditions under which the
request-scoped cache it reads exists. Off (or no resolver/cache) ⇒ **no advice bean, and the
`AbacQueryService` receives no cache collaborator** (the list-path write-through is dormant too) — an
`Enrichable` DTO then serializes without `_actions`, byte-identical to pre-Phase-6 behavior. A
user-supplied advice bean overrides the default.

## Adoption recipe

1. Add the two schema lines (`x-implements` + the `readOnly` `_actions` property) to each enriched
   resource schema; regenerate.
2. Write one `<Type>Enrichable extends Enrichable` per type with `abacResourceType()` + the verified
   `abacActions()`.
3. Register an `AbacResourceResolver` for the type (the [[RESOURCE-RESOLUTION]] feed) if not already
   present — this is what activates enrichment.
4. Ensure the type's Rego package has a `bulk` rule (mirror `category.rego` if absent).

That's it — no handler change; the advice attaches `_actions` after the handler returns.

## Caveats

- **Affordance, not enforcement** — a present map is advisory; the real gate decides independently.
- **An ungated read degrades visibly.** A resource read by an endpoint with **no** `@OpaPreAuthorize`
  (e.g. user-mgmt `getTeam`, the owner-on-create bootstrap) is never written to the cache by a gate, so
  its `_actions` **cache-misses and is omitted** — the documented degrade. If a later phase gates the read,
  enrichment lights up automatically.
- **`Membership` is not enriched** (the per-membership affordance is a different registry, out of scope).
- The page-size is bounded by the existing `perPage ≤ 100` cap (ADR 0012); no separate enrichment limit.

## Proven by

- **Unit** (`ActionEnrichmentAdviceTest`, `opa-abac-spring-security`): the P×V refold, the honest-`false`
  map, omit on every failure class (bulk throws / short list / all-`false` / cache miss / ancestor / role
  outage), cache-is-snapshot. **Starter** (`OpaAbacAutoConfigurationTest`): the kill-switch on/off, the
  write-through collaborator wired iff enabled, the metadata property.
- **Integration** (real Postgres, `example-catalog`): `ActionEnrichmentIT` (the honest-`false` headline,
  the governing-root deep product, the verb-set exclusions, the `_actions` wire round-trip);
  `ActionEnrichmentListIT` (the no-second-SELECT write-through proof + per-row maps on a page).
  `example-user-management`: `ActionEnrichmentIT` (the team OPA-decided subset + the ungated-`getTeam`
  wire degrade).
- **e2e** (`scripts/postman/run-action-enrichment-matrix.sh`, live through APISIX): viewer-vs-writer
  verb-by-verb maps, per-row page maps, affordance ≠ enforcement (a `_actions:false` matches a real 403),
  the verb-set exclusion, omit-on-failure (OPA paused). The whole existing matrix suite stays green
  (additive coexistence).
- **`opa test`** green and the existing decision tests unchanged (the `bulk` additions add no decision).

## Related

- ADR [[0016-action-enrichment-affordance-metadata|0016]] — every pinned fork.
- [[ABAC-AUTHORIZATION]] — the enforcement spine affordance mirrors but is **not**.
- [[PARTIAL-EVALUATION-FILTERING]] — the list-path cache write-through + the `allowAll`/`bulk` batch primitive.
- [[RESOURCE-RESOLUTION]] — the request-scoped cache + governing-root role rule this reuses.
- [[PERMISSION-MODEL]] — the fine-action vocabulary the `_actions` keys enumerate.
- [[REST-API-DESIGN]] — the `_actions` envelope on resources/pages.
- [[E2E-TESTING]] — the action-enrichment matrix.
- [[POC-ROADMAP]] (Phase 6) · [[USER-STORIES]] (the "buttons" epic).
