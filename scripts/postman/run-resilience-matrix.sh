#!/usr/bin/env bash
#
# Run the B3 cross-service HTTP resilience matrix (resilience-matrix.postman_collection.json) through the
# local rig — the live proof of Slice B3 (cross-service HTTP resilience, ADR 0017).
#
# What it proves (00-DESIGN, the headline / P9): the resolve edge is fault-injected by a tiny stub
# (infra/compose.resilience-stub.yaml), and the SAME protected id'd request is asserted under two outage
# shapes, in two passes:
#   E1  STUB_MODE=transient (1 x 503 then the role) -> the resolve CallGuard (2 retries) rides out the blip
#       -> the gate resolves the editor role -> GET /api/v1/categories/{id} SUCCEEDS (200).
#   E2  STUB_MODE=down (always 503) -> the guard exhausts -> HttpRoleDefinitionSupplier throws
#       RoleResolutionException -> the gate DENIES (403). B2's wall, un-breached: no realm-fallback widening
#       rode the outage to a 2xx.
# The contrast (transient recovers vs sustained still denies) is the slice's reason to exist.
#
# Prereq: the rig up WITH OIDC + OPA + the resilience stub, with fresh app code in the pods:
#   ENABLE_OIDC=1 ENABLE_RESILIENCE_STUB=1 ./deploy.sh up --pods 2
#   ./deploy.sh build          # force the B3 app code into the pods
# This runner brings the stub up in each mode itself (docker compose), so a single rig serves both passes.
# No Rego change in this slice -> no OPA restart needed. Honors the in-network token caveat (APISIX
# validates issuer http://keycloak:8888, so the token is minted IN-NETWORK).
#
# Fixture (dedicated, registered in scripts/postman/README.md):
#   cccccccc-cccc-cccc-cccc-ccccccccccccc  a Category the resilience matrix GETs (seeded via psql)

set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SELF_DIR"
ROOT_DIR="$(cd "$SELF_DIR/../.." && pwd)"

# --- config (override via env) -----------------------------------------------
COLLECTION="${COLLECTION:-resilience-matrix.postman_collection.json}"
NETWORK="${COMPOSE_NETWORK:-opa-abac-example_default}"
KEYCLOAK_TOKEN_URL="${KEYCLOAK_TOKEN_URL:-http://keycloak:8888/realms/catalog-demo/protocol/openid-connect/token}"
CLIENT_ID="${CLIENT_ID:-catalog-gateway}"
CLIENT_SECRET="${CLIENT_SECRET:-catalog-gateway-secret}"
GATEWAY="${GATEWAY:-http://localhost:9085}"
PG_CONTAINER="${PG_CONTAINER:-opa-abac-postgres}"
STUB_COMPOSE="${STUB_COMPOSE:-$ROOT_DIR/infra/compose.resilience-stub.yaml}"
PROJECT="${COMPOSE_PROJECT:-opa-abac-example}"
REPORT_DIR="${REPORT_DIR:-build/reports/postman}"

# The matrix subject: 'demo' resolves a role through the (fault-injected) resolve edge.
SUBJECT_USER="${SUBJECT_USER:-demo}"; SUBJECT_PASS="${SUBJECT_PASS:-demo}"
CATEGORY_ID="${CATEGORY_ID:-cccccccc-cccc-cccc-cccc-cccccccccccc}"
CATALOG_ID="${CATALOG_ID:-cccccccc-cccc-cccc-cccc-c00000000001}"

mkdir -p "$REPORT_DIR"

note() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
fail() { printf '\n\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

command -v newman >/dev/null 2>&1 || fail "newman not found (npm i -g newman)"
docker ps --format '{{.Names}}' | grep -q '^catalog-1$' \
  || fail "rig not up — run: ENABLE_OIDC=1 ENABLE_RESILIENCE_STUB=1 ./deploy.sh up --pods 2 && ./deploy.sh build"

# --- mint a token IN-NETWORK (issuer must match what APISIX validates) --------
mint_token() {
  docker run --rm --network "$NETWORK" curlimages/curl -s \
    -X POST "$KEYCLOAK_TOKEN_URL" \
    -d "client_id=$CLIENT_ID" -d "client_secret=$CLIENT_SECRET" \
    -d grant_type=password -d "username=$1" -d "password=$2" \
    | sed 's/.*"access_token":"//;s/".*//'
}

# --- seed the fixture Catalog + Category (idempotent) via psql ----------------
seed_fixture() {
  note "Seeding the resilience fixture Catalog ($CATALOG_ID) + Category ($CATEGORY_ID) via psql..."
  docker exec -i "$PG_CONTAINER" psql -U catalog -d catalog -v ON_ERROR_STOP=1 >/dev/null <<SQL
INSERT INTO catalog (id, name, version, tags, created_at, path)
  VALUES ('$CATALOG_ID', 'resilience-fixture-catalog', 0, '{}'::jsonb, now(),
          text2ltree('catalog_' || replace('$CATALOG_ID','-','')))
  ON CONFLICT (id) DO NOTHING;
INSERT INTO category (id, catalog_id, parent_id, name, version, tags, created_at, path)
  VALUES ('$CATEGORY_ID', '$CATALOG_ID', NULL, 'resilience-fixture-category', 0, '{}'::jsonb, now(),
          text2ltree('catalog_' || replace('$CATALOG_ID','-','') || '.category_' || replace('$CATEGORY_ID','-','')))
  ON CONFLICT (id) DO NOTHING;
SQL
}

# --- set the stub mode (recreate the stub container in the requested mode) ----
set_stub_mode() {
  local mode="$1" fails="${2:-1}"
  note "Setting resolve stub to mode=$mode (fails=$fails)..."
  STUB_MODE="$mode" STUB_FAILS="$fails" \
    docker compose -p "$PROJECT" -f "$STUB_COMPOSE" up -d --force-recreate >/dev/null
  # wait for the stub to answer
  for _ in $(seq 1 20); do
    if docker run --rm --network "$NETWORK" curlimages/curl -s -o /dev/null \
        "http://resolve-stub:8080/internal/effective-role?userId=x&resourceType=category&resourceId=$CATEGORY_ID"; then
      return 0
    fi
    sleep 0.5
  done
  fail "resolve stub did not come up in mode=$mode"
}

run_pass() {
  local label="$1" expected="$2"
  local token; token="$(mint_token "$SUBJECT_USER" "$SUBJECT_PASS")"
  [ -n "$token" ] || fail "could not mint a token for $SUBJECT_USER (is Keycloak up? ENABLE_OIDC=1?)"
  note "PASS [$label]: expecting HTTP $expected"
  newman run "$COLLECTION" \
    --env-var "access_token=$token" \
    --env-var "gateway=$GATEWAY" \
    --env-var "catalog_id=$CATALOG_ID" \
    --env-var "category_id=$CATEGORY_ID" \
    --env-var "EXPECTED_STATUS=$expected" \
    --env-var "EXPECTED_LABEL=$label" \
    --reporters cli,json \
    --reporter-json-export "$REPORT_DIR/resilience-$label.json"
}

# ---------------------------------------------------------------------------
seed_fixture

# E1 — transient blip recovers within budget -> SUCCESS
set_stub_mode transient 1
run_pass "E1-transient-recovers" 200

# E2 — sustained outage -> STILL DENIES (B2's wall, no widening)
set_stub_mode down
run_pass "E2-sustained-denies" 403

note "B3 resilience matrix PASSED: transient recovered (200), sustained denied (403)."
