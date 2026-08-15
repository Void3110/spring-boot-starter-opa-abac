#!/usr/bin/env bash
#
# Run the permission-categories matrix (permission-categories-matrix.postman_collection.json) through
# the local rig — the live proof of Phase 6.5 (coarse categories + delegation, ADR 0007).
#
# What it proves (00-DESIGN §4 — the cells that justify the slice):
#   E1  deny-overrides: a role granting WRITE on category but denying delete — PUT 200 / DELETE 403
#   E2  the TAG/WRITE boundary, BOTH directions: WRITE-no-TAG edits content (200) but a tags delta
#       is denied (403); TAG-no-WRITE relabels (200) but a content edit is denied (403)
#   E3  senior delegation via the management API: a member-level grant lands (201, the assignable
#       verdict LIVE through data.role.assignable); senior/admin targets and a subset-violating
#       candidate answer 422 ROLE_SUBSET_VIOLATION
#   E4  the admin tier: below 201; peer admin 422 (strict <); THE DESIGNED CELL — an admin whose own
#       role denies delete still assigns full WRITE (200): admin power is the TIER, not the subset
#   E5  a stale flat role (direct DB INSERT — the sanctioned authoring bypass) decides NOTHING:
#       deny everywhere via the ∅-expansion floor
#   E6  ladder parity: reader-bound GET 200 / PUT 403 / DELETE 403; member-bound updates/relabels/
#       deletes (creates are TYPE-level and ride the realm fallback by 5.97 design — out of scope)
#
# One newman run: the collection REBINDS the instance-subject's membership between cell groups via
# the internal bootstrap API (one role per user per team), so a single realm user walks the ladder.
#
# Prereq: the full rig is up WITH OIDC + OPA + the user-service, with the 6.5 images:
#   ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2
#   ./deploy.sh build      # catalog image; the usermgmt image needs its own explicit build:
#   docker build -t opa-abac-usermgmt:local -f example-user-management-service/Dockerfile .
# The runner restarts OPA itself (this slice rewrites EVERY policy; OPA has no reliable --watch).
#
# Fixture set (registered in scripts/postman/README.md — dedicated, no shared fixtures touched):
#   99999999-9999-9999-9999-999999999999  the team-governed catalog (seven per-cell categories)
#
# THE ADMIN-DENIAL WINDOW (E4's designed cell): the SYSTEM administrator row temporarily gains
# denied_actions {"*":["delete"]} for the duration of the newman run, reverted on EXIT (trap) — the
# only reachable construction: a custom level-30 code carries no team:manage capability.
#
# Honors the in-network token caveat (APISIX validates issuer http://keycloak:8888).

set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SELF_DIR"

# --- config (override via env) -----------------------------------------------
COLLECTION="${COLLECTION:-permission-categories-matrix.postman_collection.json}"
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
RUN_ID="${E2E_RUN_ID:-pc-$$}"

# realm users -> the matrix subjects
LADDER_USER="${LADDER_USER:-viewer}";   LADDER_PASS="${LADDER_PASS:-viewer}"     # the rebound instance subject
SENIOR_USER="${SENIOR_USER:-outsider}"; SENIOR_PASS="${SENIOR_PASS:-outsider}"  # the E3 senior
ADMIN_USER="${ADMIN_USER:-editor}";     ADMIN_PASS="${ADMIN_PASS:-editor}"      # the E4 administrator
CREATOR_USER="${CREATOR_USER:-demo}";   CREATOR_PASS="${CREATOR_PASS:-demo}"    # fixture creator (editor realm)

PC_CATALOG_ID="${PC_CATALOG_ID:-99999999-9999-9999-9999-999999999999}"
STALE_ROLE_ID="99999999-9999-9999-9999-999999999991"
SUPER20_ROLE_ID="99999999-9999-9999-9999-999999999992"
ADMIN_ROLE_ID="00000000-0000-0000-0000-000000000002"

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
# This slice rewrites EVERY catalog policy + adds permissions.rego/role.rego + the expansion data
# file. OPA loads /policies once (no --watch) — restart and health-poll before anything else.
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

# --- the admin-denial window (reverted on ANY exit) ------------------------------
um_psql() { "$RUNTIME" exec -i "$UM_PG_CONTAINER" psql -U usermgmt -d usermgmt -v ON_ERROR_STOP=1; }
revert_admin_denial() {
  echo "==> Reverting the system administrator row's denied_actions ..."
  um_psql >/dev/null <<SQL || echo "WARN: could not revert the administrator denial — run manually:
  UPDATE role_definition SET denied_actions = '{}'::jsonb WHERE id = '$ADMIN_ROLE_ID';" >&2
UPDATE role_definition SET denied_actions = '{}'::jsonb WHERE id = '$ADMIN_ROLE_ID';
SQL
}
trap revert_admin_denial EXIT

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
echo "==> Minting ladder / senior / admin / creator tokens in-network ($NETWORK) ..."
LADDER_TOKEN="$(mint_token "$LADDER_USER" "$LADDER_PASS")"
SENIOR_TOKEN="$(mint_token "$SENIOR_USER" "$SENIOR_PASS")"
ADMIN_TOKEN="$(mint_token "$ADMIN_USER" "$ADMIN_PASS")"
CREATOR_TOKEN="$(mint_token "$CREATOR_USER" "$CREATOR_PASS")"
for pair in "ladder:$LADDER_TOKEN" "senior:$SENIOR_TOKEN" "admin:$ADMIN_TOKEN" "creator:$CREATOR_TOKEN"; do
  name="${pair%%:*}"; tok="${pair#*:}"
  [ -n "$tok" ] || { echo "ERROR: no token for '$name'. Is the rig up with OIDC and the user present?" >&2; exit 1; }
done

LADDER_SUB="$(token_sub "$LADDER_TOKEN")"
SENIOR_SUB="$(token_sub "$SENIOR_TOKEN")"
ADMIN_SUB="$(token_sub "$ADMIN_TOKEN")"
CREATOR_SUB="$(token_sub "$CREATOR_TOKEN")"
echo "  subjects: ladder=$LADDER_SUB senior=$SENIOR_SUB admin=$ADMIN_SUB creator=$CREATOR_SUB"

# --- seed the fixture catalog (catalog DB, ltree path; idempotent) ----------------
echo "==> Seeding fixture catalog $PC_CATALOG_ID (categories of prior runs removed) ..."
"$RUNTIME" exec -i "$PG_CONTAINER" psql -U catalog -d catalog -v ON_ERROR_STOP=1 >/dev/null <<SQL
CREATE EXTENSION IF NOT EXISTS ltree;
DELETE FROM category WHERE catalog_id = '$PC_CATALOG_ID';
INSERT INTO catalog (id, name, created_at, version, tags, path)
VALUES ('$PC_CATALOG_ID', 'Permission-categories demo catalog', now(), 0, '{}'::jsonb,
        CAST('catalog_' || replace('$PC_CATALOG_ID','-','') AS ltree))
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, path = EXCLUDED.path;
SQL

# --- bootstrap users, the team, and the authorable roles --------------------------
echo "==> Bootstrapping users, the team, and the category-token roles ..."
LADDER_UID="$(post_json "$USER_SERVICE/internal/bootstrap/users" "{\"subject\":\"$LADDER_SUB\",\"displayName\":\"$LADDER_USER\"}" | json_field userId)"
SENIOR_UID="$(post_json "$USER_SERVICE/internal/bootstrap/users" "{\"subject\":\"$SENIOR_SUB\",\"displayName\":\"$SENIOR_USER\"}" | json_field userId)"
ADMIN_UID="$(post_json "$USER_SERVICE/internal/bootstrap/users" "{\"subject\":\"$ADMIN_SUB\",\"displayName\":\"$ADMIN_USER\"}" | json_field userId)"
TARGET1_UID="$(post_json "$USER_SERVICE/internal/bootstrap/users" "{\"subject\":\"pc-target-1\",\"displayName\":\"PC target 1\"}" | json_field userId)"
TARGET2_UID="$(post_json "$USER_SERVICE/internal/bootstrap/users" "{\"subject\":\"pc-target-2\",\"displayName\":\"PC target 2\"}" | json_field userId)"
CREATOR_UID="$(post_json "$USER_SERVICE/internal/bootstrap/users" "{\"subject\":\"$CREATOR_SUB\",\"displayName\":\"$CREATOR_USER\"}" | json_field userId)"

TEAM_ID="$(post_json "$USER_SERVICE/internal/bootstrap/teams" "{\"name\":\"Permission categories demo\",\"targetType\":\"catalog\",\"targetId\":\"$PC_CATALOG_ID\"}" | json_field teamId)"

# E1: WRITE granted ON CATEGORY, delete denied (deny-overrides). Deliberately NO catalog WRITE:
# denial subtraction is PER TYPE (00-DESIGN §2.9) and inherited_grant consults the ANCESTOR type's
# effective set — a catalog-wide WRITE would legitimately cover subtree deletes and bypass the
# category-type denial. To fence delete subtree-wide, deny it on every granted type (or on "*").
post_json "$USER_SERVICE/internal/bootstrap/custom-roles" \
  "{\"teamId\":\"$TEAM_ID\",\"code\":\"pc-no-delete\",\"roleLevel\":20,\"permissions\":{\"catalog\":[\"READ\"],\"category\":[\"READ\",\"WRITE\",\"TAG\"]},\"deniedActions\":{\"category\":[\"delete\"]}}" >/dev/null
# E2: the two boundary roles.
post_json "$USER_SERVICE/internal/bootstrap/custom-roles" \
  "{\"teamId\":\"$TEAM_ID\",\"code\":\"pc-writer-no-tag\",\"roleLevel\":20,\"permissions\":{\"category\":[\"READ\",\"WRITE\"]}}" >/dev/null
post_json "$USER_SERVICE/internal/bootstrap/custom-roles" \
  "{\"teamId\":\"$TEAM_ID\",\"code\":\"pc-tagger-no-write\",\"roleLevel\":20,\"permissions\":{\"category\":[\"READ\",\"TAG\"]}}" >/dev/null
# E4's designed-cell candidate: a full-WRITE member-tier role.
post_json "$USER_SERVICE/internal/bootstrap/custom-roles" \
  "{\"teamId\":\"$TEAM_ID\",\"code\":\"pc-full-write\",\"roleLevel\":20,\"permissions\":{\"category\":[\"READ\",\"WRITE\"]}}" >/dev/null
# Slice B4: the fixture CREATOR needs a RESOLVED role granting create on the catalog (the realm fallback
# that used to let a bare editor-realm user create is gone). catalog:WRITE+TAG -> create/assign-tags
# inherit to categories; category:WRITE+TAG covers the per-instance writes the cells exercise.
post_json "$USER_SERVICE/internal/bootstrap/custom-roles" \
  "{\"teamId\":\"$TEAM_ID\",\"code\":\"pc-creator\",\"roleLevel\":20,\"permissions\":{\"catalog\":[\"READ\",\"WRITE\",\"TAG\"],\"category\":[\"READ\",\"WRITE\",\"TAG\"]}}" >/dev/null

# --- the sanctioned authoring bypasses (direct DB INSERT into the usermgmt DB) -----
# E5: a stale pre-6.5 flat row (the authoring API rejects flat tokens BY DESIGN — the bypass is the
# point: the cell proves a stale STORED row decides nothing). E3d: a subset-violating candidate
# (GRANT at level 20 — unauthorable, same bypass class). Idempotent upserts; team-scoped rows.
echo "==> Seeding the stale flat role + the subset-violating candidate (direct DB) ..."
um_psql >/dev/null <<SQL
INSERT INTO role_definition (id, code, is_system, team_id, attributes, permissions, denied_actions,
                             required_tags, version, created_at, last_modified_at)
VALUES ('$STALE_ROLE_ID', 'pc-stale', false, '$TEAM_ID',
        '{"role_level": 10}'::jsonb, '{"catalog": ["read"], "category": ["read"]}'::jsonb,
        '{}'::jsonb, '{}'::jsonb, 0, now(), now())
ON CONFLICT (id) DO UPDATE
  SET team_id = EXCLUDED.team_id, permissions = EXCLUDED.permissions, attributes = EXCLUDED.attributes;
INSERT INTO role_definition (id, code, is_system, team_id, attributes, permissions, denied_actions,
                             required_tags, version, created_at, last_modified_at)
VALUES ('$SUPER20_ROLE_ID', 'pc-super20', false, '$TEAM_ID',
        '{"role_level": 20}'::jsonb, '{"catalog": ["GRANT"]}'::jsonb,
        '{}'::jsonb, '{}'::jsonb, 0, now(), now())
ON CONFLICT (id) DO UPDATE
  SET team_id = EXCLUDED.team_id, permissions = EXCLUDED.permissions, attributes = EXCLUDED.attributes;
-- E4's designed cell: the system administrator temporarily denies delete (reverted on EXIT).
UPDATE role_definition SET denied_actions = '{"*": ["delete"]}'::jsonb WHERE id = '$ADMIN_ROLE_ID';
-- Idempotency: the E3/E4 add-member cells expect 201 — clear the targets' prior memberships.
DELETE FROM team_membership WHERE team_id = '$TEAM_ID'
  AND user_id IN ('$TARGET1_UID', '$TARGET2_UID');
SQL

# --- the fixed bindings (the ladder subject is REBOUND by the collection itself) ----
post_json "$USER_SERVICE/internal/bootstrap/memberships" "{\"teamId\":\"$TEAM_ID\",\"userId\":\"$SENIOR_UID\",\"roleCode\":\"senior\"}" >/dev/null
post_json "$USER_SERVICE/internal/bootstrap/memberships" "{\"teamId\":\"$TEAM_ID\",\"userId\":\"$ADMIN_UID\",\"roleCode\":\"administrator\"}" >/dev/null
# Slice B4: bind the fixture creator to its catalog-WRITE+TAG role so the per-cell category creates below
# resolve a role (the realm fallback that used to let a bare editor-realm user create is gone).
post_json "$USER_SERVICE/internal/bootstrap/memberships" "{\"teamId\":\"$TEAM_ID\",\"userId\":\"$CREATOR_UID\",\"roleCode\":\"pc-creator\"}" >/dev/null
echo "  team $TEAM_ID governs catalog $PC_CATALOG_ID (senior + administrator + creator bound; ladder rebinds in-collection)."

# --- create the per-cell fixture categories through the gateway (creator = editor realm) -
echo "==> Creating the per-cell fixture categories through the gateway ..."
CAT_E1_ID="$(create_category "$CREATOR_TOKEN" "$PC_CATALOG_ID" '{"name":"PC e1","tags":{"region":["emea"]}}')"
CAT_E2A_ID="$(create_category "$CREATOR_TOKEN" "$PC_CATALOG_ID" '{"name":"PC e2a","tags":{"region":["emea"]}}')"
CAT_E2B_ID="$(create_category "$CREATOR_TOKEN" "$PC_CATALOG_ID" '{"name":"PC e2b","tags":{"region":["emea"]}}')"
CAT_E5_ID="$(create_category "$CREATOR_TOKEN" "$PC_CATALOG_ID" '{"name":"PC e5","tags":{"region":["emea"]}}')"
CAT_E6R_ID="$(create_category "$CREATOR_TOKEN" "$PC_CATALOG_ID" '{"name":"PC e6r","tags":{"region":["emea"]}}')"
CAT_E6M_ID="$(create_category "$CREATOR_TOKEN" "$PC_CATALOG_ID" '{"name":"PC e6m","tags":{"region":["emea"]}}')"
CAT_E6D_ID="$(create_category "$CREATOR_TOKEN" "$PC_CATALOG_ID" '{"name":"PC e6d","tags":{"region":["emea"]}}')"
for pair in "e1:$CAT_E1_ID" "e2a:$CAT_E2A_ID" "e2b:$CAT_E2B_ID" "e5:$CAT_E5_ID" \
            "e6r:$CAT_E6R_ID" "e6m:$CAT_E6M_ID" "e6d:$CAT_E6D_ID"; do
  name="${pair%%:*}"; id="${pair#*:}"
  [ -n "$id" ] && [ "$id" != "None" ] || { echo "ERROR: failed to create the '$name' fixture." >&2; exit 1; }
done
echo "  e1=$CAT_E1_ID e2a=$CAT_E2A_ID e2b=$CAT_E2B_ID e5=$CAT_E5_ID"
echo "  e6r=$CAT_E6R_ID e6m=$CAT_E6M_ID e6d=$CAT_E6D_ID"

# --- run newman ----------------------------------------------------------------
mkdir -p "$REPORT_DIR/$RUN_ID"
echo "==> newman run $COLLECTION (permission-categories matrix)"
newman run "$COLLECTION" \
  -e "$ENV_FILE" \
  --env-var "gateway=$GATEWAY" \
  --env-var "user_service=$USER_SERVICE" \
  --env-var "pc_catalog_id=$PC_CATALOG_ID" \
  --env-var "team_id=$TEAM_ID" \
  --env-var "ladder_uid=$LADDER_UID" \
  --env-var "target1_uid=$TARGET1_UID" \
  --env-var "target2_uid=$TARGET2_UID" \
  --env-var "cat_e1_id=$CAT_E1_ID" \
  --env-var "cat_e2a_id=$CAT_E2A_ID" \
  --env-var "cat_e2b_id=$CAT_E2B_ID" \
  --env-var "cat_e5_id=$CAT_E5_ID" \
  --env-var "cat_e6r_id=$CAT_E6R_ID" \
  --env-var "cat_e6m_id=$CAT_E6M_ID" \
  --env-var "cat_e6d_id=$CAT_E6D_ID" \
  --env-var "ladder_token=$LADDER_TOKEN" \
  --env-var "senior_token=$SENIOR_TOKEN" \
  --env-var "admin_token=$ADMIN_TOKEN" \
  --reporters cli,json \
  --reporter-json-export "$REPORT_DIR/$RUN_ID/permission-categories-matrix-report.json"

# --- teardown (success only — a failed run keeps its fixtures for debugging) --
# KEEP_FIXTURES=1 skips it. Every run re-seeds its registry ids from scratch, so tearing them
# down keeps the shared store (and the demo UI's directory/team lists) clean. The DELETEs ride
# the FK cascades: team -> memberships + custom roles + tag definitions; catalog -> categories
# -> products.
if [ "${KEEP_FIXTURES:-0}" != "1" ]; then
  echo "==> Teardown: removing the $PC_CATALOG_ID fixture(s) (KEEP_FIXTURES=1 keeps them) ..."
  "$RUNTIME" exec -i "${UM_PG_CONTAINER:-opa-abac-usermgmt-postgres}" psql -U usermgmt -d usermgmt -v ON_ERROR_STOP=1 >/dev/null <<SQL
DELETE FROM team WHERE target_type = 'catalog' AND target_id IN ('$PC_CATALOG_ID');
DELETE FROM app_user WHERE subject IN ('pc-target-1', 'pc-target-2');
SQL
  "$RUNTIME" exec -i "$PG_CONTAINER" psql -U catalog -d catalog -v ON_ERROR_STOP=1 >/dev/null <<SQL
DELETE FROM catalog WHERE id IN ('$PC_CATALOG_ID');
SQL
fi
