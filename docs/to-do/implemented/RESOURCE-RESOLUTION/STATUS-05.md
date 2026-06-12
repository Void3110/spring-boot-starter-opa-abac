---
tags:
  - status/done
  - type/project
  - area/abac
  - area/spring
  - area/opa
---

# STATUS — T5: policies: tags_satisfied conjunct for product.rego/catalog.rego (retro-audit fold-in #3)

**Status:** ✅ DONE

## What shipped

In `infra/opa/policies/`, `category.rego`'s tag block ported **as-is** (it references only
`input.*`, so the port is byte-equivalent bar the package context):

- `product.rego` — `direct_grant` gains the `tags_satisfied` conjunct; the inherited path becomes
  `granted if { inherited_grant; tags_satisfied }` (the leaf's tag requirement applies on the
  inherited path, exactly as in `category.rego`); the full block appended
  (`resource_tag_values` ×3, `key_satisfied`, `tags_satisfied` ANY_OF/ALL_OF/vacuous,
  `has_required_tags`); header comment documents the Phase-5.97 port + fold-in #3.
- `catalog.rego` — the flat file has no inheritance/deny indirection, so the conjunct lands on the
  one role-definition grant path (the primary `allow`); the same block appended.
- **Untouched:** both files' realm-fallback clauses (the conjunct only narrows the role-definition
  path — P5), `inherited_grant` logic, deny-overrides; the `filter`/`bulk` entrypoints correctly
  remain `category.rego`-only (the only partial-eval list path); `team.rego` / user-mgmt policies;
  `category.rego` itself (the template, not a target).

## Tests

`opa test infra/opa/policies/` → **PASS 97/97** — every pre-existing case green **unmodified**:

- `product_test.rego`: P1 ANY_OF write hit (`emea`)/miss (`apac`); P2 ALL_OF both-keys/one-missing;
  P3 tag-requiring role + **attribute-less resource → deny**; P4 tag-free role unaffected (vacuous);
  P5 no role definition → fallback decides as before; **+ the inherited path**: a tag-gated
  inheritable catalog grant allows the `emea` product and denies the `apac` one.
- `catalog_test.rego`: the same five cells against `catalog:write`.
- The P4 caveat from the ticket (an existing test seeding a tag-requiring role against an
  attribute-less input would have been asserting the hole): **no such test existed** — grep found
  zero `required_tags` references in the pre-existing product/catalog tests, so nothing flipped.

## Architecture review + refactor

Review path: conjunct-placement audit (only the role-definition grant paths), structure diff vs the
`category.rego` template, fallback/deny/filter untouched-surface check.

- The conjunct narrows and never widens: the only changed rules are `direct_grant` /
  `granted(inherited)` (product) and the primary `allow` (catalog) — each gained one AND term.
- The block mirrors the template exactly (same rule names, same clause order, same comments adapted
  minimally) — no novel policy design.
- **Nothing refactored**; 97/97 first run.

## Integration / e2e

`opa test` is this ticket's gate (green). The conjunct goes live end-to-end in T6 (cell E6 — the
product sibling 403) after the rig's OPA restart.

## Decisions

- `catalog.rego` keeps its flat shape (no `granted`/`denied` indirection introduced) — the mirror is
  of the *tag block and conjunct placement*, not a restructuring of the file.

## Commit

`feat(opa): tags_satisfied conjunct for product + catalog policies (T5)` — see `git log` on
`feature/void3110/resource-resolution`.
