---
tags:
  - status/done
  - type/project
  - area/user-service
  - area/abac
---

# STATUS — Ticket 06: Transfer-ownership

> Filled in at the ticket-06 checkpoint. See [[01-DECOMPOSITION]] ticket 6.

**Status:** ✅ done

## What shipped

Transfer-ownership — the GitHub-style first-class operation, so a resource is never orphaned.

- `TeamService.transferOwnership(teamId, newOwnerUserId)` — one `@Transactional`: the new owner's
  membership becomes `owner`, every current owner is downgraded to `administrator`. The new owner must
  already be a member (the documented choice) → `MembershipNotFoundException` (404) otherwise.
- `TeamController.transferOwnership` — `@OpaPreAuthorize(action="team:transfer-ownership",
  resourceType="'team'", resourceId="#teamId")`. The verb is in the **owner's** management ladder only,
  so administrators cannot transfer (→ 403).
- OpenAPI: `POST /api/v1/teams/{teamId}/transfer-ownership` + `TransferOwnershipRequest`.

## Tests

`:example-user-management-service:test` → **green (28 total; +3 this ticket)**. `TransferOwnershipIT`
(real Postgres + the real secured chain):
- **T1** owner transfers to a member → the new owner resolves as `owner`, the old owner as
  `administrator` (atomic);
- **T2** an administrator attempting transfer → **403** (owner-only); the owner is unchanged;
- **T3** transfer to a **non-member** → **404** (the documented guard); the owner is unchanged
  (nothing half-applied — atomic).

`./gradlew build` (whole repo) → green; catalog ITs unaffected.

## Architecture review + refactor

- **Transfer reassigns cleanly** ✅ — T1 proves the promote/downgrade pair.
- **Atomic** ✅ — single `@Transactional`; the 404 path leaves the owner unchanged.
- **Owner-only** ✅ — `team:transfer-ownership` in the owner ladder only; T2 proves admins can't.
- **Never orphaned** ✅ — exactly one owner is maintained; the downgrade loop is defensive against any
  stray multi-owner state.
- **Fail-closed / layering** ✅ — `@Transactional` service, thin controller.

**No refactor applied** — the design held; no invented churn. (The downgrade scans the team's
memberships rather than a targeted `findByRole` query; left as-is — defensive, clear, and the demo
teams are small. A targeted query is a trivial later optimization if ever needed.)

## Integration / e2e

ITs run the genuine dogfooded chain against real Postgres; the OPA hop is the in-process client
mirroring `team.rego`. Container rig + newman is T9.

## Decisions recorded

Nothing non-obvious beyond the design + the cross-platform reference (`mx-7d3605`, which already covers
transfer-ownership as a first-class rule). No Mulch record — no ritual filler.

## Commit

`feat(user-mgmt): transfer-ownership (T6)` — code + tests + this note.
