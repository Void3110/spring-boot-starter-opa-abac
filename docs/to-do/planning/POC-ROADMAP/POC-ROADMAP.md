---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/spring
---

# Proof-of-Concept Roadmap

> **Status (2026-07-13): 🎉 1.0.0 SHIPPED — published to Maven Central.** Every phase is done. All
> functional slices (Phases 0–6.7 + B2/B3/B4 + the 5.x API/hierarchy work + the **Spring Boot 4 port**,
> PR #70), the full **7.4** pre-publish gauntlet (delta security review 0-Critical, full-history secret
> scan clean, dependency CVE sweep clean, zero-config fail-safety, load-test re-baseline, and the
> browser-driven **UI QA** — A–I all PASS, one cosmetic SPA defect found + fixed, PRs #78/#79), **and**
> the **Maven Central publishing setup** ([[MAVEN-CENTRAL-PUBLISHING]] /
> [[0027-maven-central-release-engineering|ADR 0027]], T1–T6, PR #84) are shipped. **7.5 — publish — is
> DONE:** `dev.dmitriikonovalov:opa-abac-*:1.0.0` (+ the `opa-abac-bom` platform) is live on Central,
> tagged `v1.0.0`; `main` is now `1.1.0-SNAPSHOT` (PR #85). This note is the historical record of how the
> features fit together and the order we tackled them; each has its own folder under `to-do/implemented/`.
>
> **Since 1.0.0:** **Phase 9 — agent tool-call authorization — shipped 2026-07-31**
> ([[AGENT-TOOL-AUTHZ]] / [[0028-agent-tool-call-authorization|ADR 0028]]), a new `example-mcp-server`
> gating an MCP tool surface with **principal ceiling ∩ agent capability computed in Rego**, over the
> catalog's unchanged policies and with **zero library-module change**. Phase 8 (ReBAC-in-Rego) is the
> one functional row still open.

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

**Then Phase 5.97 — attribute-rich pre-authorization (resource resolution). ✅ SHIPPED (2026-06-12).**
An opt-in **`AbacResourceResolver` SPI** + a **request-scoped resource cache**: the `@OpaPreAuthorize`
gate resolves the *instance* behind a declared `resourceId` and decides on its **real attributes** and
ancestors (role on the governing root), then caches the authorized snapshot so the handler — and the
Phase-6 enrichment advice — reuse it without a second SELECT, with mutations version-guarded (`409
STATE_CONFLICT` on drift). Fail-closed split semantics, additive, kill-switched. The catalog adopted
(one resolver bean, `CategoryAuthorizer` deleted, story C4 ✅ — the realm-fallback hole on id'd member
decisions closed); user-mgmt deliberately did not (the live opt-in proof). See
[[ATTRIBUTE-RICH-PRE-AUTHORIZATION]] + [[RESOURCE-RESOLUTION]]. **6.5 shipped 2026-06-12** ([[PERMISSION-MODEL]]).

**Latest (2026-07-12) — the pre-publish gauntlet, all functional work done.** Since the route box
below was written, everything through Phase 7's validation gauntlet has shipped to `main`: **B4**
tenant isolation + self-service (PR #49), the **user-directory** pair (query filters PR #57 + the
Keycloak `UserDirectory` port PR #58), **7.1** UI feedback + ADR 0022 root-read exemption (PR #65),
**7.2** load testing + `PERFORMANCE.md` baseline (PR #62, ADR 0021), **7.3** resolve-coalescing (PR
#67, ADRs 0023/0024 — the memo staleness contract), **taggable products** (PR #68, ADR 0025), the
pre-publish **API polish** (PR #64 — `AbacResource` rename + the `Taggable.TAGS_ATTRIBUTE` contract),
and the **Spring Boot 4 port** (PR #70, ADR 0026 — single-line Boot 4.0.7 / Java 25 / Jackson 3 / R4j
2.4.0; the last 3.4 commit tagged `pre-sb4-port`). **7.4 is essentially done (2026-07-12):** the
**delta security review** (Path 2B 8-angle fan-out; 0 Critical — the one HIGH `catalog.rego`
`abac_deny` deny-override gap fixed PR #71, the INFO commons-io CVE pinned PR #73;
`docs/code-review/SECURITY-REVIEW-2026-07-12.md`), the **full-history secret scan** (CLEAN — 303
commits, only demo fixtures), the **dependency CVE sweep** (CLEAN — 0 on the published classpath), the
**zero-config fail-safety audit** (fail-closed by construction; the one footgun — `@OpaPreAuthorize`
silently off without `@EnableMethodSecurity` — now caught by a startup WARN + a README quickstart), and
the **load-test re-baseline** (gate +0.79 ms/+15 % p50, ceiling 25 rps, telemetry-off; the earlier RC=99
aborts root-caused to OTEL/Badger back-pressure, not the app).

**Shipped (2026-07-13) — 1.0.0 published to Maven Central.** The pre-publish **UI QA** (agent-driven
browser walk of the demo SPA, A–I all PASS; DEF-1 cosmetic SPA race found + fixed, PRs #78/#79), then the
**Maven Central publishing setup** (T1–T6, PR #84 — vanniktech on the 5-library allow-list + an
`opa-abac-bom` `java-platform`, signed dry-run proving 6 coordinates, examples-none; `/deep-review` clean)
and the **release** itself: namespace `dev.dmitriikonovalov` DNS-TXT-verified, key on the keyservers,
`publishAndReleaseToMavenCentral` → all six coordinates live, tagged `v1.0.0`; `main` bumped to
`1.1.0-SNAPSHOT` + `RELEASING.md` corrected with the gpg-cmd/subpkt-33 signing lesson (PR #85). **1.0 is
done — nothing remains before it.** The route box and phase table below are the historical record; this
block is the current picture.

> **Route to publish (settled 2026-06-13; B3 inserted 2026-06-15; Phase 7 grew into a gauntlet).**
> The correctness/availability slices before Phase 7 were **B2 fix-slice → Phase 6.7 → Phase 6 → B3
> resilience** (reordering the old `6 → 6.7`) — **all ✅ shipped by 2026-06-18.** Phase 7 then turned
> out to be not a single "publish" step but a **pre-publish gauntlet** (reshaped 2026-06-26, reordered
> 2026-07-06): the demo SPA (7.0) → the baseline security review (7.0.5) → tenant isolation + self-service
> (B4) → the user-directory pair → UI-feedback (7.1) → load testing (7.2) → resolve-coalescing (7.3) →
> taggable products → the **Spring Boot 4 port** — **all ✅ shipped, the port merged 2026-07-12 (PR #70).**
> **Remaining: 7.4 (the pre-publish delta security review + publish checklist) → 7.5 (publish 1.0, held
> until 1.0).** See the *Current state* log below for the full chronology.
> 1. **B2 fix-slice** *(standalone, first — ✅ shipped 2026-06-15)* — the supplier-outage
>    error-distinct posture. A resolve outage previously rode the realm-role fallback and, post-6.5,
>    **erased** the resolved role's `denied_actions`/`required_tags` narrowing (the one tracked
>    widening-on-failure, [[PERMISSION-MODEL]] / the 6.5 review). Fixed on its own small branch —
>    *outage → throw `RoleResolutionException` → deny* distinct from *no-role → `empty` → fallback* at
>    the SPI contract, swept across all five `lookup()` consumers — kept standalone (its own design +
>    review) so the security fix isn't entangled with 6.7's larger taxonomy work. Pinned by ADR
>    [[0014-supplier-outage-error-distinct|0014]] + [[B2-SUPPLIER-OUTAGE]]; **shipped** on branch
>    `feature/void3110/supplier-outage` (T1–T5, zero Rego, `opa test` 157/157).
> 2. **Phase 6.7 — control-plane vocabulary** *(the last correctness slice before publish — ✅ shipped
>    2026-06-15)* — closed the `define-tags` **enforcement** deferral (Epic G/Story G4) and categorized
>    the `team:*` control plane (new category `CONTROL`, owner-only-by-code fence, custom roles stay
>    management-incapable). Branch `feature/void3110/control-plane-vocabulary` (T1–T4); `opa test`
>    infra 157→177 / service-team 14→30. ADR [[0015-control-plane-vocabulary-categorization|0015]] +
>    [[CONTROL-PLANE-VOCABULARY]].
> 3. **Phase 6 — action enrichment** *(✅ shipped 2026-06-17)* — the read-side `_actions` affordance map
>    a response advice attaches to returned resources/pages, so a UI renders exactly the buttons the user
>    can use. Affordance, **not** enforcement. Consumes the Phase-5 `allowAll`/`bulk` batch primitive (the
>    primitive **extended** to every enriched type — the only Rego touch, additive), the 5.97 resolver/cache
>    (each verdict on resolved attributes, governing-root role), and the 6.5/6.7 vocabulary. Opt-in via the
>    `Enrichable` marker + OpenAPI `x-implements`; adopted by catalog (all three types) **and**
>    user-management (the team OPA-decided subset). Branch `feature/void3110/action-enrichment` (T1–T7).
>    Pinned by ADR [[0016-action-enrichment-affordance-metadata|0016]] + [[ACTION-ENRICHMENT]]; guide
>    [[ACTION-ENRICHMENT]] (the mechanism). The codegen-fit open question resolved (the `_actions` property
>    → `getActions`/`setActions`, no generator config).
> 4. **Slice B3 — cross-service HTTP resilience** *(availability hardening — **✅ SHIPPED 2026-06-18**,
>    the last slice before publish)* — the deliberate availability pass that softens the **outage →
>    hard-deny wall** B2 introduces, across *all* cross-service HTTP edges (`HttpRoleDefinitionSupplier`,
>    `TagDefinitionClient`, `OpaClient`): a uniform retry/backoff/circuit-break posture that classifies
>    transient (5xx/timeout) vs permanent (4xx) **without** weakening B2's outage→deny contract. T1–T4 on
>    `feature/void3110/http-resilience`; the live e2e headline passed (E1 → 200, E2 → 403). ADR
>    [[0017-cross-service-http-resilience|0017]] + [[B3-HTTP-RESILIENCE]] + guide [[HTTP-RESILIENCE]]
>    (decorator-over-`OpaClient` via a BPP, fail-closed-in-every-state, side-effect-free retry, asymmetric
>    per-edge budgets, 3 per-endpoint breakers, optional/conditional R4j, the backend-agnostic `CallGuard`
>    seam, per-edge kill-switch). `/deep-review` next, then the folder moves to `implemented/`.
> 5. **Phase 7 — publish prep.** The small tracked items (OPA authn/trust docs · CI-runs-e2e · runner
>    OPA-restart hygiene) fold into Phase 7 polish unless an earlier slice touches them first.

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

**Then Phase 6 — action enrichment** *(now sequenced after 6.7 — see the route box above)*. The first real
consumer of Phase 5's batch primitive: a response decorator that attaches a `{action: allowed}` affordance
map to returned resources so a UI shows only the buttons the user can click. Its `_actions` verdicts are
computed against **fully resolved resource attributes** (the 5.97 resolver/cache), so affordance mirrors
enforcement — including tag grants/denies and hierarchy — and its action registry enumerates the **6.5
fine-action vocabulary** (and, after 6.7, the categorized control-plane verbs). Direction is set
([[ACTION-ENRICHMENT]]); decomposition follows the B2 fix-slice + 6.7. After that, **Phase 7** (publish &
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
| **5.97** | **Attribute-rich pre-authorization (resource resolution)** | An opt-in **`AbacResourceResolver` SPI** (`(resourceType, resourceId) → AbacResource`, app-implemented — the same SPI shape as `RoleDefinitionSupplier`/`AncestorResolver`) + a **request-scoped resource cache**. With a resolver registered, the `@OpaPreAuthorize` manager resolves the **instance** behind a declared `resourceId` and sends its real attributes (tags, ownership, state) to OPA — today the gate decides on `(type, id)` with empty attributes, so tag-dependent rules wait for the post-load layer-3 check (`CategoryAuthorizer`). The loaded instance is cached for the request: the handler reuses it (**no double SELECT**) and downstream consumers (Phase-6 enrichment) read attributes without re-loading. **Fail-closed**: resolution failure → deny, never an attribute-less fallback (which could skip attribute-keyed deny rules). Additive + opt-in (no resolver bean → today's behavior); zero Rego. | ✅ **SHIPPED 2026-06-12.** ADR [[0013-attribute-rich-pre-authorization\|0013]] + [[RESOURCE-RESOLUTION]] + the [[ATTRIBUTE-RICH-PRE-AUTHORIZATION]] guide (+ 00-DESIGN, incl. the behavior matrix). Delivered: split SPI (app resolves the instance; starter binds `AncestorChainSupplier` to the 5.5 `AncestorResolver`), gate = the full per-instance decision (tags + ancestors, **role on the governing root** — mirrors `HierarchicalAuthorizer`), split fail-closed semantics (instance failure → deny; ancestor failure → direct-only), write-through request cache, **version binding** (reads return the snapshot; mutations guard the one JPA `@Version` → `409 STATE_CONFLICT`), kill-switch `opa.abac.resource-resolution.enabled`. Design discovery resolved: under the HTTP role source, id'd member decisions used to fall to the **realm-role fallback (tag-blind)** — the governing-root lookup closes that hole (story C4 ✅; flip/narrowing cells proven). Catalog adopted (one resolver bean, `CategoryAuthorizer` **deleted**); user-mgmt deliberately did not (the live opt-in proof). The **Phase-6 prerequisite** is in place; first of the three pending slices delivered (5.97 → 6.5 → 6 — **all now shipped**). |
| **6.5** | **Coarse permission categories + delegation** | Replace flat `read`/`write` with four coarse **categories** — `READ` / `WRITE` / `TAG` / `GRANT` — that **expand** to fine actions, refined by **deny-overrides** (Azure `Actions`/`NotActions`). A **five-tier `role_level` ceiling** (reader → member → senior → administrator → owner) bounds what a role may contain; two assignment gates (`role_level` strict `<` cross-tier + the subset rule at the senior tier) make delegation safe. Owner-only role authoring; `GRANT` capped at admin. | ✅ **Shipped 2026-06-12** (branch `feature/void3110/permission-categories`; guide [[PERMISSION-MODEL]]; the live proof `run-permission-categories-matrix.sh` + the whole suite green post-migration). ADR [[0007-coarse-grained-permission-categories\|0007]] (+ its Phase-6.5 implementation addendum) + [[PERMISSION-CATEGORIES]] (incl. 00-DESIGN: the ten settled forks, behavior matrix, proof obligations); stories [[USER-STORIES]] Epic G. **Pulled ahead of Phase 6** (order **5.97 → 6.5 → 6**, settled 2026-06-12): this slice defines the fine-action vocabulary (`view`/`list`/`create`/`update`/`delete`/`define-tags`/`assign-tags`/`assign-roles`) Phase 6's action registry + `_actions` keys enumerate. Headline pins: **clean cut, no back-compat** (the starter is unpublished — the additive-only doctrine is consciously waived; stale flat tokens expand to ∅ = deny, fail-closed); category tokens in the existing `permissions` map + a new `denied_actions` field; expansion table in OPA `data` (`permission_categories.json`) consumed by one shared `permissions.rego`; hybrid assignment gates (level compares in Java under the team-row lock; the senior subset verdict via the new `data.role.assignable` entrypoint); seeds migrate in one changelog (senior 25 inserted, `viewer`→`reader`); `assign-tags` = a conditional second decision on the tags delta; `define-tags` enforcement deferred to the control-plane slice (Phase 6.7); realm fallback maps through the same table. **Deep-reviewed + merged (PR #34, `6c31fde`); fixes: reparent version-bind, the target-tier gate, the token-vocabulary sweep — see [[PERMISSION-CATEGORIES-REVIEW]].** |
| **B2** | **Supplier-outage fix-slice** *(security; route step 1)* | Distinguish a resolve **outage** (→ **throw** `RoleResolutionException` → deny / no widening) from an authoritative **no-role** (`204` → `Optional.empty()` → realm fallback) at the `RoleDefinitionSupplier`/SPI contract, so a user-mgmt outage can no longer ride the catalog realm fallback to a **wider** grant than the resolved role — a hole **aggravated by 6.5** (an outage erases the resolved role's `denied_actions`/`required_tags` narrowing). The one tracked widening-on-failure path. | ✅ **Shipped 2026-06-15** (branch `feature/void3110/supplier-outage`, T1–T5; the live proof `SupplierOutageGateIT` — outage → 403, OPA never called, contrast empty → fallback still grants — + the whole suite green, `opa test` **157/157** unchanged). ADR [[0014-supplier-outage-error-distinct|0014]] + [[B2-SUPPLIER-OUTAGE]] (00-DESIGN, incl. the behavior matrix + the all-five-consumer sweep). Pinned + delivered: unchecked `RoleResolutionException` in `opa-abac-core`; the **strict HTTP invariant** (only `204`→fallback, only `200`+valid→resolved, **all else throws**); supplier-classifies / consumer-maps (no wrapper) across all 5 `lookup()` consumers (gate→403; `HierarchicalAuthorizer`→`false`; `SubtreeSpecResolver`→test-only no-widening; `CategoryListAuthorizer`→empty page); **no kill-switch** (the off-ramp would be the vuln); **zero Rego** (the fallback is *retained*); `TeamRoleDefinitionSupplier` minimal touch (DB fail → throw). Resilience explicitly **out of scope → new Slice B3** (before publish). Standalone branch + its own review (kept separate from 6.7). Source: [[PERMISSION-CATEGORIES-REVIEW]] (C1/C4), retro-audit 2026-06-12. |
| **6** | **Action enrichment (affordance metadata)** | After a handler returns a resource/page, a response advice attaches an `_actions` map — *which actions the caller may perform on it* — so a UI renders exactly the right buttons. Powered by Phase-5 **batch eval** (one OPA round-trip → N action verdicts), an **action registry** per resource type (also a validation allowlist), and an **`x-implements` marker** (`Enrichable`) stamped onto the generated DTOs via the OpenAPI generator. Affordance, **not** enforcement. | ✅ **DONE** (2026-06-17, branch `feature/void3110/action-enrichment`, T1–T7). [[ACTION-ENRICHMENT]] · guide [[ACTION-ENRICHMENT]] · ADR [[0016-action-enrichment-affordance-metadata\|0016]]. The first real consumer of the Phase-5 `allowAll` primitive (the `_actions` envelope on the already-paginated, RFC-7807-clean surface). Consumes the 5.97 resolver/cache ([[RESOURCE-RESOLUTION]]) — each verdict on **resolved attributes**, role on the governing root, so affordance mirrors enforcement (tags + hierarchy) — and the 6.5/6.7 fine-action + control-plane vocabulary. Opt-in via the `Enrichable` marker + `x-implements` (the codegen-fit open question **resolved**: `_actions` → `getActions`/`setActions`, no generator config); adopted by catalog (all three types) **and** user-management (the team OPA-decided subset; the Java-co-gated escalation verbs excluded — affordance honesty). The `bulk` batch primitive was **extended** to every enriched type (additive, decision-preserving — the only Rego touch; ADR 0016 §6 corrected the original "zero Rego" claim). Three pinned invariants: omit-never-fabricate, affordance-honesty, cache-as-snapshot. e2e green + every existing matrix unchanged. |
| **B3** | **Cross-service HTTP resilience** *(availability; before publish)* | The deliberate availability pass that softens the **outage → hard-deny wall** Slice B2 introduces. A **uniform** retry/backoff/circuit-break posture across *all* cross-service HTTP edges — `HttpRoleDefinitionSupplier`, `TagDefinitionClient`, and the `OpaClient` HTTP hop — that classifies **transient** (5xx / timeout / connection-refused → retry) vs **permanent** (4xx → fail fast) and bounds latency (esp. on request-handling/under-lock paths). **Must preserve B2's contract**: an exhausted-retry outage still throws `RoleResolutionException` → deny; resilience only makes outages *rarer*, never re-opens the fallback. | ✅ **SHIPPED 2026-06-18** (branch `feature/void3110/http-resilience`, T1–T4; the **live e2e headline passed** through the gateway — E1 transient-recovers → **200**, E2 sustained → **403** with no realm-fallback widening; full `./gradlew build` green; `opa test` **183/183** unchanged — zero Rego). ADR [[0017-cross-service-http-resilience\|0017]] + [[B3-HTTP-RESILIENCE]] + guide [[HTTP-RESILIENCE]]. Delivered: a backend-agnostic **`CallGuard` seam** + R4j impl + injectable clock (`opa-abac-spring-security`, virtual-time tests); a resilient **`OpaClient` decorator** auto-configured **`@ConditionalOnClass` R4j** via a `BeanPostProcessor` (retry-on-fail-closed-sentinel — `compile`→`error()` `fromError=true`, never `denyAll()`/`allowAll()`; fail-closed identical in every breaker/config state, contract-tested); **app-side resolve/tag wrappers** that retry the transient subset **before** B2's throw (204/200 terminal & 4xx immediate, proven by attempt counts — B2 preserved exactly); **asymmetric per-edge budgets** + **three per-endpoint breakers** + a **per-edge kill-switch** (`resilience.enabled=false` ⟺ byte-identical to pre-B3); a fault-injecting resolve stub + the two-pass matrix. Out of scope (deferred): the Boot-4 native backend + second artifact line; the **load-testing rig** → Phase 7. Java 21 / Boot 3.4; `opa-abac-core` untouched. Source: [[B2-SUPPLIER-OUTAGE]] (00-DESIGN §5) + ADR [[0014-supplier-outage-error-distinct|0014]] (§Consequences). |
| **B4** | **Multi-tenant isolation + self-service** *(security; before publish)* | Make **team membership the sole access path** to the catalog hierarchy, and add the **self-service** flow (create catalog + team + members) the isolation makes meaningful — with a **real cross-service ownership check** so team-create cannot squat another user's catalog. Closes the realm-role fallback leak (every authenticated user currently sees all 13 catalogs; `catalog-editor` can edit any) the Phase-7 demo work uncovered. | ✅ **SHIPPED 2026-06-30** (branch `feature/void3110/multi-tenant-isolation`, T1–T9 all done — awaiting the maintainer's PR/push). Proven live by the e2e **isolation matrix** (E1–E7, **20/20** — fresh user sees `[]`, scoped member access, multi-team union, no deep-link, squat→403) + the full existing Postman suite re-run **green** (resilience excluded — mutually-exclusive rig profile); `opa test` **197/197**; `./gradlew build` green. The e2e surfaced (and T9 fixed) three regressions T1's fallback-removal caused under the membership profile — type-level gates lost their role; fixed with a clean `@OpaPreAuthorize(roleResource…)` parent-role-resolution seam (see [[STATUS-09]]). ADR [[adr/0018-team-scoped-resource-isolation\|0018]] (isolation / membership-as-sole-access-path) + [[adr/0019-pluggable-cross-service-ownership\|0019]] (pluggable cross-service ownership). **Mechanism:** a role-def-only `filter` entrypoint in `catalog.rego` + a `GovernedScopeResolver` SPI (library) supplying the catalog list's **base `scope`** (`id IN (governed ids)`) composed through `AbacQueryService.findAuthorized`; the realm fallback **removed** from the single-decision path of all three policies (a narrow `catalog:create` fallback retained — creation is pre-membership); a pluggable `ResourceOwnershipResolver` + config-driven discovery client (TTL cache) calling a standard `/internal/{type}/{id}/created-by` contract, wired into `createTeam` to close target-squatting; `/api/v1/teams*` + `/api/v1/users*` routed through the gateway. **Fail-closed throughout**; `opa-abac-core` untouched. See [[MULTI-TENANT-ISOLATION]]; demoed by [[demo-spa-state]]. |
| **5.5** | **Hierarchical (N-level) resource authorization** | Generalize the one-step parent hop to a **full ancestor-chain walk**: a grant on a Catalog governs a Category/Product nested under it, N levels deep. **Opt-in per relation, deny-overridable, fail-closed** (a failed/cyclic/too-deep walk keeps only the direct grant, never widens). App-side `AncestorResolver` SPI (`ltree` materialized-path default + recursive-CTE for re-parent-heavy trees); chain supplied as `input.resource.ancestors`; role resolved once at the governing root; the Phase-5 list residual stays **tag-only** while an app-built `subtreeSpec` (`path <@` / `id IN`) widens the rows. New opt-in `AbstractHierarchicalEntity` (ltree `path` + atomic `reparent()`). | ✅ **DONE (5.5-A + 5.5-B).** ADR [[adr/0008-hierarchical-resource-authorization\|0008]] + ADR [[adr/0010-hierarchy-aware-list-filter\|0010]]; stories in [[USER-STORIES]] (Epic H). **5.5-A (single-resource)** delivered: walk + SPI + both resolvers + opt-in `AbstractHierarchicalEntity` (ltree + atomic + cross-table re-parent) + `HierarchicalAuthorizer` + Rego `inherited_grant`/deny-overrides + catalog adoption + e2e matrix incl. the **re-parent flip** ([[HIERARCHICAL-AUTHORIZATION]]). **5.5-B (hierarchy-aware list filter)** delivered: the additive `AncestorResolver.subtreeOf` (ltree `path <@` pushdown / CTE bounded `id IN`, fail-closed) + `SubtreeSpecResolver` (root-only inheritable gate) + the 4-arg `AbacQueryService.findAuthorized` composing `scope.and(tagResidual.or(subtreeSpec)).and(notDenied)` + the hierarchy-aware allowlist batch + a coarse `allow` list-gate clause; proven by `HierarchyListFilterIT` (real Postgres) + `run-hierarchy-list-matrix.sh` ([[HIERARCHY-LIST-FILTER]], [[PARTIAL-EVALUATION-FILTERING]]). Per-node independent grants deferred to Phase 8 (ReBAC). |
| **6.7** | **Control-plane vocabulary categorization** | Extend the category model to the control plane: the `team:*` verbs (`manage`, `define-roles`, `define-tags`, `transfer-ownership`), the resolve-side team-verb injection ladder, and `TeamRoleCapabilities` — re-thought against the 6.5 taxonomy, incl. whether custom roles may carry team verbs and where the tag-dictionary endpoints re-gate (the `define-tags` enforcement 6.5 defers here). | ✅ **Shipped 2026-06-15** (branch `feature/void3110/control-plane-vocabulary`, T1–T4). **Delivered:** one shared vocabulary — **new category `CONTROL` → `[add-member, change-role, remove-member]`** (the coarse `manage` split, deny-refinable) + `list-members` added to `READ`; `team.rego` is now category-driven via the shared `permissions.effective_actions` (symmetric with `catalog.rego`) + an **owner-only-by-code fence** for `define-roles`/`transfer-ownership` (keyed on the reserved `owner` code); `TeamRoleCapabilities` recast to category tokens; custom roles stay **management-incapable** (`validateContract` 422s the dead-data case); **`TAG` left intact** (`define-tags` re-gated mechanically — same outcomes; `senior` correctly cannot); the **two-axis** principle held (verb category vs the **untouched** `MembershipService` escalation gates — re-proven through a renamed verb by `ControlPlaneVocabularyIT`). Proof: `opa test` **infra 157→177 / service-team 14→30**; the headline IT (I1–I6, real Postgres) + the U1–U5 unit suite + the e2e control-plane/member-can-list cells. **Two intended externally-visible changes** (both proven): any team member can now `list-members`; a custom role carrying control tokens under `"team"` answers 422. No DB migration; no kill-switch; B2's tri-state supplier contract untouched. ADR [[0015-control-plane-vocabulary-categorization\|0015]] + [[CONTROL-PLANE-VOCABULARY]]. Closes the `define-tags` **enforcement** deferral (Epic G/Story G4). |
| **7** | **Pre-publish gauntlet → publish** | Not one step but a **validation gauntlet** before Maven Central: **7.0** demo SPA ✅ · **7.0.5** baseline security review ✅ · **B4** tenant isolation + self-service ✅ · **user-directory** (query filters + the Keycloak port) ✅ · **7.1** UI feedback ✅ · **7.2** load testing ✅ · **7.3** resolve-coalescing ✅ · **taggable products** ✅ · **SB4 port** ✅ (PR #70, 2026-07-12) · **7.4** pre-publish **delta** security review + publish checklist ✅ **essentially done** (2026-07-12 — security review 0 Critical [HIGH fixed #71, INFO CVE pinned #73], secret scan CLEAN, CVE sweep CLEAN, zero-config fail-closed [+ the `@EnableMethodSecurity` startup WARN], perf re-baselined; **only the Maven Central signing/supply-chain setup remains**) · **7.5** publish 1.0 ⏸️ **held until 1.0**. Docs/guides complete; example runs from a clean clone; the artifact must stand on its own. *(Was Phase 6; renumbered when action enrichment landed as Phase 6.)* |
| **8** | **ReBAC-in-Rego (team grants, in-policy)** | Push the team/membership/grant graph into OPA `data` and express the "subject member-of team **and** team has-role-on resource" join *in Rego* (Zanzibar-style userset), as an alternative to the Phase-4 app-resolved path. Demonstrates RBAC vs ABAC vs ReBAC expression in one OPA policy. | New item from the team-abstraction analysis. The strongest "stands out vs naive OPA" piece; deferred so the app-resolved path ships first. *(Was Phase 7.)* |
| **9** | **Agent tool-call authorization (AI-agent PEP)** | Apply the starter to the emerging **agent-authorization** surface: an example (or optional module) where an **AI agent's tool calls** (MCP or an in-app tool registry) pass through a Spring-Boot-hosted **PEP** with OPA as the **PDP** — the same ABAC building blocks (`AbacContext`, role definitions, tags, deny-overrides, batch eval) reused to answer *"may this agent invoke this tool on this resource, on behalf of this user?"*. Headline: **dual-identity propagation** — `principal` (the human on whose behalf) distinct from `actor` (the agent), plus an explicit delegation chain, carried in OPA `input` so policies can bound both; least-privilege capability scoping per agent; a decision audit trail. Fail-closed like everything else here. | ✅ **SHIPPED 2026-07-31** (`feature/void3110/agent-tool-authz`, T1–T6, pinned by [[0028-agent-tool-call-authorization|ADR 0028]]): a new `example-mcp-server` (Spring AI 2.0.0, streamable HTTP) whose four read-only `@McpTool` catalog proxies are gated by a new `agent_tools.rego` computing **principal ceiling ∩ agent capability in Rego**, over the **untouched** per-type catalog policies as the target-gate. **Zero library, existing-service or sibling-policy changes** — the starter is consumed exactly as an adopter would. Delivered: the dual-identity `DelegationChainExtractor` (RFC 8693 `act` semantics from a stock-Keycloak custom claim; absent = human, malformed = deny), the tri-state `AgentCapabilitySupplier` with a turn-scoped memo, the unbypassable call-handler PEP (not an AOP aspect), and the `tools/list` **roster filter** — one batch `allowAll` round-trip, a hint that never grants, its SDK adapter pinned reflectively and failing startup by design when an upgrade moves the pins. Proven live through the gateway by `run-agent-tool-matrix.sh` (E1–E11, 49 requests / 73 assertions): the human sees four tools and REST parity by id; the same principal behind an agent capped below `get_product`'s risk tier sees exactly two **by name** and is denied `get_product` at `tool-gate`; a foreign catalog denies at `target-gate` (the layers are distinguishable); the **headline** — a low-privilege principal collapses the roster to `[]`, while the deliberately over-wide `agent-overreach` capability still gets exactly the human's four (**capability narrows, never grants**); plus the PDP-kill, gate-OFF and revocation drills. Two defects only the rig could show, both fixed: the type-level ceiling under-approximated against membership's governing root, and Spring AI's annotation handler swallowed the target-gate's structured failure. Full e2e fleet green (14/14 runners on their documented rig flavours). The optional `opa-abac-agent` module remains the slice's **exit criterion**, deliberately not shipped. *Original framing, kept for the record:* **New item (2026-07-19), from the agentic-OS research pass.** PDP/PEP-for-agent-tool-calls is the settling industry pattern (policy-engine-governed agent capabilities; the identity side converging via OAuth **Cross-App Access** in the MCP auth spec and workload-identity schemes) — and no Spring-native worked example exists, the same gap this repo filled for classic ABAC. Needs its own phase-① research/design first: the identity-chain token model (claims vs token exchange), example-vs-module split, and which starter seams (`AbacContext` extraction, the action registry, `@OpaPreAuthorize`) generalize to tool invocations. Independent of Phase 8; either can go first. |

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
- Phase 5.97 **shipped** (2026-06-12): ADR [[0013-attribute-rich-pre-authorization|0013]] + [[RESOURCE-RESOLUTION]] + the [[ATTRIBUTE-RICH-PRE-AUTHORIZATION]] guide — attribute-rich pre-authorization (`AbacResourceResolver` SPI + request cache + version binding); the Phase-6 prerequisite is in place; **next: 6.5**
- Phase 6.5 **design settled** (grill-me 2026-06-12): ADR [[0007-coarse-grained-permission-categories|0007]] + its implementation addendum + [[PERMISSION-CATEGORIES]] (00-DESIGN, ten forks pinned, ready for `/decompose`); the control-plane vocabulary scoped out to the new **Phase 6.7** row
- Phase 6 direction: [[ACTION-ENRICHMENT]] — affordance metadata via batch eval + an `x-implements` marker, evaluated on 5.97-resolved attributes over the 6.5 vocabulary
- Phase 7 (pre-publish) **user-directory work, split into two slices** (grill-me 2026-07-06): **Slice 1** [[DIRECTORY-QUERY-FILTERS]] — **shipped** (2026-07-07, the autonomous run): the `?subject`/`?targetType`+`?targetId` exact-match filters (one-item page, both-or-400) killed the SPA's `listAll*` single-resource page-walks, plus the 204/`Accept` `produces` fix and the bootstrap displayName upsert; **Slice 2** [[USER-DIRECTORY-PORT]] — **shipped** (2026-07-07, the autonomous run): the `UserDirectory` search SPI in the library + the [[USER-DIRECTORY|guide]], a `KeycloakUserDirectory` in the new optional `opa-abac-keycloak-directory` module, starter auto-config (NoOp fallback), the bearer-only `search` endpoint under `/api/v1/users` (bounded plain list), the least-privilege `view-users` realm client (`ENABLE_DIRECTORY=1` rig flag), e2e cells in the team matrix + the SPA picker searching the directory with provision-on-select (pinned by [[0020-user-directory-port|ADR 0020]])
- Phase 7.2 (pre-publish) **load testing** [[LOAD-TESTING]] — ✅ **SHIPPED** (2026-07-08, `feature/void3110/load-testing`, pinned by [[0021-load-testing-methodology|ADR 0021]]): the committed k6 harness (`scripts/load/`) + the official baseline in the root `PERFORMANCE.md` — headline gate delta **+2.7 ms p50** (guarded vs unguarded through the identical gateway), the list ceiling (knee at the first 10 req/s stage — per-row resolve amplification), the attributed amplification tables (resolve 2/22/102 per request vs the pinned 1 — THE 7.3 finding; eval side bounded at 2 bulks/page), and the three fault timelines (typed fast denials; ~3 s gateway-plugin denies under OPA pause; breaker-paced recovery)
- Phase 7.3 (pre-publish) **resolve coalescing** [[RESOLVE-COALESCING]] — ✅ **SHIPPED** (2026-07-10, `feature/void3110/resolve-coalescing`, pinned by [[0023-request-scoped-resolution-memoization|ADR 0023]] + [[0024-batch-role-resolution|ADR 0024]]): the 7.2 findings consumed and **re-measured** — the request-scoped **memo** over the role + ancestor SPIs (all three tri-state outcomes, "one request, one answer per target", single default-on flag `opa.abac.resolve-memo.enabled`) + the **batch `lookupAll`** (two-state entries, whole-batch outage, strict completeness, one guarded internal GET) collapsed the measured per-request resolves **2/22/102/51 → 1/1/1/2** (multi-root re-pinned 2: the query-time coarse role + one response-time batch); gate p99 tail 36.8→11.0 ms; enrichment p50 266→174 ms; OPA now **survives the 50 req/s stage** (the knee stays at 10, now OPA-bulk-eval-bound — the next frontier); gateway outage-deny wall **3005→1005 ms** (`timeout:1000` int-ms — 500 measured too tight for a loaded OPA); full newman fleet green (+ the new catalogs-list `_actions` cut cells). Bonus finds fixed on the way: the method-security advisor's eager manager injection had been silently bypassing EVERY bean-level decorator on the gate path (incl. B3's wrap), and `ResilientOpaClient.allowAll` retried mixed verdict blocks (the sentinel is all-false). The memo makes the **7.4 stale-authorization scrutiny mandatory** (staleness contract: one request, one answer)
- Pre-publish **Spring Boot 4 port** [[SPRING-BOOT-4-PORT]] — ✅ **SHIPPED 2026-07-12** (grill-me 2026-07-11, pinned by [[0026-spring-boot-4-single-line-port|ADR 0026]]; branch `feature/void3110/spring-boot-4-port`, T1→T7 fully autonomous, zero pauses; **merged PR #70**, squash `0ddeea3`; the last 3.4 commit is tagged `pre-sb4-port` = `924990c`): 1.0 now targets **Boot 4.0.7 on Java 25 / Gradle 9.6.1** single-line (the dual 3.5/4.0 door closed) with **Jackson 3.1.4 / Framework 7 / Security 7 / Hibernate 7.2 / R4j 2.4.0**. Seven tickets (3.5.x baseline + deprecation map → covariant `authorize()` + R4j 2.4.0 pre-bump → THE BUMP → Jackson 3 with the three wire-parity pins → Data JPA 4 idiom → e2e fleet + the single `PERFORMANCE.md` re-baseline with double attribution); acceptance frame = **byte-identical behavior** held (zero rego, zero collection edits; 830 tests + `opa test` 228/228 + fleet 14/14; W1–W3 pins with zero Jackson-3 default-flip restores). Notables: Hibernate 7.2 has NO Jackson-3 FormatMapper (jackson-2 stays `runtimeOnly` on spring-data as its jsonb engine); no `JAVA_HOME` override needed on the ported tree. Deep-review Path 2B: **approved, zero findings** ([[SPRING-BOOT-4-PORT-REVIEW]]). Perf re-baseline is **partial by ADR-0021 validity** — call-count/steady/ladder/fault-timelines recorded; gate delta + ceiling need a quiet-host re-run (commands in `PERFORMANCE.md`). **NEXT: the 7.4 delta security review** (its CVE audit lands on this post-port dependency line) → publish 1.0
- Phase 9 ✅ **SHIPPED** (2026-07-31; decomposed 2026-07-28): **agent tool-call authorization** — a dual-identity (principal ≠ actor) PDP/PEP for MCP/agent tool invocations built on the starter. Design settled and pinned by [[0028-agent-tool-call-authorization|ADR 0028]] (two-layer decision model; enforcement by composition — nothing asserted downstream; agent capability narrows only). Shipped package: [[AGENT-TOOL-AUTHZ]] (6 tickets, all green; a new `example-mcp-server` on Spring AI; no library module, existing service or pre-existing `.rego` touched — the optional `opa-abac-agent` module remains the slice's exit criterion). Guide: [[AGENT-TOOL-AUTHORIZATION]]; e2e: `scripts/postman/run-agent-tool-matrix.sh`
- Phase 10 📋 **PLANNED** (phase ① settled 2026-08-01): **the supervisor read path** — a unit manager who is a member of **no** team sees the catalogs of the teams their reports own or manage, read-only, at production detail behind a second factor. Pinned by [[0029-supervised-read-scope|ADR 0029]] (scope: a second **disjoint** access path — membership always wins so `supervised := S \ M`, CONTROL-capable reach, a new fail-closed org-relation seam, a synthesized read-only role carrying provenance, a two-leg partitioned list) and [[0030-step-up-decision-contract|ADR 0030]] (elevation: no new verb — child reads gated on the supervised path only; an `operatorManaged` tag flag making the `env` tier unstrippable; resource-server-side `auth_time` freshness as the whole control, **measured** on the rig; an additive `deny_reason` and an RFC 9470 challenge). **Failed the slice-sizing gate as one slice** (~13 tickets over five deployables) → ships as three, each fail-closed at its boundary so every later slice only *widens*: **A** [[SUPERVISED-SCOPE]] (📋 decomposed — the list, read-only, contents closed; 6 tickets, two example services + the policy corpus, no library change, one narrow Rego change — ADR 0031's inheritance confinement), **B** `PRODUCTION-TIER` (⏳ the tier; non-prod contents open), **C** `STEP-UP-ELEVATION` (⏳ the RFC 9470 round trip). The SPA's challenge UX stays collaborative rather than autonomous
- Tooling ✅ **SHIPPED** (2026-08-01): **[[PARTS-PORT]]** — the phase-③ runner learns to execute a declared slice as **sequential subagent-delegated parts** under an orchestrator (one-line `**Parts:**` declaration in `00-DESIGN`, a hard-fail `verify-package.sh [9]` gate via `check-parts.py`, a three-mode re-entrant `/autonomous-implement` skill with a delegate-and-collect loop that trusts only on-disk state, two fixed greppable escalation/fallback markers, three review layers). Strictly additive — an undeclared package runs byte-identically to today's bare-prompt paste. Environment claims re-measured by a capability spike 2026-08-01 (Mulch `autonomous-runs`). First real consumer: [[SUPERVISED-SCOPE]] as the first orchestrated slice
- Product lens: [[USER-STORIES]] — the catalog service from the user's perspective, per phase
- Decisions: [[adr/README|ADRs]] — 0005 (partial-eval→Specification), 0006 (three-layer enforcement), 0007 (permission categories)
- Root project intent & IP boundary: `../../../CLAUDE.md`
- Incremental plan source: `CLAUDE.md` → "Incremental plan"
