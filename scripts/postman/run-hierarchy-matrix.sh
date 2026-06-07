#!/usr/bin/env bash
#
# Run the hierarchical ABAC matrix (hierarchy-abac-matrix.postman_collection.json) through the local rig.
#
# Proves Phase 5.5-A single-resource hierarchical authorization end to end:
#   - INHERITANCE: a member granted `read` on the CATALOG reads a Category nested under it (200) — the
#     grant is resolved once on the governing root and inherited down the ancestor chain.
#   - DENY-OVERRIDES: a Category carrying an explicit leaf deny (tag abac_deny=true) is carved out (403)
#     even though the Catalog grant would inherit, while a SIBLING Category stays readable (200).
#   - RE-PARENT FLIPS A DECISION: a movable Category is readable while under the granted Catalog (200);
#     after it is re-parented under a DIFFERENT Catalog the member can't see, it becomes denied (403).
#
# Prereq: the full rig is up WITH OIDC + OPA + the user-service, AND hierarchy enabled (default on):
#   ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2
#
# After editing a rego policy, restart OPA so it reloads:  docker restart opa-abac-opa
#
# Wiring (ids/tokens are only known at run time): mint in-network tokens; seed two Catalogs (granted +
# foreign); create a team on the granted Catalog binding the reader to a role that reads catalog+category;
# create Categories (open / denied / sibling / movable) through the gateway; run the inheritance + deny
# checks; then re-parent the movable Category under the FOREIGN catalog (via the catalog DB, rewriting the
# ltree path of the subtree + its products) and assert the read flips to 403.
#
# Honors the in-network token caveat (APISIX validates issuer http://keycloak:8888) and keeps runtime ids
# in the collection variable scope.

set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SELF_DIR"

# --- config (override via env) -----------------------------------------------
COLLECTION="${COLLECTION:-hierarchy-abac-matrix.postman_collection.json}"
ENV_FILE="${ENV_FILE:-local.postman_environment.json}"
NETWORK="${COMPOSE_NETWORK:-opa-abac-example_default}"
KEYCLOAK_TOKEN_URL="${KEYCLOAK_TOKEN_URL:-http://keycloak:8888/realms/catalog-demo/protocol/openid-connect/token}"
CLIENT_ID="${CLIENT_ID:-catalog-gateway}"
CLIENT_SECRET="${CLIENT_SECRET:-catalog-gateway-secret}"
USER_SERVICE="${USER_SERVICE:-http://localhost:28090}"
PG_CONTAINER="${PG_CONTAINER:-opa-abac-postgres}"
GATEWAY="${GATEWAY:-http://localhost:9085}"
REPORT_DIR="${REPORT_DIR:-build/reports/postman}"
RUN_ID="${E2E_RUN_ID:-hierarchy-$$}"

OWNER_USER="${OWNER_USER:-editor}";   OWNER_PASS="${OWNER_PASS:-editor}"
READER_USER="${READER_USER:-viewer}"; READER_PASS="${READER_PASS:-viewer}"

GRANTED_CATALOG_ID="${GRANTED_CATALOG_ID:-33333333-3333-3333-3333-333333333333}"
FOREIGN_CATALOG_ID="${FOREIGN_CATALOG_ID:-44444444-4444-4444-4444-444444444444}"

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
  local token="$1" catalog="$2" body="$3" resp
  resp="$(curl -s -X POST "$GATEWAY/api/v1/catalogs/$catalog/categories" \
    -H "Authorization: Bearer $token" -H 'Content-Type: application/json' -d "$body")"
  printf '%s' "$resp" | json_field id 2>/dev/null || {
    echo "ERROR: create_category failed (catalog=$catalog): $resp" >&2
    return 1
  }
}
read_status() {
  local token="$1" catalog="$2" category="$3"
  curl -s -o /dev/null -w '%{http_code}' "$GATEWAY/api/v1/catalogs/$catalog/categories/$category" \
    -H "Authorization: Bearer $token"
}

# --- mint tokens -------------------------------------------------------------
echo "==> Minting owner/reader tokens in-network ($NETWORK) ..."
OWNER_TOKEN="$(mint_token "$OWNER_USER" "$OWNER_PASS")"
READER_TOKEN="$(mint_token "$READER_USER" "$READER_PASS")"
[ -n "$OWNER_TOKEN" ] || { echo "ERROR: no owner token. Is the rig up with OIDC?" >&2; exit 1; }
[ -n "$READER_TOKEN" ] || { echo "ERROR: no reader token." >&2; exit 1; }
OWNER_SUB="$(token_sub "$OWNER_TOKEN")"; READER_SUB="$(token_sub "$READER_TOKEN")"
echo "  subjects: owner=$OWNER_SUB reader=$READER_SUB"

# --- seed the granted + foreign catalogs (with their ltree paths) ------------
echo "==> Seeding the granted + foreign Catalogs into the catalog DB ..."
"$RUNTIME" exec -i "$PG_CONTAINER" psql -U catalog -d catalog -v ON_ERROR_STOP=1 >/dev/null <<SQL
CREATE EXTENSION IF NOT EXISTS ltree;
INSERT INTO catalog (id, name, created_at, version, tags, path)
VALUES ('$GRANTED_CATALOG_ID', 'Granted', now(), 0, '{}'::jsonb,
        CAST('catalog_' || replace('$GRANTED_CATALOG_ID','-','') AS ltree))
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name;
INSERT INTO catalog (id, name, created_at, version, tags, path)
VALUES ('$FOREIGN_CATALOG_ID', 'Foreign', now(), 0, '{}'::jsonb,
        CAST('catalog_' || replace('$FOREIGN_CATALOG_ID','-','') AS ltree))
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name;
SQL

# --- bootstrap the team granting the reader `read` on the granted Catalog ----
echo "==> Bootstrapping the team + membership (reader reads catalog+category) ..."
OWNER_UID="$(post_json "$USER_SERVICE/internal/bootstrap/users" "{\"subject\":\"$OWNER_SUB\",\"displayName\":\"Owner\"}" | json_field userId)"
READER_UID="$(post_json "$USER_SERVICE/internal/bootstrap/users" "{\"subject\":\"$READER_SUB\",\"displayName\":\"Reader\"}" | json_field userId)"
TEAM_ID="$(post_json "$USER_SERVICE/internal/bootstrap/teams" "{\"name\":\"Hierarchy demo\",\"targetType\":\"catalog\",\"targetId\":\"$GRANTED_CATALOG_ID\"}" | json_field teamId)"
# A 'catalog-reader' role: read on catalog + category (so a Category inherits the Catalog grant).
post_json "$USER_SERVICE/internal/bootstrap/custom-roles" \
  "{\"teamId\":\"$TEAM_ID\",\"code\":\"catalog-reader\",\"permissions\":{\"catalog\":[\"read\"],\"category\":[\"read\"]}}" >/dev/null
post_json "$USER_SERVICE/internal/bootstrap/memberships" "{\"teamId\":\"$TEAM_ID\",\"userId\":\"$OWNER_UID\",\"roleCode\":\"owner\"}" >/dev/null
post_json "$USER_SERVICE/internal/bootstrap/memberships" "{\"teamId\":\"$TEAM_ID\",\"userId\":\"$READER_UID\",\"roleCode\":\"catalog-reader\"}" >/dev/null
echo "  team $TEAM_ID governs catalog $GRANTED_CATALOG_ID."

# --- create Categories via the gateway (owner) -------------------------------
echo "==> Creating Categories (open / denied / sibling / movable) through the gateway ..."
OPEN_CATEGORY_ID="$(create_category "$OWNER_TOKEN" "$GRANTED_CATALOG_ID" '{"name":"Open"}')"
DENIED_CATEGORY_ID="$(create_category "$OWNER_TOKEN" "$GRANTED_CATALOG_ID" '{"name":"Denied"}')"
SIBLING_CATEGORY_ID="$(create_category "$OWNER_TOKEN" "$GRANTED_CATALOG_ID" '{"name":"Sibling"}')"
MOVABLE_CATEGORY_ID="$(create_category "$OWNER_TOKEN" "$GRANTED_CATALOG_ID" '{"name":"Movable"}')"
for pair in "open:$OPEN_CATEGORY_ID" "denied:$DENIED_CATEGORY_ID" "sibling:$SIBLING_CATEGORY_ID" "movable:$MOVABLE_CATEGORY_ID"; do
  name="${pair%%:*}"; id="${pair#*:}"
  [ -n "$id" ] && [ "$id" != "None" ] || { echo "ERROR: failed to create the '$name' Category." >&2; exit 1; }
done
echo "  open=$OPEN_CATEGORY_ID denied=$DENIED_CATEGORY_ID sibling=$SIBLING_CATEGORY_ID movable=$MOVABLE_CATEGORY_ID"

# Set the deny-overrides flag on the 'denied' Category directly in the DB. `abac_deny` is an operational
# control flag (read by the policy as a deny-override), not a user-facing dictionary tag — so the gateway's
# tag-dictionary validation (Phase 4.5) correctly rejects it on create; we set it out-of-band here.
"$RUNTIME" exec -i "$PG_CONTAINER" psql -U catalog -d catalog -v ON_ERROR_STOP=1 >/dev/null <<SQL
UPDATE category SET tags = jsonb_set(coalesce(tags,'{}'::jsonb), '{abac_deny}', 'true')
 WHERE id = '$DENIED_CATEGORY_ID';
SQL

# --- run the inheritance + deny-overrides + pre-reparent checks --------------
mkdir -p "$REPORT_DIR/$RUN_ID"
echo "==> newman run $COLLECTION (inheritance + deny-overrides + pre-reparent)"
newman run "$COLLECTION" \
  -e "$ENV_FILE" \
  --env-var "gateway=$GATEWAY" \
  --env-var "catalog_id=$GRANTED_CATALOG_ID" \
  --env-var "open_category_id=$OPEN_CATEGORY_ID" \
  --env-var "denied_category_id=$DENIED_CATEGORY_ID" \
  --env-var "sibling_category_id=$SIBLING_CATEGORY_ID" \
  --env-var "movable_category_id=$MOVABLE_CATEGORY_ID" \
  --env-var "reader_token=$READER_TOKEN" \
  --reporter-cli \
  --reporter-json-export "$REPORT_DIR/$RUN_ID/hierarchy-abac-matrix-report.json"

# --- RE-PARENT FLIPS A DECISION ---------------------------------------------
# Move the movable Category under the FOREIGN catalog (which the reader cannot see). Rewrite the ltree
# `path` of the Category subtree AND its descendant Products, and the adjacency (catalog_id), in one tx —
# exactly what CatalogHierarchyService.reparentCategory does in-app, applied here directly to the DB so the
# e2e doesn't need a re-parent endpoint.
echo "==> Re-parenting the movable Category under the foreign Catalog (ltree subtree rewrite) ..."
"$RUNTIME" exec -i "$PG_CONTAINER" psql -U catalog -d catalog -v ON_ERROR_STOP=1 >/dev/null <<SQL
DO \$\$
DECLARE
  old_path ltree;
  new_path ltree;
  old_depth int;
BEGIN
  SELECT path INTO old_path FROM category WHERE id = '$MOVABLE_CATEGORY_ID';
  old_depth := nlevel(old_path);
  new_path := CAST('catalog_' || replace('$FOREIGN_CATALOG_ID','-','') AS ltree)
              || CAST('category_' || replace('$MOVABLE_CATEGORY_ID','-','') AS ltree);
  -- rewrite the Category subtree
  UPDATE category SET path = CASE WHEN nlevel(path) = old_depth THEN new_path
                                  ELSE new_path || subpath(path, old_depth) END
   WHERE path <@ old_path;
  -- rewrite descendant Products
  UPDATE product SET path = new_path || subpath(path, old_depth)
   WHERE path <@ old_path;
  -- move the adjacency to the foreign catalog
  UPDATE category SET catalog_id = '$FOREIGN_CATALOG_ID' WHERE id = '$MOVABLE_CATEGORY_ID';
END \$\$;
SQL

echo "==> Asserting the decision FLIPPED: the moved Category is now denied to the reader (403) ..."
MOVED_STATUS="$(read_status "$READER_TOKEN" "$FOREIGN_CATALOG_ID" "$MOVABLE_CATEGORY_ID")"
if [ "$MOVED_STATUS" = "403" ]; then
  echo "  PASS: re-parent flipped the decision (moved Category -> 403 under the foreign Catalog)."
else
  echo "  FAIL: expected 403 after re-parent, got $MOVED_STATUS" >&2
  exit 1
fi

echo "==> Hierarchy ABAC matrix: all checks passed (inheritance, deny-overrides, re-parent flip)."
