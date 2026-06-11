---
tags:
  - status/done
  - type/project
  - area/api
  - area/spring
  - area/abac
---

# STATUS — T6: Docs (§7 adopted) + PARTIAL-EVALUATION-FILTERING + roadmap + Mulch + folder move

**Status:** ✅ DONE (2026-06-11)

## What shipped

- **`docs/guides/REST-API-DESIGN.md`** — §7 rewritten from "today there is none" to the adopted rule:
  the envelope JSON, the `count`-is-subject-relative semantics, the per-spec `allOf` model, the strict
  0-based params table (defaults/bounds/strict-400/past-the-end), and the determinism-by-construction
  ordering subsection (fixed `createdAt ASC, id ASC`, the unsorted-`Pageable` guard, the e2e pointer);
  §4's "bare JSON array" rule replaced by the envelope rule; §8 gained the one-line *unpaginated by
  design* bullet; §9's pagination row moved into the body with an "Adopted in Phase 5.95" note (the 5.9
  model) and a new deferred **client `?sort=`** target row; the header's "no pagination" demo-scope
  mention removed.
- **`docs/guides/PARTIAL-EVALUATION-FILTERING.md`** — a new **"The paged composition (Phase 5.95)"**
  section: the four-path table (paged behavior + count per path), the unsorted-`Pageable` guard, the
  fail-closed-paged summary, the proof pointers; ADR 0012 added to Related.
- **`docs/guides/E2E-TESTING.md`** — the pagination matrix section (fixture set, E1–E3 table, the
  suite-wide envelope note) + the runner added to "Running it".
- **`POC-ROADMAP.md`** — Phase 5.95 flipped to ✅ DONE (full shipped summary in the Notes column); the
  Related line updated (next: Phase 6 lands on this envelope).
- **`USER-STORIES.md`** — D5 flipped to ✅.
- **Mulch** — three pattern records + the retrospective, synced (`.mulch`-only commits), `ml doctor`
  clean (the 19 broken-anchor warnings are pre-existing, from older planning→implemented moves):
  - `opa-abac`: the paged-seam pattern (guard + four paths + `PageImpl` over the filtered fallback);
  - `api-design`: the per-spec `PageEnvelope`/`allOf`/shared-params codegen model (incl. the
    `ConstraintViolationException` advice finding);
  - `opa-abac`: the suite-wide wire-shape-break pattern (scout → extraction-only migration → pinned
    counts → dedicated fixtures) + (recorded in T5) the ltree-path seed failure record;
  - `autonomous-runs`: **"Run: PAGINATION-ENVELOPE (6 tickets)" — full-success**, zero pauses; the two
    fix-until-green frictions + the planning-gap→fix list (pre-pin the param-validation exception type;
    ground QA conventions in named exemplar files; runner checklists must seed ltree paths).
- **Folder move** — `docs/to-do/planning/PAGINATION-ENVELOPE/` → `docs/to-do/implemented/` (`git mv`);
  the index flipped to `status/done` with the past-tense **Shipped** banner; all six tickets ticked.

## Tests

Docs-only ticket — the cross-cutting proof was re-run at the T4/T5 boundaries: `./gradlew build` green
end to end; all 8 newman runners green (128 assertions). Clean-room scan on the changed files: clean.

## Architecture review + refactor

Docs review only: §7/§9 now state the adopted rule without the old "target" framing; the deferred
client-sort target moved to §9 so the guide stays honest about the remaining gap; no code changed.

## Integration / e2e

N/A (see T5).

## Decisions

- The §9 targets table gained the **client `?sort=`** row (it was previously only an aside inside the
  pagination target) so the deferral stays visible after the pagination row's retirement.
- The `ml doctor` broken-anchor warnings (19, pre-existing — records pointing at old `planning/` paths
  of shipped slices) were left as-is: a mulch-maintenance/doc-audit item, not this slice's scope. This
  move adds the same class of anchor drift for PAGINATION-ENVELOPE records, same remediation bucket.

## Commit

`docs(pagination): guides adopt the envelope (§7), paged composition documented, roadmap 5.95 done, folder moved`
