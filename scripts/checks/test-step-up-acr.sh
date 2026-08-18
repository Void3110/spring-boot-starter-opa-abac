#!/usr/bin/env bash
# Regression tests for check-step-up-acr.py.
#
# The arm that matters is the ONE the policy corpus cannot catch: a required_acr that OPA's own
# `loa` map knows and the REALM cannot mint. Every guard in category.rego / product.rego passes on
# that data — the challenge is well-formed — and Keycloak rejects the authorization request with
# `Invalid parameter: claims`, so the user lands on an error page and the client never sees a
# response it could explain.
#
# Usage:  scripts/checks/test-step-up-acr.sh
# Exit:   0 = all green · 1 = failures (printed).
set -u
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
CK="$SCRIPT_DIR/check-step-up-acr.py"
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
PASS=0; FAIL=0

check() { local id=$1 want=$2 got=$3 desc=$4 needle=$5 out=$6 bad=""
  [ "$got" -eq "$want" ] || bad="rc=$got want=$want"
  if [ -z "$bad" ] && [ -n "$needle" ] && ! printf '%s' "$out" | grep -qF -- "$needle"; then bad="missing: $needle"; fi
  if [ -z "$bad" ]; then PASS=$((PASS+1)); printf 'PASS %-6s %s\n' "$id" "$desc"
  else FAIL=$((FAIL+1)); printf 'FAIL %-6s %s — %s\n' "$id" "$desc" "$bad"; printf '%s\n' "$out" | sed 's/^/       /'; fi; }

fixtures() { # $1 = step_up json body, $2 = acr.loa.map string
  printf '%s\n' "$1" > "$WORK/step_up.json"
  python3 -c "import json,sys; json.dump({'attributes':{'acr.loa.map':sys.argv[1]}}, open(sys.argv[2],'w'))" "$2" "$WORK/realm.json"
}
run() { OUT=$(python3 "$CK" "$WORK/step_up.json" "$WORK/realm.json" 2>&1); RC=$?; }

AGREED='{"step_up":{"loa":{"aal1":1,"aal2":2},"required_acr":"aal2","max_age":300,"skew":30}}'

fixtures "$AGREED" '{"aal1":1,"aal2":2}'
run; check S1 0 "$RC" "the shipped shape: both maps agree" "mintable by the realm" "$OUT"

# THE defect this check exists for. Every OPA-side guard passes here.
fixtures '{"step_up":{"loa":{"aal1":1,"aal2":2,"aal3":3},"required_acr":"aal3","max_age":300,"skew":30}}' '{"aal1":1,"aal2":2}'
run; check S2 1 "$RC" "required_acr the realm cannot mint" "Invalid parameter: claims" "$OUT"

# Fails closed OPA-side (plain deny), but still a misconfiguration worth naming.
fixtures '{"step_up":{"loa":{"aal1":1,"aal2":2},"required_acr":"aal9","max_age":300,"skew":30}}' '{"aal1":1,"aal2":2}'
run; check S3 1 "$RC" "required_acr absent from OPA's own loa" "not a key of step_up.loa" "$OUT"

# A name meaning 2 to OPA and 3 to Keycloak elevates nothing while looking right on both sides.
fixtures "$AGREED" '{"aal1":1,"aal2":3}'
run; check S4 1 "$RC" "level drift on a shared name" "level drift for 'aal2'" "$OUT"

# The realm may legitimately offer levels this deployment does not use.
fixtures "$AGREED" '{"aal1":1,"aal2":2,"aal3":3}'
run; check S5 0 "$RC" "realm knows MORE levels than OPA — fine" "mintable by the realm" "$OUT"

fixtures '{"step_up":{"loa":{"aal1":1,"aal2":2},"max_age":300,"skew":30}}' '{"aal1":1,"aal2":2}'
run; check S6 1 "$RC" "required_acr absent entirely" "is absent or empty" "$OUT"

# A realm export with no acr.loa.map cannot be reasoned about — say so, do not pass.
printf '%s\n' "$AGREED" > "$WORK/step_up.json"
printf '{"attributes":{}}\n' > "$WORK/realm.json"
OUT=$(python3 "$CK" "$WORK/step_up.json" "$WORK/realm.json" 2>&1); RC=$?
check S7 2 "$RC" "a realm with no acr.loa.map is an error, not a pass" "acr.loa.map" "$OUT"

printf '\n%d passed, %d failed\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
