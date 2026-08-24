---
tags:
  - status/active
  - type/review
  - area/opa
  - area/abac
---

# Boolean-key eval-conflict fix — Code Review

> **Verdict**: Approved with fixes
> **Scope**: Rego-only fail-closed repair (plus two Java shadow touch-ups and doc sync): fallback
> clauses that guarded on truthiness instead of key presence made a `false`-valued key produce two
> function outputs — `eval_conflict_error`, surfaced by OPA's data API as HTTP 500 instead of the
> documented deny. · **Branch**: `feature/void3110/boolean-key-eval-conflict-fix` vs `main`

## Summary

The defect arrived as an external consumer review handoff (2026-08-23) naming the four fallback
clauses in `permissions.rego`. Investigation widened the class before fixing it: the same
truthiness shape lived in `resource_tag_values` in all three per-type modules (a `false`-valued
resource attribute for a required tag key conflicted identically — reproduced), and in the
user-management bundle's byte-identical mirror copy, which CI's drift check makes a mandatory
second fix site. `false` is Rego's only falsy defined value, so it was the single escaping shape —
the same class the `is_agent_call` presence guard fixed on 2026-07-31 (`agent_tools.rego:61-72`),
whose sibling sweep stopped at partial rules and never reached the multi-clause functions.

Round 1 fixed all sites with key-presence guards (`not k in object.keys(o)`) and pinned
"present-but-malformed behaves like the present empty list" **uniformly on both axes**. The
multi-lens adversarial review then refuted that pin on the denial axis as a **Critical fail-open**
(below) — round 2 shape-guarded the denial axis. Both rounds' behavior is mutation-proved.

## Critical Issues

| # | Issue | Status |
|---|-------|--------|
| 1 | **Denial-axis fail-open (introduced by round 1 of this branch — never on `main`).** Reading a present-but-malformed `denied_actions` value as "subtracts nothing" converted `main`'s conflict-500 (→ deny via the fail-closed caller) into an **allow**: `denied_actions: {"*": ["view"], "category": false}` granted `view`. Measured at three levels by the review workflow: `effective_actions`, `category.allow` (decision), and `role.assignable` (the amplifier — a corrupt actor snapshot widened what that actor could hand out). Root error in the round-1 rationale: "cannot exceed the role" is the wrong bound, because deny-overrides exists to narrow *below* the grants (ADR 0007). | **Fixed** (round 2) |

**The round-2 fix — a deliberate axis asymmetry.** A malformed grant value only under-grants
(present key wins; a non-array expands to nothing → empty set → deny). A malformed **consulted**
denial value now leaves `denied_for` **undefined**, and `effective_actions` collapses with it, so
every consumer lands on its default deny. Only the consulted lookup is validated — garbage under
an unconsulted type key stays inert (narrowest safe reading; the write path rejects the shape
anyway). Two Rego mechanics are load-bearing and documented in-module, both measured on 1.10.1:

1. **The hoist**: a set comprehension absorbs an undefined function call into the empty set, so
   the denial subtraction must be a direct binding (`denied_list := denied_for(...)`) — the
   in-comprehension shape silently converts "deny everything" into "subtract nothing".
2. **The `object.get` binding**: `object.keys(<wholly-absent ref>)` inside a `not` guard does not
   succeed — the empty-fallback clause must bind `object.get(role_def, "denied_actions", {})`
   first or it never fires for roles without denials (round 1 had exactly this bug, masked by the
   same comprehension absorption; exposed as 138 test failures the moment the hoist landed).

## Medium Issues

| # | Issue | Status |
|---|-------|--------|
| 2 | `effective_from_categories` deleted while `docs/guides/PERMISSION-MODEL.md` still declared it "kept as shared package API" (and described the axes as symmetric). | **Fixed** — guide rewritten (removal note + the axis asymmetry); ADR 0014's historical mention annotated |

## Medium Issues (round 2 — new findings against the fixed tree)

| # | Issue | Status |
|---|-------|--------|
| 6 | **The LIST-path denial sibling** (`filter_list_denied`, catalog/category/product): `"list" in denied_actions[type]` over a non-collection value is undefined, so `not filter_list_denied` succeeded and the residual silently dropped a malformed denial — the filter was wider than the decision on the same shape. | **Fixed** — second `filter_list_denied` clause (`not is_array(denied)`) in all three files; both clauses fold to constants at partial-eval (input-known), residual shape unchanged; pinned by `test_filter_malformed_denial_fails_closed` ×3 with positive baselines |
| 7 | **`EffectiveRoleService.expandWildcard` NPE**: `Map.of(targetType, map.get("*"))` throws on a present-null `"*"` value — and that shape is *storable* (the write path `nullSafe`-iterates values, so it validates clean) → HTTP 500 on the resolve wire. | **Fixed** — null-guard returns the map unchanged (a null wildcard expands nothing, matching the policy side) |

## Medium Issues (round 3 — the next stratum)

| # | Issue | Status |
|---|-------|--------|
| 10 | **Residual-wider-than-decision asymmetry introduced by round 2**: a *non-object* `denied_actions` map left both `filter_list_denied` clauses undefined (indexing a non-object is undefined), so `filter` passed while the decision side newly denies via `denied_for`'s object guards — measured `allow=false, filter=true` in all three per-type policies. | **Fixed** — third `filter_list_denied` clause (`not is_object(denied_actions)`, bound so a wholly-absent map stays non-denying) ×3 + filter pins ×3 |
| 11 | **Pre-existing member of the same class on `main`**: `required_tags: false` (or a number) type-errors `count()` → `has_required_tags` undefined → the vacuous `tags_satisfied` clause passes — the configured tag narrowing silently drops on decision AND list, violating the files' own "malformed → deny" header. Measured `allow=true` with `required_tags: false`/`123`. | **Fixed** — `has_required_tags` re-keyed on presence with an explicit empty-object carve-out (present non-object = a requirement nothing satisfies → deny) ×3 + decision/filter pins with positive baselines ×3 |

## Medium Issues (round 4 — the last consumer of the class)

| # | Issue | Status |
|---|-------|--------|
| 14 | **`has_role_definition` was itself a truthiness guard, consumed NEGATED** in catalog's B4 create fallback: `permissions: false` made a *present* resolved role read as absent and reopened the realm `catalog:create` branch (measured: `false` → allow, `{}` → deny). The mirror-image of the `is_agent_call` escape — a partial rule under `not` — which the assigned-heads sweep was structurally blind to. | **Fixed** — presence-keyed in all three files (siblings changed for parity; their positive-only filter consumers make the mutants documented equivalents) + the contrast pin (`fallback_input` + `permissions: false` vs absent) |

## Low Issues (round 4)

| # | Issue | Status |
|---|-------|--------|
| 15 | `required_tags: []` escaped the round-3 carve-out: `[]` + `ALL_OF` allowed via vacuous `every` (contradicting the new doctrine comment) while `[]` + `ANY_OF` silently flipped from `main`'s allow to deny — untested in both directions. | **Fixed** — `empty_required_tags` gains the empty-array clause (present empty collection = no requirement, back-compat both modes; non-empty arrays deny) + pins ×3 |
| 16 | The `expandWildcard` fix comment claimed a null `"*"` "expands nothing, exactly like the policy side" — wrong on the denial axis, where a present-null `"*"` denial collapses `denied_for` → deny-all. And the storable-null class deserved dying at the source. | **Fixed** — `validateContract` now rejects null map values outright (422, both maps, pinned); the lookup/null-guard fixes are re-framed as legacy-row defense; comments corrected |
| 17 | The gateway-reachable behavior change (null-value role writes) has no newman/e2e cell. | **Carried, not fixed** — with #16 the wire pin becomes "POST with a null value → 422"; folded into the standing pre-release e2e fleet re-run (tracked since the 2026-08-20 handoff) rather than editing collections in this branch |

## Low Issues

| # | Issue | Status |
|---|-------|--------|
| 12 | Round 2's filter comment misdocumented the PE mechanism ("folds to constants — input.resource.type is known"): `input.resource` *is* the unknown, the clauses fold to negated type-eq guards, and safety on fired guards holds via the unsupported-residual → batch-recheck degradation, not constant folding. | **Fixed** — comment corrected ×3, naming the real mechanism |
| 13 | The `expandWildcard` NPE fix (#7) shipped without a pinning test (the contract-suite `Map.of` fixtures cannot hold nulls). | **Fixed** — evolved across rounds 5–6 into `presentNullWildcardGrantNarrowsButNullDenialRefusesToResolve` (multi-key HashMap fixture through the public `resourceRole` seam, pinning the OUTCOMES: grant projects to the empty target grant with siblings dropped; denial refuses to resolve) |
| 3 | Java shadows of the wildcard lookup (`RoleDefinitionService.grantedTokensFor`, `TypeLevelRoleDefinitionSupplier.permissionsFor`/`deniedFor`) used `get() == null`, treating a present-null key as absent — diverging from the presence rule the policy now pins. (`expandWildcard` already used `containsKey`; its null-value bug is #7 above.) | **Fixed** — `containsKey` in all three lookups |
| 4 | Mutation-prove round 1: mutant M4 (`denied_for` empty-clause guards) survived — no test covered a `false` denial without a `"*"` key. | **Fixed** — the round-2 denial suite kills it (`test_boolean_false_denial_without_wildcard_also_denies`) |
| 5 | The Mulch record shipped with round 1 pinned the refuted denial semantics and under-scoped the sweep rule (function heads only). | **Fixed** — record `mx-c615a4` rewritten (axis asymmetry, the three measured traps, assigned-heads + Java-shadows sweep rule) |
| 8 | Totality precision (round 2): `tokens_for`'s `[]` fallback and `resource_tag_values`' empty fallback never fired for a *wholly-absent* map — the guard over `object.keys(<absent ref>)` cannot succeed; the documented contract held only via comprehension absorption (decision-identical, but the header's totality claim was aspirational). | **Fixed** — `object.get` bindings in both (mirroring `denied_for` clause 3); decision-invisible by construction, so their mutants are *predicted equivalents* (measured: M14/M15 survive) |
| 9 | The Java presence-semantics change shipped without a pinning test (the contract suite's `Map.of` fixtures cannot express null values). | **Fixed, then superseded (round 4)** — the original presence pin was replaced by the upstream 422 rejection pair (`presentNullPermissionsValueRejected` / `presentNullDeniedActionsValueRejected`): with null values refused at the write path, the presence lookups are legacy-row belt with no reachable pinnable difference |

## Fail-closed verification

Every error/malformed path traced to deny; the two axes deliberately differ:

- **Grants**: present-but-malformed value (`false`, `true`, scalar) → concrete clause → non-array
  expands to `∅` → empty set (deny). Absent key → `"*"` fallback → absent both → `[]`. Malformed
  `role_def`/absent map → `[]` or undefined-absorbed-to-∅ — deny either way. Pinned by tests.
- **Denials**: malformed **consulted** value (concrete non-array; `"*"` non-array when consulted;
  non-object `denied_actions`) → `denied_for` undefined → `effective_actions` undefined →
  consumer default deny. Unconsulted garbage inert; well-formed `"*"` still applies beside it.
- **Tags (per-type)**: `false`-valued attribute → `{false}` singleton → intersects no acceptable
  set → no-match deny. Absent key → `set()` as before.
- **Consumer sweep**: every production consumer of `effective_actions` uses it in a **positive**
  position (`verb in …` at catalog:58, category:72/129/141, product:72/126/138, team:64 ×2
  bundles, agent_tools:95, role:36-37 direct bindings) — undefined fails the clause into
  `default allow/assignable := false`. No negated production use exists (grep-verified), so the
  undefined-collapse cannot widen anything.

## Security audit

The one real finding was the round-1 denial-axis widening (Critical above) — caught by the
adversarial pass *before merge*, including its privilege-amplifier through `role.assignable`'s
actor ceiling. No injection surface (no string-built queries; policy input only), no secrets/log
changes, no authn edge touched. The reachability note is honest: in-repo, Java typing
(`Map<String, List<String>>`) cannot emit `false` on the wire; the exposure is the module's
declared copy-portability into consumer corpora where `input.role_definition` is PEP-supplied —
which is exactly where the downstream review found the original 500.

## Concurrency & idempotency

Not applicable in substance: the diff is pure policy evaluation (stateless functions) plus two
read-only Java lookup helpers. No locks, no versions, no retries touched.

## Wiring & sibling sweep

- The class was swept to the **complete population**: all 5 multi-clause functions across the
  corpus (head-enumeration `^name(...) :=`), the user-management **mirror bundle** (byte-identical
  re-copy, `cmp`-verified — CI drift check), the three **Java shadows** (fixed) plus
  `expandWildcard` (already presence-correct), and the **filter rules** (round-1 skeptic examined
  the direct `denied_actions` reads in the list residuals and refuted the claimed sibling defect).
- New tests all paired with positive controls: the decision-level witness asserts the same input
  allows under a well-formed role before asserting deny under the malformed one; the assignable
  pins assert the well-formed baseline first.
- `effective_from_categories` deletion: zero callers outside its own tests (grep across `.rego`,
  Java, and the downstream consumer's entry-point block).

## Mutation-prove (mx-9b29e5 discipline)

Three rounds, cumulative. Round 1 (7 mutants): 6 killed, **M4 survived** → became finding #4.
Round 2 (12 mutants over the shape-guarded rework): 11 killed — truthiness reversions at every
site, dropped shape guards, the un-hoist (M11 — 7 tests fail), the `object.get`-binding
reversion (M12 — 138 tests fail) — plus M10 (`is_object` in the empty-fallback clause), a
documented **equivalent mutant** (`object.keys` already errors-to-undefined on non-objects;
kept as stated intent). Round 3 (10 mutants): **8 killed** — including the three new
`filter_list_denied` shape clauses, each by its own file's filter pin — and **2 survived exactly
as predicted**: M14/M15 are the totality bindings (#8), decision-invisible by construction and
marked equivalent in-module before the round ran. Round 4 (the round-3 guards, 6 mutants —
the non-object-map filter clause and the presence-keyed `has_required_tags`, per file): **all 6
killed**, each by its own file's new pin. Round 5 (the round-4 guards): catalog's
`has_role_definition` truthiness-revert **killed** by the fallback contrast pin;
category/product's survive as **predicted equivalents** (positive-only consumers deny either
way — recorded before the round ran); all three empty-array carve-out drops **killed** by the
`[]`+`ANY_OF` pins.

## Autonomous-run check

Not an autonomous run — interactive fix session from a downstream handoff. The self-preferential
hazard showed up anyway: round 1 pinned its own convenient semantics ("cannot exceed the role")
and wrote a test asserting the widening. The adversarial round refuted it from source and
measurement. That is the review layer doing exactly its job; recorded in Mulch.

## What's done right

- The 500 class is gone corpus-wide, with the regression suite asserting behavior that previously
  could only *error* (pre-fix, the `false` cases ERRORED the suite — which is why 28 green tests
  never saw it).
- The axis asymmetry is now a stated, tested, in-module doctrine rather than an accident: grants
  under-grant, denials collapse — each axis fails toward less access.
- The copy-portability contract survives: the module remains self-contained, the mirror is
  byte-identical, and the downstream re-copy stays a mechanical `cp` + one package line.

## Test results

- `opa test infra/opa/policies`: **423/423** · mirror bundle: **33/33** · `opa check --strict`:
  clean on both
- `./gradlew build` (all modules, Testcontainers ITs against the changed bundle): **green**
- `.sonar-local/sonar-local.sh` (Java touched): **CLEAN — 0 open findings**
- Mutation-prove: **11/12 killed + 1 documented equivalent**
- Measured re-pros (OPA 1.10.1): `{"category": false}` grant → `[]`-deny (was 500); tag
  `{"region": false}` → no-match deny (was 500); malformed denial → undefined at
  `effective_actions` / `false` at `category.allow` / `false` at `role.assignable` (was 500 on
  `main`, was **allow** after round 1)
- e2e (newman): not run this branch — the diff changes malformed-shape behavior only; well-formed
  paths are covered by the 423 (+33 mirror) policy tests + the Testcontainers ITs. The standing pre-release
  fleet re-run (carried since the 08-20 handoff) covers the gateway path before the next cut.
- Adversarial review: round 1 — 8 lenses, 8 confirmed (1 Critical), 2 refuted; round 2 — 11
  confirmed: 6 were the commit-the-working-tree process artifact (the lenses diff committed HEAD;
  they independently truth-tabled the uncommitted fixes as correct), 5 were new (#6–#9 above);
  round 3 — 5 confirmed (#10–#13), 4 refuted (incl. the Java `deniedFor` present-null claims —
  re-derived independently: the supplier's maps are internally built via `List.copyOf`/`Map.of`,
  nulls unreachable — and the filter wildcard-denial claim, safe via the batch-recheck
  degradation); round 4 — 5 confirmed (#14–#17 + one duplicate of #15's sibling scope), 2
  refuted (both re-raising the filter wildcard-denial shape, again cleared via the batch-recheck
  degradation); round 5 — 7 confirmed (the resolve-wire Critical ×2 lenses + 5 lower, all fixed
  or reconciled — see the Round 5 section), 1 refuted; round 6 — 9 confirmed, 0 refuted (see the
  Round 6 section; two Mediums against this branch's own rounds-4/5 additions, both fixed);
  round 7 — 2 confirmed, both Low, zero behavior-changing: (a) the wildcard-denial filter
  claim re-raised through one lens and was killed by the round's own critic-sibling refutation
  with the deeper measurement — under PE the `"*"` key emits a `not "*" = input.resource.type`
  guard, `CompileResponseParser` marks the non-EQ unsupported → batch allow-recheck
  (wildcard-aware) → fail-closed, and the shipped wire projects `"*"` app-side before OPA sees
  it (pinned U38); the confirmed variant had measured full-eval `filter` with a concrete type,
  a path the list flow never takes — **cleared, no code change** (fourth time this claim died);
  (b) the round-6 DTO normalization was unpinned — test added
  (`UserMgmtMapperRoleDefinitionTest`). **Terminal verdict: round 7 stands as the terminal
  round** — zero behavior-changing findings with all lenses run; the only post-round-7 delta is
  the one unit test, verified green by the build. Two round-8 attempts were killed by
  infrastructure (a session usage limit, then API 529) with the finder lenses never executing —
  their empty results are not verdicts — and the maintainer waived a third attempt as a
  proportionality call (2026-08-24): six effective adversarial rounds on this ticket, with
  later-round findings increasingly pre-existing-on-main corpus surface rather than regressions
  of the diff. The proportionality lesson is recorded in Mulch (`code-review-process`) and the
  skill's termination rule. The convergence followed the recorded strata pattern (mx-ab7cda):
  code edge → latent siblings → deeper policy edges → the last negated consumer → the pipeline
  seam (guard-vs-normalizer ordering) + doc tail.

## Round 5 — the resolve-wire widening (Critical, against round 4's own fix)

The round-4 `expandWildcard` null-guard's justification was **factually wrong**: the pass-through
map flows into the core `RoleDefinition` canonical constructor, whose `copyOfStringListMap`
normalizes `null → List.of()`, so the wire ships `{"*": []}` — a *well-formed subtracts-nothing*
— not the claimed policy-side deny-all collapse. On `main` the identical legacy row NPE'd →
500 → `RoleResolutionException` → the ADR-0014 deny: **fail-closed**. Round 4 made the corrupt
row resolve with every denial dropped. Fix: the axes split *before* construction —
`requireListValues` refuses to resolve a null **denial** value (throw → the deliberate
500→deny), while a null **grant** value passes through and normalizes to an empty grant
(narrows). Comments corrected; the test now pins the *outcomes* (grant `[]`-normalized, denial
throws). The pipeline lesson joined the Mulch record: **trace the whole pipe — a downstream
normalizer can rewrite the malformed value before it reaches the PDP, so the guard must sit
upstream of it.** Also from round 5: a present **non-object** `role_definition` now blocks the
create fallback (explicit null stays honestly absent) ×3 + pins; `is_type_level_request`
presence-keyed in category/product (a present `id: false` was read as type-level — the widening
gate) + helper pins; the decision-level malformed-denial witness mirrored into
catalog/product suites; this note's stale counts and Commits section reconciled; the newman
deferral (#17) sharpened: the pre-release e2e pass must **author** the negative cell (POST a
role with a null map value → 422 + problem code), not merely re-run existing collections.

## Round 6 — the two holes in this review's own additions

- **`has_role_definition` collapsed to `!= null`** (Medium): a present object *without* a
  `permissions` key — a role carrying only denials — satisfied neither round-4/5 presence
  clause, so the create fallback opened and silently dropped the role's explicit denial
  (wire-unreachable via the library serializer, in-scope by this branch's own standard). Any
  present non-null document now blocks; explicit null stays honestly absent. Pinned
  (denials-only role blocks create); category/product parity mutants remain documented
  equivalents.
- **`expandWildcard` null-star now projects to the empty target grant** (Medium): the round-5
  pass-through retained sibling concrete keys, so `{"*": null, "category": [WRITE]}` resolved
  *wider* than its well-formed twin `{"*": [], "category": [WRITE]}` (which collapses to
  `{target: []}`). The corrupt row now gets the same twin collapse — siblings dropped — and
  the multi-key shape is the pinned fixture.
- Lows: the supervised tier's `root_attributes` truthiness guard gained a shape clause
  (present non-object = unprovable tier = closed) in category+product + pins; legacy null map
  values are normalized at the DTO mapper so documented GET responses match the schema; the
  malformed-denial decision witness now covers all six `effective_actions` consumers
  (team ×2 bundles + agent_tools added); this note's #9/#13 rows reconciled with the tree.
- Carried (unchanged disposition): the newman 422-cell authorship in the tracked pre-release
  e2e pass (#17).

## Commits

- `682ce72` fix(policies): guard fallback clauses on key presence, not truthiness — round 1
- `3f66816` fix(policies): shape-guard the denial axis; align the list residual; presence-fix
  the Java shadows — round 2 (the refuted-Critical fix + hoist + bindings)
- `a6f2fc9` fix(policies): close the residual-vs-decision asymmetry and the required_tags
  scalar escape — round 3
- `cfdafae` fix(policies): presence-key has_role_definition; empty-collection carve-out; reject
  null map values at the write path — round 4
- `dd8c869` fix(resolve): refuse to resolve null denial values; presence-key the last
  truthiness readers — round 5
- round 6: `has_role_definition` `!= null` collapse, twin-matching null-star projection,
  supervised-tier shape clause, DTO normalization, witness parity, note reconciliation
  (this commit)
