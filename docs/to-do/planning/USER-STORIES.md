---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/catalog
---

# User stories — the catalog service from the user's perspective

> **Status: Planning.** The **product lens** over the technical [[POC-ROADMAP]]. The roadmap tracks
> *mechanisms* (the library spine, team roles, the tag dictionary, data filtering, enrichment); this note
> tracks *what a person experiences* when those mechanisms are wired into the `example-catalog-management-service`.
> Every story is tagged with the **phase that delivers it**, so the two views stay in sync: a mechanism
> isn't "done" until its stories hold end-to-end through the gateway.

This is also a teaching artifact: the example app exists to be *read*, and a reader understands an
authorization library fastest through "as a … I can/can't …" than through class diagrams.

## Why this note

Two routes were identified for growing the roadmap: **tech topics** (partial-eval, batch, action
enrichment — tracked in [[DATA-FILTERING]] / [[ACTION-ENRICHMENT]]) and **actual integration into the
catalog service from the user's perspective**. This note is the second route. It keeps the project honest
about *who the authorization is for* and gives each tech phase a user-visible acceptance lens beyond
"the test is green."

## Personas

| Persona | Who they are | What they care about |
|---------|--------------|----------------------|
| **Viewer** | A team member with read-only access to a catalog. | Seeing the catalogs/categories/products they're entitled to — and *not* seeing or being offered actions they can't take. |
| **Editor** | A team member who can create/update catalog content. | Doing their work without hitting surprise 403s; clear feedback when something is genuinely out of bounds. |
| **Team owner / administrator / senior** | Runs or helps run a team: the **owner** authors role definitions + curates the dictionary; **administrators** do everything but author roles; a **senior** onboards juniors (assigns a subset of what they hold). The five-tier ladder (reader → member → senior → administrator → owner) is ADR 0007. | Delegating safely (no self-escalation), shaping what their team can do by coarse buckets, governing the vocabulary. |
| **Platform / super-admin** *(future)* | Operates across teams. | Broad visibility; an unconditional grant that data-filtering must honor as "see everything." |
| **Integrator (frontend/API consumer)** | Builds a UI or script against the catalog API. | A response that says *which actions are available* so the UI renders the right controls without guessing. |

## Epics & stories (each tagged to the delivering phase)

### Epic A — "I only act where I'm allowed" (single-resource enforcement)

- **A1** *As a viewer*, I can read a catalog/category/product I'm entitled to, but a write returns 403.
  — **Phase 3** ([[LIBRARY-SPINE]]) ✅
- **A2** *As an editor*, I can create/update catalog content my role permits. — **Phase 3** ✅
- **A3** *As any user*, I cannot read a specific resource by guessing its ID if it isn't mine — the app
  re-checks against the loaded resource, not just the route. — **Phase 3 + the per-instance check** (ADR
  0006). *Status: type-level shipped; the per-instance/hierarchical general path is the [[DATA-FILTERING]]
  follow-up.*

### Epic B — "My access comes from my team, not a static config" (app-resolved roles)

- **B1** *As a team owner*, when I create a catalog, I automatically own it (owner-on-create). — **Phase 4**
  ([[USER-MANAGEMENT-SERVICE]]) ✅
- **B2** *As an owner/admin*, I can add members and assign them roles, but I can't grant more than I hold
  (no self-escalation / the subset rule). — **Phase 4** ✅
- **B3** *As a member*, my effective permissions on a catalog are resolved from my real team membership —
  change my role and the next decision reflects it, no redeploy. — **Phase 4** ✅
- **B4** *As an owner*, I can transfer ownership; the old owner steps down atomically. — **Phase 4** ✅

### Epic C — "Access can depend on what the resource *is*" (tag-based grants)

- **C1** *As an owner/admin*, I can define a team tag key at runtime (e.g. `region`) and it governs
  assignment + decisions immediately. — **Phase 4.5** ([[TAG-DICTIONARY]]) ✅
- **C2** *As an editor*, when I tag a category I can only use values the dictionary allows; an illegal value
  is rejected (422), never stored. — **Phase 4.5** ✅
- **C3** *As a member with a tag-gated role*, I can read a category tagged `region=emea` but the **same
  role** denies a category tagged `region=apac` — the resource's tags drive the grant. — **Phase 4.5** ✅

### Epic D — "Lists show me only what I may see" (data filtering)

- **D1** *As a viewer with a tag-gated role*, `GET …/categories` returns **only** the categories I may see
  — the rows I can't see never leave the database. — **Phase 5** ([[DATA-FILTERING]]) 📋 planned
- **D2** *As two different users*, we hit the **same** list endpoint and get **different** row sets, decided
  by the same policy. — **Phase 5** 📋
- **D3** *As a platform super-admin* with an unconditional grant, the same endpoint returns **everything**
  (the filter degrades to "match all"). — **Phase 5** 📋
- **D4** *As a user with no grant*, the list is empty (`[]`), not an error and not a leak. — **Phase 5** 📋
- **D5** *As any user*, I page through a filtered list (`page`/`perPage`) and the envelope's `count`
  reflects only what **I** may see; walking the pages never repeats or drops a row. — **Phase 5.95**
  ([[PAGINATION-ENVELOPE]], ADR [[0012-pagination-envelope|0012]]) ✅

### Epic H — "Access I'm granted on a parent reaches what's nested under it" (hierarchical inheritance)

> A grant on a Catalog should govern the Categories and Products nested under it, **N levels deep** — not
> just the root and the leaf. Opt-in per relation, fail-closed, deny-overridable. Pinned by ADR
> [[0008-hierarchical-resource-authorization|0008]]. Ships as two slices: **5.5-A** (single-resource) then
> **5.5-B** (lists). — **Phase 5.5** ([[POC-ROADMAP]]) 📋 planned

- **H1** *As a user granted access on a Catalog*, I can read a **Product three levels down**
  (`catalog/{id}/category/{id}/product/{id}`) without a separate grant on the product — the grant is
  inherited down the **whole** ancestor chain, not just root+leaf. — **Phase 5.5-A** 📋
- **H2** *As an owner*, inheritance is **opt-in**: a resource type only inherits from an ancestor where I've
  declared the relation inheritable; by default a type is authorized on itself (no surprise widening). —
  **Phase 5.5-A** 📋
- **H3** *As an owner*, an explicit **deny** on a specific node wins over an inherited grant — I can share a
  Catalog yet carve out one Category that stays private (deny-overrides). — **Phase 5.5-A** 📋
- **H4** *As an admin moving a Category* under a different Catalog, access **re-derives correctly and
  immediately** — the moved subtree now inherits the new parent's grants, and nothing is left pointing at
  the old lineage (re-parenting is consistent and atomic). — **Phase 5.5-A** 📋
- **H5** *As a user granted on a Catalog*, `GET …/products` (a list) returns the products **anywhere under
  that Catalog** — the inherited grant **widens** the rows the filter returns, decided in SQL, on top of
  the Phase-5 tag filter. — **Phase 5.5-B** 📋
- **H6** *As any user*, if the ancestor chain can't be resolved (broken/cyclic/too-deep lineage), I get my
  **direct** access only — never more (a failed walk never widens, never strips a direct grant). —
  **Phase 5.5-A** 📋

### Epic E — "The UI shows only the buttons I can click" (action enrichment)

- **E1** *As an integrator*, each resource in a response carries an `_actions` map telling me which actions
  the current user may perform — so I render exactly the right controls. — **Phase 6**
  ([[ACTION-ENRICHMENT]]) 🔜 planned
- **E2** *As a viewer*, the edit/delete affordances are reported as `false` (the buttons are hidden), even
  though the data is visible. — **Phase 6** 🔜
- **E3** *As an editor*, write affordances report `true`; an action my role never mentions still reports
  `false` (the registry enumerates the full action set, not just granted verbs). — **Phase 6** 🔜

### Epic G — "I shape and delegate access by coarse buckets, safely" (permission categories + delegation)

> The dev-team delegation flow: project owner = **owner**, lead/architect = **administrator**, senior dev =
> **senior**, mid dev/analyst = **member**, stakeholder = **reader**. Pinned by ADR 0007. — **Phase 6.5**
> ([[POC-ROADMAP]]) 🔜 planned

- **G1** *As a catalog owner authoring a role*, I pick a **level** (reader/member/senior/administrator) and
  the categories that level allows are pre-checked and **locked**; I then refine **downward** by denying
  specific fine actions (e.g. "member, but may not `delete`"). I grant by **bucket** (`READ`/`WRITE`/`TAG`/
  `GRANT`), not action-by-action. — **Phase 6.5** 🔜
- **G2** *As an administrator*, I can assign any role **strictly below administrator** to a team member —
  but I **cannot** assign another administrator (the seniority ceiling), and I **cannot** author new role
  definitions (only the owner does). — **Phase 6.5** 🔜
- **G3** *As a senior dev*, I can onboard a new member by assigning them a role whose permissions are a
  **subset of mine** — but I can **never** hand out role-assignment power itself (`GRANT` is capped at
  administrator), so I can't create another senior or an admin. — **Phase 6.5** 🔜
- **G4** *As any user editing a category/product*, my role's `TAG` bucket lets me **assign** dictionary tags
  as part of the management flow, and (if granted `define-tags`) curate the tag vocabulary — the same
  category that fences tag-curation from plain content `WRITE`. — **Phase 6.5** (builds on **Phase 4.5**) 🔜

### Epic F — "It works from a clean clone" (adoption / publish)

- **F1** *As a developer adopting the starter*, I add a dependency + a few properties + one `x-implements`
  line and get ABAC + enrichment — no framework worldview to adopt. — **Phase 7** (publish & polish)
- **F2** *As a reader*, the example runs end-to-end from a clean clone and the docs explain each layer.
  — **Phase 7**
- **F3** *As a client of a reference service*, every error I get back is canonical RFC-7807
  `application/problem+json` with a **machine-stable `errorCode`** I can branch on (not a human `message`
  string), so I handle a `422` programmatically instead of string-matching. — **Phase 5.9**
  ([[REST-API-REFINEMENT]], ADR [[0011-error-contract-problem-json|0011]])

> **Future / comparison epic.** "The same team-grant decision, expressed *in the policy* (ReBAC) instead of
> resolved by the app" — the **Phase 8** [[POC-ROADMAP|ReBAC-in-Rego]] comparison. Not a new user story so
> much as a re-implementation of Epic B's decisions in a different place, to teach RBAC-vs-ABAC-vs-ReBAC.

## How to use this note

- When a phase ships, flip its stories ✅ and confirm each holds **through the gateway** (the e2e matrices
  are the proof — the team/tag/filter matrices already map onto epics B/C/D).
- When scoping a slice, check it against the stories it claims to deliver — a mechanism that doesn't move a
  story forward is suspect.
- New product ideas land here first (as a story under a persona), then graduate to a tech slice.

## Related
- [[POC-ROADMAP]] — the technical phases these stories are tagged to.
- Shipped mechanisms: [[LIBRARY-SPINE]] · [[USER-MANAGEMENT-SERVICE]] · [[TAG-DICTIONARY]].
- Planned mechanisms: [[DATA-FILTERING]] (Phase 5) · [[ACTION-ENRICHMENT]] (Phase 6) · coarse permission
  categories + delegation (Phase 6.5, ADR [[0007-coarse-grained-permission-categories|0007]]).
- The guides that document each delivered epic: [[ABAC-AUTHORIZATION]], [[TEAM-BASED-AUTHORIZATION]],
  [[TAG-BASED-AUTHORIZATION]].
