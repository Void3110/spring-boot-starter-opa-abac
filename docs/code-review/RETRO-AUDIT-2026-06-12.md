---
tags:
  - status/done
  - type/review
  - area/abac
  - area/spring-security
  - area/spring-data
---

# Retro-audit (2026-06-12) — whole-tree security + concurrency/idempotency audit

> **Verdict**: the audit did exactly what it was built for. The two new always-on lenses
> (security-audit, persistence-concurrency/idempotency — added to the review method on 2026-06-11
> after the invariants-over-mechanisms hardening) were run retroactively over the **whole live tree**
> (main @ `9ec7f6a`), and they found what nine green per-slice reviews had missed: **two Critical
> defects, both fixed in this commit** — a privilege-escalation hole in the user-management example
> and a stale-authorization-lineage hole in the catalog example. 28 findings survived adversarial
> refutation (3 Critical entries = 2 distinct defects, 17 Medium, 8 Low) + 5 more from a re-run
> auditor; 4 were refuted. Mediums are dispositioned below: 5 fixed now (the Criticals' mirrors and
> the two made *live* by the Critical fix), 4 folded into Phase 5.97, the rest tracked as follow-ups.
>
> **Scope**: all 4 library modules, both example services, the Rego corpus, infra + e2e scripts —
> the current tree, not a diff. The known `category.rego` realm-fallback hole (fix pinned in
> ADR 0013 / Phase 5.97) was excluded from re-reporting; its *siblings* were the seeded target.

## Method

A one-off multi-agent workflow (46 agents): 10 surface auditors (7 × security-audit lens,
3 × concurrency/idempotency lens, per module/surface) → every finding adversarially refuted
(default-refute; a finding survives only if re-confirmed from source) → a completeness critic
(unswept siblings, zero-caller seams, untested off-states, decide-under-protection gaps) whose 8
candidates faced the same refutation → synthesis. One auditor (catalog concurrency) died on an API
error and was re-run separately. Both Criticals were then re-verified by hand in source before any
fix. Lens prompts are the hardened ones from [[DEEP-REVIEW-TEMPLATE]] (2B); this audit doubles as
their first full-scale validation.

## Critical — fixed in this commit

### 1. An administrator could self-promote to owner (privilege escalation, user-management)

Found independently by two lenses. The chain: `MembershipService.addMember/changeRole` are gated by
`@OpaPreAuthorize(action="team:manage")` — a verb administrators hold; `resolveAssignableRole`
resolved **any** system role including `owner`; the only ceiling, `SubsetGuard`, compared **resource
permissions only** — and the seed gives owner and administrator identical resource permissions
(`{"*": ["read","write"]}`), so `owner ⊆ administrator` passed. The management-capability ladder
(`TeamRoleCapabilities`, keyed on the role *code*: owner adds `define-roles` +
`transfer-ownership`) was never part of the comparison. An administrator could assign the owner
code to themselves (or mint a second owner, breaking the exactly-one-owner invariant of ADR 0002).

**Why nine reviews missed it**: the subset rule had a green test — for a *resource-verb* superset
(a custom role with a verb the actor lacks). The rule was checked as a mechanism ("subset check
present and tested"), not as the invariant ("no path lets an actor confer capabilities they do not
hold"). Exactly the failure shape the 2026-06 hardening targets.

**Fix** (all three faces, with pinning ITs):
- `resolveAssignableRole` rejects the `owner` code — ownership moves only through the
  transfer-ownership flow (which atomically downgrades the previous owner).
- `SubsetGuard.requireAssignableByActor` (new, used by both membership mutations) compares the
  capability ladder *in addition to* resource permissions — the invariant, robust to future
  privileged role codes.
- **Mirror swept** (Medium, same class): `changeRole`/`removeMember` could *demote or strip the
  sole owner*, orphaning the team — now rejected the same way.
- ITs: `administratorCannotSelfPromoteToOwner`, `ownerRoleIsNotAssignableEvenByAnOwner`,
  `ownerCannotBeDemotedOrRemovedViaMembershipEndpoints` (closes the audit's TEST_GAP finding).
- Sibling sweep of every membership-write site: `TeamService` creation-grant and
  `transferOwnership` are the designed paths; `RoleDefinitionService` defines ladder-less custom
  roles (permission-subset-guarded); `InternalBootstrapController` is the documented internal
  seeding seam (its exposure class is a tracked follow-up below).

### 2. PUT /categories/{id} re-parented adjacency only — authorization lineage went stale (catalog)

`updateCategory` did a bare `setParentId` + `save`. The `ltree path` — the materialized lineage
every hierarchy decision and list filter resolves against — was never rewritten, so after a move,
the whole subtree (categories *and* products) kept being authorized under the **old** branch; the
move also skipped the cycle guard. The correct atomic operation, `reparentCategory` (adjacency +
two-table subtree rewrite + cycle guard, one transaction), existed with **zero non-test callers** —
a shipped-but-inert seam, the exact NOT_WIRED class the hardened method now checks for.

**Fix**: `updateCategory` routes parent changes through `hierarchy.reparentCategory` (tag
validation — a remote call — stays outside the locked transaction). Pinned by a controller-level IT
asserting the category's *and* a descendant product's paths flip to the new branch.

**Made live by this fix, so fixed with it** (both confirmed by the re-run auditor with the
instruction to check this exact interaction):
- `reparentCategory` decided the cycle guard + new-parent path from an **unlocked** read of the new
  parent — two crossing moves (A→B ∥ B→A) could each pass the check and commit a cycle. Now both
  rows are locked FOR UPDATE in deterministic id order before anything is decided (Rule 1 of
  [[CONCURRENCY-AND-LOCKING]]).
- The detached full-entity merge in PUT wrote the (writable) `path` column back, able to silently
  restore a pre-move lineage past the optimistic guard (the native rewrite doesn't bump
  `@Version`). `AbstractHierarchicalEntity.path` is now `updatable = false` — written on insert,
  changed only by the maintainer's native rewrite.

## Folded into Phase 5.97 (RESOURCE-RESOLUTION)

These belong to the slice that already owns the mechanism:

| Finding | Where it lands in 5.97 |
|---|---|
| `OptimisticLockingFailureException` / `DataIntegrityViolationException` unmapped → races answer **500, not 409**; `LibraryErrorCode.STATE_CONFLICT` wired nowhere (both services) | 5.97 introduces `VersionGuard` → `409 STATE_CONFLICT`; the advice mappings (in the shared `AbstractProblemAdvice`) are part of that ticket, with off-state tests |
| `RoleDefinitionSupplier` outage is indistinguishable from authoritative no-role → catalog policies' JWT-roles fallback **widens** on user-mgmt outage | Same fallback-interplay class ADR 0013 closes for id'd decisions; the supplier error-distinct posture goes into the 5.97 design QA baseline |
| `tags_satisfied` exists only in `category.rego` — product/catalog writes ignore a role's `required_tags` | 5.97's attribute-rich gate is only as good as the policies; the conjunct (or an explicit documented scope) must land with the 5.97 policy work |
| Subset/ceiling checks decided on unlocked actor state (TOCTOU: concurrent demotion of the actor lets a grant commit) | The decide-under-protection remediation pattern (serialize team-scoped grant mutations on the team row) is 5.97-adjacent; pinned as a QA baseline cell + follow-up ticket |

## Follow-ups (tracked, not fixed here)

**Medium** — `AbacFilter` mutates the `SecurityContext` in place (use `createEmptyContext`);
actuator endpoints beyond health reachable unauthenticated via the `permitAll` catch-all (**both**
example services — the critic caught the user-mgmt sibling); deleting an in-use custom role hits
the FK and answers 500 (spec says 409); create flows compute the `ltree` path in a separate,
already-committed transaction from the insert (create-under-moving-parent race; the assignPath
javadoc's same-transaction invariant is not actually honored by the callers); tag/filter/team
matrix runners seed the demo catalog without its `ltree` path (fail-closed failure on a fresh rig);
ungated `POST /teams` lets any authenticated user bind a team to an existing target
(target-squatting — gate it or document as a demo limitation).

**Low** — resource type interpolated into the OPA URL/query unsanitized (validate
`^[a-zA-Z0-9_-]+$`); no authn/trust documentation toward OPA; `compile()` logs leak an OPA response
snippet via the Jackson parse exception; `validateExpiry` accepts a malformed/non-numeric `exp`;
no latch-based concurrency IT in user-management (guide Rule 6); hierarchy/tag matrix reruns
accumulate fixture rows; unknown stored `match_mode` silently repaired to the wider ANY_OF;
library `EntityNotFoundException` unmapped (update-vs-delete race answers 500, not 404);
native subtree rewrite still doesn't bump `@Version` (clobber vector closed by `updatable=false`;
the full fix bumps version in the rewrite SQL).

## Refuted (examples)

4 findings were killed by the refutation pass — e.g. "the gate ignores the supplied
`Supplier<Authentication>`" (it doesn't), and "`local.postman_environment.json` is committed with
secrets" (it is deliberately committed, carries only local-dev defaults, and the gitignore guard
covers the `*.local.*` variants). The refuted list lives in the run output, not here.

## What this validates about the method

- The **always-on lenses earn their cost**: every defect above sits squarely in the two classes the
  maintainer prioritized; none is in a class the old per-slice lenses covered.
- The **critic widens**: 3 of the surviving findings (user-mgmt actuator sibling, supplier-outage
  interplay, `tags_satisfied` sibling) came from the critic, not the per-surface lenses.
- The **refutation pass holds the floor**: 4 plausible-sounding findings died on re-confirmation.
- **Checklist-shaped green is not done**: both Criticals lived behind passing tests that exercised
  the mechanism, not the invariant.

Related: [[FULL-REPO-REVIEW-2026-06-10]] (the error-path/fail-closed + docs predecessor) ·
[[DEEP-REVIEW-TEMPLATE]] (the hardened lens prompts) · ADR 0013 / [[RESOURCE-RESOLUTION]] (the
known fallback hole this audit was seeded around).
