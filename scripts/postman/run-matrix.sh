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
#
# BOTH RIG FLAVOURS, ONE COLLECTION. Slice B4 (ADR 0018) made membership the sole access path, so on
# the user-service rig the editor's freshly seeded catalog is unreachable — by the editor AND by the
# viewer — until a governing team exists. The collection therefore claims the catalog with a
# self-service team (the shipped public endpoint, owner-on-create) and binds the viewer to it as a
# `reader`: the system role that reads and does not write, which is exactly the contrast this matrix
# draws. Both steps SKIP THEMSELVES on the OIDC-only quickstart rig, where the static `demo` role
# supplier decides. This runner probes which flavour is live and resolves the viewer's user id only
# when it needs to.

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
USER_SERVICE="${USER_SERVICE:-http://localhost:28090}"
UM_PG_CONTAINER="${UM_PG_CONTAINER:-opa-abac-usermgmt-postgres}"
TEAM_NAME="${TEAM_NAME:-ABAC matrix team}"
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

# --- which rig flavour is live? ----------------------------------------------
# Ask the CATALOG POD what its role source is — the same value application.yml reads
# (`role-source: ${CATALOG_ROLE_SOURCE:demo}`), which is the only thing that actually decides whether
# membership is consulted. Do NOT probe the user-service for this: `ENABLE_OIDC=1 ./deploy.sh up`
# recreates the catalog pods on the static supplier but leaves an already-running usermgmt container
# alone, so a reachability check reports "membership rig" on a rig that ignores membership entirely —
# and the bootstrap would run, create a team nobody consults, and pass for the wrong reason.
CATALOG_CONTAINER="${CATALOG_CONTAINER:-catalog-1}"
ROLE_SOURCE="$("$RUNTIME" inspect "$CATALOG_CONTAINER" --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null \
  | sed -n 's/^CATALOG_ROLE_SOURCE=//p' | head -1)"
ROLE_SOURCE="${ROLE_SOURCE:-demo}"
VIEWER_UID=""
if [ "$ROLE_SOURCE" = "http" ]; then
  B4_BOOTSTRAP="true"
  echo "==> Rig flavour: USER-SERVICE (catalog role-source=http). The editor will claim its catalog; the viewer is bound as a reader."
  curl -sf "$USER_SERVICE/actuator/health" 2>/dev/null | grep -q '"status":"UP"' || {
    echo "ERROR: the catalog pods resolve roles over HTTP but the user-service is unreachable at $USER_SERVICE." >&2
    echo "  Bring the rig up with: ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2" >&2
    exit 1
  }
  # The membership needs the viewer's user id, which only exists once they are provisioned. The
  # editor needs no such step: owner-on-create provisions the caller as a side effect.
  viewer_payload="$(printf '%s' "$VIEWER_TOKEN" | cut -d. -f2 | tr '_-' '/+')"
  while [ $(( ${#viewer_payload} % 4 )) -ne 0 ]; do viewer_payload="${viewer_payload}="; done
  VIEWER_SUB="$(printf '%s' "$viewer_payload" | base64 -d 2>/dev/null | sed -n 's/.*"sub":"\([^"]*\)".*/\1/p')"
  [ -n "$VIEWER_SUB" ] || { echo "ERROR: could not decode the viewer's subject from its token." >&2; exit 1; }
  VIEWER_UID="$(curl -s -X POST "$USER_SERVICE/internal/bootstrap/users" \
      -H 'Content-Type: application/json' \
      -d "{\"subject\":\"$VIEWER_SUB\",\"displayName\":\"$VIEWER_USER\"}" \
    | python3 -c 'import sys,json; print(json.load(sys.stdin)["userId"])' 2>/dev/null || true)"
  [ -n "$VIEWER_UID" ] || {
    echo "ERROR: could not provision the viewer in the user-service at $USER_SERVICE." >&2
    exit 1
  }
  echo "  viewer provisioned: sub=$VIEWER_SUB uid=$VIEWER_UID"
else
  B4_BOOTSTRAP="false"
  echo "==> Rig flavour: OIDC-ONLY (catalog role-source=$ROLE_SOURCE, the static supplier). The B4 bootstrap steps will skip themselves."
fi

# --- run newman --------------------------------------------------------------
mkdir -p "$REPORT_DIR/$RUN_ID"
NEWMAN_ARGS=(
  run "$COLLECTION"
  -e "$ENV_FILE"
  --env-var "viewer_token=$VIEWER_TOKEN"
  --env-var "editor_token=$EDITOR_TOKEN"
  --env-var "b4_bootstrap=$B4_BOOTSTRAP"
  --env-var "user_service=$USER_SERVICE"
  --env-var "viewer_uid=$VIEWER_UID"
  --reporter-cli
  --reporter-json-export "$REPORT_DIR/$RUN_ID/$(basename "${COLLECTION%.json}")-report.json"
)
[ -n "$VERBOSE" ] && NEWMAN_ARGS+=("$VERBOSE")

echo "==> newman run $COLLECTION (ABAC allow/deny matrix)"
newman "${NEWMAN_ARGS[@]}"

# --- teardown (success only; KEEP_FIXTURES=1 skips) ---------------------------
# The collection deletes its own catalog, but a team is not a catalog child and would outlive it as
# an orphan row in the shared store — visible in the demo UI's team list, and accumulating one per
# run. Keyed by the team's fixed NAME, which this matrix owns; the membership rides the FK cascade.
# Nothing to do on the OIDC-only rig, where no team was created.
if [ "$B4_BOOTSTRAP" = "true" ] && [ "${KEEP_FIXTURES:-0}" != "1" ]; then
  echo "==> Teardown: removing the '$TEAM_NAME' row (KEEP_FIXTURES=1 keeps it) ..."
  "$RUNTIME" exec -i "$UM_PG_CONTAINER" psql -U usermgmt -d usermgmt -v ON_ERROR_STOP=1 >/dev/null <<SQL
DELETE FROM team WHERE name = '$TEAM_NAME';
SQL
fi
