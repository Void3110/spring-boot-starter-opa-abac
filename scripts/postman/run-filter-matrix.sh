#!/usr/bin/env bash
#
# Run the data-filtering matrix (data-filter-matrix.postman_collection.json) through the local rig.
#
# Proves OPA partial-evaluation LIST filtering end to end: two tag-gated readers hit the SAME list
# endpoint (GET /catalogs/{id}/categories) and get DIFFERENT row sets — filtered in SQL by the residual
# the Compile API returns — while an allow-all owner sees every row and a stranger with NO role definition
# sees NONE (the `filter` rule has no subject-roles fallback, so a missing role fails CLOSED to an empty
# list, never the whole table). The decisive contrast is the row SET, not a single 200/403.
#
# Prereq: the full rig is up WITH OIDC + OPA + the user-service:
#   ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2
# Restart OPA after editing category.rego (--watch doesn't always reload).
#
# How it wires the demo data at run time (ids/subjects are only known after tokens are minted and rows
# created):
#   1. mint in-network tokens for owner(editor) / reader-emea(viewer) / reader-apac(demo) / stranger(<a
#      realm user with NO team membership>);
#   2. seed a fixed demo catalog row into the catalog DB (the team-target);
#   3. via the user-service internal API, create a team for that catalog and bind: owner->a `curator`
#      role (ungated category read+write — the allow-all subject; the system `owner` grants only `catalog`
#      verbs, so it can't read categories), reader-emea->a role requiring region ANY_OF [emea],
#      reader-apac->a role requiring region ANY_OF [apac]. The stranger is deliberately NOT bound;
#   4. via the GATEWAY (curator token), create three Categories tagged region=emea / region=apac /
#      region=amer;
#   5. run newman: reader-emea sees only emea, reader-apac sees only apac (a DIFFERENT set), curator sees
#      all three, stranger sees none.
#
# Honors the in-network token caveat (APISIX validates issuer http://keycloak:8888) and keeps the
# runtime-captured ids in the collection variable scope.

set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SELF_DIR"

# --- config (override via env) -----------------------------------------------
COLLECTION="${COLLECTION:-data-filter-matrix.postman_collection.json}"
ENV_FILE="${ENV_FILE:-local.postman_environment.json}"
NETWORK="${COMPOSE_NETWORK:-opa-abac-example_default}"
KEYCLOAK_TOKEN_URL="${KEYCLOAK_TOKEN_URL:-http://keycloak:8888/realms/catalog-demo/protocol/openid-connect/token}"
CLIENT_ID="${CLIENT_ID:-catalog-gateway}"
CLIENT_SECRET="${CLIENT_SECRET:-catalog-gateway-secret}"
USER_SERVICE="${USER_SERVICE:-http://localhost:28090}"
PG_CONTAINER="${PG_CONTAINER:-opa-abac-postgres}"
GATEWAY="${GATEWAY:-http://localhost:9085}"
REPORT_DIR="${REPORT_DIR:-build/reports/postman}"
RUN_ID="${E2E_RUN_ID:-filter-$$}"

# realm user -> role on the team
OWNER_USER="${OWNER_USER:-editor}";        OWNER_PASS="${OWNER_PASS:-editor}"
READER_EMEA_USER="${READER_EMEA_USER:-viewer}"; READER_EMEA_PASS="${READER_EMEA_PASS:-viewer}"
READER_APAC_USER="${READER_APAC_USER:-demo}";   READER_APAC_PASS="${READER_APAC_PASS:-demo}"
# The stranger is a realm user who is NEVER bound to the team -> resolves no role definition.
# `outsider` is the rig's standing unbound-user (also used as the non-member in run-team-matrix.sh).
STRANGER_USER="${STRANGER_USER:-outsider}"; STRANGER_PASS="${STRANGER_PASS:-outsider}"

DEMO_CATALOG_ID="${DEMO_CATALOG_ID:-33333333-3333-3333-3333-333333333333}"

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
echo "==> Minting owner/reader-emea/reader-apac/stranger tokens in-network ($NETWORK) ..."
OWNER_TOKEN="$(mint_token "$OWNER_USER" "$OWNER_PASS")"
READER_EMEA_TOKEN="$(mint_token "$READER_EMEA_USER" "$READER_EMEA_PASS")"
READER_APAC_TOKEN="$(mint_token "$READER_APAC_USER" "$READER_APAC_PASS")"
STRANGER_TOKEN="$(mint_token "$STRANGER_USER" "$STRANGER_PASS")"
for pair in "owner:$OWNER_TOKEN" "reader-emea:$READER_EMEA_TOKEN" "reader-apac:$READER_APAC_TOKEN" "stranger:$STRANGER_TOKEN"; do
  name="${pair%%:*}"; tok="${pair#*:}"
  [ -n "$tok" ] || { echo "ERROR: no token for '$name'. Is the rig up with OIDC and the user present?" >&2; exit 1; }
done

OWNER_SUB="$(token_sub "$OWNER_TOKEN")"
READER_EMEA_SUB="$(token_sub "$READER_EMEA_TOKEN")"
READER_APAC_SUB="$(token_sub "$READER_APAC_TOKEN")"
echo "  subjects: owner=$OWNER_SUB reader-emea=$READER_EMEA_SUB reader-apac=$READER_APAC_SUB (stranger unbound)"

# --- seed the demo catalog (the team-target) ---------------------------------
echo "==> Seeding demo catalog $DEMO_CATALOG_ID into the catalog DB ..."
# Clean any Categories from a prior run first, so the row counts are deterministic (the create endpoint
# isn't idempotent). Then upsert the catalog.
"$RUNTIME" exec -i "$PG_CONTAINER" psql -U catalog -d catalog -v ON_ERROR_STOP=1 >/dev/null <<SQL
DELETE FROM category WHERE catalog_id = '$DEMO_CATALOG_ID';
INSERT INTO catalog (id, name, created_at, version, tags, path)
VALUES ('$DEMO_CATALOG_ID', 'Filter demo catalog', now(), 0, '{}'::jsonb,
        CAST('catalog_' || replace('$DEMO_CATALOG_ID','-','') AS ltree))
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, path = EXCLUDED.path;
SQL

# --- bootstrap the team + region-gated roles + memberships -------------------
echo "==> Bootstrapping the team, region-gated roles, and memberships in the user-service ..."
OWNER_UID="$(post_json "$USER_SERVICE/internal/bootstrap/users" "{\"subject\":\"$OWNER_SUB\",\"displayName\":\"Owner\"}" | json_field userId)"
READER_EMEA_UID="$(post_json "$USER_SERVICE/internal/bootstrap/users" "{\"subject\":\"$READER_EMEA_SUB\",\"displayName\":\"Reader EMEA\"}" | json_field userId)"
READER_APAC_UID="$(post_json "$USER_SERVICE/internal/bootstrap/users" "{\"subject\":\"$READER_APAC_SUB\",\"displayName\":\"Reader APAC\"}" | json_field userId)"

TEAM_ID="$(post_json "$USER_SERVICE/internal/bootstrap/teams" "{\"name\":\"Filter demo\",\"targetType\":\"catalog\",\"targetId\":\"$DEMO_CATALOG_ID\"}" | json_field teamId)"

# Two single-region readers (each gated to one region), plus an allow-all curator for the owner.
# The system `owner` role grants only `catalog` verbs (not `category`), so the list filter — which checks
# permissions[category] — would see nothing under it. The demo's "allow-all" subject needs an UNGATED
# `category` read (no requiredTags), so it gets a custom curator role with category read+write.
post_json "$USER_SERVICE/internal/bootstrap/custom-roles" \
  "{\"teamId\":\"$TEAM_ID\",\"code\":\"curator\",\"permissions\":{\"catalog\":[\"read\",\"write\"],\"category\":[\"read\",\"write\"]}}" >/dev/null
post_json "$USER_SERVICE/internal/bootstrap/custom-roles" \
  "{\"teamId\":\"$TEAM_ID\",\"code\":\"emea-reader\",\"permissions\":{\"catalog\":[\"read\"],\"category\":[\"read\"]},\"requiredTags\":{\"region\":[\"emea\"]},\"matchMode\":\"ANY_OF\"}" >/dev/null
post_json "$USER_SERVICE/internal/bootstrap/custom-roles" \
  "{\"teamId\":\"$TEAM_ID\",\"code\":\"apac-reader\",\"permissions\":{\"catalog\":[\"read\"],\"category\":[\"read\"]},\"requiredTags\":{\"region\":[\"apac\"]},\"matchMode\":\"ANY_OF\"}" >/dev/null

post_json "$USER_SERVICE/internal/bootstrap/memberships" "{\"teamId\":\"$TEAM_ID\",\"userId\":\"$OWNER_UID\",\"roleCode\":\"curator\"}" >/dev/null
post_json "$USER_SERVICE/internal/bootstrap/memberships" "{\"teamId\":\"$TEAM_ID\",\"userId\":\"$READER_EMEA_UID\",\"roleCode\":\"emea-reader\"}" >/dev/null
post_json "$USER_SERVICE/internal/bootstrap/memberships" "{\"teamId\":\"$TEAM_ID\",\"userId\":\"$READER_APAC_UID\",\"roleCode\":\"apac-reader\"}" >/dev/null
# NOTE: the stranger is intentionally NOT given a membership -> the supplier resolves no role definition.
echo "  team $TEAM_ID governs catalog $DEMO_CATALOG_ID (curator + emea-reader + apac-reader bound; stranger unbound)."

# --- create three region-tagged Categories via the gateway (owner) -----------
echo "==> Creating region-tagged Categories through the gateway ..."
EMEA_CATEGORY_ID="$(create_category "$OWNER_TOKEN" '{"name":"EMEA","tags":{"region":["emea"]}}')"
APAC_CATEGORY_ID="$(create_category "$OWNER_TOKEN" '{"name":"APAC","tags":{"region":["apac"]}}')"
AMER_CATEGORY_ID="$(create_category "$OWNER_TOKEN" '{"name":"AMER","tags":{"region":["amer"]}}')"
echo "  emea=$EMEA_CATEGORY_ID apac=$APAC_CATEGORY_ID amer=$AMER_CATEGORY_ID"
for pair in "emea:$EMEA_CATEGORY_ID" "apac:$APAC_CATEGORY_ID" "amer:$AMER_CATEGORY_ID"; do
  name="${pair%%:*}"; id="${pair#*:}"
  [ -n "$id" ] && [ "$id" != "None" ] || { echo "ERROR: failed to create the '$name' Category. Is the owner write path working?" >&2; exit 1; }
done

# --- run newman --------------------------------------------------------------
mkdir -p "$REPORT_DIR/$RUN_ID"
echo "==> newman run $COLLECTION (data-filtering matrix through the gateway)"
newman run "$COLLECTION" \
  -e "$ENV_FILE" \
  --env-var "gateway=$GATEWAY" \
  --env-var "catalog_id=$DEMO_CATALOG_ID" \
  --env-var "emea_category_id=$EMEA_CATEGORY_ID" \
  --env-var "apac_category_id=$APAC_CATEGORY_ID" \
  --env-var "amer_category_id=$AMER_CATEGORY_ID" \
  --env-var "owner_token=$OWNER_TOKEN" \
  --env-var "reader_emea_token=$READER_EMEA_TOKEN" \
  --env-var "reader_apac_token=$READER_APAC_TOKEN" \
  --env-var "stranger_token=$STRANGER_TOKEN" \
  --reporter-cli \
  --reporter-json-export "$REPORT_DIR/$RUN_ID/data-filter-matrix-report.json"
