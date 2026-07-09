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
# Run AFTER:  ENABLE_SPA=1 ./deploy.sh up --pods 2     (brings up the user-service + enrichment)
# Then:       scripts/postman/seed-demo-data.sh
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

# The demo catalog the SPA highlights (stable id so re-seeding is deterministic). 8-4-4-4-12 hex.
DEMO_CATALOG_ID="${DEMO_CATALOG_ID:-d3110000-0000-0000-0000-000000000001}"
# Realm users -> demo roles.
ADMIN_USER="${ADMIN_USER:-editor}";  ADMIN_PASS="${ADMIN_PASS:-editor}"
EDITOR_USER="${EDITOR_USER:-demo}";  EDITOR_PASS="${EDITOR_PASS:-demo}"
VIEWER_USER="${VIEWER_USER:-viewer}"; VIEWER_PASS="${VIEWER_PASS:-viewer}"

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

echo ""
echo "==> Demo data seeded. Open the SPA (http://localhost:3000 in dev) and sign in as:"
echo "      editor / editor    -> team OWNER   (every action incl. team management: add/change/remove members, roles, transfer)"
echo "      demo   / demo      -> demo-editor  (edit + tag, no grant, no team management)"
echo "      viewer / viewer    -> demo-viewer  (read-only; mutating actions shown false)"
echo "      outsider / outsider-> no team role (the no-access case)"
echo "    The 'Demo catalog' card's _actions change with the signed-in identity."
