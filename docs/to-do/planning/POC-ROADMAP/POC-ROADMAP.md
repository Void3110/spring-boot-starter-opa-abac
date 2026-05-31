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

**Next:** Phase 3 — build the library spine and have the catalog app consume identity for real.

## Phases

> Phases 2–5 layer the library onto the catalog app incrementally (mirrors the CLAUDE.md
> "Incremental plan"). The user-service slots in once we need real attributes to drive decisions.

| # | Phase | Outcome | Notes |
|---|-------|---------|-------|
| **0** | Catalog CRUD (done) | Runnable catalog app, Postgres + Liquibase, no auth. | ✅ already in repo |
| **1** | **Restructure** | Flatten `example/` → `example-catalog-management-service`; settings + paths updated; build green. | ✅ **done** (commit `0ce6026`). |
| **2** | Infra: identity + gateway | Keycloak (realm) → APISIX (OIDC route) → OPA → Jaeger. | ✅ **done** — full rig via `deploy.sh`; see `infra/README.md`. |
| **3** | Library spine | `OpaClient` → `AbacContext` extraction → `OpaAuthorizationManager` → `@OpaPreAuthorize`, layered onto the catalog app. | ◀ **NEXT.** The core generalization work. Replaces the demo gateway enricher with Spring-native extraction. |
| **4** | **user-management-service** | New example app: users/teams/roles + dynamic tag dictionary; feeds ABAC attributes. | See [[USER-MANAGEMENT-SERVICE]]. Can begin design in parallel with Phase 3. |
| **5** | Advanced library | Batch evaluation → partial-eval → JPA data filtering, demonstrated across both services. | The differentiators vs. naive OPA integration. |
| **6** | Publish & polish | Maven Central publish for the starter; docs/guides complete; example runs from a clean clone. | The artifact must stand on its own. |

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
