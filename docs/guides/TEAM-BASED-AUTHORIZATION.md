---
tags:
  - status/active
  - type/guide
  - area/abac
  - area/user-service
---

# Team-based authorization — the app-resolved path

How the demo turns *"who is asking?"* into *"what role does that give them on **this** resource?"* by
resolving the caller's effective role from **live team membership** in the `user-management-service`,
and feeding it to the catalog spine. This is the **app-resolved** model: the user-service resolves the
role server-side; the catalog still passes `role_definition` in the OPA `input` exactly as it did with
the static demo supplier — a single-bean swap, no wire-contract change.

It builds directly on the single-decision spine in [[ABAC-AUTHORIZATION]]; read that first.

## The team abstraction

A user creates a resource → the resource is linked to a **team-target** → the creator becomes the
**owner** → the owner manages a **team** and grants access to the resource through team membership.
Researched against AWS / Heroku / GitHub / GCP / Kubernetes / OpenFGA.

### `role ≠ grant`

The root cause of *role explosion* is conflating a **role** with a **grant**; every platform studied
separates them, and so do we:

- **`RoleDefinition`** — a reusable *named permission set* (`code` + `permissions{resourceType:[verbs]}`
  + attributes). Two kinds:
  - **system roles** — immutable, seeded: `owner`, `administrator`, `member`, `viewer`;
  - **team-scoped custom roles** — owner-defined, scoped to one team.
- **`TeamMembership`** — the **grant**: `{principal × roleDefinition × team scope}`. The membership row
  *carries* the role. "Team-scoped" is the membership's scope — not a role per team.

### Entities

```
User(id, subject /* IdP sub */, displayName)
Team(id, name, targetType, targetId)              -- the team-target = the owned resource ref
RoleDefinition(id, code, system, teamId?, attributes jsonb, permissions jsonb)
TeamMembership(id, teamId, userId, roleDefinitionId)   -- unique (teamId, userId)
```

`Team` is the durable owner of a resource (resource→team indirection); an owner *role* sits on a
person and is transferable.

## The hard rules (the teaching points)

1. **Owner-on-create (bootstrap).** Creating a team-target is **one transaction**: create the `Team`
   and write the **owner** `TeamMembership` for the creator. There is never a grant-less resource.
2. **No self-escalation (the hybrid assignment gates — Phase 6.5, [[PERMISSION-MODEL]]).** Assignment
   is gated by a strict **cross-tier level compare** (`actorLevel > candidateLevel`, from
   `attributes.role_level`; an unreadable level rejects) plus, at the **senior** tier only, OPA's
   `data.role.assignable` subset-on-effective verdict (any OPA non-answer rejects). Every rejection is
   `422 ROLE_SUBSET_VIOLATION`. Authoring is bounded by the **level ceiling**, not by the author's own
   permissions (owner-only authoring made the author-subset check vestigial). Acting on an
   **existing** member is additionally bounded by the **target-tier gate**: a member whose *current*
   tier is above the actor's cannot be demoted or removed by them (peers stay manageable).
3. **Transfer-ownership is first-class.** A dedicated operation: the new owner gets `owner`, the old
   owner is downgraded to `administrator`. Prevents orphaned resources.
4. **Revocation = membership is the single source of truth.** Removing a `TeamMembership` revokes all
   access derived through it; the resolve API always re-derives (no stale denormalized grants).
5. **Authorize the actor of a grant, not the service identity** (confused-deputy guard). Every
   management endpoint is `@OpaPreAuthorize`-secured against the *calling* subject.
6. **Decide grant mutations under the team-row lock.** Every team-scoped grant mutation (membership
   add/change/remove, transfer-ownership, custom-role writes) locks the `Team` row `FOR UPDATE`
   before the gate decisions (both level gates AND the `assignable` snapshots read post-lock state),
   so a concurrent demotion of the actor cannot land between the check and the grant (retro-audit
   2026-06-12; `CONCURRENCY-AND-LOCKING` Rules 1–2).

> **Known demo limitation — team-target squatting.** `POST /teams` is deliberately ungated
> (bootstrap: creating your first team precedes any membership to authorize against), and the
> `(targetType, targetId)` uniqueness means whoever binds a team to a target first governs it. A
> production deployment must verify the caller's right over the target before binding — e.g. a
> cross-service ownership check on the catalog, or an invite/claim flow. The example keeps the
> bootstrap simple and documents the gap instead (retro-audit 2026-06-12).

## The integration point — app-resolved

```
catalog @OpaPreAuthorize(action, resourceType, resourceId)
  → HttpRoleDefinitionSupplier.lookup(userId, resourceType, resourceId)            [catalog app code]
       → GET user-mgmt /internal/effective-role?userId&resourceType&resourceId
            server-side:  subject → user → memberships → team whose team-target matches the resource
                          → the membership's bound RoleDefinition
  → AbacContext(subject, action, resource, role_definition, env) → OPA decides on permissions
```

- **`HttpRoleDefinitionSupplier`** lives in the **catalog app** (selected by `catalog.role-source=http`,
  default `demo`). It calls the resolve API on the JDK `HttpClient`, returns `Optional<RoleDefinition>`,
  and **fails closed**: a non-200 (incl. the 204 no-match), a timeout, a connection refused, or a
  malformed body → `Optional.empty()` → the policy default-denies.
- **The resolve API is internal.** `GET /internal/effective-role` is not gateway-fronted — an
  in-network attribute source the catalog calls. `200 {RoleDefinition}` or **`204`** (empty, *not* an
  error) on no-match. The role's stored permissions are returned with the wildcard `"*"` (system roles
  are target-type-agnostic) expanded to the concrete team-target type.
- **Resource→team matching is pluggable** — `TeamTargetMatcher` (exact-match default; a hierarchy-walking
  matcher is an additive Phase-5 swap).

### Why app-resolved first
It reuses the Phase-3 spine untouched (the `RoleDefinitionSupplier` SPI already returns a
`RoleDefinition`), keeps the OPA input identical, and matches the proven source-platform design.
Token-claim delivery and **ReBAC-in-Rego** (the Zanzibar userset join in the policy) are documented
alternatives; ReBAC is the more elegant end state and is **Phase 7**, so the two can be compared.

## Dogfooding the starter

The `user-management-service` is itself a secured Spring app: it adopts the starter, declares its own
`SecurityFilterChain` + `AbacFilter`, and annotates its **management** controllers with
`@OpaPreAuthorize` (`team:manage`, `team:define-roles`, `team:transfer-ownership`). Its *own*
`RoleDefinitionSupplier` (`TeamRoleDefinitionSupplier`) resolves the caller's **management** role on the
team being managed (a capability ladder: owner > administrator > senior > member/reader), and `team.rego` decides.
So the service that *produces* role definitions for the catalog is *also* a consumer of the same library
— a clean, recursive demonstration that the starter works for a real second app, and that the
subset/escalation rules are enforced by the same `@OpaPreAuthorize` mechanism.

> This resolves the "does it expose a gateway route?" question: **internal-only** for the resolve API;
> **secured (dogfooded)** for the management API.

## Run it end to end

```bash
# Bring the full rig up with OIDC + the user-service; the catalog pods resolve roles from it.
ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2

# Run the team-based ABAC matrix (mints in-network tokens, bootstraps the team data, asserts):
cd scripts/postman && ./run-team-matrix.sh
```

The matrix proves, through the gateway, with roles resolved from real team membership:

| Caller | Team role | Action | Result |
|--------|-----------|--------|--------|
| owner | `owner` | write the owned catalog | **200** |
| owner / viewer-member | (read) | read | **200** |
| viewer-member | `viewer` | write | **403** |
| custom-editor member | team-scoped `catalog-editor` | write | **200** |
| non-member | (none → empty role) | write | **403** |
| owner | `owner` | manage the user-service's own API | **200** (dogfood) |
| viewer-member | `viewer` | manage | **403** (dogfood) |

The demo team data (the team-target catalog id and the IdP subjects) is only known at run time, so
`run-team-matrix.sh` mints the tokens, decodes their subjects, seeds a fixed demo catalog, and
bootstraps the team + memberships via the user-service's internal API before running newman. See
[[E2E-TESTING]] for the in-network token rationale.

## Related
- The single-decision spine this builds on: [[ABAC-AUTHORIZATION]]
- The e2e harness + in-network token caveat: [[E2E-TESTING]]
- The base entity stack: [[DOMAIN-MODEL]] · The roadmap: [[POC-ROADMAP]]
- Deferred: the dynamic **tag dictionary** (Phase 4.5, [[RESEARCH-AUTOTAG-AND-FILTERING]]) and
  **ReBAC-in-Rego** (Phase 7).
