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
#   ceiling                   the partial-eval list ladder (knee detection)             [lands with T3]
#   fault-supplier-transient  three-phase fault pass, B3 stub STUB_MODE=transient       [lands with T5]
#   fault-supplier-down       three-phase fault pass, B3 stub STUB_MODE=down            [lands with T5]
#   fault-opa                 three-phase fault pass, docker pause on the OPA container [lands with T5]
#
# Knobs (env):
#   RATE=50          arrival rate, req/s (constant-arrival-rate; identical across passes)
#   DURATION=120     measured window per scenario, seconds
#   WARMUP=60        discarded warm-up invocation length, seconds
#   REPS=1           measured-run repetitions (the official baseline uses REPS=3, medians)
#   FIXTURE_ROWS=1000  seeded category count under the load catalog
#   LADDER=10,25,50,100,150,200  ceiling-mode stages, req/s                              [lands with T3]
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
KEEP_FIXTURES="${KEEP_FIXTURES:-0}"

usage() {
  # The header comment IS the doc — print the contiguous header block (skip the shebang).
  awk 'NR==1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "$SELF_PATH"
}

red() { echo "ERROR: $*" >&2; exit 1; }
note() { echo "==> $*"; }

MODE="${1:-}"
case "$MODE" in
  -h|--help|help|"") usage; [ -n "$MODE" ] || exit 1; exit 0 ;;
  guarded|baseline|full) ;;
  ceiling) red "mode 'ceiling' lands with T3 — not implemented yet." ;;
  fault-supplier-transient|fault-supplier-down|fault-opa) red "mode '$MODE' lands with T5 — not implemented yet." ;;
  *) usage >&2; red "unknown mode '$MODE'." ;;
esac

for knob in RATE DURATION WARMUP REPS FIXTURE_ROWS; do
  [[ "${!knob}" =~ ^[0-9]+$ ]] || red "$knob must be a positive integer (got '${!knob}')."
done

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
assert_pod_state() {
  local expected="$1" pod v
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
    # The role source must be the REAL user-service — a leftover B3 stub rig would poison every number.
    v="$(docker exec "$pod" printenv CATALOG_USER_SERVICE_BASE_URL 2>/dev/null || echo '<unset>')"
    [ "$v" = "http://usermgmt:8080" ] \
      || red "pod $pod resolves roles from '$v' (expected http://usermgmt:8080 — a leftover resilience-stub rig?).
  Redeploy guarded: ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods ${#PODS[@]}"
  done
  if [ "$expected" = "true" ]; then
    docker ps --format '{{.Names}}' | grep -q "^${OPA_CONTAINER}\$" \
      || red "OPA container '$OPA_CONTAINER' not running — the guarded pass needs it."
    [ "$(docker inspect -f '{{.State.Paused}}' "$OPA_CONTAINER")" = "false" ] \
      || red "OPA container '$OPA_CONTAINER' is PAUSED (a leftover fault run?). Unpause: docker unpause $OPA_CONTAINER"
  fi
  note "pod state asserted: OPA_ABAC_ENABLED=$expected on ${#PODS[@]} pod(s), role source = usermgmt"
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

# --- modes ---------------------------------------------------------------------
mkdir -p "$RESULTS_DIR"

case "$MODE" in
  guarded)
    preflight
    assert_pod_state "true"
    mint_perf_token
    seed_fixtures
    note "harness skeleton (T1): scenarios land with T2 — preflight, seed, and teardown proven."
    teardown_fixtures
    ;;
  baseline)
    preflight
    assert_pod_state "false"   # aborts red BEFORE any load if the rig is guarded
    mint_perf_token
    seed_fixtures
    note "harness skeleton (T1): scenarios land with T2 — preflight, seed, and teardown proven."
    teardown_fixtures
    ;;
  full)
    red "mode 'full' (two-pass guarded/baseline orchestration) lands with T2 — not implemented yet."
    ;;
esac
