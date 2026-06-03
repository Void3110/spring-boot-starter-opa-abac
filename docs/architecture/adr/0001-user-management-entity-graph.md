---
tags:
  - status/active
  - type/architecture
  - area/user-service
  - area/abac
---

# ADR 0001 — User-management entity graph & layered service structure

**Status:** Accepted (Phase 4)
**Date:** 2026-06
**Context tags:** user-service, domain model, JPA, Liquibase

## Context

The user-management-service is the **subject/attribute side** of the demo: it owns the identities, the
groupings, and the role/attribute vocabulary that ABAC decisions are made *about* and *with*. It exists
because authorization is only interesting when there are real subjects, real groupings, and a real
authority model behind a decision — not a fixed `DEMO_PRINCIPAL`.

It needs a small, honest domain that can answer one question for the catalog: *"what is this caller's
effective role on this resource?"* — derived from **live** data, never a denormalized snapshot. The
service also **dogfoods** the starter (it secures its own management API with the same `@OpaPreAuthorize`
it produces role definitions for), so its own entities must be authorizable too.

## Decision

A five-entity graph, all on the library's `AbstractAuditableEntity` (UUID id, audit columns, optimistic
`@Version`), with Liquibase owning the schema and `ddl-auto: validate` proving the mapping on every boot.

```
app_user ──< team_membership >── team
   (subject UQ)      │            (target_type+target_id UQ = the "team-target")
                     └──> role_definition ──(FK team_id, nullable)──> team
                              (partial-unique code per scope)

tag_definition ──(FK team_id, nullable)──> team      (partial-unique key per scope; ADR 0004)
```

- **`app_user`** — a profile linked to the IdP subject (`subject` is unique). The service owns the link
  between an IdP identity (`sub`) and an internal user id; nothing else stores that mapping.
- **`team`** — a grouping whose `(target_type, target_id)` is its **team-target** (unique). See ADR 0002.
- **`role_definition`** — a reusable named permission set (the *role* in role ≠ grant). System roles
  (`team_id = null`) and team-scoped custom roles (`team_id` set); a JSONB `permissions{type:[verbs]}`.
  See ADR 0003.
- **`team_membership`** — the **grant**: it binds *(user, team) → role_definition*, unique on
  `(team_id, user_id)`. This is the single edge everything re-derives from; deleting it revokes access.
- **`tag_definition`** — the tag dictionary (ADR 0004), same global-vs-team shape as role definitions.

**Service layer.** Unlike the catalog (flat: controller → repository), the user-service is **layered** —
a dedicated `service/` package holds the transactional logic (`MembershipService`, `RoleDefinitionService`,
`TagDefinitionService`, `EffectiveRoleService`, the `SubsetGuard`), controllers stay thin, mapping is a
**hand-written `UserMgmtMapper`** (no MapStruct), and there is **no facade** over the services. The extra
layer is justified here because the rules are non-trivial (owner-on-create atomicity, the subset rule,
immutability, app-resolved role derivation); the catalog's CRUD does not need it.

**No JPA associations between aggregates.** Memberships reference team/user/role by **id**, not by
`@ManyToOne` graphs. The relationships are explicit and queried deliberately, which keeps the
authorization re-derivation easy to reason about and avoids lazy-loading surprises across transaction
boundaries.

## Considered options

| Option | Why not |
|--------|---------|
| **Denormalize the effective grant** (store "user U has perms P on resource R") | Stale by construction. A removed membership would have to fan out deletes; instead we re-derive from `team_membership` every time, so revocation is immediate and there is one source of truth. |
| **Object-graph JPA associations** (`Team @OneToMany memberships`, etc.) | Couples aggregates, invites N+1 and lazy-init-outside-transaction bugs, and hides the exact queries the resolver depends on. Id references keep the graph flat and the resolution explicit. |
| **Flat service (catalog style)** — controllers call repositories directly | The user-service's invariants (atomic owner-on-create, subset rule, system-role immutability, two role *projections*) are real domain logic that belongs in a transactional service, not smeared across controllers. |
| **MapStruct / a generated mapper** | A third codegen stage (on top of OpenAPI) for flat DTOs isn't worth it; both example apps stay consistent with one small hand-written mapper each. |
| **Roles as enum constants in code** | Defeats the point — team-scoped *custom* roles must be runtime rows. System roles are seeded rows too (with stable ids in `SystemRoles`), so there is one model for both. |
| **A separate `grant` table distinct from membership** | Membership *is* the grant in this model (one role per user per team). A separate grant table would duplicate the edge without adding expressiveness at this phase. |

## Consequences

- **Good:** revocation is immediate (re-derive, never cache); the schema is greenfield and clean
  (`ddl-auto: validate` is a real boot-time check); the same entities are authorizable, so the service
  dogfoods the starter; the layered service keeps controllers thin and the invariants in one place.
- **Cost:** every decision pays a resolution walk (subject → memberships → matching team → role) rather
  than a cached lookup — acceptable for the demo, and the resolver is the natural place to add caching
  later. Id-reference relationships mean the code issues explicit queries instead of navigating a graph.
- **Follow-on:** the membership edge is exactly what a Phase-7 ReBAC-in-Rego experiment would push into
  OPA `data` to express the join in-policy instead of app-resolving it.

## Related
- [[USER-MANAGEMENT-SERVICE]] (the shipped slice) · [[DOMAIN-MODEL]] (the base-entity layer)
- ADR 0002 (team-target) · ADR 0003 (role definitions) · ADR 0004 (tag dictionary)
