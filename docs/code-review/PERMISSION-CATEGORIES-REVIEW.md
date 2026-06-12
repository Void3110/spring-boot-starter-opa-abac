---
tags:
  - status/active
  - type/review
  - area/abac
  - area/opa
  - area/user-service
---

# PERMISSION-CATEGORIES (Phase 6.5) — Code Review

> **Verdict**: Approved with fixes (one Critical class consciously carried as tracked follow-up B2)
> **Scope**: The full Phase-6.5 slice — coarse permission categories + deny-overrides + five-tier
> delegation across core, policies, both example services, and e2e (8 feature commits, ~5300 lines).
> · **Branch**: `feature/void3110/permission-categories` vs `main`

## Method

Multi-lens adversarial workflow (deep-review 2B): 8 failure-mode lenses → per-finding adversarial
refutation → completeness critic → synthesis; 32 agents. 20 candidate findings: **17 confirmed**
(4 Critical), 3 refuted. Both fix-bound Criticals re-verified by hand in source before any edit.

## Critical issues

| # | Finding | Disposition |
|---|---------|-------------|
| C1 | **Role-resolve outage widens to the JWT realm-role fallback** (`catalog`/`category`/`product.rego` + `HttpRoleDefinitionSupplier`): any transport failure → `Optional.empty()` → indistinguishable from "no role" → a realm `catalog-editor` rides the fallback to READ+WRITE+TAG. 6.5 **aggravation**: the resolved role's `denied_actions` and `required_tags` narrowing evaporate with it (`effective_from_categories` does no subtraction). | **Known-deferred — tracked B2** (retro-audit 2026-06-12: "supplier-outage error-distinct posture… needs its own design pass"). Not re-decided here: the fallback is a designed, load-bearing path (type-level creates ride it by 5.97 design) and the fix is an SPI-contract change. Landed now: the supplier's javadoc/log no longer claim "the policy default-denies" — the B2 posture is stated honestly at the seam. **The 6.5 aggravation makes B2 more urgent.** |
| C2 | **Re-parent path discards the version binding** (`CategoryController.updateCategory` → `CatalogHierarchyService.reparentCategory`): the delta dispatch + gates decide on the pre-lock read, but the re-parent branch re-loads fresh post-lock and the final save silently overwrites any racer that committed in the window (which contains the slow tag-validation HTTP call) — including across the new WRITE/TAG boundary. Every other mutating path answers 409 on the same drift. | **Fixed**: `reparentCategory` now takes the decision snapshot and `VersionGuard.requireUnchanged`s it against the FOR-UPDATE-locked row before any write → `409 STATE_CONFLICT` on drift. New IT `reparentRejectsAStaleDecisionSnapshot` proves the racer's write survives and the move is rejected. |
| C3 | **A senior could demote or remove an administrator**: the hybrid gates bound only the role being *granted*; `removeMember` took no actor at all. Pre-6.5 unreachable (every manage-holder sat at/above all non-owner targets); 6.5's senior is the first below-tier manage-holder. | **Fixed**: the **target-tier gate** in `changeRole` + `removeMember` — a target whose current `role_level` is above the actor's rejects (`422 ROLE_SUBSET_VIOLATION`). Pinned semantics: peers stay manageable (admin-removes-peer-admin, the pre-6.5 cell, unchanged); an unreadable TARGET level never outranks (revocation narrows access; corrupted roles must stay removable); an unreadable ACTOR level still rejects. DELETE resolves the actor from the authenticated subject. Four new IT cells in `MembershipGateIT`. Documented in PERMISSION-MODEL, TEAM-BASED-AUTHORIZATION, the OpenAPI summaries, and the controller javadoc. |
| C4 | `product.rego` sibling of C1 (and `catalog.rego`, whose duplicate the refuter folded into C1). | Same disposition as C1 — B2 covers all three per-type policies. |

## Medium issues

| # | Finding | Disposition |
|---|---------|-------------|
| M1 | `DemoRoleDefinitionSupplier` still emitted retired flat verbs (`read`/`write`) — under the migrated policies the default demo profile ∅-expands and denies everything, while the guide already claimed category tokens. | **Fixed**: `READ` / `READ`+`WRITE`+`TAG` (mirroring the realm fallback's reach), javadoc updated. |
| M2 | The 5.5-B subtree widening was **silently dead**: `CategoryListAuthorizer` passed flat `"read"` to `SubtreeSpecResolver`, which can never match category tokens — every inheritable list degraded to the batch recheck (correct rows, the designed fail-closed degradation — but the feature was off and nothing said so). | **Fixed**: the authorizer passes the `READ` token, pre-gated conservatively (no widening for a role carrying a `catalog`/`*` denial or any `required_tags` — token membership can't see either, and an over-widened spec would leak rows since it is OR-ed with the residual). `CategoryListWideningParityTest` pins "`list` expands from `READ` and only `READ`" to the OPA data table. Resolver javadoc now states the raw-token contract. |
| M3 | Six of nine runners don't restart OPA before running. | **Known-deferred** — explicitly dispositioned in the retro-audit ("documented-manual, deliberately not widened"); unchanged by this slice's calculus (runners assume a current rig, and the suite ran green on one). |
| M4 | Category PUT answers **404 for a missing id before any authz decision** — an id-existence oracle relative to the uniform 403. | **Accepted-pinned** (decomposition semantic; the load necessarily precedes the in-handler dispatch). The guide now states the oracle trade-off explicitly instead of leaving it implied. |

## Low issues

- **Fixed**: negated-residual parser tests (the fail-closed seat for `not filter_list_denied`: negated eq folds to NEQ; negated membership → DENY_ALL, not-fully-supported, batch-rechecked); the I9 *timeout* leg (`MembershipGateIT` TIMEOUT verdict — the stub answers a late `true`, the client has already failed closed → 422); CONCURRENCY-AND-LOCKING **Rule 4 amended** with the accepted bounded OPA-verdict-under-lock exception (Rule 1 wins, 2s+2s, fail-closed); five stale OpenAPI summaries (subset-rule wording on authoring; owner/administrator → +senior on membership); the capability-ladder comment in **both** `team.rego` copies + the `MembershipController` javadoc (senior tier, reader naming).
- **Accepted**: delegation e2e cells call the user service directly — the suite-wide house pattern since Phase 4 (the user service sits outside the APISIX route; gateway-trust posture documented); empty-delta / both-deltas dispatch branches are proven at IT level (I13–I16 against the manager seam) — live e2e cells would need a TAG-without-WRITE fixture persona and are noted as a possible future matrix extension.

## Fail-closed verification

Traced by the fail-closed lens and re-confirmed: ∅-expansion of stale/unknown tokens (`permissions.rego` total functions, `[]` bodies); `filter` has no subject-roles fallback and its two PE degradations (denial-carrying, wildcard-keyed roles) land on the batch recheck; `RoleAssignableClient` answers `false` on 500 / missing-result / **timeout (now tested)**; `levelOf` rejects missing/non-numeric levels on the actor side; the new target-tier gate's one deliberate asymmetry (unreadable target never outranks) is in the *narrowing* direction and documented. **The one widening-on-failure path is C1/B2** — pre-existing, tracked, now honestly labeled at the seam.

## Security audit

C3 was the real find (privilege escalation via the revoke mirror) — fixed. No injection surfaces added (Liquibase 0006 uses `jsonb_exists` with literals; no user-input SQL/SpEL); no authz artifacts cached across subjects (the 5.97 request cache stays request-bound); no secrets/tokens in logs (the supplier logs status/class only); the e2e admin-denial window is trap-reverted on all exits and the runner verifies the revert.

## Concurrency & idempotency

C2 was the real find (decide-under-protection violated on the re-parent branch) — fixed with the in-lock version bind; the no-reparent path's optimistic `@Version` and the post-reparent save's fresh version cover the remaining windows. The OPA-verdict-under-`lockTeam` call is the documented Rule-4 exception (now in the guide). The critic's `ensureCustomRole` create-vs-update race was **refuted** (strictly sequential callers; the in-lock re-check answers a correct 409 in the worst case).

## Wiring & sibling sweep

- Flat-verb sweep across all main sources: only `TeamRoleCapabilities` retains flat verbs — correct (`team:*` is the deferred 6.7 control-plane vocabulary, not category tokens).
- Re-parent siblings: catalog/product PUTs have no re-parent branch (leaf saves ride the optimistic version) — clean.
- Demote/remove mirror: C3 *was* the unswept mirror; `transferOwnership` stays owner-gated — clean.
- New seams wired: the target-tier gate has production callers + 4 IT cells; the widening pre-gate has the parity test; no zero-caller seams introduced by the fixes.

## Autonomous-run check

No laziness (all T1–T8 deliverables genuinely shipped; tests assert actual cuts) and no goal drift
(core Spring-free; additive surface held; fail-closed held except the pre-existing B2 seam). The
run's ★ gates did miss four real findings in introduced code, all of one shape — **the sweep stopped
at the surface the ticket named**: T5 reviewed the grant gates but not their revoke mirror (C3); T6
verified the dispatch decisions but not the downstream save path they bind to (C2); T6's
action-string sweep covered annotations but not config beans emitting the old vocabulary (M1) or the
Java half of the rego mirror (M2). Recorded as planning-gap fixes for the next slice: (1) a
vocabulary migration must enumerate every Java *emitter/consumer* of the old vocabulary, not just
annotation strings; (2) a new grant-path gate needs its revoke/demote mirror as an explicit QA row;
(3) a dispatch decided on a loaded entity must name the version-binding path for every downstream
mutation branch.

## What's done right

The PE-inline idiom with an empirical prototype before any policy was written; wildcard semantics
unified across the two homes with parity tests; the uniform 422 contracts; decide-under-protection
held everywhere the run *did* look (the latch ITs are genuinely race-proving); the e2e matrix's
in-collection rebinding and trap-reverted admin-denial window; honest STATUS notes (the E1b
stop-and-investigate is documented, not buried).

## Test results

- `./gradlew build` — green (all modules, Testcontainers ITs incl. the new cells)
- `opa test infra/opa/policies/` — **157/157**
- newman: permission-categories matrix **27/27**; team matrix **11/11**; run-tests **19/19**
  (re-run on the rebuilt 6.5+fixes images)

## Refuted (spot-checked)

`ensureCustomRole` race (not reachable, converges to a correct 409); `catalog.rego` "unswept
sibling" (duplicate of C1 — the retro-audit already records the class plurally); E6's create leg
"silently re-scoped" (consciously documented in STATUS-07 + the runner header; a member-bound POST
is architecturally meaningless on the type-level create path).
