#!/usr/bin/env python3
"""Conformance rules for the newman e2e collections (scripts/postman/*.json).

WHY THIS EXISTS
---------------
The SPA-CHALLENGE-UX review put 5 of its first 16 findings in ONE collection.
The Sonar gate scans changed `.java` only, so nothing in this repo reads a
`.postman_collection.json` — the review was doing it by hand, adversarially, at
roughly 1.8M tokens a round. These three rules are the defects that recurred.

A cell that cannot fail is worse than a missing cell: the missing one is
visible in the matrix, the vacuous one reports green forever.

THE RULES
---------
ABSENCE-ONLY
    A cell whose only claim about a list is ``not.include`` passes trivially
    when the list is EMPTY. E32b guarded a leak into a supervisor's page that
    teardown had already emptied — `ids` was `[]`, all three `not.include`
    checks passed, and the cell asserted nothing about the leak it was named
    for. The fix is a count/length control alongside the absence claim; that
    control is what actually fails when a stray row arrives.

NAME-OVERCLAIM
    A cell whose NAME claims a resource its request never touches. E31j
    promised the product ids survived a re-seed and only ever read
    `/categories`, so the exact non-idempotency the folder exists to catch
    would have passed it.

    The rule is deliberately narrow: only a CLAIM-SHAPED noun counts — a
    resource noun immediately followed by `id`/`ids`. A looser "any resource
    noun in the name" rule tripped on prose like "E5 MEMBERS UNAFFECTED", which
    is a description of the scenario, not a claim about what was read. Roughly
    13 false positives across the suite came from that looser form; it is why
    this gate was not adopted at review time.

UNPINNED-WINDOW
    A cell that reads `max_age` out of a decision without comparing it to the
    SHIPPED value passes on a rig left in the freshness drill (a deliberately
    shortened window). The runner exports the shipped value; the cell must
    compare against it.

PER-COLLECTION SCOPE
--------------------
Some collections legitimately break a rule because they OWN the thing it
polices — the step-up matrix reads drilled `max_age` values because it performs
the drill. A collection declares that in its `info.description`, on its own
line::

    conformance-lint: owns-drill

Declaring a waiver a collection does not need is itself an error, so the
declarations cannot rot into decoration.

USAGE
-----
    scripts/checks/check-collection-conformance.py [files...]   # default: scripts/postman

Exit 0 clean · 1 violations · 2 a waiver that is unused or unknown.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

RESOURCE_NOUNS = {
    "catalog": "catalogs",
    "category": "categories",
    "product": "products",
    "team": "teams",
    "user": "users",
    "membership": "memberships",
    "tag": "tag-definitions",
    "role": "roles",
}
KNOWN_WAIVERS = {"owns-drill", "absence-only-ok"}
WAIVER_LINE = re.compile(r"^\s*conformance-lint:\s*(?P<w>[\w, -]+)\s*$", re.M)

# A claim about identity: "the CATEGORY ids survived", "product id is unchanged".
CLAIM = re.compile(r"\b(?P<noun>" + "|".join(RESOURCE_NOUNS) + r")s?\s+ids?\b", re.I)

ABSENCE = re.compile(r"\.to\.not\.include\b|\.to\.not\.contain\b")
# Any control that makes an EMPTY list fail the cell. Two shapes qualify, and
# both had to be learned from real cells:
#   - an explicit count/length claim (E32b's fix);
#   - a POSITIVE membership claim on the same list — `to.include(x)` cannot pass
#     on `[]`. hierarchy-list E1/E2 pair every `not.include` with one, and an
#     earlier draft that only knew the count shape reported both as vacuous.
COUNT_CONTROL = re.compile(
    r"\bcount\s*[,)]|\.count\b.*\.to\.eql|\.to\.have\.lengthOf\b|"
    r"\.length\s*\)?\s*\.to\.|\.to\.have\.length\b|"
    r"\.to\.include\b|\.to\.contain\b"
)
MAX_AGE_READ = re.compile(r"\bmax_age\b")
# A value assertion of ANY kind pins the window. `shipped_max_age` is the good
# form (it tracks the policy file); a literal — `max_age="300"` — is brittle but
# NOT the defect this rule names: it fails LOUDLY on a drilled rig rather than
# passing vacuously. Flagging it made 7 production-tier cells red for a smell
# the rule was not about.
MAX_AGE_PINNED = re.compile(r"shipped_max_age|max_age\\?[\"'=]+\s*\\?\"?\d+")


def cells(items, trail=""):
    for it in items or []:
        if "item" in it:
            yield from cells(it["item"], trail + it.get("name", "") + " / ")
        else:
            yield trail, it


def script_of(cell) -> str:
    out = []
    for ev in cell.get("event", []):
        out.extend(ev.get("script", {}).get("exec", []) or [])
    return "\n".join(out)


def url_of(cell) -> str:
    u = cell.get("request", {}).get("url", {})
    return u.get("raw", "") if isinstance(u, dict) else str(u)


def strip_comments(js: str) -> str:
    """Assertions only. A `//` note explaining a trap is not an assertion, and
    reading it as one is how a gate starts passing on prose."""
    return "\n".join(re.sub(r"//.*$", "", ln) for ln in js.splitlines())


def check_collection(path: Path) -> tuple[list[str], list[str]]:
    doc = json.loads(path.read_text())
    desc = doc.get("info", {}).get("description", "") or ""
    declared = set()
    for m in WAIVER_LINE.finditer(desc):
        declared |= {w.strip() for w in m.group("w").split(",") if w.strip()}

    unknown = declared - KNOWN_WAIVERS
    errors = [f"{path.name}: unknown conformance-lint waiver '{w}'" for w in sorted(unknown)]
    declared &= KNOWN_WAIVERS

    violations: list[str] = []
    used: set[str] = set()

    for trail, cell in cells(doc.get("item", [])):
        name = cell.get("name", "")
        where = f"{path.name}: {trail}{name}"
        js = strip_comments(script_of(cell))
        url = url_of(cell)

        # ── ABSENCE-ONLY ────────────────────────────────────────────────────
        if ABSENCE.search(js) and not COUNT_CONTROL.search(js):
            if "absence-only-ok" in declared:
                used.add("absence-only-ok")
            else:
                violations.append(
                    f"{where}\n    ABSENCE-ONLY: asserts only what is NOT in the list. "
                    f"An empty list passes every check. Add a count/length control."
                )

        # ── NAME-OVERCLAIM ──────────────────────────────────────────────────
        for m in CLAIM.finditer(name):
            noun = m.group("noun").lower()
            segment = RESOURCE_NOUNS[noun]
            if segment not in url.lower():
                violations.append(
                    f"{where}\n    NAME-OVERCLAIM: the name claims {noun} ids, but the "
                    f"request never touches /{segment} ({url})."
                )

        # ── UNPINNED-WINDOW ─────────────────────────────────────────────────
        if MAX_AGE_READ.search(js) and not MAX_AGE_PINNED.search(js):
            if "owns-drill" in declared:
                used.add("owns-drill")
            else:
                violations.append(
                    f"{where}\n    UNPINNED-WINDOW: reads max_age without comparing it to "
                    f"shipped_max_age — passes on a rig left in the freshness drill."
                )

    for w in sorted(declared - used):
        errors.append(
            f"{path.name}: declares conformance-lint '{w}' but no cell needs it — "
            f"remove the waiver rather than leaving it to rot."
        )
    return violations, errors


def main(argv: list[str]) -> int:
    args = [Path(a) for a in argv[1:]] or [Path("scripts/postman")]
    files: list[Path] = []
    for a in args:
        files.extend(sorted(a.glob("*.postman_collection.json")) if a.is_dir() else [a])

    violations: list[str] = []
    errors: list[str] = []
    for f in files:
        v, e = check_collection(f)
        violations.extend(v)
        errors.extend(e)

    for e in errors:
        print(f"ERROR {e}", file=sys.stderr)
    for v in violations:
        print(v, file=sys.stderr)

    if errors:
        return 2
    if violations:
        print(f"\n{len(violations)} conformance violation(s).", file=sys.stderr)
        return 1
    print(f"collection-conformance: {len(files)} collection(s) clean")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
