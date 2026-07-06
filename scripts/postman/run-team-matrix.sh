#!/usr/bin/env bash
#
# Run the team-based ABAC matrix (team-abac-matrix.postman_collection.json) through the local rig.
#
# Proves the Phase-4 APP-RESOLVED path end to end: a catalog request is authorized by a role resolved
# from real team membership in the user-management-service. The owner of a catalog can write; a member
# with the viewer role cannot; a member with a team-scoped custom editor role can; a non-member is
# denied — all through the gateway, the role coming from the user-service.
#
# Prereq: the full rig is up WITH OIDC + OPA + the user-service:
#   ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2
#
# How it wires the demo data (the team-target catalog id and the IdP subjects are only known at run
# time, so a static seed can't): it mints four in-network tokens (owner/viewer/custom-editor/non-member),
# decodes each subject, seeds a fixed demo catalog row into the catalog DB, then bootstraps — via the
# user-service's internal API — a team whose team-target is that catalog plus the memberships (owner,
# viewer, a team-scoped custom 'catalog-editor' role). Finally it runs newman with the four tokens.
#
# This honours the in-network token caveat (APISIX validates issuer http://keycloak:8888) and keeps
# runtime-captured ids in the collection variable scope (mx-ecc3ef).

set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SELF_DIR"

# --- config (override via env) -----------------------------------------------
COLLECTION="${COLLECTION:-team-abac-matrix.postman_collection.json}"
ENV_FILE="${ENV_FILE:-local.postman_environment.json}"
NETWORK="${COMPOSE_NETWORK:-opa-abac-example_default}"
KEYCLOAK_TOKEN_URL="${KEYCLOAK_TOKEN_URL:-http://keycloak:8888/realms/catalog-demo/protocol/openid-connect/token}"
CLIENT_ID="${CLIENT_ID:-catalog-gateway}"
CLIENT_SECRET="${CLIENT_SECRET:-catalog-gateway-secret}"
USER_SERVICE="${USER_SERVICE:-http://localhost:28090}"
PG_CONTAINER="${PG_CONTAINER:-opa-abac-postgres}"
GATEWAY="${GATEWAY:-http://localhost:9085}"
REPORT_DIR="${REPORT_DIR:-build/reports/postman}"
RUN_ID="${E2E_RUN_ID:-team-$$}"

# Demo team users (realm user -> team role). The team-target is a fixed demo catalog UUID seeded below.
OWNER_USER="${OWNER_USER:-editor}";   OWNER_PASS="${OWNER_PASS:-editor}"
VIEWER_USER="${VIEWER_USER:-viewer}"; VIEWER_PASS="${VIEWER_PASS:-viewer}"
EDITOR_USER="${EDITOR_USER:-demo}";   EDITOR_PASS="${EDITOR_PASS:-demo}"
NONMEMBER_USER="${NONMEMBER_USER:-outsider}"; NONMEMBER_PASS="${NONMEMBER_PASS:-outsider}"

# A stable demo catalog id used as the team-target (seeded into the catalog DB).
DEMO_CATALOG_ID="${DEMO_CATALOG_ID:-11111111-1111-1111-1111-111111111111}"

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

# --- helpers -----------------------------------------------------------------
mint_token() {
  local user="$1" pass="$2" json
  json="$("$RUNTIME" run --rm --network "$NETWORK" curlimages/curl -s \
    -X POST "$KEYCLOAK_TOKEN_URL" \
    -d grant_type=password -d "client_id=$CLIENT_ID" -d "client_secret=$CLIENT_SECRET" \
    -d "username=$user" -d "password=$pass" || true)"
  printf '%s' "$json" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p'
}

# Decode the 'sub' claim from a JWT (base64url-decode the payload).
token_sub() {
  local tok="$1" payload
  payload="$(printf '%s' "$tok" | cut -d. -f2 | tr '_-' '/+')"
  # pad to a multiple of 4
  while [ $(( ${#payload} % 4 )) -ne 0 ]; do payload="${payload}="; done
  printf '%s' "$payload" | base64 -d 2>/dev/null \
    | sed -n 's/.*"sub":"\([^"]*\)".*/\1/p'
}

post_json() { curl -s -X POST "$1" -H 'Content-Type: application/json' -d "$2"; }
json_field() { python3 -c "import sys,json; print(json.load(sys.stdin)['$1'])"; }

# --- mint tokens -------------------------------------------------------------
echo "==> Minting owner/viewer/custom-editor/non-member tokens in-network ($NETWORK) ..."
OWNER_TOKEN="$(mint_token "$OWNER_USER" "$OWNER_PASS")"
VIEWER_TOKEN="$(mint_token "$VIEWER_USER" "$VIEWER_PASS")"
EDITOR_TOKEN="$(mint_token "$EDITOR_USER" "$EDITOR_PASS")"
NONMEMBER_TOKEN="$(mint_token "$NONMEMBER_USER" "$NONMEMBER_PASS")"
for pair in "owner:$OWNER_TOKEN" "viewer:$VIEWER_TOKEN" "editor:$EDITOR_TOKEN" "non-member:$NONMEMBER_TOKEN"; do
  name="${pair%%:*}"; tok="${pair#*:}"
  [ -n "$tok" ] || { echo "ERROR: no token for '$name'. Is the rig up with OIDC and the user present?" >&2; exit 1; }
done

OWNER_SUB="$(token_sub "$OWNER_TOKEN")"
VIEWER_SUB="$(token_sub "$VIEWER_TOKEN")"
EDITOR_SUB="$(token_sub "$EDITOR_TOKEN")"
echo "  subjects: owner=$OWNER_SUB viewer=$VIEWER_SUB custom-editor=$EDITOR_SUB"

# --- seed a stable demo catalog (the team-target) into the catalog DB --------
echo "==> Seeding demo catalog $DEMO_CATALOG_ID into the catalog DB ..."
"$RUNTIME" exec -i "$PG_CONTAINER" psql -U catalog -d catalog -v ON_ERROR_STOP=1 >/dev/null <<SQL
INSERT INTO catalog (id, name, created_at, version, tags, path)
VALUES ('$DEMO_CATALOG_ID', 'Team demo catalog', now(), 0, '{}'::jsonb,
        CAST('catalog_' || replace('$DEMO_CATALOG_ID','-','') AS ltree))
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, path = EXCLUDED.path;
SQL

# --- bootstrap the team + memberships via the user-service internal API ------
echo "==> Bootstrapping the team + memberships in the user-service ..."
OWNER_UID="$(post_json "$USER_SERVICE/internal/bootstrap/users" "{\"subject\":\"$OWNER_SUB\",\"displayName\":\"$OWNER_USER\"}" | json_field userId)"
VIEWER_UID="$(post_json "$USER_SERVICE/internal/bootstrap/users" "{\"subject\":\"$VIEWER_SUB\",\"displayName\":\"$VIEWER_USER\"}" | json_field userId)"
EDITOR_UID="$(post_json "$USER_SERVICE/internal/bootstrap/users" "{\"subject\":\"$EDITOR_SUB\",\"displayName\":\"$EDITOR_USER\"}" | json_field userId)"

TEAM_ID="$(post_json "$USER_SERVICE/internal/bootstrap/teams" "{\"name\":\"Team demo\",\"targetType\":\"catalog\",\"targetId\":\"$DEMO_CATALOG_ID\"}" | json_field teamId)"

# A team-scoped custom 'catalog-editor' role granting write on the catalog team-target.
post_json "$USER_SERVICE/internal/bootstrap/custom-roles" \
  "{\"teamId\":\"$TEAM_ID\",\"code\":\"catalog-editor\",\"roleLevel\":20,\"permissions\":{\"catalog\":[\"READ\",\"WRITE\",\"TAG\"]}}" >/dev/null

# Bind the roles: owner -> owner, viewer -> viewer, custom-editor -> the custom role.
post_json "$USER_SERVICE/internal/bootstrap/memberships" "{\"teamId\":\"$TEAM_ID\",\"userId\":\"$OWNER_UID\",\"roleCode\":\"owner\"}" >/dev/null
post_json "$USER_SERVICE/internal/bootstrap/memberships" "{\"teamId\":\"$TEAM_ID\",\"userId\":\"$VIEWER_UID\",\"roleCode\":\"reader\"}" >/dev/null
post_json "$USER_SERVICE/internal/bootstrap/memberships" "{\"teamId\":\"$TEAM_ID\",\"userId\":\"$EDITOR_UID\",\"roleCode\":\"catalog-editor\"}" >/dev/null
echo "  team $TEAM_ID governs catalog $DEMO_CATALOG_ID (owner/viewer/custom-editor bound)."

# --- run newman --------------------------------------------------------------
mkdir -p "$REPORT_DIR/$RUN_ID"
echo "==> newman run $COLLECTION (team-based ABAC matrix through the gateway)"
newman run "$COLLECTION" \
  -e "$ENV_FILE" \
  --env-var "gateway=$GATEWAY" \
  --env-var "user_service=$USER_SERVICE" \
  --env-var "catalog_id=$DEMO_CATALOG_ID" \
  --env-var "team_id=$TEAM_ID" \
  --env-var "owner_token=$OWNER_TOKEN" \
  --env-var "viewer_token=$VIEWER_TOKEN" \
  --env-var "editor_token=$EDITOR_TOKEN" \
  --env-var "nonmember_token=$NONMEMBER_TOKEN" \
  --reporter-cli \
  --reporter-json-export "$REPORT_DIR/$RUN_ID/team-abac-matrix-report.json"

# --- teardown (success only — a failed run keeps its fixtures for debugging) --
# KEEP_FIXTURES=1 skips it. Every run re-seeds its registry ids from scratch, so tearing them
# down keeps the shared store (and the demo UI's directory/team lists) clean. The DELETEs ride
# the FK cascades: team -> memberships + custom roles + tag definitions; catalog -> categories
# -> products.
if [ "${KEEP_FIXTURES:-0}" != "1" ]; then
  echo "==> Teardown: removing the $DEMO_CATALOG_ID fixture(s) (KEEP_FIXTURES=1 keeps them) ..."
  "$RUNTIME" exec -i "${UM_PG_CONTAINER:-opa-abac-usermgmt-postgres}" psql -U usermgmt -d usermgmt -v ON_ERROR_STOP=1 >/dev/null <<SQL
DELETE FROM team WHERE target_type = 'catalog' AND target_id IN ('$DEMO_CATALOG_ID');
SQL
  "$RUNTIME" exec -i "$PG_CONTAINER" psql -U catalog -d catalog -v ON_ERROR_STOP=1 >/dev/null <<SQL
DELETE FROM catalog WHERE id IN ('$DEMO_CATALOG_ID');
SQL
fi
