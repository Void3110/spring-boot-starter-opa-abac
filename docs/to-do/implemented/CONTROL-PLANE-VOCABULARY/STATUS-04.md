---
tags:
  - status/done
  - type/project
  - area/user-service
  - area/abac
  - area/spring
---

# STATUS — T4: IT + e2e + docs + slice record

**Status:** ✅ DONE (2026-06-15)

## What shipped

- **The headline IT — `ControlPlaneVocabularyIT`** (real Postgres via Testcontainers, the dogfooded
  `@OpaPreAuthorize` → resolve → in-process `team.rego` mirror path). Six cells = QA I1–I6:
  - I1/I2 — `memberListsRosterButCannotMutate` + `readerListsRosterButCannotMutate`: a READ-only role
    lists the roster (200, the loosening) but is **403** on add/change/remove + define-tags (the loosening
    is *exactly* listing, nothing wider);
  - I3 — `seniorManagesMembersButCannotDefineTags`: senior add-member 201 (CONTROL) but define-tags 403
    (CONTROL-not-TAG);
  - I4 — `ownerAndAdministratorCurateTags`: both curate (TAG);
  - I5 — `onlyOwnerReachesTheOwnerOnlyFences`: owner reaches define-roles/transfer-ownership, admin 403;
  - I6 — `seniorChangeRoleUpPastTierStillHitsTheEscalationGate`: the **two-axis re-proof** — a senior's
    renamed `team:change-role` (authorized by the policy) is still rejected `422 ROLE_SUBSET_VIOLATION` by
    the **untouched** `MembershipService` cross-tier gate when it would promote past the member tier.
  (The IT carries a positive `data.role.assignable` stub so the senior subset verdict is satisfied for
  I3; the I6 above-tier promotion is rejected by the level gate before the stub is consulted. The
  escalation matrix itself is exhaustively proven by the untouched `MembershipGateIT`.)
- **e2e (newman) — control-plane + member-can-list cells:**
  - `permission-categories-matrix`: added **E7a** (member lists roster → 200), **E7b** (member mutates →
    403), **E7c** (senior denied define-tags → 403) + a member-rebind cell. The existing E3a already
    exercises `team:add-member` through the renamed verb on the live OPA.
  - `team-abac-matrix`: the cell-8 intended-break — was "viewer GET /members → 403"; rewrote to **8a**
    (viewer lists → 200, the loosening) + **8** (viewer POST add-member → 403 with the problem+json
    contract). Cell 7 relabeled "owner lists members (team:list-members)".
- **Docs reconciled:** `PERMISSION-MODEL.md` (the category table + CONTROL + list-members; the two-axis
  split section; the define-tags-enforcement-closed section; the where-things-live rows),
  `TEAM-BASED-AUTHORIZATION.md` (the dogfooding section → the fine-verb endpoint table + the ladder +
  the owner-only fence + the two-axis note; the matrix rows), `infra/README.md` (team.rego is now
  category-driven, depends on the shared table, mirror both bundles + restart OPA).
- **Slice record:** `POC-ROADMAP` 6.7 → Shipped; `USER-STORIES` Epic G/Story G4 ticked (define-tags
  enforcement closed) + a new G5 (control-plane categorization); the index status table ticked through T4.

## Tests / proof

- **`./gradlew build` green** — all library + example modules + the user-mgmt ITs against real Postgres,
  incl. the new `ControlPlaneVocabularyIT` (I1–I6). User-mgmt module: 148 tests, 0 failures.
- **`opa test` both bundles green at the risen counts:** infra **177**, service-team **30**.
- **e2e suite green end-to-end (live rig):**
  | Matrix | requests | assertions | failed |
  |---|---|---|---|
  | permission-categories (+E7) | 31 | 31 | 0 |
  | team-abac (+8a, fixed 8) | 9 | 12 | 0 |
  | catalog-e2e | 10 | 19 | 0 |
  | catalog-abac | 12 | 19 | 0 |
  | data-filter | 4 | 16 | 0 |
  | hierarchy-abac | 4 | 4 | 0 |
  | hierarchy-list (pre+post) | 6 | 20 | 0 |
  | tag-abac | 7 | 12 | 0 |
  | resource-resolution | 8 | 12 | 0 |
  | pagination | 7 | 27 | 0 |
  The shared-table change (CONTROL + list-members in READ) broke **no** catalog-plane cell (list-members
  is inert on the catalog plane). Rig: OPA restarted after the policy edit; usermgmt image rebuilt with
  the T2/T3 code and the pod recreated.

## Architecture review + refactor

Self-review at the ★ gate (before the e2e run). **No production refactor — T4 is test/e2e/docs only**
(`git diff` confirms no `src/main/java`/library/`MembershipService` change). What the review surfaced and
I handled:

- **Two intended-break e2e cells** (the same class as the rego READ-expansion break): `team-abac` cell 8
  asserted the *old* "viewer can't GET /members → 403". Rewrote to the new contract (viewer lists → 200,
  mutates → 403 with the problem+json contract) + added the explicit positive loosening cell 8a. This is
  intended-break maintenance, not a regression.
- **I3/I6 OPA-stub scoping:** the senior path consults `data.role.assignable`; `AbstractSecuredPostgresIT`
  has no such stub. Added a minimal positive stub to the IT (mirroring `MembershipGateIT`) so I3's
  senior-add reaches 201; I6's above-tier promotion is rejected by the level gate *before* the stub is
  consulted (so the two-axis re-proof is genuine, not stub-masked).
- **Verified:** the IT proves the loosening is *exactly* list-members (every mutation still 403 for
  member/reader); I6 proves the verb rename did not bypass `MembershipService`; the e2e confirms it on the
  live rig with the real category-driven OPA.

## Integration / e2e

Above. The live rig ran against the **real** category-driven `team.rego` (OPA reloaded — confirmed by
`opa eval` probes: senior add-member true, senior define-tags false, member list-members true, custom
level-40 transfer-ownership false) and the **real** recast Java projection (usermgmt image rebuilt).

## Decisions

- New e2e cells live in the **permission-categories** matrix (E7*) — the 6.5 user-mgmt matrix — and the
  **team** matrix (8/8a), reusing each runner's existing bootstrap + tokens (no new runner).
- The `MembershipService.java:21` stale `team:manage` javadoc (T3 finding) is reconciled at the *guide*
  level (TEAM-BASED-AUTHORIZATION + PERMISSION-MODEL now state the now-true fine-verb model); the
  class-javadoc line is left untouched per the "no edit to `MembershipService`" pin and remains the only
  surfaced stale reference (a one-line follow-up outside this slice's pin if desired).

## Commit

`feat(control-plane): T4 — headline IT + e2e cells + docs reconcile + slice record`
