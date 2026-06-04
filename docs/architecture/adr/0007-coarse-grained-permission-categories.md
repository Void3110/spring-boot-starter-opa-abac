---
tags:
  - status/active
  - type/architecture
  - area/user-service
  - area/abac
  - area/spring
---

# ADR 0007 — Coarse-grained permission categories + the five-tier ceiling model

**Status:** Accepted (planned — Phase 6.5)
**Date:** 2026-06
**Context tags:** role definitions, coarse-vs-fine permissions, delegation, anti-escalation

> Authored **up front** as part of decomposition (per the [[adr/README|ADR convention]]). It pins the
> permission *taxonomy* and the *delegation* model for the catalog domain. The implementation slice is
> Phase 6.5 ([[POC-ROADMAP]]); the user-facing flows are in [[USER-STORIES]] (Epic G). The decision was
> reached through web research of the major platforms (AWS, Azure, GCP, GitHub, Heroku, Kubernetes) and a
> structured design interrogation.

## Context

Today a `RoleDefinition.permissions` is a flat `{resourceType: [verbs]}` map and the verbs are just
`read`/`write` (write conflates create/update/delete). That is too coarse to express what a real team
needs ("can edit products but not delete", "can manage tags but not the dictionary", "can onboard members
but not grant admin") and has **no grouping** for a UI. We want two things at once:

1. **Coarse buckets a human selects** ("give this role *write* on categories") that **expand** to fine
   actions — so a role editor offers "pick the whole category, or refine."
2. A **delegation model** that lets a catalog owner safely share management with a team — without any
   delegate being able to escalate their own or others' privileges.

The driving personas are a software team: a **project owner**, a **lead/architect**, **senior devs**,
**mid devs/analysts**, and **read-only stakeholders**. Each needs a different slice of authority, and the
gap between "full admin who grants roles" and "plain member who grants nothing" is real (a senior who
onboards juniors but isn't an admin).

## Decision

### 1. Four coarse permission **categories** (clean-room, catalog domain)

| Category | Expands to (fine actions) | The line it draws |
|----------|---------------------------|-------------------|
| **`READ`** | `view`, `list` | see content (incl. the Phase-5 list path) |
| **`WRITE`** | `create`, `update`, `delete` | mutate content — sub-categories, descriptions/marketing fields, products |
| **`TAG`** | `define-tags`, `assign-tags` | curate the tag vocabulary + apply tags to categories/products |
| **`GRANT`** | `assign-roles` | the fenced top capability — assign existing roles to members |

Named to follow cross-platform precedent: **`GRANT`** (not `MANAGE`) for the access capability, because
every platform fences "grant access to others" as a distinct, top-of-ladder power (AWS *Permissions
management*; Azure Owner = Contributor + `Microsoft.Authorization/*`; GCP Owner = Editor + `setIamPolicy`;
GitHub *Admin*; K8s *admin* creates RoleBindings) — `MANAGE` is ambiguous between *manage-config* and
*manage-access*, the exact line the rest of the model works to keep sharp. **`TAG`** (not `CURATE`) for
plainness, and it lines up with AWS's first-class *Tagging* access level and the shipped `team:define-tags`
capability (ADR 0004).

### 2. Categories-only grants + deny-overrides (Azure `Actions`/`NotActions`)

A role grants **whole categories**; there is **no à-la-carte individual-action grant**. To be surgical,
grant the category and **subtract** specific fine actions:

```
effective_actions(type) = expand(granted_categories) \ denied_actions
allow if requested_action ∈ effective_actions(type)
```

- A **denial is a global hard veto, applied last** (Azure `NotActions` semantics). Because there is only
  one grant path (categories), a denied action can never be silently re-granted.
- **New actions propagate by default:** an action added to `WRITE` later is automatically included in every
  role holding `WRITE`, unless explicitly denied — Azure's documented rationale for `Actions`/`NotActions`.
- The role stores **intent** (category tokens + denials), not **effect** (a flat action list), so a role
  editor round-trips losslessly ("WRITE ☑ as a category" vs "I happened to pick all of WRITE").

**Expansion lives in OPA:** the role stores category tokens; the category→action **expansion table lives in
OPA `data`** (editable without a policy redeploy); the Rego rule does *expand-minus-deny then membership*.
This keeps the policy the place the interesting work happens (the teaching artifact) without hardcoding the
taxonomy in a rule.

### 3. `role_level` is a permission **ceiling/template**, not a free integer

Authoring a role starts by **picking a level**. The level **auto-selects and locks** the categories it
permits (higher categories are disabled/uncheckable in the editor); the owner then refines **downward only**
via deny-overrides. So `role_level` bounds *what a role may contain* — `permissions = expand(ceiling) \
denials`.

**Five authored tiers + `owner` reserved:**

| Level | Ceiling (categories) | Assignment authority |
|-------|----------------------|----------------------|
| **reader** (10) | `{READ}` | none |
| **member** (20) | `{READ, WRITE, TAG}` | none |
| **senior** (25) | `{READ, WRITE, TAG}` + a *constrained* assign-members power | may add members of level **≤ member**, gated by the subset rule (below) |
| **administrator** (30) | `{READ, WRITE, TAG, GRANT}` | may assign any role **strictly below admin** |
| **owner** | reserved root | authors role *definitions*; assigns anything; transfers ownership |

### 4. Two fences (invariants)

1. **`define-roles` (authoring role definitions) is owner-only** — never a category, never delegatable.
   Administrators do everything *except* author roles. (Delegating authoring was rejected: it forces an
   author-can't-mint-above-themselves ceiling and a recursive trust analysis for little gain.)
2. **`GRANT` (assign roles) tops out at administrator.** The **senior** tier deliberately does **not**
   hold `GRANT`; it gets a *constrained* assign-members power (≤ member only). A senior therefore **cannot
   propagate assignment power** — self-replication of the senior/grant capability is *structurally*
   impossible, not a special-cased rule.

### 5. Two assignment gates — each doing one job

- **`role_level` strict `<`** — the **cross-tier seniority** gate. An administrator may assign any role
  *strictly below* admin (so: admin can't add admin; member/reader can't be out-ranked). Coarse, a single
  integer compare over a system-defined ladder.
- **The subset rule on *effective* permissions** — used **only at the senior tier**. A senior may assign a
  role only if `effective(R) ⊆ effective(senior)` (and, by the level ceiling, `R` carries no `GRANT`). This
  is the Kubernetes anti-escalation rule, computed on **expanded-minus-denied** sets — the *same*
  `effective_actions` function used at decision time, reused at assignment time.

> **The subset rule is optional complexity that buys exactly one feature — the senior delegate.** Drop the
> senior tier and the subset rule can be dropped entirely; assignment would run on `role_level` alone. The
> ADR states this so the cost is legible: subset exists *because* a senior IC should onboard juniors without
> being an admin.

## Considered options

| Option | Why not |
|--------|---------|
| **Keep flat `read`/`write` verbs** | Can't express delete-vs-edit, tag-curation, or delegation; no UI grouping. The whole point is the coarse bucket + refine. |
| **Name the access bucket `MANAGE`** | Ambiguous (manage-config vs manage-access) — blurs the exact line every platform keeps sharp. `GRANT` names the real capability. |
| **Allow à-la-carte individual-action grants alongside categories** | Re-introduces "denied-but-separately-granted" ambiguity and a footgun. Categories-only + deny-overrides has one grant shape and an unambiguous veto. |
| **Expand categories at authoring time (store the flat action list)** | Stores *effect* not *intent*: the editor can't tell "whole category" from "happened to pick all," and new actions don't propagate. Storing tokens + a `data` expansion table is lossless and teaches OPA doing the work. |
| **`role_level` as a free integer the owner tunes per role** | More knobs, more drift. A small system-defined enum (reader/member/senior/admin) as a *ceiling* is simpler and maps to personas. |
| **Pure `role_level` seniority for all assignment (no subset rule)** | Can't express "members hand out a *subset* of what they hold" and contradicts itself (member-adds-member needs `≤`, admin-adds-admin needs `<`). The hybrid gives each gate one job. |
| **Pure subset rule for all assignment (no `role_level`)** | Two admin roles with identical permission sets satisfy `⊆` reflexively, so subset alone can't stop admin-adds-admin. `role_level` strict `<` catches the peer case. |
| **Delegate role *authoring* to administrators** | Forces author-can't-mint-above-self ceilings + recursive trust analysis. Owner-only authoring prunes the entire escalation-via-authoring branch. |
| **Let senior hold full `GRANT`** | A senior could then assign a `GRANT`-bearing role and self-replicate the assignment power. Capping `GRANT` at admin makes that impossible by construction. |

## Consequences

- **Good:** a coarse-yet-refinable permission model that maps 1:1 to a UI (pick a level → ceiling locks →
  deny to refine) and to real dev-team personas (PM=owner, lead/architect=admin, senior-dev=senior,
  mid-dev/analyst=member, stakeholder=reader); a delegation model that is safe by construction (owner-only
  authoring + `GRANT` capped at admin + subset-at-senior) and traces every choice to a cloud precedent; the
  `effective_actions` expansion is shared between the decision path and the assignment-subset check (one
  function, two call sites).
- **Cost:** the assignment check is no longer a single integer compare for the senior tier — it expands
  categories, subtracts denials, and compares sets. This is the *only* place subset is needed, and it is
  explicitly the price of the senior persona. The "which roles may I assign?" UI list is a set computation —
  but it is structurally the same question as action enrichment (Phase 6, [[ACTION-ENRICHMENT]]), so it has
  a home.
- **Additivity:** reuses the shipped `role_level` attribute (the `owner 40 / administrator 30 / member 20 /
  viewer 10` seeds) and the `{type: [verbs]}` permissions shape — a *category* token is new; a plain action
  token still works, so existing flat roles keep deciding unchanged. The new pieces (the `data` expansion
  table, the deny list, the level ceilings, the two gates) are additive.
- **Follow-on:** the category→action expansion and the deny-override are the substrate action **enrichment**
  (Phase 6) reports against ("which of this role's actions may I perform?"), and the "which roles may I
  assign?" question is the same batch shape.

## Related
- ADR 0003 (role ≠ grant; the subset rule and `role_level` this refines) · ADR 0004 (the `TAG` category's
  `define-tags`/`assign-tags`) · ADR 0006 (the enforcement layers that consume these permissions)
- [[USER-STORIES]] (Epic G — the delegation flows) · [[ACTION-ENRICHMENT]] (the "which actions/roles may I
  pick" UI machinery) · [[POC-ROADMAP]] (Phase 6.5) · [[USER-MANAGEMENT-SERVICE]] (where role definitions live)
