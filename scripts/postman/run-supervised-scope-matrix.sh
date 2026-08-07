#!/usr/bin/env bash
#
# Run the SUPERVISED-SCOPE matrix (supervised-scope-matrix.postman_collection.json) through the rig.
#
# Proves Slice A of the supervisor epic (ADR 0029) end to end — a second, disjoint access path beside
# team membership, derived entirely from the reporting structure:
#   E1  sup-anna (member of NO team)  GET /catalogs -> EXACTLY her unit, by id (incl. her report's
#                                                     report's; a report's READER seat does NOT count)
#   E2  sup-victor                    GET /catalogs -> a DISJOINT set
#   E3  outsider-eve                  GET /catalogs -> 200 + count 0 (not 403, not 500)
#   E10 sup-noreports (claim, 0 reports) -> 200 + count 0 (the realm marker grants NOTHING)
#   E5  anna on a supervised catalog  GET 200 · PUT/PUT-with-tags/DELETE 403 · _actions view-only
#   E6  anna on its CONTENTS          categories list / a category / a product -> each 403
#   E9  pm-carol (member AND supervisor of one catalog) -> the row ONCE, with the MEMBERSHIP
#                                                          role's affordances (update:true)
#   E4  remove pm-bob from anna's reports -> his catalog gone next request; a direct GET -> 403
#   E8  (SECOND PASS) the supervised edge faulted -> anna degrades to her own memberships (empty),
#       while pm-carol's membership page is UNCHANGED (the fault is confined to that one edge)
#
# Prereq: the FULL user-service rig, with images carrying T4/T5 and a realm carrying the personas:
#   ./deploy.sh down                                    # so Keycloak re-imports the realm
#   ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2
#   ./deploy.sh build                                   # fresh catalog + usermgmt images
#
# T3 edited category.rego + product.rego (ADR 0031's confinement, which E6 depends on), so this runner
# RESTARTS THE OPA CONTAINER itself before minting tokens — `--watch` does not reliably reload, and a
# stale-allow on E6 would pass the boundary check for the wrong reason.
#
# E8's fault injection is its OWN edge, not B3's. ENABLE_RESILIENCE_STUB=1 repoints the WHOLE
# user-service the rest of this matrix needs; instead the second pass repoints only T4's dedicated
# CATALOG_USER_SERVICE_SUPERVISED_BASE_URL at a dead port and recreates the catalog pods. The runner
# orchestrates both passes and restores the rig on exit however the run ends.
#
# Fixture-id prefix: eeee… (registered in README.md). Honors the in-network token caveat.

set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SELF_DIR/../.." && pwd)"
cd "$SELF_DIR"

# --- config (override via env) -----------------------------------------------
COLLECTION="${COLLECTION:-supervised-scope-matrix.postman_collection.json}"
ENV_FILE="${ENV_FILE:-local.postman_environment.json}"
NETWORK="${COMPOSE_NETWORK:-opa-abac-example_default}"
KEYCLOAK_TOKEN_URL="${KEYCLOAK_TOKEN_URL:-http://keycloak:8888/realms/catalog-demo/protocol/openid-connect/token}"
CLIENT_ID="${CLIENT_ID:-catalog-gateway}"
CLIENT_SECRET="${CLIENT_SECRET:-catalog-gateway-secret}"
USER_SERVICE="${USER_SERVICE:-http://localhost:28090}"
PG_CONTAINER="${PG_CONTAINER:-opa-abac-postgres}"
UM_PG_CONTAINER="${UM_PG_CONTAINER:-opa-abac-usermgmt-postgres}"
OPA_CONTAINER="${OPA_CONTAINER:-opa-abac-opa}"
GATEWAY="${GATEWAY:-http://localhost:9085}"
REPORT_DIR="${REPORT_DIR:-build/reports/postman}"
RUN_ID="${E2E_RUN_ID:-supervised-$$}"
DEAD_URL="${DEAD_URL:-http://127.0.0.1:9}"   # E8's dead port for the supervised edge only

# --- the eeee… fixture set (registered in README.md) -------------------------
BOB_CATALOG_ID="${BOB_CATALOG_ID:-eeee0000-0000-0000-0000-0000000000b0}"
CAROL_CATALOG_ID="${CAROL_CATALOG_ID:-eeee0000-0000-0000-0000-0000000000c0}"
DAVE_CATALOG_ID="${DAVE_CATALOG_ID:-eeee0000-0000-0000-0000-0000000000d0}"
ERIN_CATALOG_ID="${ERIN_CATALOG_ID:-eeee0000-0000-0000-0000-0000000000e0}"
READER_CATALOG_ID="${READER_CATALOG_ID:-eeee0000-0000-0000-0000-0000000000f0}"

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

# --- reload the policies (T3's confinement is what E6 asserts) ---------------
echo "==> Restarting OPA so ADR 0031's confinement clauses are live (--watch is not reliable) ..."
"$RUNTIME" restart "$OPA_CONTAINER" >/dev/null
# Poll a REAL DECISION, not /health: OPA answers /health as soon as the server is listening, which is
# before the policy bundle is loaded — and a decision asked in that window returns undefined, which the
# fail-closed client reads as DENY. That window is long enough to 403 the fixture creation below.
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
echo "==> Minting the supervised-scope personas' tokens in-network ($NETWORK) ..."
ANNA_TOKEN="$(mint_token sup-anna sup-anna)"
VICTOR_TOKEN="$(mint_token sup-victor sup-victor)"
EVE_TOKEN="$(mint_token outsider-eve outsider-eve)"
NOREPORTS_TOKEN="$(mint_token sup-noreports sup-noreports)"
BOB_TOKEN="$(mint_token pm-bob pm-bob)"
CAROL_TOKEN="$(mint_token pm-carol pm-carol)"
DAVE_TOKEN="$(mint_token pm-dave pm-dave)"
ERIN_TOKEN="$(mint_token pm-erin pm-erin)"
EDITOR_TOKEN="$(mint_token editor editor)"
for pair in "sup-anna:$ANNA_TOKEN" "sup-victor:$VICTOR_TOKEN" "outsider-eve:$EVE_TOKEN" \
            "sup-noreports:$NOREPORTS_TOKEN" "pm-bob:$BOB_TOKEN" "pm-carol:$CAROL_TOKEN" \
            "pm-dave:$DAVE_TOKEN" "pm-erin:$ERIN_TOKEN" "editor:$EDITOR_TOKEN"; do
  name="${pair%%:*}"; tok="${pair#*:}"
  [ -n "$tok" ] || {
    echo "ERROR: no token for '$name'. The realm export gained these personas in this slice —" >&2
    echo "       run './deploy.sh down' then up so Keycloak RE-IMPORTS infra/keycloak/realm-export.json." >&2
    exit 1; }
done

ANNA_SUB="$(token_sub "$ANNA_TOKEN")";       VICTOR_SUB="$(token_sub "$VICTOR_TOKEN")"
EVE_SUB="$(token_sub "$EVE_TOKEN")";         NOREPORTS_SUB="$(token_sub "$NOREPORTS_TOKEN")"
BOB_SUB="$(token_sub "$BOB_TOKEN")";         CAROL_SUB="$(token_sub "$CAROL_TOKEN")"
DAVE_SUB="$(token_sub "$DAVE_TOKEN")";       ERIN_SUB="$(token_sub "$ERIN_TOKEN")"
EDITOR_SUB="$(token_sub "$EDITOR_TOKEN")"

# --- self-reset (the matrix is NOT idempotent: E4 removes a reporting edge) ---
# A second run must start from the ORIGINAL org, or E1 would see only 2 catalogs. Teams/edges/catalogs
# are wiped by the names + ids this matrix owns; all idempotent (no-ops on a fresh rig).
reset_fixtures() {
  "$RUNTIME" exec -i "$UM_PG_CONTAINER" psql -U usermgmt -d usermgmt -v ON_ERROR_STOP=1 >/dev/null <<SQL
DELETE FROM reporting_edge WHERE manager_id IN (SELECT id FROM app_user WHERE subject IN
  ('$ANNA_SUB', '$VICTOR_SUB', '$CAROL_SUB'));
DELETE FROM team WHERE name IN
  ('Sup Bob Team', 'Sup Carol Team', 'Sup Dave Team', 'Sup Erin Team', 'Sup Reader Team');
SQL
  "$RUNTIME" exec -i "$PG_CONTAINER" psql -U catalog -d catalog -v ON_ERROR_STOP=1 >/dev/null <<SQL
DELETE FROM catalog WHERE id IN ('$BOB_CATALOG_ID', '$CAROL_CATALOG_ID', '$DAVE_CATALOG_ID',
                                 '$ERIN_CATALOG_ID', '$READER_CATALOG_ID');
SQL
}
echo "==> Resetting any prior supervised-scope fixtures (eeee… + Sup * teams + reporting edges) ..."
reset_fixtures

# --- seed the five catalogs (with the ltree path — a pathless row breaks child creation) ---
echo "==> Seeding the eeee… catalog fixtures ..."
seed_catalog() {
  "$RUNTIME" exec -i "$PG_CONTAINER" psql -U catalog -d catalog -v ON_ERROR_STOP=1 >/dev/null <<SQL
INSERT INTO catalog (id, name, description, created_at, version, tags, path)
VALUES ('$1', '$2', '$2 description', now(), 0, '{}'::jsonb,
        CAST('catalog_' || replace('$1','-','') AS ltree))
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, path = EXCLUDED.path;
SQL
}
seed_catalog "$BOB_CATALOG_ID"    "Sup Bob Co"
seed_catalog "$CAROL_CATALOG_ID"  "Sup Carol Co"
seed_catalog "$DAVE_CATALOG_ID"   "Sup Dave Co"
seed_catalog "$ERIN_CATALOG_ID"   "Sup Erin Co"
seed_catalog "$READER_CATALOG_ID" "Sup Reader Co"

# --- bootstrap the identities ------------------------------------------------
echo "==> Bootstrapping the personas in the user-service ..."
bootstrap_user() {
  post_json "$USER_SERVICE/internal/bootstrap/users" "{\"subject\":\"$1\",\"displayName\":\"$2\"}" \
    | json_field userId
}
ANNA_UID="$(bootstrap_user "$ANNA_SUB" sup-anna)"
VICTOR_UID="$(bootstrap_user "$VICTOR_SUB" sup-victor)"
EVE_UID="$(bootstrap_user "$EVE_SUB" outsider-eve)"
NOREPORTS_UID="$(bootstrap_user "$NOREPORTS_SUB" sup-noreports)"
BOB_UID="$(bootstrap_user "$BOB_SUB" pm-bob)"
CAROL_UID="$(bootstrap_user "$CAROL_SUB" pm-carol)"
DAVE_UID="$(bootstrap_user "$DAVE_SUB" pm-dave)"
ERIN_UID="$(bootstrap_user "$ERIN_SUB" pm-erin)"
EDITOR_UID="$(bootstrap_user "$EDITOR_SUB" editor)"

# --- teams + memberships -----------------------------------------------------
# Reach is CONTROL-capable seats ONLY (owner / administrator / senior). The reader seat below is the
# negative case: pm-bob holds it, and it must NOT propagate to anna.
echo "==> Bootstrapping the Sup * teams and their memberships ..."
new_team() {
  post_json "$USER_SERVICE/internal/bootstrap/teams" \
    "{\"name\":\"$1\",\"targetType\":\"catalog\",\"targetId\":\"$2\"}" | json_field teamId
}
bind() {
  post_json "$USER_SERVICE/internal/bootstrap/memberships" \
    "{\"teamId\":\"$1\",\"userId\":\"$2\",\"roleCode\":\"$3\"}" >/dev/null
}
BOB_TEAM="$(new_team 'Sup Bob Team' "$BOB_CATALOG_ID")"
CAROL_TEAM="$(new_team 'Sup Carol Team' "$CAROL_CATALOG_ID")"
DAVE_TEAM="$(new_team 'Sup Dave Team' "$DAVE_CATALOG_ID")"
ERIN_TEAM="$(new_team 'Sup Erin Team' "$ERIN_CATALOG_ID")"
READER_TEAM="$(new_team 'Sup Reader Team' "$READER_CATALOG_ID")"

bind "$BOB_TEAM"    "$BOB_UID"   owner            # CONTROL-capable -> propagates
bind "$CAROL_TEAM"  "$CAROL_UID" administrator    # CONTROL-capable -> propagates
bind "$DAVE_TEAM"   "$DAVE_UID"  senior           # CONTROL-capable -> propagates (transitively, via carol)
bind "$ERIN_TEAM"   "$ERIN_UID"  owner            # victor's unit
bind "$READER_TEAM" "$BOB_UID"   reader           # NOT control-capable -> must NOT propagate
# E9's dual hat: carol is ALSO a member (owner) of the team she supervises through pm-dave.
bind "$DAVE_TEAM"   "$CAROL_UID" owner
# A curator so `editor` can create the contents E6 reads. Not one of anna's reports, so it changes
# no reach; a custom role is management-incapable and projects to [READ] for the reach rule anyway.
post_json "$USER_SERVICE/internal/bootstrap/custom-roles" \
  "{\"teamId\":\"$DAVE_TEAM\",\"code\":\"sup-curator\",\"roleLevel\":20,\"permissions\":{\"catalog\":[\"READ\",\"WRITE\",\"TAG\"],\"category\":[\"READ\",\"WRITE\",\"TAG\"],\"product\":[\"READ\",\"WRITE\",\"TAG\"]}}" >/dev/null
bind "$DAVE_TEAM" "$EDITOR_UID" sup-curator

# --- the reporting relation --------------------------------------------------
#   sup-anna  -> pm-bob, pm-carol        pm-carol -> pm-dave  (transitivity)
#   sup-victor -> pm-erin                sup-noreports -> (nothing: E10's cell)
echo "==> Seeding the reporting edges ..."
post_json "$USER_SERVICE/internal/bootstrap/reporting-edges" \
  "{\"managerId\":\"$ANNA_UID\",\"reportIds\":[\"$BOB_UID\",\"$CAROL_UID\"]}" >/dev/null
post_json "$USER_SERVICE/internal/bootstrap/reporting-edges" \
  "{\"managerId\":\"$CAROL_UID\",\"reportIds\":[\"$DAVE_UID\"]}" >/dev/null
post_json "$USER_SERVICE/internal/bootstrap/reporting-edges" \
  "{\"managerId\":\"$VICTOR_UID\",\"reportIds\":[\"$ERIN_UID\"]}" >/dev/null
echo "  anna -> {bob, carol}; carol -> {dave}; victor -> {erin}; sup-noreports -> {} (E10)"

# --- the contents E6 reads (created through the gateway so the ltree paths are real) ---
echo "==> Creating a category + product under Sup Dave Co (through the gateway, as the curator) ..."
# The response body is captured whole so a non-201 surfaces as a readable diagnostic instead of a bare
# KeyError from the id extraction.
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
DAVE_CATEGORY_ID="$(create_via_gateway "Category" \
  "$GATEWAY/api/v1/catalogs/$DAVE_CATALOG_ID/categories" '{"name":"Sup Dave Category"}')"
DAVE_PRODUCT_ID="$(create_via_gateway "Product" \
  "$GATEWAY/api/v1/catalogs/$DAVE_CATALOG_ID/categories/$DAVE_CATEGORY_ID/products" \
  '{"name":"Sup Dave Product","sku":"SUP-DAVE-1","priceCents":1000,"currency":"USD"}')"
echo "  category=$DAVE_CATEGORY_ID product=$DAVE_PRODUCT_ID"

# --- pass 1: the matrix ------------------------------------------------------
mkdir -p "$REPORT_DIR/$RUN_ID"
newman_run() {
  local folder="$1" report="$2"
  newman run "$COLLECTION" \
    -e "$ENV_FILE" \
    --folder "$folder" \
    --env-var "gateway=$GATEWAY" \
    --env-var "user_service=$USER_SERVICE" \
    --env-var "anna_token=$ANNA_TOKEN" \
    --env-var "victor_token=$VICTOR_TOKEN" \
    --env-var "eve_token=$EVE_TOKEN" \
    --env-var "noreports_token=$NOREPORTS_TOKEN" \
    --env-var "carol_token=$CAROL_TOKEN" \
    --env-var "anna_uid=$ANNA_UID" \
    --env-var "carol_uid=$CAROL_UID" \
    --env-var "bob_catalog_id=$BOB_CATALOG_ID" \
    --env-var "carol_catalog_id=$CAROL_CATALOG_ID" \
    --env-var "dave_catalog_id=$DAVE_CATALOG_ID" \
    --env-var "erin_catalog_id=$ERIN_CATALOG_ID" \
    --env-var "reader_catalog_id=$READER_CATALOG_ID" \
    --env-var "dave_category_id=$DAVE_CATEGORY_ID" \
    --env-var "dave_product_id=$DAVE_PRODUCT_ID" \
    --reporter-cli \
    --reporter-json-export "$REPORT_DIR/$RUN_ID/$report"
}

echo "==> newman PASS 1: the supervised-scope matrix (E1-E6, E9, E10, then E4) ..."
newman_run "Matrix" "supervised-scope-matrix-report.json"

# --- pass 2: E8, with ONLY the supervised edge repointed at a dead port -------
# The catalog pods are recreated with the dedicated property overridden; everything else — role
# resolve, tag definitions, the user-service itself — keeps working. An EXIT trap restores the rig
# however this run ends.
SUPERVISED_PASS_ACTIVE=0
restore_rig() {
  if [ "$SUPERVISED_PASS_ACTIVE" = "1" ]; then
    echo "==> Restoring the catalog pods (supervised base-url back to the real user-service) ..."
    ( cd "$REPO_ROOT" && ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up >/dev/null ) || true
    SUPERVISED_PASS_ACTIVE=0
  fi
}
trap restore_rig EXIT

echo "==> Recreating the catalog pods with ONLY the supervised edge repointed at $DEAD_URL ..."
SUPERVISED_PASS_ACTIVE=1
( cd "$REPO_ROOT" && CATALOG_USER_SERVICE_SUPERVISED_BASE_URL="$DEAD_URL" \
    ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up >/dev/null )
for _ in $(seq 1 60); do
  curl -sf "$GATEWAY/api/v1/catalogs" -H "Authorization: Bearer $EVE_TOKEN" >/dev/null 2>&1 && break
  sleep 2
done

echo "==> newman PASS 2: E8 — the supervised edge faulted ..."
newman_run "Supervised-edge outage" "supervised-scope-outage-report.json"

restore_rig
trap - EXIT

# --- teardown (success only — a failed run keeps its fixtures for debugging) --
if [ "${KEEP_FIXTURES:-0}" != "1" ]; then
  echo "==> Teardown: removing the eeee… catalogs, the Sup * teams and the reporting edges ..."
  reset_fixtures
fi

echo "==> Supervised-scope matrix: both passes complete."
