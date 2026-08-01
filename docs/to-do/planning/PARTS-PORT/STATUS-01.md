---
tags:
  - status/done
  - type/project
  - area/methodology
---

# STATUS — T1: `check-parts.py` + the `verify-package.sh` [9] hard-fail gate + self-locating scripts

**Status:** ✅ DONE

## What shipped

- `scripts/planning/check-parts.py` — the **single** validation authority for the `**Parts:**`
  grammar. Fence-skipping (char+length-matched closers); near-miss hard-fail anchored to the start
  of a line's content with inline code spans stripped first; exactly-one-live (two live lines name
  both line numbers); 0-based contiguous ascending part numbers; contiguous, ascending,
  non-overlapping ranges covering exactly `T1..TN`; ≥2 parts; the dash class `[–—-]` matching
  `check-citations.py`'s CITE class; N counted with `verify-package.sh` [6]'s exact regex
  (`^#{2,4} T[0-9]+`) so the two gates cannot disagree; bounds checked arithmetically — ranges are
  **never materialized**; ticket-numbering contiguity checked before coverage (U14); the
  heading-without-declaration defect (U13); with no declaration the decomposition is never opened,
  with one an unreadable decomposition fails closed (U15). Exit contract: 0 valid/absent · 1
  problems · 2 usage.
- `scripts/planning/verify-package.sh` — **[9] execution parts**, delegating to `check-parts.py`
  and distinguishing exit codes (0 → ✓ echoing the checker's summary incl. the single-session
  absent message; 1 → ✗ with the problems; anything else → ✗ "could not run — failing closed").
  **Self-locating header**: the old line-18 `cd "$(git rev-parse --show-toplevel)"` (the measured
  umbrella-cwd trap) replaced with resolution from `dirname "$0"`; the existing explicit-path form
  preserved — a relative path now resolves against the invocation cwd, a path inside the repo is
  normalized repo-relative (keeping output bytes identical), absolute out-of-tree paths pass
  through.
- **D1 deltas**: the flow guide's §3 mechanical-gate enumeration and §9 gate-table row both gain
  [9]; `.claude/skills/decompose/SKILL.md` (untracked — edited in place) §6a gains the [9] line and
  §6b gains the `**Validated:** <date> — mechanical + adversarial clean` index-line convention the
  phase-③ runner's Phase 0 will read.

## Tests

Throwaway bash driver (scratch `mktemp -d`, exact exit codes): **29/29 PASS** — U1, U2, U3 (three
near-miss forms + the backticked/mid-line counter-fixture), U4 (both arms), U5–U10, U11 (en/em/
hyphen), U12–U15 (both U15 arms), usage→2, U8 timing (<2 s), U17 (umbrella-cwd output byte-identical
to repo-cwd, rc 0), U18 (gate-green fixture → 0 with `[9]` ✓ single-session; malformed twin → 1 with
`[9]` ✗), U19 (absolute out-of-tree path from any cwd).

## Architecture review + refactor

Review found three substantive items, all fixed and re-drilled:

1. **Fail-closed:** a binary/mis-encoded design file raised `UnicodeDecodeError` (a `ValueError`,
   not `OSError`) — exit code was still 1 via traceback, but now both reads catch it and print the
   clean fails-closed message (drilled with `/dev/urandom` bytes).
2. **Near-miss hole:** a declaration written with a fullwidth colon (`**Parts：**`) matched neither
   the live form nor the near-miss net → would have read as *absent* (the one forbidden outcome).
   The near-miss colon class widened to `[:：]`; drilled → exit 1 near-miss.
3. **Unexercised seam:** the [9] `*)` arm (checker itself cannot run) had no case through it —
   drilled live with `python3` off PATH: exit 127 → ✗ "failing closed", package exit 1, never
   "absent".

Also verified: [9] mirrors [7]'s delegation shape (capture, ok/bad, sed-indent) plus the case-arm
exit discrimination the pinned semantics require; validation lives only in `check-parts.py` (the
gate never re-parses); scripts are read-only and idempotent. Static-analysis gate: **N/A by
construction** — no `.java` touched, the local Sonar stack stays down.

## Integration / e2e

- **U16 calibration:** all **25** packages carrying a `00-DESIGN.md` (23 under
  `docs/to-do/implemented/` + PARTS-PORT + SUPERVISED-SCOPE) → **25/25 clean**: 24 report "no parts
  declaration — single-session (the default)"; PARTS-PORT's own live line parses as
  `2 parts covering 5 of 5 tickets` with its fenced example and inline-backticked near-miss
  mentions correctly ignored. (POC-ROADMAP and QUALITY-GATE-SONAR-BASELINE have no `00-DESIGN.md` —
  not decomposition packages, out of [9]'s scope as they already fail [1].)
- **Additive proof:** old (HEAD) vs new `verify-package.sh` run on all 25 packages — **0 exit-code
  diffs, 0 byte diffs** on the [1]–[8] output (the new [9] block stripped for comparison).

## Decisions

- **The near-miss net is deliberately wider than the three named forms**: any-case `parts`, bold or
  unbolded, ASCII or fullwidth colon, any list/quote prefix ahead of a Parts-shaped opener — all
  hard-fail. Fail-closed direction (a false near-miss fails loudly; a missed one hides a
  partition); calibration confirms zero false fires on the real corpus.
- **Seam verification (no deviations):** [6]'s regex confirmed at line 91; the line-18 cwd trap
  confirmed verbatim; the explicit-path case arm confirmed at lines 21–24 (preserved, not added);
  `check-citations.py`'s dash class `[–—-]` confirmed at line 27. Reality matched the ticket on
  every named seam.
- **Unclosed-fence edge accepted:** a declaration below a never-closed fence reads as absent.
  Absence engages the OFF state, which delegates nothing and drops no tickets — not a widening —
  and the broken fence is visible in any renderer.
- **Path normalization:** an explicit path resolving inside the repo is rewritten repo-relative so
  [1]–[8] output stays byte-identical; out-of-tree paths stay absolute (what T5's fixture needs).

## Commit

`feat(parts-port): T1 — check-parts gate [9] + self-locating verify-package` (this branch).
Untracked working-tree deliverable alongside it: `.claude/skills/decompose/SKILL.md` (§6a [9] line;
§6b `**Validated:**` convention) — no diff carries it; reviewed by reading.
