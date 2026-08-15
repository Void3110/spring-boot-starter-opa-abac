#!/usr/bin/env bash
#
# Run the resource-resolution matrix (resource-resolution-matrix.postman_collection.json) through the
# local rig — the live proof of Phase 5.97 (attribute-rich pre-authorization, ADR 0013).
#
# What it proves (00-DESIGN §3, story C4): with the catalog service's AbacResourceResolver registered,
# an id'd @OpaPreAuthorize decision resolves the INSTANCE and decides on its real tags + ancestors,
# the role looked up once on the governing root. The decisive cells:
#   E1  viewer-realm member whose tag-gated team role matches the resource  -> 200  (was 403)
#   E2  editor-realm member whose tag-gated team role MISmatches            -> 403  (was 200 — the
#       tag-blind realm-fallback hole, closed)
#   E3  read-only team role + editor realm -> 403 (role definition present, fallback disabled)
#   E4  NON-MEMBER of the (separately-governed) free catalog -> 403 (Slice B4: membership is the sole
#       access path; the tag-blind realm fallback that used to decide here is gone — see ADR 0018)
#   E5  catalog-root read grant authorizes a NESTED category at the gate (hierarchy parity)
#   E6  the same tag gate on PRODUCT writes (T5's ported conjunct, live)
#   +   a missing id behind an annotated resourceId answers 403, not 404 (pinned semantic #1)
#
# Prereq: the full rig is up WITH OIDC + OPA + the user-service, with the 5.97 images:
#   ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2
#   ./deploy.sh build          # force the 5.97 app code into the pods
# The runner restarts OPA itself before running (the deny cells E2/E6a assert the T5 tags_satisfied
# conjunct in catalog.rego/product.rego — OPA has no --watch, and deploy.sh build never reloads it).
#
# Fixture set (registered in scripts/postman/README.md — dedicated, no shared fixtures touched):
#   88888888-8888-8888-8888-888888888888  the team-governed catalog (emea/apac categories + products)
#   88888888-8888-8888-8888-888888888889  a SEPARATELY-governed catalog — the E4 non-member cell
#                                          (Slice B4: every catalog is governed; "team-less" no longer
#                                          grants access, so this catalog has its OWN team that the E4
#                                          subject is NOT a member of)
#
# Subjects (realm users): viewer (realm catalog-viewer) and demo (realm catalog-editor) are both bound
# to the tag-gated WRITE role (required_tags region ANY_OF [emea]); editor (realm catalog-editor) is
# bound to a catalog-root READ-ONLY role (no tags — proves inheritance + the narrowing). Fixtures are
# created through the gateway by demo: on the main catalog via its rr-regional-writer membership, and on
# the free catalog via a SECOND membership (free-creator) on the free catalog's own team — Slice B4
# removed the realm fallback, so every create now needs a resolved role. The E4 subject (editor) is a
# member of the MAIN team but NOT the free catalog's team, so E4 is denied. Product tags are set via
# psql (products have no tag-assignment API).
#
# Honors the in-network token caveat (APISIX validates issuer http://keycloak:8888).

set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SELF_DIR"

# --- config (override via env) -----------------------------------------------
COLLECTION="${COLLECTION:-resource-resolution-matrix.postman_collection.json}"
ENV_FILE="${ENV_FILE:-local.postman_environment.json}"
NETWORK="${COMPOSE_NETWORK:-opa-abac-example_default}"
KEYCLOAK_TOKEN_URL="${KEYCLOAK_TOKEN_URL:-http://keycloak:8888/realms/catalog-demo/protocol/openid-connect/token}"
CLIENT_ID="${CLIENT_ID:-catalog-gateway}"
CLIENT_SECRET="${CLIENT_SECRET:-catalog-gateway-secret}"
USER_SERVICE="${USER_SERVICE:-http://localhost:28090}"
PG_CONTAINER="${PG_CONTAINER:-opa-abac-postgres}"
GATEWAY="${GATEWAY:-http://localhost:9085}"
REPORT_DIR="${REPORT_DIR:-build/reports/postman}"
RUN_ID="${E2E_RUN_ID:-rr-$$}"

# realm users -> the matrix subjects
WRITER_VIEWER_USER="${WRITER_VIEWER_USER:-viewer}"; WRITER_VIEWER_PASS="${WRITER_VIEWER_PASS:-viewer}"
WRITER_EDITOR_USER="${WRITER_EDITOR_USER:-demo}";   WRITER_EDITOR_PASS="${WRITER_EDITOR_PASS:-demo}"
READER_EDITOR_USER="${READER_EDITOR_USER:-editor}"; READER_EDITOR_PASS="${READER_EDITOR_PASS:-editor}"

RR_CATALOG_ID="${RR_CATALOG_ID:-88888888-8888-8888-8888-888888888888}"
FREE_CATALOG_ID="${FREE_CATALOG_ID:-88888888-8888-8888-8888-888888888889}"

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

# --- make the policy state self-contained --------------------------------------
# The deny cells (E2/E6a) assert the 5.97 tags_satisfied conjunct is live in catalog.rego /
# product.rego. OPA loads /policies once (no --watch) and deploy.sh never restarts it on `build`,
# so an already-up rig may serve stale policy — restart and wait for health before minting tokens.
OPA_CONTAINER="${OPA_CONTAINER:-opa-abac-opa}"
echo "==> Restarting OPA ($OPA_CONTAINER) so the matrix runs against the current policies ..."
"$RUNTIME" restart "$OPA_CONTAINER" >/dev/null
for _ in $(seq 1 30); do
  curl -sf "http://localhost:28181/health" >/dev/null 2>&1 && break
  sleep 1
done
curl -sf "http://localhost:28181/health" >/dev/null 2>&1 || {
  echo "ERROR: OPA did not become healthy after restart." >&2; exit 1;
}

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
create_category() { # token catalog_id body -> id
  local token="$1" catalog="$2" body="$3"
  curl -s -X POST "$GATEWAY/api/v1/catalogs/$catalog/categories" \
    -H "Authorization: Bearer $token" -H 'Content-Type: application/json' -d "$body" \
    | json_field id
}
create_product() { # token catalog_id category_id body -> id
  local token="$1" catalog="$2" category="$3" body="$4"
  curl -s -X POST "$GATEWAY/api/v1/catalogs/$catalog/categories/$category/products" \
    -H "Authorization: Bearer $token" -H 'Content-Type: application/json' -d "$body" \
    | json_field id
}

# --- mint tokens -------------------------------------------------------------
echo "==> Minting writer-viewer / writer-editor / reader-editor tokens in-network ($NETWORK) ..."
WRITER_VIEWER_TOKEN="$(mint_token "$WRITER_VIEWER_USER" "$WRITER_VIEWER_PASS")"
WRITER_EDITOR_TOKEN="$(mint_token "$WRITER_EDITOR_USER" "$WRITER_EDITOR_PASS")"
READER_EDITOR_TOKEN="$(mint_token "$READER_EDITOR_USER" "$READER_EDITOR_PASS")"
for pair in "writer-viewer:$WRITER_VIEWER_TOKEN" "writer-editor:$WRITER_EDITOR_TOKEN" "reader-editor:$READER_EDITOR_TOKEN"; do
  name="${pair%%:*}"; tok="${pair#*:}"
  [ -n "$tok" ] || { echo "ERROR: no token for '$name'. Is the rig up with OIDC and the user present?" >&2; exit 1; }
done

WRITER_VIEWER_SUB="$(token_sub "$WRITER_VIEWER_TOKEN")"
WRITER_EDITOR_SUB="$(token_sub "$WRITER_EDITOR_TOKEN")"
READER_EDITOR_SUB="$(token_sub "$READER_EDITOR_TOKEN")"
echo "  subjects: writer-viewer=$WRITER_VIEWER_SUB writer-editor=$WRITER_EDITOR_SUB reader-editor=$READER_EDITOR_SUB"

# --- seed the two fixture catalogs --------------------------------------------
# Seeded WITH the ltree path (the run-pagination-matrix.sh model) — a direct-SQL catalog row without
# a path breaks category creation fail-closed ("parent has no path"). The conflict arm repairs the
# path in case a prior run left it NULL. A prior run's categories/products are deleted first so
# re-runs never accumulate (the category FKs cascade to products) — only the 8888… pair is touched.
echo "==> Seeding fixture catalogs $RR_CATALOG_ID (team-governed) + $FREE_CATALOG_ID (team-less) ..."
"$RUNTIME" exec -i "$PG_CONTAINER" psql -U catalog -d catalog -v ON_ERROR_STOP=1 >/dev/null <<SQL
CREATE EXTENSION IF NOT EXISTS ltree;
DELETE FROM category WHERE catalog_id IN ('$RR_CATALOG_ID', '$FREE_CATALOG_ID');
INSERT INTO catalog (id, name, created_at, version, tags, path)
VALUES ('$RR_CATALOG_ID', 'Resource-resolution demo catalog', now(), 0, '{}'::jsonb,
        CAST('catalog_' || replace('$RR_CATALOG_ID','-','') AS ltree))
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, path = EXCLUDED.path;
INSERT INTO catalog (id, name, created_at, version, tags, path)
VALUES ('$FREE_CATALOG_ID', 'Resource-resolution free catalog', now(), 0, '{}'::jsonb,
        CAST('catalog_' || replace('$FREE_CATALOG_ID','-','') AS ltree))
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, path = EXCLUDED.path;
SQL

# --- bootstrap the team + roles + memberships ----------------------------------
echo "==> Bootstrapping the team, the tag-gated write role, the root-read role, and memberships ..."
WRITER_VIEWER_UID="$(post_json "$USER_SERVICE/internal/bootstrap/users" "{\"subject\":\"$WRITER_VIEWER_SUB\",\"displayName\":\"$WRITER_VIEWER_USER\"}" | json_field userId)"
WRITER_EDITOR_UID="$(post_json "$USER_SERVICE/internal/bootstrap/users" "{\"subject\":\"$WRITER_EDITOR_SUB\",\"displayName\":\"$WRITER_EDITOR_USER\"}" | json_field userId)"
READER_EDITOR_UID="$(post_json "$USER_SERVICE/internal/bootstrap/users" "{\"subject\":\"$READER_EDITOR_SUB\",\"displayName\":\"$READER_EDITOR_USER\"}" | json_field userId)"

TEAM_ID="$(post_json "$USER_SERVICE/internal/bootstrap/teams" "{\"name\":\"Resource resolution demo\",\"targetType\":\"catalog\",\"targetId\":\"$RR_CATALOG_ID\"}" | json_field teamId)"

# The tag-gated WRITE role: full write on the subtree, but only where region ANY_OF [emea].
post_json "$USER_SERVICE/internal/bootstrap/custom-roles" \
  "{\"teamId\":\"$TEAM_ID\",\"code\":\"rr-regional-writer\",\"roleLevel\":20,\"permissions\":{\"catalog\":[\"READ\",\"WRITE\",\"TAG\"],\"category\":[\"READ\",\"WRITE\",\"TAG\"],\"product\":[\"READ\",\"WRITE\",\"TAG\"]},\"requiredTags\":{\"region\":[\"emea\"]},\"matchMode\":\"ANY_OF\"}" >/dev/null
# The root READ-ONLY role: read on the CATALOG type only — a nested category is readable solely via
# the inherited grant (E5), and any write is denied with the fallback disabled (E3).
post_json "$USER_SERVICE/internal/bootstrap/custom-roles" \
  "{\"teamId\":\"$TEAM_ID\",\"code\":\"rr-catalog-reader\",\"roleLevel\":10,\"permissions\":{\"catalog\":[\"READ\"]}}" >/dev/null

post_json "$USER_SERVICE/internal/bootstrap/memberships" "{\"teamId\":\"$TEAM_ID\",\"userId\":\"$WRITER_VIEWER_UID\",\"roleCode\":\"rr-regional-writer\"}" >/dev/null
post_json "$USER_SERVICE/internal/bootstrap/memberships" "{\"teamId\":\"$TEAM_ID\",\"userId\":\"$WRITER_EDITOR_UID\",\"roleCode\":\"rr-regional-writer\"}" >/dev/null
post_json "$USER_SERVICE/internal/bootstrap/memberships" "{\"teamId\":\"$TEAM_ID\",\"userId\":\"$READER_EDITOR_UID\",\"roleCode\":\"rr-catalog-reader\"}" >/dev/null
echo "  team $TEAM_ID governs catalog $RR_CATALOG_ID (two gated writers + one root reader bound)."

# Slice B4: the FREE catalog is its OWN governed island. It needs a team + a creator membership so the
# fixture category there can be created (the realm fallback that used to let a bare editor create is gone).
# writer-editor (demo) gets a SECOND membership here — full catalog WRITE+TAG, untagged. The E4 subject
# (reader-editor / editor realm) is deliberately NOT bound here, so its E4 PUT is denied (non-member).
FREE_TEAM_ID="$(post_json "$USER_SERVICE/internal/bootstrap/teams" "{\"name\":\"Resource resolution free\",\"targetType\":\"catalog\",\"targetId\":\"$FREE_CATALOG_ID\"}" | json_field teamId)"
post_json "$USER_SERVICE/internal/bootstrap/custom-roles" \
  "{\"teamId\":\"$FREE_TEAM_ID\",\"code\":\"rr-free-creator\",\"roleLevel\":20,\"permissions\":{\"catalog\":[\"READ\",\"WRITE\",\"TAG\"],\"category\":[\"READ\",\"WRITE\",\"TAG\"]}}" >/dev/null
post_json "$USER_SERVICE/internal/bootstrap/memberships" "{\"teamId\":\"$FREE_TEAM_ID\",\"userId\":\"$WRITER_EDITOR_UID\",\"roleCode\":\"rr-free-creator\"}" >/dev/null
echo "  team $FREE_TEAM_ID governs catalog $FREE_CATALOG_ID (writer-editor bound as creator; reader-editor is NOT a member -> E4 denied)."

# --- create the fixture tree through the gateway (writer-editor = realm editor) -
echo "==> Creating the fixture categories/products through the gateway ..."
EMEA_CATEGORY_ID="$(create_category "$WRITER_EDITOR_TOKEN" "$RR_CATALOG_ID" '{"name":"RR EMEA root","tags":{"region":["emea"]}}')"
APAC_CATEGORY_ID="$(create_category "$WRITER_EDITOR_TOKEN" "$RR_CATALOG_ID" '{"name":"RR APAC root","tags":{"region":["apac"]}}')"
NESTED_CATEGORY_ID="$(create_category "$WRITER_EDITOR_TOKEN" "$RR_CATALOG_ID" "{\"name\":\"RR nested\",\"parentId\":\"$EMEA_CATEGORY_ID\"}")"
EMEA_PRODUCT_ID="$(create_product "$WRITER_EDITOR_TOKEN" "$RR_CATALOG_ID" "$EMEA_CATEGORY_ID" '{"name":"RR EMEA widget","sku":"RR-EMEA","priceCents":1500,"currency":"USD"}')"
APAC_PRODUCT_ID="$(create_product "$WRITER_EDITOR_TOKEN" "$RR_CATALOG_ID" "$APAC_CATEGORY_ID" '{"name":"RR APAC widget","sku":"RR-APAC","priceCents":1000,"currency":"USD"}')"
# Slice B4: created by writer-editor via its rr-free-creator membership on the free catalog's own team
# (the realm fallback that used to let the reader create here is gone).
FREE_CATEGORY_ID="$(create_category "$WRITER_EDITOR_TOKEN" "$FREE_CATALOG_ID" '{"name":"Free catalog category"}')"
echo "  emea=$EMEA_CATEGORY_ID apac=$APAC_CATEGORY_ID nested=$NESTED_CATEGORY_ID"
echo "  emea-product=$EMEA_PRODUCT_ID apac-product=$APAC_PRODUCT_ID free=$FREE_CATEGORY_ID"
for pair in "emea:$EMEA_CATEGORY_ID" "apac:$APAC_CATEGORY_ID" "nested:$NESTED_CATEGORY_ID" \
            "emea-product:$EMEA_PRODUCT_ID" "apac-product:$APAC_PRODUCT_ID" "free:$FREE_CATEGORY_ID"; do
  name="${pair%%:*}"; id="${pair#*:}"
  [ -n "$id" ] && [ "$id" != "None" ] || { echo "ERROR: failed to create the '$name' fixture." >&2; exit 1; }
done

# Products have no tag-assignment API (tags ride the secured base) — set them directly so the T5
# conjunct has something to match (E6).
echo "==> Tagging the fixture products (region emea/apac) via psql ..."
"$RUNTIME" exec -i "$PG_CONTAINER" psql -U catalog -d catalog -v ON_ERROR_STOP=1 >/dev/null <<SQL
UPDATE product SET tags = '{"region": ["emea"]}'::jsonb WHERE id = '$EMEA_PRODUCT_ID';
UPDATE product SET tags = '{"region": ["apac"]}'::jsonb WHERE id = '$APAC_PRODUCT_ID';
SQL

# --- run newman ----------------------------------------------------------------
mkdir -p "$REPORT_DIR/$RUN_ID"
echo "==> newman run $COLLECTION (resource-resolution matrix through the gateway)"
newman run "$COLLECTION" \
  -e "$ENV_FILE" \
  --env-var "gateway=$GATEWAY" \
  --env-var "rr_catalog_id=$RR_CATALOG_ID" \
  --env-var "free_catalog_id=$FREE_CATALOG_ID" \
  --env-var "emea_category_id=$EMEA_CATEGORY_ID" \
  --env-var "apac_category_id=$APAC_CATEGORY_ID" \
  --env-var "nested_category_id=$NESTED_CATEGORY_ID" \
  --env-var "emea_product_id=$EMEA_PRODUCT_ID" \
  --env-var "apac_product_id=$APAC_PRODUCT_ID" \
  --env-var "free_category_id=$FREE_CATEGORY_ID" \
  --env-var "writer_viewer_token=$WRITER_VIEWER_TOKEN" \
  --env-var "writer_editor_token=$WRITER_EDITOR_TOKEN" \
  --env-var "reader_editor_token=$READER_EDITOR_TOKEN" \
  --reporters cli,json \
  --reporter-json-export "$REPORT_DIR/$RUN_ID/resource-resolution-matrix-report.json"

# --- teardown (success only — a failed run keeps its fixtures for debugging) --
# KEEP_FIXTURES=1 skips it. Every run re-seeds its registry ids from scratch, so tearing them
# down keeps the shared store (and the demo UI's directory/team lists) clean. The DELETEs ride
# the FK cascades: team -> memberships + custom roles + tag definitions; catalog -> categories
# -> products.
if [ "${KEEP_FIXTURES:-0}" != "1" ]; then
  echo "==> Teardown: removing the $RR_CATALOG_ID + $FREE_CATALOG_ID fixture(s) (KEEP_FIXTURES=1 keeps them) ..."
  "$RUNTIME" exec -i "${UM_PG_CONTAINER:-opa-abac-usermgmt-postgres}" psql -U usermgmt -d usermgmt -v ON_ERROR_STOP=1 >/dev/null <<SQL
DELETE FROM team WHERE target_type = 'catalog' AND target_id IN ('$RR_CATALOG_ID', '$FREE_CATALOG_ID');
SQL
  "$RUNTIME" exec -i "$PG_CONTAINER" psql -U catalog -d catalog -v ON_ERROR_STOP=1 >/dev/null <<SQL
DELETE FROM catalog WHERE id IN ('$RR_CATALOG_ID', '$FREE_CATALOG_ID');
SQL
fi
