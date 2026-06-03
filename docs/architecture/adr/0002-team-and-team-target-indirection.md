---
tags:
  - status/active
  - type/architecture
  - area/user-service
  - area/abac
---

# ADR 0002 — Team + team-target: the resource→authority indirection

**Status:** Accepted (Phase 4)
**Date:** 2026-06
**Context tags:** user-service, teams, authority model

## Context

A resource (a catalog) needs a durable **authority** — *who governs it, and through which roles*. We do
not want every catalog to carry an ownership column, nor to encode "user X owns catalog Y" as a direct
edge (that doesn't scale to multiple people with different roles on the same resource, and it couples the
catalog schema to the identity model). We need a grouping that *points at* a resource and carries the
people + their roles.

We also want the catalog app to stay ignorant of the identity model: it forwards a subject and a resource
id and gets back a role definition. The indirection from a resource to its governing authority must live
entirely in the user-service.

## Decision

A **`Team`** is the durable authority, and its **team-target** — the pair `(target_type, target_id)` — is
how a team points at the resource it governs. The pair is **unique** (`uq_team_target`), so exactly one
team governs any given resource.

- `Team extends AbstractSecuredEntity`, so a team is itself an authorizable, taggable resource (resource
  type `"team"`) — which is what lets the service **dogfood** the starter to secure its own management API
  (`team:manage` / `team:define-roles` / `team:define-tags` / `team:transfer-ownership`).
- **Owner-on-create is atomic.** Creating a team for a target, in one `@Transactional` method, also writes
  the creator's `owner` `TeamMembership`. The unique team-target makes the create idempotent against
  races (a second create for the same `(type, id)` → `TeamTargetExistsException`). A resource is therefore
  *never* created without exactly one owner.
- **Transfer-ownership is a first-class operation** (GitHub-style): in one transaction the new owner is
  promoted to `owner` and the previous owner downgraded to `administrator`, so there is always exactly one
  owner and a resource is never orphaned.
- **The resource→team match is a pluggable seam.** `TeamTargetMatcher` decides whether a team's target
  governs a `(resourceType, resourceId)`. The default `ExactTeamTargetMatcher` matches only the exact
  pair; a later, additive matcher can walk a hierarchy (a team-target on a `catalog` granting on its
  categories/products) — which is the Phase-5 hierarchy step, deliberately left as a seam now.

The resolver and the tag-definition resolution both reuse this one matcher, so "what governs this
resource?" has a single answer everywhere.

## Considered options

| Option | Why not |
|--------|---------|
| **An `owner_id` column on the resource** | One owner only, no role gradation, and it couples the catalog schema to identity. The team-target inverts the dependency: the *team* references the resource, the resource knows nothing. |
| **A direct `user → resource` ACL edge** | Doesn't express "a group of people with different roles on the same resource," and scatters authority across rows instead of one team object that can be transferred and audited as a unit. |
| **Polymorphic JPA association to the target** | A `(target_type, target_id)` pair is a deliberately *loose* reference — the target lives in another service (the catalog), so a typed association is impossible and undesirable. The string-type + UUID-id pair is the honest cross-service pointer. |
| **Allow multiple teams per resource** | Ambiguous authority (which team's roles win?). The unique team-target keeps "who governs this" unambiguous; multi-team governance, if ever needed, is a later explicit decision. |
| **Owner-on-create as two API calls** (create team, then add owner) | A window where a resource has a team but no owner. Atomic creation closes it; the unique constraint closes the race. |

## Consequences

- **Good:** the catalog stays identity-agnostic (it forwards `(subject, resourceType, resourceId)`); a
  resource always has exactly one governing team and exactly one owner; transfer is safe and atomic;
  the `TeamTargetMatcher` seam means hierarchy is an additive change, not a rewrite.
- **Cost:** the `(target_type, target_id)` reference has no DB-level FK to the target (it lives in another
  service), so referential integrity across the catalog↔user-service boundary is the application's
  responsibility — acceptable for a two-service demo, and the resolver fails closed when no team matches.
- **Follow-on:** the exact-match matcher is the obvious place a Phase-5 hierarchy walk plugs in; the
  team-as-authority object is also the natural unit a Phase-7 ReBAC model would expose as a userset.

## Related
- ADR 0001 (entity graph) · ADR 0003 (role definitions, which hang off the team) · ADR 0004 (tag definitions)
- [[TEAM-BASED-AUTHORIZATION]] (the resulting authorization model) · [[USER-MANAGEMENT-SERVICE]]
