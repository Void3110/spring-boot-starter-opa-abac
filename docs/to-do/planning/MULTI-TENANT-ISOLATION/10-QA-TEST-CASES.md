---
tags:
  - status/planned
  - type/project
  - area/abac
  - slice/B4
---

# MULTI-TENANT-ISOLATION — QA test cases

> Concrete cases; each becomes a ticket's *Acceptance*. U = unit, I = integration
> (Testcontainers Postgres — never H2; in-process `com.sun.net.httpserver.HttpServer` OPA/HTTP stub —
> no WireMock), E = e2e (through the gateway; asserts the actual cut — row counts / allow-vs-deny —
> not just response shape). R = `opa test` / `opa eval --partial`.

## Policy (`opa test` / partial-eval) — R*

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| R1 | `catalog.filter`, role-def grants `list`, no required tags | residual **ALLOW_ALL** (`{queries:[[]]}`) — verify `opa eval --partial` | T1 |
| R2 | `catalog.filter`, **no** role-def (subject roles only) | residual **DENY_ALL** (`{}` unsatisfiable) — fail-closed boundary (no subject-roles fallback) | T1 |
| R3 | `catalog.filter`, role **denies** `list` | residual DENY_ALL | T1 |
| R4 | `catalog.allow` view/update/delete, bare `catalog-editor`, no role-def | **deny** (fallback removed) | T1 |
| R5 | `catalog.allow` `create`, bare `catalog-editor`, no role-def | **allow** (narrow create fallback retained) | T1 |
| R6 | `catalog.allow` `create`, bare `catalog-viewer`, no role-def | **deny** (create needs editor) | T1 |
| R7 | `category.granted` / `product.granted` `view`, bare realm role, no role-def | **deny** (fallback removed, both policies) | T1 |
| R8 | `catalog.allow` `view`, **resolved** role granting READ | **allow** (resolved path unchanged) | T1 |
| R9 | Full suite | `opa test` **all green** (count updated for the new cases) | T1 |

## Unit (U*)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| U1 | `HttpGovernedScopeResolver` ← stub `["id1","id2"]` | builds `id IN (id1,id2)` Specification | T2 |
| U2 | stub `[]` (authoritative no-governance) | **always-false** predicate (empty), no throw | T2 |
| U3 | stub 5xx / timeout / connection-refused | **always-false**, **does not throw** (classify-on-outage) | T2 |
| U4 | stub malformed body | always-false, no throw | T2 |
| U5 | `DiscoveryOwnershipResolver`: registry `catalog`→url, stub `{createdBy:S}`, caller=S | **true** | T5 |
| U6 | caller ≠ `createdBy` | **false** | T5 |
| U7 | type with **no** registry entry | **false** (unknown type → fail-closed), no call made | T5 |
| U8 | owning service unreachable / 5xx / `404` | **false** (fail-closed) | T5 |
| U9 | second `isOwner` same `(type,id)` | served from **cache** — no second HTTP call (assert count) | T5 |
| U10 | cache entry past TTL | **re-fetches** (assert second call) | T5 |
| U11 | `EffectiveRoleService.governedTargets(subject,"catalog")` | **distinct** `target_id`s of the subject's catalog-teams; `[]` for unknown subject | T3 |

## Integration (I*) — real Postgres / in-process stubs

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| I1 | catalog list, two subjects with **different** governed sets | each sees only **their** rows (different row sets — residual in SQL) | T4 |
| I2 | catalog list, subject governing **none** | **empty** page (fail-closed, not whole table) | T4 |
| I3 | catalog list, **multi-team** subject (X+Z) | the **union** {X,Z}, exact `count` | T4 |
| I4 | catalog list, `GovernedScopeResolver` bean **absent** | empty page (fail-closed) | T4 |
| I5 | single-GET catalog/category/product by a **non-member** | **403** at every level (fallback removed, all three) | T4 |
| I6 | `createTeam` on a catalog the caller **created** | team created; caller owner | T7 |
| I7 | `createTeam` on a catalog the caller does **not** own | **403** (squatting closed) | T7 |
| I8 | `createTeam`, ownership resolver **cannot verify** (owning service down) | **403** (fail-closed) | T7 |
| I9 | `/internal/bootstrap/teams` (seed path) | **bypasses** the check — still creates | T7 |
| I10 | `GET /internal/catalog/{id}/created-by`, existing catalog | `200 {createdBy:<sub>}` (== creator sub) | T6 |
| I11 | same, missing catalog | `404` (resolver → not-owner) | T6 |
| I12 | `GET /internal/governed-targets?subject&resourceType=catalog` | the subject's governed ids; `[]` for none | T3 |

## e2e (E*) — through the gateway (the isolation matrix)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| E1 | `alice` (fresh, `catalog-editor`, no team) `GET /catalogs` | **[]** | T9 |
| E2 | `alice` `POST /catalogs` → `POST /teams`(her catalog) → `GET /catalogs` | **201, 201, [her catalog]** (self-service works) | T9 |
| E3 | `bob` (fresh) `GET /catalogs` | **[]** | T9 |
| E4 | `alice` adds `bob` → `bob` `GET /catalogs` | **[Alice's catalog]** (scoped access — the team's, not his own) | T9 |
| E5 | `carol` (pre-seeded: own team + member of Alice's) `GET /catalogs` | **2 catalogs** (multi-team) | T9 |
| E6 | `bob` (single team) `GET` Carol's catalog id directly | **403** (no direct-id leak) | T9 |
| E7 | `bob` `POST /teams` with `targetId`=**Alice's** catalog (squat) | **403** (ownership) | T9 |
| E8 | every existing matrix (tag/filter/pagination/hierarchy/resource-resolution/permission-categories/action-enrichment/team) | re-runs **green** | T9 |

## Headline proof

**E2 + E4 + E6 + E7** together: a fresh user self-onboards and sees only her catalog (E2); a member
she adds sees only the team's catalog, not his own and not others' (E4); a single-team user cannot
deep-link another team's catalog (E6); a squat attempt on someone else's catalog is denied (E7). With
**I5** (single-GET 403 at all three levels) and **R2** (the `filter` fail-closed boundary), this is the
whole "membership is the sole access path + safe self-service" claim, proven through the gateway.

## Suite-level

- `./gradlew build` green (all modules + ITs).
- `opa test` green (new count includes the `filter`/`create` cases).
- `opa-abac-core` has **zero** new Spring imports (the SPIs live in spring-data / the security module).
