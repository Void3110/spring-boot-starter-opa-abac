#!/usr/bin/env bash
#
# Run the tag-based ABAC matrix (tag-abac-matrix.postman_collection.json) through the local rig.
#
# Proves the Phase-4.5 dynamic-tag-dictionary path end to end: a catalog request is authorized by the
# RESOURCE'S TAGS matched against a role's required tags, in Rego. The decisive contrast — the SAME
# member with the SAME tag-gated role reads two Categories that differ only in their tags: the matching
# one is allowed (200), the non-matching one is denied (403). Plus ANY_OF vs ALL_OF, the dictionary
# define dogfood (owner 201 / member 403), and an illegal assignment (422).
#
# Prereq: the full rig is up WITH OIDC + OPA + the user-service:
#   ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2
#
# How it wires the demo data at run time (the team-target catalog id, the IdP subjects, and the Category
# ids are only known after tokens are minted and Categories are created):
#   1. mint in-network tokens for owner(editor) / reader(viewer) / strict(demo) / editor(editor);
#   2. seed a fixed demo catalog row into the catalog DB;
#   3. via the user-service internal API, create a team for that catalog and bind memberships, then a
#      team-scoped 'regional-reader' role requiring region ANY_OF [emea] and a 'strict-reader' requiring
#      region:[emea] AND sensitivity:[public,internal] (ALL_OF) — bound to reader/strict;
#   4. via the GATEWAY (owner token), create three Categories with dictionary-validated tags:
#        match    -> region=[emea]                (regional-reader allowed; strict denied: no sensitivity)
#        mismatch -> region=[apac]                (regional-reader denied)
#        both     -> region=[emea],sensitivity=internal  (strict allowed)
#   5. run newman with the captured ids + tokens.
#
# Honors the in-network token caveat (APISIX validates issuer http://keycloak:8888) and keeps the
# runtime-captured ids in the collection variable scope (mx-ecc3ef).

set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SELF_DIR"

# --- config (override via env) -----------------------------------------------
COLLECTION="${COLLECTION:-tag-abac-matrix.postman_collection.json}"
ENV_FILE="${ENV_FILE:-local.postman_environment.json}"
NETWORK="${COMPOSE_NETWORK:-opa-abac-example_default}"
KEYCLOAK_TOKEN_URL="${KEYCLOAK_TOKEN_URL:-http://keycloak:8888/realms/catalog-demo/protocol/openid-connect/token}"
CLIENT_ID="${CLIENT_ID:-catalog-gateway}"
CLIENT_SECRET="${CLIENT_SECRET:-catalog-gateway-secret}"
USER_SERVICE="${USER_SERVICE:-http://localhost:28090}"
PG_CONTAINER="${PG_CONTAINER:-opa-abac-postgres}"
GATEWAY="${GATEWAY:-http://localhost:9085}"
REPORT_DIR="${REPORT_DIR:-build/reports/postman}"
RUN_ID="${E2E_RUN_ID:-tag-$$}"

# realm user -> role on the team
OWNER_USER="${OWNER_USER:-editor}";   OWNER_PASS="${OWNER_PASS:-editor}"
READER_USER="${READER_USER:-viewer}"; READER_PASS="${READER_PASS:-viewer}"
STRICT_USER="${STRICT_USER:-demo}";   STRICT_PASS="${STRICT_PASS:-demo}"
# ADR 0022 cells: a WRITE-holding tag-gated member — read of the untagged root is EXEMPT (200),
# its mutation stays tag-gated (403). Needs write so the deny is the TAG conjunct, not permission.
GATED_USER="${GATED_USER:-bob}";      GATED_PASS="${GATED_PASS:-bob}"

DEMO_CATALOG_ID="${DEMO_CATALOG_ID:-22222222-2222-2222-2222-222222222222}"

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

token_sub() {
  local tok="$1" payload
  payload="$(printf '%s' "$tok" | cut -d. -f2 | tr '_-' '/+')"
  while [ $(( ${#payload} % 4 )) -ne 0 ]; do payload="${payload}="; done
  printf '%s' "$payload" | base64 -d 2>/dev/null | sed -n 's/.*"sub":"\([^"]*\)".*/\1/p'
}

post_json() { curl -s -X POST "$1" -H 'Content-Type: application/json' -d "$2"; }
json_field() { python3 -c "import sys,json; print(json.load(sys.stdin)['$1'])"; }
# Create a Category through the GATEWAY with the owner token; echo its id.
create_category() {
  local token="$1" body="$2"
  curl -s -X POST "$GATEWAY/api/v1/catalogs/$DEMO_CATALOG_ID/categories" \
    -H "Authorization: Bearer $token" -H 'Content-Type: application/json' -d "$body" \
    | json_field id
}

# --- mint tokens -------------------------------------------------------------
echo "==> Minting owner/reader/strict tokens in-network ($NETWORK) ..."
OWNER_TOKEN="$(mint_token "$OWNER_USER" "$OWNER_PASS")"
READER_TOKEN="$(mint_token "$READER_USER" "$READER_PASS")"
STRICT_TOKEN="$(mint_token "$STRICT_USER" "$STRICT_PASS")"
GATED_TOKEN="$(mint_token "$GATED_USER" "$GATED_PASS")"
for pair in "owner:$OWNER_TOKEN" "reader:$READER_TOKEN" "strict:$STRICT_TOKEN" "gated:$GATED_TOKEN"; do
  name="${pair%%:*}"; tok="${pair#*:}"
  [ -n "$tok" ] || { echo "ERROR: no token for '$name'. Is the rig up with OIDC and the user present?" >&2; exit 1; }
done

OWNER_SUB="$(token_sub "$OWNER_TOKEN")"
READER_SUB="$(token_sub "$READER_TOKEN")"
STRICT_SUB="$(token_sub "$STRICT_TOKEN")"
GATED_SUB="$(token_sub "$GATED_TOKEN")"
echo "  subjects: owner=$OWNER_SUB reader=$READER_SUB strict=$STRICT_SUB gated=$GATED_SUB"

# --- seed the demo catalog (the team-target) ---------------------------------
# A prior run's Categories are deleted first so re-runs never accumulate (the FKs cascade to
# products) — only the dedicated 2222… fixture is touched.
echo "==> Seeding demo catalog $DEMO_CATALOG_ID into the catalog DB ..."
"$RUNTIME" exec -i "$PG_CONTAINER" psql -U catalog -d catalog -v ON_ERROR_STOP=1 >/dev/null <<SQL
DELETE FROM category WHERE catalog_id = '$DEMO_CATALOG_ID';
INSERT INTO catalog (id, name, created_at, version, tags, path)
VALUES ('$DEMO_CATALOG_ID', 'Tag demo catalog', now(), 0, '{}'::jsonb,
        CAST('catalog_' || replace('$DEMO_CATALOG_ID','-','') AS ltree))
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, path = EXCLUDED.path;
SQL

# --- bootstrap the team + tag-gated roles + memberships ----------------------
echo "==> Bootstrapping the team, tag-gated roles, and memberships in the user-service ..."
OWNER_UID="$(post_json "$USER_SERVICE/internal/bootstrap/users" "{\"subject\":\"$OWNER_SUB\",\"displayName\":\"$OWNER_USER\"}" | json_field userId)"
READER_UID="$(post_json "$USER_SERVICE/internal/bootstrap/users" "{\"subject\":\"$READER_SUB\",\"displayName\":\"$READER_USER\"}" | json_field userId)"
STRICT_UID="$(post_json "$USER_SERVICE/internal/bootstrap/users" "{\"subject\":\"$STRICT_SUB\",\"displayName\":\"$STRICT_USER\"}" | json_field userId)"
GATED_UID="$(post_json "$USER_SERVICE/internal/bootstrap/users" "{\"subject\":\"$GATED_SUB\",\"displayName\":\"$GATED_USER\"}" | json_field userId)"

TEAM_ID="$(post_json "$USER_SERVICE/internal/bootstrap/teams" "{\"name\":\"Tag demo\",\"targetType\":\"catalog\",\"targetId\":\"$DEMO_CATALOG_ID\"}" | json_field teamId)"

# A tag-gated 'regional-reader': read on the catalog team-target, requiring region ANY_OF [emea].
post_json "$USER_SERVICE/internal/bootstrap/custom-roles" \
  "{\"teamId\":\"$TEAM_ID\",\"code\":\"regional-reader\",\"roleLevel\":10,\"permissions\":{\"catalog\":[\"READ\"],\"category\":[\"READ\"]},\"requiredTags\":{\"region\":[\"emea\"]},\"matchMode\":\"ANY_OF\"}" >/dev/null
# A 'strict-reader': read, requiring region:[emea] AND sensitivity:[public,internal] (ALL_OF).
post_json "$USER_SERVICE/internal/bootstrap/custom-roles" \
  "{\"teamId\":\"$TEAM_ID\",\"code\":\"strict-reader\",\"roleLevel\":10,\"permissions\":{\"catalog\":[\"READ\"],\"category\":[\"READ\"]},\"requiredTags\":{\"region\":[\"emea\"],\"sensitivity\":[\"public\",\"internal\"]},\"matchMode\":\"ALL_OF\"}" >/dev/null

# ADR 0022: a 'gated-writer' — catalog READ+WRITE with the same region requirement. Its READ of the
# untagged root rides the root-read exemption (200); its WRITE is denied by the tag conjunct (403) —
# WRITE permission present, so the contrast isolates the exemption boundary, never the permission check.
post_json "$USER_SERVICE/internal/bootstrap/custom-roles" \
  "{\"teamId\":\"$TEAM_ID\",\"code\":\"gated-writer\",\"roleLevel\":20,\"permissions\":{\"catalog\":[\"READ\",\"WRITE\"],\"category\":[\"READ\"]},\"requiredTags\":{\"region\":[\"emea\"]},\"matchMode\":\"ANY_OF\"}" >/dev/null

# Bind: owner -> owner (full write, to create Categories); reader -> regional-reader; strict -> strict-reader.
post_json "$USER_SERVICE/internal/bootstrap/memberships" "{\"teamId\":\"$TEAM_ID\",\"userId\":\"$OWNER_UID\",\"roleCode\":\"owner\"}" >/dev/null
post_json "$USER_SERVICE/internal/bootstrap/memberships" "{\"teamId\":\"$TEAM_ID\",\"userId\":\"$READER_UID\",\"roleCode\":\"regional-reader\"}" >/dev/null
post_json "$USER_SERVICE/internal/bootstrap/memberships" "{\"teamId\":\"$TEAM_ID\",\"userId\":\"$STRICT_UID\",\"roleCode\":\"strict-reader\"}" >/dev/null
post_json "$USER_SERVICE/internal/bootstrap/memberships" "{\"teamId\":\"$TEAM_ID\",\"userId\":\"$GATED_UID\",\"roleCode\":\"gated-writer\"}" >/dev/null
echo "  team $TEAM_ID governs catalog $DEMO_CATALOG_ID (owner + regional-reader + strict-reader bound)."

# --- create three differently-tagged Categories via the gateway (owner) ------
echo "==> Creating tagged Categories through the gateway ..."
MATCH_CATEGORY_ID="$(create_category "$OWNER_TOKEN" '{"name":"EMEA only","tags":{"region":["emea"]}}')"
MISMATCH_CATEGORY_ID="$(create_category "$OWNER_TOKEN" '{"name":"APAC only","tags":{"region":["apac"]}}')"
BOTH_CATEGORY_ID="$(create_category "$OWNER_TOKEN" '{"name":"EMEA internal","tags":{"region":["emea"],"sensitivity":"internal"}}')"
echo "  match=$MATCH_CATEGORY_ID mismatch=$MISMATCH_CATEGORY_ID both=$BOTH_CATEGORY_ID"
for pair in "match:$MATCH_CATEGORY_ID" "mismatch:$MISMATCH_CATEGORY_ID" "both:$BOTH_CATEGORY_ID"; do
  name="${pair%%:*}"; id="${pair#*:}"
  [ -n "$id" ] && [ "$id" != "None" ] || { echo "ERROR: failed to create the '$name' Category. Is the owner write path working?" >&2; exit 1; }
done

# --- run newman --------------------------------------------------------------
mkdir -p "$REPORT_DIR/$RUN_ID"
echo "==> newman run $COLLECTION (tag-based ABAC matrix through the gateway)"
newman run "$COLLECTION" \
  -e "$ENV_FILE" \
  --env-var "gateway=$GATEWAY" \
  --env-var "user_service=$USER_SERVICE" \
  --env-var "catalog_id=$DEMO_CATALOG_ID" \
  --env-var "team_id=$TEAM_ID" \
  --env-var "match_category_id=$MATCH_CATEGORY_ID" \
  --env-var "mismatch_category_id=$MISMATCH_CATEGORY_ID" \
  --env-var "both_category_id=$BOTH_CATEGORY_ID" \
  --env-var "define_key=tier-$$" \
  --env-var "owner_token=$OWNER_TOKEN" \
  --env-var "reader_token=$READER_TOKEN" \
  --env-var "strict_token=$STRICT_TOKEN" \
  --env-var "gated_token=$GATED_TOKEN" \
  --env-var "editor_token=$OWNER_TOKEN" \
  --reporter-cli \
  --reporter-json-export "$REPORT_DIR/$RUN_ID/tag-abac-matrix-report.json"

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
