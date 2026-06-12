---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/user-service
---

# STATUS — T2: Policies: expansion table + shared `permissions.rego` + the per-type clean cut

**Status:** ✅ DONE (2026-06-12)

## What shipped

- `infra/opa/policies/permission_categories.json` — `data.permission_categories`:
  `READ → [view, list]` · `WRITE → [create, update, delete]` · `TAG → [define-tags, assign-tags]`
  · `GRANT → [assign-roles]` (colocated data-file pattern; the rig's `opa run …/policies` and
  `opa test` load it identically — verified against `compose.opa.yaml`).
- `infra/opa/policies/permissions.rego` (package `permissions`) — the one runtime home:
  `tokens_for`/`denied_for` (wildcard-aware, **total** — malformed/missing input yields `[]`),
  `effective_actions(role_def, type)` (expand minus denials; unknown token → ∅),
  `effective_from_categories(cats)` (the realm fallback's helper).
- `catalog.rego` / `category.rego` / `product.rego` migrated: `direct_grant`, `inherited_grant`,
  the 5.5-B coarse list gate, and the realm fallback (`catalog-viewer → {READ}`,
  `catalog-editor → {READ,WRITE,TAG}`) all decide through `permissions.effective_actions` /
  `effective_from_categories`. `bulk` unchanged (per-item `allow`). `abac_deny` veto unchanged.
- `category.rego` `filter` migrated to the **inline PE idiom** (see Decisions):
  `some token in perms[input.resource.type]; "list" in data.permission_categories[token];
  not filter_list_denied; filter_tags_satisfied`.
- Test suites rewritten at the new vocabulary — every pre-6.5 behavioral cell preserved,
  re-expressed (`view`/`list`/`update`/`delete` for `read`/`write`), plus the new cells:
  `permissions_test.rego` (P1–P4, P12 algebra incl. wildcard-denial symmetry), per-type P3
  (stale flat token decides nothing), P6 (WRITE-minus-delete end-to-end), P7 (realm-fallback
  exact reach: viewer = {view,list}; editor = all seven minus assign-roles), P8 (filter: TAG-only
  denies, wrong-type denies, list-denial closes, stale token denies), P9 (list gate opens on
  inheritable ancestor effective set), P11 (tag composition), + inherited-path ancestor-denial
  cells.

## Tests

`opa check --strict` clean. **`opa test infra/opa/policies/` → PASS: 140/140**.

## Integration / e2e — the P10 fold (acceptance)

Command: `opa eval --partial --format pretty -d infra/opa/policies -i <input> --unknowns
input.resource 'data.category.filter == true'` with a category-token reader
(`{"category": ["READ"]}`, `required_tags {region: [emea]}, ANY_OF`):

```
Query 1:  "category" = input.resource.type
          input.resource.attributes.region = "emea"
Query 2:  "category" = input.resource.type
          "emea" in input.resource.attributes.region
```

Only `input.resource…` conditions — the type-eq tautology (dropped by the parser) + the
scalar-eq / array-membership tag pair. **Identical shape to the 5.x residual.** Untagged
reader → tautology-only (ALLOW_ALL); TAG-only role → `undefined` (DENY_ALL); no role-def →
DENY_ALL.

## Architecture review + refactor

- **The one substantive deviation from the decomposition's sketch** (which wrote `filter` as
  `"list" in effective_actions(…)`): OPA's partial evaluator does NOT inline user functions over
  an unknown argument — the call form leaves `data.permissions.tokens_for(…)` calls and
  comprehensions in the residual (verified empirically before writing), which the parser rejects
  → every list would degrade to the batch fallback. The `filter` rules therefore consume the SAME
  `data.permission_categories` table through an inline membership chain. One expansion home, two
  consumption idioms — the P10 acceptance (pinned) wins over the sketch's letter. Documented in
  both policy headers.
- **Accepted fail-closed degradations at the filter** (documented in category.rego): a role
  carrying a denial → the `not filter_list_denied` survives PE as a negated type-eq → parser →
  `unsupported` → batch recheck via `allow` (denial-aware, correct rows); a raw `"*"`-keyed role
  → same degradation (does not occur on the wire path — the resolve API expands wildcards).
  Neither can widen.
- **Beyond the letter of the decomposition**: `denied_for` is wildcard-aware symmetrically with
  `tokens_for` — without it a `"*"`-scoped denial would not subtract on T5's raw-row `assignable`
  snapshots and the actor's effective set would read WIDER than reality (escalation). Concrete
  key shadows wildcard on both lookups (map-merge semantics). **Carry-to-T3:** the Java
  `expandWildcard` must shadow identically for grants and denials.
- Nothing else substantive; no refactor needed after review.

## Decisions

- Inline PE idiom for `filter` (above) — the slice's one design-level adjustment, fail-closed in
  every degradation path.
- Wildcard-denial symmetry in `denied_for` (above).
- `permission_categories.json` top-level key = the data path (`permission_categories`), matching
  how `opa run`/`opa test` merge JSON files (precedent: `category_inheritable.json`).

## Commit

`feat(opa): category expansion table + shared permissions.rego + per-type clean cut (Phase 6.5 T2)`
