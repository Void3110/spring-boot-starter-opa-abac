---
tags:
  - status/active
  - type/decision
  - area/abac
  - area/spring
---

# ADR 0029 — Supervised read scope: a second, disjoint access path beside membership

**Status:** Accepted — planning; implemented by slice **A**, [[SUPERVISED-SCOPE]].
**Amended 2026-08-02 by [[0031-inheritance-confined-to-membership-roles|ADR 0031]]** — the mechanism row
below ("contents are closed **by the role**, so slice A ships **zero Rego changes**") was disproven by
`opa eval` against the shipped corpus: `catalog: ["READ"]` inherits `category:view`/`product:view`
through the declared ancestor-inheritance tables whenever ancestors are present, which is always at
runtime. Contents are closed by the role **plus** ADR 0031's confinement rule, and slice A ships one
narrow policy change. Everything else in this ADR stands.
**Date:** 2026-08-01
**Context tags:** org-relation seam, derived id set, non-membership role resolution, partitioned list, disjoint scopes, fail-closed

> Pins the **scope and role-resolution** forks for the supervisor slice: how a subject who is a member of
> **no** team can nevertheless read the catalogs of the people who report to them, without reintroducing
> the realm-role fallback [[0018-team-scoped-resource-isolation|ADR 0018]] deliberately removed. The
> **elevation** half — the production tier, the second factor and the challenge protocol — is
> [[0030-step-up-decision-contract|ADR 0030]].
>
> **Delivery note (2026-08-01).** The supervisor feature was scoped as one slice and **failed the
> slice-sizing gate** ([[AUTONOMOUS-IMPLEMENTATION-FLOW]] §2a — ~13 tickets over five deployables,
> tripping smells (a), (b) and (c)). It ships as three, each fail-closed at its boundary so every later
> slice only ever *widens* what the previous one closed: **A** [[SUPERVISED-SCOPE]] (this ADR — the list,
> read-only, contents closed), **B** `PRODUCTION-TIER` and **C** `STEP-UP-ELEVATION` (ADR 0030). This ADR
> is implemented **whole** by slice A, with the single staging exception noted in §6.

## Context

[[0018-team-scoped-resource-isolation|ADR 0018]] (slice B4) made **team membership the sole access path**
to the catalog root list. The coarse `catalog:list` gate was dropped; `GovernedScopeResolver` became the
only authority (`id IN governed-ids`, else `denyAll`), fail-closed to an **empty** page; and
`catalog.rego`'s `filter` entrypoint became role-definition-only with no subject-roles fallback.

The consequence is exact and intended: **a subject who is a member of no team sees nothing.**

The first consumer's requirement is precisely to pierce that isolation — a unit manager must see the
catalogs of the teams their reports own or manage — while keeping the isolation invariant intact for
everyone else. Reintroducing a realm-role fallback would be a fail-open backdoor and is not an option.

Two further constraints shaped the answer. The reporting relation is **not** ours to master: it lives in
HR and reaches an IdP as a provisioned projection (SCIM `manager`), and Keycloak is explicitly not an
org-chart store — its 26.x organization groups carry no manager relation and cannot drive authorization
policies. And [[0020-user-directory-port|the `UserDirectory` port]] is a frozen two-field text-search
surface; widening it into an org-chart oracle would betray its stated contract.

## Decision

### 1. Mechanism — a derived id set behind the governed scope

The supervised set is **re-derived per request**, exactly like membership: subject → transitive reports →
those reports' teams → those teams' catalogs → an id set composed behind `GovernedScopeResolver`. No
precomputation, no invalidation machinery, no materialized closure. At demo scale a walk-per-request stays
correct with zero staleness; precomputation (a Leopard-style index) is deferred until list latency forces
it, and is a pure optimization behind the same seam.

The closure is **depth-capped at 10 hops** and **cycle-guarded**. A cycle-closing edge is rejected on
write; a cycle nevertheless detected at read time fails **closed to empty**, never to a partial set.

### 2. Scope — supervised subtree, never flat read-all

A supervisor sees **only their own unit** — the catalogs reachable through the reporting structure. Two
supervisors see different (possibly overlapping) sets. There is no tenant-wide grant, no god-mode, and no
"read all catalogs" capability anywhere in the design. Every shipping precedent surveyed (GitLab Auditor,
GitHub organization security manager, Entra Global Reader with scoped administrative units) converged on a
named, scoped, read-only role derived from an externally-mastered relation, and this follows that shape.

### 3. Reach — CONTROL-capable memberships only

A report contributes a team to the supervisor's set **only where that report holds a CONTROL-capable
role** on it — `OWNER`, `ADMINISTRATOR` or `SENIOR` per the shipped `TeamRoleCapabilities` ladder.
`MEMBER` and `READER` seats do **not** propagate.

This is the literal reading of "the teams those people own or manage", and it reuses the existing
capability vocabulary instead of inventing a parallel predicate. It also bounds over-reach: a report
holding a reader seat on an unrelated team does not silently widen their manager's reach into it.

### 4. Relation source — a new fail-closed org-relation seam, example-side

The reporting relation is consumed through a **new seam** whose contract is `transitiveReportsOf(subject)`:
never null, never throwing, **empty on any breach**. It does **not** ride the `UserDirectory` port.

The seam lives **example-side for this slice** — the user-service owns the reporting fixture and the
derivation behind its existing `/internal` endpoints. It is deliberately **not** promoted to a published
starter SPI yet: a published SPI is permanent API surface, and the contract should be pinned by a real
consumer's real source rather than by our fixture. Promotion is a later, additive decision.

### 5. Precedence — membership always wins, so the two scopes are disjoint

Where a subject is **both** a member of a team and a supervisor of it, the **membership path wins**. The
supervised set is therefore reduced by the membership set:

```
M = membership-governed catalog ids      (existing GovernedScopeResolver)
S = supervision-derived catalog ids      (new org-relation seam)

supervised := S \ M                       # disjoint by construction
governed   := M ∪ supervised
```

This is load-bearing in two ways, not merely tidy:

- **It is the correct semantic.** A manager who also sits on a team must not need a second factor to read
  their own team's production data. Without this rule the elevation requirement would leak onto ordinary
  membership reads.
- **It structurally eliminates a fail-open branch.** Because no row is reachable by two paths, a row's
  provenance is unambiguous, so the residual that applies to it is never in question — and the supervisor
  role's vacuous tag requirement can never widen a tag-gated membership row. The alternative (unioning the
  two roles' permissions) is exactly that fail-open, and is rejected.

### 6. Role resolution — a synthesized read-only role on a non-membership branch

A wider id set alone yields an **empty page**: post-B4 the `filter` is role-definition-only, and the list
authorizer resolves its residual-driving role through membership, which a supervisor has none of —
`/internal/effective-role` answers 204 and the residual compiles to `DENY_ALL`.

So `/internal/effective-role` gains a **non-membership branch**: when the subject supervises the governing
root, it answers with a **synthesized, read-only supervisor `RoleDefinition`** granting only the coarse
**`READ`** token, with vacuous required tags (safe *only* because of the disjointness in §5). The role is
synthesized in code and never stored.

**Coarse tokens, never fine verbs.** `permissions[<type>]` carries `READ`/`WRITE`/`TAG`/`GRANT`/`CONTROL`
(ADR 0007/0015), which `data.permission_categories` expands — `READ` → `view, list, list-members`. A role
written with fine verbs expands to the **empty set** and grants nothing.

**Staged grant (the one place this ADR is delivered incrementally).** The grant widens by slice, and the
narrower state is load-bearing rather than incidental:

| Slice | `permissions` on the synthesized role | Effect |
|---|---|---|
| **A** — [[SUPERVISED-SCOPE]] | `catalog: ["READ"]` only — **no `category`, no `product` key** | Contents are closed **by the role + ADR 0031's confinement rule**; slice A ships **one narrow Rego change** (the amendment) |
| **B** — `PRODUCTION-TIER` | `+ category: ["READ"]`, `+ product: ["READ"]` | Contents open, gated by the `env` tier (ADR 0030 §1–4) |

Because the policies are role-definition-driven, an absent type key already denies every verb on that
type. **[Amended by ADR 0031 — this sentence is superseded: the shipped `catalog → child` inheritance
tables hand that role the child verbs anyway, so slice A needs exactly one narrow policy edit.]** Slice A
therefore needs no policy edit to keep contents shut — and slice B's widening is what makes
the tier necessary rather than decorative.

**The realm role is a UX-only eligibility marker.** A `unit-supervisor` realm claim makes the affordance
visible in a client; it is **never** resolver input. Reach comes entirely from the resolved report set — a
subject holding the claim with zero reports sees exactly nothing. This is what distinguishes the design
from the B4 fallback it must not reintroduce.

### 7. Provenance — carried by the synthesized role, read by policy

The policy must distinguish the two paths, because elevation attaches to the supervised path only
([[0030-step-up-decision-contract|ADR 0030]] §4). Provenance travels on the resolved role — the reserved
role code plus a marker in the role's existing generic `attributes` map — so `input.role_definition`
carries it with **zero** envelope shape change, following the established additive-evolution pattern for
that record.

Role codes are only *partially* unique (a custom role may reuse a system code), so a team owner could
define a custom role bearing the supervisor code. This is **not** an escalation and is recorded here so it
is not re-derived later: reach comes from the org-relation seam and never from the role (§6), so claiming
the code grants no additional scope — it only moves the holder onto the stricter branch, which requires
elevation for production content. Spoofing it is self-demotion.

### 8. The list — two legs, partitioned by provenance

`CatalogListAuthorizer` today resolves one role from `governedIds.get(0)` and compiles **one** residual,
on the explicit assumption that "every governed catalog is one the subject is a member of". This slice
breaks that assumption, so the list becomes two legs over the disjoint scopes, unioned before paging:

```
findAuthorized( scope       = id IN (M ∪ supervised),
                context     = the MEMBERSHIP role,
                subtreeSpec = id IN supervised )
  ⇒ scope ∧ ( residual_membership ∨ id ∈ supervised ) ∧ notDenied()
```

This is the shipped ADR-0010 base-scope-widening idiom, reused rather than reinvented: a membership row is
judged by the membership residual, a supervised row is admitted by the widening arm, and the deny-override
stays AND-ed outside both. When either scope is empty the call collapses to the single-scope form — and
for an ordinary member that is **byte-identically today's query**.

**Why not two independently-compiled legs.** `findAuthorized` compiles exactly **one** residual, from the
single `AbacContext` it receives; there is no overload taking two `(scope, context)` pairs, and no public
method turning a role into a residual `Specification`. A pre-composed `legA.or(legB)` handed in as `scope`
would get that one residual AND-ed over the union, narrowing supervised rows by the membership role. The
`subtreeSpec` slot is the shipped way to say "admit these rows too".

**The precondition this buys, stated so it cannot rot.** Admitting supervised rows unconditionally is
correct **only because the supervisor role's residual is `ALLOW_ALL`** (coarse `READ`, empty
`requiredTags`). Should a later slice give that role a tag requirement, this composition must change —
the slice carries an explicit test of the precondition rather than leaving it implicit.

`totalElements` remains the subject's authorized total; every branch fails closed to empty.

### 9. Failure semantics

- Org-relation source **errored** → the supervisor degrades to their **own memberships**. Never open, never
  the whole table.
- **Partial** derivation failure → collapses to membership-only. Never a partial supervised set, because a
  partial set is indistinguishable from a correct smaller one and would silently under- or over-report.
- Supervised rows are **strictly read-only**: every mutation is denied, and the affordance map reflects it.

`GovernedScopeResolver`'s contract text is revised accordingly: the governed set is
"membership-**or-supervision**-derived, **never an unconditioned universe**."

> **[Deferred — amended 2026-08-02.]** That revision touches a **published library module**, which slice A
> forbids end to end. It lands when the org-relation seam is promoted to a published SPI (this ADR's own
> deferred consequence); slice A leaves the library untouched and composes the supervised set beside the
> resolver.

## Consequences

**Good.** The B4 isolation invariant survives intact — there is still no realm-role fallback and no coarse
list gate, and a subject with neither membership nor reports still sees nothing. The disjointness rule
converts the design's sharpest fail-open risk into a structural impossibility rather than a check that
must be written correctly. Nothing an existing persona does changes behavior: leg 1 is today's query.

**Costs.** `CatalogListAuthorizer` gains real complexity, and the `governedIds.get(0)` shortcut — along
with the comment justifying it — must go. The org-relation seam is a second authority that can fail, so
every list and every child read now has two fail-closed sources to reason about rather than one.

**Deferred.** Precomputation of the closure; promotion of the org-relation seam to a published SPI; any
write capability whatsoever for supervisors; and the consumer-side concerns — HR→IdP provisioning cadence,
supervisor sign-in alerting, and quarterly grant reviews (SOC 2 CC6.3 / ISO 27001 A.8.2).

## Alternatives rejected

- **Flat read-all / tenant grant.** Rejected 2026-07-13 and again here: it is god-mode, it cannot express
  two supervisors with different units, and it makes the blast radius of a stolen supervisor token total.
- **Extending the `UserDirectory` port.** Its contract is two-field text search and says not to add
  fields; widening it turns a user picker into an org-chart oracle. Keycloak also has no manager relation
  to back it.
- **A Keycloak-native org relation.** 26.x organization groups carry no manager relation and explicitly
  cannot drive authorization policies.
- **Unioning membership and supervisor permissions** for a doubly-reachable row — the fail-open in §5.
- **Modelling supervision on the resource hierarchy** (an ltree branch). Reporting is people-structure, not
  resource-structure; the two trees have different shapes, lifetimes and owners.
