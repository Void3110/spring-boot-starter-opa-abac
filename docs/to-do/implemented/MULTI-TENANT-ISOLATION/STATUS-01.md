---
tags:
  - status/done
  - type/project
  - area/abac
---

# STATUS — T1: catalog.rego filter + fallback removal (3 policies) + narrow create fallback

**Status:** ✅ DONE

## What shipped

- **`catalog.rego`**
  - New role-def-only **`filter`** entrypoint (`default filter := false` + one `filter if` rule:
    `has_role_definition`, the role's category tokens for the unknown type expand inline through
    `data.permission_categories` and must contain `"list"`, `not filter_list_denied`). **No
    subject-roles fallback, no tag conjunct** — catalogs are not tag-filtered here; visibility is the
    app-supplied governed base scope, and `filter` only decides "may this role list catalogs at all."
  - **Removed** the two blanket realm-role fallback `allow` clauses (viewer→READ, editor→READ+WRITE+TAG
    on *any* catalog) — the whole-table leak.
  - **Added** one narrow fallback clause: `allow if { verb == "create"; not has_role_definition;
    "catalog-editor" in input.subject.roles }` — type-level onboarding precedes any team.
- **`category.rego` / `product.rego`** — removed the two realm-fallback `granted` clauses
  (unconditional). No create-style narrow fallback (categories/products are created under an
  already-governed catalog, where the resolved role grants `create`).
- Header comments in all three reconciled to the new model (the stale "subject-roles FALLBACK is
  untouched" / "or the subject-roles fallback" lines corrected — see review below).
- `permission_categories.json`, the tag-match helpers, `bulk`, and `category`/`product` **`filter`**
  rules **untouched**.

## Tests

- `opa test infra/opa/policies/` → **PASS 188/188** (baseline was 183; net +5 after rewrites).
- `opa check --strict` → clean.
- New/updated cells: catalog `filter` R1–R3 (full-eval booleans) + a grant-only and a list-denying
  role; catalog create-only fallback (R5 editor-creates, R6 viewer-cannot-create, no-role-cannot-create);
  catalog R4 (bare editor/viewer denied non-create); category/product R7 (bare realm role denied
  view/update) + resolved-role-unchanged; flipped the two pre-existing `test_tag_fallback_path_*`
  and category's `allow`-vs-`filter` contrast cells to the new (both-deny) reality.

### `opa eval --partial` / Compile-API residual (R1–R3 — verified empirically, NOT assumed)

Ran the **real Compile API** (`POST /v1/compile`, `unknowns=["input.resource"]`, query
`data.catalog.filter == true`) — the exact residual the T4 `CatalogListAuthorizer` will consume:

| Case | Role | `queries` | Meaning |
|---|---|---|---|
| **R1** | grants `list` (READ) | `[[ eq("catalog", input.resource.type) ]]` | tautology over catalog rows → **ALLOW_ALL** (composes as `governedScope ∧ true`) |
| **R2** | **no** role-def | `null` | no satisfiable query → **DENY_ALL** → empty list (the fail-closed boundary) |
| **R3** | **denies** `list` | `[[ eq(type) ∧ ¬eq(type) ]]` | contradiction → unsatisfiable → **DENY_ALL** |

This is the mx-a932a0 / mx-f63604 boundary proven, not guessed: `{}`/`null` = DENY_ALL is the
fail-OPEN trap, and R2 lands on it correctly. **T4 note:** R3's residual is a negated type-eq (the
"unsupported negation survives PE → fail-closed to the batch recheck" case category.rego documents,
mx-cbd39e) — confirm the residual translator's handling in T4's ITs (it fails closed either way:
batch recheck uses `allow`, which also denies a list-denying role).

## Architecture review + refactor

- **Fail-closed:** R2 = `queries:null` (DENY_ALL) proven via the Compile API — the `filter` first
  conjunct is `has_role_definition`, so a missing role-def yields no satisfiable body. The narrow
  create fallback is on `allow` only, never `filter`.
- **Security — three widenings refuted:** (a) `filter`→ALLOW_ALL for a missing role-def — refuted by
  R2; (b) realm fallback surviving on a non-create verb — refuted by R4/R7 (the only `allow` fallback
  clause is `verb == "create"`); (c) ownership n/a in T1.
- **Found + refactored (not churn):** a `subject.roles` grep across the three policies confirmed the
  **only** remaining grant via subject roles is the catalog create clause. The review caught **stale
  comments** that still described the removed fallback ("subject-roles FALLBACK is untouched";
  "(direct, inherited, or the subject-roles fallback)"; the product 5.5-A `final_allow` formula). All
  corrected so the prose matches the code. `effective_from_categories` (in `permissions.rego`) is now
  unused by production rules but kept — it is shared package API, `permissions.json`/`permissions.rego`
  are explicitly out of scope, and `opa check --strict` is clean.
- **Boundary/additivity:** `permission_categories` table, the `filter` PE translator, `bulk`,
  category/product `filter` rules — all byte-for-byte unchanged. The change is additive (`filter` +
  one create clause) minus the leak (the two blanket fallbacks).

## Integration / e2e

N/A for T1 (policy-only, no app code). `opa test` + Compile-API residual are the full acceptance
(R1–R9). **Rig note:** OPA does NOT need a restart yet — no live matrix runs against the edited
policy until T4's ITs (in-process stub, not the rig) / T9 (e2e). `docker restart opa-abac-opa` is
required **before T9's matrix run** (and any T4 step that hits the live rig), per the operator notes.

## Decisions

- **No tag conjunct on the catalog `filter`** (unlike category's): catalogs carry no tag-gating in
  this slice; the residual is a clean ALLOW_ALL/DENY_ALL that AND-composes with the governed base
  scope. Keeps the residual minimal and matches design §2a ("role-def-only, no tag requirement").
- **Category/product get no narrow create fallback** — they are created under an already-governed
  catalog, so the resolved role already grants `create`; only catalog `create` is pre-membership.

## Commit

(see branch `feature/void3110/multi-tenant-isolation`)
