---
tags:
  - status/active
  - type/decision
  - area/abac
  - area/architecture
  - area/spring
---

# ADR 0016 — Action enrichment: affordance metadata on returned resources

**Status:** Accepted (planned — Phase 6, [[ACTION-ENRICHMENT]])
**Date:** 2026-06
**Context tags:** `_actions` affordance, `ResponseBodyAdvice`, `Enrichable` marker, OpenAPI `x-implements`, batch eval (`allowAll`), request-scoped cache, fail-closed degrade, affordance-vs-enforcement

> This ADR pins the **action-enrichment fork** for **Phase 6**: how a handler's returned resource (or
> page) gains an `_actions` map — *which actions the caller may perform on it* — so a UI renders exactly
> the buttons the user can use. Scope settled in a planning interview (grill-me, 2026-06-17). The forks
> closed here are the ones that would otherwise stall an autonomous run mid-ticket on an unpinned
> fail-open / contract semantic: the **cache-as-snapshot** invariant, the **omit-on-failure** degrade
> contract, and the **affordance-honesty** rule (enumerate only fully-OPA-decided verbs). Slice order in
> [[POC-ROADMAP]]: this is the last feature slice before publish — **B2 → 6.7 → 6 → B3 → 7**; enrichment
> consumes the 5.97 resolver/cache and the 6.5/6.7 vocabulary.

## Context

Three distinct authorization questions exist in this repo; enrichment answers the third:

1. **"May I do this one action?"** — enforcement, the `@OpaPreAuthorize` gate (ADR 0006 layer 1/2).
2. **"Which rows may I see?"** — data filtering, `AbacQueryService.findAuthorized` (ADR 0005).
3. **"For the resource(s) I got back, which actions on each?"** — **affordance**, this slice.

Affordance is the question most directly visible to an end user: it is what makes a UI *feel* correct
(no buttons that 4xx, no missing buttons the user could use). It is **read-side convenience, never
enforcement** — it never blocks a request; the three enforcement layers of ADR 0006 are untouched.

The prerequisites are all shipped: the Phase-5 **batch primitive** `OpaClient.allowAll(List<AbacContext>)`
→ the per-type Rego `bulk` rule (one round-trip → N verdicts); the Phase-5.97 **`AbacResourceResolver`
SPI + request-scoped `AbacResourceCache`** (resolved attributes, no re-load); the Phase-6.5/6.7
**fine-action vocabulary** (`view`/`update`/`delete`/`assign-tags`/… + the control-plane `CONTROL`
verbs) that the affordance keys enumerate.

The maintainer's prior platform solves affordance with an enrichment decorator that stamps an action map
onto response DTOs, driven by a hardcoded action enum per type and an `Enrichable`-style marker on the
generated DTOs. This ADR generalizes the decorator + marker into Spring-native, OpenAPI-codegen-native
form and pins the failure semantics the prior platform left implicit.

## Decision

### 1. Delivery: an automatic `ResponseBodyAdvice`, opt-in by the DTO's type

A library `ResponseBodyAdvice` (`@RestControllerAdvice`) recognizes returns of `Enrichable` (and
`Iterable<Enrichable>` / a paged envelope whose `items` are `Enrichable`), runs batch-eval on the
returned resource(s), and writes the `_actions` block. Handlers stay clean — enrichment is cross-cutting.
**The opt-in *is* implementing `Enrichable`**; there is no second per-endpoint annotation. A global
kill-switch property `opa.abac.action-enrichment.enabled` (default `true`) gates the advice bean
entirely — off ⇒ DTOs serialize without `_actions`, byte-identical to pre-slice behavior.

### 2. Inline `_actions` on the DTO, via the `x-implements` marker + an explicit schema property

Each enrichable schema declares **two** things in the OpenAPI spec:

```yaml
Category:
  x-implements: [ dev.dmitriikonovalov.opaabac.security.web.CategoryEnrichable ]
  properties:
    _actions: { type: object, additionalProperties: { type: boolean }, readOnly: true }
    # … the resource's own properties
```

`org.openapi.generator` (the `spring` generator already in use) reads `x-implements` and makes the
generated POJO `implements CategoryEnrichable`, and generates the `_actions` property as
`@JsonProperty("_actions") private Map<String,Boolean> actions;` with a getter/setter. **No DTO
hand-editing, no post-processing.** A marker interface alone is insufficient (implementing an interface
does not create a serializable field), so the explicit `_actions` property block is required alongside
it — this is the rejected-alternative boundary, not an accident.

`_actions` is `readOnly` (server-emitted; never accepted on input).

### 3. The marker hierarchy carries the type binding **and** the verb set (no separate registry)

```java
public interface Enrichable {                 // opa-abac-spring-security (library)
    UUID getId();                             // generated DTOs already expose this
    Map<String,Boolean> getActions();
    void setActions(Map<String,Boolean> actions);
}
public interface CategoryEnrichable extends Enrichable {   // example (one tiny interface per type)
    default String abacResourceType() { return "category"; }
    default List<String> abacActions() { return List.of("view","update","delete","assign-tags"); }
}
```

The per-type sub-interface **is** the action registry and the action-validation allowlist: the advice
reads `abacResourceType()` + `abacActions()` + `getId()` straight off the DTO, with no runtime
class→type map and no SPI bean. This is the same codegen mechanism as the type binding — one declaration
site. Adoption per type = one sub-interface (two `default` methods) + two schema lines.

### 4. Map keys are bare fine-action verbs; the set is instance-scoped only

`_actions` keys are **bare verbs** (`{"view":true,"update":false,"delete":false,"assign-tags":true}`) —
the resource type is implicit from the object the map sits on; the advice re-qualifies to `"category:view"`
when building the OPA context. The enumerated set is **instance-scoped verbs only**: actions that act on
*this specific resource*. Excluded by design, and documented:

- `list`, `create` — collection/type-level, not actions on a specific instance.
- `define-tags` — control-plane (gated `team:define-tags` per 6.7), not a per-resource-instance action.
- `assign-roles` — structurally **unreachable** for catalog resources (role assignment is `team:change-role`
  in user-mgmt; `catalog:assign-roles` is never granted — the rego tests confirm even the editor realm
  role cannot reach it). Enumerating it would emit a perpetual `false` — a meaningless key.

**Every registered verb always appears** with a `true`/`false` verdict (the headline value: honestly
reporting `delete:false`, which a role-permissions-only source could not — it can only list *granted*
verbs). Omission of a key is reserved exclusively for the **degrade** case (§7): a present `_actions` map
is always complete for its type.

### 5. The verdict context is attribute-rich, fed from the request-scoped cache (no re-load)

Each `_actions` verdict is computed against the resource's **resolved attributes** (tags; the ancestor
chain), the same context enforcement sees — a reference-level `(type,id)` context would make the map lie
(a tag-granted action would read `false`; a tag-keyed deny would read `true`). The attributes come from
the **Phase-5.97 `AbacResourceCache`**, generalized so the **list path also writes its post-filter
survivor rows** into it keyed `(type,id)` (today only the single-resource gate writes). The advice has
**one** read path for both single and list: `cache.get(type, id)`. The role is resolved **on the
governing root** (mirrors `HierarchicalAuthorizer` / the 5.97 gate); the advice obtains each distinct
row's ancestor chain via the `AncestorChainSupplier`.

The **`AbacResourceCache` interface moves down into `opa-abac-core`** (it is pure Java — `get`/`put` over
`String`/`Class`/`Object`, zero Spring) so both `spring-data` (list-path write) and `spring-security`
(gate write + the advice read) reference it without a sideways module dependency. The
`RequestAttributesResourceCache` *impl* stays in `spring-security`. The one-way module flow
(`core ← spring-data/spring-security ← starter`) is preserved.

**The cache is an attribute snapshot, never a verdict.** *Presence ≠ authorized-for-any-action.* Every
per-action verdict — gate or enrichment — is computed fresh from `bulk`. This is safe because the gate
**never reads** the cache to decide (5.97 invariant); a present entry is only ever *consumed* downstream
of a fresh gate decision for the matching action.

### 6. OPA wiring: reuse `allowAll` verbatim — zero `OpaClient` change; the `bulk` primitive extended to every enriched type

The `bulk` rule already evaluates `allow` per item via `with input as item`, with each item a full
self-contained context (its own resource + action). Enrichment is just a different *population* of the
`items` array: **same resource, varying action** (and, for a page, the `P rows × V verbs` cross-product).
The advice builds the flat `List<AbacContext>` in a known order, calls `allowAll`, and **re-folds** the
positional `List<Boolean>` into per-row `Map<verb,Boolean>` (row *i*, verb *j* → index *i·V + j*). One
`bulk` call **per resource type per response** (today's responses are homogeneous; `allowAll` already
fail-closes all-false on mixed types). The page-size is bounded by the existing pagination cap
(`perPage ≤ 100`, ADR 0012); no independent enrichment limit — a separate cap would create a confusing
partially-enriched *successful* page.

> **Correction (2026-06-17, during the T6 live e2e).** This ADR originally claimed **"zero Rego change."**
> That was a mistaken premise: Phase 5 added the `bulk` rule **only to `category.rego`** (the one type whose
> list used the allowlist-batch path that consumes `bulk`); `catalog`, `product`, and `team` had **no
> `bulk` rule**. Enriching those types makes the advice's `allowAll` read an empty OPA `result` →
> all-false → the advice omits `_actions` (a silent degrade), so only `Category` enriched live. The fix is
> **additive and decision-preserving**: the identical `bulk := [allow with input as item | some item in
> input.items]` entrypoint was added to `catalog.rego`, `product.rego`, and `team.rego` (+ the user-mgmt
> team bundle copy), mirroring `category.rego` byte-for-byte, with mirrored `opa test` cases. It adds **no
> new decision** — it maps the existing `allow` over a list. So the accurate invariant is **"zero change
> to existing decision logic; the Phase-5 `bulk` batch primitive is *extended* to every enriched type."**
> `OpaClient` is still unchanged.

### 7. The degrade contract (the fail-closed core): omit, never fabricate

On **any** enrichment failure — `bulk` error/timeout, a cache miss for a row, an ancestor-resolve
failure — the advice **omits the `_actions` map** for the affected resource(s). It **never emits a
fabricated all-false map.**

- **`_actions` present ⇒ a real, complete per-verb verdict.**
- **`_actions` absent ⇒ enrichment could not be computed** (failure or cache miss) — the client decides
  client-side (its default affordance).

An all-false map on failure is a *positive assertion* ("you definitively cannot do anything") that is a
lie when the truth is "we could not check" — and a client that inverts the convention ("show the button
unless explicitly `false`") would then render **everything**. Omission removes that footgun entirely and
matches the "degrade visibly, never guess" doctrine. Enforcement is unaffected: the real gate denies
independently of what affordance reports.

### 8. Affordance honesty: enumerate only fully-OPA-decided verbs

A verb belongs in a type's `abacActions()` **only if its verdict is wholly decided by OPA** — so a
`true` verdict means *the caller can actually perform it*. Verbs that are **co-gated in Java** are
excluded from affordance by design. Concretely, for **`team`** (user-mgmt) the 6.7 control plane splits:

- **Enumerated** (OPA-decided): `list-members`, `add-member`, `remove-member`.
- **Excluded** (Java-co-gated by `MembershipService`'s escalation gates — no-self-escalation, the subset
  rule, level-strict-`<` — and the owner-only-by-code fence): `change-role`, `define-roles`,
  `transfer-ownership`. OPA alone would say `change-role:true` for any member whose role category permits
  it, while the Java gate still rejects the *specific* escalation — affordance would over-promise and the
  UI button would 4xx. Excluding them keeps the invariant **`_actions` true ⇒ the user can actually do
  it** and keeps enrichment a pure one-`bulk`-call read (re-running the Java escalation engine in the
  read path is explicitly rejected).

This yields two deliberately *different* registry shapes across the two enriched services (catalog =
domain CRUD + tags; team = the OPA-decidable control-plane subset) — the proof that the per-type
sub-interface mechanism generalizes.

## Considered options

| Option | Why not |
|--------|---------|
| **Wrapping `Authorized<T>{ data, actions }` envelope** | Cleaner data/affordance separation, but changes *every* enriched endpoint's response schema and complicates the paged shape (`Page<Authorized<T>>`); fights the codegen-native `x-implements` mechanism and the "one marker line" adoption goal. Inline keeps the resource shape, the marker does the opt-in. |
| **Marker interface alone (no `_actions` schema property)** | Implementing an interface does not create a serializable field — there is nowhere for the advice to write the map. The explicit `readOnly` property block is mandatory, not optional. |
| **A generic `_actions` sibling on a shared base schema (no marker)** | Enriches *everything* indiscriminately, losing the explicit per-type opt-in and the per-type verb set / validation allowlist. |
| **A central `ActionRegistry` SPI bean (`type → verbs`)** | A second declaration site (Java bean *plus* the schema that declares `x-implements`) and a runtime class→type lookup, for no demonstrated need — the verb set is intrinsic to the type. The sub-interface carries it for free, symmetric with the type binding. (Kept noted as the extension point if runtime-pluggable verb sets ever arise.) |
| **All-false map on enrichment failure** | A fabricated positive assertion ("cannot do anything") masquerading as a deny; inverting-convention clients render everything. The subtle fail-open-looking-like-fail-closed trap. Omit-on-failure is honest and footgun-free (§7). |
| **Enumerate all team verbs, lean on the affordance-not-enforcement disclaimer** | Over-promises the Java-co-gated escalation verbs; the UI renders buttons that 4xx — breaking the exact "feels correct" property enrichment exists to deliver. Enumerate only OPA-decided verbs (§8). |
| **Make enrichment escalation-aware (call the Java gates too)** | Enrichment stops being one `bulk` call and becomes a full Java-policy re-evaluation in the read path — scope creep; rejected for the slice. |
| **A separate enrichment-only holder, distinct from `AbacResourceCache`** | Two snapshot stores with parallel lifecycles for no gain; the shared cache is already an attribute snapshot and the gate never reads it. The cache-as-snapshot invariant (§5) makes sharing safe. |
| **Re-resolve each row in the advice (no list-path write-through)** | A double-load (the filter already fetched the rows) and risks attribute drift between the filtered query and the advice (a concurrent tag edit) — the map could disagree with the rows shown. Write-through caches the *same* instance the query returned: no double-load, no drift. |
| **A separate enrichment row-cap** | The pagination cap (`perPage ≤ 100`, ADR 0012) already bounds the batch; a second cap creates a partially-enriched *successful* page — confusing, and omission would then mean two different things. |
| **A new `OpaClient` method shaped for one-resource-N-actions** | Either re-batches (complex) or forces per-row round-trips (kills the one-call win). `allowAll` already returns exactly the positional list enrichment needs; the refold is advice-side bookkeeping. First *consumer* of the primitive, not a reshape of it. |

## Consequences

- **Good:** the UI gets a complete, honest affordance map mirroring enforcement (tags + hierarchy
  included), as the *first real consumer* of the Phase-5 batch primitive and the 5.97 cache; adoption per
  type is one sub-interface + two schema lines; the per-type registry doubles as a validation allowlist;
  two enriched services prove the mechanism generalizes across two registry shapes. Zero `OpaClient`
  change; zero change to existing decision logic (the `bulk` primitive was *extended* to the newly-enriched
  types — additive, allow-mapped-over-a-list; see §6 Correction).
- **Cost:** the `AbacResourceCache` interface relocates to `core` (mechanical; the impl stays); the list
  path gains a write-through into that cache (`spring-data` → core interface); enrichable schemas gain an
  `_actions` property and the example DTOs gain a marker per type; one `bulk` call per enriched response
  (bounded by the pagination cap).
- **Additivity:** kill-switch off / no `Enrichable` DTO → byte-identical behavior; `_actions` is
  `readOnly` (never affects input); enforcement and data filtering untouched; the cache stays an
  attribute snapshot (no verdict semantics) so the 5.97 gate invariant holds.
- **Affordance ≠ enforcement** is the load-bearing boundary: enrichment never gates a request; a present
  map is advisory; the real gate decides independently. The §7 omit-on-failure and §8 honesty rules keep
  the advisory map from ever *masquerading* as an authoritative statement.
- **Follow-on:** Phase 7 publishes the marker + advice as part of the starter's public surface; the
  guides gain an action-enrichment mechanism guide and a note in the ABAC layer description (affordance as
  a read-side layer, distinct from the three enforcement layers of ADR 0006).

## Related

- ADR [[0005-partial-eval-to-jpa-specification|0005]] (the `allowAll`/`bulk` batch primitive enrichment
  consumes) · ADR [[0006-three-layer-enforcement-model|0006]] (the enforcement layers enrichment is
  explicitly *not* — affordance is a fourth, read-side concern) · ADR
  [[0013-attribute-rich-pre-authorization|0013]] (the resolver/cache this reuses; the cache-as-snapshot
  invariant) · ADR [[0012-pagination-envelope|0012]] (the `perPage` cap that bounds the batch) · ADR
  [[0015-control-plane-vocabulary-categorization|0015]] (the team control-plane verbs and the Java
  escalation gates that decide the affordance-honesty exclusion) · ADR
  [[0007-coarse-grained-permission-categories|0007]] (the fine-action vocabulary the keys enumerate)
- [[ACTION-ENRICHMENT]] (the Phase-6 slice index) · [[RESOURCE-RESOLUTION]] (Phase 5.97, the cache feed)
  · [[DATA-FILTERING]] (Phase 5, the batch primitive) · [[POC-ROADMAP]] (slice order) ·
  [[USER-STORIES]] (the "show me only the buttons I can use" epic)
