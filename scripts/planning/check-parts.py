#!/usr/bin/env python3
"""Execution-parts declaration integrity for a slice decomposition package.

A package may declare, on ONE line of its 00-DESIGN.md, how an autonomous run
partitions the ticket list into sequential, subagent-delegated parts:

    **Parts:** part 0 = T1–T2 · part 1 = T3–T5

This script is the SINGLE validation authority for that grammar (the scaffold
writes it blind; verify-package.sh [9] and the runner skill both delegate here
and never re-parse). Three things are checked:

  presence  — absence is the green default (single-session, exit 0). A NEAR-MISS
              at the start of a line's content — a bulleted `- **Parts:** …`, an
              unbolded `Parts: …`, a colon outside the bold `**Parts**: …` — is
              an ERROR, never "absent": absence widens nothing, so a near-miss
              falling through would hide a partition that silently drops
              tickets. Fenced blocks are skipped and inline-backticked mentions
              are excluded (a doc may document the grammar without tripping the
              gate); two live declarations are rejected rather than picking one.
  shape     — 0-based, contiguous, ascending part numbers; contiguous,
              ascending, non-overlapping ranges; two or more parts (one part is
              what absence already means). En dash, em dash and hyphen all
              parse (the same dash class check-citations.py uses).
  coverage  — the ranges cover exactly T1..TN, where N is counted with the same
              heading regex verify-package.sh [6] uses, so the two gates can
              never disagree on N. A ticket in no part would silently never run.

Bounds are checked arithmetically and ranges are NEVER materialized, so an
absurd endpoint (T900000000) fails fast instead of expanding first.

With no declaration the decomposition file is never opened (a package that
merely documents the grammar stays clean). With one, an unreadable
decomposition fails closed — a declaration that cannot be validated is a
problem, not a pass.

Usage:  check-parts.py <00-DESIGN.md> <01-DECOMPOSITION.md>
Exit:   0 = valid declaration or none · 1 = problems (printed) · 2 = usage.
Read-only.
"""
import re
import sys

# The same dash class check-citations.py's CITE uses, and the same ticket-heading
# regex verify-package.sh [6] counts with — shared shape per seam, one source each.
DASH = r"[–—-]"
TICKET_HEADING = re.compile(r"^#{2,4} T([0-9]+)")

LIVE = re.compile(r"^\*\*Parts:\*\*\s*(.*)$")
# Anything else opening a line's content with a Parts-declaration shape (any case,
# bold or not, colon anywhere, ASCII or fullwidth) — matched only after code spans
# are stripped. Broader than the live form on purpose: a shape this close is a
# probable authoring slip, and reading it as "absent" would hide a partition.
PARTS_SHAPED = re.compile(r"^(\*\*)?parts(\*\*)?\s*[:：]", re.IGNORECASE)
LIST_QUOTE_PREFIX = re.compile(r"^(?:[-*+]\s+|>\s*)+")
CODE_SPAN = re.compile(r"`[^`]*`")
SEGMENT = re.compile(rf"^part\s+(\d+)\s*=\s*T(\d+)(?:\s*{DASH}\s*T?(\d+))?$")
EXEC_HEADING = re.compile(r"^#{1,6}\s.*execution parts", re.IGNORECASE)
FENCE = re.compile(r"^(`{3,}|~{3,})")


def content_lines(lines):
    """(lineno, line) for every line outside fenced code blocks."""
    fence_char, fence_len = None, 0
    for num, line in enumerate(lines, 1):
        match = FENCE.match(line.lstrip())
        if match:
            marker = match.group(1)
            if fence_char is None:
                fence_char, fence_len = marker[0], len(marker)
            elif marker[0] == fence_char and len(marker) >= fence_len:
                fence_char = None
            continue
        if fence_char is None:
            yield num, line


def classify(line):
    """None, ('live', body) or ('near-miss', content) for one non-fenced line.

    Anchored to the start of the line's content, with inline-backticked mentions
    stripped first — mid-sentence and documented forms never match; the same
    forms opening a line do, as an error.
    """
    content = CODE_SPAN.sub("", line).strip()
    prefix = LIST_QUOTE_PREFIX.match(content)
    core = content[prefix.end():] if prefix else content
    live = LIVE.match(core)
    if live and not prefix:
        return ("live", live.group(1).strip())
    if PARTS_SHAPED.match(core):
        return ("near-miss", content)
    return None


def parse_design(path):
    """(lives, near_misses, exec_heading_line) from the design file."""
    lives, near_misses, exec_heading = [], [], None
    with open(path, encoding="utf-8") as handle:
        for num, line in content_lines(handle.readlines()):
            kind = classify(line)
            if kind and kind[0] == "live":
                lives.append((num, kind[1]))
            elif kind:
                near_misses.append((num, kind[1]))
            if exec_heading is None and EXEC_HEADING.match(line.lstrip()):
                exec_heading = num
    return lives, near_misses, exec_heading


def parse_segments(body):
    """(parsed [(part_no, start, end)], problems) — endpoints as ints, no expansion."""
    parsed, problems = [], []
    for segment in (s.strip() for s in body.split("·")):
        match = SEGMENT.match(segment)
        if not match:
            problems.append(
                f'unparseable segment "{segment}" — expected `part <n> = T<a>[–T<b>]`'
            )
            continue
        part_no = int(match.group(1))
        start = int(match.group(2))
        end = int(match.group(3)) if match.group(3) else start
        parsed.append((part_no, start, end))
    return parsed, problems


def validate(parsed, n):
    """Shape + coverage problems for parsed segments against N tickets."""
    problems = []
    for position, (part_no, _, _) in enumerate(parsed):
        if part_no != position:
            problems.append(
                f"part numbers must be 0-based, contiguous and ascending in written "
                f"order — position {position} declares part {part_no}, expected part {position}"
            )
            return problems  # positions are unreliable past this point
    if len(parsed) < 2:
        problems.append(
            "a single part is what absence already means — declare two or more "
            "parts, or remove the line"
        )
    # Bounds first, arithmetic only — an absurd endpoint fails here, never expands.
    for part_no, start, end in parsed:
        if end < start:
            problems.append(f"part {part_no}: T{start}–T{end} is descending")
        elif start < 1 or end > n:
            problems.append(
                f"part {part_no} declares T{start}–T{end} but the decomposition "
                f"has T1..T{n}"
            )
    if problems:
        return problems
    prev_end, prev_owner = 0, None
    for part_no, start, end in parsed:
        if start > prev_end + 1:
            missing = ticket_span(prev_end + 1, start - 1)
            where = (
                "part 0 must start at T1"
                if prev_owner is None
                else f"the part {prev_owner} → part {part_no} boundary is non-contiguous"
            )
            problems.append(f"{missing} assigned to no part — would never run ({where})")
        elif start <= prev_end:
            overlap = ticket_span(start, min(prev_end, end))
            problems.append(
                f"{overlap} claimed by both part {prev_owner} and part {part_no} — "
                f"ranges overlap"
            )
        prev_end, prev_owner = max(prev_end, end), part_no
    if not problems and prev_end < n:
        problems.append(
            f"{ticket_span(prev_end + 1, n)} assigned to no part — would never run "
            f"(the declaration ends at T{prev_end}, the decomposition at T{n})"
        )
    return problems


def ticket_span(first, last):
    return f"T{first}" if first == last else f"T{first}–T{last}"


def main():
    if len(sys.argv) != 3:
        print("usage: check-parts.py <00-DESIGN.md> <01-DECOMPOSITION.md>")
        return 2
    design_path, decomp_path = sys.argv[1], sys.argv[2]

    try:
        lives, near_misses, exec_heading = parse_design(design_path)
    except (OSError, UnicodeDecodeError) as error:
        print(
            f"cannot read {design_path}: {error} — a package whose "
            f"declaration state is unknowable fails closed"
        )
        return 1

    if near_misses:
        print(f"parts-declaration problems in {design_path}:")
        for num, content in near_misses:
            print(
                f"  line {num}: near-miss — looks like a parts declaration but is "
                f'not the exact `**Parts:** …` form (an error, never "absent"): {content}'
            )
        return 1
    if len(lives) > 1:
        where = ", ".join(f"line {num}" for num, _ in lives)
        print(
            f"two live parts declarations in {design_path} ({where}) — exactly one "
            f'may exist; never "last one wins"'
        )
        return 1
    if not lives:
        if exec_heading is not None:
            print(
                f'"Execution parts" heading at {design_path} line {exec_heading} but '
                f"no parseable **Parts:** declaration — a decidable authoring defect, "
                f"not absence"
            )
            return 1
        print("no parts declaration — single-session (the default)")
        return 0

    lineno, body = lives[0]
    n = 0
    parsed, problems = parse_segments(body)
    if not problems:
        try:
            with open(decomp_path, encoding="utf-8") as handle:
                tickets = [
                    int(match.group(1))
                    for line in handle
                    if (match := TICKET_HEADING.match(line))
                ]
        except (OSError, UnicodeDecodeError) as error:
            print(
                f"a live parts declaration ({design_path} line {lineno}) but "
                f"{decomp_path} is unreadable: {error} — a declaration that "
                f"cannot be validated fails closed"
            )
            return 1
        n = len(tickets)
        if n == 0:
            problems = [f"no `^#{{2,4}} T<n>` ticket headings in {decomp_path}"]
        elif sorted(tickets) != list(range(1, n + 1)):
            found = ", ".join(f"T{t}" for t in tickets)
            problems = [
                f"ticket headings are not a contiguous T1..T{n} (found: {found}) — "
                f"renumber before declaring parts"
            ]
        else:
            problems = validate(parsed, n)

    if problems:
        print(f"parts-declaration problems ({design_path} line {lineno}):")
        for problem in problems:
            print(f"  {problem}")
        return 1

    covered = sum(end - start + 1 for _, start, end in parsed)
    print(f"{len(parsed)} parts covering {covered} of {n} tickets — {body}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
