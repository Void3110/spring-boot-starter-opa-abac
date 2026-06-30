---
tags:
  - status/done
  - type/project
  - area/abac
---

# STATUS — T7: wire ownership into createTeam (public enforces, bootstrap bypasses) + IT

**Status:** ✅ DONE

## What shipped

- **`TeamController.createTeam`** — before binding the target, calls `requireOwnership(targetType,
  targetId)`: gets the caller's raw sub (`CallerIdentity.currentSubject()`, new) + the
  `ResourceOwnershipResolver` via `ObjectProvider`; `isOwner` false / resolver absent / no subject →
  throws `NotResourceOwnerException`. Replaced the "KNOWN DEMO LIMITATION (target squatting)" comment with
  the live guard.
- **`NotResourceOwnerException`** (service) → mapped to **403 `ACCESS_DENIED`** (library code, the SAME a
  `@OpaPreAuthorize` deny yields) by a new `@ExceptionHandler` in `ApiExceptionHandler`. The message names
  only the target, never the owner (a non-owner can't learn who owns it).
- **`CallerIdentity.currentSubject(): Optional<String>`** — the raw IdP sub (distinct from
  `currentUserId()`, the internal profile id) — the key the resolver compares.
- **Bootstrap bypass by construction:** `/internal/bootstrap/teams` is a **separate controller**
  (`InternalBootstrapController`) that never reaches `createTeam`, so it bypasses the gate with no flag to
  misconfigure (the seed + e2e matrices keep creating teams).
- **Test wiring:** `AbacTestConfig` gained a `@Primary` test `ResourceOwnershipResolver` delegating to a
  settable `ownershipDecision` (default: owner-of-everything), so the team-creating *setup* in the
  existing HTTP-path ITs keeps returning 201; `OwnershipGateIT` drives it per cell + resets in
  `@AfterEach`.

## Tests

- `OwnershipGateIT` (**real Postgres**, secured HTTP path) — **4/4**: **I6** (owner → 201, team bound),
  **I7** (non-owner → 403 `ACCESS_DENIED`, no team bound), **I8** (unverifiable → 403 — at the resolver
  boundary an outage is indistinguishable from non-owner, both `isOwner=false`), **I9** (bootstrap path
  bypasses — creates even when the decision would deny).
- Full user-mgmt suite **163/163** — the permissive test-resolver default kept every existing
  createTeam-using IT green. `./gradlew build` green; `opa test` 188/188 (policies untouched).

## Architecture review + refactor

- **Fail-closed:** 403 when not owner, **resolver absent**, OR no resolvable subject — never default-allow.
  The resolver returns false (never throws) on every breach (T5), so unknown-type / outage / 404 all
  collapse to this 403. Proven by I7/I8 + the absent-resolver path.
- **Bypass is structural, not a flag:** I9 proves the bootstrap path bypasses even with a denying decision,
  because it's a different controller — there is no "skip ownership" boolean that could be flipped on the
  public path (the B2 lesson: the off-ramp would be the vuln).
- **Security:** squat-deny uses the same `ACCESS_DENIED` code as any authz failure; the message leaks no
  owner identity. `uq_team_target` (T3) already blocks a SECOND team on a target; this guards the FIRST
  team-create on a catalog you didn't create — together they close squatting.
- **No refactor** beyond extracting `currentSubject()` from `currentUserId()` (DRY).

## Integration / e2e

ITs against **real Postgres** + the secured chain. The live created-by read is T6; the resolver is T5;
the e2e squat-deny through the gateway is **E7** in T9.

## Decisions / forward note

- **Production requires `abac.ownership.enabled=true` + the `catalog` registry entry on the user-service**
  for the resolver to exist — otherwise EVERY public createTeam fails closed to 403 (the correct default).
  **T9's rig must enable ownership on the user-service** (`abac.ownership.services.catalog=http://catalog:8080`)
  so the SPA's live self-service create works and E2/E7 pass. Flagged for T8/T9 config.
- **Existing e2e matrices create teams via `/internal/bootstrap/teams` (the bypass), not the public path**
  — so E8 is unaffected by the gate. To confirm in T9.

## Commit

(see branch `feature/void3110/multi-tenant-isolation`)
