---
tags:
  - status/done
  - type/project
  - area/user-service
  - area/build
---

# STATUS — Ticket 01: Scaffold example-user-management-service

> Filled in at the ticket-01 checkpoint. See [[01-DECOMPOSITION]] ticket 1.

**Status:** ✅ done

## What shipped

A runnable, empty-but-wired second example module `example-user-management-service`, mirroring the
catalog app's conventions exactly:

- `build.gradle.kts` — Spring Boot 3.4, `org.openapi.generator` (spring, interfaceOnly),
  `implementation(project(":opa-abac-spring-boot-starter"))`, security/web/data-jpa/validation/actuator,
  Liquibase + Postgres runtime, Testcontainers test deps; the same `resolveDockerHost()` +
  `TESTCONTAINERS_RYUK_DISABLED` + `--add-opens` test config; codegen into
  `dev.dmitriikonovalov.example.usermgmt.openapi.{api,model}`.
- `UserManagementApplication` (package `dev.dmitriikonovalov.example.usermgmt`).
- `application.yml` — `ddl-auto: validate`, datasource env (db/user/pass `usermgmt`, host port 5433),
  Liquibase master, actuator health, the same curated endpoint set.
- Liquibase `db.changelog-master.yaml` — **empty include list** (schema lands in T2).
- `openapi/user-mgmt-api.yaml` — a minimal `GET /api/v1/ping` → `Pong` (so codegen has a route);
  plus a reusable `ApiError` schema for later tickets.
- `PingController implements MetaApi` — the one real endpoint in the scaffold.
- Wired into `settings.gradle.kts` (the comment promoting it from "future sibling" to the active
  second app).
- Package skeleton created up front: `config/` · `domain/` · `service/` · `web/` (the **decided**
  layout — one package more than the catalog's flat `config/domain/web`; `service/` is populated from
  T3 onward).

## Tests

- `:example-user-management-service:test` → **green**. `ContextLoadsIT` (Testcontainers
  `postgres:16-alpine`) boots the wired context, Liquibase runs the (empty) changelog, and
  `ddl-auto: validate` passes — proves the scaffold is genuinely runnable, not just compilable.
- `./gradlew build` (whole repo) → **green**: all 4 library modules + **both** example apps + OpenAPI
  codegen + ITs. (S1 ✅ S2 ✅ S3 ✅ from [[10-QA-TEST-CASES]].)

## Architecture review + refactor

Pure scaffold — most review dimensions (fail-closed, hard rules, pluggability) have no surface yet.
The checks that *do* apply:

- **Boundary check** — library public APIs untouched; the new module only *consumes* the starter.
  Confirmed: the diff is module-local + the two-line `settings.gradle.kts` include.
- **Sibling consistency** — every build/test idiom (codegen options, `resolveDockerHost`, add-opens,
  actuator set, `ddl-auto: validate`) is copied from the catalog app verbatim, so the two example
  services read identically. This *is* the decided "keep it consistent / no MapStruct / low-ceremony"
  posture, applied at the scaffold level.

**No refactor applied** — nothing substantive to change in a scaffold; no invented churn. The one
deliberate structural choice (creating the empty `service/` package now) front-loads the decided
layering so T3 has the seam ready.

## Integration / e2e

`ContextLoadsIT` against real Postgres (Testcontainers) is the ticket-1 integration check; it
exercises the actual boot + Liquibase + JPA-validate path. No rig/newman at this ticket (that's T9).

## Decisions recorded

No new durable decision surfaced (the layering decision was already recorded pre-implementation as
`mx-b17da2`). Nothing non-obvious to add to Mulch at the scaffold stage — skipped per the "skip if
nothing non-obvious" rule.

## Commit

`feat(user-mgmt): scaffold example-user-management-service (T1)` — see branch
`feature/void3110/user-management-service`.
