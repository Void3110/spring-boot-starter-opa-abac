---
tags:
  - status/done
  - type/project
  - area/user-service
  - area/spring-security
---

# STATUS — Ticket 04: Team-management API (membership; subset rule; authorize the actor)

> Filled in at the ticket-04 checkpoint. See [[01-DECOMPOSITION]] ticket 4.

**Status:** ✅ done

## What shipped

The membership-management API — and the service **dogfooding the starter** to secure it.

**Policy** (`/rego-skill`-authored, `opa test` green):
- `opa/policies/team.rego` + `team_test.rego` — role-definition-driven, mirroring the catalog's
  per-type policies. Actions share the `team:` prefix (`team:manage`, `team:define-roles`,
  `team:transfer-ownership`) so the verb is a clean token; allow iff the verb is in
  `role_definition.permissions["team"]`; default deny (incl. no-role-definition).

**Security wiring** (the service dogfoods the starter):
- `SecurityConfig` — the service's own stateless chain installing `AbacFilter`; `/api/v1/**`
  authenticated, `/internal/**` permitted (the in-network resolve API, T7), health/docs open. The
  `FilterRegistrationBean<AbacFilter>` is `@ConditionalOnBean(AbacFilter.class)` (never created with a
  null filter when the starter is off).
- `TeamRoleDefinitionSupplier` (`config/`) — the dogfood supplier: given `(subjectId, "team", teamId)`,
  map the subject → `User`, return that user's **management** role on the team (the capability ladder).
  Empty when not a member / not a team / unparsable id → the policy default-denies.
- `application.yml` gains `opa.abac` config (enabled flag, base-url, blank policy-prefix → the `team`
  document).

**Service layer** (`@Transactional`):
- `MembershipService` — add / change-role / remove / list members. Enforces the **subset rule** (the
  role assigned may not exceed the actor's own effective permissions).
- `EffectiveRoleService` — the single membership→role resolution seam (shared by the supplier now and
  the resolve API in T7); two projections: `managementRole` (`permissions["team"]` = the capability
  ladder) and `resourceRole` (the role's stored perms, `"*"` expanded to the team-target type).
- `PermissionSubset` — the shared no-self-escalation check (reused by T5).
- `TeamRoleCapabilities` — the management-verb ladder by role code (owner > administrator > member/viewer).
- `CallerIdentity.requireActingUserId` reused for the actor.
- New exceptions: `SubsetRuleViolationException` (422), `MembershipConflictException` (409),
  `MembershipNotFoundException` (404).

**Web**: `MembershipController` (each endpoint `@OpaPreAuthorize(action="team:manage",
resourceType="'team'", resourceId="#teamId")`), `MembershipView` + a `UserMgmtMapper.toDto(membership,
roleCode)` overload, the `ApiExceptionHandler` mappings, and the OpenAPI membership endpoints + DTOs.

## Tests

`:example-user-management-service:test` → **green (20 total; +8 this ticket)**. `MembershipManagementIT`
(real Postgres, the **real** secured chain + the in-process OPA client mirroring `team.rego`):
- M1/M2 owner adds + removes a member (removal revokes — membership is the source of truth);
- M3 owner changes a member's role; M6 administrator manages;
- M4 member **and** viewer get **403** on manage;
- M5 the **subset rule** blocks assigning a superset custom role → **422**;
- **M7 the decision authorizes the actor**: the same caller is denied managing team B (where they are a
  viewer) but allowed on team A (where they are owner) — proof the role tracks the *calling subject*,
  not the service identity;
- unauthenticated → denied.

`opa test team.rego` → **10/10**. `./gradlew build` (whole repo, incl. the catalog ITs) → green.

## Architecture review + refactor

- **Authorize the actor, not the service** ✅ — M7 is the proof; the supplier resolves the calling
  subject's role on the specific team.
- **Subset rule** ✅ — one shared `PermissionSubset` (not duplicated), enforced in the service even for
  an authorized manager → no escalation.
- **Fail-closed** ✅ — supplier empty → policy default-deny; the in-process client mirrors the rego.
- **Pluggability/SOLID** ✅ — `EffectiveRoleService` is the single resolution seam (reused in T7);
  `TeamRoleCapabilities` isolates the ladder; `PermissionSubset` shared with T5.
- **Layering** ✅ — `@Transactional` service; thin controller carrying the annotation.

**Refactoring applied during the gate:** the first test harness drove the acting identity via a
`ThreadLocal`, which the in-process server (Tomcat worker thread) never saw — every request
authenticated as nobody, so allow-paths failed while deny-paths passed for the wrong reason. Refactored
to pass the subject **in a request header** the test extractor reads (travels with the HTTP call), and
collapsed to **one** security chain everywhere (the real `SecurityConfig`, with `@Primary` test beans)
after a second `/**` chain tripped Spring Security 6.4's "already configured" guard. Also guarded the
`FilterRegistrationBean` with `@ConditionalOnBean`. Re-ran → all green. A choice worth noting: listing
members is gated `team:manage` (so member/viewer can't enumerate) — a deliberate, consistent call.

## Integration / e2e

ITs run the genuine `@OpaPreAuthorize` → `TeamRoleDefinitionSupplier` → policy chain against real
Postgres (Testcontainers); only the OPA network hop is stubbed by an in-process client that mirrors
`team.rego`. The container-backed rig + newman matrix is T9.

## Decisions recorded

Recorded one Mulch **failure** (mx, `relates-to mx-410831`): the `ThreadLocal`-doesn't-cross-the-HTTP-
boundary trap when testing the dogfooded path over `TestRestTemplate`, plus the two-`/**`-chains and
null-filter `FilterRegistrationBean` gotchas — with the header-based / one-chain / `@ConditionalOnBean`
resolutions. Synced as a `.mulch`-only commit (`db0ff6e`).

## Commit

- `mulch: record dogfood-test threadlocal/one-chain failure (T4)` — `db0ff6e` (`.mulch` only).
- `feat(user-mgmt): team-management API + dogfooded ABAC + team.rego (T4)` — code + policy + tests + note.
