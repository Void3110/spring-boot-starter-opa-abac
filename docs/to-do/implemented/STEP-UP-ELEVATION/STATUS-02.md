---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/spring
---

# STATUS — T2: policy: `elevated`, the amended production denies, sole-blocker `deny_reason`, the agent deny

**Status:** ✅ DONE

## What shipped

**`infra/opa/policies/step_up.json`** — the one auditable home for the knobs, loaded with the corpus
like `permission_categories.json`: `{"step_up": {"loa": {"aal1": 1, "aal2": 2}, "max_age": 300,
"skew": 30}}`. `max_age` mirrors T1's realm level-2 condition max age (one window, two homes,
cross-referenced in `infra/README.md`); the rig's data API can override the **leaf** path at runtime,
which is what T6's freshness drill rides.

**`category.rego` and `product.rego`, identically** (per-type files drift, so each carries the full
shape and its own guards):

- **`denied` split into two halves.** `denied = stepup_denied ∨ denied_other`, where `denied_other`
  is *by construction* "every deny except the step-up clause" — exactly what the sole-blocker rule
  has to negate. The three pre-existing/new deny bodies moved onto `denied_other` unchanged; the
  header carries the rule a future slice needs: **add a new deny clause to `denied_other`, never to
  `denied`** — a clause landing directly on `denied` is invisible to `not denied_other` and would
  leak a challenge.
- **`elevated`** — `not is_agent_call` + `data.step_up.loa[input.subject.attributes.acr] >= 2` +
  `(time.now_ns() / 1000000000) - input.subject.attributes.auth_time <= data.step_up.max_age +
  data.step_up.skew`. Undefined on a missing/unmapped `acr`, a missing `auth_time`, or a
  **non-numeric** `auth_time` (the subtraction is a type error → undefined), so `not elevated` holds
  and the deny stands in all three cases. OPA's own clock; explicit skew.
- **`stepup_denied`** — supervised + `"production" in root_env_values` + **`not elevated`**. This is
  the *only* clause elevation narrows.
- **The unproven (absent-root) clause is untouched** and now sits in `denied_other`, which makes
  Amendment 2 structural twice over: an enrichment outage is closed for an elevated supervisor, and
  it answers **plainly** rather than with a challenge a second factor could not satisfy.
- **The agent deny** — `denied_other if { provenance == "supervised"; is_agent_call }`, and
  **`is_agent_call` is a presence-test**: `"act_chain" in object.keys(input.subject.attributes)`.
- **`deny_reason`** — `{"type": "insufficient_user_authentication", "required_acr": "aal2",
  "max_age": data.step_up.max_age}` iff `stepup_denied ∧ granted ∧ not denied_other`.

**`catalog.rego`** — `is_agent_call` + the same provenance-scoped agent deny, and **nothing else**:
the root type carries no tier (that lives on the children), a catalog read is metadata-only
(ADR 0030 §1) and is never the sensitive act, so there is nothing here a factor would open and
nothing to challenge for.

**Doc delta: none by design** — the policy comments carry the contract references (T4 owns the guide
subsection). Every new rule is commented with the ADR clause it implements and the trap it avoids.

## Tests

`opa test infra/opa/policies/` — **367/367**, grown from 308 (**+59**); `opa check --strict` clean.
Every window case runs on a pinned clock (`with time.now_ns as …`).

| Case | Where | What it pins |
|---|---|---|
| **U1** | both child files | no `acr` → the LoA lookup is undefined → deny |
| **U2** | both | `acr: aal2`, no `auth_time` → the arithmetic is undefined → deny |
| **U3** | both | `acr: "gold"` (unmapped) → deny; `acr: "aal1"` (mapped, below 2) → deny |
| — | both | a **string** `auth_time` → deny (the type-coercion trap a `to_number` "fix" would open) |
| **U4** | both | fresh `aal2` **allows** production on the instance shape, the coarse type-level gate, **and** the array-shaped `env` |
| **U5** | both | a stale `auth_time` denies |
| **U6** | both | the boundary is `<=`: exactly `max_age + skew` (330 s) allows, 331 s denies |
| **U7** | both | absent `root_attributes` + fresh `aal2` → **deny**, instance and gate — the unproven tier is elevation-proof |
| **U8** | both | the sole-blocker matrix: reason **present** (exact object) on instance + gate, and its `max_age` **read from data** (a `with data.step_up` override moves it to 60); **absent** for an already-elevated subject, a write verb, a member, a staging root, an untagged root, an enrichment outage, and when `abac_deny` fires |
| **U9** | **all three** files | supervised + `act_chain` denies at production/staging/untagged/absent tiers and on the gate; `false`, `[]`, `""`, `0`, `null` **all** still deny (one `every` loop per file); a **member** + `act_chain` is unaffected; the control (the same supervised read *without* the claim) allows |
| **U10** | both child files | the constructed "elevated agent": production + fresh `aal2` + `act_chain` → deny **and** `deny_reason` undefined |
| — | both child files | `elevated` itself is false for an agent call and true for the same human claims — asserted **directly**, see the review note |
| — | all three | `filter` still answers **true** on the very inputs the gate denies, for the elevated *and* the agent shapes — invariant 5, re-asserted against both of C's new discriminators |

**U11 — the deletion-mutation guards, measured (baseline 367/367; corpus restored after each):**

| Deleted clause | Result | Caught by |
|---|---|---|
| `category.rego` `not elevated` conjunct | 362/367, **5 fail** | the three fresh-`aal2`-allows cells, the boundary cell, `no_deny_reason_when_already_elevated` |
| `category.rego` `denied if { stepup_denied }` | 356/367, **11 fail** | every production-denies cell, incl. **pre-existing** B cells |
| `category.rego` `deny_reason` rule | 364/367, **3 fail** | the three reason-present cells |
| `category.rego` agent deny | 364/367, **3 fail** | the agent-tier, falsy-shapes and elevated-agent cells |
| `category.rego` `not is_agent_call` in `elevated` | 366/367, **1 fail** | `test_elevation_is_a_human_ceremony` |
| `product.rego` — the same five | 362 / 356 / 364 / 364 / 366 | the sibling cells, per file |
| `catalog.rego` agent deny | 365/367, **2 fail** | `supervised_agent_catalog_read_denied`, the falsy-shapes cell |

**B's amended clauses, re-measured** (not assumed to still hold):

| Mutation | Result |
|---|---|
| `category`/`product` production clause neutralised whole | 351/367, **14 fail** each — B's four production cells plus C's ten |
| `category`/`product` unproven (absent-root) clause deleted | 362/367, **3 fail** each — B's two absent cells plus C's elevation-proof cell |

**Two forbidden shapes, mutated because the design names them:**

| Mutation | Result |
|---|---|
| `is_agent_call` regressed to the bare truthiness test `input.subject.attributes.act_chain` | 364/367, **1 fail** per file — the falsy-shapes `every` loop, which exists for exactly this |
| `not elevated` **added** to the unproven clause (Amendment 2 violated) | 364/367, **1 fail** per file — `test_unproven_tier_stays_closed_for_the_elevated` |

## Architecture review + refactor

Path: **inline self-review** (the ★ gate).

- **Sole-blocker completeness — enumerated mechanically, not by eye.** Every deny-shaped rule head in
  the three files was listed by grep and ticked:

  | File | `denied` clauses | `denied_other` clauses | `stepup_denied` |
  |---|---|---|---|
  | `category.rego` | 2 — both delegating (`denied_other`, `stepup_denied`) | 3 — `abac_deny`, unproven-root, agent | 1 |
  | `product.rego` | 2 — likewise | 3 — likewise | 1 |
  | `catalog.rego` | 2 — `abac_deny`, agent | n/a (no `deny_reason` here) | n/a |

  So in both child files `denied_other` **is** the complete set of non-step-up denies, by
  construction rather than by inspection: no deny body sits directly on `denied`. `filter_list_denied`
  is filter-only and never feeds `denied` — checked, not assumed.
- **Fail-closed.** Elevation only ever *removes* a deny, and only the production one. Every undefined
  input direction was tested rather than argued (U1, U2, U3, the string-`auth_time` case), and the
  unproven clause is guarded in **both** directions: deleting it fails 3 tests, and *adding*
  `not elevated` to it also fails a test.
- **The widenings that would matter here, and why they cannot happen.** (a) *A `deny_reason` leaking
  tier information to someone who is not one elevation away* — impossible while `granted ∧ not
  denied_other` guards it; U8 walks all seven "absent" cases. (b) *A challenge for a write* — the
  write is not `granted`, so no reason. (c) *A challenge for an agent* — the agent deny is a
  `denied_other`. (d) *The presence-test regressing to truthiness* — mutated, caught. (e) *Anything
  elevation- or agent-related entering `filter`/`bulk`* — the `filter`+`bulk` tails of all three files
  are **byte-identical to `HEAD`**, verified by a script, and `filter` is asserted true on the very
  inputs the gate denies.
- **Refactor applied — one, and it came out of the review's own wiring check.** The `not is_agent_call`
  conjunct inside `elevated` initially had **zero** guarding tests: the mutation deleting it left the
  corpus at 367/367, because the agent deny already closes every path that reaches `allow`. Rather
  than delete the conjunct (defence in depth is the point — it holds if the deny is ever relaxed) or
  leave it unguarded, a test now asserts **`elevated` directly** in both directions. Re-measured
  after: deleting the conjunct fails 1 test per file. Nothing else was churned.
- **Pattern reuse.** B's per-clause mutation-guard discipline, its two-clause tier shape and the
  `root_env_values` cardinality normalisation are reused untouched; the presence-test discriminator is
  the recorded `actor=false` escape's fix applied to `act_chain`; the deny-overrides idiom is the
  corpus's existing strongest idiom, not a new mechanism.
- **Slice boundary.** `permissions.rego`, `permission_categories.json`, `team.rego`, `role.rego`,
  `gateway.rego` and **`agent_tools.rego`** are untouched — `git status` shows exactly the three leaf
  policies, their three test files, and the new `step_up.json`.

## Integration / e2e

None in this ticket by design — the corpus is `opa test`-provable, and that is the whole acceptance
(**U1–U11**, above). The rig-side proof (a real elevation over the wire, the freshness drill, the
agent cells) is T6's, in part 1. No policy file reaches the rig in part 0 beyond being on disk: OPA is
not restarted here, so the deployed decision is unchanged until part 1 restarts it.

## Decisions

- **`required_acr` is the literal `"aal2"`, not derived from `data.step_up.loa`.** Deriving "the
  lowest name whose level ≥ 2" was written and rejected: as a complete rule it produces *multiple
  outputs* the moment two names share a level, which is an eval error at request time — a fail-closed
  outcome, but one that turns a data typo into a corpus-wide 500 rather than a plain deny. The design's
  own snippet (00-DESIGN §2) and ADR 0030 §6 both write the literal; the level threshold `2` is
  likewise literal in the same rule, so the two agree by sitting three lines apart. What genuinely
  must not be duplicated — the **window** — *is* read from data, and a test proves it moves when the
  data moves.
- **The deny split is `denied → {denied_other, stepup_denied}`, not "`denied_other` = `denied` minus a
  clause".** The alternative (leaving the bodies on `denied` and re-deriving the others inside
  `deny_reason`) would have made completeness a property of a hand-written list — precisely the thing
  the ★ gate's completeness check exists to catch. Here it is a property of the rule graph, and the
  header says so for the next slice.
- **The `not is_agent_call` conjunct in `elevated` is kept although the agent deny makes it
  unreachable today.** See the review note — it is defence in depth against a future relaxation of
  the deny, and it is now directly guarded rather than trusted.
- **No `deny_reason` in `catalog.rego`.** The root's own read is metadata-only, so a challenge there
  would advertise a factor that opens nothing. Stated in the file so a later "for symmetry" edit has
  to argue with it.

## Commit

`feat(step-up-elevation): elevated, the sole-blocker deny_reason, and the human-only supervised path
(T2)` — `step_up.json` + the three leaf policies + 59 new cases with their deletion-mutation guards,
on `feature/void3110/step-up-elevation`.
