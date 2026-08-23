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

## Low Issues

| # | Issue | Status |
|---|-------|--------|
| 3 | Java shadows of the wildcard lookup (`RoleDefinitionService.grantedTokensFor`, `TypeLevelRoleDefinitionSupplier.permissionsFor`/`deniedFor`) used `get() == null`, treating a present-null key as absent — diverging from the presence rule the policy now pins. (`expandWildcard` already used `containsKey`; its null-value bug is #7 above.) | **Fixed** — `containsKey` in all three lookups |
| 4 | Mutation-prove round 1: mutant M4 (`denied_for` empty-clause guards) survived — no test covered a `false` denial without a `"*"` key. | **Fixed** — the round-2 denial suite kills it (`test_boolean_false_denial_without_wildcard_also_denies`) |
| 5 | The Mulch record shipped with round 1 pinned the refuted denial semantics and under-scoped the sweep rule (function heads only). | **Fixed** — record `mx-c615a4` rewritten (axis asymmetry, the three measured traps, assigned-heads + Java-shadows sweep rule) |
| 8 | Totality precision (round 2): `tokens_for`'s `[]` fallback and `resource_tag_values`' empty fallback never fired for a *wholly-absent* map — the guard over `object.keys(<absent ref>)` cannot succeed; the documented contract held only via comprehension absorption (decision-identical, but the header's totality claim was aspirational). | **Fixed** — `object.get` bindings in both (mirroring `denied_for` clause 3); decision-invisible by construction, so their mutants are *predicted equivalents* (measured: M14/M15 survive) |
| 9 | The Java presence-semantics change shipped without a pinning test (the contract suite's `Map.of` fixtures cannot express null values). | **Fixed** — `presentNullTypeKeyDoesNotWidenDenialValidationIntoWildcard` (HashMap fixture; fails on the old nullness code, passes on presence) |

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
kept as stated intent). Round 3 (final tree, 10 mutants): **8 killed** — including the three new
`filter_list_denied` shape clauses, each by its own file's filter pin — and **2 survived exactly
as predicted**: M14/M15 are the totality bindings (#8), decision-invisible by construction and
marked equivalent in-module before the round ran.

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

- `opa test infra/opa/policies`: **403/403** · mirror bundle: **32/32** · `opa check --strict`:
  clean on both
- `./gradlew build` (all modules, Testcontainers ITs against the changed bundle): **green**
- `.sonar-local/sonar-local.sh` (Java touched): **CLEAN — 0 open findings**
- Mutation-prove: **11/12 killed + 1 documented equivalent**
- Measured re-pros (OPA 1.10.1): `{"category": false}` grant → `[]`-deny (was 500); tag
  `{"region": false}` → no-match deny (was 500); malformed denial → undefined at
  `effective_actions` / `false` at `category.allow` / `false` at `role.assignable` (was 500 on
  `main`, was **allow** after round 1)
- e2e (newman): not run this branch — the diff changes malformed-shape behavior only; well-formed
  paths are covered by the 432 policy tests + the Testcontainers ITs. The standing pre-release
  fleet re-run (carried since the 08-20 handoff) covers the gateway path before the next cut.
- Adversarial review: round 1 — 8 lenses, 8 confirmed (1 Critical), 2 refuted; round 2 — 11
  confirmed: 6 were the commit-the-working-tree process artifact (the lenses diff committed HEAD;
  they independently truth-tabled the uncommitted fixes as correct), 5 were new (#6–#9 above);
  round 3 — terminal no-fix round on the committed tree (result recorded below before push).

## Commits

- `682ce72` fix(policies): guard fallback clauses on key presence, not truthiness (round 1)
- round 2+3: denial-axis shape guard + hoist + object.get bindings, filter-path sibling, Java
  presence lookups + NPE guard, docs, tests, review note (this commit)
