---
tags:
  - status/planned
  - type/index
  - area/abac
  - area/spring
---

# SUPERVISED-SCOPE — a supervisor sees their unit, without being on any team

> **Status: Planning.** Adds a **second, disjoint** access path beside team membership: a manager who is
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
| **A** | **SUPERVISED-SCOPE** (this) | The list + metadata, read-only. Contents entirely closed (role **+** ADR 0031's confinement). | 📋 Planning |
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
| T1 | user-service: the reporting relation + transitive derivation + `/internal/supervised-targets` | 📋 TODO |
| T2 | user-service: the non-membership `effective-role` branch + the synthesized supervisor role | 📋 TODO |
| T3 | **confine ancestor inheritance to membership-derived roles** (ADR 0031 — the provenance stamp + four policy clauses) | 📋 TODO |
| T4 | catalog-service: the `SupervisedScopeClient` HTTP edge (fail-closed, resilience-wrapped) | 📋 TODO |
| T5 | catalog-service: the two-leg partitioned list + the read-only ceiling + the audit event | 📋 TODO |
| T6 | e2e matrix + demo personas + the guide | 📋 TODO |

> ⚠️ **Fork resolved 2026-08-02 — re-validation pending.** The confirmed run-stopper (the pinned
> `catalog: ["READ"]` role inherited `category:view`/`product:view` through the shipped
> `catalog → child` inheritance tables whenever ancestors were present, which is always at runtime) is
> **decided, not merely noted**: ancestor inheritance is now confined to **membership-derived** roles by
> a provenance stamp — [[0031-inheritance-confined-to-membership-roles|ADR 0031]], which **amends
> ADR 0029** row 131 — and this slice ships **one narrow policy change** as the new **T3**. The fix was
> spiked against the real corpus before it was written: supervisor + ancestors → `false`, supervisor
> type-level list → `false`, stamped wildcard member → `true`, unstamped explicit-key member → `true`
> (direct grants untouched), supervisor `catalog:view` → `true`.
>
> Also resolved in the same amendment: **U14's input shape** (the eval must carry the resolver's
> ancestor chain — the ancestor-less probe is what green-lit the fail-open), **E8's fault-injection
> mechanism** (its own supervised base-URL property + a two-pass run, since B3's approach replaces the
> whole user-service the matrix needs), and **ADR 0029 §9's `GovernedScopeResolver` contract revision**
> (explicitly deferred to the SPI promotion — no ticket owns a library edit).
>
> **Do not run this package yet.** The amend-mode adversarial gate was re-run on the amendment
> (65 agents, 0 errors) and returned **38 confirmed / 23 refuted — 14 run-stoppers**, all clustering
> into three causes that the amendment itself introduced or left behind: the **autonomous prompt was
> never updated** (it still ran T1→T5, forbade the Rego change T3 *is*, and told the run not to restart
> OPA), the **cross-cutting "zero Rego" assertions** in three files, and residual **ticket/STATUS
> drift**. All three are now fixed and the mechanical gate is green — but the corrected package has
> **not yet been re-validated**, and several confirmed contradictions (E8's rig seam, the
> `findAuthorized` arity, U14's ticket ownership) were resolved in the same pass and need the same
> scrutiny.

**Validated:** ~~2026-08-01 — mechanical + adversarial clean~~ **SUPERSEDED — re-validation pending.**
Mechanical [1]–[9] green on the amendment (6 tickets, 6 STATUS stubs, `[9]` = 2 parts covering 6 of 6).
Adversarial history: 2026-08-01 → 20 confirmed (2 run-stoppers: the inheritance fail-open, now closed by
ADR 0031 + T3); 2026-08-02 → 38 confirmed (14 run-stoppers, all from the amendment's own blind spots,
now fixed). This line is restored only when a re-run comes back clean.

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
