---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/api
---

# "Who can access this resource?" — the reverse lookup

> **Status: 📋 researched, not scheduled.** Opened 2026-08-18. Researched against the two Zanzibar
> engines' own docs before any design, because [[REBAC-AND-ABAC]] states as an absence that this repo
> has "no reverse index, and no *who can see this resource* API" — so anything built here amends a
> published claim and had better be shaped deliberately.

## The finding that decides the design

The two candidate use cases are **not one feature**, and the industry did not build them as one.
Both major engines ship **two separate APIs** for this, split exactly where our two use cases split:

| Question | OpenFGA | SpiceDB | Shape |
|---|---|---|---|
| **Who** has access? | `ListUsers` (v1.5.4+, shipped experimental behind a server flag) | `LookupSubjects` (v1.12.0) | a **flat list of subjects** |
| **Why** do they have it? | `Expand` | `ExpandPermissionTree` | a **tree** of users/usersets |

- OpenFGA's `ListUsers` announcement names exactly one motivating product feature: displaying the users
  a resource has been shared with — **the Google Docs share dialog**. That is a product affordance, not
  an audit.
- OpenFGA documents `Expand` as being for **debugging and understanding why** a user has a relationship
  — a different job, stated as such.
- SpiceDB's `LookupSubjects` post frames the capability as important for **auditing and other kinds of
  UI**, and says it exists because clients were otherwise forced to do the recursive walk themselves
  through repeated `Expand` calls. Their `ExpandPermissionTree` resolves only **one hop per call**.

**So: "who" is cheap and flat; "why" is a graph walk and is where the cost lives.** Our two use cases
map onto that split precisely — and only one of them is worth building soon.

## Use case A — the PM affordance (worth building)

> *"Did the people on my team actually get access?"* — answered without asking them, and without the PM
> reading role definitions or tag setup.

This is the `ListUsers` / share-dialog shape: **one resource, a flat list of subjects, a yes/no per
subject.** It is a convenience over information the asker is already entitled to.

**It is unusually cheap here, and cheaper than in Zanzibar-land**, because our relationship spine is a
database join rather than a graph:

1. A catalog is **one-team-governed** (`uq_team_target`), so resource → governing team is a lookup.
2. `TeamMembershipRepository.findByTeamId` returns the roster, and each `TeamMembership` **carries its
   `RoleDefinition`** — so there are **no N per-user resolve round-trips**. (`RoleDefinitionSupplier.lookupAll`
   is one-user/many-targets — the wrong axis — and is not needed.)
3. The `bulk` entrypoint is **already direction-agnostic**: `decision := allow with input as item`, where
   each item is a *complete* input document. Holding the resource fixed and varying the role across N
   items works **today, with no policy change**.

Cost: **two queries + one OPA call**, bounded by team size. No library change, no new Rego.

**And the "why" is nearly free on this path** — unlike a tuple store, the granting role is already in
hand. Returning *"via role `catalog-editor`"* or *"blocked by required tag `region=eu`"* needs no graph
walk, which is precisely the PM's real question ("why didn't Sara get it?").

## Use case B — the security audit (do not build yet)

An audit answers a different question: **completeness**. "These are all the humans who can reach this
resource" is only true if it includes the **supervised path**, and that is where the work is:

- The org-relation seam is `transitiveReportsOf(subject)` — **forward only**. Audit needs the *upward*
  walk (managers of the members holding CONTROL-capable seats, transitively, depth-capped). That is a
  new seam method with its own fail-closed contract.
- **A partial answer is worse than no answer.** ADR 0029 already rules that a partial derivation
  collapses to membership-only rather than reporting a partial set, because a partial set is
  indistinguishable from a correct smaller one. An audit view that silently omits supervisors would
  understate access on exactly the oversight path the second factor exists for.
- Audit is also where **access reviews** live (ADR 0029 already defers quarterly grant reviews,
  SOC 2 CC6.3 / ISO 27001 A.8.2, to the consumer) — that implies export and point-in-time evidence, not
  a UI toggle.

## The trap both use cases share

**`bulk` returns booleans, and that flattens the one answer that matters most here.**
`bulk` maps `allow` over items and discards `deny_reason`. So for a **production** catalog a supervisor
renders as a plain "no" — when the policy's actual answer is *"yes, after a fresh second factor."*

Any honest UI needs **tri-state**: *can see* / *can see after step-up* / *cannot*. That means either a
second entrypoint or widening `bulk`'s item shape to carry the structured reason. Shipping the boolean
would make the UI state something the policy never said.

## Proposed shape, if scheduled

**Slice 1 — the PM affordance.** One internal endpoint (resource → governing team → roster), one bulk
call, tri-state not boolean, plus the granting role or the blocking tag as the "why". Scoped and
**labelled honestly**: *"team members with access"* — it does not claim to be exhaustive, so it never
implies the completeness it is not delivering.

**Deferred — the audit view.** Needs the inverse org walk, an explicit completeness contract, and an
export story. Its own slice, its own ADR.

## Decide before building

- **The gate.** "Who else can see this" discloses the roster to anyone holding the resource — a new
  privacy surface, not just a read. `team:list-members` is the natural candidate (note 6.7 loosened it
  to readers); it must be a deliberate decision, not inherited.
- **The completeness contract**, stated on the wire. If slice 1 covers only the membership path, the
  response should say so structurally rather than in a UI label a client may drop.
- **Does this amend [[REBAC-AND-ABAC]]?** Partly, and honestly: a bounded, one-governing-team reverse
  lookup is not a reverse *index*. The note's claim should be narrowed rather than deleted — the
  general "who can see X across the tenant" remains unanswered, which is exactly what a tuple store
  generalizes.

## Related

- [[REBAC-AND-ABAC]] — the absence this would partly retire
- [[SUPERVISED-READ-AND-STEP-UP]] — the second path any audit view must include
- [[TEAM-BASED-AUTHORIZATION]] — the roster and role≠grant model this reads
- [[adr/0029-supervised-read-scope|ADR 0029]] — the forward-only org seam, and the no-partial-sets rule
- [[adr/0016-action-enrichment-affordance-metadata|ADR 0016]] — the affordance-not-enforcement stance this inherits

## Sources

- [OpenFGA — List Users API announcement](https://openfga.dev/blog/list-users-announcement) ·
  [Relationship Queries (Check, Read, Expand, ListObjects, ListUsers)](https://openfga.dev/docs/interacting/relationship-queries)
- [AuthZed — LookupSubjects and SpiceDB v1.12.0](https://authzed.com/blog/lookup-subjects) ·
  [Querying Data](https://authzed.com/docs/spicedb/concepts/querying-data)
