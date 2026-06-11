---
tags:
  - status/done
  - type/project
  - area/api
  - area/spring
  - area/abac
---

# STATUS — T4: User-service — spec envelope ×6 + paged services/controllers + internal note + IT

**Status:** ✅ DONE (2026-06-11)

## What shipped

- **`openapi/user-mgmt-api.yaml`** — the same `PageEnvelope` base + `Page`/`PerPage` parameter
  components as T3 (defined per-spec deliberately — ADR 0012 §2), plus `UserPage`, `TeamPage`,
  `MembershipPage`, `RoleDefinitionPage`, `TagDefinitionPage` (`allOf` + required `items`). All six
  list ops (`listUsers`, `listTeams`, `listMembers`, `listRoleDefinitions`, `listTagDefinitions`,
  `listTeamTagDefinitions` — both tag ops → `TagDefinitionPage`) gained the params, the
  `<Resource>Page` `200`, and a `400` response.
- **Service layer** — `MembershipService.list(teamId, pageable): Page<MembershipView>` (paged derived
  query + `Page.map(toView)`), `RoleDefinitionService.list(teamId, pageable)`,
  `TagDefinitionService.list(teamId, pageable)` (both branch repos paged). **No `AbacQueryService`
  here** — coarse-gated plain queries, by design (authz-nowhere).
- **Repositories** — paged derived variants added; unpaged ones kept **only** where other callers need
  them (`findByTeamId` ← `TeamService.transferOwnership`; `findByTeamIdIsNull[OrTeamId]` ←
  `applicableTo`, the validation-input fetch). The caller-less `List findBySystemTrueOrTeamId` was
  *replaced* by its paged form (no orphan left).
- **`web/PageDefaults`** — this service's copy of the fixed order + builder (per-service by design).
- **Controllers** — the six list methods take `(…, Integer page, Integer perPage)`, build the fixed
  `PageRequest`, map via the new `UserMgmtMapper.to<Resource>Page` envelope mappers. **Every
  `@OpaPreAuthorize` and every deliberate absence byte-identical** (diff: 0 annotation lines).
- **`web/ApiExceptionHandler`** — the `ConstraintViolationException` → `400 VALIDATION_FAILED` mapping
  (the same finding as T3: the `@Validated` generated interfaces surface param bounds via AOP method
  validation).
- **The `/internal/**` note (I9)** — at the surface's definition site in `SecurityConfig` (next to the
  existing in-network-only comment): *unpaginated by design — bounded machine-to-machine payloads*.
  Comment only; no internal endpoint's shape changed.

## Tests

`./gradlew :example-user-management-service:build` → **77 tests, 0 failures** (codegen clean — C1/C2):

- **I7** `PaginationEnvelopeIT.envelopeAndDefaults_onUsersList` (top-level; defaults 0/20 + echo),
  `envelopeAndExactCount_onTeamScopedRolesList` (fresh team → `count == 4` system roles; a
  `page=1&perPage=2` window keeps the exact count).
- **I8** `boundsViolation_is400ValidationFailed` (`perPage=101` → `400` `problem+json`),
  `pastTheEnd_is200EmptyWithExactCount` (`page=9` → `200`, empty, `count == 4`).
- **I9** — grep-verified: the `UNPAGINATED BY DESIGN` note at `SecurityConfig`'s `/internal/**` matcher.
- **In-commit break absorption:** `RoleDefinitionManagementIT` (2 sites → `RoleDefinitionPage`, one
  now also pins `count == 5`), `TagDefinitionReadApiIT`, `UserTeamCrudIT` (users/teams →
  `…Page.class` + `perPage=100` where the shared container accumulates rows). All other ITs green
  unmodified.

## Architecture review + refactor

- Repo-method hygiene reviewed explicitly (the T3 lesson): one caller-less unpaged query replaced
  rather than orphaned; the kept unpaged variants each have a named caller.
- The fix pinned in STATUS-03 (ConstraintViolationException, not HandlerMethodValidationException)
  applied directly — no fix-until-green loop this time.
- Nothing else substantive: controllers stay thin, mapping in the mapper, bounds contract-driven.

## Integration / e2e

The module's secured-chain IT suite (real Postgres, dogfooded `@OpaPreAuthorize`) is the integration
proof; the gateway e2e lands in T5.

## Decisions

- `MembershipPage` maps via `Page.map(toView)` so the role-code resolution stays in the service view,
  not the mapper.
- Test lists that read shared-container data request `perPage=100` and assert membership, not totals;
  deterministic totals are pinned only on team-scoped lists (fresh team = 4 system roles).

## Commit

`feat(pagination): user-service adopts the list envelope — spec ×6, paged services/controllers, internal note`
