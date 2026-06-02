---
tags:
  - status/planned
  - type/project
  - area/user-service
  - area/abac
---

# user-management-service — QA test cases

> The concrete cases the unit + integration + policy + e2e work in [[01-DECOMPOSITION]] must satisfy.
> Grouped by layer/ticket. Each row names a check; the implementer turns it into a test or a manual step.
> ITs run against **real Postgres via Testcontainers** (never H2), like the catalog app.

## Scaffold — ticket 1

| # | Case | Expected |
|---|------|----------|
| S1 | `:example-user-management-service:build` | green; the Spring context loads (empty), `ddl-auto: validate` clean. |
| S2 | whole-repo `./gradlew build` | still green with the new module wired into `settings.gradle.kts`. |
| S3 | OpenAPI codegen runs | the `…usermgmt.openapi.{api,model}` packages generate; compile clean. |

## Core domain + seed — ticket 2

| # | Case | Expected |
|---|------|----------|
| D1 | Liquibase applies `0001` (+ base columns) | schema present; `ddl-auto: validate` boots clean. |
| D2 | System roles seeded | exactly `owner`/`administrator`/`member`/`viewer` exist with `system=true`, `teamId=null`, stable codes. |
| D3 | `permissions` / `attributes` JSONB round-trip | a `RoleDefinition` with `{catalog:[read,write]}` persists + reloads intact. |
| D4 | `TeamMembership` unique `(teamId,userId)` | a second membership for the same pair → constraint violation. |
| D5 | User/Team read CRUD | create/list/get users; list/get teams. |

## Owner-on-create — ticket 3

| # | Case | Expected |
|---|------|----------|
| O1 | Create team-target | exactly one `Team` + one `owner` `TeamMembership` for the creator, in one transaction. |
| O2 | Forced failure mid-create | **nothing** persisted (full rollback — no orphan team, no grant-less target). |
| O3 | Creator role | the creator immediately resolves as `owner` (re-checked in R*). |
| O4 | One team per target | a second team for the same `(targetType,targetId)` is rejected (or per the documented policy). |

## Team-management API — ticket 4

| # | Case | Expected |
|---|------|----------|
| M1 | Owner adds a member with `member` | 200/201; the membership exists with the bound role. |
| M2 | Owner/admin removes a member | 204; the membership is gone (revocation — see R5). |
| M3 | Owner changes a member's role | the membership's `roleDefinitionId` updates. |
| M4 | Member/viewer attempts manage | **403** (`@OpaPreAuthorize` deny). |
| M5 | **Subset rule:** assign a role exceeding the actor's own perms | **denied** (403/422). |
| M6 | Administrator manages within own perms | allowed; cannot exceed own perms. |
| M7 | **Authorize the actor** | the decision uses the caller's team membership, not the service identity (assert via a caller without rights → denied even though the service could). |
| P1 | `opa test team.rego` | manage allowed for owner/administrator; denied for member/viewer; default deny. |

## Role-def management API — ticket 5

| # | Case | Expected |
|---|------|----------|
| R1 | Owner creates a team-scoped custom role | `teamId` set, `system=false`; appears in the team's role list. |
| R2 | Custom role exceeding owner's perms | **denied** (subset guard). |
| R3 | Edit/delete a `system` role | **denied** (409/403 — system roles immutable). |
| R4 | Owner updates/deletes a custom role | succeeds; not assignable after delete. |
| R5 | Custom role assignable + visible | a member assigned the custom role resolves with its custom permissions (ties to the resolve API). |

## Transfer-ownership — ticket 6

| # | Case | Expected |
|---|------|----------|
| T1 | Owner transfers to a member | new owner resolves as `owner`; old owner downgraded to `administrator`; atomic. |
| T2 | Administrator attempts transfer | **403** (owner-only). |
| T3 | Transfer to a non-member | handled per the documented choice (added as owner, or rejected). |

## Effective-role resolve API — ticket 7

| # | Case | Expected |
|---|------|----------|
| E1 | Owner resolves | `200 {RoleDefinition owner}` for the matching team-target. |
| E2 | Member with `viewer` resolves | `200 {viewer}` (read-only perms). |
| E3 | Member with a custom editor role resolves | `200 {custom role}` with its permissions. |
| E4 | User with no matching team | `204`/empty (not an error). |
| E5 | Removed member resolves | empty — **revocation propagates** (membership is the source of truth). |
| E6 | Wire shape | the returned JSON matches `core.RoleDefinition` exactly (`code`/`attributes`/`permissions`). |
| E7 | `TeamTargetMatcher` exact match | a team-target on a different resource does **not** resolve. |

## Catalog adoption — `HttpRoleDefinitionSupplier` — ticket 8

| # | Case | Expected |
|---|------|----------|
| H1 | Resolve round-trip (stub `HttpServer`) | a `200 {RoleDefinition}` becomes `Optional.of(...)`; the role reaches the OPA input (as in Phase 3). |
| H2 | No-match (`204`/empty) | `Optional.empty()` → policy default-denies. |
| H3 | Fail-closed: 500 / timeout / refused / malformed | `Optional.empty()` (never throws, never allows). |
| H4 | Request URL shape | `GET …/internal/effective-role?userId&resourceType&resourceId` with the right params. |
| H5 | Profile switch | `catalog.role-source=demo` keeps the demo supplier; `=http` uses the HTTP one. |
| H6 | Existing catalog ITs | stay green under the default/permissive profile (unchanged). |

## E2E — through the gateway, two services — ticket 9

| # | Case | Expected |
|---|------|----------|
| X1 | Owner of a catalog writes | `201/200` — role `owner` resolved from the user-service. |
| X2 | Member with `viewer` writes | **403** — resolved `viewer` lacks write. |
| X3 | Member with a team-scoped custom editor writes | `200` — custom role grants write. |
| X4 | Non-member writes | **403** — no matching team → empty role → default deny. |
| X5 | Reads | owner/member read `200`. |
| X6 | User-service own management API | owner manages `200`; a member `403` (dogfooding). |
| X7 | Tokens minted in-network; stable across reruns | green twice; chained ids in collection scope. |

## Cross-cutting

| # | Case | Expected |
|---|------|----------|
| C1 | `./gradlew build` | all library modules + **both** example apps + codegen + ITs green. |
| C2 | Library public API | **unchanged** (HTTP supplier is app code; the SPI is consumed, not modified). |
| C3 | Clean-room scan of the diff | no proprietary names/paths/ticket-ids. |
| C4 | Fail-closed | every resolve failure denies (HTTP supplier empty / OPA default-deny). |
| C5 | Hard rules | owner-on-create atomic; subset rule blocks escalation; transfer works; removal revokes; actor authorized. |
| C6 | `ddl-auto: validate` | clean boot for the new service (Liquibase owns the schema). |
