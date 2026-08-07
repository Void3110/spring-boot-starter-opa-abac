---
tags:
  - status/done
  - type/index
  - area/abac
  - area/spring
---

# SUPERVISED-SCOPE — a supervisor sees their unit, without being on any team

> ✅ **SHIPPED 2026-08-07** — branch `feature/void3110/supervised-scope`, T1–T6 all green, orchestrated
> two-part autonomous run + layer-3 adversarial review (Approved with fixes —
> [[../../../code-review/SUPERVISED-SCOPE-REVIEW|review note]]). `./gradlew build` ✅ · `opa test`
> **276/276** · supervised matrix **44 assertions, 0 failed** (deny cells made real at review) ·
> Sonar CLEAN. Adds a **second, disjoint** access path beside team membership: a manager who is
> a member of **no** team sees the catalogs of the teams their reports own or manage — read-only, live,
> and derived entirely from the reporting structure. Contents (categories, products) stay **closed** in
> this slice; opening them is the next one.
> Slice **A of three** in the supervisor epic (see below). Phase 10 of [[POC-ROADMAP]].

## Why this slice exists

[[0018-team-scoped-resource-isolation|ADR 0018]] (slice B4) made **team membership the sole access path**
to the catalog root list — the coarse `catalog:list` gate was dropped, `GovernedScopeResolver` became the
only authority, and the `filter` entrypoint went role-definition-only with no fallback. The consequence is
exact and intended: **a subject who is a member of no team sees nothing.**

A unit manager is exactly that subject. This slice pierces the isolation **read-only and auditably**,
through a **new fail-closed org-relation seam** rather than by reintroducing the realm-role fallback B4
deliberately removed — which would be a fail-open backdoor.

**The headline:** a manager on zero teams gets a correct, live, read-only page of their unit's catalogs;
remove a report and those rows disappear on the next request; break the org source and they degrade to
their *own* memberships, never to everything.

## The epic — three slices, each fail-closed at its boundary

The design was sized as one slice and **failed the slice-sizing gate** ([[AUTONOMOUS-IMPLEMENTATION-FLOW]]
§2a: ~13 tickets across five deployables, tripping smells (a), (b) and (c)). It is split so that each
slice only ever **widens** what the previous one closed — the safest order for an authorization feature.

| | Slice | Ships | Status |
|---|---|---|---|
| **A** | **SUPERVISED-SCOPE** (this) | The list + metadata, read-only. Contents entirely closed (role **+** ADR 0031's confinement). | ✅ Shipped |
| **B** | PRODUCTION-TIER | `operatorManaged` tag flag + `env` + root-attribute enrichment. Supervised contents open for **non-prod**; production stays closed. | ⏳ Queued |
| **C** | STEP-UP-ELEVATION | `deny_reason` + the RFC 9470 challenge + `auth_time` freshness. Production contents open **when freshly elevated**. | ⏳ Queued |

A fourth step — the SPA's challenge UX — stays **collaborative rather than autonomous**, as the demo UI
has been throughout (Mulch `mx-12760e`).

## The pins (from the phase-① interview; full rationale in [[00-DESIGN]] + ADR 0029)

1. **Membership always wins** → `supervised := S \ M`. The two scopes are **disjoint by construction**,
   which is what makes the two-leg list safe rather than merely careful.
2. **Reach is CONTROL-capable memberships only** — a report contributes a team only where they hold
   `OWNER` / `ADMINISTRATOR` / `SENIOR`. A `MEMBER` or `READER` seat does not propagate.
3. **The realm claim is a UX-only eligibility marker**, never resolver input. Claim + zero reports = sees
   nothing. This is what distinguishes the design from the fallback B4 removed.
4. **Provenance rides the synthesized role** — a reserved code plus a marker in the role's existing
   `attributes` map. Zero decision-envelope change.
5. **Contents are closed by the role PLUS the confinement rule** ([[0031-inheritance-confined-to-membership-roles|ADR 0031]], T3).
   The synthesized role grants the coarse token `catalog: ["READ"]` and **nothing on `category` or
   `product`** — but that alone is *not* enough: the shipped `catalog → category` inheritance tables
   would hand it `category:view`/`product:view` anyway. So ancestor inheritance is confined to
   **membership-derived** roles by a provenance stamp, and this slice ships **one narrow policy
   change**. (Coarse *tokens*, never fine verbs: a fine verb expands to ∅ and would grant nothing at
   all.)
6. **Fail-closed, two classes.** Org source errored → the subject's **own memberships**. Partial derivation
   → **membership-only**, never a partial supervised set.

## Headline proof

**E1** — `sup-anna`, a member of no team, gets exactly her unit's catalogs (including her report's
report's), `sup-victor` gets a disjoint set, `outsider-eve` gets an empty page — asserted on **exact ids
and counts**, not just status codes. **E4** — remove `pm-bob` from anna's reports and his catalogs drop
from her next request, while a direct read of one returns **403**.

## Tickets (status table)

| # | Title | Status |
|---|---|---|
| T1 | user-service: the reporting relation + transitive derivation + `/internal/supervised-targets` | ✅ DONE |
| T2 | user-service: the non-membership `effective-role` branch + the synthesized supervisor role | ✅ DONE |
| T3 | **confine ancestor inheritance to membership-derived roles** (ADR 0031 — the provenance stamp + four policy clauses) | ✅ DONE |
| T4 | catalog-service: the `SupervisedScopeClient` HTTP edge (fail-closed, resilience-wrapped) | ✅ DONE |
| T5 | catalog-service: the two-leg partitioned list + the read-only ceiling + the audit event | ✅ DONE |
| T6 | e2e matrix + demo personas + the guide | ✅ DONE |

> ✅ **Cleared to run — with two named residual risks.** The inheritance fail-open that blocked this
> package is closed ([[0031-inheritance-confined-to-membership-roles|ADR 0031]] + **T3**), and the last
> full adversarial round returned **zero run-stoppers**. Validation was then stopped deliberately on a
> cost judgement (five full rounds cost 21.4M subagent tokens; see the Mulch record), in favour of
> targeted delta checks — each ~128k and each of which still caught a defect.
>
> **What that buys and what it costs:** no round ever ended *no-fix*, so the last two amendments are
> verified by a single-agent delta check rather than a full fan-out. Two risks are named rather than
> eliminated:
>
> 1. **T5's composition claims are the least-verified text in the package.** Four rounds asserted a
>    branch semantic that turned out to be wrong in both directions before it was corrected against the
>    shipped code. Treat `00-DESIGN` §5's pinned semantic and U42 as *documentation of measured
>    behavior*, and **re-measure before coding T5** rather than trusting the prose.
> 2. **The five `opa test` fixtures T3 must re-stamp are measured, not listed.** If a sixth breaks,
>    stop — something outside the model depends on inheritance.
>
> **The partition contains this.** Part 0 (**T1–T3**) is the well-validated half — the role, its
> confinement, `opa test`-provable, no rig, no list code. Both residual risks live in part 1
> (**T4–T6**), behind a maintainer checkpoint. Run part 0 first with confidence; read its STATUS notes
> before releasing part 1.

**Validated:** 2026-08-02 — mechanical [1]–[9] green · adversarial: 4 full rounds (last: **0
run-stoppers**, 49 agents) + 2 targeted delta checks · **stopped by cost decision, not by a clean
round** — residual risks named above.

## Files in this folder

| File | What it is |
|---|---|
| [[00-DESIGN]] | The mechanism, decided forks, fail-closed posture, considered-&-rejected. |
| [[01-DECOMPOSITION]] | The ordered work list T1…T6 + the critical path. |
| [[10-QA-TEST-CASES]] | Concrete U*/I*/E* cases → each ticket's Acceptance. |
| AUTONOMOUS-IMPLEMENTATION-PROMPT | The self-contained prompt the run executes. |
| STATUS-01 … STATUS-06 | One stub per ticket, filled at each checkpoint. |

## Conventions

- **Fail-closed floor is the empty page**, never the table — in every branch, exactly as ADR 0018 §Consequences.
- **Additive only**: no library module changes at all in this slice; both new endpoints mirror shipped
  siblings (`/internal/governed-targets`, `/internal/bootstrap/*`).
- **Exactly one narrow Rego change** — T3's four inheritance clauses in `category.rego` + `product.rego`
  (ADR 0031). Those two files are **not** part of the mirrored bundle (`permissions.rego` +
  `permission_categories.json` are), so the drift guard stays out of play; any *other* policy edit means
  the run has left the slice boundary.
- Clean-room: the consumer is never named; write "the first consumer" or omit.

## Related

- [[POC-ROADMAP]] — phase 10.
- [[0029-supervised-read-scope]] — the scope contract this slice implements.
- [[0030-step-up-decision-contract]] — the elevation contract; slices **B** and **C**, not this one.
- [[0018-team-scoped-resource-isolation]] — the isolation invariant this slice pierces without weakening.
- [[MULTI-TENANT-ISOLATION]] — slice B4, which established the mechanism being extended.
