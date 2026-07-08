#!/usr/bin/env bash
#
# run-load.sh — the Phase-7.2 load-testing runner (the postman-runner idiom, for k6).
#
# Measures the library's hot paths through the REAL rig (gateway -> catalog pods -> OPA ->
# user-service) and records report-only numbers with validity-only gates: no invalid number is
# ever recorded — every failure mode (rig state, load errors, thin traces, wrong fixtures) lands
# on a RED run, never a silently wrong table row. Methodology pinned in ADR 0021.
#
# Prereq: the full rig is up WITH OIDC + OPA + the user-service (the guarded posture):
#   ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2
# and k6 on the host: brew install k6
#
# First use: the `perf` realm user ships in infra/keycloak/realm-export.json — a realm-export
# change only imports on container CREATE, so recreate Keycloak once:
#   docker compose -p opa-abac-example -f infra/compose.keycloak.yaml up -d --force-recreate keycloak
#
# Usage:
#   ./run-load.sh <mode>
#
# Modes:
#   guarded                   preflight (pod state ASSERTED guarded) + seed + the guarded pass
#   baseline                  preflight (pod state ASSERTED unguarded: ENABLE_OPA=0) + seed + the baseline pass
#   full                      guarded pass -> redeploy baseline -> baseline pass -> RESTORE the guarded rig
#   ceiling                   the partial-eval list ladder: LADDER stages of LADDER_DURATION seconds,
#                             knee per ADR 0021 §5 (p99 > 1 s OR >1% failed/dropped), early stop at the
#                             knee, honest "no knee within the ladder" otherwise
#   fault-supplier-transient  three-phase timeline (PHASE s healthy/fault/recovery) on the B3 stub rig;
#                             the fault phase recreates the stub in STUB_MODE=transient
#   fault-supplier-down       same timeline; the fault phase recreates the stub in STUB_MODE=down
#                             (typed fast 403s expected — B2's wall under load)
#   fault-opa                 same timeline on the canonical guarded rig; the fault phase is a real
#                             docker pause on the OPA container (the nastier hang), unpaused after
#                             All fault modes ALWAYS restore the guarded rig (trap on exit).
#
# Knobs (env):
#   RATE=50          arrival rate, req/s (constant-arrival-rate; identical across passes)
#   DURATION=120     measured window per scenario, seconds
#   WARMUP=60        discarded warm-up invocation length, seconds
#   REPS=1           measured-run repetitions (the official baseline uses REPS=3, medians)
#   FIXTURE_ROWS=1000  seeded category count under the load catalog
#   LADDER=10,25,50,100,150,200  ceiling-mode stages, req/s
#   LADDER_DURATION=60  ceiling-mode per-stage window, seconds (ADR-pinned 60 for the official run)
#   PHASE=60         fault-mode phase length, seconds (ADR-pinned 60; shorter for smokes)
#   KEEP_FIXTURES=0  1 = skip the teardown-on-green (keep the dddd… fixtures)
#
# Fixtures + identity (the registry, scripts/postman/README.md):
#   catalog dddddddd-dddd-dddd-dddd-dddddddddddd  (the dddd… reserved load prefix; teardown-on-green)
#   realm user `perf` (password perf) — the RESERVED load identity; bound to the load team with a
#   tag-gated read/write role (region=emea, ANY_OF) so the partial-eval residual DISCRIMINATES
#   (ADR 0021 §4): the load catalog is tagged emea; category tags cycle emea/apac/amer.
#
set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SELF_PATH="$SELF_DIR/$(basename "${BASH_SOURCE[0]}")"
REPO_ROOT="$(cd "$SELF_DIR/../.." && pwd)"
cd "$SELF_DIR"

# --- config (override via env) -----------------------------------------------
NETWORK="${COMPOSE_NETWORK:-opa-abac-example_default}"
KEYCLOAK_TOKEN_URL="${KEYCLOAK_TOKEN_URL:-http://keycloak:8888/realms/catalog-demo/protocol/openid-connect/token}"
CLIENT_ID="${CLIENT_ID:-catalog-gateway}"
CLIENT_SECRET="${CLIENT_SECRET:-catalog-gateway-secret}"
GATEWAY="${GATEWAY:-http://localhost:9085}"
USER_SERVICE="${USER_SERVICE:-http://localhost:28090}"
PG_CONTAINER="${PG_CONTAINER:-opa-abac-postgres}"
UM_PG_CONTAINER="${UM_PG_CONTAINER:-opa-abac-usermgmt-postgres}"
OPA_CONTAINER="${OPA_CONTAINER:-opa-abac-opa}"
RESULTS_DIR="$SELF_DIR/results"
APISIX_ADMIN="${APISIX_ADMIN:-http://localhost:9180}"
APISIX_API_KEY="${APISIX_API_KEY:-edd1c9f034335f136f87ad84b625c8f1}"

# The load identity (registry-reserved — no matrix may bind or assert on it).
PERF_USER="${PERF_USER:-perf}"
PERF_PASS="${PERF_PASS:-perf}"

# The load fixture set (registry-reserved dddd… prefix).
LOAD_CATALOG_ID="dddddddd-dddd-dddd-dddd-dddddddddddd"
LOAD_TEAM_NAME="Load test team"
LOAD_ROLE_CODE="load"

# Knobs.
RATE="${RATE:-50}"
DURATION="${DURATION:-120}"
WARMUP="${WARMUP:-60}"
REPS="${REPS:-1}"
FIXTURE_ROWS="${FIXTURE_ROWS:-1000}"
LADDER="${LADDER:-10,25,50,100,150,200}"
LADDER_DURATION="${LADDER_DURATION:-60}"
PHASE="${PHASE:-60}"
KEEP_FIXTURES="${KEEP_FIXTURES:-0}"
COMPOSE_PROJECT="opa-abac-example"

usage() {
  # The header comment IS the doc — print the contiguous header block (skip the shebang).
  awk 'NR==1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "$SELF_PATH"
}

red() { echo "ERROR: $*" >&2; exit 1; }
note() { echo "==> $*"; }

MODE="${1:-}"
case "$MODE" in
  -h|--help|help|"") usage; [ -n "$MODE" ] || exit 1; exit 0 ;;
  guarded|baseline|full|ceiling|fault-supplier-transient|fault-supplier-down|fault-opa) ;;
  *) usage >&2; red "unknown mode '$MODE'." ;;
esac

for knob in RATE DURATION WARMUP REPS FIXTURE_ROWS LADDER_DURATION PHASE; do
  [[ "${!knob}" =~ ^[0-9]+$ ]] || red "$knob must be a positive integer (got '${!knob}')."
done
[[ "$LADDER" =~ ^[0-9]+(,[0-9]+)*$ ]] || red "LADDER must be a comma-separated list of rates (got '$LADDER')."

# --- preflight (abort red with the actionable command — never run on a wrong rig) ---
preflight() {
  command -v k6 >/dev/null 2>&1 || red "k6 not found. Install with: brew install k6"
  command -v docker >/dev/null 2>&1 || red "docker not found — this rig runs on Docker Desktop."

  curl -s -o /dev/null --max-time 3 "$GATEWAY" \
    || red "gateway unreachable at $GATEWAY. Bring the rig up: ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2"
  curl -sf --max-time 3 "$USER_SERVICE/actuator/health" 2>/dev/null | grep -q '"status":"UP"' \
    || red "user-service not healthy at $USER_SERVICE — the rig needs ENABLE_USER_SERVICE=1 (role resolution). Bring it up: ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2"

  PODS=()
  while IFS= read -r name; do PODS+=("$name"); done \
    < <(docker ps --format '{{.Names}}' | grep -E '^catalog-[0-9]+$' | sort)
  [ "${#PODS[@]}" -ge 1 ] || red "no catalog pods running. Bring the rig up: ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2"
  note "preflight: k6 $(k6 version 2>/dev/null | head -n1 | sed 's/k6 //;s/ .*//'), gateway up, user-service healthy, pods: ${PODS[*]}"
}

# Assert the ACTUAL pod state matches the pass — docker exec env probe, never trust-the-flag.
# $1 = expected OPA_ABAC_ENABLED ("true" for guarded, "false" for baseline).
# $2 = expected role source: "usermgmt" (default) or "resolve-stub" (the supplier-fault rig,
#      where resolving from the B3 stub is the DELIBERATE posture — everywhere else it poisons).
assert_pod_state() {
  local expected="$1" source="${2:-usermgmt}" pod v
  local source_url="http://$source:8080"
  for pod in "${PODS[@]}"; do
    v="$(docker exec "$pod" printenv OPA_ABAC_ENABLED 2>/dev/null || echo '<unset>')"
    if [ "$v" != "$expected" ]; then
      if [ "$expected" = "false" ]; then
        red "pod $pod has OPA_ABAC_ENABLED=$v but this pass needs the UNGUARDED baseline rig.
  Redeploy: ENABLE_OIDC=1 ENABLE_OPA=0 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods ${#PODS[@]}
  (or run './run-load.sh full', which orchestrates both passes and restores the guarded rig)"
      else
        red "pod $pod has OPA_ABAC_ENABLED=$v but this pass needs the GUARDED rig.
  Redeploy: ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods ${#PODS[@]}"
      fi
    fi
    v="$(docker exec "$pod" printenv CATALOG_USER_SERVICE_BASE_URL 2>/dev/null || echo '<unset>')"
    [ "$v" = "$source_url" ] \
      || red "pod $pod resolves roles from '$v' (this pass expects $source_url).
  Redeploy the pass's rig (or the guarded default: ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods ${#PODS[@]})"
  done
  # Every pass needs a live, unpaused OPA at its START: the app gate when guarded, the gateway's
  # coarse allow-all opa plugin always (the identical-gateway invariant, ADR 0021 §2). The
  # fault-opa mode pauses it MID-run, deliberately — never at a pass boundary.
  docker ps --format '{{.Names}}' | grep -q "^${OPA_CONTAINER}\$" \
    || red "OPA container '$OPA_CONTAINER' not running — every pass crosses the gateway opa plugin."
  [ "$(docker inspect -f '{{.State.Paused}}' "$OPA_CONTAINER")" = "false" ] \
    || red "OPA container '$OPA_CONTAINER' is PAUSED (a leftover fault run?). Unpause: docker unpause $OPA_CONTAINER"
  note "pod state asserted: OPA_ABAC_ENABLED=$expected on ${#PODS[@]} pod(s), role source = $source, OPA up"
}

# Assert the GATEWAY posture — probed via the admin API, never trusted. The two-pass delta is only
# valid if the gateway is byte-identical across passes: bearer validation (openid-connect) AND the
# coarse allow-all opa plugin present in BOTH. `deploy.sh up` with ENABLE_OPA=0 drops the opa plugin
# from the route, so the baseline deploy re-adds it via init-routes.sh (the same committed mechanism)
# — this probe is what catches a pass where that rewire didn't happen.
assert_gateway_posture() {
  local route
  route="$(curl -s --max-time 5 -H "X-API-KEY: $APISIX_API_KEY" "$APISIX_ADMIN/apisix/admin/routes/catalog-all" || true)"
  [ -n "$route" ] || red "APISIX admin unreachable at $APISIX_ADMIN — cannot assert the gateway posture."
  printf '%s' "$route" | grep -q '"openid-connect"' \
    || red "the catalog-all route carries no openid-connect plugin — the rig must run ENABLE_OIDC=1."
  printf '%s' "$route" | grep -q '"opa"' \
    || red "the catalog-all route carries no gateway opa plugin — the posture would differ between passes.
  Re-wire it: ENABLE_OIDC=1 ENABLE_OPA=1 ENABLE_USER_SERVICE=1 bash $REPO_ROOT/infra/apisix/init-routes.sh
  (or run './run-load.sh full', which orchestrates both passes and keeps the gateway identical)"
  note "gateway posture asserted: openid-connect + opa plugin on catalog-all"
}

# --- in-network token minting (issuer must match what APISIX validates) --------
mint_token() {
  local user="$1" pass="$2" json
  json="$(docker run --rm --network "$NETWORK" curlimages/curl -s \
    -X POST "$KEYCLOAK_TOKEN_URL" \
    -d grant_type=password -d "client_id=$CLIENT_ID" -d "client_secret=$CLIENT_SECRET" \
    -d "username=$user" -d "password=$pass" || true)"
  printf '%s' "$json" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p'
}

token_sub() {
  local tok="$1" payload
  payload="$(printf '%s' "$tok" | cut -d. -f2 | tr '_-' '/+')"
  while [ $(( ${#payload} % 4 )) -ne 0 ]; do payload="${payload}="; done
  printf '%s' "$payload" | base64 -d 2>/dev/null | sed -n 's/.*"sub":"\([^"]*\)".*/\1/p'
}

post_json() { curl -s -X POST "$1" -H 'Content-Type: application/json' -d "$2"; }
json_field() { python3 -c "import sys,json; print(json.load(sys.stdin)['$1'])"; }

mint_perf_token() {
  note "minting the '$PERF_USER' load-identity token in-network ($NETWORK) ..."
  PERF_TOKEN="$(mint_token "$PERF_USER" "$PERF_PASS")"
  [ -n "$PERF_TOKEN" ] || red "no token for '$PERF_USER'. Either the rig is up without OIDC, or the realm predates the perf user —
  recreate Keycloak to re-import the realm export:
  docker compose -p opa-abac-example -f $REPO_ROOT/infra/compose.keycloak.yaml up -d --force-recreate keycloak"
  PERF_SUB="$(token_sub "$PERF_TOKEN")"
  [ -n "$PERF_SUB" ] || red "could not decode the 'sub' claim from the perf token."
  note "load identity: $PERF_USER (sub $PERF_SUB)"
}

# --- bulk fixture seed (deterministic, idempotent, count-asserted) -------------
# 1 load catalog (tagged region=emea so the tag-gated load role reads it) + FIXTURE_ROWS categories
# under the reserved dddd… prefix, tags cycling emea/apac/amer so the partial-eval residual
# DISCRIMINATES (ADR 0021 §4). Bulk SQL generate_series — the one-row bootstrap API would take minutes.
seed_fixtures() {
  note "seeding the load catalog $LOAD_CATALOG_ID + $FIXTURE_ROWS categories (bulk SQL) ..."
  local cat_path="catalog_$(printf '%s' "$LOAD_CATALOG_ID" | tr -d '-')"
  docker exec -i "$PG_CONTAINER" psql -U catalog -d catalog -v ON_ERROR_STOP=1 >/dev/null <<SQL
INSERT INTO catalog (id, name, created_at, version, tags, path)
VALUES ('$LOAD_CATALOG_ID', 'Load test catalog', now(), 0,
        '{"region": ["emea"]}'::jsonb, CAST('$cat_path' AS ltree))
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, tags = EXCLUDED.tags, path = EXCLUDED.path;

-- Deterministic re-seed: wipe and repopulate, so a prior KEEP_FIXTURES run can't skew the count.
DELETE FROM category WHERE catalog_id = '$LOAD_CATALOG_ID';
INSERT INTO category (id, catalog_id, name, version, created_at, tags, path)
SELECT
  CAST('dddddddd-dddd-dddd-dddd-' || lpad(i::text, 12, '0') AS uuid),
  '$LOAD_CATALOG_ID',
  'Load category ' || i,
  0, now(),
  (CASE i % 3 WHEN 1 THEN '{"region": ["emea"]}'
              WHEN 2 THEN '{"region": ["apac"]}'
              ELSE        '{"region": ["amer"]}' END)::jsonb,
  CAST('$cat_path.category_dddddddddddddddddddd' || lpad(i::text, 12, '0') AS ltree)
FROM generate_series(1, $FIXTURE_ROWS) AS i;
SQL

  # Post-seed count assertion — a stale fixture population must land red, never a wrong table row.
  local count
  count="$(docker exec -i "$PG_CONTAINER" psql -U catalog -d catalog -tAc \
    "SELECT count(*) FROM category WHERE catalog_id = '$LOAD_CATALOG_ID';")"
  [ "$count" = "$FIXTURE_ROWS" ] || red "post-seed count assert failed: $count categories under the load catalog (expected $FIXTURE_ROWS)."
  note "seeded: $count categories (tags cycling emea/apac/amer), count asserted."

  # Self-reset the load team (the bootstrap API is create-oriented; a leftover team from a
  # KEEP_FIXTURES run must not accumulate), then bootstrap the perf membership: one team-scoped
  # role, read/write on catalog+category, TAG-GATED to region=emea so the list residual discriminates.
  docker exec -i "$UM_PG_CONTAINER" psql -U usermgmt -d usermgmt -v ON_ERROR_STOP=1 >/dev/null <<SQL
DELETE FROM team WHERE target_type = 'catalog' AND target_id = '$LOAD_CATALOG_ID';
SQL
  note "bootstrapping the load team + the '$PERF_USER' membership (role '$LOAD_ROLE_CODE', region=emea ANY_OF) ..."
  local perf_uid team_id
  perf_uid="$(post_json "$USER_SERVICE/internal/bootstrap/users" \
    "{\"subject\":\"$PERF_SUB\",\"displayName\":\"$PERF_USER\"}" | json_field userId)"
  team_id="$(post_json "$USER_SERVICE/internal/bootstrap/teams" \
    "{\"name\":\"$LOAD_TEAM_NAME\",\"targetType\":\"catalog\",\"targetId\":\"$LOAD_CATALOG_ID\"}" | json_field teamId)"
  post_json "$USER_SERVICE/internal/bootstrap/custom-roles" \
    "{\"teamId\":\"$team_id\",\"code\":\"$LOAD_ROLE_CODE\",\"roleLevel\":20,\"permissions\":{\"catalog\":[\"READ\",\"WRITE\"],\"category\":[\"READ\",\"WRITE\"]},\"requiredTags\":{\"region\":[\"emea\"]},\"matchMode\":\"ANY_OF\"}" >/dev/null
  post_json "$USER_SERVICE/internal/bootstrap/memberships" \
    "{\"teamId\":\"$team_id\",\"userId\":\"$perf_uid\",\"roleCode\":\"$LOAD_ROLE_CODE\"}" >/dev/null
  note "load team $team_id governs $LOAD_CATALOG_ID ($PERF_USER bound to '$LOAD_ROLE_CODE')."

  # Canary probe: one gateway GET of the load catalog as perf, BEFORE any load. The bootstrap posts
  # above are fire-and-forget (the matrix idiom) — this closes the silently-failed-bootstrap edge at
  # seed time: a broken token/resolve/decide chain lands red HERE, never inside a measurement window.
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 \
    -H "Authorization: Bearer $PERF_TOKEN" "$GATEWAY/api/v1/catalogs/$LOAD_CATALOG_ID")"
  [ "$code" = "200" ] || red "canary probe: GET the load catalog as $PERF_USER got HTTP $code (want 200) —
  the seeded ACL chain (token -> role resolve -> OPA decide) is broken; nothing will be measured."
  note "canary probe: perf reads the load catalog through the gateway (200)."
}

# --- teardown (success only — a failed run keeps its fixtures for debugging) ---
# KEEP_FIXTURES=1 skips it. Scoped STRICTLY to the registry-owned dddd… fixtures: the team by its
# target id (memberships + custom roles ride the FK cascades) and the load catalog (categories
# cascade). The perf app_user profile row stays — identity profiles are never deleted (README rule).
teardown_fixtures() {
  if [ "$KEEP_FIXTURES" = "1" ]; then
    note "KEEP_FIXTURES=1 — keeping the dddd… fixtures."
    return 0
  fi
  note "teardown: removing the dddd… load fixtures (KEEP_FIXTURES=1 keeps them) ..."
  docker exec -i "$UM_PG_CONTAINER" psql -U usermgmt -d usermgmt -v ON_ERROR_STOP=1 >/dev/null <<SQL
DELETE FROM team WHERE target_type = 'catalog' AND target_id = '$LOAD_CATALOG_ID';
SQL
  docker exec -i "$PG_CONTAINER" psql -U catalog -d catalog -v ON_ERROR_STOP=1 >/dev/null <<SQL
DELETE FROM catalog WHERE id = '$LOAD_CATALOG_ID';
SQL
}

# --- k6 orchestration (per scenario: discarded warm-up invocation, then REPS measured runs) -----
# k6 thresholds are validity gates: a non-zero k6 exit aborts the run red (set -e) and nothing is
# recorded — in full mode the EXIT trap below restores the guarded rig first.
k6_run() { # $1 scenario file, $2 duration (s), $3 summary-export path, [$4 rate], [$5 LADDER_STAGE]
  k6 run --quiet \
    -e RATE="${4:-$RATE}" -e DURATION="$2" -e LADDER_STAGE="${5:-0}" \
    -e GATEWAY="$GATEWAY" -e PERF_TOKEN="$PERF_TOKEN" -e LOAD_CATALOG_ID="$LOAD_CATALOG_ID" \
    --summary-export "$3" \
    "$SELF_DIR/scenarios/$1"
}

run_scenario_pass() { # $1 scenario basename (no .js), $2 pass name (guarded|baseline)
  local scenario="$1" pass="$2" rep window_start window_end
  note "[$pass] warm-up: $scenario at RATE=$RATE for ${WARMUP}s (discarded) ..."
  k6_run "$scenario.js" "$WARMUP" "$RUN_DIR/$scenario-$pass-warmup.summary.json"
  window_start="$(date +%s)"
  for rep in $(seq 1 "$REPS"); do
    note "[$pass] measured run $rep/$REPS: $scenario at RATE=$RATE for ${DURATION}s ..."
    k6_run "$scenario.js" "$DURATION" "$RUN_DIR/$scenario-$pass-rep$rep.summary.json"
  done
  window_end="$(date +%s)"
  note "[$pass] $scenario: $REPS measured run(s) recorded in $RUN_DIR/"
  # The amplification ratio is a guarded-pass-only metric (ADR 0021 §6), attributed over exactly
  # this scenario's measured window (scenarios run sequentially, so windows never overlap).
  if [ "$pass" = "guarded" ]; then
    run_amplification "$scenario" "$window_start" "$window_end"
  fi
}

scenario_operation() { # the catalog server-span name Jaeger indexes the scenario's traces under
  case "$1" in
    gate-overhead) echo "GET /api/v1/catalogs/{catalogId}" ;;
    list-filter|enrichment) echo "GET /api/v1/catalogs/{catalogId}/categories" ;;
    *) red "no Jaeger operation mapping for scenario '$1'" ;;
  esac
}

run_amplification() { # $1 scenario, $2 window start (epoch s), $3 window end
  local scenario="$1" offered floor
  # The sample floor self-scales: half the offered requests, capped at the ADR's ~500, floored at
  # 20 for short smokes. Below the floor the window is INVALID (exit 2) — never extrapolated.
  offered=$(( RATE * DURATION * REPS ))
  floor=$(( offered / 2 ))
  [ "$floor" -gt 500 ] && floor=500
  [ "$floor" -lt 20 ] && floor=20
  sleep 5   # let the OTEL batch exporters flush the tail of the window into Jaeger
  note "amplification: attributing '$scenario' traces (floor $floor) ..."
  python3 "$SELF_DIR/amplification.py" \
    --scenario "$scenario" \
    --operation "$(scenario_operation "$scenario")" \
    --window-start "$2" --window-end "$(( $3 + 5 ))" \
    --min-traces "$floor" \
    --out "$RUN_DIR/$scenario-amplification" \
    || red "amplification for '$scenario': invalid trace window — nothing recorded."
}

# --- the ceiling ladder (ceiling mode) — knee per ADR 0021 §5, evaluated by knee.py -------------
# 60 s (LADDER_DURATION) constant-rate stages up the LADDER; each stage's summary is judged by
# knee.py (p99 > 1 s OR >1% failed/dropped — the pinned definition, no alternate criteria here).
# Early stop at the knee; ceiling = the last passing stage; an honest "no knee within the ladder"
# when nothing breaks. Stage k6 runs use LADDER_STAGE=1: saturation signals are recorded DATA, and
# only auth failures (broken rig/ACL) exit red. knee.py exit 2 (unreadable summary) aborts red.
run_ceiling_ladder() {
  local stages=() rate ceiling="" knee="" signal="" line verdict
  IFS=',' read -ra stages <<< "$LADDER"
  note "ceiling ladder: stages ${LADDER} req/s x ${LADDER_DURATION}s (warm-up ${WARMUP}s at ${stages[0]} req/s)"
  k6_run "list-filter.js" "$WARMUP" "$RUN_DIR/list-filter-ladder-warmup.summary.json" "${stages[0]}" 1
  : > "$RUN_DIR/ceiling-stages.txt"
  for rate in "${stages[@]}"; do
    note "[ceiling] stage: $rate req/s for ${LADDER_DURATION}s ..."
    k6_run "list-filter.js" "$LADDER_DURATION" "$RUN_DIR/list-filter-stage-$rate.summary.json" "$rate" 1
    line="$(python3 "$SELF_DIR/knee.py" "$RUN_DIR/list-filter-stage-$rate.summary.json")" \
      || red "stage $rate: knee.py could not evaluate the summary — invalid run, nothing recorded."
    echo "rate=$rate $line" | tee -a "$RUN_DIR/ceiling-stages.txt"
    verdict="$(printf '%s' "$line" | sed -n 's/^verdict=\([a-z]*\).*/\1/p')"
    if [ "$verdict" = "knee" ]; then
      knee="$rate"
      signal="$(printf '%s' "$line" | sed -n 's/.*signal=\([a-z]*\).*/\1/p')"
      break
    fi
    ceiling="$rate"
  done
  {
    echo ""
    echo "=== partial-eval list ceiling (ladder ${LADDER} req/s, ${LADDER_DURATION}s stages) ==="
    if [ -n "$knee" ]; then
      echo "knee at $knee req/s (signal: $signal); ceiling = ${ceiling:-none — the FIRST stage knelt} req/s"
    else
      echo "no knee within the ladder (honest result); ceiling = $ceiling req/s (the last stage tried)"
    fi
  } | tee "$RUN_DIR/ceiling-verdict.txt"
  python3 - "$RUN_DIR" "$LADDER" "$LADDER_DURATION" "${ceiling:-}" "${knee:-}" "${signal:-}" <<'PY'
import json, sys, pathlib
run_dir, ladder, dur, ceiling, knee, signal = sys.argv[1:7]
stages = []
for raw in (pathlib.Path(run_dir) / "ceiling-stages.txt").read_text().splitlines():
    kv = dict(p.split("=", 1) for p in raw.split())
    stages.append({k: (float(v) if k not in ("verdict", "signal") else v) for k, v in kv.items()})
out = {"ladder_rps": [int(x) for x in ladder.split(",")], "stage_duration_s": int(dur),
       "stages": stages, "ceiling_rps": int(ceiling) if ceiling else None,
       "knee_rps": int(knee) if knee else None, "knee_signal": signal or None}
(pathlib.Path(run_dir) / "ceiling.json").write_text(json.dumps(out, indent=2) + "\n")
print(f"written: {run_dir}/ceiling.json")
PY
}

# --- the two-pass rig flip (full mode) — deploy.sh mechanisms only, always restored ------------
# The canonical posture for both passes: ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 (+ defaults
# ENABLE_TRACING=1), pods --pods N as found. Only ENABLE_OPA flips. Because deploy.sh with
# ENABLE_OPA=0 also drops the gateway's coarse allow-all opa plugin from the route, the baseline
# deploy re-runs init-routes.sh with ENABLE_OPA=1 — the gateway stays byte-identical across passes
# (ADR 0021 §2: the app-side library gate is the ONLY variable); assert_gateway_posture verifies.
deploy_rig() { # $1 = guarded | baseline
  local shape="$1"
  if [ "$shape" = "baseline" ]; then
    note "redeploying the rig UNGUARDED (ENABLE_OPA=0 — pods only; the gateway keeps its opa plugin) ..."
    ENABLE_OIDC=1 ENABLE_OPA=0 ENABLE_USER_SERVICE=1 "$REPO_ROOT/deploy.sh" up --pods "${#PODS[@]}"
    note "re-wiring the gateway opa plugin (identical gateway across passes) ..."
    ENABLE_OIDC=1 ENABLE_OPA=1 ENABLE_TRACING=1 ENABLE_USER_SERVICE=1 \
      APISIX_ADMIN="$APISIX_ADMIN" APISIX_API_KEY="$APISIX_API_KEY" \
      bash "$REPO_ROOT/infra/apisix/init-routes.sh"
  else
    note "deploying the guarded rig (canonical posture) ..."
    ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 "$REPO_ROOT/deploy.sh" up --pods "${#PODS[@]}"
  fi
}

# The fail-closed edge of the harness itself: a red run must NEVER leave the rig unguarded,
# stub-wired, or with OPA paused. Armed before any rig mutation (the baseline flip, a fault
# injection, the stub-rig deploy); disarmed only after the explicit restore is asserted. The
# restore is unconditional-cleanup shaped: unpause OPA if paused, drop the stub, redeploy guarded.
RIG_FLIPPED=0
restore_guarded_on_exit() {
  local rc=$?
  trap - EXIT
  if [ "$RIG_FLIPPED" = "1" ]; then
    echo "==> EXIT trap: the rig may be faulted/unguarded — restoring the guarded posture ..." >&2
    docker unpause "$OPA_CONTAINER" >/dev/null 2>&1 || true
    docker compose -p "$COMPOSE_PROJECT" -f "$REPO_ROOT/infra/compose.resilience-stub.yaml" down >/dev/null 2>&1 || true
    if ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 "$REPO_ROOT/deploy.sh" up --pods "${#PODS[@]}"; then
      echo "==> EXIT trap: guarded rig restored." >&2
    else
      echo "FATAL: could not restore the guarded rig. Restore manually:" >&2
      echo "  docker unpause $OPA_CONTAINER; ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods ${#PODS[@]}" >&2
    fi
  fi
  exit "$rc"
}

# --- the three-phase fault timeline (T5, ADR 0021 §7) -------------------------------------------
# PHASE s healthy -> PHASE s faulted -> PHASE s recovery, at RATE, with the fault injected/cleared
# by the runner at the REAL boundaries it records. The k6 stream (--out json) is sliced per phase
# by phases.py, whose per-phase validity (typed fast denials, completed recovery) decides red.
# The supplier fault is the B3 stub recreated into the fault mode mid-run; the OPA fault is a real
# docker pause. Restore (unpause / stub-down / guarded redeploy) is trap-armed the whole time.
stub_compose_up() { # $1 = STUB_MODE
  STUB_MODE="$1" STUB_FAILS=1 docker compose -p "$COMPOSE_PROJECT" \
    -f "$REPO_ROOT/infra/compose.resilience-stub.yaml" up -d --force-recreate 2>/dev/null
}

run_fault_timeline() { # $1 = opa | supplier-transient | supplier-down
  local mode="$1" total=$(( PHASE * 3 ))
  local stream="$RUN_DIR/resilience-$mode.ndjson"
  local fault_start fault_end k6_pid

  trap restore_guarded_on_exit EXIT
  RIG_FLIPPED=1
  if [ "$mode" = "opa" ]; then
    deploy_rig "guarded"
    assert_pod_state "true" "usermgmt"
  else
    note "deploying the resilience-stub rig (guarded pods, role source = the B3 stub, STUB_MODE=up) ..."
    ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ENABLE_RESILIENCE_STUB=1 STUB_MODE=up \
      "$REPO_ROOT/deploy.sh" up --pods "${#PODS[@]}"
    assert_pod_state "true" "resolve-stub"
  fi
  assert_gateway_posture
  mint_perf_token
  seed_fixtures

  note "[fault-$mode] warm-up at RATE=$RATE for ${WARMUP}s (healthy rig, discarded) ..."
  k6_run "resilience.js" "$WARMUP" "$RUN_DIR/resilience-$mode-warmup.summary.json"

  note "[fault-$mode] three-phase run: ${PHASE}s healthy -> ${PHASE}s fault -> ${PHASE}s recovery at RATE=$RATE ..."
  k6 run --quiet \
    -e RATE="$RATE" -e DURATION="$total" \
    -e GATEWAY="$GATEWAY" -e PERF_TOKEN="$PERF_TOKEN" -e LOAD_CATALOG_ID="$LOAD_CATALOG_ID" \
    --summary-export "$RUN_DIR/resilience-$mode.summary.json" \
    --out "json=$stream" \
    "$SELF_DIR/scenarios/resilience.js" &
  k6_pid=$!

  sleep "$PHASE"
  # fault_start BEFORE the injection: boundary requests land in the fault bucket, so the healthy
  # phase stays provably clean (the validity check demands it).
  fault_start="$(date +%s)"
  case "$mode" in
    opa) note "[fault-$mode] injecting: docker pause $OPA_CONTAINER"; docker pause "$OPA_CONTAINER" ;;
    *)   note "[fault-$mode] injecting: stub -> STUB_MODE=${mode#supplier-}"; stub_compose_up "${mode#supplier-}" ;;
  esac

  sleep "$PHASE"
  case "$mode" in
    opa) note "[fault-$mode] clearing: docker unpause $OPA_CONTAINER"; docker unpause "$OPA_CONTAINER" ;;
    *)   note "[fault-$mode] clearing: stub -> STUB_MODE=up"; stub_compose_up "up" ;;
  esac
  # fault_end AFTER the clear completes: requests failing during the clear stay in the fault bucket.
  fault_end="$(date +%s)"

  if ! wait "$k6_pid"; then
    red "[fault-$mode] k6 exited non-zero (dropped iterations — the offered rate was not kept) — invalid timeline."
  fi

  note "[fault-$mode] slicing the stream by the recorded fault boundaries ..."
  python3 "$SELF_DIR/phases.py" \
    --stream "$stream" --fault-start "$fault_start" --fault-end "$fault_end" \
    --mode "$mode" \
    --out "$RUN_DIR/resilience-$mode-phases" \
    || red "[fault-$mode] per-phase validity failed — red run, nothing recorded as a result."

  # Restore the guarded rig (the trap covers every red path above).
  if [ "$mode" = "opa" ]; then
    assert_pod_state "true" "usermgmt"     # unpaused + healthy, probed not trusted
  else
    note "[fault-$mode] restoring the guarded rig (dropping the stub role source) ..."
    docker compose -p "$COMPOSE_PROJECT" -f "$REPO_ROOT/infra/compose.resilience-stub.yaml" down >/dev/null 2>&1 || true
    deploy_rig "guarded"
    assert_pod_state "true" "usermgmt"
  fi
  assert_gateway_posture
  RIG_FLIPPED=0
  trap - EXIT
  teardown_fixtures
}

# --- the delta block (full mode): REPS medians, guarded vs baseline, absolute + relative --------
compute_delta() {
  note "computing the gate-overhead delta (REPS=$REPS medians) ..."
  python3 - "$RUN_DIR" "$REPS" "$RATE" "$DURATION" <<'PY'
import json, statistics, sys, pathlib
run_dir, reps, rate, duration = pathlib.Path(sys.argv[1]), int(sys.argv[2]), sys.argv[3], sys.argv[4]
STATS = ("med", "p(95)", "p(99)")

def pass_stats(name):
    per_stat = {}
    for stat in STATS:
        vals = []
        for rep in range(1, reps + 1):
            with open(run_dir / f"gate-overhead-{name}-rep{rep}.summary.json") as f:
                vals.append(json.load(f)["metrics"]["http_req_duration"][stat])
        per_stat[stat] = statistics.median(vals)
    return per_stat

guarded, baseline = pass_stats("guarded"), pass_stats("baseline")
delta = {
    "scenario": "gate-overhead", "rate_rps": int(rate), "duration_s": int(duration), "reps": reps,
    "guarded_ms": guarded, "baseline_ms": baseline,
    "delta_ms": {s: round(guarded[s] - baseline[s], 2) for s in STATS},
    "delta_pct": {s: round((guarded[s] - baseline[s]) / baseline[s] * 100, 1) if baseline[s] else None
                  for s in STATS},
}
out = run_dir / "gate-overhead-delta.json"
out.write_text(json.dumps(delta, indent=2) + "\n")
label = {"med": "p50", "p(95)": "p95", "p(99)": "p99"}
print(f"\n=== gate-overhead delta (RATE={rate} req/s, {duration}s x REPS={reps}, medians) ===")
print(f"{'':6} {'guarded':>10} {'baseline':>10} {'delta':>10} {'delta%':>8}")
for s in STATS:
    print(f"{label[s]:6} {guarded[s]:9.2f}ms {baseline[s]:9.2f}ms {delta['delta_ms'][s]:9.2f}ms "
          f"{delta['delta_pct'][s]:7.1f}%")
print(f"written: {out}\n")
PY
}

# --- modes ---------------------------------------------------------------------
RUN_ID="$(date +%Y%m%d-%H%M%S)-$MODE"
RUN_DIR="$RESULTS_DIR/$RUN_ID"
mkdir -p "$RUN_DIR"

case "$MODE" in
  guarded)
    preflight
    assert_pod_state "true"
    assert_gateway_posture
    mint_perf_token
    seed_fixtures
    run_scenario_pass "gate-overhead" "guarded"
    run_scenario_pass "list-filter" "guarded"
    run_scenario_pass "enrichment" "guarded"
    teardown_fixtures
    ;;
  ceiling)
    preflight
    assert_pod_state "true"      # the ladder measures the guarded PE path only
    assert_gateway_posture
    mint_perf_token
    seed_fixtures                # post-seed count assert = the fixture dependency gate before stage 1
    run_ceiling_ladder
    teardown_fixtures
    ;;
  baseline)
    preflight
    assert_pod_state "false"   # aborts red BEFORE any load if the rig is guarded
    assert_gateway_posture
    mint_perf_token
    seed_fixtures
    run_scenario_pass "gate-overhead" "baseline"
    teardown_fixtures
    ;;
  full)
    preflight
    # Pass 1 — guarded, on the canonical posture (deployed, not assumed: the delta is only valid
    # when both passes run the same flag set, so full owns the rig shape for its duration).
    deploy_rig "guarded"
    assert_pod_state "true"
    assert_gateway_posture
    mint_perf_token
    seed_fixtures
    run_scenario_pass "gate-overhead" "guarded"
    run_scenario_pass "list-filter" "guarded"   # steady list latency (guarded-only scenario)
    run_scenario_pass "enrichment" "guarded"    # the fan-out page (guarded-only scenario)
    # Pass 2 — baseline. From here until the restore completes, a red exit must restore guarded.
    trap restore_guarded_on_exit EXIT
    RIG_FLIPPED=1
    deploy_rig "baseline"
    assert_pod_state "false"
    assert_gateway_posture
    mint_perf_token
    run_scenario_pass "gate-overhead" "baseline"
    # Restore the guarded rig (also on any red path above, via the trap).
    deploy_rig "guarded"
    assert_pod_state "true"
    assert_gateway_posture
    RIG_FLIPPED=0
    trap - EXIT
    compute_delta
    teardown_fixtures
    ;;
  fault-opa)
    preflight
    run_fault_timeline "opa"
    ;;
  fault-supplier-transient)
    preflight
    run_fault_timeline "supplier-transient"
    ;;
  fault-supplier-down)
    preflight
    run_fault_timeline "supplier-down"
    ;;
esac
