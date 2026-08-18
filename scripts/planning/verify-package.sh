#!/usr/bin/env bash
# Deterministic gates for a slice's decomposition package — the /decompose skill's verify
# step, as a committed script. Code is deterministic; language interpretation isn't, so the
# gates that decide "the package is done" are scripted, not prose.
#
# Usage:  scripts/planning/verify-package.sh <SLICE | path/to/package-folder>
#         A bare slice name resolves to docs/to-do/planning/<SLICE> in THIS repo; a
#         path (e.g. docs/to-do/implemented/<SLICE>, or an absolute out-of-tree
#         fixture dir) is used as-is — a relative path resolves against the
#         invocation cwd.
# Exit:   0 = all gates green · 1 = problems found (printed). Read-only — never edits.
#
# Clean-room gate: a few generic patterns (secrets, local absolute paths) are built in;
# the private blocklist of terms that must never appear in this public repo lives in
# scripts/planning/cleanroom-patterns.local — gitignored, one extended-regex alternation
# on its first non-comment line. Bootstrap it from the committed .example. The gate FAILS
# when the file is missing: fail closed, like everything else in this repo.

set -u
# Self-locating, never cwd-derived: sessions and subagents may start OUTSIDE the
# repo (where a git-rev-parse preamble fatals). The repo root is two levels up
# from this script's own location.
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd -P)"

ARG="${1:?usage: verify-package.sh <SLICE | path/to/package-folder>}"
case "$ARG" in
  */*)
    DIR="${ARG%/}"
    # Resolve against the invocation cwd BEFORE the cd below; a path inside the
    # repo is used repo-relative, exactly as before.
    if [ -d "$DIR" ]; then DIR="$(cd "$DIR" && pwd -P)"
    else case "$DIR" in /*) ;; *) DIR="$PWD/$DIR" ;; esac
    fi
    case "$DIR" in "$REPO_ROOT"/*) DIR="${DIR#"$REPO_ROOT"/}" ;; esac
    ;;
  *)   DIR="docs/to-do/planning/$ARG" ;;
esac
SLICE="$(basename "$DIR")"
cd "$REPO_ROOT" || exit 1
FAIL=0
ok()  { printf '  \033[0;32m✓\033[0m %s\n' "$1"; }
bad() { printf '  \033[0;31m✗\033[0m %s\n' "$1"; FAIL=1; }

[ -d "$DIR" ] || { echo "no such package folder: $DIR"; exit 1; }
echo "Verifying package: $DIR"

# ── 0. Build mode ───────────────────────────────────────────────────────────────
# A package may declare, on ONE line of its index, that it was built WITH the
# maintainer rather than by an autonomous run:
#
#     **Build: collaborative**
#
# Absence means autonomous — the stricter reading, since it runs both prompt arms.
# A collaborative package legitimately has no AUTONOMOUS-IMPLEMENTATION-PROMPT.md,
# so demanding one and then reporting "no prompt to check" made ship-time verify
# red for reasons that were not defects (SPA-CHALLENGE-UX, 2026-08-18).
#
# A NEAR-MISS is an error rather than a silent fall-through to autonomous. Falling
# through would only ever be stricter, so this is about not lying to the author
# about which mode ran — the same stance check-parts.py takes on `**Parts:**`.
INDEX="$DIR/$SLICE.md"
BUILD_MODE=autonomous
echo "[0] build mode"
if [ -f "$INDEX" ] && grep -qE '^[[:space:]]*>?[[:space:]]*\*\*Build:[[:space:]]+collaborative\*\*' "$INDEX"; then
  BUILD_MODE=collaborative
  ok "declared collaborative — the prompt arms [1]/[5] do not apply"
elif [ -f "$INDEX" ] && grep -qiE '^[[:space:]]*>?[[:space:]]*[-*]?[[:space:]]*\*{0,2}Build\*{0,2}[[:space:]]*:[[:space:]]*\*{0,2}collaborative' "$INDEX"; then
  bad "near-miss Build declaration — write it exactly as **Build: collaborative**"
else
  ok "autonomous (the default)"
fi

# ── 1. Required files ───────────────────────────────────────────────────────────
echo "[1] required files"
for f in "$SLICE.md" 00-DESIGN.md 01-DECOMPOSITION.md 10-QA-TEST-CASES.md; do
  if [ -f "$DIR/$f" ]; then ok "$f"; else bad "missing $f"; fi
done
if [ "$BUILD_MODE" = collaborative ]; then
  # Declaring collaborative while shipping a prompt is a contradiction: one of the
  # two is stale, and silently trusting the declaration would skip real checks.
  if [ -f "$DIR/AUTONOMOUS-IMPLEMENTATION-PROMPT.md" ]; then
    bad "declared collaborative but AUTONOMOUS-IMPLEMENTATION-PROMPT.md is present"
  else
    ok "no prompt file (collaborative)"
  fi
elif [ -f "$DIR/AUTONOMOUS-IMPLEMENTATION-PROMPT.md" ]; then
  ok "AUTONOMOUS-IMPLEMENTATION-PROMPT.md"
else
  bad "missing AUTONOMOUS-IMPLEMENTATION-PROMPT.md"
fi
STATUS_COUNT=$(find "$DIR" -maxdepth 1 -name 'STATUS-*.md' | wc -l | tr -d ' ')
if [ "$STATUS_COUNT" -ge 1 ]; then ok "STATUS stubs: $STATUS_COUNT"; else bad "no STATUS-NN.md stubs"; fi

# ── 2. Frontmatter: every note has one status/, one type/, >=1 area/ ────────────
echo "[2] frontmatter (one status/, one type/, >=1 area/)"
for f in "$DIR"/*.md; do
  base=$(basename "$f")
  fm=$(awk 'NR==1 && $0=="---" {in_fm=1; next} in_fm && $0=="---" {exit} in_fm {print}' "$f")
  s=$(printf '%s\n' "$fm" | grep -c 'status/')
  t=$(printf '%s\n' "$fm" | grep -c 'type/')
  a=$(printf '%s\n' "$fm" | grep -c 'area/')
  if [ "$s" -eq 1 ] && [ "$t" -eq 1 ] && [ "$a" -ge 1 ]; then ok "$base"
  else bad "$base frontmatter (status=$s type=$t area=$a — want 1/1/>=1)"; fi
done

# ── 3. Clean-room scan (this repo is public — MUST be empty) ────────────────────
echo "[3] clean-room scan"
# `~/Workspace`-style paths matter as much as absolute `/Users/...` ones: a machine-local layout
# is the leak, and the tilde form is how people actually write it. Learned the hard way twice —
# AGENT-TOOL-AUTHZ-REVIEW finding #10 fixed this class once, and it RECURRED in committed Mulch
# records because the pattern below only had the absolute form (SPA-CHALLENGE-UX verify round).
# Split so the WIDE scan below can treat the two halves differently (see there).
GENERIC_TOKEN='glpat-[A-Za-z0-9_-]|ghp_[A-Za-z0-9]'
GENERIC_HOME='/Users/[a-z]|~/Workspace'
GENERIC="$GENERIC_TOKEN|$GENERIC_HOME"
BLOCKFILE="scripts/planning/cleanroom-patterns.local"
if [ -f "$BLOCKFILE" ]; then
  PRIVATE=$(grep -vE '^\s*(#|$)' "$BLOCKFILE" | head -1)
  PATTERN="$GENERIC${PRIVATE:+|$PRIVATE}"
  HITS=$(grep -rnEi "$PATTERN" "$DIR" || true)
  # THE WIDE SCAN. `.mulch/` and `docs/` are COMMITTED and were covered by nothing — which is
  # exactly where this leak class hid twice (a Mulch record, and a review note asserting the
  # opposite in the same file). Records are written by `ml record` mid-session and review notes by
  # hand, so no other gate sees either.
  #
  # Rooted at $REPO_ROOT, NOT at `git rev-parse`: this script's own preamble forbids deriving the
  # root from git precisely because sessions and subagents start outside the repo — and a failed
  # rev-parse here would silently make the path `/.mulch`, skip the scan, and still print the line
  # claiming it ran. That is a fail-OPEN in a clean-room gate, so it fails closed instead.
  #
  # TWO greps per tree, because the halves need opposite casing:
  #   * home paths CASE-SENSITIVE — `-i` would match REST paths like `/api/v1/users/search`, which
  #     these trees quote constantly (the documented false positive, Mulch mx-e621ea). A real home
  #     path has capitals: `/Users/`, `~/Workspace`.
  #   * tokens + the PRIVATE blocklist CASE-INSENSITIVE — codenames and internal hostnames are
  #     prose, written in whatever case the sentence wanted. This is the highest-value half of the
  #     pattern and must not inherit the home-path half's case-sensitivity.
  WIDE_CI="$GENERIC_TOKEN${PRIVATE:+|$PRIVATE}"
  for tree in "$REPO_ROOT/.mulch" "$REPO_ROOT/docs"; do
    if [ ! -d "$tree" ]; then
      bad "cannot locate ${tree#"$REPO_ROOT"/} — clean-room scan INCOMPLETE (failing closed)"
      continue
    fi
    HITS="$HITS
$(grep -rnE "$GENERIC_HOME" "$tree" || true)
$(grep -rnEi "$WIDE_CI" "$tree" || true)"
  done
  # Blank lines are an artefact of the newline-joined accumulation above; strip them so the
  # emptiness test means "no hits" and the report never glues two violations onto one line.
  HITS=$(printf '%s\n' "$HITS" | grep -v '^[[:space:]]*$' || true)
  if [ -z "$HITS" ]; then ok "clean (generic + private blocklist; package + committed .mulch and docs)"
  else bad "clean-room violations:"; printf '%s\n' "$HITS" | sed 's/^/      /'; fi
else
  bad "no $BLOCKFILE — copy the .example and tailor it (the gate fails closed)"
fi

# ── 4. No unfilled placeholders («…» guillemets are the scaffold/slot marker) ───
echo "[4] no unfilled placeholders"
LEFT=$(grep -rln '«' "$DIR" 2>/dev/null || true)
if [ -z "$LEFT" ]; then ok "no unfilled «slots» remain"
else bad "unfilled «slots» in:"; printf '%s\n' "$LEFT" | sed 's/^/      /'; fi

# ── 5. The prompt carries the invariant skeleton ────────────────────────────────
echo "[5] prompt invariants"
P="$DIR/AUTONOMOUS-IMPLEMENTATION-PROMPT.md"
if [ -f "$P" ]; then
  grep -q  'feature/void3110/'  "$P" && ok "names the feature/void3110/<slice> branch" || bad "prompt: branch missing"
  grep -qi 'per-ticket loop'    "$P" && ok "has the per-ticket loop"                   || bad "prompt: no per-ticket loop"
  grep -qiE 'fail.?closed'      "$P" && ok "states the fail-closed invariant"          || bad "prompt: no fail-closed invariant"
  grep -qiE 'do NOT push'       "$P" && ok "has the do-NOT-push rule"                  || bad "prompt: missing 'do NOT push'"
  grep -qi 'ARCHITECTURE REVIEW' "$P" && ok "has the ★ architecture-review gate"       || bad "prompt: no ★ architecture-review gate"
  grep -qi 'CHECKPOINT'         "$P" && ok "has checkpoints"                           || bad "prompt: no CHECKPOINT step"
  grep -qi 'Co-Authored-By'     "$P" && ok "addresses the commit-trailer convention"   || bad "prompt: no Co-Authored-By note"
elif [ "$BUILD_MODE" = collaborative ]; then ok "skipped (collaborative build)"
else bad "no prompt to check"
fi

# ── 6. Decomposition: tickets well-formed, count matches STATUS stubs ───────────
echo "[6] decomposition tickets"
D="$DIR/01-DECOMPOSITION.md"
if [ -f "$D" ]; then
  TICKETS=$(grep -cE '^#{2,4} T[0-9]+' "$D")
  if [ "$TICKETS" -ge 1 ]; then ok "ticket headings: $TICKETS"; else bad "no '## T<n>' headings"; fi
  for field in 'Goal' 'Deliverables' 'Acceptance' 'NOT to touch'; do
    c=$(grep -c "$field" "$D")
    if [ "$c" -ge "$TICKETS" ]; then ok "every ticket has '$field' ($c)"
    else bad "'$field' appears $c times — fewer than $TICKETS tickets"; fi
  done
  if [ "$TICKETS" -eq "$STATUS_COUNT" ]; then ok "ticket count ($TICKETS) == STATUS stubs ($STATUS_COUNT)"
  else bad "ticket count ($TICKETS) != STATUS stubs ($STATUS_COUNT)"; fi
  grep -qi 'critical path' "$D" && ok "has a critical path" || bad "no critical-path section"
fi

# ── 7. Acceptance citations resolve to real QA cases, owned by the citing ticket ─
# A ticket citing an id that does not exist (or that the QA doc assigns to a DIFFERENT ticket)
# sends the run to the wrong tests — or silently shrinks the definition of done. Both have
# happened here, so this is scripted rather than eyeballed.
echo "[7] acceptance citations ↔ QA cases"
Q="$DIR/10-QA-TEST-CASES.md"
if [ -f "$D" ] && [ -f "$Q" ]; then
  if CITES=$(python3 scripts/planning/check-citations.py "$D" "$Q" 2>&1); then
    ok "$(printf '%s' "$CITES" | head -1) — all resolve, all owned, none dropped"
  else
    bad "acceptance-citation problems:"
    printf '%s\n' "$CITES" | sed 's/^/      /'
  fi
else
  bad "cannot cross-reference citations (missing 01-DECOMPOSITION.md or 10-QA-TEST-CASES.md)"
fi

# ── 8. Wikilinks resolve (was "eyeball it") ─────────────────────────────────────
echo "[8] wikilinks resolve"
BROKEN=""
for f in "$DIR"/*.md; do
  for target in $(grep -oE '\[\[[^]|]+' "$f" | sed 's/^\[\[//' | sort -u); do
    # An anchor-only or external-ish target is skipped; everything else must be a real note.
    case "$target" in \#*) continue ;; esac
    if ! find docs -name "$target.md" -print -quit 2>/dev/null | grep -q .; then
      BROKEN="$BROKEN\n      $(basename "$f") → [[$target]]"
    fi
  done
done
if [ -z "$BROKEN" ]; then ok "every [[wikilink]] resolves to a note under docs/"
else bad "unresolved wikilinks:"; printf "$BROKEN\n"; fi

# ── 9. Execution-parts coverage (absence = single-session; malformed is fatal) ──
# check-parts.py is the SINGLE validation authority for the **Parts:** grammar —
# this gate delegates and never re-parses. Exit codes are distinguished, never
# truthiness: 0 = valid/absent · 1 = declaration problems · anything else = the
# checker itself failed, which fails closed too.
echo "[9] execution parts"
PARTS_OUT=$(python3 scripts/planning/check-parts.py "$DIR/00-DESIGN.md" "$DIR/01-DECOMPOSITION.md" 2>&1)
PARTS_RC=$?
case "$PARTS_RC" in
  0) ok "$(printf '%s' "$PARTS_OUT" | head -1)" ;;
  1) bad "execution-parts problems:"; printf '%s\n' "$PARTS_OUT" | sed 's/^/      /' ;;
  *) bad "check-parts.py could not run (exit $PARTS_RC) — failing closed:"
     printf '%s\n' "$PARTS_OUT" | sed 's/^/      /' ;;
esac

echo
if [ "$FAIL" -eq 0 ]; then
  printf '\033[0;32mPACKAGE OK\033[0m — mechanical gates green.\n'
  printf 'Next: the adversarial pass (seam existence · unpinned semantics · cross-doc consistency) —\n'
  printf '  Workflow({ scriptPath: ".claude/skills/decompose/decompose-validation-workflow.js",\n'
  printf '             args: { slice: "%s" } })\n' "$SLICE"
else printf '\033[0;31mPACKAGE INCOMPLETE\033[0m — fix the ✗ items above\n'; fi
exit "$FAIL"
