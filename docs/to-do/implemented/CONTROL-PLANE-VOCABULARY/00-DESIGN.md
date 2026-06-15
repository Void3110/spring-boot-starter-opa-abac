---
tags:
  - status/planned
  - type/architecture
  - area/user-service
  - area/abac
  - area/spring
---

# 00 — Design: Control-plane vocabulary categorization (Phase 6.7)

> The design, written from a settled **ADR [[0015-control-plane-vocabulary-categorization|0015]]** (which
> pins every fork below; grill-me interview 2026-06-15). The last **correctness** slice before publish.
> Extend the Phase-6.5 coarse-category model to the **control plane** (the user-management-service's
> `team:*` management verbs) and close the `define-tags` **enforcement** deferral. **One new category
> (`CONTROL`); `team.rego` becomes category-driven (symmetric with `catalog.rego`); no DB migration; B2's
> tri-state supplier contract untouched.**

## 1. The problem, precisely

The catalog plane is categorized (6.5): a role grants coarse tokens that **expand** through
`data.permission_categories` via `permissions.effective_actions`, refined by `denied_actions`. The control
plane is **not** — it is a second, divergent idiom (verified in code, 2026-06-15):

| Surface | Today | Consequence |
|---|---|---|
| `team.rego` | `allow if verb in input.role_definition.permissions["team"]` — **raw match, no expansion** | a divergent authorization idiom; contradicts the single-expansion-home teaching artifact |
| `TeamRoleCapabilities.BY_CODE` | a **hardcoded Java map** of system code → **fine verbs** (`owner → [read, manage, define-roles, define-tags, transfer-ownership]`, …) | the ladder is invisible to the policy; not refinable |
| `TagDefinitionController` (`team:define-tags`) | a **one-off raw gate**; `define-tags` ships in the catalog `TAG` expansion but its team enforcement is a special case (ADR 0007 addendum deferral) | the deferral this slice closes |
| custom roles | `TeamRoleCapabilities.forCode` defaults any custom code → `[read]`; `validateContract` lets a custom role *store* `team:[…]` tokens that `managementRole` then **ignores** | silent dead-data; "ceiling ≠ capability" enforced by the projection ignoring the stored value |

`team.rego` exists in **two identical copies**: `example-user-management-service/src/main/resources/opa/policies/`
(the source of truth, isolated `opa test` 14/14) and `infra/opa/policies/` (mounted into the rig's shared
OPA). The shared OPA bundle co-loads `permissions.rego` + `permission_categories.json` + `role.rego`; the
service bundle holds **only** `team.rego` + `team_test.rego`.

## 2. The decision (per surface)

### 2.1 One shared vocabulary; one new category

`team.rego`'s `allow` becomes category-driven via the **same** `data.permissions.effective_actions`, plus
an owner-only fence. Final `permission_categories.json` (both copies, byte-identical):

```json
{
  "permission_categories": {
    "READ":    ["view", "list", "list-members"],
    "WRITE":   ["create", "update", "delete"],
    "TAG":     ["define-tags", "assign-tags"],
    "GRANT":   ["assign-roles"],
    "CONTROL": ["add-member", "change-role", "remove-member"]
  }
}
```

`CONTROL` is the one new token; `READ` gains `list-members`; everything else is unchanged.

### 2.2 `team.rego` — two `allow` rules

```rego
package team

import data.permissions   # mirror catalog.rego's idiom (bare `permissions.effective_actions`)

default allow := false

verb := v if {
	parts := split(input.action, ":")
	count(parts) == 2
	v := parts[1]
}

# (1) category-driven — the delegatable verbs, expanded through the ONE shared table.
allow if {
	input.role_definition.permissions
	verb in permissions.effective_actions(input.role_definition, input.resource.type)
}

# (2) owner-only fence — never categories, never delegatable (ADR 0007 + 0015).
#     Keyed on the reserved, unspoofable owner CODE (not role_level).
allow if {
	verb in {"define-roles", "transfer-ownership"}
	input.role_definition.code == "owner"
}
```

(`input.resource.type` is `"team"` for the dogfooded gates; using it rather than a literal keeps the rule
generic and symmetric with the catalog policies.)

### 2.3 `TeamRoleCapabilities` — recast to category tokens

`BY_CODE` values change from fine verbs to **category tokens** (the fences are *not* listed — they are
authorized by the Rego owner-by-code rule, §2.2):

| Code | tokens |
|------|--------|
| `owner` | `[READ, CONTROL, TAG]` |
| `administrator` | `[READ, CONTROL, TAG]` |
| `senior` | `[READ, CONTROL]` |
| `member` / `reader` | `[READ]` |
| *default (custom)* | `[READ]` |

`EffectiveRoleService.managementRole` is otherwise **unchanged in shape** — still
`Map.of("team", TeamRoleCapabilities.forCode(code))`; it now emits categories the policy expands instead of
fine verbs it matched raw. The resource projection (`resourceRole`/`resolveForResource`) is **untouched**
(the two projections stay cleanly separate).

### 2.4 Controller annotations

- `MembershipController`: `listMembers` → **`team:list-members`** (now `READ`, not manager-only);
  `addMember` → `team:add-member`; `changeMemberRole` → `team:change-role`; `removeMember` → `team:remove-member`.
- `TagDefinitionController`: `team:define-tags` — **unchanged** (now granted via the `TAG` token).
- `RoleDefinitionController`: `team:define-roles` — **unchanged** (owner-only fence).
- transfer endpoint: `team:transfer-ownership` — **unchanged** (owner-only fence).

### 2.5 `validateContract` — reject custom control-plane tokens

A custom role with `CONTROL` (or a team-meaningful tag token) under a `"team"` key → **422
`ROLE_DEFINITION_INVALID`** (was silently stored as dead data). The catalog-plane validation is unchanged.

### 2.6 OPA bundle mirror

`permissions.rego` + `permission_categories.json` are copied **verbatim** into the service bundle. The
`CONTROL` + `list-members` edits must be applied to **both** `permission_categories.json` copies.

## 3. Behavior matrix (the externally-visible contract)

| Caller role | `list-members` | `add/change/remove-member` | `define-tags` | `define-roles` | `transfer-ownership` |
|---|---|---|---|---|---|
| `owner` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `administrator` | ✅ | ✅ | ✅ | ❌ | ❌ |
| `senior` | ✅ | ✅ (gated by tier/subset) | ❌ | ❌ | ❌ |
| `member` | ✅ **(new — was 403)** | ❌ | ❌ | ❌ | ❌ |
| `reader` | ✅ **(new — was 403)** | ❌ | ❌ | ❌ | ❌ |
| custom | ✅ | ❌ | ❌ | ❌ | ❌ |
| no membership | ❌ | ❌ | ❌ | ❌ | ❌ |

**Net authorization is identical to today except the two ⚠️ changes below.** The add/change/remove
columns reproduce the old `team:manage` outcome; the **on-whom / to-what-tier** bounds (cross-tier strict
`<`, senior subset-on-effective, target-tier, owner-protection in `MembershipService`) are an **invariant**
— untouched.

### The two intended externally-visible changes

1. **`list-members` → `READ`**: any team member can now list the roster (was owner/admin/senior only).
2. **Custom-role 422**: a custom role carrying `CONTROL`/team-tag tokens under `"team"` is now rejected
   (was silent dead-data).

Everything else that changes is a **wire-verb rename** (`team:manage` → the three specific verbs) with **no
net authorization change**.

## 4. The two-axis principle (pin it)

- **Axis 1 — the verb category** (`CONTROL` + deny-overrides): *which kinds* of membership acts a role may
  perform. **6.7 touches only this axis.** Holding `add-member` does **not** imply `change-role`.
- **Axis 2 — the shipped escalation gates** (`MembershipService`, Phase 6.5): *on whom* and *to what tier*.
  **Invariant — do not modify.** Whatever verbs a role holds, it can never act on someone above its tier or
  promote past its own ceiling.

## 5. Proof obligations

**Rego (`opa test`) — counts RISE (6.7 is not zero-Rego like B2). Baseline: infra 157, service-team 14.**
The decompose phase sets exact targets; the *direction* and *which tests change* are pinned here so a higher
count reads as success, not regression:

- **KNOWN INTENDED BREAK:** `permissions_test.rego` asserts `READ == {view, list}` at ~3 sites — **must
  update** to `{view, list, list-members}`. This is expected, not a regression.
- **ADD** `CONTROL` expansion cases to `permissions_test.rego` (same coverage `READ`/`WRITE` have).
- **REWRITE** `team_test.rego` (both copies) to the category model: every system code × every control verb;
  the owner-only fence (define-roles/transfer-ownership allowed **only** for `code == "owner"`); `define-tags`
  via `TAG` for owner/admin but **not** `senior`; `list-members` allowed for `READ`-holders; unknown-token
  denies; no-role-definition denies.
- **MIRROR:** both `permission_categories.json` copies byte-identical after the edit.

**Java unit:** `TeamRoleCapabilities` emits the right category tokens per code; `validateContract` rejects a
custom `team`-key control token (the new 422).

**Integration (headline):** a `member` **can** `GET /teams/{id}/members` **and** `member`/`reader` still get
**403** on add/change/remove + define-tags (proves the loosening is *exactly* listing and nothing wider);
`senior` manages members but **cannot** define-tags (the CONTROL-not-TAG distinction); owner/admin curate
tags; owner-only define-roles/transfer-ownership; the `MembershipService` escalation gates still hold
(re-prove a representative cross-tier/target-tier denial through the renamed verbs).

**e2e (newman):** the existing user-mgmt / permission-categories matrices stay green; **add** a control-plane
cell for the verb split and an explicit cell for the **member-can-list-members** loosening.

**B2 contract (non-goal, prove untouched):** the tri-state `RoleDefinitionSupplier` path
(`RoleResolutionException` on outage) is not modified.

## 6. Non-goals / out of scope

- **No DB / Liquibase migration** — the team ladder is a pure Java projection; system-role `"*"` resource
  seeds are untouched.
- **No custom-role team power** — "ceiling ≠ capability" (I12) stays; opening it is a separate analysis.
- **No `TAG` split** — `define-tags` stays in `TAG` (per-plane inertness is intentional, §2.1 / ADR 0015 §6).
- **No kill-switch.**
- **B2's supplier contract** and the `MembershipService` escalation gates are invariants, not reopened.

## 7. Autonomous-run risk pins (the recurring planning-gap classes)

Per [[AUTONOMOUS-IMPLEMENTATION-FLOW]] + the `autonomous-runs` synthesis (the two recurring pause classes):

- **Unpinned fail-open/contract semantic** → covered by the full behavior delta (§3) + the two-axis pin
  (§4): every allow/deny outcome and the two intended changes are enumerated, so no externally-visible
  fork is left for the run to decide.
- **Rig / two-copy gotcha** → covered by the mirror-both-bundles obligation (§2.6) + the
  both-`team.rego`-copies rewrite + "restart OPA after editing `team.rego`" (infra/README) for any live
  e2e cell. Podman-CLI flakiness (B2 retro) applies — prefer the deterministic IT over live container
  manipulation.

## 8. Related

- ADR [[0015-control-plane-vocabulary-categorization|0015]] (pins every fork) · ADR
  [[0007-coarse-grained-permission-categories|0007]] (the catalog model this extends) · ADR
  [[0014-supplier-outage-error-distinct|0014]] (the B2 contract preserved)
- [[USER-STORIES]] (Epic G, Story G4) · [[POC-ROADMAP]] (Phase 6.7) · [[PERMISSION-MODEL]] (the guide to
  update) · [[PERMISSION-CATEGORIES]] (the shipped 6.5 slice)
- **Next:** phase ② — `/decompose` (tickets, QA cases, the autonomous-implementation prompt, STATUS stubs).
