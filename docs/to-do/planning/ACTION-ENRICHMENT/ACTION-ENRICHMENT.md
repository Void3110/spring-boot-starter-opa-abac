---
tags:
  - status/planned
  - type/index
  - area/abac
  - area/opa
  - area/spring
---

# Action enrichment — affordance metadata on returned resources

> **Status: Design settled (grill-me 2026-06-17) — ready for /decompose.** Phase 6 of [[POC-ROADMAP]].
> Every fork below is pinned in **ADR [[0016-action-enrichment-affordance-metadata|0016]]** and the
> **[[00-DESIGN]]** (behavior matrix + proof obligations). All prerequisites have shipped: the Phase-5
> batch primitive (`OpaClient.allowAll`, [[DATA-FILTERING]]), the **[[RESOURCE-RESOLUTION]] (Phase 5.97)**
> resolver SPI + request-scoped cache (the resolved attributes enrichment evaluates against), and the
> **6.5/6.7** fine-action vocabulary (ADR [[0007-coarse-grained-permission-categories|0007]] +
> [[0015-control-plane-vocabulary-categorization|0015]]). The open questions at the foot of this note are
> resolved — see the *Settled (grill-me 2026-06-17)* block below. **Next: `/decompose`.**
>
> ### Settled (grill-me 2026-06-17) — supersedes the "Open questions" section below
> - **Scope:** single-resource **and** list/page in this slice. Adopters: **catalog (all three types:
>   Catalog/Category/Product) + user-management-service** (two registry shapes, proving generality).
> - **Envelope:** inline `_actions` on the DTO (Option B — `x-implements` marker **+** an explicit
>   `readOnly` `_actions` schema property). `Authorized<T>` wrapper rejected.
> - **Registry:** the **per-type sub-interface** (`CategoryEnrichable extends Enrichable`) carries
>   `default abacResourceType()` + `default abacActions()` — it *is* the registry + validation allowlist.
>   No separate SPI bean.
> - **Keys / set:** bare-verb keys; **instance-scoped verbs only.** `category` →
>   `[view, update, delete, assign-tags]` (catalog/product verified vs real endpoints in 00→T).
>   `assign-roles`/`list`/`create`/`define-tags` excluded. **`team` → `[list-members, add-member,
>   remove-member]`** (the OPA-fully-decided subset; the Java-co-gated escalation verbs excluded —
>   *affordance honesty*).
> - **Feed:** generalize the 5.97 `AbacResourceCache` — the **list path write-through**s its post-filter
>   survivors; the advice has one `cache.get(type,id)` read path. The **`AbacResourceCache` interface
>   relocates to `opa-abac-core`**. Cache = attribute snapshot, **never a verdict**.
> - **Failure (fail-closed core):** **omit `_actions` on any failure** (bulk error / cache miss /
>   ancestor failure) — never a fabricated all-false map. Present ⇒ complete real verdict; absent ⇒
>   couldn't-compute.
> - **Perf / opt-out:** automatic on any `Enrichable` return (opt-in = the marker); rely on the
>   `perPage ≤ 100` cap, no separate enrichment limit; `opa.abac.action-enrichment.enabled` kill-switch.
> - **OPA wiring:** reuse `allowAll` verbatim (advice owns the P×V flatten/refold), one `bulk` per type
>   per response. **Zero `OpaClient` change, zero Rego change.**

## Package (this folder)

| File | Role |
|------|------|
| `ACTION-ENRICHMENT.md` | this index — direction, settled decisions, the ticket status table |
| `00-DESIGN.md` | the mechanism, verified verb sets, behavior matrix, proof obligations, closed forks |
| `01-DECOMPOSITION.md` | the **work list** — T1…T7, each Goal / Deliverables / Acceptance / What-NOT-to-touch + the critical path |
| `10-QA-TEST-CASES.md` | the U/I/E/D cases each ticket's *Acceptance* references + the fail-closed checklist |
| `AUTONOMOUS-IMPLEMENTATION-PROMPT.md` | the self-contained run prompt (verbatim §4 skeleton, slots filled) |
| `STATUS-01..07.md` | one stub per ticket, filled at each checkpoint during the run |
| ADR [[0016-action-enrichment-affordance-metadata\|0016]] | the immutable fork record (in `docs/architecture/adr/`) |

## Ticket status

| Ticket | Summary | Module | Status |
|--------|---------|--------|--------|
| **T1** | Relocate `AbacResourceCache` → core + the `Enrichable` marker (build-breaker sweep) | core / spring-security | ✅ |
| **T2** | `ActionEnrichmentAdvice` (`ResponseBodyAdvice`) + P×V refold + omit-on-failure | spring-security | ✅ |
| **T3** | List-path write-through into the cache (all `findAuthorized` paths) | spring-data | ☐ |
| **T4** | Starter auto-config wiring + `opa.abac.action-enrichment.enabled` kill-switch | starter | ☐ |
| **T5** | Catalog adoption: 3 `<Type>Enrichable` + 3 schema blocks + codegen + ITs | example-catalog | ☐ |
| **T6** | user-mgmt adoption: `TeamEnrichable` (OPA-decided subset) + cross-service e2e | example-usermgmt | ☐ |
| **T7** | Docs (guide + reconciliations) + roadmap/stories/index + Mulch + folder move | docs | ☐ |

> **Critical path:** `T1 → (T2 ∥ T3) → T4 → T5 → T6 → T7`. **T1–T4** are the independently-landable
> library subset (opt-in, dormant until an app ships an `Enrichable` DTO). Conventions: clean-room
> (original names only); commit identity `Void3110 <void31102025@gmail.com>`; one focused commit per
> ticket; **no push** (the maintainer ships).

## What it is

After a handler returns a resource (or a page of resources), attach a map of **which actions the caller
may perform on it** — e.g.

```jsonc
{
  "id": "…", "name": "Widgets", "tags": { "region": "emea" },
  "_actions": { "view": true, "update": true, "delete": false, "assign-tags": true }
}
```

(bare-verb keys — the resource type is implicit; see ADR 0016 §4.) This is **affordance metadata**, *not*
enforcement: it never blocks a request, it answers *"what could I do here?"* Enforcement still happens at
the three layers of ADR 0006; enrichment is a **read-side convenience** layered on top.

It is the **first real consumer of Phase-5 batch evaluation** (`OpaClient.allowAll`): one OPA round-trip
returns the verdict for the resource's whole action set, instead of one call per action.

After a handler returns a resource (or a page of resources), attach a map of **which actions the caller
may perform on it** — e.g.

```jsonc
{
  "id": "…", "name": "Widgets", "tags": { "region": "emea" },
  "_actions": { "category:read": true, "category:write": true, "category:delete": false }
}
```

so a frontend renders exactly the buttons the user can use. This is **affordance metadata**, *not*
enforcement: it never blocks a request, it answers *"what could I do here?"* Enforcement still happens at
the three layers of ADR 0006; enrichment is a **read-side convenience** layered on top.

It is the **first real consumer of Phase-5 batch evaluation** (`OpaClient.allowAll`): one OPA round-trip
returns the verdict for the resource's whole action set, instead of one call per action.

## Why a phase of its own

`@OpaPreAuthorize` answers "may I do this *one* action?" (enforcement). Data filtering answers "*which
rows*?" Enrichment answers "for the rows I got back, *which actions* on each?" — a third, distinct
question, and the one most directly visible to an end user (it's what makes a UI feel correct). It earns
its own slice; it depends on Phase 5's batch primitive, so it sequences right after (Phase 6, before
publish).

## Agreed direction (decided with the maintainer)

1. **Delivery = a response decorator / advice (automatic), not per-endpoint boilerplate.** A library
   `ResponseBodyAdvice` / `@RestControllerAdvice` recognizes enrichable return types, runs batch-eval on
   the returned resource(s), and injects the `_actions` block. Handlers stay clean; enrichment is
   cross-cutting (mirrors the reference platform's enrichment decorator, generalized).
2. **Action source = a per-resource-type action registry.** A small dictionary
   (`resourceType → [its actions]`, e.g. `category → [category:read, category:write, category:delete]`)
   generalizes the reference platform's hardcoded action-enum approach. Enrichment evaluates
   that fixed set, so it can honestly report `delete: false` for an action the role never grants (a
   role-permissions-only source could only enumerate *granted* verbs). **Bonus:** the same registry is an
   **action-validation allowlist** — defense-in-depth, rejecting unknown action strings before they reach
   OPA.
3. **Generated-DTO opt-in via an `x-implements` marker interface** (see next section).
4. **Fully evaluated action list — contexts are attribute-rich, never reference-level** (settled
   2026-06-12). Each `_actions` verdict is computed against the resource's **resolved attributes**
   (tags; hierarchy per the 5.5 model) — the same context enforcement sees. A reference-level
   `(type, id)` context would make the map lie: a tag-granted action would read `false`, a tag-keyed
   deny would read `true`. The attributes come from the **request-scoped resource cache /
   `AbacResourceResolver`** shipped by [[RESOURCE-RESOLUTION]] (Phase 5.97): list rows are already in
   hand from the filtered query, single resources from the gate's resolution — the advice never
   re-loads.

## The OpenAPI-codegen fit (the key open mechanism — `x-implements`)

The advice must recognize *which* return types to enrich. The clean, codegen-native way (confirmed
against the reference platform's external-credentials DTOs, which stamp `java.lang.AutoCloseable` onto a
generated DTO this exact way):

```yaml
# in the catalog OpenAPI spec, on a resource schema:
Category:
  x-implements:
    - dev.dmitriikonovalov.opaabac.security.web.Enrichable   # library marker interface
  properties: { … }
```

`org.openapi.generator` (the `spring` generator we already use) reads `x-implements` and makes the
generated POJO `implements Enrichable` — **no DTO hand-editing, no post-processing**. The library ships:

- the **`Enrichable`** marker interface (a resource DTO that may carry an `_actions` map — likely with a
  default `abacResourceType()` / `setActions(Map)` contract);
- the **advice** that detects `Enrichable` (or `Iterable<Enrichable>` / `Page<Enrichable>`) returns,
  builds the per-resource `AbacContext`s, calls `allowAll`, and writes `_actions`;
- the **action registry** SPI + a default backed by the spec.

The app opts a DTO in with **one `x-implements` line** in its spec — exactly the low-ceremony adoption the
starter aims for. (`x-closeable`-style companion extensions, as the credentials DTO uses for
`AutoCloseable`, are the precedent that this is a supported, idiomatic codegen hook.)

> This is the load-bearing open question the maintainer flagged: **how enrichment fits OpenAPI generation.**
> The `x-implements`-marker approach is the working answer; the alternatives (a hand-written wrapper
> envelope `Authorized<T>`; a generic `_actions` sibling field added to every schema; a separate
> non-generated response type) are weighed in the design pass.

## Open questions — ✅ RESOLVED (grill-me 2026-06-17)

> All five are settled in the *Settled* block near the top of this note and pinned in
> **ADR [[0016-action-enrichment-affordance-metadata|0016]]** (with rejections). Kept below for the
> reasoning trail; **do not reopen during the run**. Briefly: **envelope** = inline `_actions` (Option B —
> marker + explicit `readOnly` property); **DTO→cache lookup** = `cache.get(type,id)` with **omit on
> miss/failure** (never re-resolve, never fabricate); **perf/opt-out** = automatic on `Enrichable`, the
> `perPage ≤ 100` cap bounds the batch, `opa.abac.action-enrichment.enabled` kill-switch; **registry** =
> the per-type sub-interface carries `abacActions()` (no SPI bean); **the ADR** = 0016, authored with this
> decomposition.

- **The envelope shape.** Inline `_actions` on the resource (mutates the DTO, needs the marker to expose a
  setter) vs. a wrapping `Authorized<T>{ data, actions }` (cleaner separation, but changes every enriched
  endpoint's response schema). The `x-implements` marker leans toward inline; confirm against pagination
  (`Page<Enrichable>` → `_actions` per element). → **inline (Option B); `<Resource>Page` items each carry
  `_actions`.**
- **Batch context lookup mechanics.** ~~Whether contexts are attribute-rich~~ — settled (direction
  point 4: yes, via the [[RESOURCE-RESOLUTION]] cache). What remains: the advice sees generated
  **DTOs**, not entities — pin the DTO → `(type, id)` → cache-lookup path, and the posture when a row
  is *missing* from the cache (resolve on demand vs omit `_actions` for that element — enrichment is
  affordance-grade, not enforcement-grade, so a gap must degrade visibly, never guess).
- **Performance / opt-out.** One batch call per response is cheap, but a 200-element page × M actions is a
  large single input — cap, paginate, or make enrichment opt-in per endpoint (an annotation alongside the
  marker?). Decide the default.
- **Where the registry lives.** Library SPI + an app-provided bean (like `RoleDefinitionSupplier`), or
  derived from the spec's `x-implements` + an `x-actions` extension? Lean SPI for symmetry.
- **The ADR.** An action-enrichment ADR (decorator-vs-inline, batch-vs-N-calls, the `x-implements` marker,
  affordance-vs-enforcement separation) is **pending** in the [[adr/README|ADR index]] — authored *with*
  this slice's decomposition, per the convention.

## Dependencies & sequencing

- **Depends on:** [[DATA-FILTERING]] (Phase 5) — specifically `OpaClient.allowAll` (the batch primitive)
  and the per-type `bulk` rego rule. Enrichment is its first consumer; building Phase 5 first avoids
  building batch twice. ✅ Shipped.
- **Depends on:** [[RESOURCE-RESOLUTION]] (Phase 5.97) — the `AbacResourceResolver` SPI + request-scoped
  cache that supply each resource's **resolved attributes** to the enrichment context (direction
  point 4).
- **Depends on:** Phase 6.5 (ADR [[0007-coarse-grained-permission-categories|0007]]) — replaces flat
  `read`/`write` with the category-expanded fine-action vocabulary (`view`/`list`/`create`/`update`/
  `delete`/`define-tags`/`assign-tags`/`assign-roles`) that the action registry and `_actions` keys
  enumerate; landing 6.5 first avoids reworking the registry, Rego tests, and e2e matrices — and its
  "which roles may I assign?" affordance lands here. Slice order: **5.97 → 6.5 → 6**.
- **Feeds:** the user-facing stories in [[USER-STORIES]] under the "show me only the buttons I can use"
  epic.
- **Distinct from:** enforcement (ADR 0006 three layers) and data filtering (ADR 0005) — enrichment is
  read-side affordance, not a gate.

## Related
- [[POC-ROADMAP]] — Phase 6.
- [[RESOURCE-RESOLUTION]] — Phase 5.97, the resolver SPI + request cache this evaluates against.
- [[DATA-FILTERING]] — the Phase-5 batch primitive this consumes.
- [[USER-STORIES]] — the "which buttons" epic this delivers.
- ADR [[0005-partial-eval-to-jpa-specification|0005]] (batch is shared with filtering) ·
  [[0006-three-layer-enforcement-model|0006]] (enforcement, which enrichment is *not*).
