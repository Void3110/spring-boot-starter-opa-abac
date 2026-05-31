---
tags:
  - status/planned
  - type/project
  - area/user-service
  - area/abac
---

# user-management-service (example)

> **Status:** Planning — design only, no code yet. Part of the [[POC-ROADMAP]] (Phase 4).
> Detailed domain decisions are deliberately deferred until we reach implementation; this note
> fixes the *purpose, shape, and boundaries* so the rest of the roadmap can plan around it.

## Purpose

The second example application. Where `example-catalog-management-service` is the **resource**
side of the demo, this service is the **subject / attribute** side: it owns the identities and
attributes that ABAC decisions are made *about* and *with*.

It exists to answer, for the demo: *"who is asking, what teams/roles do they have, and what
tags qualify them?"* — i.e. it produces the inputs an OPA policy needs to decide access to a
catalog resource.

This is the part that turns the PoC from "OPA says yes/no on a hardcoded input" into "a real
authorization decision driven by live subject attributes."

## What it mirrors (and how we generalize)

It mirrors the *shape* of the source platform's user/role model — but generalized and made
Spring-native, per the [[POC-ROADMAP]] thesis and the root `CLAUDE.md` IP boundary. No source,
names, or docs are copied; we reference the prior system only as "the source platform."

| Concept | What the demo needs | Generalization note |
|---------|--------------------|---------------------|
| **Users** | Identities (id, display name, link to the IdP subject). | Authentication is Keycloak's job; this service holds the *profile + attributes*, not credentials. |
| **Teams** | Grouping of users; teams carry attributes that flow into decisions. | Keep the hierarchy shallow unless a demo needs depth. |
| **Teammates / membership** | User↔team membership, possibly with a role-in-team. | The join is where "team-scoped" authorization gets interesting. |
| **Role definitions** | Named roles → permissions/attributes; referenced by policies. | **Data-driven**, not hardcoded enums — definitions live in the DB so the demo can show them changing. |
| **Tag dictionary** | A **dynamic** dictionary of tags applied to users/teams to drive ABAC attributes. | The source platform hardcodes tags; here the dictionary is a first-class, runtime-editable entity. This is a deliberate "done properly" improvement to showcase. |

## How it feeds ABAC (the integration point)

The catalog service (or APISIX/OPA) needs subject attributes at decision time. Options to decide
at implementation (tracked as an open question below):

1. **Attributes pushed into the token** — Keycloak/user-service populate claims; OPA reads them
   from the JWT. Simplest; limited by token size and freshness.
2. **OPA pulls from user-service** — policy queries this service (or a cached projection) for the
   subject's teams/roles/tags during evaluation. More realistic for dynamic tags; needs a clean,
   cacheable read API.
3. **The starter's `AbacContext` extraction** assembles subject attributes app-side from a
   user-service client before calling OPA. Keeps OPA inputs explicit; showcases the starter's
   extraction story.

Likely we demonstrate **(3) as the primary path** (it exercises the starter best) and mention
(1)/(2) as alternatives in a guide.

## Boundaries

- **Unpublished**, like the catalog example. It's PoC infrastructure, not a shippable artifact.
- **Not** a general-purpose identity service — it does only what the demo needs to make
  authorization decisions interesting. Keycloak remains the IdP.
- Same stack as the catalog app for consistency: Java 21 · Spring Boot 3.4 · Postgres +
  Liquibase · OpenAPI codegen · Testcontainers ITs.

## Decomposition (first pass — refine when we start)

1. **Scaffold** `example-user-management-service` as a flat root module (sibling of the catalog
   app); wire into `settings.gradle.kts`; Postgres + Liquibase baseline; health/actuator.
2. **Domain + CRUD**: users, teams, membership, role definitions — schema (Liquibase changelog),
   entities, repositories, controllers (OpenAPI-first like the catalog app).
3. **Dynamic tag dictionary**: tag entity + dictionary management endpoints; apply tags to
   users/teams.
4. **Read API for attributes**: a clean, cacheable endpoint/projection that returns a subject's
   authorization-relevant attributes (teams, roles, tags) — the contract the catalog side / OPA
   consumes.
5. **Wire into the ABAC loop**: integrate with the starter's `AbacContext` extraction so a catalog
   request resolves real subject attributes before the OPA call.
6. **Tests + a guide**: ITs for the attribute API; a docs guide showing the end-to-end decision
   using live user-service data.

## Open questions (resolve at implementation)

- Does this service expose a **public gateway route**, or is it **internal-only** (called by the
  catalog service / used as an attribute source)? Leaning internal-only for the first cut.
- **Attribute delivery**: token claims vs. OPA pull vs. app-side extraction — see the three
  options above. Pick one primary, document the rest.
- How much of **teams/teammates** do we actually need for a convincing demo vs. gold-plating?
- Does the **tag dictionary** need versioning/audit to make the "done properly" point, or is
  runtime-editable enough?

## Related

- Overall roadmap: [[POC-ROADMAP]]
- Resource-side counterpart: `example-catalog-management-service` (currently
  `example/catalog-management-service` — see [[POC-ROADMAP]] Phase 1 rename).
- IP boundary: root `CLAUDE.md` → "IP Boundary".
