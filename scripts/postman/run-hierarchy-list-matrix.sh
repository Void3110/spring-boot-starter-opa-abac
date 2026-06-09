#!/usr/bin/env bash
#
# Run the hierarchy-aware LIST filter matrix (hierarchy-list-matrix.postman_collection.json) through the
# local rig. This is Slice 5.5-B: it composes the shipped Phase-5 partial-eval list filter with the 5.5-A
# ancestor resolver so an inheritable CATALOG grant WIDENS a category list to the whole catalog subtree —
# proven as a per-subject SQL row-set difference through the gateway.
#
# What it proves (GET /catalogs/{id}/categories):
#   E1 — an inherit reader (role grants read on the CATALOG only, NO category tag grant) sees the whole
#        catalog subtree (the emea + apac categories) — rows its own leaf-tags would never surface. The
#        coarse list gate (the 5.5-B `allow` list clause) lets the inheritable grant through; the SQL
#        subtreeSpec is what widens the rows.
#   E2 — a region reader (tag-gated to region=emea, with its own category:read) sees ONLY the emea row — a
#        DIFFERENT set on the SAME endpoint (the decisive proof the cut is in SQL).
#   E1 — a leaf abac_deny category is ABSENT from the inherit reader's widened list (deny-overrides).
#   E4 — the unbound stranger (no role definition) gets [] / 403 (fail-closed; the filter rule has no
#        subject-roles fallback, and the list gate finds no inheritable grant).
#   re-parent — moving the apac category subtree under a FOREIGN catalog (the same ltree subtree rewrite
#        CatalogHierarchyService.reparentCategory does) makes it LEAVE catalog C's widened list (post pass).
#
# Prereq: the full rig is up WITH OIDC + OPA + the user-service:
#   ./profile.sh up                                   # base Postgres first
#   ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2
#   ./deploy.sh build                                 # force new app code into the pods (5.5-B adoption)
# Restart/redeploy OPA after editing category.rego (--watch doesn't always reload — confirm via /v1/compile).
#
# Honors the in-network token caveat (APISIX validates issuer http://keycloak:8888) and keeps the
# runtime-captured ids in the COLLECTION variable scope.

set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SELF_DIR"

# --- config (override via env) -----------------------------------------------
COLLECTION="${COLLECTION:-hierarchy-list-matrix.postman_collection.json}"
ENV_FILE="${ENV_FILE:-local.postman_environment.json}"
NETWORK="${COMPOSE_NETWORK:-opa-abac-example_default}"
KEYCLOAK_TOKEN_URL="${KEYCLOAK_TOKEN_URL:-http://keycloak:8888/realms/catalog-demo/protocol/openid-connect/token}"
CLIENT_ID="${CLIENT_ID:-catalog-gateway}"
CLIENT_SECRET="${CLIENT_SECRET:-catalog-gateway-secret}"
USER_SERVICE="${USER_SERVICE:-http://localhost:28090}"
PG_CONTAINER="${PG_CONTAINER:-opa-abac-postgres}"
GATEWAY="${GATEWAY:-http://localhost:9085}"
REPORT_DIR="${REPORT_DIR:-build/reports/postman}"
RUN_ID="${E2E_RUN_ID:-hierlist-$$}"

# realm user -> role on the team
OWNER_USER="${OWNER_USER:-editor}";        OWNER_PASS="${OWNER_PASS:-editor}"
INHERIT_USER="${INHERIT_USER:-viewer}";    INHERIT_PASS="${INHERIT_PASS:-viewer}"
REGION_USER="${REGION_USER:-demo}";        REGION_PASS="${REGION_PASS:-demo}"
# The stranger is a realm user NEVER bound to the team -> resolves no role definition.
STRANGER_USER="${STRANGER_USER:-outsider}"; STRANGER_PASS="${STRANGER_PASS:-outsider}"

CATALOG_ID="${CATALOG_ID:-44444444-4444-4444-4444-444444444444}"
FOREIGN_CATALOG_ID="${FOREIGN_CATALOG_ID:-55555555-5555-5555-5555-555555555555}"

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
  curl -s -X POST "$GATEWAY/api/v1/catalogs/$CATALOG_ID/categories" \
    -H "Authorization: Bearer $token" -H 'Content-Type: application/json' -d "$body" \
    | json_field id
}

# --- mint tokens -------------------------------------------------------------
echo "==> Minting owner/inherit/region/stranger tokens in-network ($NETWORK) ..."
OWNER_TOKEN="$(mint_token "$OWNER_USER" "$OWNER_PASS")"
INHERIT_TOKEN="$(mint_token "$INHERIT_USER" "$INHERIT_PASS")"
REGION_TOKEN="$(mint_token "$REGION_USER" "$REGION_PASS")"
STRANGER_TOKEN="$(mint_token "$STRANGER_USER" "$STRANGER_PASS")"
for pair in "owner:$OWNER_TOKEN" "inherit:$INHERIT_TOKEN" "region:$REGION_TOKEN" "stranger:$STRANGER_TOKEN"; do
  name="${pair%%:*}"; tok="${pair#*:}"
  [ -n "$tok" ] || { echo "ERROR: no token for '$name'. Is the rig up with OIDC and the user present?" >&2; exit 1; }
done

OWNER_SUB="$(token_sub "$OWNER_TOKEN")"
INHERIT_SUB="$(token_sub "$INHERIT_TOKEN")"
REGION_SUB="$(token_sub "$REGION_TOKEN")"
echo "  subjects: owner=$OWNER_SUB inherit=$INHERIT_SUB region=$REGION_SUB (stranger unbound)"

# --- seed the catalog + foreign catalog (with their ltree paths) -------------
echo "==> Seeding catalog $CATALOG_ID + foreign $FOREIGN_CATALOG_ID into the catalog DB ..."
"$RUNTIME" exec -i "$PG_CONTAINER" psql -U catalog -d catalog -v ON_ERROR_STOP=1 >/dev/null <<SQL
DELETE FROM category WHERE catalog_id IN ('$CATALOG_ID','$FOREIGN_CATALOG_ID');
INSERT INTO catalog (id, name, created_at, version, tags, path)
VALUES ('$CATALOG_ID', 'Hierarchy-list demo', now(), 0, '{}'::jsonb,
        CAST('catalog_' || replace('$CATALOG_ID','-','') AS ltree))
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name;
INSERT INTO catalog (id, name, created_at, version, tags, path)
VALUES ('$FOREIGN_CATALOG_ID', 'Foreign catalog', now(), 0, '{}'::jsonb,
        CAST('catalog_' || replace('$FOREIGN_CATALOG_ID','-','') AS ltree))
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name;
SQL

# --- bootstrap the team + inherit/region roles + memberships -----------------
echo "==> Bootstrapping the team, inherit + region roles, and memberships in the user-service ..."
OWNER_UID="$(post_json "$USER_SERVICE/internal/bootstrap/users" "{\"subject\":\"$OWNER_SUB\",\"displayName\":\"Owner\"}" | json_field userId)"
INHERIT_UID="$(post_json "$USER_SERVICE/internal/bootstrap/users" "{\"subject\":\"$INHERIT_SUB\",\"displayName\":\"Inherit reader\"}" | json_field userId)"
REGION_UID="$(post_json "$USER_SERVICE/internal/bootstrap/users" "{\"subject\":\"$REGION_SUB\",\"displayName\":\"Region reader\"}" | json_field userId)"

TEAM_ID="$(post_json "$USER_SERVICE/internal/bootstrap/teams" "{\"name\":\"Hierarchy-list demo\",\"targetType\":\"catalog\",\"targetId\":\"$CATALOG_ID\"}" | json_field teamId)"

# An UNGATED owner-curator (category read+write, no tag requirement) so the owner can create Categories.
post_json "$USER_SERVICE/internal/bootstrap/custom-roles" \
  "{\"teamId\":\"$TEAM_ID\",\"code\":\"curator\",\"permissions\":{\"catalog\":[\"read\",\"write\"],\"category\":[\"read\",\"write\"]}}" >/dev/null
# The INHERIT reader: read on the CATALOG ONLY (no category grant). A readable Category in its list therefore
# proves the decision came from the inheritable catalog grant (the subtreeSpec widening), not a direct tag
# grant — this isolates the 5.5-B CUT.
post_json "$USER_SERVICE/internal/bootstrap/custom-roles" \
  "{\"teamId\":\"$TEAM_ID\",\"code\":\"catalog-reader\",\"permissions\":{\"catalog\":[\"read\"]}}" >/dev/null
# The REGION reader: a direct category:read gated to region=emea (a tag-only Phase-5 reader, no widening).
post_json "$USER_SERVICE/internal/bootstrap/custom-roles" \
  "{\"teamId\":\"$TEAM_ID\",\"code\":\"emea-reader\",\"permissions\":{\"catalog\":[\"read\"],\"category\":[\"read\"]},\"requiredTags\":{\"region\":[\"emea\"]},\"matchMode\":\"ANY_OF\"}" >/dev/null

post_json "$USER_SERVICE/internal/bootstrap/memberships" "{\"teamId\":\"$TEAM_ID\",\"userId\":\"$OWNER_UID\",\"roleCode\":\"curator\"}" >/dev/null
post_json "$USER_SERVICE/internal/bootstrap/memberships" "{\"teamId\":\"$TEAM_ID\",\"userId\":\"$INHERIT_UID\",\"roleCode\":\"catalog-reader\"}" >/dev/null
post_json "$USER_SERVICE/internal/bootstrap/memberships" "{\"teamId\":\"$TEAM_ID\",\"userId\":\"$REGION_UID\",\"roleCode\":\"emea-reader\"}" >/dev/null
# NOTE: the stranger is intentionally NOT given a membership -> the supplier resolves no role definition.
echo "  team $TEAM_ID governs catalog $CATALOG_ID (curator + catalog-reader + emea-reader bound; stranger unbound)."

# --- create the categories via the gateway (owner) ---------------------------
echo "==> Creating region-tagged Categories (emea / apac / denied) through the gateway ..."
EMEA_CATEGORY_ID="$(create_category "$OWNER_TOKEN" '{"name":"EMEA","tags":{"region":["emea"]}}')"
APAC_CATEGORY_ID="$(create_category "$OWNER_TOKEN" '{"name":"APAC","tags":{"region":["apac"]}}')"
DENY_CATEGORY_ID="$(create_category "$OWNER_TOKEN" '{"name":"Denied","tags":{"region":["emea"]}}')"
echo "  emea=$EMEA_CATEGORY_ID apac=$APAC_CATEGORY_ID deny=$DENY_CATEGORY_ID"
for pair in "emea:$EMEA_CATEGORY_ID" "apac:$APAC_CATEGORY_ID" "deny:$DENY_CATEGORY_ID"; do
  name="${pair%%:*}"; id="${pair#*:}"
  [ -n "$id" ] && [ "$id" != "None" ] || { echo "ERROR: failed to create the '$name' Category." >&2; exit 1; }
done

# Mark the 'denied' Category with the abac_deny control tag directly in the DB (it is an operational
# deny-override flag, not a business tag the dictionary accepts — mx-ecf3d2).
echo "==> Marking the denied Category with abac_deny=true ..."
"$RUNTIME" exec -i "$PG_CONTAINER" psql -U catalog -d catalog -v ON_ERROR_STOP=1 >/dev/null <<SQL
UPDATE category SET tags = jsonb_set(tags, '{abac_deny}', 'true'::jsonb) WHERE id = '$DENY_CATEGORY_ID';
SQL

# --- run newman (PRE re-parent) ----------------------------------------------
mkdir -p "$REPORT_DIR/$RUN_ID"
echo "==> newman run $COLLECTION (PRE re-parent: widening, different-sets, deny, stranger)"
newman run "$COLLECTION" \
  -e "$ENV_FILE" \
  --env-var "gateway=$GATEWAY" \
  --env-var "catalog_id=$CATALOG_ID" \
  --env-var "emea_category_id=$EMEA_CATEGORY_ID" \
  --env-var "apac_category_id=$APAC_CATEGORY_ID" \
  --env-var "deny_category_id=$DENY_CATEGORY_ID" \
  --env-var "inherit_token=$INHERIT_TOKEN" \
  --env-var "region_token=$REGION_TOKEN" \
  --env-var "stranger_token=$STRANGER_TOKEN" \
  --env-var "owner_token=$OWNER_TOKEN" \
  --env-var "phase=pre" \
  --reporter-cli \
  --reporter-json-export "$REPORT_DIR/$RUN_ID/hierarchy-list-matrix-pre.json"

# --- RE-PARENT: move the apac Category subtree under the FOREIGN catalog ------
# The same ltree subtree rewrite CatalogHierarchyService.reparentCategory does, applied directly to the DB
# so the e2e doesn't need a re-parent endpoint. After this, the apac Category is no longer in catalog C's
# subtree, so it LEAVES the inherit reader's catalog-C widened list.
echo "==> Re-parenting the APAC Category under the foreign Catalog (ltree subtree rewrite) ..."
"$RUNTIME" exec -i "$PG_CONTAINER" psql -U catalog -d catalog -v ON_ERROR_STOP=1 >/dev/null <<SQL
DO \$\$
DECLARE
  old_path ltree;
  new_path ltree;
  old_depth int;
BEGIN
  SELECT path INTO old_path FROM category WHERE id = '$APAC_CATEGORY_ID';
  old_depth := nlevel(old_path);
  new_path := CAST('catalog_' || replace('$FOREIGN_CATALOG_ID','-','') AS ltree)
              || CAST('category_' || replace('$APAC_CATEGORY_ID','-','') AS ltree);
  UPDATE category SET path = CASE WHEN nlevel(path) = old_depth THEN new_path
                                  ELSE new_path || subpath(path, old_depth) END
   WHERE path <@ old_path;
  UPDATE product SET path = new_path || subpath(path, old_depth) WHERE path <@ old_path;
  UPDATE category SET catalog_id = '$FOREIGN_CATALOG_ID' WHERE id = '$APAC_CATEGORY_ID';
END \$\$;
SQL

# --- run newman (POST re-parent) ---------------------------------------------
echo "==> newman run $COLLECTION (POST re-parent: the moved row left catalog C's widened list)"
newman run "$COLLECTION" \
  -e "$ENV_FILE" \
  --env-var "gateway=$GATEWAY" \
  --env-var "catalog_id=$CATALOG_ID" \
  --env-var "emea_category_id=$EMEA_CATEGORY_ID" \
  --env-var "apac_category_id=$APAC_CATEGORY_ID" \
  --env-var "deny_category_id=$DENY_CATEGORY_ID" \
  --env-var "inherit_token=$INHERIT_TOKEN" \
  --env-var "region_token=$REGION_TOKEN" \
  --env-var "stranger_token=$STRANGER_TOKEN" \
  --env-var "owner_token=$OWNER_TOKEN" \
  --env-var "phase=post" \
  --reporter-cli \
  --reporter-json-export "$REPORT_DIR/$RUN_ID/hierarchy-list-matrix-post.json"

echo "==> Hierarchy-aware list matrix: all checks passed (widening, different-sets, deny, stranger, re-parent)."
