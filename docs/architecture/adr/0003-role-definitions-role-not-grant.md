---
tags:
  - status/active
  - type/architecture
  - area/user-service
  - area/abac
---

# ADR 0003 — Role definitions: role ≠ grant, system + team-scoped, app-resolved

**Status:** Accepted (Phase 4; extended in Phase 4.5 — see ADR 0004)
**Date:** 2026-06
**Context tags:** user-service, role definitions, app-resolved authorization

## Context

The authorization backbone is a **role definition** — `{code, attributes, permissions{type:[tokens]}}` —
serialized into the OPA `input` as `role_definition`. Since Phase 6.5 the map holds **coarse category
tokens** (`READ`/`WRITE`/`TAG`/`GRANT`), and the policy decides on their expansion through
`data.permission_categories` minus `denied_actions` ([[0007-coarse-grained-permission-categories|ADR 0007]]),
not on the raw action verb. We need to decide: where do role definitions come from,
who may create them, how does a caller's *effective* role get resolved for a specific resource, and how
do we stop a delegated admin from escalating privileges.

A role is reusable vocabulary; binding a *person* to a role on a *team* is a separate fact. Conflating the
two is the trap (you end up unable to reuse a role, or unable to reason about "who has what").

## Decision

**Role ≠ grant.** A `role_definition` is a reusable named permission set; the **`team_membership`** is the
grant that binds *(user, team) → role*. The same role can be bound to many people on many teams.

**Two kinds of role, one model.**
- **System roles** — `system = true`, `team_id = null`: the immutable global ladder
  `reader(10)` < `member(20)` < `senior(25)` < `administrator(30)` < `owner(40)` (Phase 6.7 renamed
  `viewer` → `reader` and added `senior`), seeded by Liquibase with **stable ids/codes** (`SystemRoles`) so
  application code resolves them without a query. Immutable through the API (edit/delete → 409).
- **Team-scoped custom roles** — `system = false`, `team_id` set: owner-defined at runtime, scoped to one
  team. A code is unique **within its scope** via two **partial unique indexes**
  (`(code) WHERE team_id IS NULL` for system; `(team_id, code) WHERE team_id IS NOT NULL` for custom), so
  `catalog-editor` can exist once globally *and* once per team independently.

**Two projections of the same membership** (`EffectiveRoleService`):
- the **management** projection — `permissions["team"]` = coarse capability tokens
  (`TeamRoleCapabilities`, post-6.5/6.7: owner/administrator → `READ`,`CONTROL`,`TAG`; senior →
  `READ`,`CONTROL`; member/reader/custom → `READ`), expanded by `team.rego` through
  `data.permission_categories` into the fine management verbs. This is what the dogfooded
  `@OpaPreAuthorize` on the service's *own* API decides on.
- the **resource** projection — the role's stored `permissions` with the wildcard `"*"` expanded to the
  concrete team-target type, returned to the catalog. The same person is "owner of the team" (management)
  and "read+write on the catalog" (resource) through one membership.

**Authorization is app-resolved.** The user-service resolves a caller's effective role from **live**
membership (`subject → user → memberships → team matched by the TeamTargetMatcher → bound role`) and
returns a `core.RoleDefinition`; the catalog's `HttpRoleDefinitionSupplier` is a single-bean swap that
fetches it and passes it to OPA. The match/resolution is in the app; the *decision* is in Rego. Resolving
from live membership means a removed member resolves empty immediately — **revocation propagates with no
cache to invalidate**. Later refinements keep the contract: the supervised-read branch synthesizes a
read-only role on the *non-membership* path ([[0029-supervised-read-scope|ADR 0029]]), stamped with
`provenance` so inheritance stays confined to membership roles ([[0031-inheritance-confined-to-membership-roles|ADR 0031]]).

**Self-escalation is blocked by a subset rule.** A delegated admin may only bind a role whose
permissions are a **subset** of the actor's own effective permissions on that team — enforced at
assignment time by `MembershipService.requireAssignableByActor`, which asks OPA's
`data.role.assignable` verdict through `RoleAssignableClient` (`role.rego`; fail-closed on outage).
`define-roles` is **owner-only** (not in the administrator ladder), so shaping the access ladder itself
is reserved to the owner; administrators manage members and curate tags but cannot mint new roles.

## Considered options

| Option | Why not |
|--------|---------|
| **Decide on raw JWT realm roles** | Coarse and IdP-coupled. Role definitions are the proven, richer model: data-driven `permissions{type:[verbs]}`, extensible attributes, team-scoped customisation. Post-B4 ([[0018-team-scoped-resource-isolation|ADR 0018]]) **no role definition denies**; the only surviving JWT-role fallback is verb-gated to `catalog:create`. |
| **Resolve roles in Rego from membership in OPA `data`** (ReBAC-first) | Deferred to **Phase 7** on purpose, so the app-resolved path ships first and the two can be compared. App-resolution keeps the decision input small and the membership graph out of OPA for now. |
| **Cache the resolved effective role** | A cross-request cache is a revocation hazard (a removed member could keep access until eviction) and stays rejected. A **request-scoped** memo ([[0023-request-scoped-resolution-memoization|ADR 0023]], `opa.abac.resolve-memo.enabled`, on by default) is a different thing — one request, one answer — and shipped in Phase 7.3. |
| **One `permissions` projection** (no management vs resource split) | The capability to *administer a team* and the permissions to *act on its target resource* are genuinely different axes. Folding them loses the dogfooding (the service couldn't decide its own management API from the same role) and muddies the wildcard expansion. |
| **A single unique index on `code`** | Would forbid a team from reusing a system-ish code like `catalog-editor`. The two partial indexes make "unique among globals" and "unique within a team" independent — the same trick reused for tag keys (ADR 0004). |
| **No subset rule** (trust the capability check alone) | The capability check says *who may define roles*; the subset rule says *they can't define one stronger than their own*. Both are needed to prevent a delegated admin from escalating. |
| **`administrator` may define roles** | Shaping the access ladder is an owner concern; admins manage people and tags. Keeping `define-roles` owner-only is the least-privilege split. |

## Consequences

- **Good:** roles are reusable and team-customisable; revocation is immediate (live re-derivation);
  delegated administration can't escalate (capability + subset); the catalog is a single-bean swap away
  from any role source; the same role drives both the dogfooded management decision and the resource
  decision.
- **Cost:** every decision resolves live (a walk, not a cache hit); the two-projection model is a little
  more to hold in your head than a single permission set; the wildcard-`"*"` expansion is a small bit of
  resolver cleverness to remember.
- **Extended by ADR 0004:** Phase 4.5 adds **optional** `requiredTags` + `matchMode` to the role
  definition (the one additive library change), so a role can require resource *tags* in addition to
  granting verbs — matched in Rego. **Refined by** ADR 0007 (coarse categories), 0015 (control-plane
  vocabulary), 0018 (membership as sole access path), 0029/0031 (the supervised branch + provenance).

## Related
- ADR 0001 (entity graph) · ADR 0002 (team-target) · ADR 0004 (the additive tag requirement on the role)
- ADR 0007 (coarse categories) · ADR 0015 (control-plane vocabulary) · ADR 0018 (sole access path) · ADR 0029/0031 (supervised branch, provenance)
- [[TEAM-BASED-AUTHORIZATION]] · [[ABAC-AUTHORIZATION]] · [[USER-MANAGEMENT-SERVICE]]
