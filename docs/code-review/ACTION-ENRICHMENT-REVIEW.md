---
tags:
  - status/done
  - type/review
  - area/abac
  - area/spring-security
  - area/spring-data
  - area/opa
---

# Action enrichment (Phase 6) — Code Review

> **Verdict**: Approved with fixes
> **Scope**: the read-side `_actions` affordance slice (T1–T7) — `ActionEnrichmentAdvice` + the
> `Enrichable` marker, the list-path cache write-through, the starter wiring/kill-switch, catalog +
> user-mgmt adoption, the `bulk`-primitive extension, docs.  · **Branch**:
> feature/void3110/action-enrichment vs main (3,568 lines, 7 tickets, multi-module + rego + e2e).

## Summary

Multi-lens adversarial review (8 failure-mode lenses → refutation → completeness critic; 20 agents).
**Zero fail-open / widening / security / concurrency / core-boundary / rego-decision defects** — the
load-bearing invariants all held. The 10 confirmed findings (0 refuted) were entirely **documentation
accuracy** and **test/coverage honesty**: one Critical (an operationally-misleading runbook line) and
nine Mediums/Lows. All fixed on the branch; build + `opa test` green.

The review earned its keep as a **self-preferential-bias check**: the implementing author corrected the
"zero Rego change" claim in ADR 0016 §6 during the run but **left the same falsehood in 5 other places**,
and STATUS-06 claimed the e2e "proved everything" while the populated-team path and the E4 cell were gaps.

## Critical Issues

1. **The runbook told operators "NO OPA restart needed — zero Rego change", but the slice ADDS the `bulk`
   entrypoint to `catalog`/`product`/`team` rego** (`run-action-enrichment-matrix.sh:22`). On `main` only
   `category.rego` had `bulk`; the enrichment `allowAll` calls `/v1/data/<type>/bulk`. OPA loads
   `/policies` once with no `--watch`, so an operator following the documented "no restart" would have OPA
   serve an undefined `bulk` document → `allowAll` reads no `result` → all-false → the advice omits
   `_actions` → **E6 fails** (and catalog/product enrichment silently degrades in any real deployment that
   didn't restart). **Fixed**: the runner now **restarts OPA itself** before minting tokens (mirroring
   `run-resource-resolution-matrix.sh`), and the false claim was corrected in all 5 sites (the script
   header, `scripts/postman/README.md`, `docs/guides/E2E-TESTING.md` ×2, the slice's `10-QA-TEST-CASES.md`,
   plus the design-statement echoes in `00-DESIGN.md` / `01-DECOMPOSITION.md` / the autonomous prompt, each
   now pointing at ADR 0016 §6).

## Medium Issues

2–4. **The same stale "zero Rego / no OPA restart" claim** repeated in `scripts/postman/README.md:40`,
`docs/guides/E2E-TESTING.md` (lines 128 + 269), and the slice's `10-QA-TEST-CASES.md` (the root of the
falsehood). **Fixed** — all corrected to "OPA must reload; the runner restarts it" with the additive-`bulk`
rationale.

5–7. **The populated team `_actions` path had no coverage.** `getTeam`/`listTeams` are ungated, so no gate
ever caches a `Team` → the live map is always the (correct, tested) absent-degrade; but `TeamEnrichable` +
both `team.rego bulk` rules had **no positive-path test**, and the gateway collection had **no `/teams`
request at all** (the `team_id` newman env-var was unconsumed; `/teams` isn't gateway-routed). **Fixed**:
added `TeamEnrichmentAdviceTest` (user-mgmt) — drives the real `ActionEnrichmentAdvice` with a pre-populated
cache + the real generated `Team` DTO, proving the populated subset-only map (`list-members:true,
add-member:false, remove-member:false` for a member) and that the escalation verbs never appear; the runner
drops the dead `team_id`/`opa_container`/`runtime` newman env-vars and documents that the team cells live
in the user-mgmt module tests.

8. **The QA-cases planning doc itself asserted the false claim** (the root). **Fixed** with a dated
correction note.

## Low Issues

9. **The README rows described the *old* tag-gated matrix design** (an `apac update:false` per-row contrast)
that the shipped grant-based collection neither seeds nor asserts. **Fixed** — README rows 40 + 63 rewritten
to the actual grant-based cells; the E2E-TESTING paragraph + the collection's own description were already
accurate.

10. **QA I1's single-GET no-second-SELECT clause was untested** (only the list path asserted query count).
**Fixed** — the catalog `ActionEnrichmentIT` now spies `CategoryRepository` and asserts a `getCategory`
loads the row exactly once (the gate's resolver), the advice reusing the cached snapshot.

4 (cont.). **The E4 e2e "omit-on-failure, live" cell asserted vacuously** — with OPA paused the *gate*
also 403s, so the cell never exercised a gate-allowed read whose enrichment bulk fails; it only checked
"no 5xx / no fabricated all-false map". **Fixed** — relabeled honestly as an *enrichment-never-harms smoke
check* and points at the catalog IT / unit case U6 (a stubbed bulk that throws/returns all-false → `_actions`
unset) as the real gate-allowed omit-on-failure proof, where gate and bulk are independently controllable.

## Fail-closed verification

**Clean — no widening on any error/empty path** (the lens found nothing, confirmed by hand on the Criticals):
- `ActionEnrichmentAdvice.beforeBodyWrite` — `setActions` has exactly **one** call site, inside the
  `if (anyTrue)` guard; every failure class (no subject, cache miss, ancestor/role failure, `allowAll`
  throw, short list, **all-false block**) omits `_actions`. No branch synthesizes a verdict.
- `@JsonInclude(NON_EMPTY)` on `Enrichable.getActions()` keeps an unset/empty map off the wire (absent ≠ `{}`).
- The list-path `cacheSurvivors` writes only post-filter survivors (denied rows never cached); the
  decisions/return values are byte-identical; the deny/`fromError`/`Page.empty` sites are unwrapped.
- The added `bulk` rules are `allow` mapped over `input.items` — **no new decision**; `opa test` green and
  the existing decision tests unmodified; the two `team.rego` copies byte-identical.

## Security audit

No widening surface introduced. The cache is read for **attributes only**, never as a verdict (presence ≠
authorized; every verdict fresh from `bulk` — proven by U9). The team set enumerates only fully-OPA-decided
verbs (the Java-co-gated `change-role`/`define-roles`/`transfer-ownership` excluded — `TeamEnrichmentAdviceTest`
asserts their absence). No SpEL/SQL/JSONB/ltree built from user input; the advice never alters status or
body beyond setting `_actions`. `opa-abac-core` stays Spring-free after the `AbacResourceCache` relocation.

## Concurrency & idempotency

Clean (CONCURRENCY-AND-LOCKING Rules 1/2). The advice reads the attribute snapshot taken at gate/query time
and never re-resolves — no drift between the rows shown and the verdicts; enrichment is a pure read +
per-DTO mutation, naturally idempotent. The list write-through writes the same instance the query returned
and never changes which rows are returned. No new locked path.

## Wiring & sibling sweep

- **Wiring:** every new seam has a consumer + a non-happy-path test — the advice (omit branches U6–U8), the
  kill-switch (off-state U12), the `@ConditionalOnBean(resolver)`+`AllNestedConditions(2 properties)` gate
  (the resolver-present-resolution-off case `actionEnrichmentAdviceAbsent_whenResolutionDisabled`), the
  write-through collaborator (wired-iff-enabled probe), each `<Type>Enrichable`, the `bulk` rules (opa-test).
- **Sibling sweep (the Critical):** the stale "no OPA restart" claim was swept across **all** its mirrors
  — 5 operational sites + 4 design-statement echoes — and corrected in the same review commit. The
  `bulk`-rule addition was already swept across catalog/product/team + both team.rego bundles in T6.

## Autonomous-run check

- **Agentic laziness** — found: the E4 cell asserted shape-not-cut (vacuous under a paused gate); the
  single-GET no-second-SELECT clause was claimed (STATUS) but untested. Both now have real assertions.
- **Self-preferential bias** — found: ADR §6 was corrected mid-run but the same falsehood was left in 5
  sibling docs; STATUS-06 over-claimed the e2e coverage (populated team path, E4). Corrected.
- **Goal drift** — none: fail-closed, the core Spring-free boundary, additivity, and AND-not-replace all
  held end to end across the 7 tickets.

## What's done right

The three load-bearing invariants (omit-never-fabricate, affordance-honesty, cache-as-snapshot) are
implemented exactly and well-tested at the unit/IT level; the `setActions` single-call-site discipline is
clean; the `@JsonInclude(NON_EMPTY)` wire-omit is the right library-level fix; the `bulk` extension is
genuinely additive (decision-preserving, byte-identical existing matrices); the codegen-fit (`x-implements`
→ `getActions`/`setActions`) is solved without generator config. The two real bugs the slice itself caught
(missing `bulk`, empty-`{}`) were caught by the **mandatory live e2e** — exactly why the flow mandates it.

## Test results

- `./gradlew build`: **green** (all modules + both examples + OpenAPI codegen + real-Postgres ITs, incl.
  the new `singleGetEnrichesFromCacheWithoutSecondSelect` + `TeamEnrichmentAdviceTest`).
- `opa test`: **183/183** (infra) + **32/32** (user-mgmt bundle) — unchanged (the review touched no rego).
- newman: not re-run — the review changed only docs + tests + the runner's OPA-restart step (no runtime
  code); the T6 live run (action-enrichment 14/14 + every existing matrix green) stands. The runner's new
  self-restart is a strict correctness improvement (verified by syntax + logic).

## Commits

- `<this commit>` `fix(action-enrichment): correct the stale no-OPA-restart runbook + cover the populated
  team map + tighten test/doc honesty (deep-review)`
