---
tags:
  - status/active
  - type/decision
  - area/abac
  - area/rego
---

# ADR 0031 — Ancestor inheritance is confined to membership-derived roles

**Status:** Accepted — planning; **amends** [[0029-supervised-read-scope|ADR 0029]] (its §Decision
per-slice table row for slice **A**, and the zero-Rego consequence drawn from it); implemented by slice **A**, [[SUPERVISED-SCOPE]] T3
**Date:** 2026-08-02
**Context tags:** ancestor inheritance, role provenance, synthesized roles, fail-closed default, policy invariant

> A role only inherits a verb from an ancestor type when it is **membership-derived**, proven by a
> provenance stamp applied at the single role-resolution funnel. Every **synthesized** (non-membership)
> role — the supervisor role now, the tiered and elevated roles of slices B and C later — is therefore
> **confined to the types it names explicitly**, by default and without further action.

## Context

ADR 0029 pinned that slice A's supervisor role grants the coarse token `catalog: ["READ"]` and *nothing*
on `category` or `product`, and concluded, in the slice-**A** row of its per-slice grant table, that **"contents are closed by the role, so slice A
ships zero Rego changes."**

**That conclusion is false against the shipped corpus, and was proven false by evaluation, not argument.**
`category_inheritable.json` declares `catalog → category` inheritance (and `product_inheritable.json` the
same for products), and `category.rego`'s `inherited_grant` admits a leaf action when the role's effective
actions **on the ancestor type** contain the verb. `catalog: ["READ"]` expands to `view, list,
list-members`, so `category:view` is inherited from the catalog the supervisor may read. Against
`infra/opa/policies` with the exact role ADR 0029 pins:

| input | `allow` |
|---|---|
| `category:view`, ancestors `[catalog]` (the runtime shape) | **true** ← the leak |
| `product:view`, ancestors `[catalog]` | **true** |
| type-level `category:list` (via `list_inheritable_grant`) | **true** |
| `category:view` with **no** ancestors | false |

The last row is why the defect survived planning: an ancestor-less probe returns `false`, and slice A's
acceptance case U14 was written that way — a green-lighting trap. At runtime the resolver always supplies
the ancestor chain, so the passing probe never matched the request being authorized.

Two further facts constrain any fix:

1. **A synthesized role and a wildcard-expanded membership role are indistinguishable in the policy
   input.** `EffectiveRoleService.resourceRole()` expands a stored `"*"` to the *requested* target type,
   so a wildcard member resolved on a catalog arrives as `{catalog: [tokens]}` — byte-identical in shape
   to the supervisor role. No structural test can separate them; a **marker is mandatory**.
2. **No data-only fix exists.** Inheritance keys on the *same verb name* across types, so no token choice
   separates "catalog metadata yes, child contents no"; `denied_actions` on `category` does not apply
   (the rule tests the *ancestor* type's effective actions); and denying `view` on `catalog` would remove
   the metadata read the slice exists to provide.

## Decision

**Ancestor inheritance requires membership provenance.**

1. **One provenance key.** `RoleDefinition.attributes.provenance` carries a value vocabulary —
   `"membership"` for a role resolved from a team membership, `"supervised"` for slice A's synthesized
   supervisor role, and one value per future synthesized role. The `attributes` map already exists and is
   always serialized, so the **wire contract is unchanged**.
2. **Stamped at the funnel, not stored.** `EffectiveRoleService.resourceRole(...)` — the single
   construction site for roles that reach the **catalog-side** policies (`catalog`/`category`/`product`)
   — stamps `provenance: "membership"`. (`managementRole(...)` is a *second* construction site, but it
   serves the user-service's own dogfooded `team.rego` decisions, and `team` has no ancestor-inheritance
   table, so this conjunct never applies there. The reserved-key discipline below covers both, since
   both copy stored `attributes` verbatim.) Nothing is
   migrated; no stored row changes.
3. **`provenance` is a RESERVED, system-owned attribute — never client-settable.** A stored role's
   `attributes` map *is* client-supplied through the role create/update API, and `resourceRole()` copies
   it verbatim onto the wire role. So the stamp must **overwrite**, not merge — exactly the discipline
   the shipped `withRoleLevel()` already applies to `attributes.role_level` ("the explicit value is the
   single source; an attributes-supplied value is overwritten"). A client-supplied `provenance` is
   **stripped on the write path** and **overwritten on the read path**. Without both, "provenance means
   the system decided this" would hold only by accident of the current call graph: any future path that
   returns a *stored* role without passing through the funnel would let a client's own
   `provenance: "membership"` buy back the inheritance this ADR exists to deny.
4. **The policy opens inheritance only on that stamp.** `inherited_grant` and `list_inheritable_grant` in
   **`category.rego` and `product.rego`** (four clauses) gain the conjunct
   `input.role_definition.attributes.provenance == "membership"`.
5. **Absence is closed.** An unstamped role, an empty `attributes`, or an unknown provenance value simply
   fails the conjunct — inheritance does not apply. A future synthesized role that forgets the stamp is
   **fail-closed** (a visible missing-access bug), never fail-open.
6. **Direct grants are untouched.** A role naming a type explicitly still reaches it via `direct_grant`
   with no stamp at all — which is why the shipped per-type demo roles need no change, and why slice B's
   tiered role (which adds `category`/`product` keys explicitly, ADR 0029's slice-**B** grant row) is unaffected by this
   ADR.

**This amends ADR 0029:** the slice-**A** row of its per-slice grant table — "contents are closed by the role, so slice A ships zero Rego changes"
is corrected to *"contents are closed by the role **plus this confinement rule**; slice A ships one narrow
policy change (T3)."* ADR 0029's scope contract — disjointness, CONTROL-capable reach, the realm claim as
a UX-only marker, the fail-closed classes — is unchanged and remains in force.

## Considered options

| Option | Why rejected |
|---|---|
| **Deny-list**: stamp the *supervisor* as confined; policy skips inheritance for it | Fail-**open** for every future synthesized role — slices B and C each add one, and a forgotten flag reproduces exactly the defect this ADR exists to close |
| **Allow-list on stored data**: every role row carries an inherit flag | A data migration, and it breaks wildcard roles until migrated; the stamp belongs at resolution, where the provenance is actually known |
| **Key on the role code** (`code == "supervisor-readonly"`) | Codes are user-facing, collidable and renameable; keying authorization on one makes a rename a privilege change |
| **Centralize the check in `permissions.effective_actions`** | Breaks the supervisor's own `catalog:view` (direct grants use the same helper) **and** touches `permissions.rego`, which is byte-mirrored into the user-service bundle — a drift surface for no benefit |
| **Narrow the inheritance tables** (`*_inheritable.json`) globally | Removes the mechanism for the wildcard-role constituency that legitimately depends on it; a global change to fix one role |
| **Ship slice A with contents open**, close them in B/C | Inverts the epic's ordering guarantee (each slice only *widens* what the previous closed) and ships a documented fail-open on the slice's own headline boundary |

## Consequences

- **Slice A ships one narrow policy change.** The "zero Rego changes" property is retired; the
  mirrored-bundle drift guard stays out of play because `category.rego`/`product.rego` are **not**
  mirrored (only `permissions.rego` and `permission_categories.json` are).
- **The stamp becomes load-bearing.** If `resourceRole()` ever stops stamping, every member loses child
  access at once — a loud, immediate, e2e-visible failure rather than a silent widening. Because
  `opa test` fixtures are hand-written and would stay green, a **test at the seam** asserting the stamp is
  applied is required, not optional (SUPERVISED-SCOPE T3).
- **Future synthesized roles are closed by default.** Slices B and C need no policy change to stay
  confined; each opens what it intends by naming types explicitly.
- **The existing e2e matrices are the regression proof** for member inheritance: a red cell after this
  lands is a stamp bug, not a flaky rig.

## Related

- [[0029-supervised-read-scope]] — the scope contract this amends (its slice-**A** grant row) and otherwise leaves intact.
- [[0018-team-scoped-resource-isolation]] — the isolation invariant whose "absence ⇒ the safe outcome"
  posture this extends to role provenance.
- [[0030-step-up-decision-contract]] — slices B and C, whose synthesized roles inherit this default.
- [[SUPERVISED-SCOPE]] — T3 implements it.
