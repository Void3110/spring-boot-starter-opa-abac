#!/usr/bin/env python3
"""The guarded-substitution rule for this repo's shell scripts.

THE BUG THIS POLICES
--------------------
Under ``set -e`` (every runner here uses ``set -euo pipefail``), a bare
assignment from a command substitution ABORTS THE SCRIPT the moment the
substituted pipeline exits non-zero::

    TOKEN="$(mint_token "$USER" "$PASS")"          # <-- set -e fires HERE
    [ -n "$TOKEN" ] || { echo "ERROR: ..." >&2; exit 1; }   # never reached

The operator gets a bare exit status instead of the diagnosis the author
plainly meant to give them. The fix is to let the assignment survive so its
own guard can speak::

    TOKEN="$(mint_token "$USER" "$PASS")" || true

``shellcheck`` does not flag this, with every optional check enabled (measured
2026-08-18, and again against the two defects that motivated this gate). The
invariant is repo-specific, which is why it lives here.

WHAT COUNTS AS A VIOLATION
--------------------------
All five must hold. Each exclusion below exists because a real site in this
repo would otherwise be misreported in one direction or the other:

1. The file runs in strict mode (``set -e`` / ``set -euo pipefail``).

2. The assignment is BARE. ``local X="$(cmd)"``, and likewise ``declare`` /
   ``export`` / ``readonly``, do NOT trip ``set -e``: the exit status of the
   compound is the KEYWORD's, not the substitution's — a genuine bash wart, and
   the reason those are silently fine. Flagging them would be a false positive.

3. The substituted pipeline CAN fail. ``printf '%s' "$JSON" | sed -n 's/…/p'``
   cannot: printf is fed from a variable already in hand, and sed on stdin
   exits 0 even when nothing matches. This distinction is the whole difference
   between a gate people keep and a gate people delete — 7 of 48 candidate
   sites in this repo are pure filter chains and must stay quiet.

   NOTE the trap this rule was built around: classification is over the WHOLE
   pipeline, never the first command. ``printf … | python3 -c …`` and
   ``printf … | base64 -d`` both START with printf and both CAN fail — python
   raises on malformed JSON, base64 on bad padding — and under ``pipefail``
   that failure is the pipeline's. Keying on the first stage misses four real
   sites here. ``grep`` is fallible for the same reason people forget: it
   exits 1 on NO MATCH.

4. The variable is GUARDED below — ``[ -n "$VAR" ]``, ``[ -z "$VAR" ]``,
   ``[ "$VAR" != … ]`` or ``require_token … "$VAR"`` — within a short window.
   The guard is the evidence the author intended a friendly failure. An
   unguarded substitution is out of scope: nothing was promised, so nothing is
   broken. That boundary is deliberate — extending the rule to EVERY fallible
   substitution would flag hundreds of sites where dying immediately is the
   correct behaviour, and the gate would be deleted within a week.

5. The line does not already carry ``|| true``.

WAIVER
------
``# shell-guards: ignore -- <reason>`` on the line ABOVE silences one site. A
reason is mandatory; a bare ignore is itself an error, so a waiver cannot be
added thoughtlessly.

USAGE
-----
    scripts/checks/check-shell-guards.py [paths...]            # default: scripts/
    scripts/checks/check-shell-guards.py --fix [paths...]      # append `|| true` in place

``--fix`` reuses the same scanner that found the site, so the fixer and the
check can never disagree about where a substitution ends.

Exit 0 clean, 1 on violations, 2 on a malformed waiver.
"""

from __future__ import annotations

import bisect
import re
import sys
from pathlib import Path

# How far below an assignment a guard may sit and still count as "its" guard.
# 24 lines covers every real pairing in this repo (measured max: 9) while
# staying far short of the next unrelated block.
GUARD_WINDOW = 24

# Commands that cannot fail when fed from a variable already in hand. Kept
# deliberately tiny: every addition is a potential false NEGATIVE, and a missed
# abort is worse than a reported line. `grep`, `base64`, `python3`, `jq` and
# `awk` are NOT here, on purpose (see the module docstring).
PURE_FILTERS = {"sed", "tr", "cut", "head", "tail", "wc", "rev", "cat", "sort", "uniq"}

# Pipeline heads that supply data rather than fetch it.
DATA_SOURCES = {"printf", "echo"}

ASSIGN = re.compile(
    r"""^[ \t]*
        (?:(?P<kw>local|declare|export|readonly)[ \t]+)?
        (?P<var>[A-Za-z_]\w*)=
        "?\$\(
    """,
    re.VERBOSE | re.MULTILINE,
)
WAIVER = re.compile(r"#\s*shell-guards:\s*ignore\b(?P<rest>.*)$")


def substitution_span(text: str, dollar_paren: int) -> int | None:
    """Return the offset just past the `)` closing the `$(` at *dollar_paren*.

    Quote-aware on purpose. The naive approach — counting `(` against `)` across
    the line — corrupts real code here: run-load.sh embeds multi-line SQL whose
    own parentheses are unbalanced per line, and a counter walks straight past
    the true end of the substitution. Anything appended at that wrong offset
    lands INSIDE a string literal, where `bash -n` still parses clean.
    """
    i, depth = dollar_paren + 2, 1
    quote = None
    while i < len(text):
        c = text[i]
        if quote:
            if c == "\\" and quote == '"':
                i += 2
                continue
            if c == quote:
                quote = None
        elif c in "'\"":
            quote = c
        elif c == "\\":
            i += 2
            continue
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return None


def absorbs_failure(inner: str) -> bool:
    """True when the substitution swallows its own failure with `|| <fallback>`.

    `"$(docker exec … 2>/dev/null || echo '<unset>')"` exits 0 whatever docker
    does, so `set -e` never fires and there is nothing to report. Missing this
    produced 11 false positives on the first pass.
    """
    i, depth, quote = 0, 0, None
    while i < len(inner) - 1:
        c = inner[i]
        if quote:
            if c == "\\" and quote == '"':
                i += 2
                continue
            if c == quote:
                quote = None
        elif c in "'\"":
            quote = c
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        elif c == "|" and inner[i + 1] == "|" and depth == 0:
            return True
        i += 1
    return False


def pipeline_can_fail(inner: str) -> bool:
    """True unless every stage of the substituted pipeline is provably safe."""
    if absorbs_failure(inner):
        return False

    stages = [x.strip() for x in re.split(r"\|(?!\|)", inner) if x.strip()]
    if not stages:
        return True

    for pos, stage in enumerate(stages):
        stage = re.sub(r"\d?>&?\S+", " ", stage).strip()   # drop redirections
        words = stage.split()
        if not words:
            return True
        cmd = words[0].strip("\"'()").rsplit("/", 1)[-1]
        if pos == 0:
            if cmd in DATA_SOURCES:
                continue
            if cmd in PURE_FILTERS and not _reads_a_file(words):
                continue
            return True
        if cmd not in PURE_FILTERS:
            return True
    return False


def _reads_a_file(words: list[str]) -> bool:
    """A pure filter given a FILE operand can fail (missing file)."""
    return any(not w.startswith("-") for w in words[1:])


def guarded_below(var: str, lines: list[str], idx: int) -> bool:
    window = "\n".join(lines[idx : idx + GUARD_WINDOW])
    v = re.escape(var)
    patterns = (
        rf'\[\s+-[nz]\s+"?\$\{{?{v}\}}?"?\s+\]',
        rf'\[\s+"?\$\{{?{v}\}}?"?\s+!?=',
        rf'require_token[^\n]*\$\{{?{v}\b',
    )
    return any(re.search(p, window) for p in patterns)


def check_file(path: Path, fix: bool = False) -> tuple[list[str], list[str]]:
    text = path.read_text()
    if not re.search(r"^\s*set\s+-\S*e", text, re.M):
        return [], []

    lines = text.splitlines()
    starts, off = [], 0
    for ln in lines:
        starts.append(off)
        off += len(ln) + 1

    def lineno_of(offset: int) -> int:
        return bisect.bisect_right(starts, offset)

    violations: list[str] = []
    errors: list[str] = []
    fixes: list[int] = []            # offsets to append `|| true` at

    for m in ASSIGN.finditer(text):
        dollar_paren = m.end() - 2                 # the regex ends on `$(`
        span_end = substitution_span(text, dollar_paren)
        if span_end is None:
            errors.append(f"{path}:{lineno_of(m.start())}: unterminated command substitution")
            continue

        start_line = lineno_of(m.start())
        end_line = lineno_of(span_end - 1)
        inner = text[dollar_paren + 2 : span_end - 1]
        tail = text[span_end : starts[end_line - 1] + len(lines[end_line - 1])]

        prev = lines[start_line - 2] if start_line >= 2 else ""
        w = WAIVER.search(prev)
        if w:
            if not w.group("rest").strip().lstrip("-").strip():
                errors.append(
                    f"{path}:{start_line - 1}: waiver without a reason "
                    f"(use `# shell-guards: ignore -- <why>`)"
                )
            continue

        if m.group("kw"):
            continue                          # local/declare/export mask the status
        if "|| true" in tail:
            continue
        var = m.group("var")
        if not guarded_below(var, lines, end_line):
            continue                          # nothing promised, nothing broken
        if not pipeline_can_fail(inner):
            continue                          # cannot abort: pure chain, or self-absorbing

        violations.append(
            f"{path}:{start_line}: {var} is guarded below but its substitution "
            f"can fail — set -e aborts before the guard. Append `|| true`."
        )
        # The insert point is the END of the substitution's own logical line —
        # computed by the SAME scanner that found the violation, so a fixer can
        # never disagree with the check about where a site ends.
        fixes.append(starts[end_line - 1] + len(lines[end_line - 1]))

    if fix and fixes:
        out = text
        for off in sorted(fixes, reverse=True):
            out = out[:off] + " || true" + out[off:]
        path.write_text(out)

    return violations, errors


def main(argv: list[str]) -> int:
    args = [a for a in argv[1:] if a != "--fix"]
    fix = "--fix" in argv[1:]
    roots = [Path(a) for a in args] or [Path("scripts")]
    files: list[Path] = []
    for r in roots:
        files.extend(sorted(r.rglob("*.sh")) if r.is_dir() else [r])

    violations: list[str] = []
    errors: list[str] = []
    for f in files:
        v, e = check_file(f, fix=fix)
        violations.extend(v)
        errors.extend(e)

    for e in errors:
        print(f"ERROR {e}", file=sys.stderr)
    for v in violations:
        print(v, file=sys.stderr)

    if errors:
        return 2
    if violations:
        if fix:
            print(f"\nfixed {len(violations)} site(s) — re-run to confirm clean.")
            return 0
        print(
            f"\n{len(violations)} guarded substitution(s) will abort before their "
            f"own guard runs. Re-run with --fix to append `|| true` to each.",
            file=sys.stderr,
        )
        return 1
    print(f"shell-guards: {len(files)} script(s) clean")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
