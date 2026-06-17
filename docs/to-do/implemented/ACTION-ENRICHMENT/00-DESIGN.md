---
tags:
  - status/planned
  - type/architecture
  - area/abac
  - area/spring
  - area/architecture
---

# 00 — Design: Action enrichment / affordance metadata (Phase 6)

> The design, written from a settled **ADR [[0016-action-enrichment-affordance-metadata|0016]]** (which
> pins every fork below; grill-me 2026-06-17). A **read-side** slice: a library `ResponseBodyAdvice`
> attaches an `_actions` map — *which actions the caller may perform* — to returned `Enrichable`
> resources, computed by one batch OPA call against the resource's **resolved attributes** (the 5.97
> cache). **Zero `OpaClient` change; zero Rego change; enforcement (ADR 0006) and data filtering (ADR
> 0005) untouched; opt-in by the DTO implementing `Enrichable`; kill-switched.**

## 1. The problem, precisely

Enforcement answers "may I do this *one* action?"; filtering answers "*which rows*?". Neither tells a UI,
for the resource(s) it received back, **which actions are available on each** — so a frontend either
renders every button and lets some 4xx, or hardcodes its own guess at the rules. Enrichment answers that
third question as **affordance**, never enforcement: it never blocks a request; the three layers of ADR
0006 still decide.

Why it earns a slice now (all prerequisites shipped, verified in code 2026-06-17):

| Prerequisite | Shipped artifact | What enrichment needs from it |
|---|---|---|
| Batch eval (Phase 5) | `OpaClient.allowAll(List<AbacContext>)` → per-type Rego `bulk` (`with input as item`) | one round-trip → N verdicts; **reused verbatim** |
| Resolver + cache (5.97) | `AbacResourceResolver` SPI + `AbacResourceCache` (request-scoped) | resolved attributes per resource, **no re-load** |
| Fine-action vocabulary (6.5/6.7) | `permission_categories.json` (`view`/`update`/`delete`/`assign-tags`/…; `CONTROL`) | the verbs the `_actions` keys enumerate |

## 2. The mechanism (ADR 0016 §1–§8)

### Core (`opa-abac-core` — Spring-free)

- **`AbacResourceCache` relocates here** from `spring-security` (the interface is pure Java —
  `<T> Optional<T> get(String type, String id, Class<T> as)` / `void put(String, String, Object)`). Both
  `spring-data` (list-path write) and `spring-security` (gate write + advice read) now reference it
  without a sideways module dep. The one-way flow (`core ← spring-data/spring-security ← starter`) holds.

### Library marker + advice (`opa-abac-spring-security`)

```java
public interface Enrichable {                         // base marker (library)
    UUID getId();
    Map<String,Boolean> getActions();
    void setActions(Map<String,Boolean> actions);
}
// RequestAttributesResourceCache (the AbacResourceCache impl) STAYS here.
// ActionEnrichmentAdvice (ResponseBodyAdvice<Object>) — the new advice.
```

The advice flow, for a return of `Enrichable` / `Iterable<Enrichable>` / a paged envelope whose `items`
are `Enrichable`, when the advice bean is wired (`opa.abac.action-enrichment.enabled=true`):

1. Collect the enrichable DTOs (single, list elements, or `page.items`).
2. For each **distinct** DTO: `cache.get(abacResourceType(), getId(), …)` → the resolved instance.
   **Cache miss → omit `_actions` for that DTO** (degrade, §7). Resolve its ancestor chain via the
   `AncestorChainSupplier` (deduped per distinct row).
3. Build the flat `List<AbacContext>` = `rows × abacActions()` (same resource, varying re-qualified
   action `"type:verb"`, role on the **governing root**), in a known order.
4. One `opaClient.allowAll(contexts)` **per resource type** → positional `List<Boolean>`.
   **Any bulk/ancestor failure → omit `_actions`** for the affected rows (§7).
5. Re-fold the flat booleans into per-row `Map<verb,Boolean>` (row *i*, verb *j* → index *i·V+j*),
   `setActions(map)` on each DTO. Keys are **bare verbs**.

### List-path cache feed (`opa-abac-spring-data`)

`AbacQueryService.findAuthorized` writes its **post-filter survivor rows** into `AbacResourceCache` keyed
`(type, id)` on **all** query paths (pure-SQL, allowlist, kill-switch — the page content is always
materialized before mapping). The advice then has one read path for both single and list. Caches the
*same* instance the query returned → no double-load, no attribute drift.

### The example DTO opt-in (per type)

```yaml
# in the OpenAPI spec, on each enrichable resource schema:
Category:
  x-implements: [ dev.dmitriikonovalov.opaabac.…web.CategoryEnrichable ]
  properties:
    _actions: { type: object, additionalProperties: { type: boolean }, readOnly: true }
```
```java
public interface CategoryEnrichable extends Enrichable {
    default String abacResourceType() { return "category"; }
    default List<String> abacActions() { return List.of("view","update","delete","assign-tags"); }
}
```

`org.openapi.generator` makes the POJO `implements CategoryEnrichable` and generates
`@JsonProperty("_actions") private Map<String,Boolean> actions;` + accessors. The sub-interface **is** the
registry and the validation allowlist — no separate SPI bean, no runtime class→type map.

### The starter (`opa-abac-spring-boot-starter`)

Auto-config registers `ActionEnrichmentAdvice` under
`@ConditionalOnProperty(opa.abac.action-enrichment.enabled, matchIfMissing=true)`, wiring the `OpaClient`,
the `AbacResourceCache`, and the `AncestorChainSupplier` (already bound to the 5.5 `AncestorResolver` by
the 5.97 auto-config). Off → no advice bean → DTOs serialize without `_actions`, byte-identical.

## 3. The verb sets (verified, not assumed)

Instance-scoped verbs only. **The catalog sets are verified against real endpoints during T-setup** (the
discipline that dropped `assign-roles`); the expected sets:

| Type | `abacActions()` (expected) | Verify in 00→T |
|---|---|---|
| `catalog` | `[view, update, delete, assign-tags]` | confirm a `catalog:assign-tags` endpoint exists; else drop it |
| `category` | `[view, update, delete, assign-tags]` | confirmed: `assign-tags` dispatched on `PUT /categories/{id}` tags-delta |
| `product` | `[view, update, delete, assign-tags]` | confirm `product:assign-tags` endpoint; else drop it |
| `team` | `[list-members, add-member, remove-member]` | OPA-decided subset only (§ below) |

**Excluded (documented):** `list`/`create` (collection-level) · `define-tags` (control-plane) ·
`assign-roles` (structurally unreachable for catalog — role assignment is `team:change-role`) · the team
**Java-co-gated** verbs `change-role`/`define-roles`/`transfer-ownership` (affordance honesty, §8 ADR).

## 4. Behavior matrix (the cells that define correctness)

`E` = the `_actions` map. Subject reads/lists a resource it can see; enrichment evaluates each verb.

| Scenario | `_actions` result | Cell |
|---|---|---|
| Member, team role grants WRITE, **tags match** | `{view:true, update:true, delete:true, assign-tags:true}` | full affordance |
| Member, team role READ-only | `{view:true, update:false, delete:false, assign-tags:false}` | **honest `false` — the headline** |
| Member, role grants write but **tags mismatch** the resource | `{…, update:false, delete:false}` | affordance mirrors the tag deny |
| Root-granted member, deep `product` (inherited grant) | verbs reflect the **root** role + product tags | hierarchy honored (governing-root role) |
| Page of N categories, mixed per-row grants | each element its own `_actions`; **one** bulk call | per-row, batched |
| `bulk` call errors / OPA down | **`_actions` omitted** on affected rows (request still 200) | **degrade — never all-false** |
| Row not in cache (unexpected) | **`_actions` omitted** for that row | degrade, visible |
| `team` instance, any member | `{list-members:?, add-member:?, remove-member:?}` — **never** `change-role` | OPA-decided subset only |
| Kill-switch off / DTO not `Enrichable` | no `_actions` field emitted | byte-identical to pre-slice |

**Invariants pinned (the fail-closed core):**
- **`_actions` present ⇒ a complete, real per-verb verdict.** Absent ⇒ could-not-compute. Never a
  fabricated map.
- **`_actions` true ⇒ the caller can actually perform it** (only fully-OPA-decided verbs enumerated).
- **Cache = attribute snapshot, never a verdict.** Presence ≠ authorized; every verdict is fresh from
  `bulk`.

## 5. What this slice does NOT change

- **Zero `OpaClient` change** (reuse `allowAll`) and **no change to existing decision logic**. *(Correction,
  2026-06-17: this design assumed the `bulk` rule already existed for every enriched type — it did not, only
  `category.rego` had it. The slice **added** the identical decision-preserving `bulk` entrypoint to
  `catalog`/`product`/`team` rego, so OPA must reload on first pull. "Zero Rego change" was the wrong framing;
  see ADR 0016 §6.)* Enrichment is the first *consumer* of the batch primitive, not a reshape.
- **Enforcement** (the `@OpaPreAuthorize` gate, ADR 0006 layers) and **data filtering**
  (`findAuthorized`'s decisions, ADR 0005) — untouched. The list path gains only a *write-through* to the
  cache; its authorization output is identical.
- **The 5.97 gate invariant** — the gate still never reads the cache to decide; the cache stays a pure
  attribute snapshot (no verdict semantics).
- **Input contracts** — `_actions` is `readOnly`, server-emitted, never accepted on a request body.
- **The pagination envelope** (ADR 0012) — `_actions` rides on each `items` element; the envelope shape is
  unchanged. The `perPage ≤ 100` cap bounds the batch; no separate enrichment cap.
- **The error contract** (ADR 0011) — enrichment never produces an error; a failure degrades to omission,
  the handler's own status stands.

## 6. Proof obligations (QA skeleton — cases get ids in 10-QA)

- **Unit (advice, OPA stub):** single `Enrichable` → correct map from a stubbed `allowAll`; the refold
  index math (P×V → per-row maps) for a multi-row page; **bulk-error → omit** (no all-false map);
  **cache-miss → omit** that row; ancestor-throw → omit/direct-only per the chosen posture; kill-switch
  off → no advice, no `_actions`; non-`Enrichable` return → passthrough; mixed-type list → one bulk per
  type (or the homogeneous-only assertion).
- **IT (real Postgres):** list path writes survivors into the cache → advice reads them with **no second
  SELECT** (assert query count); `getCategory` single-resource enrichment reads the gate's cached
  snapshot; tag-restricted verb reads `false` while a tag-matched verb reads `true` on the **same** row
  (the headline honest-`false` cell); a deep `product` reflects the governing-root role (inherited grant).
- **e2e (new matrix, through APISIX):** a viewer vs editor `GET /categories/{id}` and `GET /categories`
  page — assert the `_actions` keys + true/false per role; a `team` instance shows only the three
  OPA-decided verbs (never `change-role`); the whole existing suite green (coexistence, no envelope
  break). Fixture ids registered per the e2e conventions.

## 7. Forks already closed (do not reopen during decomposition)

ADR 0016's considered-options table: wrapping `Authorized<T>` envelope · marker-alone (no schema
property) · generic sibling field · central `ActionRegistry` SPI bean · all-false-on-failure ·
enumerate-all-team-verbs · escalation-aware enrichment · a separate enrichment-only holder ·
re-resolve-in-advice (no write-through) · a separate enrichment row-cap · a purpose-shaped `OpaClient`
method. Plus, from the interview: **both** catalog (all three types) **and** user-mgmt adopt; the team set
is the OPA-decided subset; verb sets verified against real endpoints, not assumed; keys are bare verbs;
omit (never fabricate) on every failure class.

## Related

- ADR [[0016-action-enrichment-affordance-metadata|0016]] — every fork above, with rejections.
- [[ACTION-ENRICHMENT]] — the slice index (direction note).
- [[RESOURCE-RESOLUTION]] (the 5.97 cache this feeds from) · [[DATA-FILTERING]] (the Phase-5 batch
  primitive) · [[POC-ROADMAP]] (Phase 6, order B2 → 6.7 → 6 → B3 → 7).
- Guides to touch: a new action-enrichment mechanism guide + [[ABAC-AUTHORIZATION]] (affordance as a
  read-side layer, distinct from the three enforcement layers), [[PARTIAL-EVALUATION-FILTERING]] (the
  list-path cache write-through), [[REST-API-DESIGN]] (the `_actions` envelope on resources/pages).
