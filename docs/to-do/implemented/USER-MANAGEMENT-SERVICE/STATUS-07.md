---
tags:
  - status/done
  - type/project
  - area/user-service
  - area/abac
---

# STATUS — Ticket 07: Effective-role resolve API

> Filled in at the ticket-07 checkpoint. See [[01-DECOMPOSITION]] ticket 7.

**Status:** ✅ done

## What shipped

The effective-role resolve API — the contract the catalog's `HttpRoleDefinitionSupplier` consumes
(T8). The user-service is now complete in isolation (T1–T7).

- `GET /internal/effective-role?userId&resourceType&resourceId` (`InternalResolveController`,
  hand-written — an internal contract, not the public API) → `200 {core.RoleDefinition}` or **204**
  (empty) on no-match. `userId` is the IdP **subject** the catalog forwards.
- `EffectiveRoleService.resolveForResource(subject, resourceType, resourceId)` — walks
  `subject → user → memberships → team matched by the TeamTargetMatcher → bound role`, returning the
  **resource** projection (`"*"` expanded to the resource type). Always re-derived from live membership.
- `TeamTargetMatcher` SPI + `ExactTeamTargetMatcher` default (exact `(type, id)`); the hierarchy-walking
  matcher is an additive Phase-5 swap — the seam is built now.

## Tests

`:example-user-management-service:test` → **green (35 total; +7 this ticket)**. `EffectiveRoleResolveIT`
(real Postgres, the internal API):
- **E1** owner → `{owner, catalog:[read,write]}` (the `"*"` system perm expanded to the team-target type);
- **E2** viewer → `{catalog:[read]}`; **E3** a team-scoped custom editor → its `{catalog:[read,write]}`;
- **E4** no matching team → **204** (empty, not an error);
- **E5** a **removed** member → **204** — revocation propagates (membership is the source of truth);
- **E7** the **exact matcher** does not resolve a different resource id or a different resource type;
- **E6** the returned JSON matches the `core.RoleDefinition` wire shape exactly (`code`/`attributes`/
  `permissions`, with `permissions.catalog` present).

`./gradlew build` (whole repo) → green; catalog ITs unaffected.

## Architecture review + refactor

- **Fail-closed** ✅ — `204` (not an error) on no-match → the catalog supplier maps it to
  `Optional.empty()` → the policy default-denies.
- **Revocation propagates** ✅ — E5; always re-derives, no stale denormalized grants.
- **Wire shape** ✅ — raw `core.RoleDefinition` returned (E6).
- **Pluggability** ✅ — `TeamTargetMatcher` seam; exact-match default proven (E7); hierarchy is a clean
  later swap.
- **Internal-only** ✅ — `/internal/**` is permitted in `SecurityConfig` (an in-network attribute
  source), never gateway-fronted.
- **Boundary** ✅ — `EffectiveRoleService` is the single resolution seam, shared by the T4 dogfood
  supplier (management projection) and this API (resource projection) — no duplication.

**No refactor applied** — the design held; no invented churn. (`resolveForResource` looks up each
membership's team by id; for the demo a user is on few teams, so this is clear and fine — a join query
is a trivial later optimization, not warranted now.)

## Integration / e2e

ITs hit the real internal endpoint over HTTP against real Postgres (Testcontainers). The full
two-service loop through the gateway is T9.

## Decisions recorded

Recorded one Mulch **pattern** (`relates-to mx-723b5c`): the app-resolved effective-role design — one
resolver, two projections (management vs resource), `"*"` expansion, the internal `204`-on-no-match
fail-closed contract, the raw `core.RoleDefinition` wire shape, and the pluggable `TeamTargetMatcher`.
Synced as a `.mulch`-only commit (`cebc11b`).

## Commit

- `mulch: record app-resolved effective-role pattern (T7)` — `cebc11b` (`.mulch` only).
- `feat(user-mgmt): internal effective-role resolve API + TeamTargetMatcher (T7)` — code + tests + note.
