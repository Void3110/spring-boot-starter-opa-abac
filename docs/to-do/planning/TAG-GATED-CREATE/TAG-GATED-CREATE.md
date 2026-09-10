---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/opa
---

# TAG-GATED-CREATE — a tag-requiring role must not create what it cannot read

> **Status: 📋 researched, not yet designed.** Opened 2026-09-11 from **DEF-1** of
> [[PRE-HABR-UI-QA-2026-09-10]]. Severity **Medium**: no escalation beyond the role's own WRITE, but
> it contradicts the published contract — "mutations + everything below the root stay tag-gated"
> ([[0022-root-read-tag-exemption|ADR 0022]]) and "a role may act on resources whose tags match"
> ([[0009-tag-requirement-subject-side|ADR 0009]]). Small slice: one input-contract change, two
> policies, tests on three tiers, a doc line. **Build: collaborative** (the [[SPA-CHALLENGE-UX]] shape).

## The reproduction (measured 2026-09-10, `viewer` holding `alice-role`)

`alice-role`: permissions `{catalog, category, product: [READ, WRITE, TAG]}`, `required_tags`
`{region:[apac], sensitivity:[public]}`, `match_mode ALL_OF`. On the untagged Demo catalog:

| Call | Answer | Expected under the contract |
|---|---|---|
| `GET …/categories` (list) | `count:0` — nothing matches | ✓ |
| `GET …/categories/{EMEA}` (`region:emea`) | 403 | ✓ |
| `PUT …/catalogs/{root}` | 403 (root mutations tag-gated) | ✓ |
| `POST …/categories` `{name}` (no tags) | **201** | 403 |
| `POST …/categories/{EMEA}/products` | **201** — inside a category the role is denied to read | 403 |
| `GET` the category it just created (untagged) | 403 — a write-only spawn | — |
| `POST …/categories` `{tags:{region:[apac], sensitivity:public}}` | 201 | 201 |

## The mechanism

`category.rego` and `product.rego` decide **type-level** requests (no resource id — a list, or a
create/assign-tags before the instance exists) through

```
allow if { is_type_level_request; not denied; list_inheritable_grant }
```

`list_inheritable_grant` only checks that the verb is in the role's effective actions on an
**inheritable ancestor type** (`data.category.inheritable = {catalog, category}`; the role is
resolved on the parent catalog via `@OpaPreAuthorize(roleResourceType='catalog')`). Its own comment
says it "only OPENS the gate, never widens the rows" — true for **LIST**, whose rows the SQL residual
then cuts with the same tag match; false for **CREATE**, where nothing cuts afterwards and
`tags_satisfied` is never consulted. The create input carries no parent tags to match against
either: `resourceType='category'`, attributes = the tag-on-create payload at most.

## The shape on the table

Two conjuncts on a type-level **create** (and the type-level **assign-tags**), LIST unchanged:

1. **The parent must satisfy the role's requirement.** For a category create, the resolved
   `roleResource` (the catalog); for a product create, the category. The parent's tag map has to
   reach the input — the `root_attributes` pattern of ADR 0032 (manager-side, memoized) is the
   precedent: a `resource.parent_attributes` (or reuse of `ancestors`) populated by the
   authorization manager, absent ⇒ deny for a tag-requiring role.
2. **The tag-on-create payload must satisfy it too** — a creator must be able to read what it
   creates. An untagged payload under a tag-requiring role ⇒ deny; a role without a requirement ⇒
   unchanged (vacuous truth stays).

Fail-closed edges to pin: absent parent attributes ≠ empty parent tags (three states, as ADR 0032);
the root-read exemption never applies to create; a malformed `required_tags` keeps denying
(the #122 presence guards).

## Forks for the grill-me

1. **Where the parent's tags enter the input** — a new additive component vs the existing
   `ancestors`/`root_attributes` (which today serve inheritance and the production tier, not the tag
   match); who pays the fetch (manager-side memo vs the app's resolver).
2. **Is the parent conjunct ADR 0022-shaped?** Root reads are exempt; should a category create under
   an untagged root by a tag-requiring role be denied (consistent with "root mutations are
   tag-gated") or allowed when the *payload* matches? Recommendation: deny — the root's tags are
   the placement decision the role was scoped by.
3. **Type-level `assign-tags`** — the same clause; does any caller use it before an instance exists?
4. **ADR** — amend ADR 0009 (a "creation" section) vs a short new ADR; the contract sentence in
   ADR 0022 and [[TAG-BASED-AUTHORIZATION]] §"Layer 3" needs the create case either way.

## Ratchet

- `opa test` per policy: tag-gated writer + mismatching parent → deny; matching parent + matching
  payload → allow; untagged payload → deny; no-requirement role → unchanged; absent parent
  attributes → deny.
- A newman cell pair in the tag matrix (`run-tag-matrix.sh`): the regional-reader-with-WRITE persona
  creates under `mismatch` → 403, under `match` with a matching payload → 201.
- A UI QA row: the console's create form under a tag-requiring role answers 403 honestly.

## Next steps

1. `ml prime rego-policy opa-abac-authz-model --budget 8000`, then `/grill-me` on the four forks.
2. `00-DESIGN.md` + the ADR decision. 3. `/decompose` (collaborative). 4. Build on
   `feature/void3110/tag-gated-create`; layer 3 = one multi-lens `/deep-review` pass.

## Related

- [[PRE-HABR-UI-QA-2026-09-10]] — DEF-1, the evidence
- Mulch `rego-policy` failure record (2026-09-10): the class and the diagnostic tell
- [[TAG-BASED-AUTHORIZATION]] · [[0009-tag-requirement-subject-side|ADR 0009]] · [[0022-root-read-tag-exemption|ADR 0022]] · [[0032-root-attribute-enrichment-input-contract|ADR 0032]]
