#!/usr/bin/env bash
#
# test-offline.sh — the load harness's offline (U*) checks. No rig needed.
#
# Covers: script syntax (U1), the knee function against the committed synthetic stage summaries
# (U2: latency-knee, errors-knee, drop-knee, clean/no-knee, and the malformed-summary red path).
# T4/T5 plug their analysis-function cases (U3/U4) into this same harness.
#
# Usage: ./test-offline.sh   (exit 0 = all green)
set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOAD_DIR="$(cd "$SELF_DIR/.." && pwd)"
FAILURES=0

check() { # $1 label, $2 expected, $3 actual
  if [ "$2" = "$3" ]; then
    echo "  ok: $1"
  else
    echo "  FAIL: $1 — expected '$2', got '$3'" >&2
    FAILURES=$((FAILURES + 1))
  fi
}

echo "== U1: script syntax =="
bash -n "$LOAD_DIR/run-load.sh" && echo "  ok: run-load.sh parses"
bash -n "$SELF_DIR/test-offline.sh" && echo "  ok: test-offline.sh parses"

echo "== U2: knee.py against the synthetic stage summaries =="
verdict_of() { python3 "$LOAD_DIR/knee.py" "$SELF_DIR/knee-cases/$1" | sed -n 's/^verdict=\([a-z]*\) signal=\([a-z]*\).*/\1:\2/p'; }
check "clean stage passes (honest no-knee)"      "pass:none"    "$(verdict_of clean.json)"
check "p99 > 1 s is a latency knee"              "knee:latency" "$(verdict_of latency-knee.json)"
check ">1% failed requests is an errors knee"    "knee:errors"  "$(verdict_of errors-knee.json)"
check ">1% dropped iterations is a drop knee"    "knee:drops"   "$(verdict_of drop-knee.json)"

# The red path: an unreadable summary must exit 2 (an invalid run, never a silent pass/fake knee).
if python3 "$LOAD_DIR/knee.py" "$SELF_DIR/knee-cases/does-not-exist.json" >/dev/null 2>&1; then
  check "malformed summary lands red (exit 2)" "2" "0"
else
  check "malformed summary lands red (exit 2)" "2" "2"
fi

echo
if [ "$FAILURES" -gt 0 ]; then
  echo "OFFLINE TESTS RED: $FAILURES failure(s)" >&2
  exit 1
fi
echo "OFFLINE TESTS GREEN"
