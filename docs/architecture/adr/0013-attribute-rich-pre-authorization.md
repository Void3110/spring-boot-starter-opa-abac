---
tags:
  - status/active
  - type/decision
  - area/abac
  - area/architecture
  - area/spring
---

# ADR 0013 — Attribute-rich pre-authorization: resource resolution at the gate

> **Naming note (2026-07-08, pre-publish API polish):** the interface this ADR calls `AbacDataObject` was renamed **`AbacResource`** before the first publish — it names the role the object plays in the authorization query (`input.resource`), completing the `AbacResourceResolver`/`AbacResourceCache` naming family. The decision content is unchanged.


**Status:** Accepted (planned — Phase 5.97, [[RESOURCE-RESOLUTION]])
**Date:** 2026-06
**Context tags:** `@OpaPreAuthorize`, resource resolution, request-scoped cache, TOCTOU / version binding, governing-root role, fail-closed

> This ADR pins the **resource-resolution fork** for **Phase 5.97**: how the `@OpaPreAuthorize` gate
> obtains the *instance* behind a declared `resourceId` so its decision is made on real attributes
> (tags, hierarchy) instead of a bare `(type, id)` reference. The scope was settled in a planning
> interview (2026-06-12); the forks closed here are the ones that would otherwise stall an autonomous
> run mid-ticket on an unpinned fail-open/contract semantic. The slice order around it is pinned in
> [[POC-ROADMAP]]: **5.97 → 6.5 → 6** (Phase 6 enrichment consumes what this ships).

## Context

The `@OpaPreAuthorize` gate is **reference-based**: it names the resource by `(type, id)` with empty
attributes and resolves the caller's role **on the leaf id**. Its own Javadoc defers per-instance,
attribute-based checks to "a later phase". Four consequences, all observable in the shipped code:

1. **Tag rules are undecidable at the gate.** `tags_satisfied` is fail-closed on missing attributes, so
   per-instance tag grants/denies can only run in a post-load layer-3 check — the example's
   `CategoryAuthorizer` exists solely for this.
2. **Id'd gate decisions for team members fall through to the realm-role fallback.** Under the HTTP role
   source, team targets match resources **exactly** (`ExactTeamTargetMatcher`), so a leaf lookup
   (`("category", id)`, `("product", id)`) resolves no role definition and the policy's FALLBACK clause
   decides from **JWT realm roles, tag-blind**. The team/tag model the repo demonstrates does not
   actually govern id'd writes at the gate today.
3. **The gate-then-handler double load**: authorize by reference, then load the entity again.
4. **No version binding**: the decision is made on whatever state the check saw; a parallel writer can
   change the resource between the gate and the handler's own load (TOCTOU), and nothing detects it.

Phase 6 (action enrichment) additionally requires resolved attributes so the `_actions` affordance map
mirrors enforcement.

The maintainer's prior platform solves this with a command-pipeline decorator that fetches the resource,
authorizes the loaded instance, and caches it for the handler — built around a mediator and a central
class-keyed rule registry. This ADR generalizes the *fetch–authorize–cache* half into Spring-native form
and deliberately rejects the registry half.

## Decision

### 1. A split SPI: the app resolves the instance; the starter composes ancestors

Two small, Spring-free interfaces in **`opa-abac-core`**:

- **`AbacResourceResolver`** — `Optional<AbacResource> resolve(String resourceType, String resourceId)`.
  Implemented by the app as **one bean**, dispatching on `resourceType` internally (the catalog example: a
  three-way switch over its repositories).
- **`AncestorChainSupplier`** — `List<ParentRef> ancestorsOf(String resourceType, String resourceId)`.
  Not implemented by apps directly: the **starter auto-binds it to the Phase-5.5 `AncestorResolver` bean**
  when one is present (the starter legally sees both `spring-security` and `spring-data`; the manager sees
  only core types).

### 2. With a resolver present, the gate makes the full per-instance decision

For an annotation that declares a `resourceId`, the `OpaPreAuthorizeAuthorizationManager`: resolves the
instance → obtains the ancestor chain → resolves the role **once on the governing root** (the chain's
first element, or the leaf when there is no lineage) → builds the `AbacContext` with the instance's
attributes **and** `ancestors` → asks OPA. This mirrors `HierarchicalAuthorizer`'s composition exactly,
so the gate decision **equals** the layer-3 decision it replaces — never narrower (inherited grants
survive), never tag-blind. Type-level checks (no `resourceId`) and the `resource()`-SpEL path are
unchanged.

### 3. Split failure semantics (the fail-closed core)

- **Instance resolution fails or returns empty → DENY.** Never a silent fallback to the attribute-less
  context: missing attributes could skip attribute-keyed deny rules, i.e. *widen*.
- **Ancestor resolution fails → the chain collapses to empty** and the decision proceeds direct-grant-only
  — the 5.5 posture verbatim: never strips a direct grant, never widens.
- **No resolver bean registered → today's reference-based behavior, byte-identical.** Opt-in and additive.

### 4. A write-through, request-scoped resource cache

The manager `put`s the resolved instance after an **allow** (and on the `resource()` path); an injectable
typed accessor (`AbacResourceCache.get(type, id, Class<T>)`) lets the handler and later read-side
consumers (Phase-6 enrichment) reuse it. Storage is request-attributes (`RequestContextHolder`): no scope
proxying, naturally request-bounded, and a clean **no-op outside a web request**.
*Amended by [[0032-root-attribute-enrichment-input-contract|ADR 0032]]:* the decided **leaf** is still
never read back (resolved fresh, written only on allow), but the governing **root** is now a
decision-read memo — the gate read-through-memoizes the root's attributes into its own input.

### 5. Version binding: the decision and the action see the same resource version

The snapshot the gate cached is the *authorized version*. Doctrine:

- **Read handlers return the snapshot** — the response is exactly the state the decision saw.
- **Mutating handlers never persist the snapshot.** They load fresh inside their transaction (as today)
  and **guard**: fresh version ≠ snapshot version → **`409 STATE_CONFLICT`** (the existing
  `LibraryErrorCode`), client retries, the retry's gate decides on the new state. The TOCTOU race becomes
  *detected*, not accepted.
- Mechanism: a Spring-free **`Versioned`** interface in core (`Integer getVersion()`),
  `BaseModel extends Versioned` in spring-data (a pure hierarchy statement — the method already exists),
  and a core `VersionGuard` throwing `VersionConflictException`. **The existing JPA `@Version` is the one
  version field** — one number, two consumers (persistence races and decision binding).
- Resources without a version attribute can't be guarded; for them the window stays today's
  load-then-check posture, documented, never silent.

### 6. A kill-switch property

`opa.abac.resource-resolution.enabled` (default `true`). Off → the manager skips resolution entirely and
the gate reverts to the pre-5.97 reference-based context, beans untouched — the rollback path for a buggy
or slow resolver (whose failures otherwise manifest as mass 403s, by §3). Documented caveat: the switch
restores the *baseline*, so attribute-keyed **deny** rules are only enforced while resolution is on (none
exist in the shipped policies today).

### 7. The pinned behavior change (and non-changes)

For **team members** on id'd endpoints, the decision basis moves from the realm-role fallback
(tag-blind, leaf-lookup 204) to **team role on the governing root + tag rules** — the model the repo was
always demonstrating. Concretely: a member whose team role grants a tag-matched write gains access the
fallback denied; a member whose team role does *not* support the write loses the access the fallback
leaked. **Non-members were unchanged** at 5.97 (no role definition at the root either → the then-live
fallback still decided) — *Slice B4 ([[0018-team-scoped-resource-isolation|ADR 0018]]) later removed
that blanket fallback*; non-members are now denied on id'd endpoints, with only the verb-gated
`catalog:create` check surviving. Creates and lists (type-level, no id) are unchanged — the create-targets-the-parent question
belongs to the Phase-6.5 action-vocabulary redesign, not here.

## Considered options

| Option | Why not |
|--------|---------|
| **Port the central class-keyed rule registry (mediator-style)** | It presupposes a uniquely-typed command object per operation for total, type-safe class→rule lookup. Spring MVC's dispatch unit is the controller method; a central registry degrades to method-name/pointcut keys — runtime-only binding, two sources of truth. Co-located annotations *are* the rule table; the SPI supplies what they lack. |
| **Tags-only enrichment (no ancestors)** | Narrower than the layer-3 decision for hierarchical resources: leaf-role + tags + no ancestors evaluates only the direct grant, so root-granted users would be denied at the gate. A behavior break dressed as a smaller slice. |
| **App-assembled rich SPI (app returns instance + ancestors)** | Every adopter rewrites the same composition glue and owns two failure semantics that must not be confused (deny vs collapse) — the exact fail-open foot-gun class planning exists to remove. Library code does it once. |
| **Per-type resolver beans** | More Spring ceremony for no demonstrated need; one dispatching bean serves the three-type example. |
| **Persist the snapshot for mutations (`@Version` merge as the guard)** | Detached-merge footguns (whole-field overwrite, cascades, lazy-init) and forces snapshot-based mutation on flows that aren't whole-entity saves. The explicit guard gives the same detection with managed-entity hygiene. |
| **Re-authorize on version drift instead of 409** | One request acting under two decision bases; ambiguous failure semantics (403 vs 409); hides the race instead of surfacing it. The 409-retry loop re-runs the gate on the new state cleanly. |
| **A separate ABAC-only version counter** | Manual bump discipline on every authz-relevant setter — each missed bump is a *silent fail-open*. The JPA `@Version` errs the safe way (false 409 → retry) and already covers tags and ltree-path changes (incl. subtree rewrites on re-parent). |
| **`@RequestScope` cache bean** | Scope-proxy config in auto-configuration and hard failures outside web contexts; request-attributes degrade to a no-op instead. |
| **No kill-switch** | A misbehaving resolver fail-closes into mass 403s with no remediation short of a redeploy; the property restores known-good baseline semantics instantly. |

## Consequences

- **Good:** the gate becomes the complete per-instance decision — tag grants/denies and inherited grants
  are decidable declaratively; the example's layer-3 `CategoryAuthorizer` is deleted rather than
  reduced; the team/tag model actually governs id'd writes (the fallback hole closes for members); the
  TOCTOU window is detected via one version field; Phase 6 gets its resolved attributes from the same
  cache. Adoption stays one bean + zero annotation changes.
- **Cost:** the ancestor-walk SQL moves from layer 3 into the gate (net-zero — layer 3 stops doing it);
  benign concurrent edits inside the gate→handler window surface as retryable 409s; one more SPI pair and
  property in the starter's surface.
- **Additivity:** no resolver bean → byte-identical behavior; `opa-abac-core` gains only Spring-free
  types; spring-data's only diff is `BaseModel extends Versioned`; `AbacQueryService` and the four query
  paths untouched; zero Rego changes; user-mgmt deliberately unregistered (live opt-in coexistence
  proof).
- **Follow-on:** Phase 6 reads the cache for `_actions` (and registers a user-mgmt resolver when it needs
  team attributes); Phase 6.5's action-vocabulary sweep mechanically renames the action strings this
  slice's tests use; the layer-2/3 boundary of ADR 0006 is redrawn (see [[RESOURCE-RESOLUTION]] and the
  design's "what remains of layer 3").

## Related

- ADR [[0006-three-layer-enforcement-model|0006]] (the layers whose 2/3 boundary this redraws — not
  superseded; layer 3 retains state guards, list filtering, and the version guard) ·
  ADR [[0008-hierarchical-resource-authorization|0008]] (the ancestor model the gate now consumes) ·
  ADR [[0009-tag-requirement-subject-side|0009]] / [[0004-dynamic-tag-dictionary|0004]] (the tag rules
  that become gate-decidable) · ADR [[0011-error-contract-problem-json|0011]] (`STATE_CONFLICT`)
- [[RESOURCE-RESOLUTION]] (the Phase-5.97 slice) · [[ACTION-ENRICHMENT]] (Phase 6, the first cache
  consumer) · [[POC-ROADMAP]] (slice order 5.97 → 6.5 → 6)
