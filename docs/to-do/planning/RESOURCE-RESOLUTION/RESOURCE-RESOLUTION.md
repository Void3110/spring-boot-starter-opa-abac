---
tags:
  - status/planned
  - type/index
  - area/abac
  - area/opa
  - area/spring
---

# Resource resolution — attribute-rich pre-authorization

> **Status: Planning (direction set 2026-06-12; not yet decomposed).** Phase 5.97 of [[POC-ROADMAP]].
> This note captures the agreed direction + the open design questions; the full work package
> (grill-me → ADR → 00-DESIGN → /decompose) follows when the slice is picked up. **Sequenced before
> Phase 6** — action enrichment consumes what this ships.

## What it is

Two additive library pieces:

1. **`AbacResourceResolver` SPI** — `(resourceType, resourceId) → the resource as an AbacDataObject`,
   implemented by the app (the same opt-in-SPI shape as `RoleDefinitionSupplier` and `AncestorResolver`).
   With a resolver registered, the `@OpaPreAuthorize` authorization manager resolves the **instance**
   behind a declared `resourceId` and sends its **real attributes** (tags, ownership, state) to OPA.
   Today the pre-invocation gate names the resource by `(type, id)` with empty attributes — its own
   Javadoc marks per-instance attribute checks as "a later phase" — so attribute-dependent rules
   (tag grants, tag-keyed denies) can only run in the post-load layer-3 check.

2. **Request-scoped resource cache** — the resolved instance is cached for the duration of the request,
   so (a) the handler **reuses the loaded resource instead of issuing a second SELECT**, and (b)
   downstream read-side consumers — first of all the Phase-6 action-enrichment advice — read each
   resource's attributes without re-loading.

## Why a slice of its own

- **It closes a documented gap.** The `@OpaPreAuthorize` Javadoc explicitly defers per-instance,
  attribute-based pre-invocation checks to "a later phase". This is that phase.
- **It collapses duplicated checks.** The catalog example's programmatic layer-3 tag check
  (`CategoryAuthorizer`) exists *only because* the gate can't see tags. With resolution at the gate,
  tag-based grant/deny rules run declaratively in the annotation; layer 3 shrinks to what genuinely
  needs mid-transaction state.
- **It eliminates the double load.** Gate-then-handler today means authorize-by-reference, then load;
  with the cache the authorized instance *is* the one the handler uses.
- **It is a prerequisite for Phase 6** (settled 2026-06-12): the `_actions` affordance map must be
  computed against **fully resolved resource attributes**, the same context enforcement sees —
  otherwise the map lies (a tag-granted action would read `false`, a tag-denied one `true`). See
  [[ACTION-ENRICHMENT]].
- **It is the Spring-native generalization of a proven pattern.** The source platform centralizes
  pre-authorization in a command-pipeline decorator that fetches the resource, authorizes the loaded
  instance, and caches it for the handler. The fetch–authorize–cache half generalizes cleanly; the
  *central class-keyed rule registry* half deliberately does **not** — it presupposes a uniquely-typed
  command object per operation (a mediator), which this starter must not require. Co-located
  `@OpaPreAuthorize` annotations remain the rule table; the SPI supplies what they're missing.

## Posture (pinned now, ahead of grill-me)

- **Opt-in and additive.** No resolver bean registered → exactly today's reference-based behavior;
  zero change for existing adopters.
- **Fail-closed.** A registered resolver that throws or returns nothing → **deny** — never a silent
  fallback to the attribute-less context, which could skip attribute-keyed deny rules (i.e. widen).
- **Zero Rego changes required.** Richer `input.resource.attributes` feeds the rules that already
  exist (`tags_satisfied`, deny clauses); policies that ignore attributes behave identically.
- **`opa-abac-core` stays Spring-free.** Where the SPI interface lives (core, like
  `RoleDefinitionSupplier`) vs. where the request-scoped cache lives (spring-security) is a design
  fork, but the module-dependency direction is not negotiable.

## Open questions (settle at grill-me)

- **Cache mechanism.** Request-scoped bean vs `RequestContextHolder` vs ThreadLocal; behavior outside
  a request context (batch jobs, scheduled work, tests); async/dispatch boundaries.
- **SPI shape.** One resolver bean dispatching on `resourceType` vs per-type resolvers; exact return
  contract (the `AbacDataObject` itself vs a found/not-found envelope).
- **Hierarchy interplay.** Does the resolver also supply the ancestor chain (folding into the 5.5
  `AncestorResolver` flow), or do ancestors stay a separate input to context construction?
- **What remains of layer 3.** Tag checks move to the gate; transactional/state guards stay
  programmatic. Does the three-layer model (ADR [[0006-three-layer-enforcement-model|0006]]) need a
  superseding note in the new slice ADR?
- **Mutation semantics.** The cached instance is an *authorization-time snapshot* resolved outside the
  handler's transaction — pin the reuse rules for mutating handlers (re-load-for-update vs reuse;
  optimistic-version interplay).
- **List-path population.** Does `AbacQueryService.findAuthorized` populate the cache with the rows it
  returns (so Phase-6 enrichment reads attributes with zero extra SQL), or does enrichment
  batch-resolve? Lean: populate at the query layer — the rows are already in hand.
- **Proof shape.** The decisive e2e contrast: a tag-dependent action allowed/denied **at the gate**
  (today only decidable post-load), plus evidence the double load is gone.

## Dependencies & sequencing

- **Builds on:** the Phase-3 spine (`@OpaPreAuthorize` manager), Phase 4.5 (tags as the attributes
  worth resolving), Phase 5.5 (hierarchy model, if ancestors fold in).
- **Feeds:** Phase 6 [[ACTION-ENRICHMENT]] — **required**; enrichment contexts are attribute-rich by
  decision. Sequence: 5.97 → 6.
- **Independent of:** 6.5 (permission categories), 7 (publish), 8 (ReBAC).

## Related

- [[POC-ROADMAP]] — Phase 5.97.
- [[ACTION-ENRICHMENT]] — Phase 6, the first consumer.
- ADR [[0006-three-layer-enforcement-model|0006]] — the enforcement layers this rebalances.
- [[TAG-BASED-AUTHORIZATION]] — the tag grant/deny semantics that become gate-decidable.
