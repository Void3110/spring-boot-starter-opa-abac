---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/opa
  - area/spring
---

# Proof-of-Concept Roadmap

> **Status:** Planning. This is the map of where the whole repo is going. Individual features
> get their own folder under `to-do/planning/`; this note tracks how they fit together and the
> order we tackle them.

## The thesis

**The starter (`opa-abac-*`) is the product.** Everything else exists to prove it works and to
show how to adopt it.

The library is a **clean-room generalization** of an ABAC + OPA authorization design that the
author built and ran in a prior production platform. The goal here is not to reproduce that
platform — it's to extract the *general* idea and make it **natively Spring-friendly**:

- No bespoke "mediator" layer between the app and authorization — wire into Spring Security's
  `AuthorizationManager`, method security (`@PreAuthorize` / a custom `@OpaPreAuthorize`), and
  Spring Data instead.
- Auto-configuration via a real Spring Boot starter, so adoption is "add a dependency + a few
  properties," not "adopt our framework's worldview."
- The hard parts the prior platform solved — hierarchical resource authorization, batch
  evaluation, partial-evaluation → data filtering — re-expressed as idiomatic Spring building
  blocks.

> **IP boundary.** The prior platform is a *study reference only* (see root `CLAUDE.md` →
> "IP Boundary" and the local-only `CLAUDE.local.md`). Nothing in this repo copies its source,
> names, or docs. We refer to it here only as "the source platform" / "a prior production system."

## What we're building (target shape)

```
opa-abac-core                     # framework-agnostic ABAC model + OPA client (allow / compile / batch; no Spring dep)
opa-abac-spring-security          # AuthorizationManager + @OpaPreAuthorize + action-enrichment advice (P6)
opa-abac-spring-data              # partial-eval → JPA Specification data filtering (P5)
opa-abac-spring-boot-starter      # the published starter (auto-config)

example-catalog-management-service   # the RESOURCE side of the demo (see naming note below)
example-user-management-service      # the SUBJECT/ATTRIBUTE side (roles, teams, users, tags)

compose.yaml · profile.sh         # local infra: Postgres → Keycloak → APISIX → OPA → Jaeger
```

### The two example services (a deliberate PoC, not throwaway demos)

They mimic a small but realistic microservice topology so the starter is exercised the way a
real system would use it — two services, one gateway, OPA in the middle.

| Service | Role in the demo | Models (very simplified) |
|---------|------------------|--------------------------|
| **catalog-management-service** | The **resource** side. A basic CDP-style product catalog — think SKU-level product master data feeding segmentation/personalization, but stripped to the essentials. | `Catalog → Category` (tree) `→ Product`. Hierarchical resources are exactly what the starter's hierarchical-authorization feature needs to prove itself. |
| **user-management-service** | The **subject/attribute** side. Supplies the identities and attributes that ABAC decisions are made *about* and *with*. Mirrors the shape of the source platform's user/role model, generalized. | Users, teams, teammates, role definitions, and a **dynamic tag dictionary** (the source platform hardcodes tags; we do it properly). See its own plan: [[USER-MANAGEMENT-SERVICE]]. |

Together they let us demonstrate the full loop end to end:
**Keycloak (identity) → APISIX (OIDC + coarse OPA) → service (fine-grained ABAC via the starter) → Postgres**,
with the catalog as the thing being protected and the user-service as the source of the
attributes that protect it.

## Naming & layout (decided)

- Two example apps are **flattened to repo root** as siblings of the library modules:
  `example-catalog-management-service` and `example-user-management-service`.
- The `example-` prefix makes each app self-describing; flat paths read cleanly next to the
  `opa-abac-*` modules and avoid a redundant `example/example-…` nesting.
- This means an early **prerequisite step**: rename/move the existing `example/catalog-management-service`
  → `example-catalog-management-service` and update `settings.gradle.kts`, package paths, and any
  references. Tracked as Phase 1 below. *(✅ Executed — commit `0ce6026`; see the phase table.)*

## Current state (chronological log)

> This section is an **append-style log**: each block below records the state *as of that phase's
> completion*, oldest first. For the up-to-date picture, read the **last** block and the phase table.

**Phases 0–2 done.** The example rig runs end to end via `./deploy.sh` (see
[`infra/README.md`](../../../../infra/README.md)):

```
Keycloak (identity) → APISIX [ openid-connect → demo identity-enricher → OPA decision → tracing ]
                        → round-robin over N catalog pods → Postgres
```

- **Load balancing**: APISIX round-robins over N app pods (`./deploy.sh up --pods N`).
- **Tracing**: Jaeger + Badger; 5 services traced (apisix, keycloak, opa, catalog app, jaeger).
- **Authz** *(as of Phase 2 — superseded in Phase 3)*: OPA called per request — the **allow-all
  placeholder** gateway policy (`infra/opa/policies/gateway.rego`, still a placeholder; the real
  decisions moved into the per-type app policies).
- **Identity** *(as of Phase 2 — superseded in Phase 3)*: Keycloak realm `catalog-demo` (user
  `demo/demo`), gateway OIDC; the demo Lua enricher was **throwaway** and is long replaced by
  Spring-native extraction.
- The "service does no auth yet" state ended with Phase 3: the app now does real, fine-grained ABAC.

**Phase 3 in progress.** Its first, load-bearing slice — the **domain-model foundation** (base/secure
entities, tags, locking, base service) — is **done and merged on a feature branch**: the reusable
library stack lives in `opa-abac-spring-data`, the catalog example adopts it (0002 migration, audit +
JSONB tags + optimistic version), `ProductService.mutate()` proves concurrent writers serialize, and
an e2e Postman/Newman suite runs green through the gateway. See [[DOMAIN-MODEL-FOUNDATION]].

**Second slice done (merged to `main`, PR #4):** the **library spine** — `HttpOpaClient` → `AbacContext`
extraction → role-definition-driven `@OpaPreAuthorize` → starter wiring → catalog adoption — replacing
the demo gateway enricher with Spring-native extraction. The e2e allow/deny matrix is green through the
gateway (viewer reads 200 / viewer writes 403 / editor writes succeed). See [[LIBRARY-SPINE]].

**Phase 3 is complete.** Both slices are merged; the catalog app does real, fine-grained,
role-definition-driven ABAC end to end.

**Phase 4 is complete.** The `user-management-service` ships: it owns **teams**, **role definitions**
(fixed system roles + owner-defined team-scoped custom roles), and **team-scoped grants**, and resolves
the caller's effective role *for a resource* by walking team membership — the **app-resolved** path. The
catalog's HTTP-backed `RoleDefinitionSupplier` swaps the demo one (a single-bean change). The team
abstraction (owner-on-create, transfer-ownership, the no-self-escalation subset rule) is enforced and
tested; the service **dogfoods** the starter to secure its own management API. The e2e team matrix is
green through the gateway with roles from real membership. See the shipped slice
[[USER-MANAGEMENT-SERVICE]] and the guide [[TEAM-BASED-AUTHORIZATION]].

**Phase 4.5 — the dynamic tag dictionary — ✅ DONE.** A runtime-editable tag dictionary (global +
team-scoped definitions: value-type/cardinality/allowed-values), tag *assignment* to sub-resources
(validated against the dictionary, fail-closed), and tag-based *grants* matched **in Rego** via
`requiredTags` + ANY_OF/ALL_OF (`some in`/`every`) — the source platform's hardcoded tags done properly.
The one library change is the additive `RoleDefinition.requiredTags`/`matchMode`. The decisive demo is
green through the gateway: the same role + permission, the grant flips on the resource's tags. Shipped
slice [[TAG-DICTIONARY]]; guide [[TAG-BASED-AUTHORIZATION]].

**Phase 5 — DONE.** The advanced library slice: **batch evaluation + partial-eval → JPA `Specification`
data filtering** (the list-endpoint "which rows may this subject see?" answered by pushing the OPA residual
into the SQL `WHERE` clause over the existing `tags` JSONB, with a batch call for the residue) — the
strongest "stands out vs naive OPA" piece in the repo. Shipped (T1–T7): `OpaClient.compile`/`allowAll` +
the neutral DNF residual model in core (Spring-free, fail-closed, abstract-not-default);
`ResidualSpecificationFactory` + `AbacQueryService` in spring-data (JSONB `Specification`, AND-with-scope,
the post-fetch allowlist + a `partialEval.enabled` kill-switch); the `category.rego` `filter`/`bulk`
entrypoints (`filter` **role-definition-only** so a missing role fails *closed* to an empty list, flat-verb,
scalar+array consistent with `allow`); the catalog list endpoint filtered in SQL; `opa test` 60/60; real-
Postgres Testcontainers ITs (two subjects → different row sets); and a green filter matrix through the
gateway (`run-filter-matrix.sh`). Pinned by ADR [[adr/0005-partial-eval-to-jpa-specification|0005]]; the DB
layer of ADR [[adr/0006-three-layer-enforcement-model|0006]]. Guide [[PARTIAL-EVALUATION-FILTERING|the data-filtering guide]].
The general per-instance/**hierarchical** ancestor-walk path the tag demo stubbed with a load-then-check is
explicitly **not** in this slice (it filters by the row's own tags) — it is now **Phase 5.5**, designed and
pinned by ADR [[adr/0008-hierarchical-resource-authorization|0008]].

**Phase 5.5 — hierarchical (N-level) resource authorization — ✅ SHIPPED (5.5-A + 5.5-B).** Slice
**5.5-A (single-resource)** is implemented (the `AncestorResolver` SPI + both resolvers, the opt-in
`AbstractHierarchicalEntity` + ltree/atomic re-parent incl. a **cross-table** rewrite, the
`HierarchicalAuthorizer`, the Rego `inherited_grant` + deny-overrides clause, the catalog adoption + ltree
migration, and an e2e matrix incl. the **re-parent flip**). See [[HIERARCHICAL-AUTHORIZATION]] +
[[HIERARCHY-SINGLE-RESOURCE]]. **5.5-B (hierarchy-aware list filter)** is **shipped** — an ancestor grant
widens a list (the OPA residual stays tag-only; hierarchy is a separate app-built `subtreeSpec` OR-ed in via
the new additive `AncestorResolver.subtreeOf` — ltree `path <@` pushdown / CTE bounded `id IN`, fail-closed —
composed by the 4-arg `AbacQueryService.findAuthorized` as `scope.and(tagResidual.or(subtreeSpec)).and(notDenied)`,
with the allowlist batch carrying ancestors and a small additive `allow` list-gate clause). Proven by
`HierarchyListFilterIT` (real Postgres: widening · two-subjects · `notDenied` · no-leak · re-parent-on-list)
and the `run-hierarchy-list-matrix.sh` e2e. Pinned by ADR [[adr/0010-hierarchy-aware-list-filter|0010]] (the
`subtreeSpec` composition, the `subtreeOf` SPI, deny-overrides-as-SQL, the hierarchy-aware allowlist batch).
See [[HIERARCHY-LIST-FILTER]] + [[PARTIAL-EVALUATION-FILTERING]]. Generalizes the
one-step parent hop (a Category's role resolved on its governing Catalog) to a **full ancestor-chain walk**:
a grant on a Catalog governs a Category and a Product nested under it, N levels deep. **Opt-in per relation,
deny-overridable, fail-closed** (a failed/cyclic/too-deep walk collapses the *inherited* contribution but
keeps the direct grant — never wider). The chain is resolved app-side (`AncestorResolver` SPI: an `ltree`
materialized-path impl by default, a recursive-CTE impl for re-parent-heavy trees) and supplied to OPA as
`input.resource.ancestors`; the role is resolved once on the governing root; the Phase-5 list residual stays
**tag-only** while an app-built `subtreeSpec` (`path <@` / `id IN`) **widens** the returned rows. Ships as
**two slices**: **5.5-A** single-resource (the walk, the SPI + both resolvers, the opt-in `AbstractHierarchical
Entity` + ltree/atomic re-parent, the Rego inheritance clause, e2e incl. a **re-parent test**) then **5.5-B**
the hierarchy-aware list filter. Pinned by ADR [[adr/0008-hierarchical-resource-authorization|0008]] (the
model) + ADR [[adr/0010-hierarchy-aware-list-filter|0010]] (the 5.5-B list forks); stories in
[[USER-STORIES]] (Epic H). Per-node independent grants (a mid-tree node with its own team) are deferred to
**Phase 8** (ReBAC-in-Rego).

**Then Phase 5.97 — attribute-rich pre-authorization (resource resolution).** An opt-in
**`AbacResourceResolver` SPI** + a **request-scoped resource cache**: the `@OpaPreAuthorize` gate resolves
the *instance* behind a declared `resourceId` and decides on its **real attributes** (today the
pre-invocation check sends empty attributes, so tag-dependent rules wait for the post-load layer-3 check),
then caches the loaded resource so the handler — and the Phase-6 enrichment advice — reuse it without a
second SELECT. Fail-closed (resolution failure → deny, never an attribute-less fallback), additive,
zero Rego. **Design settled** (ADR [[0013-attribute-rich-pre-authorization|0013]] +
[[RESOURCE-RESOLUTION]], grill-me 2026-06-12 — incl. version binding via the one JPA `@Version` → `409`
on drift, and the discovery that the governing-root lookup closes the realm-fallback hole on id'd member
decisions); ready for /decompose. It runs **first** of the three pending slices (**5.97 → 6.5 → 6**).

**Then Phase 6.5 — coarse permission categories + delegation** *(pulled ahead of Phase 6, 2026-06-12)*.
Replace flat `read`/`write` with four coarse buckets (`READ`/`WRITE`/`TAG`/`GRANT`) that expand to fine
actions, refined by deny-overrides, with a five-tier `role_level` ceiling and a safe-by-construction
delegation model (owner-only authoring, `GRANT` capped at admin, subset-rule at the senior tier). The
model is pinned in ADR [[0007-coarse-grained-permission-categories|0007]] and storied in [[USER-STORIES]]
(Epic G). It sequences **before** Phase 6 because it replaces the fine-action vocabulary (`read`/`write` →
`view`/`list`/`create`/`update`/`delete`/`define-tags`/`assign-tags`/`assign-roles`) that Phase 6's action
registry and `_actions` keys enumerate — landing it first avoids reworking enrichment, and its "which
roles may I assign?" affordance lands in Phase 6's batch shape. Remaining phase-① work before /decompose:
a focused grill-me on the implementation forks (the category-expansion ↔ partial-eval fold on the `filter`
entrypoint; role-def schema/back-compat; seeded-role migration; the repo-wide action-string sweep) →
`00-DESIGN`; the ADR stands.

**Then Phase 6 — action enrichment.** The first real consumer of Phase 5's batch primitive: a response
decorator that attaches a `{action: allowed}` affordance map to returned resources so a UI shows only the
buttons the user can click. Its `_actions` verdicts are computed against **fully resolved resource
attributes** (the 5.97 resolver/cache), so affordance mirrors enforcement — including tag grants/denies
and hierarchy — and its action registry enumerates the **6.5 fine-action vocabulary**. Direction is set
([[ACTION-ENRICHMENT]]); decomposition follows once 5.97 + 6.5 ship. After that, **Phase 7** (publish &
polish) and **Phase 8** (ReBAC-in-Rego — the team-grant join in the policy, to compare against the
app-resolved path shipped here).

> **Two lenses on this roadmap.** Beyond the technical phases here, the same work is tracked as
> **user stories** — what a person experiences when each mechanism is wired into the catalog service
> ([[USER-STORIES]]) — and the **structural decisions** behind it are pinned as immutable
> [[adr/README|ADRs]] (0005 = partial-eval→Specification, 0006 = the three-layer enforcement model;
> an action-enrichment ADR is pending). Use the stories as the product-facing acceptance lens and the
> ADRs as the "why this fork" record.

## Phases

> Phases 2–5 layer the library onto the catalog app incrementally (mirrors the CLAUDE.md
> "Incremental plan"). The user-service slots in once we need real attributes to drive decisions.

| # | Phase | Outcome | Notes |
|---|-------|---------|-------|
| **0** | Catalog CRUD (done) | Runnable catalog app, Postgres + Liquibase, no auth. | ✅ already in repo |
| **1** | **Restructure** | Flatten `example/` → `example-catalog-management-service`; settings + paths updated; build green. | ✅ **done** (commit `0ce6026`). |
| **2** | Infra: identity + gateway | Keycloak (realm) → APISIX (OIDC route) → OPA → Jaeger. | ✅ **done** — full rig via `deploy.sh`; see `infra/README.md`. |
| **3** | Library spine | `OpaClient` → `AbacContext` extraction → `OpaAuthorizationManager` → `@OpaPreAuthorize`, layered onto the catalog app. | ✅ **DONE** (on a feature branch). The core generalization work. First slice: [[DOMAIN-MODEL-FOUNDATION]] (base/secure entities, tags, locking, base service, e2e suite). Second slice: [[LIBRARY-SPINE]] (HttpOpaClient + extraction + role-definition-driven `@OpaPreAuthorize` + starter wiring + catalog adoption; the demo gateway enricher retired; e2e allow/deny matrix green). |
| **4** | **user-management-service (teams + role-defs)** | New example app: users, **teams** (members + a role hierarchy), **role definitions** (fixed system roles + owner-defined team-scoped custom roles), **team-scoped grants**, owner-on-create + transfer-ownership. The HTTP-backed `RoleDefinitionSupplier` resolves the caller's effective role *for a resource* by walking team membership server-side and feeds it to the catalog spine (a single-bean swap of the demo supplier). **Authorization is app-resolved**: the service resolves the role, the catalog still passes `role_definition` in OPA `input`. | ✅ **DONE.** Shipped slice [[USER-MANAGEMENT-SERVICE]] (T1–T9): the team/role-def core, owner-on-create, the subset rule, transfer-ownership, the `/internal/effective-role` resolve API, the catalog `HttpRoleDefinitionSupplier` swap, the second service in the rig, and a green e2e team matrix through the gateway. The service **dogfoods** the starter. Guide: [[TEAM-BASED-AUTHORIZATION]]. The **dynamic tag dictionary** is deferred to Phase 4.5. |
| **4.5** | **Dynamic tag dictionary (ABAC extension)** | A runtime-editable tag dictionary — **global + team-scoped** tag *definitions* (`valueType` STRING/ENUM, `cardinality` SINGLE/MULTI, optional `allowedValues`), tag *assignment* to sub-resources (validated against the dictionary), and tag-based *grants*: a role carries `requiredTags` + a `matchMode` and OPA grants when the resource's tags satisfy it — the **ANY_OF/ALL_OF match evaluated in Rego** (`some in`/`every`). The source platform hardcodes tag keys; this does it properly. | ✅ **DONE** (T1–T6). Shipped slice [[TAG-DICTIONARY]]: the `TagDefinition` dictionary (global+team partial-unique, seeded system keys) + `team:define-tags` management; tag assignment on Category validated fail-closed against the dictionary; the **additive** `RoleDefinition.requiredTags`/`matchMode` (the one library change, whole-repo build green); the `category.rego` `tags_satisfied` match (`some in`/`every`, vacuous back-compat, fail-closed) — `opa test` 49/49; and a green tag matrix through the gateway (the decisive same-role/different-tags 200-vs-403 contrast). Owner/admin **define** (`team:define-tags`); members **assign** (a normal write). Guide: [[TAG-BASED-AUTHORIZATION]]. Background: [[RESEARCH-AUTOTAG-AND-FILTERING]]. Lead-in to Phase 7 (match-in-policy). |
| **5** | Advanced library | Batch evaluation → partial-eval → JPA data filtering. | The differentiators vs. naive OPA integration. ✅ **DONE** (T1–T7). Shipped slice [[DATA-FILTERING]]: `OpaClient.compile`/`allowAll` + the neutral DNF residual model in core (fail-closed, abstract-not-default); `ResidualSpecificationFactory` (JSONB `Specification`, scalar+array via the `?` op) + `AbacQueryService` (AND-with-scope, post-fetch allowlist, `partialEval.enabled` kill-switch) in spring-data; the `category.rego` `filter`/`bulk` entrypoints (`filter` **role-def-only** → no-role-def fails closed to an empty list; flat-verb) — `opa test` 60/60; real-Postgres Testcontainers ITs (two subjects → different row sets) + a green gateway filter matrix. Pinned by ADR 0005; the DB layer of ADR 0006. Guide [[PARTIAL-EVALUATION-FILTERING]]. Hierarchical ancestor-walk held for a follow-up; ReBAC is Phase 8. |
| **5.9** | **REST API refinement (error contract)** | Close the publication-readiness gaps the REST API design review found — **no fail-open exists**, these are the distance between "clean demo" and "a published library's reference services". **In scope (settled via grill-me):** (1) the headline — **RFC-7807 `application/problem+json` + a machine-stable closed `errorCode` enum** shipped by the library (the *minimal additive superset* — `type`/`title`/`status`/`detail`/`instance` + the `errorCode` extension; **not** a hosted type-registry), so clients branch on a typed code, not a localizable message; (2) a `Location` header on every `201` (the id is already known — cheap); (3) one-line intent comments at the deliberately-ungated user-service bootstrap mutations (so an absent `@OpaPreAuthorize` reads as a decision, not a forgetting). **Explicitly OUT of scope:** pagination → its own slice **5.95**; affordance metadata → **Phase 6**. Sequenced **before** both so the `_actions` envelope and the page envelope land on an already-RFC-7807-clean error surface. | ✅ **DONE** (branch `feature/void3110/rest-api-refinement`, T1–T5). Shipped: the library `ApiErrorCode`/`LibraryErrorCode`/`ProblemDetail`/advice base; both services adopt `problem+json` + a typed `errorCode` (catalog reuses library codes; user-svc splits the 409 group); `Location` on every `201`; intent comments at the ungated bootstrap mutations. Proven by unit + per-service MockMvc ITs (real Postgres) + OpenAPI codegen + the extended newman matrices through the gateway (catalog/tag/team — live 403→`ACCESS_DENIED` `problem+json`, 422→`TAG_VALUE_ILLEGAL`, 201→`Location`). Source: [[REST-API-DESIGN-REVIEW]] (findings 1, 2, 4). Pinned by **ADR [[0011-error-contract-problem-json\|0011]]**. See [[REST-API-REFINEMENT]]. |
| **5.95** | **Pagination envelope** | A single shared list envelope (`{count, page, perPage, items}` or `Slice`-shaped) adopted **library-wide**, composed with the Phase-5 partial-eval residual (the residual `Specification` flows into a `Page`/`Slice` query — so the filtered row set paginates, not the full table). Replaces every bare unbounded array (`listCatalogs`/`listCategories`/`listProducts`/`GET /users`/`GET /teams`/members/role-defs/tag-defs). A data-layer change touching `AbacQueryService.findAuthorized`, not API polish — which is why it's its own slice, not folded into 5.9. | ✅ **DONE** (2026-06-11, branch `feature/void3110/pagination-envelope`, T1–T6; pinned by ADR [[0012-pagination-envelope\|0012]]). Shipped: the additive paged `findAuthorized(…, Pageable)` → `Page<T>` overload — **exact subject-relative `count` on all four query paths** (the allowlist fallback pages its SQL-sorted, batch-filtered result in memory at unchanged Phase-5 cost) + the unsorted-`Pageable` guard (determinism by construction: fixed `createdAt ASC, id ASC` everywhere); `PageEnvelope` + `<Resource>Page` (`allOf`) + shared strict 0-based `page`/`perPage` params in both specs (defaults 0/20, max 100, `400 VALIDATION_FAILED`, past-the-end = `200`+empty+exact `count`); all **9 public lists** on the envelope with **zero authorization change** (annotation diffs: 0 lines; only `listCategories` residual-filtered) and `/internal/**` unpaginated by design; the suite-wide clean wire break (3 collections / 8 sites → `items`, every pinned row count numerically identical); zero Rego changes; `opa-abac-core` untouched. Proven by U1–U7 unit cases + `PaginationListIT` (the 5-vs-3 count contrast, the `perPage=2` stability walk, fallback parity) + per-service envelope ITs + the new `run-pagination-matrix.sh` (27/27 through APISIX; whole suite 128 assertions green). See [[PAGINATION-ENVELOPE]]; guides [[REST-API-DESIGN]] §7 + [[PARTIAL-EVALUATION-FILTERING]] (the paged composition). |
| **5.97** | **Attribute-rich pre-authorization (resource resolution)** | An opt-in **`AbacResourceResolver` SPI** (`(resourceType, resourceId) → AbacDataObject`, app-implemented — the same SPI shape as `RoleDefinitionSupplier`/`AncestorResolver`) + a **request-scoped resource cache**. With a resolver registered, the `@OpaPreAuthorize` manager resolves the **instance** behind a declared `resourceId` and sends its real attributes (tags, ownership, state) to OPA — today the gate decides on `(type, id)` with empty attributes, so tag-dependent rules wait for the post-load layer-3 check (`CategoryAuthorizer`). The loaded instance is cached for the request: the handler reuses it (**no double SELECT**) and downstream consumers (Phase-6 enrichment) read attributes without re-loading. **Fail-closed**: resolution failure → deny, never an attribute-less fallback (which could skip attribute-keyed deny rules). Additive + opt-in (no resolver bean → today's behavior); zero Rego. | 🔜 **Design settled (grill-me 2026-06-12) — ready for /decompose.** ADR [[0013-attribute-rich-pre-authorization\|0013]] + [[RESOURCE-RESOLUTION]] (+ its 00-DESIGN, incl. the behavior matrix). Pinned: split SPI (app resolves the instance; starter binds `AncestorChainSupplier` to the 5.5 `AncestorResolver`), gate = the full per-instance decision (tags + ancestors, **role on the governing root** — mirrors `HierarchicalAuthorizer`), split fail-closed semantics (instance failure → deny; ancestor failure → direct-only), write-through request cache, **version binding** (reads return the snapshot; mutations guard the one JPA `@Version` → `409 STATE_CONFLICT`), kill-switch `opa.abac.resource-resolution.enabled`. Design discovery: under the HTTP role source, id'd member decisions currently fall to the **realm-role fallback (tag-blind)** — the governing-root lookup closes that hole (flip/narrowing cells pinned). Catalog adopts (one resolver bean, `CategoryAuthorizer` deleted, story C4); user-mgmt deliberately not (live opt-in proof). **Prerequisite for Phase 6**; **first of the three pending slices** (5.97 → 6.5 → 6). |
| **6.5** | **Coarse permission categories + delegation** | Replace flat `read`/`write` with four coarse **categories** — `READ` / `WRITE` / `TAG` / `GRANT` — that **expand** to fine actions, refined by **deny-overrides** (Azure `Actions`/`NotActions`). A **five-tier `role_level` ceiling** (reader → member → senior → administrator → owner) bounds what a role may contain; two assignment gates (`role_level` strict `<` cross-tier + the subset rule at the senior tier) make delegation safe. Owner-only role authoring; `GRANT` capped at admin. | 🔜 **Planned — model pinned, not yet slice-designed.** ADR [[0007-coarse-grained-permission-categories\|0007]] (via cross-platform research AWS/Azure/GCP/GitHub/Heroku/K8s + a structured design interrogation); stories in [[USER-STORIES]] (Epic G); **no 00-DESIGN yet**. **Pulled ahead of Phase 6** (order **5.97 → 6.5 → 6**, settled 2026-06-12): this slice replaces the fine-action vocabulary (`read`/`write` → `view`/`list`/`create`/`update`/`delete`/`define-tags`/`assign-tags`/`assign-roles`) that Phase 6's action registry + `_actions` keys enumerate — 6.5-first avoids reworking enrichment, and its "which roles may I assign?" affordance lands in Phase 6's batch shape. Remaining phase-① forks for its grill-me: the category-expansion ↔ partial-eval fold on the `filter` entrypoint (the `data` table must fold at PE time, keeping the residual clean); role-def schema/back-compat (flat tokens keep deciding); seeded system-role migration; the repo-wide `@OpaPreAuthorize` action-string sweep; where the senior tier's constrained assign-members power is enforced. Additive: reuses the shipped `role_level` + `{type:[verbs]}` shape; category→action expansion table lives in OPA `data`. |
| **6** | **Action enrichment (affordance metadata)** | After a handler returns a resource/page, a response decorator attaches an `_actions` map — *which actions the caller may perform on it* — so a UI renders exactly the right buttons. Powered by Phase-5 **batch eval** (one OPA round-trip → N action verdicts), an **action registry** per resource type (also a validation allowlist), and an **`x-implements` marker** (`Enrichable`) stamped onto the generated DTOs via the OpenAPI generator. Affordance, **not** enforcement. | 🔜 **Planned (direction set, not yet decomposed)** — [[ACTION-ENRICHMENT]]. First real consumer of the Phase-5 `allowAll` primitive (so it sequences after Phase 5.9 + 5.95 — the `_actions` envelope lands on an already-paginated, RFC-7807-clean surface). **Also consumes the 5.97 resolver/cache** ([[RESOURCE-RESOLUTION]]): each `_actions` verdict is evaluated against **fully resolved resource attributes**, so affordance mirrors enforcement — incl. tag grants/denies and hierarchy. **And after 6.5**: the action registry + `_actions` keys enumerate the fine-action vocabulary 6.5 defines (order **5.97 → 6.5 → 6**, settled 2026-06-12). Open question: the `_actions` envelope ↔ OpenAPI codegen fit (the `x-implements` marker, modeled on the source platform's `AutoCloseable` DTO precedent). ADR pending, to be written with its decomposition. |
| **5.5** | **Hierarchical (N-level) resource authorization** | Generalize the one-step parent hop to a **full ancestor-chain walk**: a grant on a Catalog governs a Category/Product nested under it, N levels deep. **Opt-in per relation, deny-overridable, fail-closed** (a failed/cyclic/too-deep walk keeps only the direct grant, never widens). App-side `AncestorResolver` SPI (`ltree` materialized-path default + recursive-CTE for re-parent-heavy trees); chain supplied as `input.resource.ancestors`; role resolved once at the governing root; the Phase-5 list residual stays **tag-only** while an app-built `subtreeSpec` (`path <@` / `id IN`) widens the rows. New opt-in `AbstractHierarchicalEntity` (ltree `path` + atomic `reparent()`). | ✅ **DONE (5.5-A + 5.5-B).** ADR [[adr/0008-hierarchical-resource-authorization\|0008]] + ADR [[adr/0010-hierarchy-aware-list-filter\|0010]]; stories in [[USER-STORIES]] (Epic H). **5.5-A (single-resource)** delivered: walk + SPI + both resolvers + opt-in `AbstractHierarchicalEntity` (ltree + atomic + cross-table re-parent) + `HierarchicalAuthorizer` + Rego `inherited_grant`/deny-overrides + catalog adoption + e2e matrix incl. the **re-parent flip** ([[HIERARCHICAL-AUTHORIZATION]]). **5.5-B (hierarchy-aware list filter)** delivered: the additive `AncestorResolver.subtreeOf` (ltree `path <@` pushdown / CTE bounded `id IN`, fail-closed) + `SubtreeSpecResolver` (root-only inheritable gate) + the 4-arg `AbacQueryService.findAuthorized` composing `scope.and(tagResidual.or(subtreeSpec)).and(notDenied)` + the hierarchy-aware allowlist batch + a coarse `allow` list-gate clause; proven by `HierarchyListFilterIT` (real Postgres) + `run-hierarchy-list-matrix.sh` ([[HIERARCHY-LIST-FILTER]], [[PARTIAL-EVALUATION-FILTERING]]). Per-node independent grants deferred to Phase 8 (ReBAC). |
| **7** | Publish & polish | Maven Central publish for the starter; docs/guides complete; example runs from a clean clone. | The artifact must stand on its own. *(Was Phase 6; renumbered when action enrichment landed as Phase 6.)* |
| **8** | **ReBAC-in-Rego (team grants, in-policy)** | Push the team/membership/grant graph into OPA `data` and express the "subject member-of team **and** team has-role-on resource" join *in Rego* (Zanzibar-style userset), as an alternative to the Phase-4 app-resolved path. Demonstrates RBAC vs ABAC vs ReBAC expression in one OPA policy. | New item from the team-abstraction analysis. The strongest "stands out vs naive OPA" piece; deferred so the app-resolved path ships first. *(Was Phase 7.)* |

## Guiding principle

Prefer decisions that are **explainable and teachable**, not just functional — this repo is meant
to be read as a worked example of clean, Spring-native ABAC with OPA, so clarity of design and
documentation is a first-class goal alongside correctness.

## Open questions

- How far does the catalog app need to go on the "CDP" framing? Likely just enough hierarchy +
  attributes to make authorization interesting; not a real CDP.
- Does user-management-service expose its own gateway route, or is it internal-only (called by
  the catalog service / used as an attribute source)? — resolved in [[USER-MANAGEMENT-SERVICE]].
- Publish cadence: publish the starter early (0.x, expect churn) or hold until the API settles?

## Related

- Feature plan: [[USER-MANAGEMENT-SERVICE]]
- Shipped slice: [[DATA-FILTERING]] — Phase 5 partial-eval + batch data filtering (✅ merged, PR #11)
- Phase 5.5 direction: ADR [[adr/0008-hierarchical-resource-authorization|0008]] — N-level ancestor-chain authorization (Epic H in [[USER-STORIES]])
- Phase 5.95 shipped: ADR [[adr/0012-pagination-envelope|0012]] + [[PAGINATION-ENVELOPE]] — the exact-count pagination envelope composed with the partial-eval filter (Epic D5 in [[USER-STORIES]]); next: 5.97 → 6.5 → Phase 6 action enrichment lands on this envelope
- Phase 5.97 design: ADR [[0013-attribute-rich-pre-authorization|0013]] + [[RESOURCE-RESOLUTION]] — attribute-rich pre-authorization (`AbacResourceResolver` SPI + request cache + version binding); prerequisite for Phase 6; **runs first**, ready for /decompose
- Phase 6.5 direction: ADR [[0007-coarse-grained-permission-categories|0007]] — coarse permission categories + delegation; **sequenced before Phase 6** (defines the fine-action vocabulary enrichment enumerates)
- Phase 6 direction: [[ACTION-ENRICHMENT]] — affordance metadata via batch eval + an `x-implements` marker, evaluated on 5.97-resolved attributes over the 6.5 vocabulary
- Product lens: [[USER-STORIES]] — the catalog service from the user's perspective, per phase
- Decisions: [[adr/README|ADRs]] — 0005 (partial-eval→Specification), 0006 (three-layer enforcement), 0007 (permission categories)
- Root project intent & IP boundary: `../../../CLAUDE.md`
- Incremental plan source: `CLAUDE.md` → "Incremental plan"
