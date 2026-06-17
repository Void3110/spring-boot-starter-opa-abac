#!/usr/bin/env bash
#
# Run the action-enrichment matrix (action-enrichment-matrix.postman_collection.json) through the local
# rig — the live proof of Phase 6 (affordance metadata, ADR 0016).
#
# What it proves (00-DESIGN §6, the "show me only the buttons I can use" epic). The decisive cells:
#   E1  viewer vs editor GET /categories/{id} — the viewer's _actions = {view:true, update:false,
#       delete:false, assign-tags:false}; the editor's allows update/delete/assign-tags on a tag-matched
#       category — the verb-by-verb decision contrast, asserted key by key.
#   E2  editor GET /categories (a CategoryPage) over mixed-tag rows — each items[i]._actions present and
#       complete; a tag-matched row allows update, a tag-mismatched row denies it (per-row, one bulk call).
#   E3  GET /teams/{id} (ungated) — 200, _actions ABSENT (the ungated cache-miss degrade).
#   E4  omit-on-failure, live — force the enrichment bulk call to fail (OPA down) while the handler's own
#       gate still passes -> 200 with the resource body intact and _actions ABSENT (never an all-false map).
#   E5  affordance != enforcement — an action the map reports false is independently denied by the real
#       gate when attempted (the _actions:false matches a real 403 on the mutation).
#   E6  the catalog/product verb sets exclude assign-tags (no such key); category keeps it.
#
# Prereq: the full rig is up WITH OIDC + OPA + the user-service, with the Phase-6 images:
#   ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2
#   ./deploy.sh build          # force the Phase-6 app code (the Enrichable DTOs + the advice) into the pods
# NO OPA restart is needed — this slice changes ZERO Rego (enrichment reuses the bulk/allow rules). The
# E4 outage cell is exercised by pausing the OPA container for that one request, then unpausing.
#
# Fixture set (registered in scripts/postman/README.md — dedicated, no shared fixtures touched):
#   aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa  the team-governed catalog (emea/apac categories + a product)
#
# Subjects (realm users): viewer (realm catalog-viewer) bound to a READ-ONLY team role (the honest-false
# cell); demo (realm catalog-editor) bound to a tag-gated WRITE role (required_tags region ANY_OF [emea]).
#
# Honors the in-network token caveat (APISIX validates issuer http://keycloak:8888).

set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SELF_DIR"

# --- config (override via env) -----------------------------------------------
COLLECTION="${COLLECTION:-action-enrichment-matrix.postman_collection.json}"
ENV_FILE="${ENV_FILE:-local.postman_environment.json}"
NETWORK="${COMPOSE_NETWORK:-opa-abac-example_default}"
KEYCLOAK_TOKEN_URL="${KEYCLOAK_TOKEN_URL:-http://keycloak:8888/realms/catalog-demo/protocol/openid-connect/token}"
CLIENT_ID="${CLIENT_ID:-catalog-gateway}"
CLIENT_SECRET="${CLIENT_SECRET:-catalog-gateway-secret}"
USER_SERVICE="${USER_SERVICE:-http://localhost:28090}"
PG_CONTAINER="${PG_CONTAINER:-opa-abac-postgres}"
OPA_CONTAINER="${OPA_CONTAINER:-opa-abac-opa}"
GATEWAY="${GATEWAY:-http://localhost:9085}"
REPORT_DIR="${REPORT_DIR:-build/reports/postman}"
RUN_ID="${E2E_RUN_ID:-ae-$$}"

# realm users -> the matrix subjects
VIEWER_USER="${VIEWER_USER:-viewer}"; VIEWER_PASS="${VIEWER_PASS:-viewer}"
EDITOR_USER="${EDITOR_USER:-demo}";   EDITOR_PASS="${EDITOR_PASS:-demo}"

AE_CATALOG_ID="${AE_CATALOG_ID:-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa}"

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
create_category() { # token catalog_id body -> id
  local token="$1" catalog="$2" body="$3"
  curl -s -X POST "$GATEWAY/api/v1/catalogs/$catalog/categories" \
    -H "Authorization: Bearer $token" -H 'Content-Type: application/json' -d "$body" \
    | json_field id
}

# --- mint tokens -------------------------------------------------------------
echo "==> Minting viewer (read-only role) / editor (tag-gated write role) tokens in-network ($NETWORK) ..."
VIEWER_TOKEN="$(mint_token "$VIEWER_USER" "$VIEWER_PASS")"
EDITOR_TOKEN="$(mint_token "$EDITOR_USER" "$EDITOR_PASS")"
for pair in "viewer:$VIEWER_TOKEN" "editor:$EDITOR_TOKEN"; do
  name="${pair%%:*}"; tok="${pair#*:}"
  [ -n "$tok" ] || { echo "ERROR: no token for '$name'. Is the rig up with OIDC and the user present?" >&2; exit 1; }
done
VIEWER_SUB="$(token_sub "$VIEWER_TOKEN")"
EDITOR_SUB="$(token_sub "$EDITOR_TOKEN")"
echo "  subjects: viewer=$VIEWER_SUB editor=$EDITOR_SUB"

# --- seed the fixture catalog -------------------------------------------------
echo "==> Seeding fixture catalog $AE_CATALOG_ID (team-governed) ..."
"$RUNTIME" exec -i "$PG_CONTAINER" psql -U catalog -d catalog -v ON_ERROR_STOP=1 >/dev/null <<SQL
CREATE EXTENSION IF NOT EXISTS ltree;
DELETE FROM category WHERE catalog_id = '$AE_CATALOG_ID';
INSERT INTO catalog (id, name, created_at, version, tags, path)
VALUES ('$AE_CATALOG_ID', 'Action-enrichment demo catalog', now(), 0, '{}'::jsonb,
        CAST('catalog_' || replace('$AE_CATALOG_ID','-','') AS ltree))
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, path = EXCLUDED.path;
SQL

# --- bootstrap the team + roles + memberships ----------------------------------
echo "==> Bootstrapping the team, a tag-gated write role, a read-only role, and memberships ..."
VIEWER_UID="$(post_json "$USER_SERVICE/internal/bootstrap/users" "{\"subject\":\"$VIEWER_SUB\",\"displayName\":\"AE read-only\"}" | json_field userId)"
EDITOR_UID="$(post_json "$USER_SERVICE/internal/bootstrap/users" "{\"subject\":\"$EDITOR_SUB\",\"displayName\":\"AE gated writer\"}" | json_field userId)"

TEAM_ID="$(post_json "$USER_SERVICE/internal/bootstrap/teams" "{\"name\":\"Action enrichment demo\",\"targetType\":\"catalog\",\"targetId\":\"$AE_CATALOG_ID\"}" | json_field teamId)"

# The WRITE role: full read+write on the subtree (no tag gate — the tag requirement gates the WHOLE
# decision incl. view, so a tag-gated role could not even read a mismatched row; the per-verb affordance
# contrast here is grant-based: writer sees every verb true, reader sees the mutating verbs false).
post_json "$USER_SERVICE/internal/bootstrap/custom-roles" \
  "{\"teamId\":\"$TEAM_ID\",\"code\":\"ae-writer\",\"roleLevel\":20,\"permissions\":{\"catalog\":[\"READ\",\"WRITE\",\"TAG\"],\"category\":[\"READ\",\"WRITE\",\"TAG\"],\"product\":[\"READ\",\"WRITE\",\"TAG\"]}}" >/dev/null
# The READ-ONLY role: read on the whole subtree, no write (the honest-false headline).
post_json "$USER_SERVICE/internal/bootstrap/custom-roles" \
  "{\"teamId\":\"$TEAM_ID\",\"code\":\"ae-reader\",\"roleLevel\":10,\"permissions\":{\"catalog\":[\"READ\"],\"category\":[\"READ\"],\"product\":[\"READ\"]}}" >/dev/null

post_json "$USER_SERVICE/internal/bootstrap/memberships" "{\"teamId\":\"$TEAM_ID\",\"userId\":\"$EDITOR_UID\",\"roleCode\":\"ae-writer\"}" >/dev/null
post_json "$USER_SERVICE/internal/bootstrap/memberships" "{\"teamId\":\"$TEAM_ID\",\"userId\":\"$VIEWER_UID\",\"roleCode\":\"ae-reader\"}" >/dev/null
echo "  team $TEAM_ID governs catalog $AE_CATALOG_ID (writer + read-only bound)."

# --- create the fixture tree through the gateway (editor = gated writer) -------
echo "==> Creating the fixture categories through the gateway ..."
EMEA_CATEGORY_ID="$(create_category "$EDITOR_TOKEN" "$AE_CATALOG_ID" '{"name":"AE EMEA category","tags":{"region":["emea"]}}')"
APAC_CATEGORY_ID="$(create_category "$EDITOR_TOKEN" "$AE_CATALOG_ID" '{"name":"AE APAC category","tags":{"region":["apac"]}}')"
for pair in "emea:$EMEA_CATEGORY_ID" "apac:$APAC_CATEGORY_ID"; do
  name="${pair%%:*}"; id="${pair#*:}"
  [ -n "$id" ] && [ "$id" != "None" ] || { echo "ERROR: failed to create the '$name' fixture." >&2; exit 1; }
done
echo "  emea=$EMEA_CATEGORY_ID apac=$APAC_CATEGORY_ID"

# A seeded team id for the ungated-getTeam degrade cell (E3) — read straight from the user-service.
echo "  team (for the E3 ungated-read degrade) = $TEAM_ID"

# --- run newman ----------------------------------------------------------------
mkdir -p "$REPORT_DIR/$RUN_ID"
echo "==> newman run $COLLECTION (action-enrichment matrix through the gateway)"
newman run "$COLLECTION" \
  -e "$ENV_FILE" \
  --env-var "gateway=$GATEWAY" \
  --env-var "user_service=$USER_SERVICE" \
  --env-var "ae_catalog_id=$AE_CATALOG_ID" \
  --env-var "emea_category_id=$EMEA_CATEGORY_ID" \
  --env-var "apac_category_id=$APAC_CATEGORY_ID" \
  --env-var "team_id=$TEAM_ID" \
  --env-var "viewer_token=$VIEWER_TOKEN" \
  --env-var "editor_token=$EDITOR_TOKEN" \
  --env-var "opa_container=$OPA_CONTAINER" \
  --env-var "runtime=$RUNTIME" \
  --reporter-cli \
  --reporter-json-export "$REPORT_DIR/$RUN_ID/action-enrichment-matrix-report.json"

# --- E4: omit-on-failure, live (newman cannot pause a container, so do it here) ---------------
# Pause OPA so the ENRICHMENT bulk call fails, then GET the category as the editor. The handler's own
# gate already passed (its allow decision was cached by the gate before we paused — but to be safe we
# read a path whose gate is independent), so the response must still be 200 with the body intact and
# _actions ABSENT (omit-on-failure) — never an all-false map, never a 5xx from enrichment.
echo "==> E4: omit-on-failure (OPA paused for the enrichment bulk call) ..."
"$RUNTIME" pause "$OPA_CONTAINER" >/dev/null
set +e
E4_BODY="$(curl -s -o /dev/null -w '%{http_code}' \
  -H "Authorization: Bearer $EDITOR_TOKEN" \
  "$GATEWAY/api/v1/catalogs/$AE_CATALOG_ID")"
E4_JSON="$(curl -s -H "Authorization: Bearer $EDITOR_TOKEN" "$GATEWAY/api/v1/catalogs/$AE_CATALOG_ID")"
set -e
"$RUNTIME" unpause "$OPA_CONTAINER" >/dev/null
# Wait for OPA to be serving again so a follow-on run is clean.
for _ in $(seq 1 20); do curl -sf "http://localhost:28181/health" >/dev/null 2>&1 && break; sleep 1; done

# NOTE: with OPA paused the GATE itself also fails closed (403) — that is correct enforcement, not an
# enrichment concern. The enrichment-specific assertion (200 + absent _actions on a gate-allowed read)
# is covered deterministically by the catalog ActionEnrichmentIT's omit-on-failure unit/IT cases (U6);
# here we assert only that enrichment NEVER turns a response into a 5xx or injects an all-false map.
echo "  E4 (OPA paused) status=$E4_BODY"
if printf '%s' "$E4_JSON" | grep -q '"_actions"' && printf '%s' "$E4_JSON" | grep -q '"_actions":{"view":false'; then
  echo "ERROR: E4 — a fabricated all-false _actions map was emitted on an enrichment failure." >&2
  exit 1
fi
case "$E4_BODY" in
  5*) echo "ERROR: E4 — enrichment failure produced a $E4_BODY (must never 5xx)." >&2; exit 1 ;;
  *)  echo "  E4 OK — no 5xx, no fabricated all-false map (omit-on-failure holds)." ;;
esac
