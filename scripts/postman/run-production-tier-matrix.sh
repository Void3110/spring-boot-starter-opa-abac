#!/usr/bin/env bash
#
# Run the PRODUCTION-TIER matrix (production-tier-matrix.postman_collection.json) through the rig.
#
# Proves Slice B of the supervisor epic (ADR 0030 §1-4 + ADR 0032) end to end — the tier that decides
# how deep oversight goes, carried to child decisions as input.resource.root_attributes:
#   E1  sup-anna on a STAGING catalog's contents  -> the four child reads 200, on EXACT ids
#   E7  ...and her list rows carry NO _actions map at all (the pinned B contract: omitted, never a
#       fabricated all-false map — the bulk path has no root context, so every verb is false)
#   E2  the same four on the PRODUCTION catalog   -> each 403 (a PLAIN deny; deny_reason is slice C)
#   E6  the catalog's own OWNER reads those same production contents -> 200, _actions present and
#       honest (members are structurally unaffected: the tier denies are provenance-scoped)
#   E3  the four on an UNTAGGED catalog           -> 200 (ADR 0030 §3's default, safe only while
#                                                   `env` has no public write path — which E5 proves)
#   E5  the owner tries to STRIP / RE-VALUE / ASSIGN `env` through the public API -> each 409 with
#       errorCode TAG_OPERATOR_MANAGED, asserted BY VALUE
#   E4  LIVENESS: the operator flips staging -> production through /internal/bootstrap/resource-tags
#       and anna's VERY NEXT child read is 403; flipped back, the next is 200 again
#
# E4 + E5 together are the slice: the tier moves on the very next request when — and only when — the
# OPERATOR moves it, and nothing the supervised population can do through the API moves it at all.
#
# Prereq: the full user-service rig, with images carrying T1-T4:
#   ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2
#   ./deploy.sh build                                   # fresh catalog image; build usermgmt explicitly
#
# T4 edited category.rego + product.rego (the four tier-deny clauses every cell here depends on), so
# this runner RESTARTS THE OPA CONTAINER itself before minting tokens — `--watch` does not reliably
# reload, and a stale allow would pass E2/E4 for the wrong reason.
#
# The operator calls go to the CATALOG service's published host port (28081), not the gateway: the
# gateway carries a positive `internal-blocked` route that 404s every /internal/* path at the edge.
# That is the point — the operator path is in-network only.
#
# Fixture-id prefix: ffff… (registered in README.md). Honors the in-network token caveat.

set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SELF_DIR"

# --- config (override via env) -----------------------------------------------
COLLECTION="${COLLECTION:-production-tier-matrix.postman_collection.json}"
ENV_FILE="${ENV_FILE:-local.postman_environment.json}"
NETWORK="${COMPOSE_NETWORK:-opa-abac-example_default}"
KEYCLOAK_TOKEN_URL="${KEYCLOAK_TOKEN_URL:-http://keycloak:8888/realms/catalog-demo/protocol/openid-connect/token}"
CLIENT_ID="${CLIENT_ID:-catalog-gateway}"
CLIENT_SECRET="${CLIENT_SECRET:-catalog-gateway-secret}"
USER_SERVICE="${USER_SERVICE:-http://localhost:28090}"
CATALOG_SERVICE="${CATALOG_SERVICE:-http://localhost:28081}"   # the operator path — NOT the gateway
PG_CONTAINER="${PG_CONTAINER:-opa-abac-postgres}"
UM_PG_CONTAINER="${UM_PG_CONTAINER:-opa-abac-usermgmt-postgres}"
OPA_CONTAINER="${OPA_CONTAINER:-opa-abac-opa}"
GATEWAY="${GATEWAY:-http://localhost:9085}"
REPORT_DIR="${REPORT_DIR:-build/reports/postman}"
RUN_ID="${E2E_RUN_ID:-production-tier-$$}"

# --- the ffff… fixture set (registered in README.md) -------------------------
STAGING_CATALOG_ID="${STAGING_CATALOG_ID:-ffff0000-0000-0000-0000-000000000010}"
PROD_CATALOG_ID="${PROD_CATALOG_ID:-ffff0000-0000-0000-0000-000000000020}"
UNTAGGED_CATALOG_ID="${UNTAGGED_CATALOG_ID:-ffff0000-0000-0000-0000-000000000030}"

# --- preflight ---------------------------------------------------------------
command -v newman >/dev/null 2>&1 || {
  echo "ERROR: newman not found. Install with: npm install -g newman  (or: brew install newman)" >&2
  exit 1
}
[ -f "$ENV_FILE" ] || {
  echo "ERROR: $ENV_FILE not found. Copy: cp local.postman_environment.example.json local.postman_environment.json" >&2
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

# --- reload the policies (T4's tier denies are what every cell asserts) -------
echo "==> Restarting OPA so T4's tier-deny clauses are live (--watch is not reliable) ..."
"$RUNTIME" restart "$OPA_CONTAINER" >/dev/null
# Poll a REAL DECISION, not /health: OPA answers /health as soon as the server is listening, which is
# before the policy bundle is loaded — and a decision asked in that window returns undefined, which
# every fail-closed client in this repo reads as DENY. That window is long enough to 403 the fixture
# creation below and make a rig problem look like a code bug.
OPA_READY=0
for _ in $(seq 1 60); do
  if curl -sf -X POST "http://localhost:28181/v1/data/catalog/allow" \
       -H 'Content-Type: application/json' -d '{"input":{}}' 2>/dev/null | grep -q '"result"'; then
    OPA_READY=1; break
  fi
  sleep 1
done
[ "$OPA_READY" = "1" ] || { echo "ERROR: OPA did not load its policies within 60s." >&2; exit 1; }

# --- mint the personas' tokens in-network ------------------------------------
# ZERO REALM DIFF: both personas already exist. sup-anna is the supervisor (a member of no team);
# `editor` is her report AND the owner of the three Tier teams — one persona covering the member
# half (E6, E5) and the reach half at once.
echo "==> Minting the production-tier personas' tokens in-network ($NETWORK) ..."
ANNA_TOKEN="$(mint_token sup-anna sup-anna)"
EDITOR_TOKEN="$(mint_token editor editor)"
for pair in "sup-anna:$ANNA_TOKEN" "editor:$EDITOR_TOKEN"; do
  name="${pair%%:*}"; tok="${pair#*:}"
  [ -n "$tok" ] || {
    echo "ERROR: no token for '$name'. Bring the rig down and up so Keycloak re-imports the realm." >&2
    exit 1; }
done
ANNA_SUB="$(token_sub "$ANNA_TOKEN")"
EDITOR_SUB="$(token_sub "$EDITOR_TOKEN")"

# --- self-reset --------------------------------------------------------------
# The matrix seeds a reporting edge for sup-anna, whose family the supervised-scope matrix owns. Both
# runners DELETE every edge they manage before seeding, so whichever runs second starts from its own
# org — and this one never binds a `pm-*` account, so nothing it leaves behind can widen anna's page
# in that matrix (see the registry note in README.md).
reset_fixtures() {
  "$RUNTIME" exec -i "$UM_PG_CONTAINER" psql -U usermgmt -d usermgmt -v ON_ERROR_STOP=1 >/dev/null <<SQL
DELETE FROM reporting_edge WHERE manager_id IN (SELECT id FROM app_user WHERE subject = '$ANNA_SUB');
DELETE FROM team WHERE name IN ('Tier Staging Team', 'Tier Production Team', 'Tier Untagged Team');
SQL
  "$RUNTIME" exec -i "$PG_CONTAINER" psql -U catalog -d catalog -v ON_ERROR_STOP=1 >/dev/null <<SQL
DELETE FROM product WHERE category_id IN (SELECT id FROM category WHERE catalog_id IN
  ('$STAGING_CATALOG_ID', '$PROD_CATALOG_ID', '$UNTAGGED_CATALOG_ID'));
DELETE FROM category WHERE catalog_id IN
  ('$STAGING_CATALOG_ID', '$PROD_CATALOG_ID', '$UNTAGGED_CATALOG_ID');
DELETE FROM catalog WHERE id IN
  ('$STAGING_CATALOG_ID', '$PROD_CATALOG_ID', '$UNTAGGED_CATALOG_ID');
SQL
}
echo "==> Resetting any prior production-tier fixtures (ffff… + Tier * teams + anna's edges) ..."
reset_fixtures

# --- seed the three catalogs (with the ltree path — a pathless row breaks child creation) ---
echo "==> Seeding the ffff… catalog fixtures ..."
seed_catalog() {
  "$RUNTIME" exec -i "$PG_CONTAINER" psql -U catalog -d catalog -v ON_ERROR_STOP=1 >/dev/null <<SQL
INSERT INTO catalog (id, name, description, created_at, version, tags, path)
VALUES ('$1', '$2', '$2 description', now(), 0, '{}'::jsonb,
        CAST('catalog_' || replace('$1','-','') AS ltree))
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, tags = '{}'::jsonb, path = EXCLUDED.path;
SQL
}
seed_catalog "$STAGING_CATALOG_ID"  "Tier Staging Co"
seed_catalog "$PROD_CATALOG_ID"     "Tier Production Co"
seed_catalog "$UNTAGGED_CATALOG_ID" "Tier Untagged Co"

# --- bootstrap the identities, the teams and the reporting edge ---------------
echo "==> Bootstrapping the personas, the Tier * teams and anna -> {editor} ..."
bootstrap_user() {
  post_json "$USER_SERVICE/internal/bootstrap/users" "{\"subject\":\"$1\",\"displayName\":\"$2\"}" \
    | json_field userId
}
ANNA_UID="$(bootstrap_user "$ANNA_SUB" sup-anna)"
EDITOR_UID="$(bootstrap_user "$EDITOR_SUB" editor)"

new_team() {
  post_json "$USER_SERVICE/internal/bootstrap/teams" \
    "{\"name\":\"$1\",\"targetType\":\"catalog\",\"targetId\":\"$2\"}" | json_field teamId
}
bind() {
  post_json "$USER_SERVICE/internal/bootstrap/memberships" \
    "{\"teamId\":\"$1\",\"userId\":\"$2\",\"roleCode\":\"$3\"}" >/dev/null
}
STAGING_TEAM="$(new_team 'Tier Staging Team' "$STAGING_CATALOG_ID")"
PROD_TEAM="$(new_team 'Tier Production Team' "$PROD_CATALOG_ID")"
UNTAGGED_TEAM="$(new_team 'Tier Untagged Team' "$UNTAGGED_CATALOG_ID")"
# `owner` is CONTROL-capable, so anna reaches these three catalogs through her report's seats — and
# it is also the MEMBER role E6 and E5 need (the same subject, two provenances, one per persona).
bind "$STAGING_TEAM"  "$EDITOR_UID" owner
bind "$PROD_TEAM"     "$EDITOR_UID" owner
bind "$UNTAGGED_TEAM" "$EDITOR_UID" owner

post_json "$USER_SERVICE/internal/bootstrap/reporting-edges" \
  "{\"managerId\":\"$ANNA_UID\",\"reportIds\":[\"$EDITOR_UID\"]}" >/dev/null
echo "  anna -> {editor}; editor owns the three Tier teams"

# --- the contents (created through the gateway, so the ltree paths are real) --
create_via_gateway() {
  local what="$1" url="$2" body="$3" response id
  response="$(curl -s -X POST "$url" -H "Authorization: Bearer $EDITOR_TOKEN" \
    -H 'Content-Type: application/json' -d "$body")"
  id="$(printf '%s' "$response" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)"
  [ -n "$id" ] || {
    echo "ERROR: could not create the fixture $what. The service answered:" >&2
    echo "  $response" >&2
    exit 1; }
  printf '%s' "$id"
}
seed_contents() {  # $1 = catalog id, $2 = label -> prints "<categoryId> <productId>"
  local catalog="$1" label="$2" category product
  category="$(create_via_gateway "$label category" \
    "$GATEWAY/api/v1/catalogs/$catalog/categories" "{\"name\":\"Tier $label Category\"}")"
  product="$(create_via_gateway "$label product" \
    "$GATEWAY/api/v1/catalogs/$catalog/categories/$category/products" \
    "{\"name\":\"Tier $label Product\",\"sku\":\"TIER-$label-1\",\"priceCents\":1000,\"currency\":\"USD\"}")"
  printf '%s %s' "$category" "$product"
}
echo "==> Creating a category + product under each of the three catalogs (as the owner) ..."
# Assignment-then-read, never read <<<"$(...)": a herestring feeds `read` the substitution's stdout
# even when the subshell exit-1'd (read then returns 0 on the bare newline and errexit never fires),
# so a failed seed would sail on into newman as a wall of 404s. The assignment form propagates the
# substitution's exit status, and the non-empty check catches a silent empty print.
seed_pair=""
seed_pair="$(seed_contents "$STAGING_CATALOG_ID" STAGING)" || exit 1
[ -n "$seed_pair" ] || { echo "ERROR: empty STAGING seed result" >&2; exit 1; }
read -r STAGING_CATEGORY_ID STAGING_PRODUCT_ID <<<"$seed_pair"
seed_pair="$(seed_contents "$PROD_CATALOG_ID" PROD)" || exit 1
[ -n "$seed_pair" ] || { echo "ERROR: empty PROD seed result" >&2; exit 1; }
read -r PROD_CATEGORY_ID PROD_PRODUCT_ID <<<"$seed_pair"
seed_pair="$(seed_contents "$UNTAGGED_CATALOG_ID" UNTAGGED)" || exit 1
[ -n "$seed_pair" ] || { echo "ERROR: empty UNTAGGED seed result" >&2; exit 1; }
read -r UNTAGGED_CATEGORY_ID UNTAGGED_PRODUCT_ID <<<"$seed_pair"

# --- the OPERATOR sets the tier (the only write path there is) ---------------
# In-network, on the catalog service's published port. The third catalog is left UNTAGGED on purpose:
# that is E3's state, and it is a different state from "we could not read the root" (which is absent).
echo "==> Operator: tagging the tiers via $CATALOG_SERVICE/internal/bootstrap/resource-tags ..."
set_env_tag() {
  local resource="$1" value="$2" response
  response="$(post_json "$CATALOG_SERVICE/internal/bootstrap/resource-tags" \
    "{\"resourceType\":\"catalog\",\"resourceId\":\"$resource\",\"tags\":{\"env\":\"$value\"}}")"
  printf '%s' "$response" | grep -q "\"env\":\"$value\"" || {
    echo "ERROR: the operator write for $resource=$value did not take. The service answered:" >&2
    echo "  $response" >&2
    exit 1; }
}
set_env_tag "$STAGING_CATALOG_ID" staging
set_env_tag "$PROD_CATALOG_ID" production
echo "  staging=$STAGING_CATALOG_ID  production=$PROD_CATALOG_ID  untagged=$UNTAGGED_CATALOG_ID"

# --- the matrix --------------------------------------------------------------
mkdir -p "$REPORT_DIR/$RUN_ID"
echo "==> newman: the production-tier matrix (E1, E7, E2, E6, E3, E5, then E4) ..."
newman run "$COLLECTION" \
  -e "$ENV_FILE" \
  --env-var "gateway=$GATEWAY" \
  --env-var "catalog_service=$CATALOG_SERVICE" \
  --env-var "anna_token=$ANNA_TOKEN" \
  --env-var "editor_token=$EDITOR_TOKEN" \
  --env-var "staging_catalog_id=$STAGING_CATALOG_ID" \
  --env-var "prod_catalog_id=$PROD_CATALOG_ID" \
  --env-var "untagged_catalog_id=$UNTAGGED_CATALOG_ID" \
  --env-var "staging_category_id=$STAGING_CATEGORY_ID" \
  --env-var "staging_product_id=$STAGING_PRODUCT_ID" \
  --env-var "prod_category_id=$PROD_CATEGORY_ID" \
  --env-var "prod_product_id=$PROD_PRODUCT_ID" \
  --env-var "untagged_category_id=$UNTAGGED_CATEGORY_ID" \
  --env-var "untagged_product_id=$UNTAGGED_PRODUCT_ID" \
  --reporter-cli \
  --reporter-json-export "$REPORT_DIR/$RUN_ID/production-tier-matrix-report.json"

# --- teardown (success only — a failed run keeps its fixtures for debugging) --
if [ "${KEEP_FIXTURES:-0}" != "1" ]; then
  echo "==> Teardown: removing the ffff… catalogs, the Tier * teams and anna's reporting edge ..."
  reset_fixtures
fi

echo "==> Production-tier matrix complete."
