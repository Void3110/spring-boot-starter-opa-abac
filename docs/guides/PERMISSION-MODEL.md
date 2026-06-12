---
tags:
  - status/active
  - type/guide
  - area/abac
  - area/opa
  - area/user-service
---

# The permission model — coarse categories, deny-overrides, and safe delegation

> Phase 6.5 (ADR [[0007-coarse-grained-permission-categories|0007]] + its implementation addendum).
> A role grants **coarse categories** that **expand to fine actions** in OPA `data`, refined by
> **deny-overrides**, bounded by a **five-tier level ceiling**, and delegated through **two
> assignment gates**. This guide is the shipped contract; the design record (the ten settled
> forks) lives in the PERMISSION-CATEGORIES package under `docs/to-do/implemented/`.

## The four categories and the expansion table

A `RoleDefinition.permissions` value is a list of **category tokens** per resource type:

| Category | Expands to (fine actions) |
|---|---|
| `READ` | `view`, `list` |
| `WRITE` | `create`, `update`, `delete` |
| `TAG` | `define-tags`, `assign-tags` |
| `GRANT` | `assign-roles` |

The table lives in **OPA `data`** — `infra/opa/policies/permission_categories.json` →
`data.permission_categories` — and is consumed by one shared module,
`infra/opa/policies/permissions.rego`:

- `effective_actions(role_def, type)` = the union of the expansions of the tokens granted for
  `type`, **minus** `denied_actions[type]`. Lookup is **wildcard-aware**: the concrete type key
  wins; `"*"` backs it up when the key is absent — for grants **and** denials symmetrically (the
  Java resolve wire, `EffectiveRoleService.expandWildcard`, shadows identically, so the two homes
  cannot diverge).
- `effective_from_categories(cats)` — the expansion of a literal category set; the **realm
  fallback** maps through it (`catalog-viewer → {READ}`, `catalog-editor → {READ,WRITE,TAG}`), so
  even the no-role-definition path speaks the same table.

The app-side `PermissionCategories` constant (user-service) exists for **422-time validation
only** and is parity-pinned to the JSON by a unit test — the runtime decision home is OPA, full
stop.

### The fail-closed floor: ∅-expansion

An unknown or stale token — a pre-6.5 flat `read`, a typo, a removed category — **expands to
nothing**. A role holding only stale tokens therefore **decides nothing** (and, because a present
role definition disables the realm fallback, the request is denied). This is the clean cut's
replacement for compatibility machinery: stale data degrades to deny, never to a guess.

## Deny-overrides — and their per-type scope

`RoleDefinition.denied_actions` maps a resource type to fine actions subtracted **after**
expansion: `{"category": ["delete"]}` on a `WRITE` grant leaves `create`/`update` and removes
`delete`. Denials only ever **narrow** — denying a never-granted action is rejected at authoring
and inert at decision time.

**The subtlety adopters must know:** subtraction is **per type**, and the hierarchical
`inherited_grant` consults the **ancestor type's** effective set. A role granting `WRITE` on
`catalog` with `denied_actions: {"category": ["delete"]}` can still delete a category — through
the catalog-type grant the denial never touched. **To fence an action subtree-wide, deny it on
every granted type, or use the wildcard key** (`denied_actions: {"*": ["delete"]}`).

## The five-tier ladder and the authoring contract

| Code | `role_level` | Ceiling (grantable categories) |
|---|---|---|
| `reader` | 10 | `READ` |
| `member` | 20 | `READ`, `WRITE`, `TAG` |
| `senior` | 25 | `READ`, `WRITE`, `TAG` |
| `administrator` | 30 | `READ`, `WRITE`, `TAG`, `GRANT` |
| `owner` | 40 | (never authorable — seeded only) |

Custom roles are authored (owner-only, `team:define-roles`) by picking a `roleLevel` from the
authorable ladder (`10/20/25/30`). The contract, enforced in `RoleDefinitionService` — **every**
violation answers `422 problem+json errorCode=ROLE_DEFINITION_INVALID`, including a missing
`roleLevel` (deliberately not a schema constraint, so the whole contract has one shape):

1. `roleLevel ∈ {10, 20, 25, 30}`;
2. every permission token is one of the four categories (flat verbs and fine actions are retired
   at the API boundary);
3. granted categories ⊆ the level's ceiling (`GRANT` only at 30);
4. **strict denials** — per type, `denied_actions[type] ⊆ expand(granted for that type)`
   (wildcard-aware lookup, mirroring the policy);
5. the explicit `roleLevel` is the **single source** of `attributes.role_level` — an
   attributes-supplied value is overwritten.

The internal bootstrap endpoint routes through the same service — seeding is **not** a
validation bypass.

## Delegation: the two assignment gates

Assignment (`addMember`/`changeRole`) is decided **under the team-row lock** on lock-read
snapshots (decide-under-protection; the latch IT proves a stale snapshot would have passed):

1. **Cross-tier, everyone:** `actorLevel > candidateLevel`, strictly — an administrator cannot
   mint a peer administrator. Levels come from `attributes.role_level`; a **missing or
   non-numeric level on either side rejects** (never 0-and-pass).
2. **At senior (25) only, additionally:** the candidate must sit at or below the member tier
   (≤ 20) **and** OPA's `data.role.assignable` verdict must answer `true` — the candidate's
   effective actions a subset of the senior's, per type, over the two **raw row snapshots**
   (the policy does the wildcard + denial math; Java never reimplements set algebra).
3. **Acting on an existing member** (`changeRole` / `removeMember`) additionally applies the
   **target-tier gate**: a member whose *current* `role_level` is **above** the actor's cannot be
   demoted or removed by them — a senior cannot demote or remove an administrator. Peers stay
   manageable (an administrator can remove a peer administrator, the pre-6.5 cell). The asymmetry
   is deliberate: an unreadable **target** level never outranks (revocation only narrows access,
   and a member holding a corrupted role must stay removable), while an unreadable **actor** level
   still rejects.

Every rejection — level, the senior bound, the subset verdict, the target-tier rule, **and any
OPA error/timeout during the verdict** — is the one `422 ROLE_SUBSET_VIOLATION` contract: an OPA
outage is deliberately indistinguishable from "not assignable" (fail-closed by
indistinguishability). The verdict call is the app-side `RoleAssignableClient` (short timeouts; any
non-answer → `false`); the level gates run first, so the verdict is never consulted when the tier
already rejects.

**Tier is power; ceiling is not.** Admin delegation is seniority, not subset — an administrator
whose own role denies `delete` still assigns full `WRITE` (the designed cell). And a **custom**
level-25 role has senior's authoring ceiling but **no live assign power**: management capability
(`team:manage`) keys on the system role **codes** (`TeamRoleCapabilities`), not on levels.

## The TAG/WRITE boundary: the delta-dispatched second decision

A static `@OpaPreAuthorize(<type>:update)` cannot express "TAG-without-WRITE relabels but never
edits", so the **category update** handler dispatches on what the request actually changes, via
the annotated `TagDecisionGate` bean (the manager seam + the 5.97 request cache reused — zero
library change):

- content delta → the `category:update` decision;
- tags delta → the `category:assign-tags` decision;
- both → both (update first); an **empty delta → `update`** (conservative — a no-op PUT by a
  TAG-only holder answers 403).

Create keeps its static `category:create` annotation plus a conditional **type-level**
`assign-tags` decision when the request carries tags. All decisions precede the version guard,
the tag validation, and any mutation. The dispatch lives on the **category** handlers only —
catalog/product requests carry no tags field, so their PUTs keep a static `<type>:update` (and,
because the load necessarily precedes an in-handler dispatch, the category PUT's missing-id
answer is the handler's **404**; the annotated GET keeps the 5.97 **403** pin). The accepted
trade-off of that 404: the category PUT is an **id-existence oracle** for callers with no grant
(missing id → 404, existing-but-denied → 403) — deliberate and bounded to this one endpoint;
every statically annotated handler keeps the uniform 403.

## `define-tags`: shipped in the math, enforcement deferred

The `TAG` category expands to `define-tags` and `assign-tags`, and `assign-tags` is enforced (the
second decision above). The **dictionary endpoints** still gate on the management verb
`team:define-tags` — moving them onto the resource-side `define-tags` action is the control-plane
slice (Phase 6.7), together with the rest of the `team:*` vocabulary.

## Where things live

| Concern | Home |
|---|---|
| The expansion table | `infra/opa/policies/permission_categories.json` (OPA `data`) |
| The effective-set math | `infra/opa/policies/permissions.rego` (one runtime home) |
| Per-type decisions | `catalog.rego` / `category.rego` / `product.rego` (direct, inherited, list gate, fallback) |
| The list filter | `category.rego` `filter` — `"list" ∈` the expansion, consumed **inline** (the PE-friendly idiom; a function call would not fold under partial evaluation) |
| The subset verdict | `infra/opa/policies/role.rego` (`data.role.assignable`, default `false`) |
| The authoring contract | `RoleDefinitionService` + `PermissionCategories` (user-service) |
| The assignment gates | `MembershipService` (+ `RoleAssignableClient`) under `lockTeam` |
| The delta dispatch | `TagDecisionGate` + `CategoryController` (catalog service) |
| The live proof | `scripts/postman/run-permission-categories-matrix.sh` (fixture `9999…`) |

## Related

[[ABAC-AUTHORIZATION]] · [[TEAM-BASED-AUTHORIZATION]] · [[TAG-BASED-AUTHORIZATION]] ·
[[PARTIAL-EVALUATION-FILTERING]] · [[HIERARCHICAL-AUTHORIZATION]] ·
[[ATTRIBUTE-RICH-PRE-AUTHORIZATION]] · ADR [[0007-coarse-grained-permission-categories|0007]] ·
ADR [[0003-role-definitions-role-not-grant|0003]] · ADR [[0004-dynamic-tag-dictionary|0004]] ·
ADR [[0006-three-layer-enforcement-model|0006]]
