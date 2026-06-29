---
tags:
  - status/done
  - type/project
  - area/abac
---

# STATUS — T3: user-service GET /internal/governed-targets endpoint

**Status:** ✅ DONE

## What shipped

- **`EffectiveRoleService.governedTargets(String subject, String resourceType): List<UUID>`** — walks the
  SAME join as `resolveForResource` (`subject → user → memberships → teams`) but collects ALL matches with
  a `target_type == resourceType` filter, de-duplicated via a `LinkedHashSet` (insertion-ordered).
  Returns `List.of()` — never an error — for an unknown subject or a subject on no team of that type.
  `@Transactional(readOnly = true)`, re-derived from live membership (revocation propagates).
- **`InternalResolveController.governedTargets`** — `GET /internal/governed-targets?subject&resourceType`
  → `200 [uuid,…]`. Always `200` with a JSON array; an **empty array** (never `204`) is the authoritative
  "governs nothing" (the T2 resolver fails closed on an empty array exactly as on an error). Param name is
  `subject` (matching what `HttpGovernedScopeResolver` sends in T2).

## Tests

`EffectiveRoleResolveIT` (real Postgres via Testcontainers / Docker Desktop) — **17/17 pass** (7 new):
- I12 — single-team member → `[that catalog]`; unknown subject → `[]` (200).
- U11 — multi-team member → the UNION of distinct catalog ids; the `resourceType` filter (a `product`-team
  membership is not a governed catalog, and vice-versa, exactly type-scoped); a known subject on no team
  → `[]`.
- The schema-invariant cell (see Decisions): a second team on the same `(target_type, target_id)` is
  rejected by `uq_team_target`.

## Architecture review + refactor

- **Fail-closed:** `governedTargets` → `List.of()` (empty array, no throw) for unknown/no-team; the endpoint
  is always `200 [...]`, never `204`, so an empty array is the affirmative "governs nothing" the resolver
  needs. Proven by the unknown-subject + no-team cells.
- **Boundary (load-bearing):** the endpoint is under `/internal/**` (`permitAll`, in-network) — it must
  **NOT** be a T8 gateway route. The IT reaches it with no auth (confirming in-network reachability). The
  existing `/internal/effective-role` + `/internal/tag-definitions` contracts are unchanged (additive).
- **Pattern reuse:** mirrors `resolveForResource`'s join; differs only in collect-all + type-filter vs
  first-match. The `TeamTargetMatcher` SPI is untouched (it matches a single id; governed-targets collects
  all of a type — a different question, so the direct type-filter is correct, not a matcher call).
- **No refactor needed** beyond the test correction below — the service method cleanly extends the existing
  join.

## Integration / e2e

ITs run against **real Postgres** (Testcontainers), not H2. Docker Desktop is the active context
(`desktop-linux`); the opa-abac rig is up alongside podman (the portal platform) — runtimes correctly
separated. **Reminder for T9:** `docker restart opa-abac-opa` before any e2e matrix (the live OPA still
runs the pre-T1 policy).

## Decisions / discovery

- **`uq_team_target` makes catalogs one-team-governed.** The IT surfaced a unique constraint on
  `(target_type, target_id)`: a catalog can be governed by **at most one team**. So `governedTargets` is
  naturally distinct-by-id for the realistic shape, and the `LinkedHashSet` dedup is a defensive
  belt-and-braces (not load-bearing). I replaced an impossible "two teams, same target → one id" test with
  one pinning the constraint. **This matters for T7:** part of squat-protection is already enforced by the
  schema (you can't create a *second* team on an existing target); the B4 ownership check guards the
  **first** team-create on a catalog you don't own.
- **`subject` param name** (not the historical `userId` of `/internal/effective-role`) — it carries the
  IdP `sub`, and matches the T2 client.

## Commit

(see branch `feature/void3110/multi-tenant-isolation`)
