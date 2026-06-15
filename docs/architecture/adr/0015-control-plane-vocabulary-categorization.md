---
tags:
  - status/active
  - type/architecture
  - area/user-service
  - area/abac
  - area/spring
---

# ADR 0015 — Control-plane vocabulary categorization

**Status:** Accepted (planned — Phase 6.7)
**Date:** 2026-06-15
**Context tags:** control-plane verbs, coarse-vs-fine permissions, delegation, anti-escalation, dogfooding

> Authored **up front** as part of the Phase-6.7 design (grill-me interview 2026-06-15), the last
> correctness slice before publish. It extends the Phase-6.5 coarse-category model (ADR [[0007-coarse-grained-permission-categories|0007]])
> to the **control plane** — the user-management-service's `team:*` management verbs — and closes the
> `define-tags` **enforcement** deferral 6.5 left to this slice (ADR 0007 addendum; [[USER-STORIES]] Epic G,
> Story G4). The implementation slice is [[CONTROL-PLANE-VOCABULARY]] (its 00-DESIGN). **One new category
> (`CONTROL`); no DB migration; B2's tri-state supplier contract untouched.**

## Context

Phase 6.5 categorized the **catalog** plane: a role grants coarse categories (`READ`/`WRITE`/`TAG`/`GRANT`)
that **expand** through a `data.permission_categories` table consumed by a shared `permissions.rego`,
refined by `denied_actions` (deny-overrides). The **control plane** never got that treatment. Today
(verified in code, 2026-06-15):

- The user-management-service secures its own management API by **dogfooding** the starter:
  `MembershipController` / `RoleDefinitionController` / `TagDefinitionController` / the transfer endpoint
  each carry `@OpaPreAuthorize(action="team:<verb>", resourceType="'team'", resourceId="#teamId")`.
- `team.rego` does a **raw verb match** — `allow if verb in input.role_definition.permissions["team"]` —
  with **no** category expansion (unlike the catalog policies).
- The capability ladder is a **hardcoded Java map**, `TeamRoleCapabilities.BY_CODE`, emitting *fine verbs*:
  `owner → [read, manage, define-roles, define-tags, transfer-ownership]`, `administrator → [read, manage,
  define-tags]`, `senior → [read, manage]`, `member`/`reader` → `[read]`. `EffectiveRoleService.managementRole`
  projects `permissions["team"] = TeamRoleCapabilities.forCode(code)`.
- `define-tags` enforcement is the **deferral** ADR 0007's addendum carried here: it ships in the catalog
  expansion math (`TAG → [define-tags, assign-tags]`) but the tag-dictionary endpoints sit on a one-off
  `team:define-tags` raw gate.

Two consequences this slice resolves: the control plane is a **second, divergent** authorization idiom
(raw match + Java ladder) that contradicts the single-expansion-home teaching artifact; and `define-tags`
enforcement is a special case rather than part of the model.

## Decision

### 1. One shared vocabulary — control-plane verbs fold into the category model

There is **one** category vocabulary spanning both planes (not a separate control-plane taxonomy). The
`team:*` verbs become fine actions under categories in the **same** `data.permission_categories` table,
and `team.rego` expands them through the **same** `data.permissions.effective_actions` the catalog uses.
The expansion math has exactly one runtime home; `team.rego` becomes symmetric with `catalog.rego`.

### 2. One new category — `CONTROL` — and the final table

The coarse `manage` verb is **split** into membership fine actions so `CONTROL` is a *refinable* category
(the catalog's `WRITE → [create, update, delete]` pattern), refined by `denied_actions`:

| Category | Expands to | Plane(s) the action is live on |
|----------|-----------|--------------------------------|
| `READ` | `view`, `list`, **`list-members`** | catalog (view/list) · **team (list-members — new)** |
| `WRITE` | `create`, `update`, `delete` | catalog |
| `TAG` | `define-tags`, `assign-tags` | **team (define-tags)** · catalog (assign-tags) |
| `GRANT` | `assign-roles` | catalog |
| **`CONTROL`** *(new)* | **`add-member`, `change-role`, `remove-member`** | **team** |

`CONTROL` is the one new token. `READ` gains `list-members` (the roster is *visibility*). `TAG` is
**unchanged** — `define-tags` stays in it (see §6). `WRITE`/`GRANT` unchanged.

### 3. Two owner-only fences stay outside the category system

`define-roles` (author role definitions) and `transfer-ownership` are **not** categories and **not**
delegatable — they are authorized by an explicit **owner-only-by-code** rule in `team.rego`, never by a
category token. This preserves ADR 0007's pruning of the escalation-via-authoring branch and extends the
same fence to `transfer-ownership` (the same escalation class — minting the access ladder / surrendering
the team). Keying the fence on the reserved `code == "owner"` (not `role_level`) makes it **unspoofable**:
a custom role can carry any level but never the `owner` code, so no custom or non-owner system role can
ever hold these two verbs.

`team.rego` therefore has two `allow` rules: a **category-driven** one (`verb in
effective_actions(role_def, "team")`) for the delegatable verbs, and the **owner-only fence** rule for the
two reserved verbs.

### 4. The two-axis separation (the slice's organizing principle)

Authorization on the control plane is two **orthogonal** axes:

1. The **verb category** (`CONTROL` + deny-overrides) decides *which kinds* of membership acts a role may
   perform. This is the only axis 6.7 touches.
2. The **shipped escalation gates** in `MembershipService` (Phase 6.5 — cross-tier strict `<`, the senior
   subset-on-effective verdict, the target-tier gate, owner-protection) decide *on whom* and *to what tier*.
   These are an **invariant** 6.7 preserves untouched.

Consequence: holding `add-member` does **not** imply `change-role` (independent fine actions); and whatever
verbs a role holds, the gates still forbid acting on anyone above the actor's tier or promoting past the
actor's own ceiling.

### 5. Custom roles stay management-incapable (preserve "ceiling ≠ capability")

A team-scoped **custom** role may **not** carry live control-plane power. `managementRole` projects a
custom code to `READ`-only on `type:"team"` regardless of what it stores; only the reserved **system**
codes carry the management ladder. The ladder stays a **Java projection** (`TeamRoleCapabilities`, recast
from fine verbs to **category tokens**):

| Code | `permissions["team"]` tokens | Effective control-plane verbs |
|------|------------------------------|-------------------------------|
| `owner` | `[READ, CONTROL, TAG]` + the two fences (by code) | list-members, add/change/remove, define-tags, **define-roles, transfer-ownership** |
| `administrator` | `[READ, CONTROL, TAG]` | list-members, add/change/remove, define-tags |
| `senior` | `[READ, CONTROL]` | list-members, add/change/remove (**no define-tags**) |
| `member` / `reader` | `[READ]` | list-members only |
| *custom* | `[READ]` (projection-forced) | list-members only |

`RoleDefinitionService.validateContract` is **tightened**: a custom role putting `CONTROL` (or a
team-meaningful tag token) under a `"team"` key is **rejected (422)** — today it is silently stored as
dead data (`managementRole` ignores it). Turning the footgun into an honest error breaks no working flow.

This is preserved *because* the system management ladder is a small, fixed, audited closed set the
escalation surface can be fully reasoned about; opening custom-role team power is a separate, larger
analysis explicitly out of scope.

### 6. `TAG` is left intact — `define-tags` is not moved

`define-tags` (team governance) and `assign-tags` (catalog resource-mutation) are semantically different,
but **no catalog endpoint ever asks `define-tags`** and **no team endpoint ever asks `assign-tags`** — each
token has exactly **one live action per plane**. Splitting `TAG` would require either a second new category
(churning the shipped catalog seeds/tests for a cosmetic gain) or folding `define-tags` into `CONTROL`
(which would newly grant tag-dictionary curation to `senior`, an escalation). `TAG` is therefore kept as
`[define-tags, assign-tags]`; the per-plane inertness is **intentional**. `define-tags` re-gating is purely
mechanical: the `team:define-tags` annotation is unchanged, now *granted* via the `TAG` token in the
owner/admin ladder and *expanded* by `team.rego`; allow/deny outcomes are identical (owner/admin curate,
everyone else 403; `senior` correctly stays without it, holding `CONTROL` not `TAG`).

### 7. OPA artifacts mirror into the dogfood service bundle

`team.rego` now depends on `data.permissions.effective_actions` + `data.permission_categories`. At
**runtime** both planes hit the one shared OPA container (mounts `infra/opa/policies/`, which co-loads
everything), so no runtime wiring changes. But the user-management-service's own bundle
(`example-user-management-service/src/main/resources/opa/policies/`, the source of truth for `team.rego`,
tested in isolation) holds only `team.rego` + `team_test.rego`. So `permissions.rego` +
`permission_categories.json` are **copied verbatim** into that bundle so the dogfood `team.rego` resolves
and `opa test` stays self-contained per module. The `CONTROL` + `list-members` edits land in **both**
`permission_categories.json` copies (a mirror obligation).

## Considered options

| Option | Why not |
|--------|---------|
| **Separate control-plane category set** (`MANAGE_MEMBERS`, etc.) | Two parallel taxonomies double the surface a reader learns and fork `permissions.rego`; the single-expansion-home teaching artifact is the whole point. |
| **Reuse `WRITE` for `manage`** (`WRITE → [..., manage]`) | Overloads `WRITE` to mean CRUD on catalog types *and* manage on team — a token meaning different things by type; the exact ambiguity ADR 0007 avoided when it rejected naming the access bucket `MANAGE`. |
| **`manage` stays a single coarse verb** (`CONTROL → [manage]`) | A category that expands to one action cannot be refined — categories exist to be refined by deny-override (the catalog `WRITE` pattern). Splitting into membership fine actions is what makes "grant control but deny remove-member" expressible. |
| **`define-roles`/`transfer-ownership` as categories** | Either could then be carried by a non-owner/custom role — reopening the escalation-via-authoring branch ADR 0007 closed. They must stay owner-only fences. |
| **Fence keyed on `role_level >= 40`** | A custom role can carry any level; keying on level would let a custom level-40 role pass. The reserved `owner` *code* is unspoofable. |
| **Open custom-role team power** (read stored `team` tokens for custom roles) | A separate, larger escalation analysis; would bloat this slice and enlarge the trust surface. "Ceiling ≠ capability" (the pinned I12 cell) stays. |
| **Split `TAG`** (new `DEFINE_TAGS`, or fold `define-tags` into `CONTROL`) | A 2nd new category churns shipped catalog seeds/tests for a cosmetic gain; folding into `CONTROL` newly grants `define-tags` to `senior` (an escalation). Per-plane inertness makes the impurity harmless. |
| **Move the ladder into OPA `data`** | Creates a second team-capability lookup table, breaking the single-expansion-home story; the ladder is a *projection* of the role code, not stored state. |
| **Inline expansion into `team.rego`** (self-contained) | Forks the expansion logic — violates the single-expansion-home principle. |

## Consequences

- **Good:** the control plane uses the *same* category model, expansion table, and `effective_actions` as
  the catalog — `team.rego` is now symmetric with `catalog.rego`, and the `define-tags` deferral is closed
  by making its enforcement uniform with the model (no special case). `CONTROL` is refinable
  (grant CONTROL, deny `remove-member` → onboard/retitle but never remove — the senior-IC-onboards-juniors
  persona). The two owner-only fences are now **visible in the policy** (Rego), not buried in a Java map.
- **Externally-visible behavior delta — exactly two intended changes** (everything else is net-identical
  authorization, only wire-verb renames `team:manage` → `team:add-member`/`change-role`/`remove-member`):
  1. `list-members` moves to `READ`, so **any team member can list the roster** (today manager-only) — the
     natural "READ = visibility" consequence.
  2. A custom role carrying control-plane tokens under `"team"` now answers **422** (was silent dead-data).
- **Cost:** the management projection keeps a system-vs-custom branch (system codes carry the ladder; custom
  codes project `READ`). That asymmetry is correct — system management roles and custom resource roles are
  different things. `opa test` counts **rise** in both bundles (6.7 is not zero-Rego like B2); the
  `permissions_test.rego` `READ`-expansion assertions are a **known intended break** (`{view, list}` →
  `{view, list, list-members}`), not a regression.
- **Additivity / non-goals:** no DB or Liquibase migration — the team ladder is a pure Java projection;
  system-role `"*"` *resource* seeds are untouched. B2's tri-state `RoleDefinitionSupplier` contract
  (`RoleResolutionException` on outage) is **untouched** — 6.7 changes neither the supplier path nor the
  fail-closed contract. No kill-switch.

## Related

- ADR [[0007-coarse-grained-permission-categories|0007]] (the catalog category model + the five-tier ceiling
  this extends; its addendum deferred `define-tags` enforcement here) · ADR 0004 (`team:define-tags`
  capability) · ADR 0006 (the enforcement layers) · ADR [[0014-supplier-outage-error-distinct|0014]]
  (the tri-state supplier contract 6.7 must preserve)
- [[CONTROL-PLANE-VOCABULARY]] (the Phase-6.7 slice design) · [[USER-STORIES]] (Epic G, Story G4 — the
  `define-tags` enforcement flow) · [[POC-ROADMAP]] (Phase 6.7) · [[PERMISSION-CATEGORIES]] (the shipped
  6.5 slice) · [[PERMISSION-MODEL]] (the guide this updates)
