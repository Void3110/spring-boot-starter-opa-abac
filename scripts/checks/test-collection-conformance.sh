#!/usr/bin/env bash
# Regression tests for check-collection-conformance.py.
#
# The suite's centre of gravity is the PRE-FIX shapes of the three real defects
# the SPA-CHALLENGE-UX review found by hand. A conformance gate that reports the
# suite clean means nothing unless it would still have caught those — so each
# must-flag arm below is a faithful reduction of the actual cell, and each is
# paired with a near-identical must-stay-quiet arm (the fixed form, or the false
# positive that kept this gate out of the repo at review time).
#
# Usage:  scripts/checks/test-collection-conformance.sh
# Exit:   0 = all green · 1 = failures (printed).
set -u
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
CC="$SCRIPT_DIR/check-collection-conformance.py"
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
PASS=0; FAIL=0

check() { local id=$1 want=$2 got=$3 desc=$4 needle=$5 out=$6 bad=""
  [ "$got" -eq "$want" ] || bad="rc=$got want=$want"
  if [ -z "$bad" ] && [ -n "$needle" ] && ! printf '%s' "$out" | grep -qF -- "$needle"; then bad="missing: $needle"; fi
  if [ -z "$bad" ]; then PASS=$((PASS+1)); printf 'PASS %-8s %s\n' "$id" "$desc"
  else FAIL=$((FAIL+1)); printf 'FAIL %-8s %s — %s\n' "$id" "$desc" "$bad"; printf '%s\n' "$out" | sed 's/^/       /'; fi; }

# collection <description> <cell-name> <url> <script-lines-json-array>
collection() {
  cat > "$WORK/c.postman_collection.json" <<JSON
{ "info": { "name": "fixture", "description": "$1", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json" },
  "item": [ { "name": "$2",
      "request": { "method": "GET", "url": { "raw": "$3" } },
      "event": [ { "listen": "test", "script": { "type": "text/javascript", "exec": $4 } } ] } ] }
JSON
}
run() { OUT=$(python3 "$CC" "$WORK/c.postman_collection.json" 2>&1); RC=$?; }

# ══ ABSENCE-ONLY — the real E32b, before its fix ══════════════════════════════
# Three not.include checks over a page that teardown had already emptied: `ids`
# was [], every check passed, and the cell asserted nothing about the leak it is
# named for.
collection "d" "E32b — the demo world never leaked into a MATRIX supervisor page" "{{base}}/catalogs?perPage=50" \
  '["const ids = pm.response.json().items.map(i => i.id);",
    "pm.test(\"no leak\", () => { pm.expect(ids).to.not.include(pm.environment.get(\"demo_prod_catalog_id\")); });"]'
run; check A1 1 "$RC" "pre-fix E32b: absence-only over a list is flagged" "ABSENCE-ONLY" "$OUT"

collection "d" "E32b — fixed form" "{{base}}/catalogs?perPage=50" \
  '["const body = pm.response.json(); const ids = body.items.map(i => i.id);",
    "pm.test(\"scoped to nothing\", () => pm.expect(body.count).to.eql(0));",
    "pm.test(\"no leak\", () => pm.expect(ids).to.not.include(pm.environment.get(\"demo_prod_catalog_id\")));"]'
run; check A2 0 "$RC" "…the count control makes it clean" "clean" "$OUT"

# The FP that an earlier draft produced: hierarchy-list E1/E2 pair every
# not.include with a POSITIVE include, which cannot pass on an empty list.
collection "d" "E1 — inherit reader lists categories" "{{base}}/categories" \
  '["const ids = pm.response.json().items.map(c => c.id);",
    "pm.test(\"sees EMEA\", () => pm.expect(ids).to.include(pm.variables.get(\"emea_category_id\")));",
    "pm.test(\"not the denied one\", () => pm.expect(ids).to.not.include(pm.variables.get(\"deny_category_id\")));"]'
run; check A3 0 "$RC" "a positive include is an adequate control" "clean" "$OUT"

# A comment is not an assertion. The prose below is written to MATCH the control
# pattern verbatim, so the arm fails unless comments are stripped before the scan —
# an earlier draft used vaguer prose that matched nothing, and the arm proved
# nothing (it survived a comments-not-stripped mutation).
collection "d" "E9 — absence only, with a comment about count" "{{base}}/catalogs" \
  '["// the control that matters is pm.expect(body.count).to.eql(0) -- but it is only prose here",
    "const ids = pm.response.json().items.map(i => i.id);",
    "pm.test(\"no leak\", () => pm.expect(ids).to.not.include(pm.environment.get(\"x\")));"]'
run; check A4 1 "$RC" "a comment mentioning count does not satisfy the control" "ABSENCE-ONLY" "$OUT"

# ══ NAME-OVERCLAIM — the real E31j, before its fix ════════════════════════════
collection "d" "E31j — IDEMPOTENT: the PRODUCT ids survived the re-seed too" "{{base}}/catalogs/{{id}}/categories" \
  '["pm.test(\"200\", () => pm.response.to.have.status(200));",
    "pm.test(\"count\", () => pm.expect(pm.response.json().count).to.eql(1));"]'
run; check N1 1 "$RC" "pre-fix E31j: claims product ids, reads /categories" "NAME-OVERCLAIM" "$OUT"

collection "d" "E31j — IDEMPOTENT: the CATEGORY ids survived the re-seed too" "{{base}}/catalogs/{{id}}/categories" \
  '["pm.test(\"count\", () => pm.expect(pm.response.json().count).to.eql(1));"]'
run; check N2 0 "$RC" "…renamed to what it reads, clean" "clean" "$OUT"

# The FP that kept this rule out at review time: scenario prose, not a claim.
collection "d" "E5 — MEMBERS UNAFFECTED: pm-demo reads the same contents" "{{base}}/catalogs/{{id}}/categories" \
  '["pm.test(\"200\", () => pm.response.to.have.status(200));"]'
run; check N3 0 "$RC" "scenario prose (MEMBERS UNAFFECTED) is not a claim" "clean" "$OUT"

# ══ UNPINNED-WINDOW ═══════════════════════════════════════════════════════════
collection "d" "E7 — the window" "{{base}}/catalogs" \
  '["const m = /max_age=\"(\\\\d+)\"/.exec(pm.response.headers.get(\"WWW-Authenticate\"));",
    "pm.test(\"has a window\", () => pm.expect(m).to.not.eql(null));"]'
run; check U1 1 "$RC" "reads max_age but pins no value" "UNPINNED-WINDOW" "$OUT"

collection "d" "E7 — the window, pinned to the shipped value" "{{base}}/catalogs" \
  '["pm.test(\"window\", () => pm.expect(pm.response.headers.get(\"WWW-Authenticate\")).to.include(\"max_age=\\\"\" + pm.environment.get(\"shipped_max_age\") + \"\\\"\"));"]'
run; check U2 0 "$RC" "pinned to shipped_max_age is clean" "clean" "$OUT"

# A literal pin is brittle but NOT this defect: it fails LOUDLY on a drilled rig.
collection "d" "E2a — a well-formed RFC 9470 challenge" "{{base}}/catalogs/{{id}}/categories" \
  '["pm.test(\"challenge\", () => pm.expect(pm.response.headers.get(\"WWW-Authenticate\")).to.include(\"max_age=\\\"300\\\"\"));"]'
run; check U3 0 "$RC" "a literal max_age pin is not the vacuous defect" "clean" "$OUT"

# ══ Per-collection waivers ════════════════════════════════════════════════════
collection "owns it\\nconformance-lint: owns-drill" "E7 — drilled read" "{{base}}/catalogs" \
  '["const m = /max_age=\"(\\\\d+)\"/.exec(pm.response.headers.get(\"WWW-Authenticate\"));",
    "pm.test(\"has a window\", () => pm.expect(m).to.not.eql(null));"]'
run; check W1 0 "$RC" "a declared owns-drill waiver silences the rule" "clean" "$OUT"

collection "nothing needs it\\nconformance-lint: owns-drill" "E1 — plain" "{{base}}/catalogs" \
  '["pm.test(\"200\", () => pm.response.to.have.status(200));"]'
run; check W2 2 "$RC" "a waiver no cell needs is an error, not decoration" "no cell needs it" "$OUT"

collection "typo\\nconformance-lint: owns-drills" "E1 — plain" "{{base}}/catalogs" \
  '["pm.test(\"200\", () => pm.response.to.have.status(200));"]'
run; check W3 2 "$RC" "an unknown waiver name is rejected" "unknown conformance-lint waiver" "$OUT"

printf '\n%d passed, %d failed\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
