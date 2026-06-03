---
tags:
  - status/planned
  - type/architecture
  - area/user-service
  - area/abac
---

# user-management-service — design

> Part of [[POC-ROADMAP]] Phase 4. The index is [[USER-MANAGEMENT-SERVICE]]; the work breakdown is
> [[01-DECOMPOSITION]]; the cases are [[10-QA-TEST-CASES]]. This note is the *why* and *shape*.

## Problem

Phase 3 ([[LIBRARY-SPINE]]) made the catalog app do real, role-definition-driven ABAC, but the role
definitions come from a **static demo supplier** keyed on Keycloak realm roles. There is no real
authority that says *"this user, on this resource, has this role."* This phase builds that authority:
a **user-management-service** that owns **teams**, **role definitions**, and **grants**, and resolves a
caller's **effective role for a resource** — feeding the catalog spine's `RoleDefinitionSupplier` SPI
(the HTTP-backed implementation that replaces the demo one, a single-bean swap built for in Phase 3).

The headline concept is the **team abstraction**: a user creates a resource → it is linked to a
**team-target** → the creator becomes the **owner** → the owner manages a team and grants access to the
resource through team membership. This is the GitHub/Heroku "team grants access to a resource" pattern,
researched against AWS / GCP / Kubernetes / OpenFGA (Mulch reference *"Team-based resource access models
across cloud platforms"*).

**Scope (decided):** teams + role-definitions + team/role management + the app-resolved resolve API +
the catalog wiring. **Out of scope:** the dynamic tag dictionary (Phase 4.5) and ReBAC-in-Rego (Phase 7).

## The core distinction: `role ≠ grant`

Every platform studied separates a **role** from a **grant**, and conflating them is the root cause of
*role explosion*. We model them as distinct things:

- **`RoleDefinition`** — a reusable *named permission set*. Already exists in `opa-abac-core` (from
  [[LIBRARY-SPINE]]): `code` + `attributes` + `permissions{resourceType:[verbs]}`. Two kinds:
  - **system roles** — immutable, seeded: `owner`, `administrator`, `member`, `viewer`;
  - **team-scoped custom roles** — owner-defined, live in the DB, scoped to one team.
- **Grant / Binding** — `{principal × roleDefinition × scope}`. Here the scope is a **team** (and through
  the team-target, a resource). We realize the binding as the **`TeamMembership`** row, which *carries*
  the role. Borrow GCP's vocabulary: principal · role · binding.

> "Team-scoped" means the **membership's scope** is the team — *not* a new role per team. One role
> definition serves many teams via many memberships.

## Entity model

`example-user-management-service` adopts the `opa-abac-spring-data` base stack (so every entity is an
auditable, lockable, taggable `AbstractSecuredEntity` / `AbacDataObject` and the service secures *its own*
management API with the starter — dogfooding it). Plain `UUID` ids, OpenAPI-first, Postgres + Liquibase,
Testcontainers ITs — same conventions as the catalog app.

```
User(id, subject /* IdP sub */, displayName)
Team(id, name, targetType, targetId)              // the team-target = the owned resource ref
RoleDefinition(id, code, system, teamId?,         // system (teamId null) or team-scoped
               attributes jsonb, permissions jsonb /* {type:[verbs]} */)
TeamMembership(id, teamId, userId, roleDefinitionId)   // the GRANT: principal × role × team
```

- **`Team.targetType` + `targetId`** point at the resource the team governs (e.g. `catalog`, a UUID).
  Resource→team indirection: the **team** is the durable owner; an owner *role* sits on a person.
- **`TeamMembership`** is unique on `(teamId, userId)` — a user holds exactly one role per team (kept
  simple for the demo; multi-role is additive later).
- **`RoleDefinition.permissions`** reuses the exact shape the OPA policy already reads
  (`{resourceType: [verbs]}`), so the resolve API returns a `core.RoleDefinition` verbatim.

### System roles & semantics

| Role | permissions (on the team-target type) | management capability |
|------|----------------------------------------|------------------------|
| **owner** | read + write | manage membership, define team-scoped custom roles, assign roles, **transfer ownership** |
| **administrator** | read + write | manage membership + assign *existing* roles; **cannot** transfer ownership or exceed own perms |
| **member** | read + the verbs its assigned role grants | none |
| **viewer** | read | none |

System roles are seeded by Liquibase, `system = true`, and are **immutable** via the API (update/delete
of a `system` role → 409/forbidden). Their `code`s are stable so policies/tests can rely on them.

## The hard rules (the teaching points)

These come straight from the cross-platform research and are *the* reason this slice is interesting:

1. **Owner-on-create (bootstrap).** Creating a team-target is **one transaction**: create the `Team`,
   set its target, and write the **owner** `TeamMembership` for the creating user. There is never a
   grant-less resource. (AWS account-owns-resource / GCP grants creator `owner` / OpenFGA writes the
   `(creator, owner, resource)` tuple — all atomic.)
2. **No self-escalation (the Kubernetes subset rule).** An owner/admin may only **assign or define** a
   role whose `permissions` are a **subset** of the actor's own effective permissions on that team —
   unless the actor holds an explicit `delegate` capability. You cannot grant more than you hold.
3. **Transfer-ownership is first-class.** A dedicated operation: the new owner gets the `owner` role; the
   old owner is downgraded to `administrator` (GitHub-style — new owner admin, old owner collaborator).
   Prevents orphaned resources when an owner leaves.
4. **Revocation = membership is the single source of truth.** Removing a `TeamMembership` revokes all
   access derived through it; the resolve API always **re-derives** (no denormalized grants to go stale).
5. **Authorize the actor of a grant, not the service identity** (confused-deputy guard). Every
   management endpoint is `@OpaPreAuthorize`-secured against the *calling* subject, carrying the caller's
   identity — the service's own elevated identity never performs a grant on its behalf.

## The integration point — app-resolved (decided)

The **app-resolved** path (mirrors the source platform), chosen over token-claims and ReBAC-in-Rego for
the first cut because it **reuses the Phase-3 spine untouched** — the `RoleDefinitionSupplier` SPI already
returns a `RoleDefinition`, and the OPA input stays identical.

```
catalog @OpaPreAuthorize(action, resourceType, resourceId)
  → HttpRoleDefinitionSupplier.lookup(userId, resourceType, resourceId)            [catalog side]
       → GET user-mgmt/internal/effective-role?userId&resourceType&resourceId
            server-side:  user → memberships → team where (targetType,targetId) matches the resource
                          → the membership's bound RoleDefinition
  → AbacContext(subject, action, resource, role_definition, env) → OPA decides on permissions
```

- **`HttpRoleDefinitionSupplier`** lives in the **catalog app** (an `@Bean` overriding the demo no-op),
  not the library — it's app-specific wiring. Built on the JDK `HttpClient` (or `RestClient`), it returns
  `Optional<RoleDefinition>`; **fails closed** (any error / non-200 / timeout → `Optional.empty()` → the
  policy default-denies). The demo supplier stays behind a profile/property as a fallback.
- **Resource→team matching is pluggable.** Start with **exact match** (`targetType==resourceType &&
  targetId==resourceId`). Hierarchy walking (a team-target on a `catalog` granting on its
  categories/products via [[DOMAIN-MODEL]] ancestry) is a later, additive resolver — it ties into Phase-5
  hierarchical authorization. Keep a `TeamTargetMatcher` seam.
- **The resolve API is internal.** `/internal/effective-role` is not gateway-fronted (it's an
  attribute source the catalog calls in-network). The **management** API (teams, memberships, role-defs)
  *is* gateway-fronted and secured by the starter — the service dogfoods its own ABAC.

### Why not the alternatives (yet)
- **Token claims** — push teams/roles into the JWT. Simplest but token-size/freshness bound, and it
  pushes authority into Keycloak rather than a service we control. Documented, not built.
- **ReBAC-in-Rego** — push the team/membership/grant graph into OPA `data` and do the
  `member-of team ∧ team has-role-on resource` join *in the policy* (Zanzibar userset). The more elegant
  end state and the strongest portfolio piece, but a bigger lift and a different data-distribution story.
  **Deferred to Phase 7**; the app-resolved path ships first and the two can be compared.

## Dogfooding the starter (a deliberate demo)

The user-management-service is itself a secured Spring app: it adopts `opa-abac-spring-boot-starter`,
declares its own `SecurityFilterChain` + `AbacFilter`, and annotates its **management** controllers with
`@OpaPreAuthorize` (e.g. `team:manage`, `roledef:write`). Its *own* `RoleDefinitionSupplier` resolves the
caller's role on the **team** being managed (owner/administrator vs member/viewer). So the service that
produces role definitions for the catalog is *also* a consumer of the same library — a clean, recursive
demonstration that the starter works for a real second app, and that the subset/escalation rules are
enforced by the same `@OpaPreAuthorize` mechanism.

> This also resolves the "does it expose a gateway route" open question: **internal-only** for the
> resolve API; **gateway-fronted + ABAC-secured** for the management API.

## Considered & rejected

| Option | Why rejected (for now) |
|--------|------------------------|
| **One combined role+grant entity** | Conflating role and grant is the documented cause of role explosion; every platform (GCP binding, K8s ClusterRole+RoleBinding, GitHub role+team-grant) separates them. We model `RoleDefinition` and `TeamMembership` separately. |
| **Direct user-owns-resource (no team)** | Orphaned-resource problem when the owner leaves (GitHub's "needs a Support ticket"). The **team** owns the resource; an owner *role* sits on a person and is transferable. |
| **ReBAC-in-Rego now** | More elegant but a bigger lift + a different data-distribution model; the app-resolved path reuses the Phase-3 spine with zero wire change. Deferred to Phase 7 so both can be compared. |
| **Token-claim attribute delivery** | Token-size/freshness bound and moves authority into Keycloak. Documented as an alternative. |
| **Dynamic tag dictionary in this slice** | Large machinery orthogonal to the team/role core; split to Phase 4.5 so the centerpiece ships first. |
| **Multi-role-per-team membership** | Unneeded complexity for the demo; `(team,user)` unique with one role is enough and is additive to extend. |
| **Custom roles at any scope** | Like GCP (custom roles only at project/org, not folders), we restrict custom roles to **team scope** — system roles cover the global ladder. Keeps governance simple and the subset rule local. |
| **MapStruct for entity↔DTO mapping** | Diverges from the catalog's hand-written `CatalogMapper`, adds a third codegen stage alongside the OpenAPI generator, and the ~4 flat DTOs don't justify it. Hand-written `UserMgmtMapper` instead (see "Internal structure"). |
| **A facade layer** | Pure-delegation over the single multi-aggregate read; the `service/` layer is already that orchestration point. Deferred as a trivial later insertion if Phase 5 needs read-side orchestration. |
| **Flat structure like the catalog (no `service/`)** | The catalog has no cross-entity transactional logic; the user-service's invariants (owner-on-create, transfer, subset rule, resolve) demand a `@Transactional` service layer. Mixing them into `domain/` would hide the orchestration. |

## Internal structure (layered, deliberately)

The catalog app is intentionally **flat** (`config` · `domain` · `web`; controllers call repositories
directly; one `ProductService` exists only for the optimistic-locking demo) — it teaches "you don't need
ceremony to use the starter." The user-service makes **one deliberate divergence**: a dedicated
**`service/` layer**, because — unlike the catalog — its core operations are inherently **cross-entity and
transactional**. That divergence is justified by the domain, not by taste:

| Layer | Decision | Why |
|-------|----------|-----|
| **`service/`** | **Add it.** `TeamService`, `RoleDefinitionService`, `MembershipService`, `EffectiveRoleResolver` — `@Transactional`, controllers stay thin. | Owner-on-create (Team + membership in one tx), transfer-ownership (atomic up/down), the subset rule (read actor's effective role → validate target → write), effective-role resolution (the membership walk) are all multi-entity units of work. They *cannot* live in a controller without becoming a design smell. |
| **Mapping** | **Hand-written** static `UserMgmtMapper`, like the catalog's `CatalogMapper`. **No MapStruct.** | Sibling-consistency in a repo meant to be *read*; avoids a third codegen stage next to the OpenAPI generator; the ~4 flat DTOs don't earn MapStruct's nesting/bulk-copy strengths; keeps the low-ceremony adoption story honest. |
| **Facade** | **None.** | Correct overkill for a single multi-aggregate read (effective-role resolution) — the `service/` layer already *is* that orchestration point. A facade would be a pure-delegation layer (lasagna). Keep the service clean so a facade is a trivial later insertion **if** Phase 5 brings read-side orchestration (batch eval / list filtering). Build the seam, not the layer. |

Package layout (vs the catalog's `config`/`domain`/`web`): adds exactly **one** package, `service/`.

## Module placement

- **New app:** `example-user-management-service` (flat root module, sibling of the catalog app), wired
  into `settings.gradle.kts`. Adopts `opa-abac-spring-boot-starter` + `opa-abac-spring-data`.
- **Catalog app:** add `HttpRoleDefinitionSupplier` (app-specific bean) + a property/profile to choose
  demo vs HTTP supplier. **No change** to the library modules' public API — the SPI was built for this.
- **Infra:** the rig must run a **second service** alongside the catalog pool (a user-mgmt container +
  its own Postgres schema/DB); `deploy.sh`/compose extended; Keycloak realm gains the demo users/teams
  seed or the service seeds them.

## Deferred to later phases

The dynamic **tag dictionary** (Phase 4.5) · **ReBAC-in-Rego** team-grant join (Phase 7) · batch eval +
partial-eval → JPA `Specification` list filtering (Phase 5) · hierarchical team-target matching (additive,
tied to Phase 5) · multi-role memberships · a `delegate` capability beyond the subset rule.

## Related
- Work breakdown: [[01-DECOMPOSITION]] · Run it: [[AUTONOMOUS-IMPLEMENTATION-PROMPT]] · QA: [[10-QA-TEST-CASES]]
- Index: [[USER-MANAGEMENT-SERVICE]] · Prior slice: [[LIBRARY-SPINE]] · Resource side: [[DOMAIN-MODEL]]
- Roadmap: [[POC-ROADMAP]] · Follow-on: [[RESEARCH-AUTOTAG-AND-FILTERING]] (Phase 4.5)
