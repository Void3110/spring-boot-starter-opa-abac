#!/usr/bin/env bash
#
# Run the team-based ABAC matrix (team-abac-matrix.postman_collection.json) through the local rig.
#
# Proves the Phase-4 APP-RESOLVED path end to end: a catalog request is authorized by a role resolved
# from real team membership in the user-management-service. The owner of a catalog can write; a member
# with the viewer role cannot; a member with a team-scoped custom editor role can; a non-member is
# denied — all through the gateway, the role coming from the user-service.
#
# Prereq: the full rig is up WITH OIDC + OPA + the user-service + the identity directory
# (the USER-DIRECTORY-PORT cells 13-15 search the live directory; the flag force-enables the rest):
#   ENABLE_DIRECTORY=1 ./deploy.sh up --pods 2
#
# Directory preflight (E-pre): before newman, this runner mints the catalog-directory
# client_credentials token and PINS the least-privilege posture (view-users read 200; user
# create/update/delete all 403), proves the directory is live through the gateway, and wipes any
# provisioned row for 'dora' — the RESERVED never-provisioned probe account (no credentials, no
# matrix may bootstrap her; see README fixture registry) — so cell 13a stays order-independent.
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
# The identity-directory service account (USER-DIRECTORY-PORT E-pre) — view-users ONLY.
DIRECTORY_CLIENT_ID="${DIRECTORY_CLIENT_ID:-catalog-directory}"
DIRECTORY_CLIENT_SECRET="${DIRECTORY_CLIENT_SECRET:-catalog-directory-secret}"
UM_PG_CONTAINER="${UM_PG_CONTAINER:-opa-abac-usermgmt-postgres}"
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

# --- directory preflight (USER-DIRECTORY-PORT: E-pre + fixture hygiene) --------
# Pins T5's least-privilege posture as COMMITTED assertions (not a one-time manual check) and makes
# the directory cells deterministic. Fails fast with the actionable cause — the no-oracle contract
# makes "directory off" and "no matches" identical at the endpoint, so the discriminators here are
# the service-account grant (realm current?) and a search for 'dora', who provably exists in the realm.
echo "==> Directory preflight (E-pre): least privilege + the directory live ..."
DIR_TOKEN="$("$RUNTIME" run --rm --network "$NETWORK" curlimages/curl -s \
  -X POST "$KEYCLOAK_TOKEN_URL" \
  -d grant_type=client_credentials -d "client_id=$DIRECTORY_CLIENT_ID" -d "client_secret=$DIRECTORY_CLIENT_SECRET" \
  | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')"
[ -n "$DIR_TOKEN" ] || {
  echo "ERROR: could not mint the $DIRECTORY_CLIENT_ID service token — stale realm? Recreate Keycloak:" >&2
  echo "  docker compose -p opa-abac-example -f ../../infra/compose.keycloak.yaml up -d --force-recreate keycloak" >&2
  exit 1
}

kc_admin() { # method path [json-body] -> body + last line HTTP code
  local method="$1" path="$2" body="${3:-}"
  if [ -n "$body" ]; then
    "$RUNTIME" run --rm --network "$NETWORK" curlimages/curl -s -w '\n%{http_code}' \
      -X "$method" -H "Authorization: Bearer $DIR_TOKEN" -H 'Content-Type: application/json' \
      -d "$body" "http://keycloak:8888/admin/realms/catalog-demo$path"
  else
    "$RUNTIME" run --rm --network "$NETWORK" curlimages/curl -s -w '\n%{http_code}' \
      -X "$method" -H "Authorization: Bearer $DIR_TOKEN" \
      "http://keycloak:8888/admin/realms/catalog-demo$path"
  fi
}

# E-pre read half: view-users works, and the reserved probe account exists in the realm.
resp="$(kc_admin GET '/users?username=dora&exact=true&max=2')"
code="$(printf '%s' "$resp" | tail -n1)"
[ "$code" = "200" ] || { echo "ERROR: E-pre view-users read got HTTP $code (want 200)." >&2; exit 1; }
DORA_SUB="$(printf '%s' "$resp" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p' | head -n1)"
[ -n "$DORA_SUB" ] || {
  echo "ERROR: the reserved probe account 'dora' is not in the realm — recreate Keycloak to import the current realm-export." >&2
  exit 1
}

# E-pre denied half: every write is 403 — the account holds view-users and NOTHING else.
code="$(kc_admin POST '/users' '{"username":"evil","enabled":true}' | tail -n1)"
[ "$code" = "403" ] || { echo "ERROR: E-pre — user CREATE got HTTP $code (want 403): the service account holds more than view-users." >&2; exit 1; }
code="$(kc_admin PUT "/users/$DORA_SUB" '{"firstName":"Hacked"}' | tail -n1)"
[ "$code" = "403" ] || { echo "ERROR: E-pre — user UPDATE got HTTP $code (want 403): the service account holds more than view-users." >&2; exit 1; }
code="$(kc_admin DELETE "/users/$DORA_SUB" | tail -n1)"
[ "$code" = "403" ] || { echo "ERROR: E-pre — user DELETE got HTTP $code (want 403): the service account holds more than view-users." >&2; exit 1; }
echo "  E-pre: view-users read 200; create/update/delete all 403 (least privilege pinned)."

# The directory must be LIVE through the gateway for cells 13/15.
DIR_ITEMS="$(curl -s -H "Authorization: Bearer $OWNER_TOKEN" "$GATEWAY/api/v1/users/search?q=dora" \
  | python3 -c 'import sys,json; print(len(json.load(sys.stdin)["items"]))' 2>/dev/null || echo 0)"
[ "$DIR_ITEMS" -ge 1 ] || {
  echo "ERROR: the identity directory is OFF on the user-service (the realm account 'dora' was not returned)." >&2
  echo "  Bring the rig up with: ENABLE_DIRECTORY=1 ./deploy.sh up --pods 2" >&2
  exit 1
}

# Fixture hygiene: 13a asserts dora has NO provisioned row. She is RESERVED for this probe (no
# credentials, no matrix bootstraps her), so wiping any stray row (a demo click) is safe by design.
"$RUNTIME" exec -i "$UM_PG_CONTAINER" psql -U usermgmt -d usermgmt -v ON_ERROR_STOP=1 >/dev/null <<SQL
DELETE FROM team_membership WHERE user_id IN (SELECT id FROM app_user WHERE subject = '$DORA_SUB');
DELETE FROM app_user WHERE subject = '$DORA_SUB';
SQL
echo "  directory live through the gateway; dora unprovisioned (any stray row wiped)."

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
  --env-var "owner_sub=$OWNER_SUB" \
  --env-var "viewer_uid=$VIEWER_UID" \
  --reporters cli,json \
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
