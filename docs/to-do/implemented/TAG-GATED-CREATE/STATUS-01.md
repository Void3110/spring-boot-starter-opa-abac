---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/spring
  - area/catalog-service
---

# STATUS — T1: the placement gate in category.rego + product.rego (U1–U12)

**Status:** ✅ done — 2026-09-11 (collaborative build)

## What shipped

- `infra/opa/policies/category.rego` + `product.rego` (mirrored): the instance clause gains
  `not strict_type_level`; the coarse ancestor clause is scoped to `verb == "list"`; the **placement
  gate** `strict_type_level; not denied; type_level_verb_grant; parent_tags_satisfied; tags_satisfied`
  owns every other type-level verb, whichever way the verb is granted (`type_level_verb_grant` = the
  verb on the decided type directly OR `list_inheritable_grant`). The tag match is parameterized —
  `attribute_values(attrs, key)`, `key_satisfied(attrs, key, acceptable)`, `tags_match(attrs)` — with
  `tags_satisfied` over the resource's own map and `parent_tags_satisfied` over
  `input.resource.parent_attributes` read **without a default** (absent ⇒ undefined ⇒ deny; `{}` ⇒
  deny; non-object ⇒ deny; a no-requirement role vacuous). `resource_tag_values(key)` kept as a
  one-line wrapper (two shipped pins call it). Comments rewritten to say what is now true.
- `docs/guides/TAG-BASED-AUTHORIZATION.md` §"The match in Rego": the parameterized snippet and a new
  "Placement" subsection (LIST coarse / the rest strict, the root included, re-parent as a placement,
  the three states, no fallback).

## Tests

- `category_test.rego` + `product_test.rego`: fixtures `gated_writer_any` (stamped
  `provenance: membership`, catalog READ+WRITE+TAG), `gated_writer_no_tag`, `gated_writer_direct`
  (names the type), `gated_direct_no_tag`; helpers `placement_input` / `_no_parent` / `_no_attrs`;
  cells **U1–U12** as `test_placement_u1…u12` in both files (24 cells). `opa test`: **447/447** (was
  423/423, every pre-existing cell unchanged). `opa check` clean; `opa fmt --list` names only the four
  test files that carried drift before this ticket (the appended blocks are fmt-clean — verified by
  diffing `opa fmt` output; the policy files are clean).
- **Mutation check (measured):** removing `parent_tags_satisfied` from both strict clauses on a
  scratch copy fails 14/447 (U3, U6, U7, U8, U10, U11, U12 in both files) — the cells guard the
  conjunct, not just the happy path.

## Architecture review + refactor

Filled at the ticket's checkpoint: the review path used (the collaborative build's per-ticket read-through; the slice-level passes are recorded in STATUS-05), what it found, what was refactored (or "nothing substantive").

## Integration / e2e

None in this ticket (policy only). The rig's OPA needs `docker restart opa-abac-opa` before any live check — it never watches its mount.

## Decisions

- `parent_tags_satisfied` has **two** clauses (the object match, and the vacuous no-requirement one)
  rather than routing through `tags_match` alone — the vacuous case must pass with the parent map
  absent, which the object clause cannot do by design.
- `strict_type_level` is undefined for a malformed action (no `verb`), so such a request falls to the
  instance clause where `direct_grant` needs the same `verb` and fails closed — no new open path.
- U12's future-verb cell grants `frobnicate` for real through `data.permission_categories` (a
  `with … as object.union(…)` override), so it proves the strict path, not "no grant".

## Commit
