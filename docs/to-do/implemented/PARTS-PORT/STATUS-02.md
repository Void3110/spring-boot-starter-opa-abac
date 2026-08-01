---
tags:
  - status/done
  - type/project
  - area/methodology
---

# STATUS — T2: `scaffold-package.py` `--parts` and `--planning-root`

**Status:** ✅ DONE

## What shipped

- `--parts "<declaration>"` — appends a `## 5. Execution parts` section carrying
  `**Parts:** <declaration>` **verbatim** to the `00-DESIGN.md` stub. Requires `--with-design`
  (the stub it lands in) — without it, a hard error rather than silently dropping the author's
  declaration. **Validates nothing**, by decided fork 4: garbage is written verbatim; the gate
  catches it.
- `--planning-root <dir>` — the package skeleton lands under `<dir>/<SLICE>/` instead of
  `docs/to-do/planning/<SLICE>/`; nothing else changes.
- **Self-location**: `repo_root()` re-derived from `os.path.abspath(__file__)` (two levels up),
  replacing the cwd-derived `git rev-parse` subprocess that fatals from the umbrella workspace
  root; the now-unused `subprocess` import removed.

## Tests

Throwaway bash driver, exact exit codes: **9/9 PASS** — U20 (verbatim section + line), U20v
(garbage accepted verbatim — validates nothing), U20e (`--parts` without `--with-design` errors),
U21 (the single-authority proof: scaffold exits 0 on a coverage-gap partition, `check-parts.py`
exits 1 naming T3), U22 (skeleton lands under the external dir; repo planning tree byte-unchanged),
U23 (golden diff: old HEAD scaffold vs new, no new flags, `diff -r` empty), U24 (invoked from the
umbrella root — self-locating `repo_root()` works), plus an idempotency drill (re-run skips
existing files, checksums identical, exactly one Execution-parts section).

## Architecture review + refactor

Nothing substantive to refactor post-hoc. The one load-bearing choice was made at build time and
drilled: `--parts` extends the **in-memory** stub content rather than appending to a file on disk,
which is what keeps re-runs convergent (no duplicate sections) and preserves the existing
skip-unless-`--force` semantics. Reviewed lenses: fail-closed (`--parts` without `--with-design`
errors loudly; a silently-vanishing declaration was the risk), fixture-leak widening (U22 proves
`--planning-root` writes nothing into the repo tree), module separation (the scaffold checks
nothing — U20v/U21 prove the division of authority), pattern reuse (argparse house style, existing
`write()`/`sys.exit("error: …")` idioms). A left-behind scaffolded heading whose declaration is
later hand-deleted trips `check-parts.py`'s heading-without-declaration defect (U13) — the seams
compose fail-closed. Static-analysis gate: **N/A by construction** — no `.java` touched.

## Integration / e2e

U21 (single-authority) and U23 (golden diff) are this ticket's mandated integration proofs — both
in the driver run above. The U23 golden run reconstructs the pre-change script from git
(`git show HEAD:…`) in a scratch git repo so its cwd-derived resolver still works, and diffs the
two generated trees byte-wise.

## Decisions

- The appended section is numbered `## 5. Execution parts` (the design stub owns `## 1`–`## 4`),
  matching where PARTS-PORT's own hand-written declaration lives (§6 there; the stub's numbering
  differs — the gate keys on the `**Parts:**` line, not the heading number).
- `--planning-root` composes with every existing flag; the default path expression is unchanged
  (`repo_root()/docs/to-do/planning`).
- Seam verification (no deviations): `repo_root()` was confirmed to shell out to
  `git rev-parse --show-toplevel` exactly as the ticket claimed, before replacing it.

## Commit

`feat(parts-port): T2 — scaffold --parts (writes blind) + --planning-root + self-locating root`
(this branch). No untracked deliverables in this ticket.
