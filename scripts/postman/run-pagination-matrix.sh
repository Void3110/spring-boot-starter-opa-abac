#!/usr/bin/env bash
#
# Run the pagination matrix (pagination-matrix.postman_collection.json) through the local rig.
#
# Proves the 5.95 list envelope (ADR 0012) end to end: two tag-gated readers hit the SAME paged list
# URL and the envelope's `count` differs per subject (the count is the count of rows YOU may see); a
# perPage=2 walk returns disjoint pages whose union is exactly the single-page set, with the count
# stable on every page and past-the-end answering 200 + empty + the same exact count; and an
# out-of-bounds perPage is a live 400 VALIDATION_FAILED problem+json through APISIX (no clamping).
#
# Prereq: the full rig is up WITH OIDC + OPA + the user-service:
#   ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2
# (Run ./deploy.sh build first after changing app code, so the images carry the envelope.)
#
# How it wires the demo data at run time (the run-filter-matrix.sh model):
#   1. mint in-network tokens for owner(editor) / reader-emea(viewer) / reader-apac(demo);
#   2. seed the DEDICATED pagination demo catalog (7777… — its own row in the fixture-id registry;
#      shared fixtures must not grow, other matrices pin exact counts on them);
#   3. via the user-service internal API, create a team for that catalog and bind: owner->curator
#      (ungated category read+write, creates the rows), reader-emea->region ANY_OF [emea],
#      reader-apac->region ANY_OF [apac];
#   4. via the GATEWAY (curator token), create 5 EMEA-tagged + 3 APAC-tagged Categories — sequential
#      creates, so createdAt ASC, id ASC gives a deterministic walk order;
#   5. run newman: emea-reader counts 5, apac-reader counts 3 on the same URL; the walk is exact.
#
# Honors the in-network token caveat (APISIX validates issuer http://keycloak:8888).

set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SELF_DIR"

# --- config (override via env) -----------------------------------------------
COLLECTION="${COLLECTION:-pagination-matrix.postman_collection.json}"
ENV_FILE="${ENV_FILE:-local.postman_environment.json}"
NETWORK="${COMPOSE_NETWORK:-opa-abac-example_default}"
KEYCLOAK_TOKEN_URL="${KEYCLOAK_TOKEN_URL:-http://keycloak:8888/realms/catalog-demo/protocol/openid-connect/token}"
CLIENT_ID="${CLIENT_ID:-catalog-gateway}"
CLIENT_SECRET="${CLIENT_SECRET:-catalog-gateway-secret}"
USER_SERVICE="${USER_SERVICE:-http://localhost:28090}"
PG_CONTAINER="${PG_CONTAINER:-opa-abac-postgres}"
GATEWAY="${GATEWAY:-http://localhost:9085}"
REPORT_DIR="${REPORT_DIR:-build/reports/postman}"
RUN_ID="${E2E_RUN_ID:-pagination-$$}"

# realm user -> role on the team
OWNER_USER="${OWNER_USER:-editor}";             OWNER_PASS="${OWNER_PASS:-editor}"
READER_EMEA_USER="${READER_EMEA_USER:-viewer}"; READER_EMEA_PASS="${READER_EMEA_PASS:-viewer}"
READER_APAC_USER="${READER_APAC_USER:-demo}";   READER_APAC_PASS="${READER_APAC_PASS:-demo}"

# The DEDICATED pagination fixture catalog (see the fixture-id registry in README.md).
DEMO_CATALOG_ID="${DEMO_CATALOG_ID:-77777777-7777-7777-7777-777777777777}"

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
create_category() {
  local token="$1" body="$2"
  curl -s -X POST "$GATEWAY/api/v1/catalogs/$DEMO_CATALOG_ID/categories" \
    -H "Authorization: Bearer $token" -H 'Content-Type: application/json' -d "$body" \
    | json_field id
}

# --- mint tokens -------------------------------------------------------------
echo "==> Minting owner/reader-emea/reader-apac tokens in-network ($NETWORK) ..."
OWNER_TOKEN="$(mint_token "$OWNER_USER" "$OWNER_PASS")"
READER_EMEA_TOKEN="$(mint_token "$READER_EMEA_USER" "$READER_EMEA_PASS")"
READER_APAC_TOKEN="$(mint_token "$READER_APAC_USER" "$READER_APAC_PASS")"
for pair in "owner:$OWNER_TOKEN" "reader-emea:$READER_EMEA_TOKEN" "reader-apac:$READER_APAC_TOKEN"; do
  name="${pair%%:*}"; tok="${pair#*:}"
  [ -n "$tok" ] || { echo "ERROR: no token for '$name'. Is the rig up with OIDC and the user present?" >&2; exit 1; }
done

OWNER_SUB="$(token_sub "$OWNER_TOKEN")"
READER_EMEA_SUB="$(token_sub "$READER_EMEA_TOKEN")"
READER_APAC_SUB="$(token_sub "$READER_APAC_TOKEN")"
echo "  subjects: owner=$OWNER_SUB reader-emea=$READER_EMEA_SUB reader-apac=$READER_APAC_SUB"

# --- seed the dedicated pagination catalog ------------------------------------
echo "==> Seeding pagination demo catalog $DEMO_CATALOG_ID into the catalog DB ..."
# Clean this catalog's Categories from a prior run so the counts (5/3) are deterministic, then upsert
# the catalog row WITH its ltree path (the run-hierarchy-matrix.sh model) — a direct-SQL catalog row
# without a path breaks category creation fail-closed ("parent has no path"). The conflict arm also
# repairs the path, in case a prior run left it NULL. ONLY the 7777… fixture is touched — shared
# fixtures must not be perturbed.
"$RUNTIME" exec -i "$PG_CONTAINER" psql -U catalog -d catalog -v ON_ERROR_STOP=1 >/dev/null <<SQL
CREATE EXTENSION IF NOT EXISTS ltree;
DELETE FROM category WHERE catalog_id = '$DEMO_CATALOG_ID';
INSERT INTO catalog (id, name, created_at, version, tags, path)
VALUES ('$DEMO_CATALOG_ID', 'Pagination demo catalog', now(), 0, '{}'::jsonb,
        CAST('catalog_' || replace('$DEMO_CATALOG_ID','-','') AS ltree))
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, path = EXCLUDED.path;
SQL

# --- bootstrap the team + region-gated roles + memberships -------------------
echo "==> Bootstrapping the team, region-gated roles, and memberships in the user-service ..."
OWNER_UID="$(post_json "$USER_SERVICE/internal/bootstrap/users" "{\"subject\":\"$OWNER_SUB\",\"displayName\":\"$OWNER_USER\"}" | json_field userId)"
READER_EMEA_UID="$(post_json "$USER_SERVICE/internal/bootstrap/users" "{\"subject\":\"$READER_EMEA_SUB\",\"displayName\":\"$READER_EMEA_USER\"}" | json_field userId)"
READER_APAC_UID="$(post_json "$USER_SERVICE/internal/bootstrap/users" "{\"subject\":\"$READER_APAC_SUB\",\"displayName\":\"$READER_APAC_USER\"}" | json_field userId)"

TEAM_ID="$(post_json "$USER_SERVICE/internal/bootstrap/teams" "{\"name\":\"Pagination demo\",\"targetType\":\"catalog\",\"targetId\":\"$DEMO_CATALOG_ID\"}" | json_field teamId)"

# The same role shapes as the filter matrix: an ungated curator (creates the rows), two single-region
# readers whose residuals drive the subject-relative counts (5 vs 3).
post_json "$USER_SERVICE/internal/bootstrap/custom-roles" \
  "{\"teamId\":\"$TEAM_ID\",\"code\":\"curator\",\"roleLevel\":20,\"permissions\":{\"catalog\":[\"READ\",\"WRITE\",\"TAG\"],\"category\":[\"READ\",\"WRITE\",\"TAG\"]}}" >/dev/null
post_json "$USER_SERVICE/internal/bootstrap/custom-roles" \
  "{\"teamId\":\"$TEAM_ID\",\"code\":\"emea-reader\",\"roleLevel\":10,\"permissions\":{\"catalog\":[\"READ\"],\"category\":[\"READ\"]},\"requiredTags\":{\"region\":[\"emea\"]},\"matchMode\":\"ANY_OF\"}" >/dev/null
post_json "$USER_SERVICE/internal/bootstrap/custom-roles" \
  "{\"teamId\":\"$TEAM_ID\",\"code\":\"apac-reader\",\"roleLevel\":10,\"permissions\":{\"catalog\":[\"READ\"],\"category\":[\"READ\"]},\"requiredTags\":{\"region\":[\"apac\"]},\"matchMode\":\"ANY_OF\"}" >/dev/null

post_json "$USER_SERVICE/internal/bootstrap/memberships" "{\"teamId\":\"$TEAM_ID\",\"userId\":\"$OWNER_UID\",\"roleCode\":\"curator\"}" >/dev/null
post_json "$USER_SERVICE/internal/bootstrap/memberships" "{\"teamId\":\"$TEAM_ID\",\"userId\":\"$READER_EMEA_UID\",\"roleCode\":\"emea-reader\"}" >/dev/null
post_json "$USER_SERVICE/internal/bootstrap/memberships" "{\"teamId\":\"$TEAM_ID\",\"userId\":\"$READER_APAC_UID\",\"roleCode\":\"apac-reader\"}" >/dev/null
echo "  team $TEAM_ID governs catalog $DEMO_CATALOG_ID (curator + emea-reader + apac-reader bound)."

# --- create the pagination fixture set via the gateway (curator) --------------
echo "==> Creating the pagination fixture set (5 EMEA + 3 APAC categories) through the gateway ..."
EMEA_IDS=()
for i in 1 2 3 4 5; do
  id="$(create_category "$OWNER_TOKEN" "{\"name\":\"EMEA-$i\",\"tags\":{\"region\":[\"emea\"]}}")"
  [ -n "$id" ] && [ "$id" != "None" ] || { echo "ERROR: failed to create EMEA-$i." >&2; exit 1; }
  EMEA_IDS+=("$id")
done
APAC_IDS=()
for i in 1 2 3; do
  id="$(create_category "$OWNER_TOKEN" "{\"name\":\"APAC-$i\",\"tags\":{\"region\":[\"apac\"]}}")"
  [ -n "$id" ] && [ "$id" != "None" ] || { echo "ERROR: failed to create APAC-$i." >&2; exit 1; }
  APAC_IDS+=("$id")
done
echo "  emea: ${EMEA_IDS[*]}"
echo "  apac: ${APAC_IDS[*]}"

# --- run newman --------------------------------------------------------------
mkdir -p "$REPORT_DIR/$RUN_ID"
echo "==> newman run $COLLECTION (the pagination envelope through the gateway)"
newman run "$COLLECTION" \
  -e "$ENV_FILE" \
  --env-var "gateway=$GATEWAY" \
    --env-var "collection_base_url=$GATEWAY/api/v1" \
  --env-var "catalog_id=$DEMO_CATALOG_ID" \
  --env-var "reader_emea_token=$READER_EMEA_TOKEN" \
  --env-var "reader_apac_token=$READER_APAC_TOKEN" \
  --reporter-cli \
  --reporter-json-export "$REPORT_DIR/$RUN_ID/pagination-matrix-report.json"

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
