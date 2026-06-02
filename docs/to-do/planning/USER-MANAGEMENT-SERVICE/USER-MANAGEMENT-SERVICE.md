---
tags:
  - status/planned
  - type/project
  - area/user-service
  - area/abac
---

# user-management-service (example)

> **Status:** Planning — **direction decided** (teams + role-defs core, app-resolved authorization,
> fixed system roles + team-scoped custom roles); not yet decomposed into tickets. Part of the
> [[POC-ROADMAP]] (Phase 4). The **team abstraction** is the centerpiece of this phase. The dynamic tag
> dictionary is split to Phase 4.5; a ReBAC-in-Rego demonstration is a new Phase 7.

## Purpose

The second example application. Where `example-catalog-management-service` is the **resource**
side of the demo, this service is the **subject / attribute** side: it owns the identities, **teams**,
**role definitions**, and **grants** that ABAC decisions are made *about* and *with*.

It exists to answer, for the demo: *"who is asking, what teams are they on, what role does that give
them on this resource?"* — i.e. it resolves the caller's **effective role for a resource** and feeds it
to the catalog spine's `RoleDefinitionSupplier` (the HTTP-backed implementation that replaces the
Phase-3 demo one — a single-bean swap).

This is the part that turns the PoC from "OPA says yes/no on a hardcoded input" into "a real
authorization decision driven by live team membership and role definitions."

## The team abstraction (the centerpiece — decided)

A user creates a resource (a catalog/product) → the resource is linked to a **team target** → the
creator becomes the **owner** → the owner manages a **team** and grants access to the resource via team
membership. Researched against AWS / Heroku / GitHub / GCP / Kubernetes / OpenFGA (Mulch reference
`team-based resource access models`); this is the GitHub/Heroku "team grants access to a resource"
pattern, with GCP's binding vocabulary and Kubernetes' anti-escalation rule.

### The core distinction: **role ≠ grant**
Every platform separates these, and conflating them causes *role explosion*. We model them separately:

- **`RoleDefinition`** — a reusable *named permission set* (`code` + `permissions{resourceType:[verbs]}`
  + attributes). Already exists in `opa-abac-core` from [[LIBRARY-SPINE]]. Two kinds:
  - **system roles** (immutable, seeded): `owner`, `administrator`, `member`, `viewer`;
  - **team-scoped custom roles** (owner-defined, live in the DB, scoped to a team).
- **`Grant` / `Binding`** — `{principal × roleDefinition × scope}` where scope is a team (and, through
  the team, the team-target resource). This is what gives a role *meaning in context*. Borrow GCP's
  vocabulary: principal · role · binding.

> "Team-scoped" means the **binding's scope** is the team — *not* a new role per team. One role
> definition serves many teams via many bindings.

### Entities (first cut)

| Entity | Shape | Notes |
|--------|-------|-------|
| **User** | id, display name, IdP subject (`sub`) | Profile + attributes; Keycloak still authenticates. |
| **Team** | id, name, **team-target** (the owned resource ref: type + id) | The durable owner of the resource (resource→team indirection). |
| **TeamMembership** | (team, user, roleDefinition) | The join *carries the role* — membership = a grant scoped to the team. |
| **RoleDefinition** | code, attributes, permissions{type:[verbs]}, `system` flag, nullable `teamId` | System (global, immutable) or team-scoped (custom). |

### System roles & semantics
- **owner** — created **with the team-target**, atomically, for the creating user (owner-on-create).
  Can manage membership, define team-scoped custom roles, assign roles, and transfer ownership.
- **administrator** — manage membership + assign existing roles; cannot transfer ownership.
- **member** — read + the write verbs their assigned role grants on the team-target.
- **viewer** — read-only.

### The hard rules (from the research — these are teaching points)
- **Owner-on-create (bootstrap):** create resource + create team-target + write the owner grant in **one
  transaction**; always keep an admin fallback so a resource is never grant-less.
- **No self-escalation (the Kubernetes subset rule):** an owner/admin may only assign a role whose
  permission set is a **subset** of their own, unless they hold an explicit `delegate` capability.
- **Transfer-ownership is first-class:** new owner gets the owner role immediately; old owner is
  downgraded (GitHub-style). Prevents orphaned resources when an owner leaves.
- **Revocation = membership is the single source of truth:** removing a member revokes all access
  derived through the team; the resolver always re-derives, no stale denormalized grants.
- **Authorize the actor of a grant, not the service identity** (confused-deputy guard).

## What it mirrors (and how we generalize)

It mirrors the *shape* of the source platform's user/role model — but generalized and made
Spring-native, per the [[POC-ROADMAP]] thesis and the root `CLAUDE.md` IP boundary. No source,
names, or docs are copied; we reference the prior system only as "the source platform."

| Concept | What the demo needs | Generalization note |
|---------|--------------------|---------------------|
| **Users** | Identities (id, display name, link to the IdP subject). | Authentication is Keycloak's job; this service holds the *profile + attributes*, not credentials. |
| **Teams** | A team owns a resource (its **team-target**) and groups its members. | Resource→team indirection: the team is the durable owner, an owner *role* sits on a person. |
| **Membership (grant)** | `(team, user, roleDefinition)` — the join **carries the role**. | This is the **grant/binding** (principal × role × team scope); the place "team-scoped" auth happens. |
| **Role definitions** | Named roles → permissions; **system** (fixed) + **team-scoped custom**. | **Data-driven**, not hardcoded enums. `role ≠ grant`: one role definition, many bindings. |
| **Tag dictionary** *(Phase 4.5, not this slice)* | A **dynamic** dictionary of tags as subject/resource attributes. | The source platform hardcodes tags; here it's a runtime-editable entity. Split out so the team/role core ships first. |

## How it feeds ABAC (the integration point — decided: app-resolved)

**Decided:** the **app-resolved** path (mirrors the source platform). The catalog app's HTTP-backed
`RoleDefinitionSupplier` calls this service to resolve the caller's **effective `RoleDefinition` for a
specific resource** — the service walks the caller's team memberships server-side, finds the team whose
team-target is (or is an ancestor of) the resource, and returns the bound role definition. The catalog
spine then puts that `role_definition` into the OPA `input` exactly as it does today with the demo
supplier. **A single-bean swap on the catalog side; no wire-contract change** (the SPI was built for
this in [[LIBRARY-SPINE]]).

```
catalog @OpaPreAuthorize
   → HttpRoleDefinitionSupplier.lookup(userId, resourceType, resourceId)
        → GET user-mgmt /effective-role?userId&resourceType&resourceId
             (server-side: user → memberships → team(matching team-target) → bound RoleDefinition)
   → AbacContext(subject, action, resource, role_definition, env) → OPA decides on permissions
```

Alternatives (documented, not built here):
- **Token claims** — push teams/roles into the JWT; simplest but token-size/freshness bound.
- **ReBAC-in-Rego** — push the team/membership/grant graph into OPA `data` and do the
  "member-of team ∧ team has-role-on resource" **join in the policy** (Zanzibar userset). The strongest
  portfolio piece; **deferred to Phase 7** so the app-resolved path ships first.

> **Why app-resolved first:** it reuses the Phase-3 spine untouched (the `RoleDefinitionSupplier`
> contract already returns a `RoleDefinition`), keeps the OPA input explicit and identical to today, and
> matches the proven source-platform design. ReBAC is the more elegant end state but a bigger lift.

## Boundaries

- **Unpublished**, like the catalog example. It's PoC infrastructure, not a shippable artifact.
- **Not** a general-purpose identity service — it does only what the demo needs to make
  authorization decisions interesting. Keycloak remains the IdP.
- Same stack as the catalog app for consistency: Java 21 · Spring Boot 3.4 · Postgres +
  Liquibase · OpenAPI codegen · Testcontainers ITs.

## Build sequence (decided — core first, build teams + role-defs together)

Role-defs and teams are the **core** and are designed/built **together** (one slice), then team
management + role-def management layer on top. The dynamic tag dictionary is **not** in this slice
(Phase 4.5). Rough order — to be turned into a ticketed decomposition + an autonomous prompt like the
prior slices:

1. **Scaffold** `example-user-management-service` as a flat root module (sibling of the catalog app);
   wire into `settings.gradle.kts`; Postgres + Liquibase baseline; health/actuator; adopt the
   `opa-abac-spring-data` base-entity stack.
2. **Core domain — teams + role-defs (together):** `User`, `Team` (with team-target), `TeamMembership`
   (carrying a `RoleDefinition`), `RoleDefinition` (system + team-scoped) — schema (Liquibase), entities,
   repositories. Seed the immutable **system roles**.
3. **Owner-on-create:** a "create team-target" operation that atomically creates the team + writes the
   **owner** membership for the creating user (one transaction). Admin fallback.
4. **Team management API** (owner/admin): add/remove/update team members; assign a role to a member.
   Enforce the **no-self-escalation subset rule** and authorize the **actor**.
5. **Role-def management API** (owner): create/update/delete **team-scoped custom** role definitions
   (subset-of-own-permissions guard); list system + team roles.
6. **Transfer-ownership** operation (new owner → owner role; old owner downgraded).
7. **Effective-role read API:** `GET /effective-role?userId&resourceType&resourceId` — walks
   memberships → team(matching team-target) → bound `RoleDefinition`. The contract the catalog's
   `HttpRoleDefinitionSupplier` consumes. Cacheable; membership is the source of truth.
8. **Wire the catalog side:** ship `HttpRoleDefinitionSupplier` in the catalog app (or as an opt-in
   starter bean) that calls (7) and overrides the demo supplier — the single-bean swap. Keep the demo
   supplier as a fallback/profile.
9. **e2e + a guide:** extend the rig (a second app pod-set or service) so a catalog request resolves a
   real role via team membership; an allow/deny matrix proving owner/admin/member/viewer + a custom role
   through the gateway; a docs guide on the team model + the app-resolved path.

## Open questions (resolve at decomposition)

- Does this service expose a **public gateway route**, or is it **internal-only** (called by the catalog
  service as an attribute source)? Leaning **internal-only** for the resolve API, with a public
  (gateway-fronted) surface for the team/role *management* endpoints (which themselves need ABAC — the
  service secures its own management API with the starter, dogfooding it).
- **Resource→team matching:** exact match (team-target == resource) only, or walk the catalog hierarchy
  (team-target on a Catalog grants on its Categories/Products via [[DOMAIN-MODEL]] ancestry)? The latter
  ties into Phase-5 hierarchical authorization — keep the resolver pluggable so we can start exact and
  add ancestry.
- **System-role seeding & immutability:** Liquibase seed vs. bootstrap-on-startup; how hard to enforce
  "system roles can't be edited."
- Does **transfer-ownership** need an accept step, or is it a direct owner action for the demo?

## This package (design → autonomous-implement → track)

This folder is the full work package for Phase 4, written to be **implemented autonomously** — the
same shape as the shipped [[LIBRARY-SPINE]] slice. The design, the work breakdown, and a self-contained
[[AUTONOMOUS-IMPLEMENTATION-PROMPT]] are all here.

| File | Role |
|------|------|
| `USER-MANAGEMENT-SERVICE.md` | This note — the index: purpose, the team abstraction, the integration point, the build sequence. |
| [`00-DESIGN.md`](00-DESIGN.md) | The design: the entity model (`role ≠ grant`), system + team-scoped custom roles, owner-on-create, transfer-ownership, the no-self-escalation rule, the effective-role resolve API, the app-resolved `HttpRoleDefinitionSupplier`, dogfooding the starter, considered-&-rejected. |
| [`01-DECOMPOSITION.md`](01-DECOMPOSITION.md) | The ordered tickets — each Goal / Deliverables / Acceptance / What-NOT-to-touch. **The implementer's work list.** |
| [`AUTONOMOUS-IMPLEMENTATION-PROMPT.md`](AUTONOMOUS-IMPLEMENTATION-PROMPT.md) | Self-contained prompt to implement this package autonomously, ticket by ticket, with a review gate + checkpoints. |
| [`10-QA-TEST-CASES.md`](10-QA-TEST-CASES.md) | The unit / integration / policy / e2e cases the work must satisfy. |
| `STATUS-01.md` … `STATUS-09.md` | One per ticket — filled in at each checkpoint during the run. |
| [`RESEARCH-AUTOTAG-AND-FILTERING.md`](RESEARCH-AUTOTAG-AND-FILTERING.md) | Carried-over study notes for the **Phase-4.5** tag dictionary + the Phase-5 list filtering (not this slice). |

### Tickets (status)

| # | Ticket | Status | Note |
|---|--------|--------|------|
| 1 | Scaffold `example-user-management-service` (module, Postgres, Liquibase, base-entity stack) | ✅ done | `STATUS-01.md` |
| 2 | Core domain — `User` / `Team` / `TeamMembership` / `RoleDefinition` (system + team-scoped), seed system roles | ✅ done | `STATUS-02.md` |
| 3 | Owner-on-create (atomic team-target + owner membership) | ☐ planned | `STATUS-03.md` |
| 4 | Team-management API (membership add/remove/update + assign role; subset rule; authorize the actor) | ☐ planned | `STATUS-04.md` |
| 5 | Role-def management API (team-scoped custom roles; subset-of-own guard) | ☐ planned | `STATUS-05.md` |
| 6 | Transfer-ownership | ☐ planned | `STATUS-06.md` |
| 7 | Effective-role resolve API (`/effective-role`) | ☐ planned | `STATUS-07.md` |
| 8 | Catalog adoption — `HttpRoleDefinitionSupplier` swaps the demo one | ☐ planned | `STATUS-08.md` |
| 9 | Infra (second service in the rig) + e2e matrix + docs/roadmap/Mulch | ☐ planned | `STATUS-09.md` |

### Workflow-as-artifact
Like the prior slices, the verbatim [[AUTONOMOUS-IMPLEMENTATION-PROMPT]] + the `STATUS-0N.md` notes are
**deliberate deliverables** — a studyable record of the plan→autonomous-implement→test→review workflow.
On ship the folder moves to `docs/to-do/implemented/` with a "Shipped" banner, alongside
[[DOMAIN-MODEL-FOUNDATION]] and [[LIBRARY-SPINE]].

## Related

- Overall roadmap: [[POC-ROADMAP]]
- Resource-side counterpart: `example-catalog-management-service` (the catalog app this service feeds).
- Prior slice this builds on: [[LIBRARY-SPINE]] — ships `RoleDefinition` + the `RoleDefinitionSupplier`
  SPI (demo supplier); this service provides the real HTTP-backed supplier (a single-bean swap).
- Cross-platform team-access research (AWS/Heroku/GitHub/GCP/K8s/OpenFGA): Mulch reference
  *"Team-based resource access models across cloud platforms"* — the basis for the entity model + the
  hard rules.
- Follow-on: [[RESEARCH-AUTOTAG-AND-FILTERING]] — the **Phase-4.5** dynamic tag dictionary + the Phase-5
  list filtering (not this slice).
- IP boundary: root `CLAUDE.md` → "IP Boundary".
