---
tags:
  - status/done
  - type/project
  - area/abac
---

# STATUS — Ticket 05: Rego tag match (some in / every)

> Filled in at the ticket-05 checkpoint during the autonomous run. See [[01-DECOMPOSITION]] ticket 5.

**Status:** ✅ done

## What shipped

The **grant** match — OPA grants only when the resource's tags satisfy the role's `required_tags`,
evaluated **in Rego**. The layer that turns tags from metadata into authorization.

- **`infra/opa/policies/category.rego`:** `allow` (the role-definition path) now requires **both** the
  permission check **and** a new `tags_satisfied`. New rules:
  - `resource_tag_values(key)` — the resource's value(s) for a key as a **set**: an array tag → its
    elements; a scalar tag → a singleton; an absent key → the empty set (three mutually-exclusive bodies).
  - `key_satisfied(key, acceptable)` — existential intersection: `some v in resource_tag_values(key); v in acceptable`.
  - `tags_satisfied` — **ANY_OF** via `some key, acceptable in required_tags` (existential), **ALL_OF** via
    `every key, acceptable in required_tags { key_satisfied(...) }` (universal), and a **vacuous** body
    (`not has_required_tags`) so a role with no requirement behaves exactly as Phase 4.
  - Fail-closed: a present `required_tags` with an unknown/missing `match_mode` matches none of the
    quantifier rules and isn't vacuous → `tags_satisfied` fails → deny. `default allow := false` unchanged.
  - The fallback (no role definition) path is untouched (no tag requirement applies). `gateway.rego` stays
    coarse — the tag match is an app-layer decision (two-layer model intact).

## Tests

- **`opa test .` → 49/49 green** across category/catalog/product/team (every pre-existing test unchanged).
- **`category_test.rego` (+15)** — T1 ANY_OF hit, T2 ANY_OF miss, T3 ALL_OF hit, T4 ALL_OF partial → deny,
  T5 multi-value (scalar) intersection, T6 vacuous (no required tags → Phase-4 behavior), T7
  permission-ok-tags-fail → deny, T8 tags-ok-permission-fail → deny, T9 malformed `match_mode` → deny, T9b
  `required_tags` with no `match_mode` → deny, T10 default-deny on a missing resource tag, plus an ANY_OF
  second-key-hits case.
- **`opa check`** clean; **`opa fmt`** applied.
- **Manual OPA probe (`opa eval`):** the decisive contrast — the **same** `regional-reader` role
  (requires `region:[emea]`), a Category tagged `region=[emea,amer]` → `allow=true`; a Category tagged
  `region=[apac]` → `allow=false`. Identical permissions; the grant flips on the resource's tags, in Rego.

## Architecture review + refactor

- **Additivity / boundary:** only `category.rego` + `category_test.rego` changed — no code, no other
  policy. `allow` now requires `tags_satisfied`, but that is vacuously true for untagged roles, so every
  pre-existing category test still passes. ✅
- **Fail-closed:** malformed `required_tags` / unknown `match_mode` → deny (T9, T9b); missing resource tag →
  deny (T10); `default allow := false` preserved. ✅
- **Three-layer separation:** T5 is the **grant** match only — it reads `required_tags` (T4) against the
  resource's tags (T3). The any-of/all-of decision lives in the policy, not in Java. ✅
- **Pattern reuse:** `some in` / `every` are the OPA-native quantifiers the design called for (AWS
  `ForAnyValue:`/`ForAllValues:`); `gateway.rego` stays coarse. ✅
- **No refactoring warranted.** Considered folding `resource_tag_values` into the call site, but the
  three-body set helper is the clearest way to normalize scalar-vs-array; the 49/49 pass confirms no
  multi-body function conflict. Nothing substantive invented.

## Integration / e2e

`opa test` + the `opa eval` probe above. The full through-the-gateway pass (a live decision flipping on the
assigned tags, no redeploy) is ticket 6's e2e matrix.

## Decisions recorded

`ml record opa-abac --type pattern` — the **ANY_OF/ALL_OF tag match in Rego**: normalize a scalar-or-array
resource tag to a set (`resource_tag_values`), test a single key with existential intersection
(`key_satisfied`), then quantify the requirement with `some` (ANY_OF) / `every` (ALL_OF); make the
no-requirement case a separate **vacuous** body so untagged roles are unaffected, and let a malformed
requirement fall through to deny (fail-closed) rather than adding an explicit error path. Relates to the
Phase-4.5 design (`mx-94e70d`). `ml sync` touched `.mulch/` only.

## Commit

One focused commit on `feature/void3110/tag-dictionary`: `feat(opa): match required tags in category.rego
via some-in / every, fail-closed (T5)`.
