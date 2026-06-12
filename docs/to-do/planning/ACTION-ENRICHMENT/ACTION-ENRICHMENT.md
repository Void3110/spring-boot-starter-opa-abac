---
tags:
  - status/planned
  - type/index
  - area/abac
  - area/opa
  - area/spring
---

# Action enrichment — affordance metadata on returned resources

> **Status: Planning (design-direction set; not yet decomposed).** Phase 6 of [[POC-ROADMAP]]. This note
> captures the agreed direction + the open design questions; a full work package (00-DESIGN /
> 01-DECOMPOSITION / prompt / QA / STATUS stubs) is written once the open questions settle. The Phase-5
> batch primitive (`OpaClient.allowAll`, [[DATA-FILTERING]]) has shipped; the remaining prerequisites are
> **[[RESOURCE-RESOLUTION]] (Phase 5.97)** — the resolver SPI + request-scoped cache that supply the
> resolved attributes enrichment evaluates against (direction point 4 below) — and **Phase 6.5**
> (ADR [[0007-coarse-grained-permission-categories|0007]]) — the category model that fixes the
> fine-action vocabulary the action registry enumerates. Slice order: **5.97 → 6.5 → 6**
> (settled 2026-06-12).

## What it is

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

## Open questions (settle before decomposing)

- **The envelope shape.** Inline `_actions` on the resource (mutates the DTO, needs the marker to expose a
  setter) vs. a wrapping `Authorized<T>{ data, actions }` (cleaner separation, but changes every enriched
  endpoint's response schema). The `x-implements` marker leans toward inline; confirm against pagination
  (`Page<Enrichable>` → `_actions` per element).
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
