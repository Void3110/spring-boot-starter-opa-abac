#!/usr/bin/env bash
#
# Run the ABAC allow/deny matrix (catalog-abac-matrix.postman_collection.json) through the local rig.
#
# Proves the library spine end to end: a viewer token reads (200) but cannot write (403); an editor
# token writes (201/200/204). Decisions are role-definition-driven, enforced by @OpaPreAuthorize -> OPA.
#
# Prereq: the full rig is up WITH OIDC + OPA and the per-type policies loaded:
#   ENABLE_OIDC=1 ./deploy.sh up --pods 2
#
# Like run-tests.sh, tokens are minted IN-NETWORK (APISIX validates issuer http://keycloak:8888), but
# here we mint TWO — for the viewer and editor realm users — and inject both into newman.
# See docs/guides/E2E-TESTING.md for the in-network token rationale.

set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SELF_DIR"

# --- config (override via env) -----------------------------------------------
COLLECTION="${COLLECTION:-catalog-abac-matrix.postman_collection.json}"
ENV_FILE="${ENV_FILE:-local.postman_environment.json}"
NETWORK="${COMPOSE_NETWORK:-opa-abac-example_default}"
KEYCLOAK_TOKEN_URL="${KEYCLOAK_TOKEN_URL:-http://keycloak:8888/realms/catalog-demo/protocol/openid-connect/token}"
CLIENT_ID="${CLIENT_ID:-catalog-gateway}"
CLIENT_SECRET="${CLIENT_SECRET:-catalog-gateway-secret}"
VIEWER_USER="${VIEWER_USER:-viewer}"
VIEWER_PASS="${VIEWER_PASS:-viewer}"
EDITOR_USER="${EDITOR_USER:-editor}"
EDITOR_PASS="${EDITOR_PASS:-editor}"
REPORT_DIR="${REPORT_DIR:-build/reports/postman}"
RUN_ID="${E2E_RUN_ID:-$(date +%s)-$$}"

VERBOSE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --collection) COLLECTION="${2:-}"; shift 2 ;;
    --env)        ENV_FILE="${2:-}"; shift 2 ;;
    --verbose)    VERBOSE="--verbose"; shift ;;
    -h|--help)
      echo "Usage: ./run-matrix.sh [--collection FILE] [--env FILE] [--verbose]"; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; exit 1 ;;
  esac
done

# --- preflight ---------------------------------------------------------------
command -v newman >/dev/null 2>&1 || {
  echo "ERROR: newman not found. Install with: npm install -g newman  (or: brew install newman)" >&2
  exit 1
}
[ -f "$ENV_FILE" ] || {
  echo "ERROR: $ENV_FILE not found. Copy the template: cp local.postman_environment.example.json local.postman_environment.json" >&2
  exit 1
}
RUNTIME=""
for c in docker podman; do command -v "$c" >/dev/null 2>&1 && { RUNTIME="$c"; break; }; done
[ -n "$RUNTIME" ] || { echo "ERROR: need docker or podman to mint in-network tokens." >&2; exit 1; }

# --- mint a token for a given user, in-network -------------------------------
mint_token() {
  local user="$1" pass="$2"
  local json
  json="$("$RUNTIME" run --rm --network "$NETWORK" curlimages/curl -s \
    -X POST "$KEYCLOAK_TOKEN_URL" \
    -d grant_type=password \
    -d "client_id=$CLIENT_ID" \
    -d "client_secret=$CLIENT_SECRET" \
    -d "username=$user" \
    -d "password=$pass" || true)"
  printf '%s' "$json" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p'
}

echo "==> Minting viewer + editor tokens in-network ($NETWORK) ..."
VIEWER_TOKEN="$(mint_token "$VIEWER_USER" "$VIEWER_PASS")"
EDITOR_TOKEN="$(mint_token "$EDITOR_USER" "$EDITOR_PASS")"
for pair in "viewer:$VIEWER_TOKEN" "editor:$EDITOR_TOKEN"; do
  name="${pair%%:*}"; tok="${pair#*:}"
  if [ -z "$tok" ]; then
    echo "ERROR: could not obtain a token for '$name'. Is the rig up with OIDC and the user present?" >&2
    exit 1
  fi
done
echo "  viewer token: ${#VIEWER_TOKEN} chars; editor token: ${#EDITOR_TOKEN} chars."

# --- run newman --------------------------------------------------------------
mkdir -p "$REPORT_DIR/$RUN_ID"
NEWMAN_ARGS=(
  run "$COLLECTION"
  -e "$ENV_FILE"
  --env-var "viewer_token=$VIEWER_TOKEN"
  --env-var "editor_token=$EDITOR_TOKEN"
  --reporter-cli
  --reporter-json-export "$REPORT_DIR/$RUN_ID/$(basename "${COLLECTION%.json}")-report.json"
)
[ -n "$VERBOSE" ] && NEWMAN_ARGS+=("$VERBOSE")

echo "==> newman run $COLLECTION (ABAC allow/deny matrix)"
newman "${NEWMAN_ARGS[@]}"
