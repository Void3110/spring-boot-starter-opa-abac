---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/spring
---

# STATUS — T4: the widened supervisor role + the tier-deny clauses + the policy proof

**Status:** ✅ DONE

## What shipped

- **`SupervisorRoles.readOnlyFor` widens for `catalog`** — the governing type teams target — to
  `{catalog: [READ], category: [READ], product: [READ]}`, so a supervisor reaches contents through the
  ordinary **direct-grant** path. Any other supervised type keeps the single-key shape. Same code, same
  provenance attribute, still never stored, still READ-only. The class javadoc's "what it does NOT name"
  section is rewritten into "contents open by DIRECT grant, never by inheritance", stating that how far
  the widened read reaches is decided in **policy**, not by withholding the grant.
- **Four `denied` clauses — the slice's only policy change** — two in `category.rego` and two in
  `product.rego`, each carrying the `provenance == "supervised"` conjunct:

  ```rego
  denied if { …provenance == "supervised"; not input.resource.root_attributes }          # unproven
  denied if { …provenance == "supervised"; input.resource.root_attributes.env == "production" }
  ```

  With a comment block that records **why two clauses and not one negation**, why `{}` fires neither,
  and why nothing tier-related belongs in `filter`. `catalog.rego`, `team.rego`, `agent_tools.rego`, the
  mirrored bundle (`permissions.rego` + `permission_categories.json`) and every `filter` rule are
  untouched.
- **The tier test blocks** in `category_test.rego` + `product_test.rego`: the four states on both the
  instance shape and the type-level gate shape, the member controls, a read-only-ceiling case, and a
  positive assertion that `filter` still passes on the very input the gate denies.
- **Guide delta** — the *Production tier* subsection in `docs/guides/TEAM-BASED-AUTHORIZATION.md`: the
  widened role, the deny shape with the naive-negation warning, the four-state table, the
  member-unaffected scoping, where the decision lands (coarse gate, never the residual), and the pinned
  `_actions` omission. *(The e2e/E6-flip paragraph is T6's and is deliberately absent.)*

## Tests

`opa test infra/opa/policies/` — **301/301** (from 276; +25). `./gradlew build` (all modules) —
**BUILD SUCCESSFUL**.

| Case | Where | Count | Asserts |
|---|---|---|---|
| **U5** | `SupervisorRolesTest` (2, rewritten) | | `readOnlyFor("catalog")` → the three-key READ shape, no `"*"`, coarse tokens only, READ-only, provenance intact; `readOnlyFor("product")`/`("team")` → the unchanged single-key shape |
| **U6** | `category_test.rego` | 4 | the instance shape (category + catalog ancestor): absent → **deny**, `{}` → allow, `staging` → allow, `production` → **deny** |
| **U7** | `category_test.rego` | 4 | the same four through the coarse `category:list` gate |
| **U8** | `product_test.rego` | 4 | the U6 quartet on the product instance shape (full two-ancestor chain) |
| **U9** | `product_test.rego` | 4 | the U7 quartet through the `product:list` gate |
| **U10** | both files | 6 | a **membership** role reads production contents *and* survives an absent-root outage, on instance **and** gate, in **both** files; an **unstamped** role likewise |
| — | both files | 2 | the supervised READ-only ceiling holds under the tier (`:update` denied on an open staging root) |
| — | `product_test.rego` | 1 | `filter` is **true** on the very gate input the tier denies — the decision visibly lands at the gate, never in the residual |

**U11 — the mutation results, measured (four clause sites, four deletions):**

| Deleted clause | Result | Tests that caught it |
|---|---|---|
| `category.rego` absent-clause | 299/301, **2 fail** | `test_tier_instance_absent_root_denies`, `test_tier_gate_absent_root_denies` |
| `category.rego` production-clause | 299/301, **2 fail** | `test_tier_instance_production_root_denies`, `test_tier_gate_production_root_denies` |
| `product.rego` absent-clause | 299/301, **2 fail** | `test_tier_instance_absent_root_denies`, `test_tier_gate_absent_root_denies` |
| `product.rego` production-clause | 299/301, **2 fail** | `test_tier_instance_production_root_denies`, `test_tier_gate_production_root_denies` |

Every site is guarded; the corpus restored to 301/301 after each. **Two further mutations were run
because they are the shapes the design warns about**, and both are worth recording:

| Mutation | Result |
|---|---|
| Collapse both clauses into the **forbidden naive negation** `not …root_attributes.env == "production"` | **295/301, 6 fail** — the untagged-allows and staging-allows cells on both shapes catch it, i.e. the naive form is caught by the *open* cases, not the closed ones |
| **Drop the provenance conjunct** from both clauses | **277/301, 24 fail** — including 21 **pre-existing** tests (every membership/direct-grant case), which is the measure of how load-bearing the scoping is |

**Rego semantics verified by `opa eval` against the real corpus before any test was written** (the
middle state is the whole contract): supervised + absent → `false`; supervised + `{}` → `true`;
supervised + `staging` → `true`; supervised + `production` → `false`; membership + `production` →
`true`; membership + absent → `true`.

## Architecture review + refactor

Inline ★ review over the ticket's diff. This is a **headline ticket** — the mechanism the slice exists
for, and the one place a careless shape fails **open**.

- **Fail-closed** — the tier gate's floor is deny, and the two closed states are closed for *different*
  reasons that must not merge: unproven (the field is absent) and proven-production. The `{}` state must
  stay open, which is why the middle state was measured with `opa eval` before tests existed rather than
  reasoned about. The naive-negation mutation above is the empirical proof that the forbidden shape
  fails **open** on exactly the states the design named.
- **Security — the widenings that would matter.**
  1. *Contents opened via inheritance rather than direct grant* (invariant 1) — the role names the child
     types explicitly; `inherited_grant`/`list_inheritable_grant` and their `membership_derived`
     conjunct are **byte-unchanged**, and slice A's ADR 0031 tests still pass untouched, still asserting
     the narrow role cannot inherit.
  2. *The deny leaking onto membership decisions* (invariant 2) — both clauses carry the provenance
     conjunct; the U10 controls assert members on **both** shapes in **both** files, and the
     drop-the-conjunct mutation shows 24 tests holding that line.
  3. *A `root_attributes` predicate reaching the SQL residual* (invariant 4) — `filter` does not consult
     `denied` in either file (unchanged), and this is now asserted **positively**: `filter` is true on
     the very gate input the tier denies.
  4. *The widening quietly widening writes* — the role stays READ-only; asserted in the unit test and
     again in policy (`:update` denied on an open root).
- **Concurrency / idempotency** — not applicable to a pure policy decision; the coherence question (one
  root snapshot per request) belongs to T3's memo and is asserted there.
- **Wiring** — the new clauses' consumer is `allow` (both clauses) and, through it, `bulk`; the role
  widening's consumer is the resolve API (`SupervisorEffectiveRoleIT` asserts the new shape over the
  wire). Non-happy paths *are* the point of this ticket.
- **Boundary** — exactly two policy files changed, exactly four clauses added, no verb added, the
  mirrored bundle untouched. `git diff --stat` over `infra/opa/policies/` shows only
  `category.rego`/`product.rego` and their test files.
- **Pattern reuse** — the existing `denied` deny-overrides shape extended rather than a new mechanism;
  the test files' existing fixture idiom (a `*_input(role_def, …)` builder + `with input as`) reused,
  with `object.union` so the **absent** case is a genuinely missing key rather than a `null`.
- **Refactor applied (one, from the static-analysis gate).** The rewritten `SupervisorRolesTest` first
  asserted the ceiling with a second `allSatisfy` lambda; Sonar's **S5841** correctly flagged it as an
  assertion that would pass vacuously on an empty subject. It was replaced by `hasSize(3)` plus a single
  `containsExactly("READ")` — which pins **both** halves at once (never a fine verb, never
  WRITE/TAG/GRANT/CONTROL), making the second lambda redundant rather than merely guarded.
- **Static-analysis gate** — `./.sonar-local/sonar-local.sh`: after that fix, **16 findings, all
  documented by-design FP classes** (`quality-gate-sonar` mx-302e78): S5778×8, S1186×5, S1168×2, S107×1
  — all carried from T1–T3 and unchanged by this ticket. No new finding on the changed files.

## Integration / e2e

None in this ticket by design: T4 is `opa test`-provable, which is exactly why it sits in part 0. The
rig proof (E1–E8, including the tier-flip liveness cell and the E6 flip) is **T6's, in part 1**.

**The safe intermediate state now holds, and it is worth stating precisely.** The role is widened and the
denies are live, but no input carries `root_attributes` in anger yet — the manager ships the population
code (T3) and the catalog service will exercise it under test in T5. Every supervised child read
therefore hits the **absent ⇒ deny** clause, so contents stay exactly as closed as they were in slice A.
Part 0 ends deployable, with nothing user-visible changed.

## Decisions

- **Seam deviation — the "NOT to touch" list under-scoped the role widening by one file.** The
  decomposition scoped T4's Java to "`SupervisorRoles` (+ its test)". In fact **two** tests assert the
  synthesized role's shape: `SupervisorRolesTest` (the unit case) **and**
  `SupervisorEffectiveRoleIT.supervisorOnNoTeamResolvesTheSynthesizedRole`, which asserts it over the
  `/internal/effective-role` wire. The second is a hard build-breaker for this ticket — it cannot be
  green and the old shape cannot both hold — so it was updated here, in the same commit, with the same
  rewrite and a comment naming U5 as the reason. Recorded rather than absorbed: a future ticket widening
  a synthesized role should grep the **factory**, not the module the plan named.
- **U14's rewrite is the unit-level analogue of the E6 flip, and was planned.** Slice A asserted the
  role names *no* child-type key; B asserts it names two. Slice A's own policy comment anticipated this
  ("a future slice that widens the synthesized role by naming child types needs no policy change"), and
  that prediction held exactly — the widening needed no change to any inheritance clause. Both rewritten
  assertions keep every part of A's contract that B does not change (no `"*"`, coarse tokens, READ-only,
  provenance, envelope).
- **Two stale statements in A's guide section were corrected, minimally.** "Contents stay closed" and
  "What is NOT in this slice" are falsified *by this ticket's own mechanism*, so leaving them would have
  shipped a self-contradicting guide. Each got a pointer to the new subsection rather than a rewrite;
  the substance of A's subsection (inheritance stays closed, and that is *why* widening the role was the
  safe move) is untouched. T6's e2e/E6-flip paragraph is not written here.
- **`object.union` for the fixture states** so the absent case is a **missing key**, not a `null` — an
  explicitly-null fixture would have tested a state the manager never produces and would have made the
  absent-clause guard weaker than it looks.
- **The extra two mutations were run and recorded** (naive-negation, provenance-drop) beyond the four
  the ticket required. The acceptance asks for one guard per clause site; these two answer the different
  question of whether the *shape* and the *scoping* are guarded, which is where this slice's fail-open
  actually lives.

## Commit

`feat(production-tier): widen the supervisor role and add the tier denies (T4)` — the role widening, the
four `denied` clauses, 25 new policy tests, the two rewritten shape assertions, and the guide
subsection, on `feature/void3110/production-tier`.

## Part review (layer 2)

_Filled at the part boundary, after T4's commit._
