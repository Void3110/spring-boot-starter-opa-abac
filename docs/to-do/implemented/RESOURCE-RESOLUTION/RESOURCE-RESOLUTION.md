---
tags:
  - status/done
  - type/index
  - area/abac
  - area/opa
  - area/spring
---

# Resource resolution — attribute-rich pre-authorization

> **Status: ✅ SHIPPED (2026-06-12)** — implemented end-to-end on branch
> `feature/void3110/resource-resolution`, one commit per ticket T1–T7. Phase 5.97 of [[POC-ROADMAP]],
> pinned by **ADR [[0013-attribute-rich-pre-authorization|0013]]**; the mechanism guide is
> [[ATTRIBUTE-RICH-PRE-AUTHORIZATION]]. The gate now resolves the instance behind a declared
> `resourceId` and decides on its real tags + ancestors (role on the governing root); the catalog
> adopted it (story C4 ✅ — the realm-fallback hole closed live, cell E2), user-mgmt deliberately did
> not (the opt-in coexistence proof, B1 green). **Next: 6.5** (order **5.97 → 6.5 → 6**) — action
> enrichment consumes the cache this shipped.

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

## The decomposition package (2026-06-12)

| File | Role |
|------|------|
| [[00-DESIGN]] | The mechanism, the behavior matrix, the proof obligations (phase-① deliverable). |
| [[01-DECOMPOSITION]] | The seven tickets T1…T7 (Goal / Deliverables / Acceptance / What-NOT-to-touch), the critical path, **the two decomposition-pinned semantics** (missing-id `403`; the `tags_satisfied` conjunct). |
| [[10-QA-TEST-CASES]] | U1–U18 / I1–I7 / P1–P5 / E1–E7 / D1–D3 + the retro-audit baseline cells B1–B3 + the fail-closed checklist. |
| [[AUTONOMOUS-IMPLEMENTATION-PROMPT]] | The self-contained phase-③ prompt (kept verbatim — a deliverable). |
| `STATUS-01 … STATUS-07` | One stub per ticket, filled at each checkpoint during the run. |

### Ticket status

| Ticket | Status |
|--------|--------|
| T1 — Core: split SPI (`AbacResourceResolver`/`AncestorChainSupplier`) + `Versioned`/`VersionGuard` (Spring-free, additive) | ✅ |
| T2 — spring-security: manager resolution flow + `AbacResourceCache` + `VersionConflictException` → 409 | ✅ |
| T3 — starter: composition + kill-switch (`opa.abac.resource-resolution.enabled`) + persistence 409 advice | ✅ |
| T4 — catalog adoption: resolver bean, `getCategory` to the gate, `CategoryAuthorizer` deleted, version guards + ITs | ✅ |
| T5 — policies: `tags_satisfied` conjunct for `product.rego`/`catalog.rego` (retro-audit fold-in #3) | ✅ |
| T6 — e2e: resource-resolution matrix (fixture `8888…`) + whole-suite coexistence | ✅ |
| T7 — docs: `ATTRIBUTE-RICH-PRE-AUTHORIZATION` guide + reconciliations + roadmap/stories/Mulch + folder move | ✅ |

**Critical path:** T1 → T2 → T3 → T4 → T6 → T7; **T5 parallel** (after T1, before T6). **T1+T2+T3**
are the independently-landable subset (the complete dormant library mechanism). Conventions:
clean-room (original names only), one focused commit per ticket, identity
`Void3110 <void31102025@gmail.com>`, **no push** — the maintainer pushes.

## Inputs from the retro-audit (2026-06-12) — folded into the package at /decompose

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

**Where each landed:** #1 → T2/T3 (cases U13/U17, I4/I5 — the mapped status reached on a non-happy
path); #2 → QA baseline **B2** + the guide's scope-out caveat (D1); #3 → **T5** (the conjunct, chosen
over the documented scope-out — [[01-DECOMPOSITION]] pinned semantic #2); #4 → QA baseline **B3**
(the fix itself stays a tracked follow-up, not this slice).

## Related

- [[POC-ROADMAP]] — Phase 5.97.
- [[ACTION-ENRICHMENT]] — Phase 6, the first consumer.
- ADR [[0006-three-layer-enforcement-model|0006]] — the enforcement layers this rebalances.
- [[TAG-BASED-AUTHORIZATION]] — the tag grant/deny semantics that become gate-decidable.
