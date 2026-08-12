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

**Scope:** the whole of part 0 — T1–T4 as one diff (`main...HEAD`, 42 files, +2762/-84, four feature
commits) — reviewed at the part boundary after T4's commit.

**Path taken, and the downgrade this records.** The **2A lens set applied INLINE, in the part-runner's
own context** — no review sub-agent was spawned. This is the deep-review skill's own routing for a
part-runner (its path table: *running inside a spawned subagent → 2A applied inline, any size, any risk;
2B unreachable*, since `Workflow` does not exist where a subagent runs). **T3 and T4 are headline
tickets** — the input contract every adopter sees, and the tier mechanism whose careless shape fails
open — and both would normally take the multi-lens 2B path on a diff this size and this risk. **They
took inline-2A instead. Layer 3 (the whole-delivery `/deep-review` from the main session, after part 1)
re-covers both**, and should treat them as first-priority scope rather than as already-reviewed.

### What was checked, with the evidence

- **Fail-closed (the lens that matters most here).** Every error/empty branch traced to deny/absent:
  the six enrichment-failure paths (T3) all land on **absent**, and absent is a **deny** for the
  supervised population (T4) — verified end to end by `opa eval` against the real corpus before any test
  was written, not inferred: supervised + absent → `false`, `{}` → `true`, `staging` → `true`,
  `production` → `false`; membership → `true` in every state. The dictionary-outage branch (T2) rejects
  the write (503) rather than skipping the operator-managed check. **No path in the part returns more
  access on error than on success.**
- **The forbidden shape is guarded, measurably.** Beyond the four required clause-deletion mutations
  (each fails exactly 2 tests), the naive single-negation form was substituted into the corpus and
  **fails 6 tests**, and dropping the provenance conjunct **fails 24** (21 pre-existing). The slice's two
  named fail-open shapes are therefore caught by tests rather than by review attention.
- **Security audit.** (i) *IDOR/scope*: the instance path enriches from the **ancestor chain's** root,
  not from the URL, so a child in another catalog is judged by its real governing root (the URL-scope
  404 is unchanged). (ii) *An authz artifact served across subjects/requests*: the memo is
  `RequestAttributesResourceCache` — request-scoped, single-subject; nothing crosses either boundary.
  (iii) *The cache-contract amendment*: every cache **read** in the example app is keyed on the
  request's own leaf (`cachedCatalog`/`cachedCategory`/`cachedProduct` at their handlers), never on an
  ancestor id, so **no handler can read the decision-independent root memo** — checked by grep, not
  assumed. (iv) *Gateway exposure* of the new internal write endpoint: APISIX carries a positive
  `internal-blocked` route at priority 70 that 404s `/internal/*`. (v) *Injection surfaces*: none added
  (no SpEL/SQL/JSONB built from user input in the part).
- **Concurrency / idempotency (the invariant, not the mechanics).** The delta-rejection decides on the
  **same loaded entity the write persists** at all three update call sites, each of which already
  version-guards in its mutating path (drift → 409); the operator merge-upsert converges under retry
  (asserted); a root-tag change mid-request cannot yield a mixed gate/instance view because the memo
  pins one snapshot (asserted on **both** captured contexts).
- **Wiring.** Every new seam has a non-test caller and a non-happy-path test: the column → mapper →
  internal projection (I2 asserts it over the wire); the widened `validateAndBuild` (three call sites,
  U4); the exception → advice → enum → yaml chain (I3 asserts the **code**, not just the status); the
  internal controller (I4, four failure modes); the record component → the manager → the four rego
  clauses (U6–U11).
- **Core boundary / additivity / AND-not-replace.** `opa-abac-core` imports only Jackson + `java.util`.
  `opa-abac-spring-data`, `AbacResource`, `AbacResourceResolver`, `ParentRef` and both ancestor
  resolvers are **byte-unchanged** (`git diff --name-only`), so nothing tier-related can reach the
  residual — and `filter` is asserted **true** on the very gate input the tier denies, making "the
  decision lands at the gate" a test rather than a claim. **Zero library tests were modified across the
  whole part** (`git diff --name-status main...HEAD -- 'opa-abac-*/src/test'` is empty); the two
  example-app tests that changed are the two that asserted the supervisor role's pre-B shape.
- **Rego.** `default allow := false` intact in both files, no unconditional allow added, `filter` still
  has no subject-roles fallback, the mirrored bundle and `catalog.rego`/`team.rego`/`agent_tools.rego`
  untouched. Exactly one policy change, exactly four clauses — invariant (5) holds.
- **Schema.** Entity ↔ Liquibase ↔ real Postgres agree: the ITs boot under `ddl-auto: validate`.
- **Autonomous-run lens.** *Laziness*: every ticket's cited QA cases are implemented and named in its
  STATUS note; the tests assert the **cut** (allow/deny, 409-with-code, exact tag maps), not shapes.
  *Self-preferential bias*: three of the four ★gates recorded a real refactor (a removed setter, a
  removed save-switch, a consolidated enrichment point plus a redundant assertion) and none claimed
  "nothing found" while the diff said otherwise. *Goal drift*: the load-bearing invariants were re-checked
  **at the part boundary, not just per ticket** — additive-only, core Spring-free, one policy change,
  nothing in `filter`, provenance-scoping — all hold above.

### Finding, and what was done about it

**One finding, Low, documented rather than coded around — the root memo inherits the trust level of the
cache entry it reads.** `resolveRootAttributes` reads through the request cache, and the allow
write-through stores the resolved instance — which for the `@OpaPreAuthorize(resource = "#x")` form is an
object the **caller** supplied rather than one the resolver loaded. An application that used that form
for a type which is also a governing root, and then made a child check on that same root later in the
same request, would take `root_attributes` from the caller's object — a tier downgrade. This is an
extension of a trust boundary slice 5.97 already accepted ("the caller holds the instance") from "a
handler may reuse this snapshot" to "a later decision may read it", which is what makes it worth stating.

*Verified unreachable in this repository*: `grep` finds **no** `@OpaPreAuthorize(resource = …)` in either
example service's main code, and all six governing-root overrides are the `roleResourceType='catalog'`
gates. Not fixed in code: the namespaced-memo alternative contradicts the decomposition's explicitly
pinned decision to share the cache and amend its contract, and skipping the read would break the
one-resolve-per-request requirement (U15/I8). **Fix applied: an adopter caveat in
`resolveRootAttributes`'s javadoc**, naming the condition and the guidance (resolve governing roots
through the resolver, not through the `resource()` form). Recorded here so layer 3 can weigh it with the
whole delivery in view.

**No cross-part escalation.** Nothing found reaches an already-completed part — part 0 is the first part,
and every finding above lands inside T1–T4, which this runner owns.

**Re-tested after the review:** `./gradlew build` (all modules) green, `opa test infra/opa/policies/`
301/301, local Sonar 16 findings all in documented by-design FP classes.

