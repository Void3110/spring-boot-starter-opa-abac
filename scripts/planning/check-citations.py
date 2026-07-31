#!/usr/bin/env python3
"""Acceptance-citation integrity for a slice decomposition package.

Two directions, both of which have silently broken a real package before:

  forward  — every U*/I*/E*/P* id a ticket's *Acceptance* cites must EXIST in
             10-QA-TEST-CASES.md, and that case's "→ Ticket" column must name the
             SAME ticket. A phantom id sends an autonomous run looking for tests
             that aren't there; an id the QA doc assigns elsewhere sends it to the
             WRONG tests (AGENT-TOOL-AUTHZ T5 cited "U8"/"I4", which the QA doc
             defines as T2 cases about malformed claims).
  reverse  — every QA case must be cited by the ticket that owns it. An uncited
             case is a silently-dropped definition of done (that same package's T6
             dropped E5 — the QA doc's own headline proof — by drifted numbering).

Ranges are expanded: "I16–I28", "E1-E11", "U18–U30" all count as citing every id
between the endpoints, inclusive (en-dash and hyphen both).

Usage:  check-citations.py <01-DECOMPOSITION.md> <10-QA-TEST-CASES.md>
Exit:   0 = clean · 1 = problems (printed, one per line). Read-only.
"""
import re
import sys

ID = r"(?:U|I|E|P)\d+"
# A cited id or range, as it appears bolded in an Acceptance paragraph: **I16–I28**
CITE = re.compile(rf"\*\*({ID})(?:\s*[–—-]\s*(?:U|I|E|P)?(\d+))?\*\*")
# A QA table row: | I16 | … | T5 |
ROW = re.compile(rf"^\|\s*({ID})\s*\|.*\|\s*(T\d+)\s*\|\s*$")
HEADING = re.compile(r"^#{2,4}\s+(T\d+)\b")
ACCEPTANCE = re.compile(r"^\*\*Acceptance\.?\*\*")


def parse_qa(path):
    """id -> owning ticket, from the QA doc's tables."""
    owners = {}
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            match = ROW.match(line.rstrip())
            if match:
                owners[match.group(1)] = match.group(2)
    return owners


def expand(match):
    """A citation match -> the list of ids it covers."""
    first, last = match.group(1), match.group(2)
    prefix, start = first[0], int(first[1:])
    if last is None:
        return [first]
    end = int(last)
    if end < start:
        return [first]
    return [f"{prefix}{n}" for n in range(start, end + 1)]


def parse_citations(path):
    """ticket -> set of ids cited in that ticket's Acceptance paragraph(s)."""
    cited, current, in_acceptance = {}, None, False
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            heading = HEADING.match(line)
            if heading:
                current = heading.group(1)
                in_acceptance = False
                continue
            if ACCEPTANCE.match(line):
                in_acceptance = True
            elif not line.strip():
                in_acceptance = False  # blank line ends the paragraph
            if in_acceptance and current:
                for match in CITE.finditer(line):
                    cited.setdefault(current, set()).update(expand(match))
    return cited


def main():
    if len(sys.argv) != 3:
        print("usage: check-citations.py <01-DECOMPOSITION.md> <10-QA-TEST-CASES.md>")
        return 2

    owners = parse_qa(sys.argv[2])
    if not owners:
        print("could not parse any '| <ID> | … | T<n> |' rows — did the QA table shape change?")
        return 1

    cited = parse_citations(sys.argv[1])
    problems = []

    for ticket in sorted(cited, key=lambda t: int(t[1:])):
        for case in sorted(cited[ticket], key=lambda c: (c[0], int(c[1:]))):
            owner = owners.get(case)
            if owner is None:
                problems.append(f"{ticket} cites {case} — no such case in the QA doc")
            elif owner != ticket:
                problems.append(f"{ticket} cites {case} — but the QA doc assigns it to {owner}")

    all_cited = set().union(*cited.values()) if cited else set()
    for case, owner in sorted(owners.items(), key=lambda kv: (kv[0][0], int(kv[0][1:]))):
        if case not in all_cited:
            problems.append(f"{case} ({owner}) is cited by no ticket — a dropped definition of done")

    print(f"parsed {len(owners)} QA cases; {len(all_cited)} cited across {len(cited)} tickets")
    for problem in problems:
        print(f"  {problem}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
