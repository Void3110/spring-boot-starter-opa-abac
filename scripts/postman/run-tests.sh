#!/usr/bin/env bash
#
# Run the catalog e2e Postman suite through the local rig (APISIX + Keycloak + OPA).
#
# Prereq: the full rig is up with OIDC ->  ENABLE_OIDC=1 ./deploy.sh up --pods 2
# (Postgres + APISIX on :9085 + Keycloak realm catalog-demo + catalog pods.)
#
# Because Keycloak is hostname-aware and APISIX validates the issuer IN-NETWORK
# (issuer http://keycloak:8888), a token minted against the host (localhost:28888) is
# rejected at the gateway. So this runner mints the token from INSIDE the shared compose
# network (opa-abac-example_default) and injects it into newman as `access_token`.
# See docs/guides/E2E-TESTING.md for the full explanation.

set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SELF_DIR"

# --- config (override via env) -----------------------------------------------
COLLECTION="${COLLECTION:-catalog-e2e.postman_collection.json}"
ENV_FILE="${ENV_FILE:-local.postman_environment.json}"
NETWORK="${COMPOSE_NETWORK:-opa-abac-example_default}"
KEYCLOAK_TOKEN_URL="${KEYCLOAK_TOKEN_URL:-http://keycloak:8888/realms/catalog-demo/protocol/openid-connect/token}"
CLIENT_ID="${CLIENT_ID:-catalog-gateway}"
CLIENT_SECRET="${CLIENT_SECRET:-catalog-gateway-secret}"
USERNAME="${KC_USERNAME:-demo}"
PASSWORD="${KC_PASSWORD:-demo}"
REPORT_DIR="${REPORT_DIR:-build/reports/postman}"

# A run id for isolating report output (no Math.random / Date needed — use the PID + epoch).
RUN_ID="${E2E_RUN_ID:-$(date +%s)-$$}"

# --- args --------------------------------------------------------------------
FOLDER=""
VERBOSE=""
usage() {
  cat <<EOF
Usage: ./run-tests.sh [--folder NAME] [--collection FILE] [--env FILE] [--verbose]

  --folder NAME       Run only the named collection folder (e.g. "Product").
  --collection FILE   Collection JSON (default: $COLLECTION).
  --env FILE          Environment JSON (default: $ENV_FILE).
  --verbose           Pass --verbose to newman.

Prereq: ENABLE_OIDC=1 ./deploy.sh up --pods 2   (full rig with Keycloak)
EOF
}
while [ $# -gt 0 ]; do
  case "$1" in
    --folder)     FOLDER="${2:-}"; shift 2 ;;
    --collection) COLLECTION="${2:-}"; shift 2 ;;
    --env)        ENV_FILE="${2:-}"; shift 2 ;;
    --verbose)    VERBOSE="--verbose"; shift ;;
    -h|--help)    usage; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; usage; exit 1 ;;
  esac
done

# --- preflight ---------------------------------------------------------------
command -v newman >/dev/null 2>&1 || {
  echo "ERROR: newman not found. Install with: npm install -g newman  (or: brew install newman)" >&2
  exit 1
}
if [ ! -f "$ENV_FILE" ]; then
  echo "ERROR: $ENV_FILE not found. Copy the template first:" >&2
  echo "  cp local.postman_environment.example.json local.postman_environment.json" >&2
  exit 1
fi

# Container runtime for the in-network token grab (docker or podman).
RUNTIME=""
for c in docker podman; do command -v "$c" >/dev/null 2>&1 && { RUNTIME="$c"; break; }; done
[ -n "$RUNTIME" ] || { echo "ERROR: need docker or podman to mint an in-network token." >&2; exit 1; }

# --- mint an in-network token ------------------------------------------------
echo "==> Minting Keycloak token in-network ($NETWORK) ..."
TOKEN_JSON="$("$RUNTIME" run --rm --network "$NETWORK" curlimages/curl -s \
  -X POST "$KEYCLOAK_TOKEN_URL" \
  -d grant_type=password \
  -d "client_id=$CLIENT_ID" \
  -d "client_secret=$CLIENT_SECRET" \
  -d "username=$USERNAME" \
  -d "password=$PASSWORD" || true)"

# Extract access_token without requiring jq.
ACCESS_TOKEN="$(printf '%s' "$TOKEN_JSON" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')"
if [ -z "$ACCESS_TOKEN" ]; then
  echo "ERROR: could not obtain an access_token. Is the rig up with OIDC (ENABLE_OIDC=1)?" >&2
  echo "  response: ${TOKEN_JSON:0:200}" >&2
  exit 1
fi
echo "  token acquired (${#ACCESS_TOKEN} chars)."

# --- run newman --------------------------------------------------------------
mkdir -p "$REPORT_DIR/$RUN_ID"
NEWMAN_ARGS=(
  run "$COLLECTION"
  -e "$ENV_FILE"
  --env-var "access_token=$ACCESS_TOKEN"
  --reporter-cli
  --reporter-json-export "$REPORT_DIR/$RUN_ID/$(basename "${COLLECTION%.json}")-report.json"
)
[ -n "$FOLDER" ]  && NEWMAN_ARGS+=(--folder "$FOLDER")
[ -n "$VERBOSE" ] && NEWMAN_ARGS+=("$VERBOSE")

echo "==> newman run $COLLECTION ${FOLDER:+(folder: $FOLDER)}"
newman "${NEWMAN_ARGS[@]}"
