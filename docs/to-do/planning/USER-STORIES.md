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
- **A4** *As a security operator*, a role-source **outage** can never **widen** a caller's access: when
  the role authority is unavailable, the request fails closed (a uniform 403) instead of falling back to
  a broader realm-role grant. A *genuine* no-role still falls back as designed — only an outage is
  denied. — **Slice B2** ([[B2-SUPPLIER-OUTAGE]], ADR [[0014-supplier-outage-error-distinct|0014]]) ✅
  *Proven by `SupplierOutageGateIT` (outage → 403, OPA never called; contrast: empty → fallback grants).*

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
- **C4** *As a member with a tag-gated role that grants write*, I can **update** a category whose tags
  match my grant and am denied on one whose tags don't — decided **at the gate**, declaratively; and my
  realm role no longer leaks write access my team role doesn't grant. — **Phase 5.97**
  ([[RESOURCE-RESOLUTION]], ADR [[0013-attribute-rich-pre-authorization|0013]]) ✅

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
  ([[ACTION-ENRICHMENT]]) ✅ shipped
- **E2** *As a viewer*, the edit/delete affordances are reported as `false` (the buttons are hidden), even
  though the data is visible. — **Phase 6** ✅ shipped (the honest-`false` map)
- **E3** *As an editor*, write affordances report `true`; an action my role never mentions still reports
  `false` (the registry enumerates the full action set, not just granted verbs). — **Phase 6** ✅ shipped

### Epic G — "I shape and delegate access by coarse buckets, safely" (permission categories + delegation)

> The dev-team delegation flow: project owner = **owner**, lead/architect = **administrator**, senior dev =
> **senior**, mid dev/analyst = **member**, stakeholder = **reader**. Pinned by ADR 0007. — **Phase 6.5**
> ([[POC-ROADMAP]]) ✅ shipped 2026-06-12 ([[PERMISSION-MODEL]])

- **G1** *As a catalog owner authoring a role*, I pick a **level** (reader/member/senior/administrator) and
  the categories that level allows are pre-checked and **locked**; I then refine **downward** by denying
  specific fine actions (e.g. "member, but may not `delete`"). I grant by **bucket** (`READ`/`WRITE`/`TAG`/
  `GRANT`), not action-by-action. — **Phase 6.5** ✅ (the authoring contract: level ceiling, category
  tokens, strict denials — `422 ROLE_DEFINITION_INVALID`)
- **G2** *As an administrator*, I can assign any role **strictly below administrator** to a team member —
  but I **cannot** assign another administrator (the seniority ceiling), and I **cannot** author new role
  definitions (only the owner does). — **Phase 6.5** ✅ (the strict cross-tier gate; the designed cell:
  an admin whose own role denies `delete` still assigns full `WRITE` — tier, not subset)
- **G3** *As a senior dev*, I can onboard a new member by assigning them a role whose permissions are a
  **subset of mine** — but I can **never** hand out role-assignment power itself (`GRANT` is capped at
  administrator), so I can't create another senior or an admin. — **Phase 6.5** ✅ (the `≤ member` bound +
  the live `data.role.assignable` subset-on-effective verdict; OPA non-answers reject)
- **G4** *As any user editing a category/product*, my role's `TAG` bucket lets me **assign** dictionary tags
  as part of the management flow, and (if granted `define-tags`) curate the tag vocabulary — the same
  category that fences tag-curation from plain content `WRITE`. — **Phase 6.5** ✅ (the delta-dispatched
  `assign-tags` second decision, both directions) **+ Phase 6.7** ✅ (`define-tags` **enforcement closed**:
  the `team:define-tags` annotation is unchanged but now *granted* via the `TAG` token and *expanded* by
  the category-driven `team.rego` — owner/admin curate, `senior` correctly cannot (holds `CONTROL`, not
  `TAG`); ADR [[0015-control-plane-vocabulary-categorization|0015]])
- **G5** *As the platform*, the control plane (`team:*` management) uses the **same** category vocabulary
  as the catalog: the coarse `manage` verb is split into the deny-refinable `CONTROL` category
  (`add-member`/`change-role`/`remove-member`), `list-members` rides `READ` (any member sees the roster),
  and `define-roles`/`transfer-ownership` are owner-only-by-code fences. — **Phase 6.7** ✅ (one shared
  expansion home; `team.rego` symmetric with `catalog.rego`; the two-axis split keeps the
  `MembershipService` escalation gates an untouched invariant; ADR
  [[0015-control-plane-vocabulary-categorization|0015]])

### Epic F — "It works from a clean clone" (adoption / publish)

- **F1** *As a developer adopting the starter*, I add a dependency + a few properties + one `x-implements`
  line and get ABAC + enrichment — no framework worldview to adopt. — **Phase 7** (publish & polish)
- **F2** *As a reader*, the example runs end-to-end from a clean clone and the docs explain each layer.
  — **Phase 7**
- **F3** *As a client of a reference service*, every error I get back is canonical RFC-7807
  `application/problem+json` with a **machine-stable `errorCode`** I can branch on (not a human `message`
  string), so I handle a `422` programmatically instead of string-matching. — **Phase 5.9**
  ([[REST-API-REFINEMENT]], ADR [[0011-error-contract-problem-json|0011]])
- **F4** *As a developer evaluating the starter*, I can read **measured** performance evidence — what
  the authorization gate costs per request (p50/95/99 vs an unguarded baseline), where filtered lists
  saturate, that enrichment and cross-service chatter stay bounded, and how the system degrades under
  a dependency outage — and reproduce the numbers myself with one command. — **Phase 7.2**
  ([[LOAD-TESTING]], ADR [[0021-load-testing-methodology|0021]])
- **F5** *As a developer running list-shaped endpoints on the starter*, a page costs **one** role
  resolve and one ancestor walk per request — not one per row — whether the rows share a governing
  root (request-scoped memo) or each row is its own root (one batch resolve round-trip); the price is
  pinned and honest: a role answer is a **per-request snapshot** (revocation takes effect at the next
  request boundary), and no partial batch ever yields partial roles. — **Phase 7.3**
  ([[RESOLVE-COALESCING]], ADRs [[0023-request-scoped-resolution-memoization|0023]] +
  [[0024-batch-role-resolution|0024]]) ✅
- **F6** *As a developer adopting the starter in late 2026*, the 1.0 artifact targets the **current
  Spring Boot line** — Boot 4.0.x on Java 25, compiled against it first-class (not a 3.x build that
  happens to run) — with **behavior byte-identical** to the proven 3.4 implementation, so adopting it
  doesn't mean betting on an EOL platform or re-verifying semantics. — **SB4 port (pre-publish)**
  ([[SPRING-BOOT-4-PORT]], ADR [[0026-spring-boot-4-single-line-port|0026]])
- **F7** *As a developer adopting the starter*, I can pull it straight from **Maven Central** —
  `implementation("dev.dmitriikonovalov:opa-abac-spring-boot-starter:1.0.0")` (or pin everything through
  `platform("dev.dmitriikonovalov:opa-abac-bom:1.0.0")`) — a signed, POM-complete `1.0.0` with sources +
  javadoc, no building-from-source and no snapshot repo. The optional Keycloak directory module is its own
  fetchable coordinate; the demo apps are **not** published. — **Phase 7 (publish)**
  ([[MAVEN-CENTRAL-PUBLISHING]], ADR [[0027-maven-central-release-engineering|0027]])

### Epic I — "The demo UI is honest about what I can do" (the SPA experience)

> The `example-demo-ui` SPA is the human-facing lens over the same enforced decisions — a teaching
> surface where the authorization *cut* is **visible**, not just a green test. These stories capture the
> UX flows the pre-publish UI QA (2026-07-13) exercised end-to-end through the gateway. They are
> **SPA-experience** stories: the enforcement they ride on is already proven by epics A–H; what's new is
> that a *person* sees it. Verified live in `docs/code-review/PRE-PUBLISH-UI-QA-2026-07-12.md`.

- **I1** *As any visitor*, the SPA shows nothing until I sign in — a Keycloak Authorization-Code + PKCE
  login, no anonymous catalog data — and "Switch identity" fully clears the session so the next persona's
  reach applies cleanly. — **Phase 7 (demo SPA)** ✅ (A1/A2/A4 in the UI QA)
- **I2** *As a member vs a stranger*, I see the tenant-isolation cut with my own eyes: a member sees the
  catalog; an identity with no team membership sees an **empty** list **and** is denied a direct deep-link
  to the catalog (no shell, no children) — membership is the sole access path. — **Phase 7 (demo SPA)** ✅
  (B2/B3; rides [[TENANT-ISOLATION]] / ADR [[0018-team-scoped-resource-isolation|0018]])
- **I3** *As a viewer vs an editor vs an owner*, the buttons I see mirror my grant — the `_actions` map
  drives the controls: a viewer's write/tag/delete affordances are **absent/disabled**, an editor's are
  live, an owner also sees the control-plane (add-member / change-role / define-roles). — **Phase 7 (demo
  SPA)** ✅ (D1–D4; rides Epic E action enrichment)
- **I4** *As a viewer*, a control the client predicts I can't use shows **amber** (a client-side
  prediction from `_actions`) — and if I force the action anyway, the **server** still denies it and the
  failure surfaces distinctly in **red**: the amber affordance never *replaces* enforcement (a forced
  create still returns `403 ACCESS_DENIED`). — **Phase 7 (demo SPA)** ✅ (E1–E3 in the UI QA — the
  amber-vs-red predicted-deny UX; affordance ≠ enforcement)
- **I5** *As an editor*, I create a category/product and assign dictionary-legal tags **in the same step**
  (tag-on-create), the dictionary-driven tag editor offers only legal keys/values on **all three** taggable
  types (catalog, category, product), and an out-of-dictionary value is rejected `422 TAG_VALUE_ILLEGAL`
  with a readable message — I can never set the operator-only `abac_deny` key from the client. — **Phase 7
  (demo SPA)** ✅ (F1–F3/I2 in the UI QA; rides Epic C + [[0025-taggable-products|ADR 0025]])
- **I6** *As an owner*, I close the self-service loop from the UI: I search the **identity directory** for
  someone who has never logged in, provision-and-grant them a role in one flow, and after they re-login
  they now see the catalog that was empty before — membership, established through self-service. A custom
  non-owner role attempting the same control-plane action is denied honestly (`403`). — **Phase 7 (demo
  SPA)** ✅ (G1/G2/G3 in the UI QA; rides [[USER-DIRECTORY-PORT]] / ADR
  [[0020-user-directory-port|0020]] + the control-plane vocabulary, ADR
  [[0015-control-plane-vocabulary-categorization|0015]])

### Epic J — "An AI agent acting for me can never exceed me — and usually does less" (agent tool-call authorization)

> The starter secures what a **person** calls. An AI agent calling tools on someone's behalf is a second
> caller shape: it holds a delegated token and picks actions itself. These stories capture the cut a
> **dual-identity** decision makes — the human is the ceiling, the agent's capability profile only narrows
> — proven by [[AGENT-TOOL-AUTHZ]] (Phase 9) on a new `example-mcp-server`. Planned, not yet shipped.

- **J1** *As a user whose agent acts for me*, the agent can never do anything **I** couldn't do myself: my
  own role is the ceiling, and a capability profile that claims more than my role grants still yields only
  my role's actions. — **Phase 9 (agent tool-call authz)** 📋 (the no-widening case, proven in `opa test`
  **and** live on the rig)
- **J2** *As a user*, my agent is further **restricted** to the job I gave it: a capability profile narrower
  than my role means the agent may call only that subset — an out-of-capability tool is denied even though
  I could have called it myself. — **Phase 9** 📋
- **J3** *As an agent*, I see only the tools I may actually call — the advertised tool list is filtered to
  my effective authority, so I don't burn turns discovering denials. The list is a **hint**: a tool listed
  a moment ago whose capability was revoked is still denied when I call it. — **Phase 9** 📋
- **J4** *As an agent*, a denial tells me **which layer** refused (the tool-gate vs the resource's own
  policy) as a structured, advisory error — enough to pick another tool or ask for escalation, never a
  silent failure and never a stack trace. — **Phase 9** 📋
- **J5** *As an operator*, when the decision service is unreachable the agent path **denies** and the tool
  list degrades to un-filtered (never empty, never wider): no outage ever grants an agent something it
  couldn't do while healthy. — **Phase 9** 📋 (the fail-closed drill)
- **J6** *As a person signing in normally*, nothing changes: a token with no agent actor is an ordinary
  human call, decided exactly as before. — **Phase 9** 📋

### Epic K — "I can see what my people are working on, without joining their teams" (supervised read scope)

> Team membership is the **sole** access path to a catalog (Epic B / slice B4), so a unit manager who sits
> *above* several teams sees nothing at all. These stories capture the second, **disjoint** access path —
> derived from the reporting structure, read-only, and audited — plus the second factor that gates
> production detail. Split across three slices: **A** [[SUPERVISED-SCOPE]] (Phase 10-A, ✅ shipped
> 2026-08-07), **B** [[PRODUCTION-TIER]] (Phase 10-B, ✅ shipped 2026-08-13) and **C**
> [[STEP-UP-ELEVATION]] (Phase 10-C, ✅ shipped 2026-08-15) — plus the console follow-up
> [[SPA-CHALLENGE-UX]] (📋 planned 2026-08-15).

- **K1** *As a unit manager on no team*, I see the catalogs of the teams my reports own or manage —
  including my reports' reports' — and **nothing** adjacent to my unit. — **Phase 10-A** ✅ (the headline
  cut, asserted on exact ids)
- **K2** *As a manager*, my access is **read-only**: I can open a catalog's metadata, and every mutation is
  refused — the UI shows me no buttons I cannot press. — **Phase 10-A** ✅
- **K3** *As an organization*, revoking a reporting line takes effect **immediately**: the moment someone
  stops reporting to me, their catalogs leave my list and a direct read is refused. — **Phase 10-A** ✅
- **K4** *As a security officer*, a manager who holds the supervisor marker but has **no** reports sees
  **nothing** — the claim itself grants no access, only eligibility. — **Phase 10-A** ✅
- **K5** *As an operator*, when the reporting source is unreachable a manager degrades to their **own**
  memberships — never to everything, and never to a partial, silently-wrong unit. — **Phase 10-A** ✅
  (the fail-closed drill)
- **K6** *As a manager who also sits on a team*, my own team's data behaves exactly as it always has — being
  a supervisor never makes my own work harder to reach. — **Phase 10-A** ✅
- **K7** *As a product owner*, the fact that a catalog holds **production** data cannot be edited away by
  the people being supervised — the tier is set by an operator and is not assignable through the API. —
  **Phase 10-B** ✅
- **K8** *As a manager*, opening **production** content asks me for a second factor, once, and then lets me
  work for a bounded window — and refreshing my session does **not** silently extend it. — **Phase 10-C**
  ✅ (the headline step-up round trip)
- **K9** *As a compliance reviewer*, every privileged production read a manager performs leaves an audit
  event — the reads are the exact risk the second factor exists for. — **Phase 10-C** ✅
- **K10** *As an identity team*, we can swap the second factor (TOTP → passkey, say) as a **configuration**
  change, with no application change and no redeploy. — **Phase 10-C** ✅
- **K11** *As a manager*, I can open the **contents** of my reports' non-production catalogs — categories
  and products, staging and dev — without ceremony: routine oversight needs no second factor. —
  **Phase 10-B** ✅ (the headline tier cut)
- **K12** *As a security officer*, a supervisor's read of **production** contents is refused in this phase
  — the gate exists before the elevation does, and an untagged catalog is non-production only because
  tagging is operator-controlled. — **Phase 10-B** ✅
- **K13** *As a team member*, the tier changes nothing for me: reading my own team's production catalog
  works exactly as it always has, elevated or not — even when the tier machinery itself is failing. —
  **Phase 10-B** ✅ (the fail-closed drill: enrichment outage narrows the supervisor, never the member)
- **K14** *As a client developer*, the step-up refusal tells my client exactly how to re-authenticate
  (`acr_values` + `max_age` in a standard RFC 9470 challenge), and following it cannot loop — while a
  re-auth that omits `max_age` provably stays stuck on the same stale login. — **Phase 10-C** ✅
- **K15** *As a security officer*, an AI agent cannot exercise supervisory oversight: any call carrying
  the agent delegation claim is refused on the supervised path — plain 403, any tier, never a challenge —
  even if its token were somehow elevated (a combination the realm cannot even mint). — **Phase 10-C** ✅
  (revisitable: a supervised agent read-out would be its own designed feature)

- **K16** *As a manager using the demo console*, a production catalog tells me **before I click** that
  it will ask for verification, the refusal explains itself in the server's own words, one [Verify]
  takes me to the second factor and back to the same place, and I can see how long my elevation lasts —
  without the console ever pretending to decide what the server decides. — **SPA-CHALLENGE-UX** ✅
  (the console consuming K8/K14's contract; the "never pretends" half is the measured one — the
  console shows contents the server allowed while its own lapsed-elevation chip stayed amber)

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
