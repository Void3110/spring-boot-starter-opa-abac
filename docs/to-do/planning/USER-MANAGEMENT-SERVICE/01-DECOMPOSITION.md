---
tags:
  - status/planned
  - type/project
  - area/user-service
  - area/abac
---

# user-management-service — decomposition

> The ordered work list. Each ticket is one focused commit. Rationale is in [[00-DESIGN]]; the cases are
> in [[10-QA-TEST-CASES]]. **This is the implementer's work list.**

New app package `dev.dmitriikonovalov.example.usermgmt`. Clean-room: original names only. App-resolved
authorization (no ReBAC-in-Rego, no tag dictionary in this slice). `opa-abac-core`/`-spring-security`/
`-spring-data`/`-starter` public APIs are **frozen** — this slice consumes them, it does not change them.

Critical path **T1 → T2 → T3 → {T4, T5} → T6 → T7 → T8 → T9**. T4 and T5 are parallelizable once T3
lands. T1–T7 build + validate the service in isolation (no rig); T8 wires the catalog; T9 is the rig + e2e.

---

## Ticket 1 — Scaffold `example-user-management-service`

**Goal:** a runnable, empty-but-wired second example service, same conventions as the catalog app.

**Deliverables**
- New flat root module `example-user-management-service`; add to `settings.gradle.kts`.
- `build.gradle.kts` mirroring the catalog app: Spring Boot 3.4, `org.openapi.generator` (spring,
  interfaceOnly), `implementation(project(":opa-abac-spring-boot-starter"))`,
  `spring-boot-starter-security`/`-web`/`-data-jpa`/`-validation`/`-actuator`, Liquibase, Postgres
  runtime, Testcontainers test deps, the same `resolveDockerHost()` + `--add-opens` test config.
- `UserManagementApplication` (`dev.dmitriikonovalov.example.usermgmt`), `application.yml`
  (`ddl-auto: validate`, datasource env, Liquibase changelog, actuator health), Liquibase
  `db.changelog-master.yaml` (empty include list to start).
- A minimal OpenAPI spec `openapi/user-mgmt-api.yaml` (health/ping or an empty paths stub) so codegen
  runs; package `…usermgmt.openapi.{api,model}`.
- Adopt the `opa-abac-spring-data` base stack on the classpath (no entities yet).

**Acceptance**
- `./gradlew :example-user-management-service:build` green (boots an empty context against Testcontainers
  Postgres if a smoke IT is added; otherwise compiles + the app context loads).
- `./gradlew build` (whole repo) still green.

**What NOT to touch**
- Library modules; the catalog app; infra/rig (that's T9). No domain entities yet.

---

## Ticket 2 — Core domain: User / Team / TeamMembership / RoleDefinition (+ seed system roles)

**Goal:** the team + role-def core, designed and built **together** (per the decided direction).

**Deliverables**
- Entities (extending the appropriate `opa-abac-spring-data` base; plain `UUID` ids; OpenAPI DTOs):
  - `User(id, subject, displayName)`;
  - `Team(id, name, targetType, targetId)`;
  - `RoleDefinition(id, code, system, teamId?, attributes jsonb, permissions jsonb)` — `permissions` uses
    the `{resourceType:[verbs]}` shape the OPA policy reads;
  - `TeamMembership(id, teamId, userId, roleDefinitionId)`, unique `(teamId, userId)`.
- Liquibase changelog `0001-create-usermgmt-schema.yaml` (+ `0002` base-entity columns if the base stack
  needs them) — real Postgres types, JSONB for `attributes`/`permissions`, the unique constraint, FKs.
- **Seed the system roles** (`owner`/`administrator`/`member`/`viewer`, `system=true`, `teamId=null`) via
  a Liquibase data changeset with stable UUIDs/codes.
- Repositories + read-only CRUD controllers for `User` (create/list/get) and `Team` (list/get) — enough
  to exercise persistence. (Team *creation* is T3; membership/role-def management are T4/T5.)
- A mapper layer (entity ↔ OpenAPI DTO) like the catalog `CatalogMapper`.

**Acceptance**
- `./gradlew :example-user-management-service:build` green; `ddl-auto: validate` boots clean against the
  seeded schema (Testcontainers Postgres).
- System roles present after Liquibase runs (an IT asserts the four seeded codes, `system=true`).
- `permissions`/`attributes` round-trip through JSONB.

**What NOT to touch**
- Management/grant logic (T3–T6); the resolve API (T7); catalog/infra. No security annotations yet (the
  service's own ABAC is wired in T4 once there's something to authorize).

---

## Ticket 3 — Owner-on-create (atomic team-target + owner membership)

**Goal:** creating a team-target is one transaction and never leaves a grant-less resource.

**Deliverables**
- `POST /api/v1/teams` (or `/team-targets`): body = `{name, targetType, targetId}` + the creating
  user (from the authenticated subject). In **one `@Transactional`**: create the `Team`, then create the
  `owner` `TeamMembership` for the creator (resolve the seeded `owner` `RoleDefinition`).
- A `TeamService.createWithOwner(creatorUserId, name, targetType, targetId)` that encapsulates the atomic
  bootstrap; rolls back fully on any failure (no orphan team, no grant-less target).
- Idempotency/uniqueness guard: one team per `(targetType,targetId)` (or document the chosen policy).

**Acceptance**
- IT: creating a team-target yields exactly one `Team` + one `owner` membership for the creator, in one
  transaction; a forced failure mid-create leaves **nothing** persisted.
- The creator immediately resolves as `owner` (verified again in T7).

**What NOT to touch**
- Membership *management* by others (T4); role-def management (T5); transfer (T6).

---

## Ticket 4 — Team-management API (membership; subset rule; authorize the actor)

**Goal:** owners/admins manage who is on a team and what role they hold — safely.

**Deliverables**
- Endpoints (under a team): `POST` add member `{userId, roleCode}`, `DELETE` remove member,
  `PUT`/`PATCH` change a member's role, `GET` list members.
- **Secure the service's own management API with the starter:** wire `SecurityConfig` (+ `AbacFilter`),
  a `RoleDefinitionSupplier` bean that resolves the *caller's* role **on the team being managed**, and
  annotate the endpoints `@OpaPreAuthorize(action="team:manage", resourceType="'team'", resourceId="#teamId")`.
  Author a `team.rego` policy (via `/rego-skill`) that allows manage for owner/administrator.
- **No-self-escalation subset rule:** assigning a role is rejected (403/422) unless the role's
  `permissions` are a **subset** of the actor's own effective permissions on that team. A
  `PermissionSubset` check used here and in T5.
- **Authorize the actor:** the decision uses the calling subject's membership, never the service identity.

**Acceptance**
- ITs: owner adds/removes/updates members; administrator can manage but cannot exceed own perms; member/
  viewer get 403 on manage; assigning a superset role → denied (subset rule); the actor's identity (not
  the service's) drives the decision.
- `opa test` green for `team.rego`.

**What NOT to touch**
- Role-def *creation* (T5 — though the subset helper is shared); transfer (T6); the catalog.

---

## Ticket 5 — Role-def management API (team-scoped custom roles)

**Goal:** an owner defines custom roles scoped to a team, within the subset rule.

**Deliverables**
- Endpoints: `POST` create a team-scoped `RoleDefinition` `{code, attributes, permissions}` with
  `teamId` set + `system=false`; `PUT` update; `DELETE`; `GET` list (system + this team's custom roles).
- **System roles are immutable:** update/delete of a `system=true` role → 409/403.
- **Subset-of-own guard (reuse T4's `PermissionSubset`):** a custom role's `permissions` cannot exceed
  the creator's own effective permissions on the team.
- `@OpaPreAuthorize(action="roledef:write", resourceType="'team'", resourceId="#teamId")` — owner only
  (admins manage *assignments*, owners define *roles* — match the system-role table in [[00-DESIGN]]).

**Acceptance**
- ITs: owner creates/updates/deletes a team-scoped role; a custom role exceeding own perms → denied;
  editing a system role → denied; a custom role is assignable to members (via T4) and shows up in the
  resolve API (T7).

**What NOT to touch**
- Membership endpoints (T4); transfer (T6); catalog/infra.

---

## Ticket 6 — Transfer-ownership

**Goal:** ownership is reassignable so a resource is never orphaned.

**Deliverables**
- `POST /api/v1/teams/{teamId}/transfer-ownership {newOwnerUserId}` — in one transaction: the new owner's
  membership becomes `owner`; the previous owner is downgraded to `administrator`. (Direct owner action
  for the demo; an accept-step is noted as a future option.)
- `@OpaPreAuthorize(action="team:transfer-ownership", …)` — **owner only** (administrators cannot).
- Guard: the new owner must already be a team member (or is added as `owner` — document the choice).

**Acceptance**
- IT: owner transfers → new owner resolves as `owner`, old owner as `administrator`; an administrator
  attempting transfer → 403; the operation is atomic.

**What NOT to touch**
- The resolve API (T7); catalog/infra.

---

## Ticket 7 — Effective-role resolve API

**Goal:** the contract the catalog's `HttpRoleDefinitionSupplier` consumes.

**Deliverables**
- `GET /internal/effective-role?userId&resourceType&resourceId` → `200 {RoleDefinition}` (the
  `core`-shaped `{code, attributes, permissions}`) or `204`/empty when the user has no role on a team
  whose team-target matches the resource.
- Server-side resolution: `user → memberships → team where TeamTargetMatcher matches (resourceType,
  resourceId) → the membership's bound RoleDefinition`. A `TeamTargetMatcher` SPI with an **exact-match**
  default (hierarchy walking is a later, additive matcher — keep the seam).
- **Internal-only** (not gateway-fronted); cacheable; membership is the single source of truth (no stale
  denormalized grants — always re-derive).

**Acceptance**
- ITs: owner/admin/member/viewer each resolve to the expected `RoleDefinition`; a user with no matching
  team → empty; a removed member → empty (revocation propagates); a team-scoped custom role resolves with
  its custom permissions; the returned JSON matches the `core.RoleDefinition` wire shape exactly.

**What NOT to touch**
- The catalog side (T8); the rego/policy of the catalog; infra.

---

## Ticket 8 — Catalog adoption: `HttpRoleDefinitionSupplier` swaps the demo one

**Goal:** the catalog resolves real roles via the user-service — a single-bean swap.

**Deliverables**
- In the **catalog app**: `HttpRoleDefinitionSupplier implements RoleDefinitionSupplier` — calls
  `GET …/internal/effective-role`; returns `Optional<RoleDefinition>`; **fails closed** (non-200 /
  timeout / parse error / connection refused → `Optional.empty()` → policy default-denies). Base URL +
  timeout via properties.
- Wire it as the active `RoleDefinitionSupplier` `@Bean` (overrides `DemoRoleDefinitionSupplier`),
  selectable by property/profile (`catalog.role-source=http|demo`) so the demo path stays available.
- Unit tests with an in-process `HttpServer` stub (no WireMock), mirroring `HttpOpaClientTest`:
  allow/deny round-trip, all fail-closed paths, the request URL shape.

**Acceptance**
- `./gradlew :example-catalog-management-service:build` green; existing catalog ITs stay green under the
  default (demo/permissive) profile.
- With the HTTP profile + a stub user-service, a catalog request resolves a role from the supplier (unit
  level); the full path is proven in T9.

**What NOT to touch**
- The library SPI (frozen); the OPA input shape; the user-service.

---

## Ticket 9 — Infra (second service) + e2e matrix + docs/roadmap/Mulch

**Goal:** the full loop — a catalog request authorized by a role resolved from real team membership —
proven through the rig; docs tell the team story; roadmap + Mulch updated.

**Deliverables**
- **Infra:** run the user-management-service in the rig alongside the catalog pool — its own container +
  Postgres DB/schema; `deploy.sh`/compose extended; the catalog pods get
  `CATALOG_ROLE_SOURCE=http` + the user-service base URL (in-network). Seed demo users + a team + a
  team-target so the matrix has data (Liquibase seed or a bootstrap call).
- **e2e (Postman/newman):** mint tokens for the seeded users; a matrix proving the **app-resolved** path:
  the owner of a catalog can write; a member with `viewer` cannot; a member with a team-scoped custom
  *editor* role can write; a non-member is denied — all through the gateway, the role coming from the
  user-service. Reuse the in-network token + collection-scope-ids conventions (mx-ecc3ef, mx-05b2c1).
  A small matrix for the user-service's **own** management API (owner manages, member 403).
- **Docs:** a guide `docs/guides/TEAM-BASED-AUTHORIZATION.md` (the team model: role≠grant, owner-on-create,
  transfer, subset rule, the app-resolved resolve API, dogfooding). Reconcile `infra/README.md`,
  `docs/guides/E2E-TESTING.md`, and `POC-ROADMAP.md` (Phase 4 done; 4.5 tag-dict + Phase-7 ReBAC next).
- **Move** `USER-MANAGEMENT-SERVICE/` → `docs/to-do/implemented/` with a "Shipped" banner.
- **Mulch:** record durable insights (app-resolved RoleDefinitionSupplier; role≠grant + team-membership
  binding; owner-on-create atomic bootstrap; the subset/no-escalation enforcement in `@OpaPreAuthorize`;
  the dogfooding pattern; the second-service rig wiring). `ml sync` (`.mulch`-only); `ml doctor` clean.

**Acceptance**
- Rig up → the e2e matrix green (owner writes, viewer denied, custom-editor writes, non-member denied)
  through the gateway with roles resolved from the user-service; stable across reruns.
- `./gradlew build` green; `opa test` green; docs/roadmap/Mulch updated; **clean-room scan clean**. **No push.**

**What NOT to touch**
- The library public APIs; batch/partial; the tag dictionary; ReBAC-in-Rego.

---

## Cross-cutting acceptance (the whole slice)

- `./gradlew build` green: all library modules + **both** example apps + OpenAPI codegen + ITs.
- The library public API is **unchanged** (this slice consumes the `RoleDefinitionSupplier` SPI; the
  HTTP supplier is app code).
- **Fail-closed** holds end to end: the catalog's `HttpRoleDefinitionSupplier` denies on any resolve
  failure; the resolve API returns empty (not an error) for no-match; OPA default-denies.
- The **hard rules** are enforced and tested: owner-on-create is atomic, the subset rule blocks
  escalation, transfer-ownership works, removing a member revokes access, the *actor* is authorized.
- `ddl-auto: validate` boots clean for the new service (Liquibase owns the schema).
- e2e matrix green through the gateway with roles from real team membership; docs (the new guide +
  roadmap), Mulch, and the `STATUS-0N.md` notes updated; clean-room scan clean. **Nothing pushed.**
