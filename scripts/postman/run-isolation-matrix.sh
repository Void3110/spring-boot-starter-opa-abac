#!/usr/bin/env bash
#
# Run the multi-tenant ISOLATION matrix (isolation-matrix.postman_collection.json) through the rig.
#
# Proves Slice B4 (ADR 0018/0019) end to end — membership is the sole access path + safe self-service:
#   E1  alice (fresh, catalog-editor, no team)  GET /catalogs            -> []        (no fallback leak)
#   E2  alice POST /catalogs -> POST /teams(her catalog) -> GET /catalogs -> [hers]   (self-service works)
#   E3  bob (fresh)                              GET /catalogs            -> []
#   E4  alice adds bob to her team -> bob        GET /catalogs            -> [Alice's] (scoped access)
#   E5  carol (pre-seeded own team + member of Alice's) GET /catalogs     -> 2         (multi-team)
#   E6  bob (single team) GET Carol's catalog id directly                -> 403       (no direct-id leak)
#   E7  bob POST /teams targetId=Alice's catalog (squat)                 -> 403       (ownership)
#
# Prereq: the FULL rig is up with the B4 code + OPA restarted after the T1 rego edit:
#   ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2
#   ./deploy.sh build           # fresh app images carrying T1-T8
#   docker restart opa-abac-opa # reload the B4 policy (T1)
#   # the user-service must run with ABAC_OWNERSHIP_ENABLED=true (compose.usermgmt.yaml) for E2/E7.
#
# How it wires the data: it mints three in-network tokens (alice/bob/carol — issuer must match what
# APISIX validates, http://keycloak:8888), decodes each sub, bootstraps the three as user-service users,
# pre-seeds CAROL's OWN catalog + team (she's the owner), and leaves ALICE's create + add-member to the
# matrix (performed LIVE through the gateway). Carol's membership in Alice's (live) team is added by the
# collection after E2 captures Alice's team id. Honors the in-network token caveat.

set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SELF_DIR"

# --- config (override via env) -----------------------------------------------
COLLECTION="${COLLECTION:-isolation-matrix.postman_collection.json}"
ENV_FILE="${ENV_FILE:-local.postman_environment.json}"
NETWORK="${COMPOSE_NETWORK:-opa-abac-example_default}"
KEYCLOAK_TOKEN_URL="${KEYCLOAK_TOKEN_URL:-http://keycloak:8888/realms/catalog-demo/protocol/openid-connect/token}"
CLIENT_ID="${CLIENT_ID:-catalog-gateway}"
CLIENT_SECRET="${CLIENT_SECRET:-catalog-gateway-secret}"
USER_SERVICE="${USER_SERVICE:-http://localhost:28090}"
PG_CONTAINER="${PG_CONTAINER:-opa-abac-postgres}"
UM_PG_CONTAINER="${UM_PG_CONTAINER:-opa-abac-usermgmt-postgres}"
GATEWAY="${GATEWAY:-http://localhost:9085}"
REPORT_DIR="${REPORT_DIR:-build/reports/postman}"
RUN_ID="${E2E_RUN_ID:-isolation-$$}"

ALICE_USER="${ALICE_USER:-alice}"; ALICE_PASS="${ALICE_PASS:-alice}"
BOB_USER="${BOB_USER:-bob}";       BOB_PASS="${BOB_PASS:-bob}"
CAROL_USER="${CAROL_USER:-carol}"; CAROL_PASS="${CAROL_PASS:-carol}"

# Carol's pre-seeded own catalog (stable id so re-running is deterministic).
CAROL_CATALOG_ID="${CAROL_CATALOG_ID:-cab01000-0000-0000-0000-000000000001}"

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

# --- mint tokens + decode subs -----------------------------------------------
echo "==> Minting alice/bob/carol tokens in-network ($NETWORK) ..."
ALICE_TOKEN="$(mint_token "$ALICE_USER" "$ALICE_PASS")"
BOB_TOKEN="$(mint_token "$BOB_USER" "$BOB_PASS")"
CAROL_TOKEN="$(mint_token "$CAROL_USER" "$CAROL_PASS")"
for pair in "alice:$ALICE_TOKEN" "bob:$BOB_TOKEN" "carol:$CAROL_TOKEN"; do
  name="${pair%%:*}"; tok="${pair#*:}"
  [ -n "$tok" ] || { echo "ERROR: no token for '$name'. Is the rig up with OIDC and the user present in the realm?" >&2; exit 1; }
done
ALICE_SUB="$(token_sub "$ALICE_TOKEN")"
BOB_SUB="$(token_sub "$BOB_TOKEN")"
CAROL_SUB="$(token_sub "$CAROL_TOKEN")"
echo "  subjects: alice=$ALICE_SUB bob=$BOB_SUB carol=$CAROL_SUB"

# --- self-reset (the matrix is NOT idempotent: E1 asserts alice sees []) ----------
# E2 creates "Alice Co" + "Alice Team" LIVE and Carol's fixture persists across runs, so a second run
# would start with alice already owning a catalog and E1 would fail. Wipe both demo tenants first — by
# NAME, so only this matrix's fixtures are touched. usermgmt rows go first (FK: membership -> team);
# the catalog rows (categories cascade) go next. All idempotent (no-ops on a fresh rig).
echo "==> Resetting prior isolation-matrix tenants (Alice Co / Carol Co) ..."
"$RUNTIME" exec -i "$PG_CONTAINER" psql -U catalog -d catalog -v ON_ERROR_STOP=1 >/dev/null <<SQL
DELETE FROM category WHERE catalog_id IN (SELECT id FROM catalog WHERE name IN ('Alice Co', 'Carol Co'));
DELETE FROM catalog WHERE name IN ('Alice Co', 'Carol Co');
SQL
"$RUNTIME" exec -i "${UM_PG_CONTAINER:-opa-abac-usermgmt-postgres}" psql -U usermgmt -d usermgmt -v ON_ERROR_STOP=1 >/dev/null <<SQL
DELETE FROM team_membership WHERE team_id IN (SELECT id FROM team WHERE name IN ('Alice Team', 'Carol Team'));
DELETE FROM team WHERE name IN ('Alice Team', 'Carol Team');
SQL

# --- bootstrap the three as user-service users (so add-member can reference them) ---
echo "==> Bootstrapping alice/bob/carol as user-service users ..."
ALICE_UID="$(post_json "$USER_SERVICE/internal/bootstrap/users" "{\"subject\":\"$ALICE_SUB\",\"displayName\":\"Alice\"}" | json_field userId)"
BOB_UID="$(post_json "$USER_SERVICE/internal/bootstrap/users" "{\"subject\":\"$BOB_SUB\",\"displayName\":\"Bob\"}" | json_field userId)"
CAROL_UID="$(post_json "$USER_SERVICE/internal/bootstrap/users" "{\"subject\":\"$CAROL_SUB\",\"displayName\":\"Carol\"}" | json_field userId)"

# --- pre-seed Carol's OWN catalog + team (she is the owner; multi-team needs her own + Alice's) ---
echo "==> Pre-seeding Carol's own catalog $CAROL_CATALOG_ID + team ..."
"$RUNTIME" exec -i "$PG_CONTAINER" psql -U catalog -d catalog -v ON_ERROR_STOP=1 >/dev/null <<SQL
INSERT INTO catalog (id, name, created_at, version, tags, path, created_by)
VALUES ('$CAROL_CATALOG_ID', 'Carol Co', now(), 0, '{}'::jsonb,
        CAST('catalog_' || replace('$CAROL_CATALOG_ID','-','') AS ltree),
        '$CAROL_SUB')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, path = EXCLUDED.path, created_by = EXCLUDED.created_by;
SQL
CAROL_TEAM_ID="$(post_json "$USER_SERVICE/internal/bootstrap/teams" "{\"name\":\"Carol Team\",\"targetType\":\"catalog\",\"targetId\":\"$CAROL_CATALOG_ID\"}" | json_field teamId)"
post_json "$USER_SERVICE/internal/bootstrap/memberships" "{\"teamId\":\"$CAROL_TEAM_ID\",\"userId\":\"$CAROL_UID\",\"roleCode\":\"owner\"}" >/dev/null
echo "  team $CAROL_TEAM_ID governs catalog $CAROL_CATALOG_ID (carol = owner)."

# --- run newman --------------------------------------------------------------
# The collection performs E1-E7 in order, creating Alice's catalog+team LIVE (E2), capturing the new
# team id, and adding bob (E4) + carol (E5) to Alice's team via the in-network bootstrap membership API
# (so the membership is deterministic; the SPA uses the public add-member). The user ids + carol's
# pre-seeded catalog id are passed in as env vars.
mkdir -p "$REPORT_DIR/$RUN_ID"
echo "==> newman run $COLLECTION (multi-tenant isolation matrix through the gateway)"
newman run "$COLLECTION" \
  -e "$ENV_FILE" \
  --env-var "gateway=$GATEWAY" \
    --env-var "collection_base_url=$GATEWAY/api/v1" \
  --env-var "user_service=$USER_SERVICE" \
  --env-var "alice_token=$ALICE_TOKEN" \
  --env-var "bob_token=$BOB_TOKEN" \
  --env-var "carol_token=$CAROL_TOKEN" \
  --env-var "alice_uid=$ALICE_UID" \
  --env-var "bob_uid=$BOB_UID" \
  --env-var "carol_uid=$CAROL_UID" \
  --env-var "carol_catalog_id=$CAROL_CATALOG_ID" \
  --env-var "carol_team_id=$CAROL_TEAM_ID" \
  --reporters cli,json \
  --reporter-json-export "$REPORT_DIR/$RUN_ID/isolation-matrix-report.json"

# --- teardown (success only — a failed run keeps its fixtures for debugging) --
# KEEP_FIXTURES=1 skips it. Same name-keyed deletes as the pre-run self-reset: the matrix creates
# its catalogs/teams live through self-service (generated ids), so names are the stable key. With
# this, a green run leaves the store as it found it — the pre-run reset stays as belt-and-braces.
if [ "${KEEP_FIXTURES:-0}" != "1" ]; then
  echo "==> Teardown: removing the Alice Co / Carol Co fixtures (KEEP_FIXTURES=1 keeps them) ..."
  "$RUNTIME" exec -i "$UM_PG_CONTAINER" psql -U usermgmt -d usermgmt -v ON_ERROR_STOP=1 >/dev/null <<SQL
DELETE FROM team WHERE name IN ('Alice Team', 'Carol Team');
SQL
  "$RUNTIME" exec -i "$PG_CONTAINER" psql -U catalog -d catalog -v ON_ERROR_STOP=1 >/dev/null <<SQL
DELETE FROM catalog WHERE name IN ('Alice Co', 'Carol Co');
SQL
fi
