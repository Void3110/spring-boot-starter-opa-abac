---
tags:
  - status/planned
  - type/index
  - area/abac
  - area/opa
  - area/spring
---

# Resource resolution — attribute-rich pre-authorization

> **Status: Design settled (grill-me 2026-06-12) — ready for /decompose.** Phase 5.97 of
> [[POC-ROADMAP]]. Every fork below is now pinned by **ADR
> [[0013-attribute-rich-pre-authorization|0013]]** and elaborated in [[00-DESIGN]]; what remains is the
> decomposition package (tickets + QA + prompt + STATUS stubs). **Sequenced before Phase 6** — action
> enrichment consumes what this ships (order **5.97 → 6.5 → 6**).

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

## Forks resolved (grill-me 2026-06-12 → ADR [[0013-attribute-rich-pre-authorization|0013]])

All the formerly-open questions are pinned; see [[00-DESIGN]] for the mechanism and the behavior matrix:

- **Scope: full resolved context** — tags **and** ancestors; role on the **governing root** (tags-only
  would deny inherited grants; rejected).
- **SPI: split contract** — the app implements one `AbacResourceResolver` bean (instance lookup,
  type-dispatching); the **starter** binds `AncestorChainSupplier` to the 5.5 `AncestorResolver`.
  Failure semantics split: instance failure → **deny**; ancestor failure → **collapse to direct-only**.
- **Cache: request-attributes** (`RequestContextHolder`), write-through on allow, typed accessor,
  no-op outside web requests, never read by decisions.
- **Version binding (the maintainer's core concern):** reads return the snapshot; mutations load fresh
  and **guard against the snapshot's version** → `409 STATE_CONFLICT` on drift. One version field — the
  existing JPA `@Version` (an ABAC-only counter was rejected as a silent-fail-open trap).
- **Layer 3:** `CategoryAuthorizer` deleted; `HierarchicalAuthorizer` stays (programmatic alternative);
  ADR 0006 not superseded — 0013 records the redrawn 2/3 boundary.
- **List-path cache population: deferred to Phase 6** (its consumer); `AbacQueryService` untouched.
- **Kill-switch:** `opa.abac.resource-resolution.enabled` (default on) → baseline semantics.
- **Discovered during design:** under the HTTP role source, id'd gate decisions for members currently
  fall through to the policy's **realm-role fallback (tag-blind)** — `ExactTeamTargetMatcher` means a
  leaf lookup finds no role. The governing-root lookup closes that hole; the behavior matrix (flip +
  narrowing + unchanged cells) is pinned in [[00-DESIGN]] §3.

## Dependencies & sequencing

- **Builds on:** the Phase-3 spine (`@OpaPreAuthorize` manager), Phase 4.5 (tags as the attributes
  worth resolving), Phase 5.5 (hierarchy model, if ancestors fold in).
- **Feeds:** Phase 6 [[ACTION-ENRICHMENT]] — **required**; enrichment contexts are attribute-rich by
  decision. Slice order: **5.97 → 6.5 → 6** (settled 2026-06-12).
- **Independent of (mechanically):** 6.5 (permission categories — orthogonal surfaces; it slots between
  this slice and Phase 6 for action-vocabulary stability, and its repo-wide action-string sweep will
  mechanically rename the action strings this slice's tests use), 7 (publish), 8 (ReBAC).

## Inputs from the retro-audit (2026-06-12) — fold into the QA baseline at /decompose

[[RETRO-AUDIT-2026-06-12]] confirmed the fallback-hole class this slice closes and handed it four
QA-baseline items (details + rationale in the report's "Folded into Phase 5.97" table):

1. **409 advice wiring** — `OptimisticLockingFailureException` / `DataIntegrityViolationException` →
   `409 STATE_CONFLICT` in the shared `AbstractProblemAdvice` (today: 500). Lands with the
   `VersionGuard` ticket; acceptance must reach the mapped status (non-happy path), per the wiring rule.
2. **Supplier outage ≠ no-role** — a `RoleDefinitionSupplier` failure is currently indistinguishable
   from an authoritative empty result, and the catalog policies' JWT-roles fallback then *widens*; the
   resolver-failure → DENY posture this slice pins must cover (or explicitly scope out) the supplier seam.
3. **`tags_satisfied` only exists in `category.rego`** — the attribute-rich gate is only as good as the
   policies; product/catalog need the conjunct (or a documented category-only scope) in this slice's
   policy work.
4. **Decide-under-protection TOCTOU cells** — the user-mgmt subset/ceiling checks read unlocked actor
   state; this slice's version-binding doctrine is the model for the fix; pin baseline QA cells so the
   gate work doesn't regress them.

## Related

- [[POC-ROADMAP]] — Phase 5.97.
- [[ACTION-ENRICHMENT]] — Phase 6, the first consumer.
- ADR [[0006-three-layer-enforcement-model|0006]] — the enforcement layers this rebalances.
- [[TAG-BASED-AUTHORIZATION]] — the tag grant/deny semantics that become gate-decidable.
