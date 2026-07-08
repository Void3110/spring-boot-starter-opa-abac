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


echo "== U3: amplification.py against the synthetic trace fixtures =="
AMP="python3 $LOAD_DIR/amplification.py"
out="$($AMP --scenario gate-overhead --input "$SELF_DIR/amplification-cases/gate-overhead-clean.json" --min-traces 5)"
check "clean gate-overhead attributes within bounds" "yes" "$(printf '%s' "$out" | grep -q 'resolve | 1 | 1 | 1 | within' && printf '%s' "$out" | grep -q 'decide | 1 | 1 | 1 | within' && echo yes || echo no)"
check "the markdown table renders" "yes" "$(printf '%s' "$out" | grep -q '| outbound op | pinned bound |' && echo yes || echo no)"
out="$($AMP --scenario enrichment --input "$SELF_DIR/amplification-cases/enrichment-exceeded.json" --min-traces 5)"
check "per-row resolve scaling lands as an EXCEEDED finding" "yes" "$(printf '%s' "$out" | grep -q 'resolve.*EXCEEDED' && printf '%s' "$out" | grep -q 'FINDINGS' && echo yes || echo no)"
check "batch-eval above its pinned bound is EXCEEDED too" "yes" "$(printf '%s' "$out" | grep -q 'batch-eval | 1 | 2 | 2 | EXCEEDED' && echo yes || echo no)"
if $AMP --scenario gate-overhead --input "$SELF_DIR/amplification-cases/gate-overhead-clean.json" --min-traces 50 >/dev/null 2>&1; then
  check "MIN_TRACES floor aborts red on a thin window (exit 2)" "2" "0"
else
  check "MIN_TRACES floor aborts red on a thin window (exit 2)" "2" "2"
fi

echo "== U4: phases.py against the synthetic request streams =="
PH="python3 $LOAD_DIR/phases.py --fault-start 1000000060 --fault-end 1000000120 --mode opa"
out="$($PH --stream "$SELF_DIR/phases-cases/clean-opa.ndjson" 2>/dev/null)"; rc=$?
check "clean fault run is valid (exit 0)" "0" "$rc"
check "phase attribution correct at the boundaries" "yes" "$(printf '%s' "$out" | grep -Eq '\| healthy \| 120 \| 120 \| 0 \| 0' && printf '%s' "$out" | grep -Eq '\| fault \| 120 \| 0 \| 120 \| 0' && echo yes || echo no)"
check "time-to-recovery detected" "yes" "$(printf '%s' "$out" | grep -q 'time-to-recovery: 1.5s' && echo yes || echo no)"
if $PH --stream "$SELF_DIR/phases-cases/slow-deny.ndjson" >/dev/null 2>&1; then rc=0; else rc=$?; fi
check "synthetic slow-deny fails the fault-phase validity red (exit 2)" "2" "$rc"
if $PH --stream "$SELF_DIR/phases-cases/no-recovery.ndjson" >/dev/null 2>&1; then rc=0; else rc=$?; fi
check "incomplete recovery lands red (exit 2)" "2" "$rc"

echo "== U4b: the mode-divergent transient branch (deep-review fix) =="
# The SAME all-2xx stream must be VALID under supplier-transient (retries absorb the blip;
# zero denials required) and INVALID under opa (a fault phase with no denials = no fault injected).
PHT="python3 $LOAD_DIR/phases.py --fault-start 1000000060 --fault-end 1000000120"
if $PHT --mode supplier-transient --stream "$SELF_DIR/phases-cases/transient-clean.ndjson" >/dev/null 2>&1; then rc=0; else rc=$?; fi
check "all-2xx fault phase is VALID under supplier-transient" "0" "$rc"
if $PHT --mode opa --stream "$SELF_DIR/phases-cases/transient-clean.ndjson" >/dev/null 2>&1; then rc=0; else rc=$?; fi
check "the same stream is INVALID under opa (no denials => no fault)" "2" "$rc"

echo "== U1b: the --help mode/knob surface (deep-review fix) =="
help_out="$("$LOAD_DIR/run-load.sh" --help)"
for token in guarded baseline full ceiling fault-supplier-transient fault-supplier-down fault-opa \
             RATE= DURATION= WARMUP= REPS= FIXTURE_ROWS= LADDER= LADDER_DURATION= PHASE= KEEP_FIXTURES=; do
  if printf '%s' "$help_out" | grep -q "$token"; then
    echo "  ok: --help lists $token"
  else
    echo "  FAIL: --help missing $token" >&2
    FAILURES=$((FAILURES + 1))
  fi
done
if "$LOAD_DIR/run-load.sh" bogus-mode >/dev/null 2>&1; then rc=0; else rc=$?; fi
check "unknown mode exits red" "1" "$rc"

echo
if [ "$FAILURES" -gt 0 ]; then
  echo "OFFLINE TESTS RED: $FAILURES failure(s)" >&2
  exit 1
fi
echo "OFFLINE TESTS GREEN"
