---
tags:
  - type/index
  - area/abac
  - area/user-service
---

# Architecture Decision Records (ADRs)

> The **why** behind structural decisions, kept as small immutable records. Each ADR captures one
> decision: its context, the options weighed, the choice, and the consequences we accepted. Unlike the
> prose architecture docs ([[DOMAIN-MODEL]], [[TWO-LAYER-AUTHORIZATION]]) which describe how things work
> *now*, an ADR is a dated snapshot of *why a fork was taken* — it doesn't get rewritten as the system
> evolves; it gets **superseded** by a later ADR.

## Format

Lightly-MADR. Each record has: **Status · Context · Decision · Considered options (with why-rejected) ·
Consequences**. Filenames are `NNNN-short-title.md` (zero-padded, monotonic). A decision is never edited
once `Accepted` — to change it, write a new ADR that supersedes it and flip the old one's status.

## Status values

`Proposed` → `Accepted` → (`Superseded by [[NNNN-...]]` | `Deprecated`).

## When to write one (the convention, going forward)

ADRs are **part of the decomposition process**, not an afterthought. When planning a feature
(`docs/to-do/planning/<FEATURE>/`), a **structural decision** gets an ADR — written *up front*, as the
fork is decided, and linked from the feature's `00-DESIGN`. A decision is "structural" when it would be
expensive to reverse or surprising to a future reader: a schema or authority shape, a module/service
boundary, where a check is evaluated (app vs. policy), an additive-vs-breaking choice, a "we deliberately
did **not** do X" with real alternatives weighed.

Why up front, not after: a feature's `00-DESIGN`/`01-DECOMPOSITION` are *living* docs — they get
rewritten and then `git mv`'d to `implemented/` on ship, so the rationale buried in them drifts or moves.
An ADR is *immutable* — it pins the decision and its rejected alternatives at a point in time. Routine
implementation choices (naming, file layout, which test library) do **not** need an ADR; reach for one
only when you catch yourself writing a "considered & rejected" list worth keeping.

> Records 0001–0004 were written **retroactively** for the Phase-4/4.5 user-management work (the decisions
> were sound, the docs just hadn't been pinned). From here, the ADR is authored *with* the decomposition —
> **0005 and 0006 are the first records written *up front*** (0005 pins a Phase-5 fork as part of the
> [[DATA-FILTERING]] decomposition; 0006 pins a cross-cutting model that previously lived only in prose).

## Index

| # | Title | Status | Area |
|---|-------|--------|------|
| [0001](0001-user-management-entity-graph.md) | User-management entity graph & layered service structure | Accepted | user-service |
| [0002](0002-team-and-team-target-indirection.md) | Team + team-target: the resource→authority indirection | Accepted | user-service · abac |
| [0003](0003-role-definitions-role-not-grant.md) | Role definitions: role ≠ grant, system + team-scoped, app-resolved | Accepted | user-service · abac |
| [0004](0004-dynamic-tag-dictionary.md) | The dynamic tag dictionary: three layers, global + team, match-in-Rego | Accepted | user-service · abac |
| [0005](0005-partial-eval-to-jpa-specification.md) | Partial evaluation → JPA `Specification`: residual conditions in the SQL `WHERE` | Accepted (implemented) | spring-data · abac · opa |
| [0006](0006-three-layer-enforcement-model.md) | The three-layer enforcement model: gateway → app → DB | Accepted | abac · opa · spring |
| [0007](0007-coarse-grained-permission-categories.md) | Coarse-grained permission categories (READ/WRITE/TAG/GRANT) + the five-tier ceiling model | Accepted (planned) | user-service · abac · spring |
| [0008](0008-hierarchical-resource-authorization.md) | Hierarchical (N-level ancestor) resource authorization: chain-in-input, opt-in inheritance, `ltree`/CTE resolver SPI, fail-closed | Accepted (implemented) | abac · opa · spring-data |
| [0009](0009-tag-requirement-subject-side.md) | The tag access requirement is subject-side (on the role), not resource-side: AWS-IAM-style, fail-closed, row-filter-native; Keycloak-style resource-gated clearance deliberately deferred | Accepted | abac · opa · spring-data |
| [0010](0010-hierarchy-aware-list-filter.md) | Hierarchy-aware list filter — the `subtreeSpec` composition: root-only subtree roots, the `subtreeOf` SPI (predicate-not-id-set), deny-overrides-as-SQL (`abac_deny`), the hierarchy-aware allowlist batch; extends ADR 0008 (took the "0009" 0008 had reserved) | Accepted (implemented) | abac · opa · spring-data |
| [0011](0011-error-contract-problem-json.md) | Error contract: RFC-7807 `application/problem+json` (minimal additive superset, no hosted type registry) + a library-owned, app-extensible `ApiErrorCode` vocabulary (interface + base enum, semantic granularity, typed in the OpenAPI schema); clean replacement of `ApiError` | Accepted (implemented) | api · architecture · spring |
| [0012](0012-pagination-envelope.md) | Pagination envelope composed with ABAC list filtering: exact-count `{count, page, perPage, items}` on all four query paths, the additive `Pageable`/`Page<T>` seam (wire shape spec-owned), strict 0-based params (no clamping, past-the-end = 200+empty), determinism by construction (fixed `createdAt,id` order + the unsorted-`Pageable` guard), envelope-everywhere/authz-nowhere | Accepted (planned) | api · spring-data · abac |
| [0013](0013-attribute-rich-pre-authorization.md) | Attribute-rich pre-authorization: the split `AbacResourceResolver`/`AncestorChainSupplier` SPI (app resolves the instance, starter composes ancestors), the gate's full per-instance decision (tags + ancestors, role on the governing root — mirrors `HierarchicalAuthorizer`), split fail-closed semantics (instance failure → deny; ancestor failure → direct-only), the write-through request-scoped cache, version binding via the one JPA `@Version` (reads return the snapshot; mutations guard → `409 STATE_CONFLICT`), the `opa.abac.resource-resolution.enabled` kill-switch; central class-keyed rule registry explicitly rejected | Accepted (planned) | abac · architecture · spring |
| [0014](0014-supplier-outage-error-distinct.md) | Role-source outage is error-distinct from no-role at the `RoleDefinitionSupplier` SPI: a tri-state contract (`Optional.of`=resolved · `Optional.empty()`=authoritative no-role→realm fallback · **throw `RoleResolutionException`**=outage→deny/no-widening), an unchecked exception in `opa-abac-core`, the strict HTTP classification (only `204`→fallback, only `200`+valid→resolved, all else throws), supplier-classifies/consumer-maps across all five `lookup()` consumers (no wrapper), **no kill-switch** (the off-ramp would be the vuln), **zero Rego** (the fallback is retained); closes the one widening-on-failure path ([[PERMISSION-CATEGORIES-REVIEW]] C1/C4, aggravated by 6.5) | Accepted (planned) | abac · architecture · security |
| [0015](0015-control-plane-vocabulary-categorization.md) | Control-plane vocabulary categorization (Phase 6.7): extend the 6.5 coarse-category model to the `team:*` management verbs — one new category **`CONTROL`** → `[add-member, change-role, remove-member]` (the coarse `manage` verb split so it is deny-refinable), `list-members` added to `READ`, `team.rego` becomes category-driven via the shared `permissions.effective_actions` (symmetric with `catalog.rego`), `define-roles`/`transfer-ownership` stay **owner-only-by-code fences** outside the category system; `TeamRoleCapabilities` recast to emit category tokens; custom roles stay **management-incapable** (I12; `validateContract` now 422s the dead-data case); `TAG` left intact (`define-tags` re-gated mechanically — same outcomes); the two-axis principle (verb category vs the untouched `MembershipService` escalation gates); no DB migration; B2's tri-state supplier contract untouched | Accepted (planned) | abac · architecture · user-service |
| [0016](0016-action-enrichment-affordance-metadata.md) | Action enrichment / affordance metadata (Phase 6): an automatic `ResponseBodyAdvice` attaches an `_actions` map (bare-verb keys → `true`/`false`) to returned `Enrichable` resources/pages — read-side affordance, **not** enforcement; **inline on the DTO** via the OpenAPI `x-implements` marker + an explicit `readOnly` `_actions` property (wrapping `Authorized<T>` rejected); the **per-type sub-interface carries `abacResourceType()` + `abacActions()`** (it *is* the registry + validation allowlist — a separate SPI bean rejected); verdicts computed against **resolved attributes** from the generalized **`AbacResourceCache` (relocated to `opa-abac-core`)**, the list path write-through its post-filter survivors (re-resolve-in-advice rejected — double-load/drift); **reuses `allowAll` verbatim** (zero `OpaClient`/Rego change); the **omit-on-failure** degrade contract (present ⇒ complete real verdict, absent ⇒ couldn't-compute; all-false-on-failure rejected as a fail-open footgun); **affordance honesty** — enumerate only fully-OPA-decided verbs (team's Java-co-gated `change-role`/`define-roles`/`transfer-ownership` excluded); `opa.abac.action-enrichment.enabled` kill-switch; cache stays an attribute snapshot, never a verdict | Accepted (planned) | abac · architecture · spring |
| [0017](0017-cross-service-http-resilience.md) | Cross-service HTTP resilience (Slice B3): a uniform retry/backoff/circuit-break posture over all three cross-service HTTP edges (`HttpOpaClient`, `HttpRoleDefinitionSupplier`, `TagDefinitionClient`) that softens B2's outage→deny wall **without** re-opening the realm fallback — OPA resilience as a **decorator over the existing `OpaClient` interface** (no pluggable transport in core), the fail-closed contract **identical in every breaker/config state** (`compile`→`error()` with `fromError=true`, never `denyAll()`/`allowAll()` — the 5.5-B widening landmine), **side-effect-free** retry (incl. read-timeout; future mutating edges opt out), **asymmetric per-edge budgets** (OPA 1 retry / resolve+tag 2; no resilience under a DB lock), **three breakers** per-endpoint with **breaker outcome-invariance** (latency/load only, never a decision input), **optional/conditional Resilience4j** in the starter, a thin backend-agnostic **`CallGuard` seam** (R4j today, Boot-4 native later — second artifact line deferred), a **per-edge kill-switch** (the principled inverse of B2 — off = a safe baseline), **virtual-time tests** (injectable clock); zero core/Rego change | Accepted (planned) | abac · architecture · spring |

## Related
- The example app these decisions shape: [[USER-MANAGEMENT-SERVICE]] (Phase 4) and [[TAG-DICTIONARY]] (Phase 4.5).
- The library slices they feed/pin: [[DATA-FILTERING]] (Phase 5) · [[ACTION-ENRICHMENT]] (Phase 6).
- The authorization model they feed: [[TEAM-BASED-AUTHORIZATION]], [[TAG-BASED-AUTHORIZATION]], [[ABAC-AUTHORIZATION]].
- The library base they build on: [[DOMAIN-MODEL]].
