---
tags:
  - status/active
  - type/guide
  - area/abac
  - area/user-service
---

# Team-based authorization — the app-resolved path

How the demo turns *"who is asking?"* into *"what role does that give them on **this** resource?"* by
resolving the caller's effective role from **live team membership** in the `user-management-service`,
and feeding it to the catalog spine. This is the **app-resolved** model: the user-service resolves the
role server-side; the catalog still passes `role_definition` in the OPA `input` exactly as it did with
the static demo supplier — a single-bean swap, no wire-contract change.

It builds directly on the single-decision spine in [[ABAC-AUTHORIZATION]]; read that first.

## The team abstraction

A user creates a resource → the resource is linked to a **team-target** → the creator becomes the
**owner** → the owner manages a **team** and grants access to the resource through team membership.
Researched against AWS / Heroku / GitHub / GCP / Kubernetes / OpenFGA.

### `role ≠ grant`

The root cause of *role explosion* is conflating a **role** with a **grant**; every platform studied
separates them, and so do we:

- **`RoleDefinition`** — a reusable *named permission set* (`code` + `permissions{resourceType:[verbs]}`
  + attributes). Two kinds:
  - **system roles** — immutable, seeded: `owner`, `administrator`, `member`, `viewer`;
  - **team-scoped custom roles** — owner-defined, scoped to one team.
- **`TeamMembership`** — the **grant**: `{principal × roleDefinition × team scope}`. The membership row
  *carries* the role. "Team-scoped" is the membership's scope — not a role per team.

### Entities

```
User(id, subject /* IdP sub */, displayName)
Team(id, name, targetType, targetId)              -- the team-target = the owned resource ref
RoleDefinition(id, code, system, teamId?, attributes jsonb, permissions jsonb)
TeamMembership(id, teamId, userId, roleDefinitionId)   -- unique (teamId, userId)
```

`Team` is the durable owner of a resource (resource→team indirection); an owner *role* sits on a
person and is transferable.

## The hard rules (the teaching points)

1. **Owner-on-create (bootstrap).** Creating a team-target is **one transaction**: create the `Team`
   and write the **owner** `TeamMembership` for the creator. There is never a grant-less resource.
2. **No self-escalation (the hybrid assignment gates — Phase 6.5, [[PERMISSION-MODEL]]).** Assignment
   is gated by a strict **cross-tier level compare** (`actorLevel > candidateLevel`, from
   `attributes.role_level`; an unreadable level rejects) plus, at the **senior** tier only, OPA's
   `data.role.assignable` subset-on-effective verdict (any OPA non-answer rejects). Every rejection is
   `422 ROLE_SUBSET_VIOLATION`. Authoring is bounded by the **level ceiling**, not by the author's own
   permissions (owner-only authoring made the author-subset check vestigial). Acting on an
   **existing** member is additionally bounded by the **target-tier gate**: a member whose *current*
   tier is above the actor's cannot be demoted or removed by them (peers stay manageable).
3. **Transfer-ownership is first-class.** A dedicated operation: the new owner gets `owner`, the old
   owner is downgraded to `administrator`. Prevents orphaned resources.
4. **Revocation = membership is the single source of truth.** Removing a `TeamMembership` revokes all
   access derived through it; the resolve API always re-derives (no stale denormalized grants).
5. **Authorize the actor of a grant, not the service identity** (confused-deputy guard). Every
   management endpoint is `@OpaPreAuthorize`-secured against the *calling* subject.
6. **Decide grant mutations under the team-row lock.** Every team-scoped grant mutation (membership
   add/change/remove, transfer-ownership, custom-role writes) locks the `Team` row `FOR UPDATE`
   before the gate decisions (both level gates AND the `assignable` snapshots read post-lock state),
   so a concurrent demotion of the actor cannot land between the check and the grant (retro-audit
   2026-06-12; `CONCURRENCY-AND-LOCKING` Rules 1–2).

> **Team-target squatting — closed (Slice B4, ADR 0019).** `POST /api/v1/teams` carries no
> `@OpaPreAuthorize` (creating your first team precedes any membership to authorize against), so it used
> to bind *any* `(targetType, targetId)` first-come-first-served. Slice B4 closes this: before binding,
> the public path verifies the caller **owns** the target via a pluggable cross-service
> `ResourceOwnershipResolver` (config-keyed discovery → the owning service's
> `GET /internal/{type}/{id}/created-by` → compare to the caller `sub`); a non-owner, or an unverifiable
> check, fails closed to **403**. The `(targetType, targetId)` uniqueness constraint already blocked a
> *second* team on an existing target; the ownership check now also blocks the *first* team-create on a
> resource you did not create. The trusted in-network `/internal/bootstrap/teams` seed path is a separate
> controller that bypasses the gate by construction. See [[MULTI-TENANT-ISOLATION]] +
> [[adr/0019-pluggable-cross-service-ownership|ADR 0019]].

## The integration point — app-resolved

```
catalog @OpaPreAuthorize(action, resourceType, resourceId)
  → HttpRoleDefinitionSupplier.lookup(userId, resourceType, resourceId)            [catalog app code]
       → GET user-mgmt /internal/effective-role?userId&resourceType&resourceId
            server-side:  subject → user → memberships → team whose team-target matches the resource
                          → the membership's bound RoleDefinition
  → AbacContext(subject, action, resource, role_definition, env) → OPA decides on permissions
```

- **`HttpRoleDefinitionSupplier`** lives in the **catalog app** (selected by `catalog.role-source=http`,
  default `demo`). It calls the resolve API on the JDK `HttpClient`, returns `Optional<RoleDefinition>`,
  and **fails closed**: a non-200 (incl. the 204 no-match), a timeout, a connection refused, or a
  malformed body → `Optional.empty()` → the policy default-denies.
- **The resolve API is internal.** `GET /internal/effective-role` is not gateway-fronted — an
  in-network attribute source the catalog calls. `200 {RoleDefinition}` or **`204`** (empty, *not* an
  error) on no-match. The role's stored permissions are returned with the wildcard `"*"` (system roles
  are target-type-agnostic) expanded to the concrete team-target type.
- **Resource→team matching is pluggable** — `TeamTargetMatcher` (exact-match default; a hierarchy-walking
  matcher is an additive Phase-5 swap).

### Why app-resolved first
It reuses the Phase-3 spine untouched (the `RoleDefinitionSupplier` SPI already returns a
`RoleDefinition`), keeps the OPA input identical, and matches the proven source-platform design.
Token-claim delivery and **ReBAC-in-Rego** (the Zanzibar userset join in the policy) are documented
alternatives; ReBAC is the more elegant end state and is **Phase 7**, so the two can be compared.

## Dogfooding the starter

The `user-management-service` is itself a secured Spring app: it adopts the starter, declares its own
`SecurityFilterChain` + `AbacFilter`, and annotates its **management** controllers with
`@OpaPreAuthorize`. Its *own* `RoleDefinitionSupplier` (`TeamRoleDefinitionSupplier`) resolves the
caller's **management** role on the team being managed, and `team.rego` decides. So the service that
*produces* role definitions for the catalog is *also* a consumer of the same library — a clean, recursive
demonstration that the starter works for a real second app, and that the subset/escalation rules are
enforced by the same `@OpaPreAuthorize` mechanism.

### The control-plane vocabulary (Phase 6.7, ADR [[0015-control-plane-vocabulary-categorization|0015]])

Since Phase 6.7 the control plane uses the **same** category vocabulary as the catalog (see
[[PERMISSION-MODEL]]) — `team.rego` is now category-driven, expanding the resolved role's tokens through
the **same** shared `effective_actions` (symmetric with `catalog.rego`), no longer a raw verb match. The
management endpoints gate on **fine verbs**, not a coarse `team:manage`:

| Endpoint | Action | Granted by |
|---|---|---|
| `GET /teams/{id}/members` | `team:list-members` | `READ` — **any team member** can list the roster |
| `POST /teams/{id}/members` | `team:add-member` | `CONTROL` |
| `PUT /teams/{id}/members/{u}` | `team:change-role` | `CONTROL` |
| `DELETE /teams/{id}/members/{u}` | `team:remove-member` | `CONTROL` |
| `…/tag-definitions` (curate) | `team:define-tags` | `TAG` |
| `…/role-definitions` (author) | `team:define-roles` | **owner-only fence** (by code) |
| `…/transfer-ownership` | `team:transfer-ownership` | **owner-only fence** (by code) |

The capability **ladder** is `TeamRoleCapabilities`, projecting each system role **code** into category
tokens: `owner`/`administrator` → `[READ, CONTROL, TAG]`; `senior` → `[READ, CONTROL]` (manages members
but **cannot** `define-tags` — it holds `CONTROL`, not `TAG`); `member`/`reader`/custom → `[READ]`
(list-members only). `define-roles` and `transfer-ownership` are **not** category tokens — they are an
**owner-only-by-code fence** in `team.rego`, keyed on the reserved `owner` code (unspoofable by
`role_level`, so no custom or non-owner role can ever reach them). A **custom** role can never carry
team-management power: the projection forces a custom code to `[READ]`, and authoring one with `CONTROL`
(or a team-meaningful `TAG`) under a `"team"` key is rejected `422 ROLE_DEFINITION_INVALID`.

> **Two orthogonal axes.** The verb category decides *which kinds* of acts a role may perform; the
> escalation gates in `MembershipService` (rule 2 above) decide *on whom* and *to what tier*. Phase 6.7
> categorized only the first axis — the escalation gates are unchanged, so categorizing the verbs did not
> re-open any escalation path.

> This resolves the "does it expose a gateway route?" question: **internal-only** for the resolve API;
> **secured (dogfooded)** for the management API.

## The second access path — supervised read scope (Slice A, ADR [[adr/0029-supervised-read-scope|0029]])

Slice B4 made **team membership the sole access path** to the catalog root list. That is exact and
intended, and it has an exact consequence: **a subject who is a member of no team sees nothing.** A unit
manager is precisely that subject — they need to see the catalogs of the teams their *reports* own or
manage, without being on any of those teams.

Slice A adds a **second, disjoint access path** for that case. It does **not** reintroduce the realm-role
fallback B4 removed (that would be a fail-open backdoor); it derives reach from a **reporting relation**,
per request, behind a fail-closed seam.

### The two access paths

| | **Membership** (B4) | **Supervision** (ADR 0029) |
|---|---|---|
| Source of reach | a `TeamMembership` on the team that governs the resource | a `reporting_edge` chain: the subject's transitive reports, then the teams **they** hold a CONTROL-capable seat on |
| Where it is derived | `GET /internal/governed-targets` | `GET /internal/supervised-targets` (a sibling endpoint, same shape, **always `200`** + a possibly-empty array) |
| Role driving the decision | the membership's bound `RoleDefinition` | a **synthesized, read-only supervisor role** — `permissions = {"catalog": ["READ"]}`, empty `requiredTags`, `attributes.provenance = "supervised"`. Built in code per request, **never stored** |
| What it reaches | whatever the bound role grants | the catalog **list and metadata only** — read-only, and contents stay closed |

Both legs are composed in **one** query by the catalog's `CatalogListAuthorizer`, through the shipped
paged `AbacQueryService.findAuthorized`: the union as the base scope, the supervised ids on the
`subtreeSpec` widening arm.

### The precedence rule — membership always wins

```
supervised := S \ M          # S = the raw supervised set, M = the membership set
```

The two scopes are **disjoint by construction**, and that is load-bearing twice. It is the correct
semantic — a dual-hatted manager must not be pushed onto the stricter branch for their *own* team's data
— and it makes every row's provenance unambiguous, so the supervisor role's *vacuous* tag requirement can
never end up judging a tag-gated membership row. Unioning the two roles' permissions on a doubly-reachable
row is exactly that fail-open, and is rejected.

The same rule decides which role drives the residual: **whenever `M` is non-empty the role is resolved on
a MEMBERSHIP id**, never on one from the union. Only a *pure* supervisor (`M` empty) resolves it on a
supervised id — correct precisely because there are no membership rows for it to widen.

### The reach rule — CONTROL-capable seats only

A report contributes a team **only** where they hold `OWNER` / `ADMINISTRATOR` / `SENIOR` — the
CONTROL-capable rungs of the shipped `TeamRoleCapabilities` ladder. A `MEMBER` or `READER` seat does not
propagate, and neither does a custom role (custom roles project to `[READ]`). Otherwise one report's
reader seat on an unrelated team would silently widen their manager's reach into it.

Derivation is per request, breadth-first, **depth-capped at 10 hops** (a direct report is hop 1) and
cycle-guarded; a cycle-closing edge is rejected on write.

### The realm claim is a UX-only marker

The realm role `unit-supervisor` exists so a UI can *show* the supervised-unit affordance. It is **never
resolver input**: claim + zero reports sees **nothing**. This is what distinguishes the design from the
fallback B4 removed — reach comes entirely from the org relation, and the reserved role code is
**provenance, not authority** (a custom role bearing it buys no reach; spoofing it is self-demotion).

### Contents stay closed — and it takes TWO things, not one

> **Superseded in part by slice B** — see *[The production tier](#the-production-tier--how-deep-oversight-goes-slice-b-adr-0030-14)* below. B opens **non-production** contents by widening the
> synthesized role to name the child types **directly**. What this subsection describes is still exactly
> true and still load-bearing: **inheritance** stays closed to synthesized roles, and that is why
> widening the role — rather than re-opening inheritance — was the only safe way to open anything.

The synthesized role named **no `category` key, no `product` key, no `"*"`** (in slice A; B adds the two
child keys, still no `"*"`). That alone is **not
sufficient**: the shipped `catalog → category` and `catalog → product` inheritance tables would hand it
`category:view` / `product:view` anyway, from the very catalog it may read, whenever the ancestor chain is
present — which at runtime it always is. (An *ancestor-less* probe returns `false`, which is exactly how
that fail-open survived review once.)

So the second thing is [[adr/0031-inheritance-confined-to-membership-roles|ADR 0031]]:

> **Ancestor inheritance requires membership provenance. A synthesized role is confined to the types it
> names.**

`EffectiveRoleService.resourceRole` — the single funnel for membership-derived roles reaching the
catalog-side policies — stamps `attributes.provenance = "membership"` by **overwrite, never merge**, and
`inherited_grant` + `list_inheritable_grant` in `category.rego` **and** `product.rego` open only on that
stamp. **Direct** grants are untouched: a role naming a type explicitly still reaches it with no stamp at
all, which is why every shipped per-type role is unaffected. `provenance` is a **reserved, system-owned
key** — a client-supplied value is stripped on the write path and overwritten on the read path, so it
cannot be forged. **Absence is closed**: an unstamped role, an empty `attributes`, or an unknown value
grants no inherited access, so a future synthesized role that forgets the stamp fails *closed*.

### The production tier — how deep oversight goes (Slice B, ADR [[adr/0030-step-up-decision-contract|0030]] §1–4)

Oversight that can never open anything is a directory, not oversight. Slice **B** lets a supervisor open
a report's contents **when the environment is routine**, and keeps **production** detail shut.

**The role widens; authority stays in the role.** `SupervisorRoles.readOnlyFor("catalog")` now grants
`{catalog: [READ], category: [READ], product: [READ]}`, so child reads pass through the ordinary
**direct-grant** path. This is deliberately *not* a new inheritance path: ADR 0031 stays exactly as exact
as it was, and the role stays READ-only, so the ceiling cells (`PUT`/`DELETE` → 403) are untouched. Any
supervised type other than `catalog` keeps the single-key shape.

**The tier lives on the governing root.** An operator-managed `env` tag (`production | staging | dev`) is
written on the catalog and reaches child decisions as `input.resource.root_attributes` — see
[[TAG-BASED-AUTHORIZATION]] for the flag and the operator path, and [[ABAC-AUTHORIZATION]] for the
enrichment contract. Nothing a catalog's own owner can do through the API touches that tag
(`409 TAG_OPERATOR_MANAGED`).

**The decision is two deny clauses per leaf policy** (`category.rego` + `product.rego`), and the *shape*
is the point:

```rego
denied if {                       # tier UNPROVEN — enrichment failed or was never attempted
    input.role_definition.attributes.provenance == "supervised"
    not input.resource.root_attributes
}

denied if {                       # tier proven PRODUCTION
    input.role_definition.attributes.provenance == "supervised"
    input.resource.root_attributes.env == "production"
}
```

| Root state | Supervised child read |
|---|---|
| `root_attributes` **absent** | **denied** — an unproven tier is a closed tier |
| `{}` (fetched, untagged) | **open** — untagged means non-production (ADR 0030 §3) |
| `{"env": "staging"}` / `dev` | **open** |
| `{"env": "production"}` | **denied** — a plain `403` in B; slice C makes it a step-up challenge |

> **Do not "simplify" this into one clause.** `not input.resource.root_attributes.env == "production"`
> reads naturally and is **wrong**: an *absent* value passes a negated comparison, so an enrichment
> outage would **open** the tier instead of closing it. The absent state needs its own positive clause.
> Each of the four clause sites carries its own deletion-mutation guard in the test suite for exactly
> this reason.

**Members are structurally unaffected** (ADR 0030 §2). Both clauses require
`provenance == "supervised"`, so a membership decision **cannot reach them** — a member reads their own
team's production contents exactly as before, and keeps reading them during an enrichment outage, when
every supervised child read closes. That is the slice's one request-time failure class, and it lands on
two different answers on purpose: **the supervised path closes, the member's request proceeds unchanged**
— never a 5xx, never an exception out of the manager.

**Where the decision lands, and where it must not.** The list's tier decision happens at the **coarse
type-level gate** (which consults `denied`); it never enters the partial-evaluation `filter` residual —
a `root_attributes` predicate in the SQL would be both a dead end for the compiler and a slice-boundary
breach.

**One known affordance gap, pinned as contract:** the `_actions` enrichment builds per-row inputs with no
root context, so on supervised child rows every verb computes false and the omit-on-all-false convention
**omits the map entirely**. That is the intended B behavior — omitted, never a fabricated `view: false`
on a row the caller can actually read. Member rows are untouched. Threading root context through the
enrichment advice is slice C's work.

### Two failure classes — and they land in different places

Never collapse them into one rule:

| Class | Lands on |
|---|---|
| The org-relation source **errored / unreachable / non-200 / unparseable** | the subject's **own memberships** — the supervised leg contributes nothing |
| A **partial** derivation (a cycle, a depth-cap breach, a malformed element) | **membership-only** — never a *partial* supervised set |
| Unauthenticated · no `AbacQueryService` · no `GovernedScopeResolver` · both scopes empty · an unresolvable role on every leg | the **empty page** (unchanged from ADR 0018) |
| A role reaching a child type with **no provenance stamp** | **no inherited grant** (ADR 0031) |

A partial set is indistinguishable from a correct smaller one, which is why it *collapses* rather than
degrades. In every branch the floor is the empty collection and the empty page — never a partial
supervised set, and never the whole table.

The supervised edge has its **own** `CallGuard` (its own breaker) and its **own** base-URL property
(`catalog.user-service.supervised-base-url`, defaulting to the shared one). Sharing the resolve breaker
would let a supervised-targets outage trip the one every persona's role resolution depends on — turning a
degrade-to-membership-only into an empty page for everyone.

### What is NOT in this slice

*(This subsection scopes **slice A**. Slice B has since shipped ADR 0030 §1–4 — see *The production
tier* above; §5–9 remain slice C's.)* Contents (categories, products) stayed closed in A; opening them
behind a production tier ([[adr/0030-step-up-decision-contract|ADR 0030]] §1–4) and a second factor
(§5–9) is slices **B** and **C**. No `env` tag, no `operatorManaged` flag, no `deny_reason`, no RFC 9470 challenge, no
`acr`/`auth_time` ingestion. A supervised **single-`GET`** is audited by slice C; slice A audits the
**list** path, where the supervised authority is applied.

### Prove it

```bash
ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2
cd scripts/postman && ./run-supervised-scope-matrix.sh
```

The headline cells: **`sup-anna`**, a member of no team, gets exactly her unit's catalogs *by id* —
including her report's report's, and excluding a report's READER-seat team; **removing a reporting edge
withdraws access on the very next request**, and the withdrawn catalog then returns `403` on a direct
`GET` rather than merely disappearing from the list.

## Run it end to end

```bash
# Bring the full rig up with OIDC + the user-service; the catalog pods resolve roles from it.
ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2

# Run the team-based ABAC matrix (mints in-network tokens, bootstraps the team data, asserts):
cd scripts/postman && ./run-team-matrix.sh
```

The matrix proves, through the gateway, with roles resolved from real team membership:

| Caller | Team role | Action | Result |
|--------|-----------|--------|--------|
| owner | `owner` | write the owned catalog | **200** |
| owner / viewer-member | (read) | read | **200** |
| viewer-member | `viewer` | write | **403** |
| custom-editor member | team-scoped `catalog-editor` | write | **200** |
| non-member | (none → empty role) | write | **403** |
| owner | `owner` | manage the user-service's own API (`team:add-member`, …) | **200** (dogfood) |
| viewer-member | `reader` | `team:list-members` (the roster) | **200** (dogfood — Phase 6.7 loosening) |
| viewer-member | `reader` | mutate membership (`team:add-member`) | **403** (dogfood) |

The demo team data (the team-target catalog id and the IdP subjects) is only known at run time, so
`run-team-matrix.sh` mints the tokens, decodes their subjects, seeds a fixed demo catalog, and
bootstraps the team + memberships via the user-service's internal API before running newman. See
[[E2E-TESTING]] for the in-network token rationale.

## Related
- The single-decision spine this builds on: [[ABAC-AUTHORIZATION]]
- The e2e harness + in-network token caveat: [[E2E-TESTING]]
- The base entity stack: [[DOMAIN-MODEL]] · The roadmap: [[POC-ROADMAP]]
- Deferred: the dynamic **tag dictionary** (Phase 4.5, [[RESEARCH-AUTOTAG-AND-FILTERING]]) and
  **ReBAC-in-Rego** (Phase 7).
