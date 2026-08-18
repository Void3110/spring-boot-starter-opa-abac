#!/usr/bin/env bash
#
# Seed a stable, reproducible demo dataset for the browser SPA (example-demo-ui).
#
# Without this, the realm users resolve to NO team role (the realm fallback), so the catalog returns
# resources with no `_actions` and the SPA shows "no affordances enriched". This script binds the
# demo identities to real team roles on a team-governed catalog, so:
#   - the catalog/category/product `_actions` map populates, and
#   - switching identity in the SPA shows DIFFERENT affordances per role (the whole point).
#
# Idempotent-ish: re-running re-seeds the catalog row and re-bootstraps the team/roles/memberships
# (the bootstrap endpoints upsert by natural key). Safe to run after every `ENABLE_SPA=1 ./deploy.sh up`.
#
# Run AFTER:  ENABLE_SPA=1 ENABLE_MCP=1 ./deploy.sh up --pods 2   (user-service + enrichment + the
#             packaged SPA; deploy.sh tears DOWN whichever of the SPA/MCP stacks its flag is missing,
#             so a session that also runs run-step-up-matrix.sh — whose preflight needs the MCP
#             server — must carry BOTH flags on the SAME up)
# Then:       scripts/postman/seed-demo-data.sh
#
# The SUPERVISED half of the seed (sup-demo / pm-demo, the two Demo * catalogs) needs a realm that
# already carries those personas. On a realm imported before they landed, the seed stops with the
# down-first re-import spelled out.
#
# Roles demonstrated (all on one team that governs the demo catalog):
#   editor   -> owner        (system ladder)         — every verb INCLUDING team management: the
#                                                      control plane (add/change/remove member,
#                                                      define-roles, transfer) needs a SYSTEM role —
#                                                      custom roles are management-incapable BY DESIGN
#                                                      (ADR 0015 §5), so without an owner the demo
#                                                      team could not be managed by anyone
#   demo     -> demo-editor  (READ/WRITE/TAG)        — can edit + tag, cannot grant
#   viewer   -> demo-viewer  (READ)                  — read-only; mutating verbs honestly false
#   outsider -> (unbound)                            — no team role -> the "no access" story
#
# And a SUPERVISED world beside it (the console's step-up story, ADR 0029/0030/0032/0033):
#   pm-demo  -> owner on both Demo * Teams           — the MEMBER leg: production needs no ceremony
#   sup-demo -> supervises pm-demo, bound to NOTHING — the SUPERVISED leg: both catalogs reachable
#                                                      by derivation, and the production one's
#                                                      CONTENTS demand a fresh second factor
#   (demo-admin, level 30, stays DEFINED but unbound — the owner can grant it live from the SPA's
#    member picker / role editor, which is exactly the custom-role authoring story.)

set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SELF_DIR"

# --- config (override via env) -----------------------------------------------
NETWORK="${COMPOSE_NETWORK:-opa-abac-example_default}"
KEYCLOAK_TOKEN_URL="${KEYCLOAK_TOKEN_URL:-http://keycloak:8888/realms/catalog-demo/protocol/openid-connect/token}"
CLIENT_ID="${CLIENT_ID:-catalog-gateway}"
CLIENT_SECRET="${CLIENT_SECRET:-catalog-gateway-secret}"
USER_SERVICE="${USER_SERVICE:-http://localhost:28090}"
PG_CONTAINER="${PG_CONTAINER:-opa-abac-postgres}"
GATEWAY="${GATEWAY:-http://localhost:9085}"
# The catalog service's PUBLISHED port: the operator tag write goes to /internal/*, which the
# gateway 404s at the edge (run-step-up-matrix.sh's recipe).
CATALOG_SERVICE="${CATALOG_SERVICE:-http://localhost:28081}"
# The least-privilege service account (view-users only) used to look up sup-demo's subject — she
# carries a TOTP, and Keycloak's direct-grant flow demands one from any identity that has it, so
# she can never be minted here. run-team-matrix.sh's 'dora' recipe.
DIRECTORY_CLIENT_ID="${DIRECTORY_CLIENT_ID:-catalog-directory}"
DIRECTORY_CLIENT_SECRET="${DIRECTORY_CLIENT_SECRET:-catalog-directory-secret}"
KEYCLOAK_ADMIN_BASE="${KEYCLOAK_ADMIN_BASE:-http://keycloak:8888/admin/realms/catalog-demo}"

# The demo catalog the SPA highlights (stable id so re-seeding is deterministic). 8-4-4-4-12 hex.
DEMO_CATALOG_ID="${DEMO_CATALOG_ID:-d3110000-0000-0000-0000-000000000001}"
# The supervised world (the SPA's step-up story): one production catalog, one open.
DEMO_PROD_CATALOG_ID="${DEMO_PROD_CATALOG_ID:-d3110000-0000-0000-0000-000000000002}"
DEMO_OPEN_CATALOG_ID="${DEMO_OPEN_CATALOG_ID:-d3110000-0000-0000-0000-000000000003}"
# Realm users -> demo roles.
ADMIN_USER="${ADMIN_USER:-editor}";  ADMIN_PASS="${ADMIN_PASS:-editor}"
EDITOR_USER="${EDITOR_USER:-demo}";  EDITOR_PASS="${EDITOR_PASS:-demo}"
VIEWER_USER="${VIEWER_USER:-viewer}"; VIEWER_PASS="${VIEWER_PASS:-viewer}"
# The supervised pair. sup-demo is NEVER minted (TOTP) and NEVER bound to a team — her whole reach
# is derived from the reporting edge to pm-demo, which is the point of the story.
SUPERVISOR_USER="${SUPERVISOR_USER:-sup-demo}"
REPORT_USER="${REPORT_USER:-pm-demo}"; REPORT_PASS="${REPORT_PASS:-pm-demo}"

# --- preflight ---------------------------------------------------------------
RUNTIME=""
for c in docker podman; do command -v "$c" >/dev/null 2>&1 && { RUNTIME="$c"; break; }; done
[ -n "$RUNTIME" ] || { echo "ERROR: need docker or podman to mint in-network tokens." >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "ERROR: python3 required (for JSON field extraction)." >&2; exit 1; }

# --- helpers -----------------------------------------------------------------
mint_token() { # user pass -> access_token
  local user="$1" pass="$2" json
  json="$("$RUNTIME" run --rm --network "$NETWORK" curlimages/curl -s \
    -X POST "$KEYCLOAK_TOKEN_URL" \
    -d grant_type=password -d "client_id=$CLIENT_ID" -d "client_secret=$CLIENT_SECRET" \
    -d "username=$user" -d "password=$pass" || true)"
  printf '%s' "$json" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p'
}

token_sub() { # jwt -> sub claim
  local tok="$1" payload
  payload="$(printf '%s' "$tok" | cut -d. -f2 | tr '_-' '/+')"
  while [ $(( ${#payload} % 4 )) -ne 0 ]; do payload="${payload}="; done
  printf '%s' "$payload" | base64 -d 2>/dev/null | sed -n 's/.*"sub":"\([^"]*\)".*/\1/p'
}

post_json() { curl -s -X POST "$1" -H 'Content-Type: application/json' -d "$2"; }
json_field() { python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('$1',''))" 2>/dev/null; }

create_category() { # token catalog body -> id
  curl -s -X POST "$GATEWAY/api/v1/catalogs/$2/categories" \
    -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -d "$3" | json_field id
}

create_product() { # token catalog category body -> id
  curl -s -X POST "$GATEWAY/api/v1/catalogs/$2/categories/$3/products" \
    -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -d "$4" | json_field id
}

# --- mint tokens + subjects --------------------------------------------------
echo "==> Minting demo tokens in-network ($NETWORK) ..."
ADMIN_TOKEN="$(mint_token "$ADMIN_USER" "$ADMIN_PASS")"
EDITOR_TOKEN="$(mint_token "$EDITOR_USER" "$EDITOR_PASS")"
VIEWER_TOKEN="$(mint_token "$VIEWER_USER" "$VIEWER_PASS")"
for pair in "admin($ADMIN_USER):$ADMIN_TOKEN" "editor($EDITOR_USER):$EDITOR_TOKEN" "viewer($VIEWER_USER):$VIEWER_TOKEN"; do
  [ -n "${pair#*:}" ] || { echo "ERROR: no token for ${pair%%:*}. Is the rig up (ENABLE_SPA=1) with the user present?" >&2; exit 1; }
done
ADMIN_SUB="$(token_sub "$ADMIN_TOKEN")"
EDITOR_SUB="$(token_sub "$EDITOR_TOKEN")"
VIEWER_SUB="$(token_sub "$VIEWER_TOKEN")"
echo "  subjects: admin=$ADMIN_SUB editor=$EDITOR_SUB viewer=$VIEWER_SUB"

# --- seed the demo catalog (resource side) -----------------------------------
echo "==> Seeding demo catalog $DEMO_CATALOG_ID ..."
"$RUNTIME" exec -i "$PG_CONTAINER" psql -U catalog -d catalog -v ON_ERROR_STOP=1 >/dev/null <<SQL
CREATE EXTENSION IF NOT EXISTS ltree;
DELETE FROM category WHERE catalog_id = '$DEMO_CATALOG_ID';
INSERT INTO catalog (id, name, description, created_at, version, tags, path)
VALUES ('$DEMO_CATALOG_ID', 'Demo catalog', 'The SPA demo catalog — try switching identity to see the affordances change.',
        now(), 0, '{}'::jsonb, CAST('catalog_' || replace('$DEMO_CATALOG_ID','-','') AS ltree))
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description, path = EXCLUDED.path;
SQL

# --- bootstrap the team + roles + memberships (subject side) ------------------
echo "==> Bootstrapping the demo team, roles, and memberships ..."
# Display names mirror the Keycloak logins — the roster then agrees with who you signed in as
# AND with the picker path (KeycloakUserDirectory maps username -> displayName the same way).
ADMIN_UID="$(post_json "$USER_SERVICE/internal/bootstrap/users" "{\"subject\":\"$ADMIN_SUB\",\"displayName\":\"$ADMIN_USER\"}" | json_field userId)"
EDITOR_UID="$(post_json "$USER_SERVICE/internal/bootstrap/users" "{\"subject\":\"$EDITOR_SUB\",\"displayName\":\"$EDITOR_USER\"}" | json_field userId)"
VIEWER_UID="$(post_json "$USER_SERVICE/internal/bootstrap/users" "{\"subject\":\"$VIEWER_SUB\",\"displayName\":\"$VIEWER_USER\"}" | json_field userId)"

TEAM_ID="$(post_json "$USER_SERVICE/internal/bootstrap/teams" "{\"name\":\"Demo team\",\"targetType\":\"catalog\",\"targetId\":\"$DEMO_CATALOG_ID\"}" | json_field teamId)"
[ -n "$TEAM_ID" ] || { echo "ERROR: failed to bootstrap the demo team." >&2; exit 1; }

# Three roles with clearly different capability so the SPA's affordances visibly differ per identity.
post_json "$USER_SERVICE/internal/bootstrap/custom-roles" \
  "{\"teamId\":\"$TEAM_ID\",\"code\":\"demo-admin\",\"roleLevel\":30,\"permissions\":{\"catalog\":[\"READ\",\"WRITE\",\"TAG\",\"GRANT\"],\"category\":[\"READ\",\"WRITE\",\"TAG\",\"GRANT\"],\"product\":[\"READ\",\"WRITE\",\"TAG\"]}}" >/dev/null
post_json "$USER_SERVICE/internal/bootstrap/custom-roles" \
  "{\"teamId\":\"$TEAM_ID\",\"code\":\"demo-editor\",\"roleLevel\":20,\"permissions\":{\"catalog\":[\"READ\",\"WRITE\",\"TAG\"],\"category\":[\"READ\",\"WRITE\",\"TAG\"],\"product\":[\"READ\",\"WRITE\",\"TAG\"]}}" >/dev/null
post_json "$USER_SERVICE/internal/bootstrap/custom-roles" \
  "{\"teamId\":\"$TEAM_ID\",\"code\":\"demo-viewer\",\"roleLevel\":10,\"permissions\":{\"catalog\":[\"READ\"],\"category\":[\"READ\"],\"product\":[\"READ\"]}}" >/dev/null

# The admin persona is the team OWNER (a system-ladder code): the control plane (team:add-member /
# change-role / remove-member / define-roles / transfer) is carried ONLY by system codes — custom
# roles are management-incapable by design (ADR 0015 §5). Binding a custom role here (the original
# demo-admin) left the flagship team with NO member able to manage it, and the member picker's
# add-member always answered 403 (found during the USER-DIRECTORY-PORT browser verify — see
# docs/to-do/implemented/USER-DIRECTORY-PORT/STATUS-06.md).
post_json "$USER_SERVICE/internal/bootstrap/memberships" "{\"teamId\":\"$TEAM_ID\",\"userId\":\"$ADMIN_UID\",\"roleCode\":\"owner\"}" >/dev/null
post_json "$USER_SERVICE/internal/bootstrap/memberships" "{\"teamId\":\"$TEAM_ID\",\"userId\":\"$EDITOR_UID\",\"roleCode\":\"demo-editor\"}" >/dev/null
post_json "$USER_SERVICE/internal/bootstrap/memberships" "{\"teamId\":\"$TEAM_ID\",\"userId\":\"$VIEWER_UID\",\"roleCode\":\"demo-viewer\"}" >/dev/null
# NOTE: 'outsider' is deliberately NOT bound — it demonstrates the no-team-role / no-access case.

# Reset the demo roster to the canonical three. Without this, stale-subject memberships accumulate
# across Keycloak re-imports (every realm re-create mints new subs, orphaning the old rows) and
# demo-added members persist — and the seed promises a REPRODUCIBLE baseline. Scoped to this team
# only; the users' profile rows are left alone (other tenants may reference them).
"$RUNTIME" exec -i "${UM_PG_CONTAINER:-opa-abac-usermgmt-postgres}" psql -U usermgmt -d usermgmt -v ON_ERROR_STOP=1 >/dev/null <<SQL
DELETE FROM team_membership
 WHERE team_id = '$TEAM_ID'
   AND user_id NOT IN ('$ADMIN_UID', '$EDITOR_UID', '$VIEWER_UID');
SQL
echo "  team $TEAM_ID governs $DEMO_CATALOG_ID; bound editor->owner, demo->demo-editor, viewer->demo-viewer (roster reset to the canonical three)."

# --- create a small category tree through the gateway (as the admin) ----------
echo "==> Creating demo categories through the gateway ..."
EMEA_ID="$(create_category "$ADMIN_TOKEN" "$DEMO_CATALOG_ID" '{"name":"EMEA region","tags":{"region":["emea"]}}')"
APAC_ID="$(create_category "$ADMIN_TOKEN" "$DEMO_CATALOG_ID" '{"name":"APAC region","tags":{"region":["apac"]}}')"
for pair in "EMEA:$EMEA_ID" "APAC:$APAC_ID"; do
  id="${pair#*:}"
  [ -n "$id" ] && [ "$id" != "None" ] || { echo "ERROR: failed to create the ${pair%%:*} category (admin write denied? check the role)." >&2; exit 1; }
done
echo "  categories: EMEA=$EMEA_ID APAC=$APAC_ID"

# A couple of products under EMEA so the products drill-in level isn't empty.
echo "==> Creating demo products through the gateway ..."
W1="$(create_product "$ADMIN_TOKEN" "$DEMO_CATALOG_ID" "$EMEA_ID" '{"name":"Demo widget","sku":"SKU-001","priceCents":1999,"currency":"USD"}')"
W2="$(create_product "$ADMIN_TOKEN" "$DEMO_CATALOG_ID" "$EMEA_ID" '{"name":"Demo gadget","sku":"SKU-002","priceCents":4950,"currency":"USD"}')"
echo "  products under EMEA: $W1 $W2"

# --- the supervised world (the SPA's step-up story) --------------------------
# sup-demo supervises pm-demo. She is bound to NO team: her two rows come entirely from the
# reporting edge (ADR 0029's derived scope). One of the two catalogs is tagged env=production, so
# reading its CONTENTS as a supervisor demands a fresh second factor (ADR 0030/0032) — that is the
# challenge the console now consumes. A MEMBER of the same catalog (pm-demo) needs no elevation.
echo ""
echo "==> Seeding the supervised demo world (sup-demo -> pm-demo) ..."

# pm-demo is minted normally (password only). If this fails, the realm predates the persona.
REPORT_TOKEN="$(mint_token "$REPORT_USER" "$REPORT_PASS")"
[ -n "$REPORT_TOKEN" ] || {
  echo "ERROR: could not mint a token for '$REPORT_USER'." >&2
  echo "  The running realm predates the demo supervisor personas. Re-import it:" >&2
  echo "    ./deploy.sh down && ./profile.sh up && ENABLE_SPA=1 ENABLE_MCP=1 ./deploy.sh up --pods 2" >&2
  echo "  (a 'down' stops base Postgres, so profile.sh must run before the next up)" >&2
  exit 1; }
REPORT_SUB="$(token_sub "$REPORT_TOKEN")"

# sup-demo's subject comes from the Keycloak admin API, never from a mint: she carries a seeded
# TOTP, and Keycloak's direct-grant flow demands an OTP from any identity that has one, so a
# password-only ROPC for her answers invalid_grant. The catalog-directory service account already
# holds view-users and nothing else (run-team-matrix.sh pins that posture).
DIR_TOKEN="$("$RUNTIME" run --rm --network "$NETWORK" curlimages/curl -s \
  -X POST "$KEYCLOAK_TOKEN_URL" \
  -d grant_type=client_credentials -d "client_id=$DIRECTORY_CLIENT_ID" \
  -d "client_secret=$DIRECTORY_CLIENT_SECRET" \
  | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')"
[ -n "$DIR_TOKEN" ] || { echo "ERROR: could not mint the $DIRECTORY_CLIENT_ID service token (stale realm?)." >&2; exit 1; }
SUPERVISOR_SUB="$("$RUNTIME" run --rm --network "$NETWORK" curlimages/curl -s \
  -H "Authorization: Bearer $DIR_TOKEN" \
  "$KEYCLOAK_ADMIN_BASE/users?username=$SUPERVISOR_USER&exact=true&max=2" \
  | sed -n 's/.*"id":"\([^"]*\)".*/\1/p' | head -n1)"
[ -n "$SUPERVISOR_SUB" ] || {
  echo "ERROR: '$SUPERVISOR_USER' is not in the realm — re-import it (see the $REPORT_USER hint above)." >&2
  exit 1; }
echo "  subjects: $SUPERVISOR_USER=$SUPERVISOR_SUB (admin-API lookup) $REPORT_USER=$REPORT_SUB (minted)"

# --- the two catalogs (psql upsert; the ltree path is what lets children be created) ---
seed_supervised_catalog() { # id name description
  "$RUNTIME" exec -i "$PG_CONTAINER" psql -U catalog -d catalog -v ON_ERROR_STOP=1 >/dev/null <<SQL
INSERT INTO catalog (id, name, description, created_at, version, tags, path)
VALUES ('$1', '$2', '$3', now(), 0, '{}'::jsonb,
        CAST('catalog_' || replace('$1','-','') AS ltree))
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description, path = EXCLUDED.path;
SQL
}
# The tags column is deliberately NOT reset here: env=production is written below through the
# operator endpoint, and a re-run must not silently untag the production catalog between the
# upsert and the tag write.
seed_supervised_catalog "$DEMO_PROD_CATALOG_ID" 'Demo Production Catalog' \
  'Production tier — a supervisor must verify a second factor to read its contents.'
seed_supervised_catalog "$DEMO_OPEN_CATALOG_ID" 'Demo Open Catalog' \
  'No tier — a supervisor reads its contents with no ceremony.'

# --- the identities, the teams, the edge -------------------------------------
SUPERVISOR_UID="$(post_json "$USER_SERVICE/internal/bootstrap/users" "{\"subject\":\"$SUPERVISOR_SUB\",\"displayName\":\"$SUPERVISOR_USER\"}" | json_field userId)"
REPORT_UID="$(post_json "$USER_SERVICE/internal/bootstrap/users" "{\"subject\":\"$REPORT_SUB\",\"displayName\":\"$REPORT_USER\"}" | json_field userId)"
[ -n "$SUPERVISOR_UID" ] && [ -n "$REPORT_UID" ] || { echo "ERROR: failed to bootstrap the supervised personas." >&2; exit 1; }

PROD_TEAM_ID="$(post_json "$USER_SERVICE/internal/bootstrap/teams" "{\"name\":\"Demo Production Team\",\"targetType\":\"catalog\",\"targetId\":\"$DEMO_PROD_CATALOG_ID\"}" | json_field teamId)"
OPEN_TEAM_ID="$(post_json "$USER_SERVICE/internal/bootstrap/teams" "{\"name\":\"Demo Open Team\",\"targetType\":\"catalog\",\"targetId\":\"$DEMO_OPEN_CATALOG_ID\"}" | json_field teamId)"
[ -n "$PROD_TEAM_ID" ] && [ -n "$OPEN_TEAM_ID" ] || { echo "ERROR: failed to bootstrap the Demo * Teams." >&2; exit 1; }

# `owner` is CONTROL-capable, so pm-demo's seats are what propagate to his manager (ADR 0029).
post_json "$USER_SERVICE/internal/bootstrap/memberships" "{\"teamId\":\"$PROD_TEAM_ID\",\"userId\":\"$REPORT_UID\",\"roleCode\":\"owner\"}" >/dev/null
post_json "$USER_SERVICE/internal/bootstrap/memberships" "{\"teamId\":\"$OPEN_TEAM_ID\",\"userId\":\"$REPORT_UID\",\"roleCode\":\"owner\"}" >/dev/null
# REPLACE, not upsert — safe because this seed is the only writer for sup-demo's edges (the matrix
# family is sup-anna/sup-victor/sup-noreports, and no matrix may touch sup-demo: see the registry).
post_json "$USER_SERVICE/internal/bootstrap/reporting-edges" \
  "{\"managerId\":\"$SUPERVISOR_UID\",\"reportIds\":[\"$REPORT_UID\"]}" >/dev/null

# Roster reset for the two demo teams, the Demo-team idiom: keep exactly pm-demo. This is also what
# keeps sup-demo UNBOUND if someone binds her from the SPA — her page must stay purely derived.
"$RUNTIME" exec -i "${UM_PG_CONTAINER:-opa-abac-usermgmt-postgres}" psql -U usermgmt -d usermgmt -v ON_ERROR_STOP=1 >/dev/null <<SQL
DELETE FROM team_membership
 WHERE team_id IN ('$PROD_TEAM_ID', '$OPEN_TEAM_ID')
   AND user_id <> '$REPORT_UID';
SQL
echo "  $REPORT_USER owns both Demo * Teams; $SUPERVISOR_USER -> {$REPORT_USER}, bound to nothing"

# --- the contents (through the gateway, as the owner, so the ltree paths are real) ---
# Find-or-create by name, NOT delete-and-recreate: a re-run must leave the ids identical (the demo
# is a stable world a reader can bookmark, and the pane cells name rows by what they see).
find_named() { # token listUrl name -> id ('' when absent)
  local body
  body="$(curl -s -H "Authorization: Bearer $1" "$2")"
  printf '%s' "$body" | NAME="$3" python3 -c \
    "import sys,json,os; print(next((i['id'] for i in json.load(sys.stdin).get('items',[]) if i.get('name')==os.environ['NAME']), ''))" 2>/dev/null || true
}
ensure_contents() { # token catalogId label -> "<categoryId> <productId>"
  local token="$1" catalog="$2" label="$3" cat_id prod_id
  cat_id="$(find_named "$token" "$GATEWAY/api/v1/catalogs/$catalog/categories" "Demo $label Category")"
  [ -n "$cat_id" ] || cat_id="$(create_category "$token" "$catalog" "{\"name\":\"Demo $label Category\"}")"
  [ -n "$cat_id" ] && [ "$cat_id" != "None" ] || {
    echo "ERROR: could not create the Demo $label Category (is $REPORT_USER still the team owner?)." >&2; exit 1; }
  prod_id="$(find_named "$token" "$GATEWAY/api/v1/catalogs/$catalog/categories/$cat_id/products" "Demo $label Product")"
  [ -n "$prod_id" ] || prod_id="$(create_product "$token" "$catalog" "$cat_id" \
    "{\"name\":\"Demo $label Product\",\"sku\":\"DEMO-$label-1\",\"priceCents\":3400,\"currency\":\"USD\"}")"
  [ -n "$prod_id" ] && [ "$prod_id" != "None" ] || {
    echo "ERROR: could not create the Demo $label Product." >&2; exit 1; }
  printf '%s %s' "$cat_id" "$prod_id"
}
echo "==> Creating a category + product under each supervised catalog (as $REPORT_USER) ..."
# Assignment-then-read, never `read <<<"$(fn)"`: the herestring form swallows the function's exit 1.
pair="$(ensure_contents "$REPORT_TOKEN" "$DEMO_PROD_CATALOG_ID" Production)" || exit 1
[ -n "$pair" ] || { echo "ERROR: empty Production contents result" >&2; exit 1; }
read -r PROD_CATEGORY_ID PROD_PRODUCT_ID <<<"$pair"
pair="$(ensure_contents "$REPORT_TOKEN" "$DEMO_OPEN_CATALOG_ID" Open)" || exit 1
[ -n "$pair" ] || { echo "ERROR: empty Open contents result" >&2; exit 1; }
read -r OPEN_CATEGORY_ID OPEN_PRODUCT_ID <<<"$pair"
echo "  production: category=$PROD_CATEGORY_ID product=$PROD_PRODUCT_ID"
echo "  open:       category=$OPEN_CATEGORY_ID product=$OPEN_PRODUCT_ID"

# --- the OPERATOR sets the tier (the only write path there is) ---------------
# On the catalog service's published port: the gateway 404s every /internal/* path at the edge.
# The open catalog is left UNTAGGED on purpose — that is the no-ceremony half of the story.
echo "==> Operator: tagging $DEMO_PROD_CATALOG_ID env=production ..."
tag_response="$(post_json "$CATALOG_SERVICE/internal/bootstrap/resource-tags" \
  "{\"resourceType\":\"catalog\",\"resourceId\":\"$DEMO_PROD_CATALOG_ID\",\"tags\":{\"env\":\"production\"}}")"
printf '%s' "$tag_response" | grep -q '"env":"production"' || {
  echo "ERROR: the operator tier write did not take. The service answered:" >&2
  echo "  $tag_response" >&2
  exit 1; }
echo "  $DEMO_PROD_CATALOG_ID is env=production; $DEMO_OPEN_CATALOG_ID left untagged"

echo ""
echo "==> Demo data seeded. Open the SPA (http://localhost:3000 in dev) and sign in as:"
echo "      editor / editor    -> team OWNER   (every action incl. team management: add/change/remove members, roles, transfer)"
echo "      demo   / demo      -> demo-editor  (edit + tag, no grant, no team management)"
echo "      viewer / viewer    -> demo-viewer  (read-only; mutating actions shown false)"
echo "      outsider / outsider-> no team role (the no-access case)"
echo "    The 'Demo catalog' card's _actions change with the signed-in identity."
echo ""
echo "    The supervised world (the step-up story):"
echo "      pm-demo  / pm-demo  -> MEMBER (owner) of both Demo * Teams: reads production with no ceremony"
echo "      sup-demo / sup-demo -> SUPERVISOR of pm-demo, bound to NO team: sees both catalogs derived,"
echo "                             opens 'Demo Open Catalog' freely, and must verify a second factor to"
echo "                             read 'Demo Production Catalog' contents (TOTP secret: spachallengedemo1234;"
echo "                             a code: scripts/postman/mint-code-flow-token.py --print-otp --otp-secret spachallengedemo1234)"
