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
| **A** | **SUPERVISED-SCOPE** (this) | The list + metadata, read-only. Contents entirely closed. | 📋 Planning |
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
5. **Contents are closed by the role, not by policy.** The synthesized supervisor role grants the coarse
   token `catalog: ["READ"]` and **nothing on `category` or `product`** — so the existing
   role-definition-driven policies deny child reads with **zero Rego change in this slice**. (Coarse
   *tokens*, never fine verbs: a fine verb expands to ∅ and would grant nothing at all.)
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
| T3 | catalog-service: the `SupervisedScopeClient` HTTP edge (fail-closed, resilience-wrapped) | 📋 TODO |
| T4 | catalog-service: the two-leg partitioned list + the read-only ceiling + the audit event | 📋 TODO |
| T5 | e2e matrix + demo personas + the guide | 📋 TODO |

> ⛔ **BLOCKED — do not run this package.** The 2026-08-01 parts amendment's re-validation found a
> **confirmed run-stopper**: the design's central claim *"contents close themselves … zero Rego
> changes"* is false against the shipped policy corpus. `category_inheritable.json` declares
> `catalog → category` inheritance, so the pinned synthesized role (`catalog: ["READ"]`) grants
> `category:view` **and** `product:view` on the supervised path whenever ancestors are present —
> which is always, at runtime. Reproduced independently with `opa eval` against
> `infra/opa/policies`:
> `category.allow → true` · `product.allow → true` · type-level `category:list → true` (E6 pins
> **403**) · the same input **without** ancestors → `false`, which is exactly how **U14's
> acceptance eval is written** — a green-lighting trap. Adding `denied_actions` for
> `category`/`product` does **not** close it (`inherited_grant` tests the *ancestor* type's
> effective actions, where a category denial does not apply). This is a **fail-open on the slice's
> own headline boundary** and needs a phase-① decision (narrow the inheritance table for the
> supervisor role · a Rego change, breaking the zero-Rego pin · or re-scope the slice) before any
> ticket runs.
>
> **Open alongside it, pending the same fork** (they reshape with whatever is decided): **U14's
> input shape** (its eval must carry the resolver's ancestor chain — ancestor-less is not a valid
> probe), **E6's pinned 403** for the supervised category list, **E8's fault-injection mechanism**
> (the named B3 approach repoints the *whole* user-service the rest of the matrix needs — it needs
> its own supervised base-URL property), and **ADR 0029 §9**, which orders a `GovernedScopeResolver`
> contract-text revision this slice's "no library changes" rule forbids.

**Validated:** ~~2026-08-01 — mechanical + adversarial clean~~ **SUPERSEDED — BLOCKED.**
Mechanical [1]–[9] green (incl. the new execution-parts gate). The adversarial re-validation
**completed on the second attempt** (41/41 agents, 0 errors — the first attempt's `confirmed: 0`
was a *vacuous* pass: 37 agents, including every verifier, had died on a session limit).
Result: **20 confirmed / 17 refuted — 2 run-stoppers, 8 contradictions, 10 nits.** Both
run-stoppers are the inheritance fail-open above, from two independent angles; it survived
adversarial refutation and an independent hand-check with `opa eval`. **10 fork-independent
corrections are applied** (the `pm-bob` persona is new, not existing; the E7 matrix minimum gains
the isolation matrix; ADR 0018 has no §5; the wire field is `count`, not `totalElements`; four
internal endpoints, not three). This line is restored only when the run-stopper is resolved and
the gate re-run clean.

## Files in this folder

| File | What it is |
|---|---|
| [[00-DESIGN]] | The mechanism, decided forks, fail-closed posture, considered-&-rejected. |
| [[01-DECOMPOSITION]] | The ordered work list T1…T5 + the critical path. |
| [[10-QA-TEST-CASES]] | Concrete U*/I*/E* cases → each ticket's Acceptance. |
| AUTONOMOUS-IMPLEMENTATION-PROMPT | The self-contained prompt the run executes. |
| STATUS-01 … STATUS-05 | One stub per ticket, filled at each checkpoint. |

## Conventions

- **Fail-closed floor is the empty page**, never the table — in every branch, exactly as ADR 0018 §Consequences.
- **Additive only**: no library module changes at all in this slice; both new endpoints mirror shipped
  siblings (`/internal/governed-targets`, `/internal/bootstrap/*`).
- **Zero Rego changes** — so the mirrored-bundle drift guard is not in play here.
- Clean-room: the consumer is never named; write "the first consumer" or omit.

## Related

- [[POC-ROADMAP]] — phase 10.
- [[0029-supervised-read-scope]] — the scope contract this slice implements.
- [[0030-step-up-decision-contract]] — the elevation contract; slices **B** and **C**, not this one.
- [[0018-team-scoped-resource-isolation]] — the isolation invariant this slice pierces without weakening.
- [[MULTI-TENANT-ISOLATION]] — slice B4, which established the mechanism being extended.
