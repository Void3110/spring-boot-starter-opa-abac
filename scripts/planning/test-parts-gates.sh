#!/usr/bin/env bash
# Regression tests for the execution-parts gates: check-parts.py, the
# verify-package.sh [9] wiring, and scaffold-package.py's --parts/--planning-root.
#
# Committed so the PARTS-PORT QA cases stay re-runnable (the slice's original
# drivers were throwaway). Everything runs against scratch fixtures in mktemp -d;
# nothing is written inside the repo tree, and no case depends on which planning
# packages currently exist. Exit codes are asserted exactly — the gates' contract
# IS their exit discipline (0 valid/absent · 1 problems · 2 usage).
#
# Requires scripts/planning/cleanroom-patterns.local (same as the gate itself —
# bootstrap from the committed .example; the [3] fixture runs fail closed without it).
#
# Usage:  scripts/planning/test-parts-gates.sh     (from any cwd)
# Exit:   0 = all green · 1 = failures (printed).
set -u
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
REPO="$(cd "$SCRIPT_DIR/../.." && pwd -P)"
CP="$SCRIPT_DIR/check-parts.py"
SP="$SCRIPT_DIR/scaffold-package.py"
VP="$SCRIPT_DIR/verify-package.sh"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
PASS=0; FAIL=0

check() { # id want_rc got_rc desc needle output
  local id=$1 want=$2 got=$3 desc=$4 needle=$5 out=$6 bad=""
  [ "$got" -eq "$want" ] || bad="rc=$got want=$want"
  if [ -z "$bad" ] && [ -n "$needle" ] && ! printf '%s' "$out" | grep -qF -- "$needle"; then
    bad="missing: $needle"
  fi
  if [ -z "$bad" ]; then PASS=$((PASS+1)); printf 'PASS %-14s %s\n' "$id" "$desc"
  else FAIL=$((FAIL+1)); printf 'FAIL %-14s %s — %s\n' "$id" "$desc" "$bad"; printf '%s\n' "$out" | sed 's/^/       /'; fi
}

fm() { printf -- '---\ntags:\n  - status/planned\n  - type/project\n  - area/methodology\n---\n\n'; }

# ══ check-parts.py — the declaration grammar (PARTS-PORT U1–U15 + hardening arms) ══
D5="$WORK/decomp5.md"
{ for i in 1 2 3 4 5; do printf '## T%d — ticket %d\n\ntext\n\n' "$i" "$i"; done; } > "$D5"
mk() { printf '%s\n' "$1" > "$WORK/design.md"; }
run() { OUT=$(python3 "$CP" "$WORK/design.md" "$D5" 2>&1); RC=$?; }

mk '**Parts:** part 0 = T1–T2 · part 1 = T3–T5'
run; check U1 0 "$RC" "valid two-part declaration" "2 parts covering 5 of 5 tickets" "$OUT"
mk 'plain prose, nothing else'
run; check U2 0 "$RC" "no declaration is green" "single-session (the default)" "$OUT"
mk '- **Parts:** part 0 = T1–T2 · part 1 = T3–T5'
run; check U3a 1 "$RC" "bulleted near-miss" "near-miss" "$OUT"
mk 'Parts: part 0 = T1–T2 · part 1 = T3–T5'
run; check U3b 1 "$RC" "unbolded near-miss" "near-miss" "$OUT"
mk '**Parts**: part 0 = T1–T2 · part 1 = T3–T5'
run; check U3c 1 "$RC" "colon-outside-bold near-miss" "near-miss" "$OUT"
mk '**Parts：** part 0 = T1–T2 · part 1 = T3–T5'
run; check U3f 1 "$RC" "fullwidth-colon near-miss (never absent)" "near-miss" "$OUT"
mk '`- **Parts:**` is a documented near-miss form.
`Parts:` unbolded and `**Parts**:` are the others.
a sentence naming - **Parts:** mid-line is also not a declaration.'
run; check U3d 0 "$RC" "backticked + mid-line mentions are NOT near-misses" "single-session" "$OUT"
mk '```
**Parts:** part 0 = T1–T9 · part 1 = T10–T12
```

**Parts:** part 0 = T1–T2 · part 1 = T3–T5'
run; check U4a 0 "$RC" "fenced example + live line: live wins" "2 parts covering 5 of 5" "$OUT"
mk '```
**Parts:** part 0 = T1–T9 · part 1 = T10–T12
```'
run; check U4b 0 "$RC" "ONLY a fenced example = absent" "single-session" "$OUT"
mk '# d

**Parts:** part 0 = T1–T2 · part 1 = T3–T5

**Parts:** part 0 = T1–T3 · part 1 = T4–T5'
run; check U5 1 "$RC" "two live declarations name both lines" "line 3, line 5" "$OUT"
mk '**Parts:** part 0 = T1–T2 · part 1 = T4–T5'
run; check U6 1 "$RC" "coverage gap names the dropped ticket" "T3 assigned to no part — would never run" "$OUT"
mk '**Parts:** part 0 = T1–T3 · part 1 = T3–T5'
run; check U7 1 "$RC" "overlap names ticket + both parts" "T3 claimed by both part 0 and part 1" "$OUT"
mk '**Parts:** part 0 = T1–T3 · part 1 = T4–T900000000'
T0=$(date +%s); run; T1S=$(date +%s)
check U8 1 "$RC" "absurd range fails on bounds" "T1..T5" "$OUT"
[ $((T1S-T0)) -le 2 ] && { PASS=$((PASS+1)); echo "PASS U8t            bounds-checked, never expanded ($((T1S-T0))s)"; } \
                      || { FAIL=$((FAIL+1)); echo "FAIL U8t            took $((T1S-T0))s (>2s)"; }
mk '**Parts:** part 0 = T1–T5'
run; check U9 1 "$RC" "one-part declaration is an error" "single part is what absence already means" "$OUT"
mk '**Parts:** part 0 = T1–T1 · part 1 = T2–T2 · part 2 = T4–T5'
run; check U10 1 "$RC" "non-contiguous boundary named" "part 1 → part 2 boundary" "$OUT"
for dash in '–' '—' '-'; do
  mk "**Parts:** part 0 = T1${dash}T2 · part 1 = T3${dash}T5"
  run; check "U11($dash)" 0 "$RC" "dash variant parses" "2 parts covering 5 of 5" "$OUT"
done
mk '**Parts:** part 0 = T1–T4 · part 1 = T5'
run; check U12 0 "$RC" "single-ticket part parses" "2 parts covering 5 of 5" "$OUT"
mk '# d

## Execution parts

prose that is not a declaration'
run; check U13 1 "$RC" "heading without declaration is a defect" "authoring defect" "$OUT"
DGAP="$WORK/decomp-gap.md"
{ for i in 1 2 4 5 6; do printf '## T%d — t\n\n' "$i"; done; } > "$DGAP"
mk '**Parts:** part 0 = T1–T2 · part 1 = T3–T5'
OUT=$(python3 "$CP" "$WORK/design.md" "$DGAP" 2>&1); RC=$?
check U14 1 "$RC" "non-contiguous ticket numbering" "renumber" "$OUT"
mk '**Parts:** part 0 = T1–T2 · part 1 = T3–T5'
OUT=$(python3 "$CP" "$WORK/design.md" "$WORK/no-such.md" 2>&1); RC=$?
check U15a 1 "$RC" "declaration + unreadable decomposition fails closed" "fails closed" "$OUT"
mk 'no declaration here'
OUT=$(python3 "$CP" "$WORK/design.md" "$WORK/no-such.md" 2>&1); RC=$?
check U15b 0 "$RC" "no declaration: decomposition never opened" "single-session" "$OUT"
head -c 64 /dev/urandom > "$WORK/binary.md"
OUT=$(python3 "$CP" "$WORK/binary.md" "$D5" 2>&1); RC=$?
check Ubin 1 "$RC" "unreadable/binary design fails closed, cleanly" "fails closed" "$OUT"
OUT=$(python3 "$CP" 2>&1); RC=$?
check Uusage 2 "$RC" "usage error exits 2" "usage:" "$OUT"

# ══ scaffold-package.py — --parts writes blind · --planning-root stays out-of-tree ══
OUT=$(python3 "$SP" --slice TPKG --tickets 5 --area methodology --with-design \
      --parts 'part 0 = T1–T2 · part 1 = T3–T5' --planning-root "$WORK/sc" 2>&1); RC=$?
D="$WORK/sc/TPKG/00-DESIGN.md"
if [ "$RC" -eq 0 ] && grep -qF '## 5. Execution parts' "$D" \
   && grep -qF '**Parts:** part 0 = T1–T2 · part 1 = T3–T5' "$D"; then
  PASS=$((PASS+1)); echo "PASS U20            --parts writes the section + declaration verbatim"
else FAIL=$((FAIL+1)); echo "FAIL U20            rc=$RC"; printf '%s\n' "$OUT"; fi
OUT=$(python3 "$SP" --slice TGARB --tickets 2 --area methodology --with-design \
      --parts 'totally not a partition' --planning-root "$WORK/sc" 2>&1); RC=$?
[ "$RC" -eq 0 ] && grep -qF '**Parts:** totally not a partition' "$WORK/sc/TGARB/00-DESIGN.md" \
  && { PASS=$((PASS+1)); echo "PASS U20v           garbage accepted verbatim — the scaffold validates NOTHING"; } \
  || { FAIL=$((FAIL+1)); echo "FAIL U20v           rc=$RC"; }
OUT=$(python3 "$SP" --slice TNOD --tickets 2 --area methodology \
      --parts 'part 0 = T1 · part 1 = T2' --planning-root "$WORK/sc" 2>&1); RC=$?
check U20e 1 "$RC" "--parts without --with-design errors" "needs --with-design" "$OUT"
OUT=$(python3 "$SP" --slice TBAD --tickets 5 --area methodology --with-design \
      --parts 'part 0 = T1–T2 · part 1 = T4–T5' --planning-root "$WORK/sc" 2>&1); RC=$?
check U21a 0 "$RC" "scaffold exits 0 on a coverage-gap partition" "" "$OUT"
OUT=$(python3 "$CP" "$WORK/sc/TBAD/00-DESIGN.md" "$WORK/sc/TBAD/01-DECOMPOSITION.md" 2>&1); RC=$?
check U21b 1 "$RC" "single-authority proof: the gate catches what the scaffold wrote" "T3 assigned to no part" "$OUT"
BEFORE=$(ls "$REPO/docs/to-do/planning" | sort)
OUT=$(cd "$WORK" && python3 "$SP" --slice TOUT --tickets 3 --area methodology --planning-root "$WORK/sc" 2>&1); RC=$?
AFTER=$(ls "$REPO/docs/to-do/planning" | sort)
if [ "$RC" -eq 0 ] && [ -f "$WORK/sc/TOUT/01-DECOMPOSITION.md" ] && [ "$BEFORE" = "$AFTER" ]; then
  PASS=$((PASS+1)); echo "PASS U22/U24        out-of-tree skeleton from a non-repo cwd; repo tree untouched"
else FAIL=$((FAIL+1)); echo "FAIL U22/U24        rc=$RC"; fi
SUM1=$(cd "$WORK/sc/TPKG" && find . -type f -exec md5 -q {} + 2>/dev/null | sort || find . -type f -exec md5sum {} + | sort)
python3 "$SP" --slice TPKG --tickets 5 --area methodology --with-design \
  --parts 'part 0 = T1–T2 · part 1 = T3–T5' --planning-root "$WORK/sc" >/dev/null 2>&1
SUM2=$(cd "$WORK/sc/TPKG" && find . -type f -exec md5 -q {} + 2>/dev/null | sort || find . -type f -exec md5sum {} + | sort)
N=$(grep -cF '## 5. Execution parts' "$D")
[ "$SUM1" = "$SUM2" ] && [ "$N" -eq 1 ] \
  && { PASS=$((PASS+1)); echo "PASS IDEM           re-run converges; exactly one Execution-parts section"; } \
  || { FAIL=$((FAIL+1)); echo "FAIL IDEM           sections=$N"; }

# ══ verify-package.sh [9] — wired, exit codes distinguished, path form from any cwd ══
mk_pkg() { # $1 dir, $2 extra design line ('' for none) — minimal gate-green package
  local P=$1 extra=$2 name; name=$(basename "$P"); mkdir -p "$P"
  { fm; printf '# %s — index\n\nticket table lives here.\n' "$name"; } > "$P/$name.md"
  { fm; printf '# %s — design\n\nplain mechanism prose.\n' "$name"
    [ -n "$extra" ] && printf '\n%s\n' "$extra"; } > "$P/00-DESIGN.md"
  { fm; printf '# %s — decomposition\n\n## Critical path\n\nT1 then T2.\n\n' "$name"
    for i in 1 2; do printf '## T%d — step %d\n\n**Goal.** g\n\n**Deliverables.**\n- d\n\n**Acceptance.** **U%d**.\n\n**What NOT to touch.** n\n\n' "$i" "$i" "$i"; done
  } > "$P/01-DECOMPOSITION.md"
  { fm; printf '# %s — QA\n\n| ID | Case | Asserts | → Ticket |\n|---|---|---|---|\n| U1 | a | b | T1 |\n| U2 | c | d | T2 |\n' "$name"; } > "$P/10-QA-TEST-CASES.md"
  { fm; cat <<'EOF'
# fixture — autonomous implementation prompt

You are implementing the fixture on branch `feature/void3110/fixture`.

### Per-ticket loop

1. Build.
2. ★ ARCHITECTURE REVIEW + REFACTOR.
3. CHECKPOINT — STOP and report.

### Hard rules

- Fail-closed is the load-bearing invariant.
- Do NOT push, open PRs, or touch `main`.
- A `Co-Authored-By: Claude` trailer is welcome.
EOF
  } > "$P/AUTONOMOUS-IMPLEMENTATION-PROMPT.md"
  for i in 1 2; do { fm; printf '# STATUS — T%d\n\n**Status:** 📋 TODO\n' "$i"; } > "$P/STATUS-0$i.md"; done
}
mk_pkg "$WORK/POK" ''
OUT=$(cd "$WORK" && "$VP" "$WORK/POK" 2>&1); RC=$?
check U17-19a 0 "$RC" "gate-green fixture: [9] single-session, run from a non-repo cwd" "single-session" "$OUT"
printf '%s' "$OUT" | grep -q '\[9\] execution parts' \
  && { PASS=$((PASS+1)); echo "PASS U18-section    [9] section present in the output"; } \
  || { FAIL=$((FAIL+1)); echo "FAIL U18-section    no [9] section"; }
mk_pkg "$WORK/PBAD" '**Parts:** part 0 = T1–T2 · part 1 = T2–T2'
OUT=$(cd "$WORK" && "$VP" "$WORK/PBAD" 2>&1); RC=$?
check U18b 1 "$RC" "malformed declaration fails the package at [9]" "execution-parts problems" "$OUT"

echo
echo "== $PASS passed, $FAIL failed =="
exit "$((FAIL > 0))"
