---
tags:
  - status/done
  - type/project
  - area/abac
  - area/rego
  - area/spring
---

# STATUS — T3: confine ancestor inheritance to membership-derived roles (ADR 0031)

**Status:** ✅ DONE

## What shipped

The slice's **one narrow policy change**, plus the Java stamp that drives it.

- **`infra/opa/policies/category.rego` + `product.rego`** — the conjunct
  `input.role_definition.attributes.provenance == "membership"` added to **all four** inheritance
  clauses (`inherited_grant` and `list_inheritable_grant`, in each file), expressed as a named
  `membership_derived` helper per file so the invariant reads once and both clauses cite it.
- **`EffectiveRoleService.resourceRole`** — the single membership funnel for roles reaching the
  **catalog-side** policies — now stamps `attributes.provenance = "membership"` by **overwrite,
  never merge**.
- **`RoleDefinitionService`** — `withRoleLevel` became `withSystemOwnedAttributes`, which now
  **strips** a client-supplied `provenance` as well as overwriting `role_level`. Both halves of
  ADR 0031 §3 are present: strip on write, overwrite on read.
- **`SupervisorRoles.PROVENANCE_MEMBERSHIP`** completes the value vocabulary (T2 shipped the key and
  `"supervised"`).
- **`managementRole` is deliberately NOT stamped** — verified, not assumed: it serves the
  user-service's own `team.rego`, and `grep` confirms `team.rego` declares **no** inheritable table,
  so the conjunct can never apply there.

## Tests

`opa test infra/opa/policies/` → **274/274** (was 266). `./gradlew :example-user-management-service:test`
→ **246 tests, 0 failures**. `./gradlew build` green. `opa check` clean; the four touched policy files
are `opa fmt`-clean.

**The measured five.** Applying the conjunct to the unmodified corpus took `opa test` from
**266/266 → 261/266** — **exactly the five** cases the ticket predicted, no sixth:

```
category_test: test_inherited_grant_from_catalog
category_test: test_inherited_grant_respects_leaf_tags_match
category_test: test_list_gate_passes_for_inheritable_ancestor_grant
product_test:  test_inherited_grant_from_catalog_ancestor
product_test:  test_inherited_grant_with_satisfied_leaf_tags
```

**Eight new cases** (U35–U40 plus two product-side mirrors): U35 (supervisor role + a category
**carrying its catalog ancestor** → `allow` false), U36 (the same for `product`), U37 (the coarse
**type-level** gate is confined too), U38 (**the regression case** — a *wildcard-derived* membership
role, byte-identical in shape to the supervisor role, stamped `membership`, **keeps** inheritance),
U39 (a role naming `category` explicitly, **no stamp at all**, still reaches it via `direct_grant`),
U40 (**absence is closed** — no `attributes`, an empty map, and an *unknown* provenance value each
grant nothing).

**`ProvenanceStampIT`** carries **I7 — the seam test** (`resourceRole` stamps on a real membership;
the stamp rides the resolve **wire**; a *stored* `provenance` is **overwritten** on the read path)
and **U41** (a client-supplied `provenance` is **stripped on write**, on both create and update,
while non-reserved attributes survive).

### The fail-open reproduced before the fix, and the fix proved load-bearing

ADR 0031's table re-measured against the shipped corpus **before** the change — including the last
row, the green-lighting trap that let this survive planning:

| probe (role = `catalog: ["READ"]`, `provenance: supervised`) | `allow` before T3 |
|---|---|
| `category:view`, ancestors `[catalog]` (the runtime shape) | **true** ← the leak |
| `product:view`, ancestors `[catalog]` | **true** |
| type-level `category:list` | **true** |
| `category:view` with **no** ancestors | false ← the trap |

And the new denial cases are **not vacuous** — with the conjunct temporarily neutralised
(`membership_derived := true`), all five negative cases **FAIL** (274 → 269/274), then pass again
when restored. They fail for the reason they claim.

## Architecture review + refactor

Ran the ★ gate inline after `opa test` green, before the seam IT. **Two substantive findings, both
fixed.**

1. **Migrated fixtures would have gone vacuous (real, fixed).** Stamping only the five *failing*
   fixtures leaves the *negative* inheritance tests — `test_list_gate_respects_ancestor_denial`,
   `test_inherited_grant_respects_ancestor_denial` (both files), `test_inheritable_but_no_ancestor_grant_denies` —
   passing **for the wrong reason**: they would deny on the missing stamp instead of on the ancestor
   denial they exist to test. Fixed by stamping **every** pre-existing fixture role, which is the
   faithful migration: *before* T3 every role behaved as membership-derived, so the stamp restores
   each test's original meaning exactly. Verified mechanically — a universal-stamp probe held
   **266/266**, i.e. no assertion flipped.
   The one deliberate exception is `test_direct_grant_unaffected_by_inheritance`, left **unstamped**
   on purpose so the existing suite itself proves ADR 0031 §6 (a direct grant needs no stamp).
2. **`opa fmt` drift introduced (real, fixed).** A trailing comma in one added call left
   `product_test.rego` unformatted where it was clean at HEAD. Reformatted; all four touched files
   are now `opa fmt`-clean. (`agent_tools_test.rego` also reports drift — **pre-existing at HEAD**,
   not mine, and outside this ticket's boundary, so deliberately untouched.)

**The construction-site sweep — the check this ticket lives or dies on.** ADR 0031 makes the stamp
load-bearing: any role that reaches `category.rego`/`product.rego` through *inheritance* without it
loses access. All five `new RoleDefinition(...)` sites in the repo were enumerated and classified:

| site | reaches | verdict |
|---|---|---|
| `EffectiveRoleService.resourceRole` | catalog/category/product | **stamped `membership`** — the funnel |
| `EffectiveRoleService.managementRole` | `team.rego` only | correctly **unstamped** — `team.rego` declares no inheritable table (verified by grep) |
| `SupervisorRoles.readOnlyFor` | catalog | **stamped `supervised`** — confined, by design |
| `DemoRoleDefinitionSupplier` (catalog svc) | catalog/category/product | **no change needed** — it names all three types **explicitly**, so it reaches them by `direct_grant` |
| `TypeLevelRoleDefinitionSupplier` (mcp) | `agent_tools.rego` | **no change needed** — names the requested type explicitly, and `agent_tools.rego` has no inheritance at all (verified) |

So the change reaches exactly the intended constituency and nothing else.

The rest of the checklist:

- **Fail-closed.** *Absence is closed* is the whole posture and it is asserted three ways (U40): no
  `attributes`, an empty map, an unknown value. A future synthesized role that forgets the stamp
  fails **closed** — a loud missing-access bug — never open. The conjunct only ever **removes** a
  grant path, so no input can be allowed after T3 that was denied before.
- **Security — the widening that would matter here** is the inverse of the usual one: this ticket
  *narrows*, so the risk is over-narrowing (breaking members). U38 is the explicit regression case
  for the constituency that legitimately inherits — a **wildcard-derived** membership role, which
  ADR 0031 §Context notes is byte-identical in policy input to the supervisor role, making the
  marker mandatory rather than merely convenient.
- **Forgery.** `provenance` is reserved and system-owned on **both** paths. With only the read-path
  overwrite, the guarantee would hold merely by accident of the current call graph — any future path
  returning a *stored* role would let a client's own `provenance: "membership"` buy back the
  inheritance. U41 pins the write path; `storedProvenanceIsOverwrittenOnTheReadPath` pins the read
  path by writing a forged row straight to the repository, bypassing the service.
- **Boundary / additivity.** The policy diff is **exactly** `category.rego`, `product.rego` and their
  two test files — `git diff --stat main -- '*.rego' '*.json'` shows those four and nothing else.
  `permissions.rego` and `permission_categories.json` (the **byte-mirrored** pair) are untouched, so
  the drift guard stays out of play; `category_inheritable.json`/`product_inheritable.json` and
  `catalog.rego` are untouched; no library module; no stored row migrated.
- **Not centralized into `permissions.effective_actions`** — as ADR 0031 requires: direct grants use
  the same helper (the supervisor would lose its own `catalog:view`), and that file is mirrored.
- **Pattern reuse / sibling parity.** `category.rego` and `product.rego` received the identical
  helper and clause shape — the per-type sibling-drift discipline this corpus already follows.
- **No JVM test evaluates these policies** (checked): the only test reading `infra/opa/policies` is
  `CategoryListWideningParityTest`, and it reads `permission_categories.json`, which is unchanged.
  So the rig-level regression for T3 is genuinely **T6's E7**, exactly as the decomposition states —
  and a red cell there is a stamp bug, not a flaky rig.
- **Static-analysis gate: `CLEAN — 0 open findings`** on the changed files.

## Integration / e2e

**No rig, by design** — part 0 stays provable with ITs plus `opa test` alone. `ProvenanceStampIT`
runs against real Postgres via Testcontainers (I7 + U41). The rig-level non-regression proof for this
change is **T6's E7** (part 1), where the existing matrices that read categories/products as a member
are the regression evidence.

## Decisions

- **⚠️ The T3 docs delta is DEFERRED TO T6 (part 1) — a deliberate part-boundary decision, and the
  one T3 deliverable not shipped here.** `01-DECOMPOSITION` lists "a subsection in
  `docs/guides/TEAM-BASED-AUTHORIZATION.md` stating the invariant" under T3, but that file is
  **explicitly part 1's territory** in this part-runner's brief ("T6 writes/reconciles the guide
  section"), because T6 owns D1 and would otherwise rewrite the same section. Editing it here would
  put two parts in one file for no benefit. **T6 must therefore cover the ADR-0031 invariant** —
  *ancestor inheritance requires membership provenance; a synthesized role is confined to the types
  it names* — alongside D1's two access paths, precedence rule, reach rule and both failure classes.
  Nothing else from T3 is outstanding.
- **The conjunct is a named `membership_derived` helper, not an inline expression.** Four clauses
  across two files would otherwise repeat the same literal, and the invariant deserves one place per
  file to carry the rationale comment. Behaviourally identical (a rule body with a single
  comparison); it keeps the two sibling policies textually parallel.
- **All pre-existing fixture roles were stamped, not just the five that failed.** See the review
  above — minimal stamping would have left the negative denial tests vacuous. This is a larger
  fixture diff than the ticket's "five" implies, and it is the honest migration: the ticket's five is
  the count of tests that *fail*, not the count of fixtures whose *meaning* the conjunct changes.
- **`withRoleLevel` was renamed `withSystemOwnedAttributes`.** It now enforces two reserved,
  system-owned keys rather than one; leaving the old name would have hidden the strip.
- **No seam deviation to report.** Every artifact was verified before being built on: the four clause
  sites, `data.category.inheritable`/`data.product.inheritable`'s declared tables, `team.rego`'s
  absence of inheritance, `agent_tools.rego`'s absence of inheritance, and the exact wire path by
  which `attributes` reaches `input.role_definition`. The ticket's "if a sixth breaks, stop"
  tripwire was **not** hit — exactly five broke.

## Part review (layer 2)

**Scope:** the whole of part 0 — **T1–T3** as one diff (commits `6a156c7`, `5801d90`, and this
ticket's), i.e. the org-relation seam, the synthesized supervisor role, and the confinement rule.

**Downgrade recorded (required — T3 is a headline ticket).** This review is the **2A lens set applied
INLINE, in the part-runner's own context**, not the multi-lens 2B path. That is this environment's
routing, not a shortcut: a part-runner is already a subagent, so nesting a review sub-agent is the
rejected fork and the multi-lens path is unreachable here. **T3 is one of the slice's two named
fail-open edges** (the other, `supervised := S \ M`, lives in T5/part 1), so it carries the
downgrade. The automatic **whole-delivery layer-3 review re-covers both at branch scope** after part
1 lands — which is where cross-part composition is visible anyway.

**Findings — three, all fixed within this part; none reached an earlier part.**

1. *(T3, test integrity — the most consequential)* **The migrated `opa test` fixtures would have gone
   vacuous.** Stamping only the five measured failures leaves the *negative* inheritance tests
   denying on the missing stamp rather than on the ancestor denial they exist to prove. **Fixed** by
   stamping every pre-existing fixture role, verified by a universal-stamp probe that held 266/266
   (no assertion flipped), with `test_direct_grant_unaffected_by_inheritance` deliberately left
   unstamped so the suite itself proves ADR 0031 §6. **Re-tested: 274/274.**
2. *(T1, correctness)* **Duplicated dedup logic that threw NPE on a null element.** The bootstrap
   controller recomputed the written count with `Set.copyOf(...)`, duplicating the service's rule and
   500-ing on `[null]`. **Fixed** — `replaceReportsOf` returns the count; regression test added.
   **Re-tested.**
3. *(T3, hygiene)* **`opa fmt` drift** introduced in `product_test.rego`, which was clean at HEAD.
   **Fixed.**

**Verification the review added, beyond the ticket gates.** Two checks that a lens pass exists to
force, both of which could have hidden a silent defect:

- **The anti-vacuity proof.** With the conjunct neutralised, all five new denial cases **FAIL**
  (274 → 269/274). Without this, U35–U37/U40 could have been passing because of an input-shape
  mistake — which is *precisely* how the original U14 green-lit a live fail-open (an ancestor-less
  probe returning false for the wrong reason, ADR 0031 §Context). The new cases carry the ancestor
  chain deliberately.
- **The five-site construction sweep** (table above). ADR 0031 makes the stamp load-bearing, so an
  unstamped role reaching the leaf policies through inheritance would break member access. The two
  sites *outside* the user-service — `DemoRoleDefinitionSupplier` and the MCP
  `TypeLevelRoleDefinitionSupplier` — were each confirmed safe for a *stated* reason (both name their
  types explicitly → `direct_grant`; the MCP one feeds an inheritance-free policy), not assumed.

**Cross-part:** nothing found in this part reaches an already-completed part — part 0 is the first
part, and no earlier part exists. The one forward-facing item is the guide-docs deferral to T6,
recorded in *Decisions* above; it is a hand-off, **not** a defect and **not** an escalation.

**Part gates at close:** `./gradlew build` green (all modules, Testcontainers ITs against real
Postgres) · `opa test infra/opa/policies/` **274/274** · local Sonar **CLEAN — 0 open findings** on
the changed files · working tree clean, no undeclared artifacts.

## Commit

`feat(supervised-scope): T3 confine ancestor inheritance to membership-derived roles (ADR 0031)`
— on `feature/void3110/supervised-scope`.
