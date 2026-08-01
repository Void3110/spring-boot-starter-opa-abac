#!/usr/bin/env python3
"""Scaffold a slice's decomposition package under docs/to-do/planning/<SLICE>/.

Deterministic boilerplate — folder, files, frontmatter, section skeletons — so the
/decompose skill spends its context on *content*, not structure.

Phase boundary (the repo's method, AUTONOMOUS-IMPLEMENTATION-FLOW.md): phase (1)
writes the index + 00-DESIGN; this scaffold creates the PHASE-(2) files by default
(01-DECOMPOSITION, 10-QA-TEST-CASES, STATUS stubs). Pass --with-design to also stub
the phase-(1) files when starting a brand-new slice folder during planning.

The AUTONOMOUS-IMPLEMENTATION-PROMPT.md is never scaffolded: it is copied verbatim
from the flow guide's section-4 template and slot-filled — the skeleton must stay
word-for-word.

Usage:
  python3 scripts/planning/scaffold-package.py --slice ACTION-ENRICHMENT --tickets 6 \
      --area abac --area opa [--with-design] [--force] \
      [--parts "part 0 = T1–T3 · part 1 = T4–T6"] [--planning-root <dir>]

--parts (needs --with-design) appends an "Execution parts" section carrying the
declaration VERBATIM to the 00-DESIGN.md stub. The scaffold validates NOTHING —
scripts/planning/check-parts.py is the single validation authority, and
verify-package.sh [9] is where a bad partition is caught.

Idempotent: existing files are skipped unless --force. Writes nothing outside
the package folder — docs/to-do/planning/<SLICE>/ by default, or
<planning-root>/<SLICE>/ with --planning-root (e.g. a scratch fixture dir).
Placeholders use «guillemets» so scripts/planning/verify-package.sh flags any
that remain unfilled.
"""
import argparse
import os
import sys


def repo_root():
    # Self-locating, never cwd-derived: the script may be invoked from outside the
    # repo (where a git-rev-parse subprocess fatals). The repo root is two levels
    # up from this file: scripts/planning/ -> scripts/ -> the root.
    return os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def frontmatter(status, typ, areas):
    lines = ["---", "tags:", f"  - status/{status}", f"  - type/{typ}"]
    lines += [f"  - area/{a}" for a in areas]
    lines += ["---", ""]
    return "\n".join(lines) + "\n"


def write(path, content, force):
    if os.path.exists(path) and not force:
        print(f"  skip (exists): {path}")
        return
    with open(path, "w") as f:
        f.write(content)
    print(f"  wrote: {path}")


def index_note(slice_name, areas, n):
    body = frontmatter("planned", "index", areas)
    body += f"# {slice_name} — «one-line slice title»\n\n"
    body += (
        "> **Status: Planning.** «2–3 sentences: what this slice delivers and why.»\n"
        "> Phase «N» of [[POC-ROADMAP]].\n\n"
    )
    body += "## Why this slice exists\n\n«The gap today → the mechanism this slice adds → the headline value.»\n\n"
    body += "## Files in this folder\n\n| File | What it is |\n|---|---|\n"
    body += f"| [[00-DESIGN]] | The mechanism, decided forks, fail-closed posture, considered-&-rejected. |\n"
    body += f"| [[01-DECOMPOSITION]] | The ordered work list T1…T{n} + the critical path. |\n"
    body += "| [[10-QA-TEST-CASES]] | Concrete U*/I*/E* cases → each ticket's Acceptance. |\n"
    body += "| AUTONOMOUS-IMPLEMENTATION-PROMPT | The self-contained prompt the run executes. |\n"
    body += f"| STATUS-01 … STATUS-{n:02d} | One stub per ticket, filled at each checkpoint. |\n\n"
    body += "## Ticket status at a glance\n\n| # | Title | Status |\n|---|---|---|\n"
    for i in range(1, n + 1):
        body += f"| T{i} | «title» | 📋 TODO |\n"
    body += "\n## Related\n\n- [[POC-ROADMAP]] — «phase link».\n- «ADRs / sibling slices».\n"
    return body


def design_note(slice_name, areas, parts=None):
    body = frontmatter("planned", "architecture", areas)
    body += f"""# {slice_name} — design

## 1. The mechanism

«End to end, with every integration point a NAMED link — an unnamed link is an
unwired one. The core abstraction(s) and where each lives.»

## 2. Decided forks

### 2.1 «fork»

«The decision + why. Anything externally visible is pinned here (or in an ADR) so
the autonomous run never re-asks.»

## 3. Fail-closed posture

«The one-sentence invariant — no error path widens the result — then the exact
failure modes and where each lands.»

## 4. Considered & rejected

| Option | Why rejected |
|---|---|
| «alt» | «reason» |
"""
    if parts is not None:
        # Written VERBATIM, validated by nothing here — check-parts.py is the single
        # validation authority (verify-package.sh [9] is where a bad partition fails).
        body += f"\n## 5. Execution parts\n\n**Parts:** {parts}\n"
    return body


def decomposition_note(slice_name, areas, n):
    body = frontmatter("planned", "project", areas)
    body += f"# {slice_name} — decomposition\n\n"
    body += f"> T1…T{n}, in order. Each ticket is one focused commit's worth of work.\n\n"
    body += "## Critical path\n\n```\nT1 ──► T2 ──► «…»\n```\n\n"
    body += (
        "«What's sequential, what's parallel, which early subset is independently\n"
        "landable for standalone value.»\n\n"
    )
    for i in range(1, n + 1):
        body += f"""## T{i} — «title»

**Goal.** «one sentence — the outcome this ticket delivers»

**Deliverables.**
- «exact classes / packages / rego rules / mappings — NAMED»

**Acceptance.** «the exact `:module:test` / `opa test` / e2e from 10-QA-TEST-CASES that proves it»

**What NOT to touch.** «the boundary — the slice invariants this ticket carries
forward (fail-closed, additive-only, core-stays-Spring-free, …). Flag build-breakers
here: the exact files that must land in the same commit.»

"""
    body += (
        "## Cross-cutting acceptance\n\n"
        "- `./gradlew build` green (all modules + integration tests).\n"
        "- «the slice's e2e proof».\n"
        "- The fail-closed invariant holds on every error path.\n"
    )
    return body


def qa_note(slice_name, areas):
    body = frontmatter("planned", "project", areas)
    body += f"""# {slice_name} — QA test cases

> Concrete cases; each becomes a ticket's *Acceptance*. U = unit, I = integration
> (Testcontainers Postgres — never H2; in-process HttpServer OPA stub — no WireMock),
> E = e2e (asserts the actual cut, not just response shape).

## Unit (U*)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| U1 | «case» | «invariant» | T«?» |

## Integration (I*)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| I1 | «case» | «invariant» | T«?» |

## E2E (E*)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| E1 | «flow» | «row counts / allow-vs-deny» | T«?» |

## Headline proof

«The 1–2 cases whose passing justifies the whole slice.»
"""
    return body


def status_stub(areas, i):
    body = frontmatter("planned", "project", areas)
    body += f"""# STATUS — T{i}: «title»

**Status:** 📋 TODO

## What shipped

## Tests

## Architecture review + refactor

«Which review path was used; what it found; what was refactored (or "nothing substantive").»

## Integration / e2e

## Decisions

## Commit
"""
    return body


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--slice", required=True, help="UPPER-KEBAB slice name")
    ap.add_argument("--tickets", type=int, required=True, help="ticket count → STATUS-01..NN stubs")
    ap.add_argument("--area", action="append", dest="areas", default=[],
                    help="frontmatter area/ tag (repeatable; >=1 required)")
    ap.add_argument("--with-design", action="store_true",
                    help="also stub the phase-(1) files (<SLICE>.md index + 00-DESIGN.md)")
    ap.add_argument("--parts", default=None, metavar="DECLARATION",
                    help="append an 'Execution parts' section with this **Parts:** "
                         "declaration, VERBATIM, to the 00-DESIGN.md stub (needs "
                         "--with-design; validated only by check-parts.py, never here)")
    ap.add_argument("--planning-root", default=None, metavar="DIR",
                    help="build the package skeleton under DIR instead of "
                         "docs/to-do/planning/ (e.g. a scratch fixture dir)")
    ap.add_argument("--force", action="store_true", help="overwrite existing files")
    a = ap.parse_args()

    if not a.areas:
        sys.exit("error: at least one --area is required")
    if a.tickets < 1:
        sys.exit("error: --tickets must be >= 1")
    if a.parts is not None and not a.with_design:
        sys.exit("error: --parts needs --with-design (the declaration lands in the 00-DESIGN.md stub)")

    planning_root = a.planning_root or os.path.join(repo_root(), "docs", "to-do", "planning")
    folder = os.path.join(planning_root, a.slice)
    os.makedirs(folder, exist_ok=True)
    print(f"Scaffolding {folder}/ ({a.tickets} tickets, areas={a.areas})")

    if a.with_design:
        write(os.path.join(folder, f"{a.slice}.md"), index_note(a.slice, a.areas, a.tickets), a.force)
        write(os.path.join(folder, "00-DESIGN.md"), design_note(a.slice, a.areas, a.parts), a.force)
    write(os.path.join(folder, "01-DECOMPOSITION.md"), decomposition_note(a.slice, a.areas, a.tickets), a.force)
    write(os.path.join(folder, "10-QA-TEST-CASES.md"), qa_note(a.slice, a.areas), a.force)
    for i in range(1, a.tickets + 1):
        write(os.path.join(folder, f"STATUS-{i:02d}.md"), status_stub(a.areas, i), a.force)

    print("\nNext: fill every «slot» with real content, write the prompt from the flow")
    print("guide's section-4 template, then gate with scripts/planning/verify-package.sh")


if __name__ == "__main__":
    main()
