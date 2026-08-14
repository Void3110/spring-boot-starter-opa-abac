#!/usr/bin/env bash
#
# Run the STEP-UP ELEVATION matrix (step-up-matrix.postman_collection.json) through the rig.
#
# Proves Slice C of the supervisor epic (ADR 0030 §5-9 as amended) end to end — a supervisor's
# PRODUCTION contents open behind a FRESH second factor, for a bounded window, and nothing else moves:
#   E1  anna at aal1 on the production catalog's contents -> 401 + an RFC 9470 WWW-Authenticate
#       challenge + STEP_UP_REQUIRED (slice B answered these with a plain 403 — the C-flip)
#   E2  the SAME reads with an aal2 token (one TOTP) -> 200 on exact ids ... and both audit events
#       appear on the catalog pod's `opa.abac.audit` channel
#   E4  fingerprinting: an OUT-OF-UNIT supervisor -> plain 403, no challenge; and ELEVATED anna's
#       PUT -> plain 403, no challenge (the read-only ceiling is not an elevation problem)
#   E5  members unaffected: the catalog's OWNER reads the same contents at plain aal1 -> 200,
#       _actions present and honest
#   E6  the supervised path is HUMAN-ONLY: an agent-client token for anna's subject (act_chain
#       present) is denied at the TARGET-GATE with the ordinary code on production AND on
#       non-production content, and its list_catalogs is the empty page — each paired with a
#       human-token control cell, so a closed door is distinguishable from an empty reach
#   E7  the freshness drill: max_age overridden to 5s on the LEAF path, a positive control, then
#       > 35s of wall clock and the SAME token answers 401 again
#   E3  the loop-prevention negative, INSIDE the drill's shrunk window: re-auth WITHOUT max_age
#       reusing the miner's cookie jar -> SSO reuse -> the same stale auth_time -> still 401
#
# Prereq: the FULL rig on the MCP flavour, with images carrying T1-T4 and a realm carrying T1's
# level-2 flow. The realm CHANGED in this slice, so Keycloak must RE-IMPORT it — bring the rig
# DOWN first; `up` alone leaves the old realm in the existing container:
#   ./deploy.sh down
#   ENABLE_MCP=1 ./deploy.sh up --pods 2      # force-enables OIDC + OPA + the user-service
#   ./deploy.sh build                          # fresh catalog image; build usermgmt + mcp explicitly
# The preflight below detects a stale realm and says so rather than failing deep in the matrix.
# ENABLE_MCP is a superset of what the REST cells need, so the WHOLE set runs on one flavour.
#
# T2 edited category.rego + product.rego + added step_up.json, so this runner RESTARTS THE OPA
# CONTAINER itself before minting tokens (`--watch` does not reliably reload) and then polls a REAL
# DECISION, never /health — OPA answers /health before the bundle is loaded, and a decision asked in
# that window is undefined, which every fail-closed client here reads as DENY.
#
# TOKENS: anna's come from mint-code-flow-token.py (the scripted PKCE code flow). ROPC structurally
# cannot carry auth_time, so `mint_token()` is unusable for her — it is still used for every other
# persona, whose cells are precisely about NOT being elevated.
#
# Fixture-id prefix: f00d… (registered in README.md). Honors the in-network token caveat.

set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SELF_DIR/../.." && pwd)"
cd "$SELF_DIR"

# --- config (override via env) -----------------------------------------------
COLLECTION="${COLLECTION:-step-up-matrix.postman_collection.json}"
ENV_FILE="${ENV_FILE:-local.postman_environment.json}"
NETWORK="${COMPOSE_NETWORK:-opa-abac-example_default}"
KEYCLOAK_TOKEN_URL="${KEYCLOAK_TOKEN_URL:-http://keycloak:8888/realms/catalog-demo/protocol/openid-connect/token}"
CLIENT_ID="${CLIENT_ID:-catalog-gateway}"
CLIENT_SECRET="${CLIENT_SECRET:-catalog-gateway-secret}"
AGENT_CLIENT_ID="${AGENT_CLIENT_ID:-catalog-agent-overreach}"
AGENT_CLIENT_SECRET="${AGENT_CLIENT_SECRET:-catalog-agent-overreach-secret}"
USER_SERVICE="${USER_SERVICE:-http://localhost:28090}"
PG_CONTAINER="${PG_CONTAINER:-opa-abac-postgres}"
UM_PG_CONTAINER="${UM_PG_CONTAINER:-opa-abac-usermgmt-postgres}"
OPA_CONTAINER="${OPA_CONTAINER:-opa-abac-opa}"
CATALOG_SERVICE="${CATALOG_SERVICE:-http://localhost:28081}"   # the operator path — NOT the gateway
CATALOG_PODS="${CATALOG_PODS:-catalog-1 catalog-2}"
OPA_URL="${OPA_URL:-http://localhost:28181}"
GATEWAY="${GATEWAY:-http://localhost:9085}"
REPORT_DIR="${REPORT_DIR:-build/reports/postman}"
RUN_ID="${E2E_RUN_ID:-step-up-$$}"
MINER="${MINER:-$SELF_DIR/mint-code-flow-token.py}"

# The drill's shrunk window. skew stays 30 (only the LEAF max_age is overridden), so the wait must
# exceed max_age + skew = 35s.
DRILL_MAX_AGE="${DRILL_MAX_AGE:-5}"
DRILL_WAIT="${DRILL_WAIT:-42}"

# --- the f00d… fixture set (registered in README.md) --------------------------
PROD_CATALOG_ID="${PROD_CATALOG_ID:-f00d0000-0000-0000-0000-000000000010}"
OPEN_CATALOG_ID="${OPEN_CATALOG_ID:-f00d0000-0000-0000-0000-000000000020}"

# --- preflight ---------------------------------------------------------------
command -v newman >/dev/null 2>&1 || {
  echo "ERROR: newman not found. Install with: npm install -g newman  (or: brew install newman)" >&2
  exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "ERROR: python3 is required (the token miner)." >&2; exit 1; }
[ -x "$MINER" ] || { echo "ERROR: $MINER not found or not executable." >&2; exit 1; }
[ -f "$ENV_FILE" ] || {
  echo "ERROR: $ENV_FILE not found. Copy: cp local.postman_environment.example.json local.postman_environment.json" >&2
  exit 1; }
RUNTIME=""
for c in docker podman; do command -v "$c" >/dev/null 2>&1 && { RUNTIME="$c"; break; }; done
[ -n "$RUNTIME" ] || { echo "ERROR: need docker or podman to mint in-network tokens." >&2; exit 1; }
MCP_CONTAINER="${MCP_CONTAINER:-mcp}"
"$RUNTIME" inspect "$MCP_CONTAINER" >/dev/null 2>&1 || {
  echo "ERROR: the MCP server is not running — E6 needs it. Bring the rig up with:" >&2
  echo "         ./deploy.sh down && ENABLE_MCP=1 ./deploy.sh up --pods 2" >&2
  exit 1; }

# --- helpers -----------------------------------------------------------------
mint_token() {   # ROPC, in-network. $1 user $2 pass [$3 client $4 secret $5 otp]
  local user="$1" pass="$2" client="${3:-$CLIENT_ID}" secret="${4:-$CLIENT_SECRET}" otp="${5:-}" json
  json="$("$RUNTIME" run --rm --network "$NETWORK" curlimages/curl -s \
    -X POST "$KEYCLOAK_TOKEN_URL" \
    -d grant_type=password -d "client_id=$client" -d "client_secret=$secret" \
    -d "username=$user" -d "password=$pass" ${otp:+-d "otp=$otp"} || true)"
  printf '%s' "$json" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p'
}

# anna owns a TOTP factor (T1 seeds it), and Keycloak's DIRECT-GRANT flow demands a code from any
# identity that has one — a plain ROPC mint for her answers `invalid_grant / Invalid user
# credentials`, which reads as a wrong password and is not. The code comes from the miner so the
# fixture secret lives in one place; the retry is for Keycloak's refusal to reuse a spent code,
# which this runner can genuinely provoke (the browser flow above consumed one moments ago).
mint_anna_token() {   # [$1 client $2 secret]
  local client="${1:-$CLIENT_ID}" secret="${2:-$CLIENT_SECRET}" token=""
  for _ in 1 2 3; do
    token="$(mint_token sup-anna sup-anna "$client" "$secret" "$("$MINER" --print-otp)")"
    [ -n "$token" ] && break
    sleep 31   # the current TOTP window was already spent; wait it out
  done
  printf '%s' "$token"
}

token_claim() {  # $1 token $2 claim -> the raw value, or empty
  python3 -c "
import base64, json, sys
payload = sys.argv[1].split('.')[1]
claims = json.loads(base64.urlsafe_b64decode(payload + '=' * (-len(payload) % 4)))
value = claims.get(sys.argv[2])
print('' if value is None else value)" "$1" "$2"
}

post_json() { curl -s -X POST "$1" -H 'Content-Type: application/json' -d "$2"; }
json_field() { python3 -c "import sys,json; print(json.load(sys.stdin)['$1'])"; }

opa_wait_for_a_real_decision() {
  local ready=0
  for _ in $(seq 1 60); do
    if curl -sf -X POST "$OPA_URL/v1/data/catalog/allow" \
         -H 'Content-Type: application/json' -d '{"input":{}}' 2>/dev/null | grep -q '"result"'; then
      ready=1; break
    fi
    sleep 1
  done
  [ "$ready" = "1" ] || { echo "ERROR: OPA did not load its policies within 60s." >&2; return 1; }
}

# --- reload the policies (T2's step_up data + the amended denies) -------------
echo "==> Restarting OPA so T2's corpus and step_up.json are live (--watch is not reliable) ..."
"$RUNTIME" restart "$OPA_CONTAINER" >/dev/null
opa_wait_for_a_real_decision
STEP_UP_DATA="$(curl -sf "$OPA_URL/v1/data/step_up" || true)"
printf '%s' "$STEP_UP_DATA" | grep -q '"max_age"' || {
  echo "ERROR: OPA has no data.step_up — infra/opa/policies/step_up.json did not load." >&2
  echo "       OPA answered: $STEP_UP_DATA" >&2
  exit 1; }
SHIPPED_MAX_AGE="$(printf '%s' "$STEP_UP_DATA" | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['max_age'])")"
echo "  data.step_up loaded; the shipped window is max_age=${SHIPPED_MAX_AGE}s (mirrors the realm's level-2 condition)."

# --- tokens ------------------------------------------------------------------
COOKIE_JAR="$(mktemp -t stepup-anna-jar.XXXXXX)"
cleanup_jar() { rm -f "$COOKIE_JAR"; }

# The drill's restore, installed BEFORE anything can fail: a run that dies between the data
# override and the restore would otherwise leave the rig on a 5-second window for the next runner.
# `restore_opa_data` is a no-op until the override actually happens.
DRILL_ACTIVE=0
restore_opa_data() {
  [ "$DRILL_ACTIVE" = "1" ] || return 0
  echo "==> Restoring the shipped step_up data (restarting OPA — the file bundle is the truth) ..."
  "$RUNTIME" restart "$OPA_CONTAINER" >/dev/null || true
  opa_wait_for_a_real_decision || true
  DRILL_ACTIVE=0
}
trap 'restore_opa_data; cleanup_jar' EXIT

echo "==> Minting anna's aal1 + aal2 tokens through the scripted code flow ..."
# ROPC has no authentication EVENT, so it cannot carry auth_time — the whole reason the miner exists.
ANNA_AAL1_TOKEN="$("$MINER" --user sup-anna --cookie-jar "$COOKIE_JAR")" || {
  echo "ERROR: could not mint anna's aal1 token. If the login form never appeared, the rig is" >&2
  echo "       running the PRE-C realm: run './deploy.sh down' then up so Keycloak RE-IMPORTS" >&2
  echo "       infra/keycloak/realm-export.json (this slice added the level-2 flow + her factor)." >&2
  exit 1; }
ANNA_AAL2_TOKEN="$("$MINER" --user sup-anna --acr aal2 --cookie-jar "$COOKIE_JAR")" || {
  echo "ERROR: could not mint anna's aal2 token — the level-2 TOTP subflow did not complete." >&2
  exit 1; }

echo "==> Minting the other personas in-network ($NETWORK) ..."
EDITOR_TOKEN="$(mint_token editor editor)"
VICTOR_TOKEN="$(mint_token sup-victor sup-victor)"
ANNA_AGENT_TOKEN="$(mint_anna_token "$AGENT_CLIENT_ID" "$AGENT_CLIENT_SECRET")"
for pair in "editor:$EDITOR_TOKEN" "sup-victor:$VICTOR_TOKEN" "anna(agent):$ANNA_AGENT_TOKEN"; do
  name="${pair%%:*}"; tok="${pair#*:}"
  [ -n "$tok" ] || {
    echo "ERROR: no token for '$name'. Bring the rig down and up so Keycloak re-imports the realm." >&2
    exit 1; }
done

# The dual-identity preflight. Without it every cell below could silently degrade into a different
# cell than the one it claims to be — an unelevated 401 that is really "no role", an agent cell that
# is really a human call — and the matrix would stay green while proving nothing.
[ "$(token_claim "$ANNA_AAL1_TOKEN" acr)" = "aal1" ] || {
  echo "ERROR: anna's aal1 token does not carry acr=aal1." >&2; exit 1; }
[ "$(token_claim "$ANNA_AAL2_TOKEN" acr)" = "aal2" ] || {
  echo "ERROR: anna's elevated token does not carry acr=aal2." >&2; exit 1; }
[ -n "$(token_claim "$ANNA_AAL1_TOKEN" auth_time)" ] || {
  echo "ERROR: anna's token carries no auth_time — the realm's 'basic' client scope is missing." >&2
  exit 1; }
[ -z "$(token_claim "$EDITOR_TOKEN" auth_time)" ] || {
  echo "ERROR: the ROPC token carries auth_time; E5 needs a token that provably cannot elevate." >&2
  exit 1; }
[ -n "$(token_claim "$ANNA_AGENT_TOKEN" act_chain)" ] || {
  echo "ERROR: the $AGENT_CLIENT_ID token carries no act_chain claim — stale realm import." >&2
  exit 1; }
[ -z "$(token_claim "$ANNA_AGENT_TOKEN" auth_time)" ] || {
  echo "ERROR: the agent token carries auth_time — the 'elevated agent' must stay unmintable here." >&2
  exit 1; }
echo "  acr aal1/aal2 confirmed on anna's pair; act_chain present on the agent token and absent"
echo "  from every human one; auth_time absent from both ROPC tokens (structural, ADR 0030 §Context)."

ANNA_SUB="$(token_claim "$ANNA_AAL1_TOKEN" sub)"
EDITOR_SUB="$(token_claim "$EDITOR_TOKEN" sub)"

# --- self-reset --------------------------------------------------------------
# This matrix seeds a reporting edge for sup-anna, whose persona family the supervised-scope matrix
# reserves. The shared-persona rules (README.md's registry note) are honoured mechanically: every
# runner DELETES the edges it manages before seeding, and this one binds NO reserved account into
# its own teams (both f00d teams are owned by the shared `editor` seeder). Anna's MEMBERSHIPS are
# cleared too — E6d asserts an empty membership-only page, which a stray seat would silently break.
reset_fixtures() {
  "$RUNTIME" exec -i "$UM_PG_CONTAINER" psql -U usermgmt -d usermgmt -v ON_ERROR_STOP=1 >/dev/null <<SQL
DELETE FROM reporting_edge WHERE manager_id IN (SELECT id FROM app_user WHERE subject = '$ANNA_SUB');
DELETE FROM team_membership WHERE user_id IN (SELECT id FROM app_user WHERE subject = '$ANNA_SUB');
DELETE FROM team WHERE name IN ('Step-Up Production Team', 'Step-Up Open Team');
SQL
  "$RUNTIME" exec -i "$PG_CONTAINER" psql -U catalog -d catalog -v ON_ERROR_STOP=1 >/dev/null <<SQL
DELETE FROM product WHERE category_id IN (SELECT id FROM category WHERE catalog_id IN
  ('$PROD_CATALOG_ID', '$OPEN_CATALOG_ID'));
DELETE FROM category WHERE catalog_id IN ('$PROD_CATALOG_ID', '$OPEN_CATALOG_ID');
DELETE FROM catalog WHERE id IN ('$PROD_CATALOG_ID', '$OPEN_CATALOG_ID');
SQL
}
echo "==> Resetting any prior step-up fixtures (f00d… + Step-Up * teams + anna's edges/seats) ..."
reset_fixtures

# --- seed the two catalogs (with the ltree path — a pathless row breaks child creation) ---
seed_catalog() {
  "$RUNTIME" exec -i "$PG_CONTAINER" psql -U catalog -d catalog -v ON_ERROR_STOP=1 >/dev/null <<SQL
INSERT INTO catalog (id, name, description, created_at, version, tags, path)
VALUES ('$1', '$2', '$2 description', now(), 0, '{}'::jsonb,
        CAST('catalog_' || replace('$1','-','') AS ltree))
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, tags = '{}'::jsonb, path = EXCLUDED.path;
SQL
}
echo "==> Seeding the f00d… catalog fixtures ..."
seed_catalog "$PROD_CATALOG_ID" "Step-Up Production Co"
seed_catalog "$OPEN_CATALOG_ID" "Step-Up Open Co"

# --- the identities, the teams and the reporting edge -------------------------
echo "==> Bootstrapping the personas, the Step-Up * teams and anna -> {editor} ..."
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
PROD_TEAM="$(new_team 'Step-Up Production Team' "$PROD_CATALOG_ID")"
OPEN_TEAM="$(new_team 'Step-Up Open Team' "$OPEN_CATALOG_ID")"
# `owner` is CONTROL-capable, so anna's reach is derived through her report's seats — and it is also
# the MEMBER role E5 needs. One persona, two provenances.
bind "$PROD_TEAM" "$EDITOR_UID" owner
bind "$OPEN_TEAM" "$EDITOR_UID" owner
post_json "$USER_SERVICE/internal/bootstrap/reporting-edges" \
  "{\"managerId\":\"$ANNA_UID\",\"reportIds\":[\"$EDITOR_UID\"]}" >/dev/null
echo "  anna -> {editor}; editor owns both Step-Up teams"

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
seed_contents() {  # $1 catalog id, $2 label -> prints "<categoryId> <productId>"
  local catalog="$1" label="$2" category product
  category="$(create_via_gateway "$label category" \
    "$GATEWAY/api/v1/catalogs/$catalog/categories" "{\"name\":\"Step-Up $label Category\"}")"
  product="$(create_via_gateway "$label product" \
    "$GATEWAY/api/v1/catalogs/$catalog/categories/$category/products" \
    "{\"name\":\"Step-Up $label Product\",\"sku\":\"STEPUP-$label-1\",\"priceCents\":2500,\"currency\":\"USD\"}")"
  printf '%s %s' "$category" "$product"
}
echo "==> Creating a category + product under each catalog (as the owner) ..."
# Assignment-then-read, never `read <<<"$(...)"`: the herestring form swallows the substitution's
# exit 1 (read returns 0 on the bare newline and errexit never fires), so a failed seed would sail
# into newman as a wall of 404s naming nothing.
seed_pair="$(seed_contents "$PROD_CATALOG_ID" PROD)" || exit 1
[ -n "$seed_pair" ] || { echo "ERROR: empty PROD seed result" >&2; exit 1; }
read -r PROD_CATEGORY_ID PROD_PRODUCT_ID <<<"$seed_pair"
seed_pair="$(seed_contents "$OPEN_CATALOG_ID" OPEN)" || exit 1
[ -n "$seed_pair" ] || { echo "ERROR: empty OPEN seed result" >&2; exit 1; }
read -r OPEN_CATEGORY_ID OPEN_PRODUCT_ID <<<"$seed_pair"

# --- the OPERATOR sets the tier (the only write path there is) ---------------
# In-network, on the catalog service's published port: the gateway 404s every /internal/* path at
# the edge. The second catalog is left UNTAGGED on purpose — that is E1d/E6c's non-production state.
echo "==> Operator: tagging $PROD_CATALOG_ID env=production ..."
tag_response="$(post_json "$CATALOG_SERVICE/internal/bootstrap/resource-tags" \
  "{\"resourceType\":\"catalog\",\"resourceId\":\"$PROD_CATALOG_ID\",\"tags\":{\"env\":\"production\"}}")"
printf '%s' "$tag_response" | grep -q '"env":"production"' || {
  echo "ERROR: the operator tier write did not take. The service answered:" >&2
  echo "  $tag_response" >&2
  exit 1; }

# --- the matrix --------------------------------------------------------------
mkdir -p "$REPORT_DIR/$RUN_ID"
run_folder() {  # $1 folder, $2 report suffix, then extra --env-var pairs
  local folder="$1" suffix="$2"; shift 2
  newman run "$COLLECTION" \
    -e "$ENV_FILE" \
    --folder "$folder" \
    --env-var "gateway=$GATEWAY" \
    --env-var "anna_aal1_token=$ANNA_AAL1_TOKEN" \
    --env-var "anna_aal2_token=$ANNA_AAL2_TOKEN" \
    --env-var "anna_agent_token=$ANNA_AGENT_TOKEN" \
    --env-var "editor_token=$EDITOR_TOKEN" \
    --env-var "victor_token=$VICTOR_TOKEN" \
    --env-var "prod_catalog_id=$PROD_CATALOG_ID" \
    --env-var "prod_category_id=$PROD_CATEGORY_ID" \
    --env-var "prod_product_id=$PROD_PRODUCT_ID" \
    --env-var "open_catalog_id=$OPEN_CATALOG_ID" \
    --env-var "open_category_id=$OPEN_CATEGORY_ID" \
    --env-var "open_product_id=$OPEN_PRODUCT_ID" \
    "$@" \
    --reporter-cli \
    --reporter-json-export "$REPORT_DIR/$RUN_ID/step-up-matrix-$suffix.json"
}

echo "==> newman: E1 the challenge, E2 the round trip, E4 the negatives, E5 the members ..."
run_folder "Matrix" matrix

# --- E2's second half: both audit events on the wire path --------------------
# The pool round-robins, so the challenge and the elevated read can land on different pods; grep all
# of them. This asserts the emission POINTS exist in a live process, which no unit test can.
echo "==> E2 (audit): grepping the catalog pods' opa.abac.audit channel ..."
audit_log=""
for pod in $CATALOG_PODS; do
  audit_log="$audit_log$("$RUNTIME" logs "$pod" 2>&1 | grep 'opa.abac.audit' || true)"$'\n'
done
for event in STEP_UP_CHALLENGED SUPERVISED_PRODUCTION_READ; do
  printf '%s' "$audit_log" | grep -q "$event" || {
    echo "ERROR: the audit event $event never reached opa.abac.audit on any catalog pod." >&2
    exit 1; }
  echo "  $event present"
done
# The challenge event must NOT carry the claims the subject does not have at challenge time.
if printf '%s' "$audit_log" | grep 'STEP_UP_CHALLENGED' | grep -q 'authTime'; then
  echo "ERROR: STEP_UP_CHALLENGED carries authTime — the subject is precisely NOT elevated there." >&2
  exit 1
fi
echo "  ...and STEP_UP_CHALLENGED carries no authTime, as ADR 0030 §8 requires."

echo "==> newman: E6 — the supervised path is human-only (the target-gate cells, over REST) ..."
run_folder "E6 agents" agents
echo "==> newman: E6 — ...and the MCP tool-gate closes it a layer earlier ..."
run_folder "E6 agents through MCP" agents-mcp

# --- E7 the freshness drill + E3 the loop-prevention negative ----------------
# The override is a LEAF PUT. OPA's data PUT is create/overwrite, NOT merge, so a whole-document
# `PUT /v1/data/step_up {"max_age":5}` would take `loa` and `skew` with it — every token would then
# be unelevatable and the 401 would arrive instantly, proving nothing. E7a is the positive control
# that catches exactly that mistake.
echo "==> E7: overriding data.step_up.max_age -> ${DRILL_MAX_AGE}s (the LEAF path only) ..."
DRILL_ACTIVE=1
curl -sf -X PUT "$OPA_URL/v1/data/step_up/max_age" \
  -H 'Content-Type: application/json' -d "$DRILL_MAX_AGE" >/dev/null || {
  echo "ERROR: the leaf-path data override was refused by OPA." >&2; exit 1; }
OVERRIDDEN="$(curl -sf "$OPA_URL/v1/data/step_up")"
printf '%s' "$OVERRIDDEN" | python3 -c "
import json, sys
data = json.load(sys.stdin)['result']
assert data['max_age'] == $DRILL_MAX_AGE, data
assert data['skew'] == 30, ('skew was clobbered', data)
assert data['loa'] == {'aal1': 1, 'aal2': 2}, ('loa was clobbered', data)
print('  max_age=%s, and loa/skew SURVIVED the leaf PUT: %s' % (data['max_age'], data['loa']))" || {
  echo "ERROR: the override clobbered the sibling keys — the drill would be vacuous." >&2; exit 1; }

echo "==> E7a: minting a FRESH elevation under the shrunk window ..."
ANNA_DRILL_TOKEN="$("$MINER" --user sup-anna --acr aal2 --cookie-jar "$COOKIE_JAR")" || exit 1
DRILL_AUTH_TIME="$(token_claim "$ANNA_DRILL_TOKEN" auth_time)"
DRILL_IAT="$(token_claim "$ANNA_DRILL_TOKEN" iat)"
run_folder "E7a drill positive control" drill-a \
  --env-var "anna_drill_token=$ANNA_DRILL_TOKEN"

echo "==> E7b: waiting ${DRILL_WAIT}s — longer than max_age + skew = $((DRILL_MAX_AGE + 30))s ..."
sleep "$DRILL_WAIT"
run_folder "E7b drill expiry" drill-b \
  --env-var "anna_drill_token=$ANNA_DRILL_TOKEN"

echo "==> E3: re-authenticating WITHOUT max_age, reusing the SSO session ..."
ANNA_STALE_TOKEN="$("$MINER" --user sup-anna --acr aal2 --no-max-age --cookie-jar "$COOKIE_JAR")" || exit 1
STALE_AUTH_TIME="$(token_claim "$ANNA_STALE_TOKEN" auth_time)"
STALE_IAT="$(token_claim "$ANNA_STALE_TOKEN" iat)"
[ "$STALE_AUTH_TIME" = "$DRILL_AUTH_TIME" ] || {
  echo "ERROR: the re-auth changed auth_time ($DRILL_AUTH_TIME -> $STALE_AUTH_TIME); the SSO" >&2
  echo "       session was not reused, so E3 would prove nothing. Is the cookie jar readable?" >&2
  exit 1; }
[ "$STALE_IAT" -gt "$DRILL_IAT" ] || {
  echo "ERROR: the 'stale' token is not a new token (iat did not advance)." >&2; exit 1; }
echo "  a NEW token (iat $DRILL_IAT -> $STALE_IAT) carrying the SAME auth_time ($STALE_AUTH_TIME)."
run_folder "E3 loop prevention" drill-c \
  --env-var "anna_stale_token=$ANNA_STALE_TOKEN" \
  --env-var "drill_auth_time=$DRILL_AUTH_TIME" \
  --env-var "stale_auth_time=$STALE_AUTH_TIME" \
  --env-var "drill_iat=$DRILL_IAT" \
  --env-var "stale_iat=$STALE_IAT"

restore_opa_data

# --- teardown (success only — a failed run keeps its fixtures for debugging) --
if [ "${KEEP_FIXTURES:-0}" != "1" ]; then
  echo "==> Teardown: removing the f00d… catalogs, the Step-Up * teams and anna's reporting edge ..."
  reset_fixtures
fi

echo "==> Step-up matrix complete."
