---
tags:
  - status/done
  - type/project
  - area/abac
  - area/spring
  - area/opa
---

# STATUS — T7: Docs + slice record: guide, reconciliations, roadmap/stories/index, Mulch, folder move

**Status:** ✅ DONE

## What shipped

- **New mechanism guide `docs/guides/ACTION-ENRICHMENT.md`** — the advice + `Enrichable` marker + the
  `x-implements`/`_actions` codegen recipe (one sub-interface + two schema lines), the cache feed (single +
  list write-through), the **three pinned semantics** (omit-on-failure, affordance-honesty,
  snapshot-not-verdict), the verified verb sets (the catalog/product `assign-tags` exclusion), the
  `bulk`-extended-to-every-type note, the kill-switch wiring, and the caveats (affordance ≠ enforcement,
  the ungated-`getTeam` degrade, `Membership` unenriched).
- **Reconciliations** (short anchored cross-references, not rewrites):
  - [[ABAC-AUTHORIZATION]] — affordance is a **read-side** layer, distinct from ADR 0006's three
    enforcement layers (not a fourth); + a Related link. ADR 0006 body untouched.
  - [[PARTIAL-EVALUATION-FILTERING]] — the list-path cache write-through (the Phase-6 feed) in the adoption
    recipe; + a Related link.
  - [[REST-API-DESIGN]] — the `_actions` field added to §4; the now-shipped row **removed** from the §9
    Targets table and recorded in the "Adopted in Phase 6" note.
  - [[E2E-TESTING]] — the `run-action-enrichment-matrix.sh` line + a matrix-description paragraph + a
    Related link.
  - `infra/README.md` — the action-enrichment matrix paragraph + the `bulk`-extension restart note.
- **Record:** [[USER-STORIES]] Epic E (E1/E2/E3) flipped ✅ shipped; [[POC-ROADMAP]] Phase 6 table row →
  ✅ DONE, the route-status block updated (B2/6.7/6 all shipped; **next is B3**), the route-list rewritten
  past-tense with B3 marked NOW NEXT.
- **Slice record:** the [[ACTION-ENRICHMENT]] index flipped to `status/done` + the ✅ SHIPPED banner
  (planning banner preserved); the ticket table ticked T1–T7.
- **Mulch:** the durable per-ticket insights were recorded as they arose (the `allowAll`-all-false→omit
  failure mode [T2], the `@ConditionalOnBean` sibling-auto-config ordering hazard + resolver-vs-cache
  gating [T4/T5], the missing-`bulk` + empty-map-serialization e2e-only-visible failures [T6]); this ticket
  adds the run retrospective to the **`autonomous-runs`** domain.
- **Folder move:** `git mv docs/to-do/planning/ACTION-ENRICHMENT → docs/to-do/implemented/ACTION-ENRICHMENT`.

## Tests

Docs-only ticket. The full `./gradlew build` + `opa test` (183/183 infra, 32/32 user-mgmt bundle) were
green at the T6 close (the last code ticket); this ticket changes only Markdown + the folder location.
Frontmatter valid on every touched note; wikilinks resolve.

## Architecture review + refactor

★ gate — **nothing to refactor** (docs only). Verified the guide reflects the *shipped* code including the
T6 corrections (the `bulk` extension + the `@JsonInclude(NON_EMPTY)` wire-omit are documented, not
glossed); ADR 0006 body untouched; the REST-API target row moved (shipped), not left stale; the roadmap
route reads B3-next, matching the decomposition. Clean-room: original neutral names only.

## Integration / e2e

N/A (docs). The slice's live e2e is recorded in STATUS-06.

## Decisions

- **ADR 0016 §6 corrected via a dated note, not rewritten** — preserves the accepted decision's history
  while making the "zero Rego change" claim accurate (the missing-`bulk` gap, T6).
- **The REST-API `actions`/`pageActions` target row removed** (shipped) and folded into §4 — per the
  guide's own "when a target is adopted, move it into the body" rule.

## Commit

`docs(action-enrichment): guide + reconciliations + roadmap/stories + ship the slice`
