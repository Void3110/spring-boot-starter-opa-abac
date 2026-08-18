#!/usr/bin/env bash
# Regression tests for check-shell-guards.py — the guarded-substitution gate.
#
# Committed for the reason the SPA-CHALLENGE-UX review made structural: a gate
# is only worth its exit code if something proves it still discriminates. Every
# case below is a fixture the gate must classify, and the arms come in pairs —
# for each "must flag" there is a near-identical "must stay quiet", because a
# check that flags everything passes a must-flag suite while being useless.
#
# Fixtures live in mktemp -d; nothing is written inside the repo tree, and no
# case depends on which runners currently exist. Exit codes are asserted
# exactly — the gate's contract IS its exit discipline (0 clean · 1 violations
# · 2 malformed waiver).
#
# Usage:  scripts/checks/test-shell-guards.sh     (from any cwd)
# Exit:   0 = all green · 1 = failures (printed).
set -u
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
CG="$SCRIPT_DIR/check-shell-guards.py"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
PASS=0; FAIL=0

check() { # id want_rc got_rc desc needle output
  local id=$1 want=$2 got=$3 desc=$4 needle=$5 out=$6 bad=""
  [ "$got" -eq "$want" ] || bad="rc=$got want=$want"
  if [ -z "$bad" ] && [ -n "$needle" ] && ! printf '%s' "$out" | grep -qF -- "$needle"; then
    bad="missing: $needle"
  fi
  if [ -z "$bad" ]; then PASS=$((PASS+1)); printf 'PASS %-10s %s\n' "$id" "$desc"
  else FAIL=$((FAIL+1)); printf 'FAIL %-10s %s — %s\n' "$id" "$desc" "$bad"; printf '%s\n' "$out" | sed 's/^/       /'; fi
}

fixture() { # body -> writes $WORK/f.sh
  { printf '#!/usr/bin/env bash\nset -euo pipefail\n\n'; printf '%s\n' "$1"; } > "$WORK/f.sh"
}
run() { OUT=$(python3 "$CG" "$WORK/f.sh" 2>&1); RC=$?; }

# ══ The core rule: fallible + guarded + bare + no `|| true` ══
fixture 'TOKEN="$(mint_token alice secret)"
[ -n "$TOKEN" ] || { echo "ERROR: no token" >&2; exit 1; }'
run; check G1 1 "$RC" "fallible + guarded is flagged" "TOKEN is guarded below" "$OUT"

fixture 'TOKEN="$(mint_token alice secret)" || true
[ -n "$TOKEN" ] || { echo "ERROR: no token" >&2; exit 1; }'
run; check G2 0 "$RC" "the same site with || true is clean" "clean" "$OUT"

# ══ Arm 4: an UNGUARDED substitution promised nothing ══
fixture 'TOKEN="$(mint_token alice secret)"
echo "using $TOKEN"'
run; check G3 0 "$RC" "unguarded substitution is out of scope" "clean" "$OUT"

# ══ Arm 2: local/declare/export mask the exit status — must NOT be flagged ══
for kw in local declare export readonly; do
  fixture "f() {
  $kw TOKEN=\"\$(mint_token alice secret)\"
  [ -n \"\$TOKEN\" ] || { echo 'ERROR' >&2; exit 1; }
}"
  run; check "G4-$kw" 0 "$RC" "$kw masks set -e, stays quiet" "clean" "$OUT"
done

fixture 'f() {
  local TOKEN
  TOKEN="$(mint_token alice secret)"
  [ -n "$TOKEN" ] || { echo "ERROR" >&2; exit 1; }
}'
run; check G5 1 "$RC" "pre-declared local, bare assignment IS flagged" "TOKEN is guarded" "$OUT"

# ══ Arm 3: pipeline classification — the trap this gate was built around ══
fixture 'ACCESS="$(printf "%s" "$JSON" | sed -n "s/.*x:\(.*\)/\1/p")"
[ -n "$ACCESS" ] || { echo "ERROR" >&2; exit 1; }'
run; check P1 0 "$RC" "printf|sed cannot fail, stays quiet" "clean" "$OUT"

fixture 'ID="$(printf "%s" "$JSON" | python3 -c "import sys,json;print(json.load(sys.stdin))")"
[ -n "$ID" ] || { echo "ERROR" >&2; exit 1; }'
run; check P2 1 "$RC" "printf|python3 CAN fail despite the printf head" "ID is guarded" "$OUT"

fixture 'SUB="$(printf "%s" "$JWT" | base64 -d | sed -n "1p")"
[ -n "$SUB" ] || { echo "ERROR" >&2; exit 1; }'
run; check P3 1 "$RC" "printf|base64 CAN fail on bad padding" "SUB is guarded" "$OUT"

fixture 'HIT="$(printf "%s" "$BODY" | grep -o "needle")"
[ -n "$HIT" ] || { echo "ERROR" >&2; exit 1; }'
run; check P4 1 "$RC" "grep exits 1 on no-match, so it is fallible" "HIT is guarded" "$OUT"

fixture 'V="$(cat /etc/some-file | sed -n "1p")"
[ -n "$V" ] || { echo "ERROR" >&2; exit 1; }'
run; check P5 1 "$RC" "a filter given a FILE operand can fail" "V is guarded" "$OUT"

# ══ Self-absorbing substitutions (regression: 11 false positives, first pass) ══
fixture 'V="$(docker exec pod printenv THING 2>/dev/null || echo "<unset>")"
[ -n "$V" ] || { echo "ERROR" >&2; exit 1; }'
run; check A1 0 "$RC" "an inner || fallback absorbs failure, stays quiet" "clean" "$OUT"

fixture 'V="$(some_cmd | json_field count || echo "<unparseable>")"
[ -n "$V" ] || { echo "ERROR" >&2; exit 1; }'
run; check A2 0 "$RC" "|| fallback after a pipe also absorbs" "clean" "$OUT"

# ══ The span scanner: quote-awareness and nesting ══
# Both arms are built so a BROKEN scanner changes the VERDICT, not just the
# internals — an earlier draft asserted here with balanced parens, which a naive
# counter gets right by luck, and both mutations survived.
#
# Q1: a lone `(` inside a string literal. Counting parens blind never returns to
# depth 0, so the substitution reads as unterminated (rc 2) instead of flagged.
fixture 'ROWS="$(psql -c "SELECT count( FROM t")"
[ -n "$ROWS" ] || { echo "ERROR" >&2; exit 1; }'
run; check Q1 1 "$RC" "an unbalanced paren INSIDE quotes does not break the span" "ROWS is guarded" "$OUT"

# Q2: a multi-line substitution with `|| true` on the LAST physical line — the
# tail must be read from the span's true end, not the first line.
#
# NOT covered, stated rather than implied: the scanner's paren-DEPTH branch. A
# nested `$(...)` inside quotes is handled by quote tracking, so depth never
# fires; neutering it changes neither this suite nor the real corpus (measured).
# The branch is kept because it is correct, not because a test pins it.
fixture 'V="$(outer \
  "$(inner a)" \
  b)" || true
[ -n "$V" ] || { echo "ERROR" >&2; exit 1; }'
run; check Q2 0 "$RC" "multi-line substitution: || true on the last line is seen" "clean" "$OUT"

# ══ Guard shapes ══
fixture 'ID="$(post_json /x "{}")"
[ "$ID" != "null" ] || { echo "ERROR" >&2; exit 1; }'
run; check S1 1 "$RC" "[ \"\$V\" != … ] counts as a guard" "ID is guarded" "$OUT"

fixture 'TOK="$(mint_token a b)"
require_token "user" "$TOK"'
run; check S2 1 "$RC" "require_token counts as a guard" "TOK is guarded" "$OUT"

# ══ Line continuation must not hide a site ══
fixture 'JSON="$(curl -s \
  --fail \
  http://localhost/x)"
[ -n "$JSON" ] || { echo "ERROR" >&2; exit 1; }'
run; check C1 1 "$RC" "a backslash-continued substitution is still seen" "JSON is guarded" "$OUT"

# ══ Non-strict scripts are out of scope entirely ══
{ printf '#!/usr/bin/env bash\n\n'; printf 'TOKEN="$(mint_token a b)"\n[ -n "$TOKEN" ] || exit 1\n'; } > "$WORK/f.sh"
OUT=$(python3 "$CG" "$WORK/f.sh" 2>&1); RC=$?
check N1 0 "$RC" "no set -e, no abort, nothing to police" "clean" "$OUT"

# ══ Waivers ══
fixture '# shell-guards: ignore -- the caller already validated this
TOKEN="$(mint_token alice secret)"
[ -n "$TOKEN" ] || { echo "ERROR" >&2; exit 1; }'
run; check W1 0 "$RC" "a waiver with a reason silences the site" "clean" "$OUT"

fixture '# shell-guards: ignore
TOKEN="$(mint_token alice secret)"
[ -n "$TOKEN" ] || { echo "ERROR" >&2; exit 1; }'
run; check W2 2 "$RC" "a waiver WITHOUT a reason is itself an error" "without a reason" "$OUT"

printf '\n%d passed, %d failed\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
