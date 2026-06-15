---
tags:
  - status/planned
  - type/index
  - area/user-service
  - area/abac
  - area/spring
---

# Phase 6.7 — Control-plane vocabulary categorization

> **Status: 🔜 PLANNED** (route step 2 — the last **correctness** slice before publish; route
> **B2 ✅ → 6.7 → Phase 6 → B3 → Phase 7**). Extend the Phase-6.5 coarse-category model to the
> **control plane** (the user-management-service's `team:*` management verbs) and close the
> `define-tags` **enforcement** deferral 6.5 left here ([[USER-STORIES]] Epic G, Story G4). Pinned by
> **ADR [[0015-control-plane-vocabulary-categorization|0015]]**; the full design is [[00-DESIGN]].
> **One new category (`CONTROL`); `team.rego` becomes category-driven (symmetric with `catalog.rego`);
> no DB migration; B2's tri-state supplier contract untouched.**

## What it is

The catalog plane is categorized (6.5): roles grant coarse `READ`/`WRITE`/`TAG`/`GRANT` tokens that
**expand** through `data.permission_categories` via `permissions.effective_actions`, refined by
`denied_actions`. The **control plane** never got that treatment — `team.rego` does a **raw verb match**
and the capability ladder is a **hardcoded Java map** (`TeamRoleCapabilities`). 6.7 folds the control
plane into the **one** shared vocabulary so the teaching artifact stays singular and `define-tags`
enforcement is no longer a one-off.

## The shape (settled — ADR 0015 / [[00-DESIGN]])

- **One new category — `CONTROL` → `[add-member, change-role, remove-member]`** (the coarse `manage` verb
  split so the category is **deny-refinable**, the catalog's `WRITE` pattern). `list-members` moves to
  `READ` (the roster is *visibility*). `TAG`/`WRITE`/`GRANT` unchanged.
- **`team.rego` becomes category-driven** via the shared `permissions.effective_actions` (symmetric with
  `catalog.rego`), plus an **owner-only-by-code fence** for `define-roles`/`transfer-ownership` (the two
  escalation-sensitive verbs stay outside the category system; keyed on the unspoofable `owner` code).
- **`TeamRoleCapabilities` recast** from fine verbs to **category tokens** (`owner`/`administrator` →
  `[READ, CONTROL, TAG]`, `senior` → `[READ, CONTROL]`, `member`/`reader`/custom → `[READ]`). The
  management projection stays Java; the resource projection is untouched.
- **Custom roles stay management-incapable** (the I12 "ceiling ≠ capability" fence); `validateContract`
  now **422**s a custom role carrying control tokens under `"team"` (was silent dead-data).
- **`define-tags` re-gated mechanically** — the `team:define-tags` annotation is unchanged, now granted via
  the `TAG` token and expanded by `team.rego`; allow/deny outcomes are identical.

## The two-axis principle

Authorization on the control plane is two **orthogonal** axes: the **verb category** (`CONTROL` +
deny-overrides) decides *which kinds* of acts a role may perform; the **shipped escalation gates**
(`MembershipService` — cross-tier, senior subset, target-tier, owner-protection) decide *on whom* and *to
what tier*. **6.7 touches only the first axis; the second is an invariant.**

## The two intended externally-visible changes

1. `list-members` → `READ`: **any team member can now list the roster** (was manager-only).
2. A custom role carrying control-plane tokens under `"team"` now answers **422** (was silent dead-data).

Everything else is net-identical authorization (only wire-verb renames `team:manage` → the three specific
verbs).

## Package

- [[00-DESIGN]] — the per-surface map, behavior matrix, proof obligations, autonomous-run risk pins.
- ADR [[0015-control-plane-vocabulary-categorization|0015]] — pins every fork + the considered-options table.
- [[01-DECOMPOSITION]] — the four tickets (the work list) + the critical path + the three pinned semantics.
- [[10-QA-TEST-CASES]] — the R / U / I / E / D cases each ticket's acceptance references.
- [[AUTONOMOUS-IMPLEMENTATION-PROMPT]] — the self-contained phase-③ run prompt (kept verbatim).
- `STATUS-01…04.md` — one stub per ticket, filled at each checkpoint during the run.

## Tickets

| Ticket | Scope | Status |
|--------|-------|--------|
| **T1** | Vocabulary + policy: the `CONTROL` category (both `permission_categories.json` copies), the category-driven `team.rego` + owner-only-by-code fence (both copies), the service-bundle `permissions.rego` mirror, the `permissions_test.rego` + `team_test.rego` rewrites | ☐ |
| **T2** | Resolve-side: `TeamRoleCapabilities` recast to category tokens; `RoleDefinitionService.validateContract` rejects custom `"team"` control tokens (the new 422) | ☐ |
| **T3** | Controller verbs: split `team:manage` → `team:list-members`/`add-member`/`change-role`/`remove-member` in `MembershipController` | ☐ |
| **T4** | IT (the behavior matrix + the two-axis re-proof) + e2e (verb-split + member-can-list cells) + docs reconcile + record + folder move | ☐ |

**Critical path:** `T1 → {T2, T3} → T4`. T1 is the independently-landable policy core; T1+T2 the smallest
app-correct subset.

## Related

- ADR [[0007-coarse-grained-permission-categories|0007]] (the catalog model this extends) · ADR
  [[0014-supplier-outage-error-distinct|0014]] (the B2 contract preserved)
- [[PERMISSION-CATEGORIES]] (the shipped 6.5 slice) · [[PERMISSION-MODEL]] (the guide to update) ·
  [[POC-ROADMAP]] (Phase 6.7) · [[USER-STORIES]] (Epic G, Story G4)
