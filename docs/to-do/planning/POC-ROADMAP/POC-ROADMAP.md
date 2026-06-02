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
opa-abac-core                     # framework-agnostic ABAC model + OPA client (no Spring dep)
opa-abac-spring-security          # AuthorizationManager + @OpaPreAuthorize
opa-abac-spring-data              # partial-eval → JPA Specification data filtering
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
  references. Tracked as Phase 1 below. *(Not yet executed — separate confirmed step.)*

## Current state (snapshot)

Phases 0–2 are **done**. The example rig runs end to end via `./deploy.sh` (see
[`infra/README.md`](../../../infra/README.md)):

```
Keycloak (identity) → APISIX [ openid-connect → demo identity-enricher → OPA decision → tracing ]
                        → round-robin over N catalog pods → Postgres
```

- **Load balancing**: APISIX round-robins over N app pods (`./deploy.sh up --pods N`).
- **Tracing**: Jaeger + Badger; 5 services traced (apisix, keycloak, opa, catalog app, jaeger).
- **Authz**: OPA called per request — **allow-all placeholder** policy (`infra/opa/policies/gateway.rego`).
- **Identity**: Keycloak realm `catalog-demo` (user `demo/demo`), gateway OIDC; a **demo** Lua
  enricher injects `X-User-Id`/`X-Username` — **throwaway**, replaced by Spring-native extraction in Phase 3.
- The **service itself does no auth yet** — all enforcement is at the gateway. That's intentional;
  Phase 3 moves real ABAC into the app via the library.

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

**Next: Phase 4 — `user-management-service` (teams + role-defs).** The `RoleDefinitionSupplier` SPI built
in Phase 3 (demo supplier today) gets its real, HTTP-backed implementation: the user-service owns
**teams** (members + role hierarchy), **role definitions** (fixed system roles + owner-defined
team-scoped custom roles), and **team-scoped grants**, and resolves the caller's effective role *for a
resource* by walking team membership — the portal-style **app-resolved** path. The team abstraction
(owner-on-create, transfer-ownership, the no-self-escalation rule) is the centerpiece; the **dynamic tag
dictionary** is split to Phase 4.5, and a **ReBAC-in-Rego** demonstration is a new Phase 7. See
[[USER-MANAGEMENT-SERVICE]]. (Batch eval + partial-eval → JPA data filtering remain Phase 5.)

## Phases

> Phases 2–5 layer the library onto the catalog app incrementally (mirrors the CLAUDE.md
> "Incremental plan"). The user-service slots in once we need real attributes to drive decisions.

| # | Phase | Outcome | Notes |
|---|-------|---------|-------|
| **0** | Catalog CRUD (done) | Runnable catalog app, Postgres + Liquibase, no auth. | ✅ already in repo |
| **1** | **Restructure** | Flatten `example/` → `example-catalog-management-service`; settings + paths updated; build green. | ✅ **done** (commit `0ce6026`). |
| **2** | Infra: identity + gateway | Keycloak (realm) → APISIX (OIDC route) → OPA → Jaeger. | ✅ **done** — full rig via `deploy.sh`; see `infra/README.md`. |
| **3** | Library spine | `OpaClient` → `AbacContext` extraction → `OpaAuthorizationManager` → `@OpaPreAuthorize`, layered onto the catalog app. | ✅ **DONE** (on a feature branch). The core generalization work. First slice: [[DOMAIN-MODEL-FOUNDATION]] (base/secure entities, tags, locking, base service, e2e suite). Second slice: [[LIBRARY-SPINE]] (HttpOpaClient + extraction + role-definition-driven `@OpaPreAuthorize` + starter wiring + catalog adoption; the demo gateway enricher retired; e2e allow/deny matrix green). |
| **4** | **user-management-service (teams + role-defs)** | New example app: users, **teams** (members + a role hierarchy), **role definitions** (fixed system roles + owner-defined team-scoped custom roles), **team-scoped grants**, owner-on-create + transfer-ownership. The HTTP-backed `RoleDefinitionSupplier` resolves the caller's effective role *for a resource* by walking team membership server-side and feeds it to the catalog spine (a single-bean swap of the demo supplier). **Authorization is app-resolved** (the portal-style path): the service resolves the role, the catalog still passes `role_definition` in OPA `input`. | See [[USER-MANAGEMENT-SERVICE]]. The **dynamic tag dictionary** is deferred to its own follow-on (Phase 4.5) as an ABAC extension. |
| **4.5** | **Dynamic tag dictionary (ABAC extension)** | Runtime-editable tag dictionary (subject vs resource tag keys + validation rules) layered onto the user-service; tags become subject/resource attributes the policies read. | Split out so Phase 4 ships the team/role core first. See [[RESEARCH-AUTOTAG-AND-FILTERING]]. |
| **5** | Advanced library | Batch evaluation → partial-eval → JPA data filtering, demonstrated across both services. | The differentiators vs. naive OPA integration. |
| **6** | Publish & polish | Maven Central publish for the starter; docs/guides complete; example runs from a clean clone. | The artifact must stand on its own. |
| **7** | **ReBAC-in-Rego (team grants, in-policy)** | Push the team/membership/grant graph into OPA `data` and express the "subject member-of team **and** team has-role-on resource" join *in Rego* (Zanzibar-style userset), as an alternative to the Phase-4 app-resolved path. Demonstrates RBAC vs ABAC vs ReBAC expression in one OPA policy. | New item from the team-abstraction analysis. The strongest "stands out vs naive OPA" piece; deferred so the app-resolved path ships first. |

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
- Root project intent & IP boundary: `../../../CLAUDE.md`
- Incremental plan source: `CLAUDE.md` → "Incremental plan"
