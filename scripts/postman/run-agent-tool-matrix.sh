#!/usr/bin/env bash
#
# Run the agent tool-call authorization matrix (agent-tool-matrix.postman_collection.json) through
# the local rig — the live proof of Phase 9 (AGENT-TOOL-AUTHZ, ADR 0028).
#
# What it proves (10-QA-TEST-CASES E1-E11, plus E12): an MCP tool surface in front of the catalog service
# authorizes agent calls at TWO independent layers and propagates NOTHING between them. The tool-gate
# (this server, agent_tools.rego) intersects the principal's ceiling with the acting agent's declared
# capability; the target-gate (the catalog service's own, unchanged per-type policies) then decides the
# resource with the caller's own bearer. The decisive cells:
#   E1/E10  a HUMAN token (no actor claim) sees the same catalogs through MCP as over REST, and its
#           tools/list is the ceiling-only cut — the tool surface adds no access and removes none
#   E2/E3   the SAME principal behind an agent capped below get_product's risk tier: tools/list is
#           exactly [get_catalog, list_catalogs] by name, both callable, and get_product DENIES at
#           tool-gate although the principal is permitted it — the narrowing, on one token
#   E4      an agent whose tool-gate ALLOWS get_catalog, on a catalog its principal may not reach ->
#           denied at target-gate: the two layers are distinguishable in the error
#   E5      the headline — the same agent + tool + target replayed for a LOW-PRIVILEGE principal is
#           denied, and the deliberately over-wide `agent-overreach` capability still gets exactly the
#           human's cut. Capability narrows; it never grants
#   E6      mid-run PDP kill: every call denies as `tool-gate-policy-unavailable` ("could not decide",
#           not "the policy said no" — ADR 0037) and the roster goes EMPTY (the honest pair — during the
#           outage nothing is callable, so an unfiltered list would advertise four unusable tools); zero
#           widening; restarting OPA restores the pre-kill vector exactly
#   E7      agent-gate OFF: the tool-gate stops narrowing and the catalog's target-gate still denies —
#           OFF is not wider than ON, proven on the rig rather than argued
#   E8      every deny is a structured tool error naming its layer and a stable code
#   E11     revocation: the actor's profile is emptied and the pod restarted -> the tool leaves the
#           roster AND denies at call time. E11-b adds the other shape in the same pass: the
#           `agent-revoked` actor, DECLARED with zero capability from the start, so its empty roster is
#           an authoritative answer rather than the all-false vector a dead PDP produces
#   E12     the agent-clients guard (MCP-AUTH-LIBRARY): with catalog-gateway ADDED to the agent clients
#           (the default lists the four agent clients), the ordinary human token (that client has no actor
#           mapper) is refused `tool-gate-actor-required`, an actor-carrying agent token on the same pod
#           keeps its normal outcome, and on the default pod — catalog-gateway no longer listed — the human
#           token is a human call again (E12-restore)
#   E13     the RFC 9728 protected-resource documents through the gateway, with no token: both paths
#           answer the exact configured document, a forged Host / forwarding headers change nothing, and a
#           POST or a third well-known path falls to the catch-all's bearer 401 (GATEWAY-AUDIENCE-BINDING)
#   E14     the discovery login: the miner starts from nothing but the MCP URL, walks the documents to the
#           realm and mints as catalog-mcp-host — aud catalog-gateway, act_chain [agent-host], the gateway
#           origin as iss
#   E15     that host token on the tool surface: the roster is exactly the three low-risk reads, and
#           get_product — LISTED in its profile — is denied at the tool-gate by the risk cap alone, while
#           the same token reads the same product over REST (200)
#   E16     the host's refresh token rotates: a refresh answers a NEW refresh token, and replaying the
#           first is refused `invalid_grant` "Maximum allowed refresh token reuse exceeded"
#   E17     the transport's own refusals — an Accept without text/event-stream (400), an unknown session
#           (404) — answer the JSON-RPC error and its message, never the exception's stack trace; and E10's
#           roster cell reads every tool's read-only hints back (both found by the real-client login, QA E13)
# E9 (every pre-existing runner still green) is a suite-level acceptance, not a cell here.
#
# Prereq: the full rig is up WITH the MCP server (the flag force-enables OIDC + OPA + the user-service):
#   ENABLE_MCP=1 ./deploy.sh up --pods 2
#   (EXPORT the flag — `export ENABLE_MCP=1` — if another matrix will re-up the rig afterwards:
#   a flag-less `deploy.sh up` now positively tears the MCP route + container down, by design.)
#
# FOUR CELLS ARE RIG DRILLS, NOT COLLECTION STEPS, and this runner orchestrates them: it stops and
# restarts the OPA container around the E6 folders, recreates the MCP pod with
# OPA_ABAC_MCP_AGENT_GATE_ENABLED=false around E7, recreates it with the readonly actor's
# capability emptied around E11, and recreates it with catalog-gateway ADDED to
# OPA_ABAC_MCP_IDENTITY_AGENT_CLIENTS around E12, then on its default profile for E12-restore (and for
# E13–E16, which need the shipped pod). An EXIT trap restores the rig (OPA up, MCP pod on its default
# profile) however the run ends — a failed drill never leaves the rig switched off.
#
# Fixture set (registered in scripts/postman/README.md — dedicated, no shared fixtures touched):
#   bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb  the granted catalog (demo is a member; category + product)
#   bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbc  the FOREIGN catalog — its own team, demo is NOT a member
#                                          (E4's target, and E7's product). Seeded by `editor`, who
#                                          holds the only membership on it.
#
# Subjects: demo (the principal behind every agent cell) and outsider (the low-privilege principal for
# E5 — no membership, so an empty ceiling). The ACTOR comes from WHICH CLIENT minted the token: the
# realm's catalog-agent-{readonly,overreach,revoked} clients each carry a hardcoded-claim mapper that
# mints act_chain with that client's actor id, so the same human can also obtain an ordinary no-actor
# token through catalog-gateway (E1/E10 need exactly that).
#
# Honors the in-network token caveat: the ROPC mints address keycloak:8888, one of the three issuers the
# gateway admits; E14's discovery login mints at the gateway origin, another.

set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SELF_DIR/../.." && pwd)"
cd "$SELF_DIR"

# --- config (override via env) -----------------------------------------------
COLLECTION="${COLLECTION:-agent-tool-matrix.postman_collection.json}"
ENV_FILE="${ENV_FILE:-local.postman_environment.json}"
NETWORK="${COMPOSE_NETWORK:-opa-abac-example_default}"
KEYCLOAK_TOKEN_URL="${KEYCLOAK_TOKEN_URL:-http://keycloak:8888/realms/catalog-demo/protocol/openid-connect/token}"
CLIENT_ID="${CLIENT_ID:-catalog-gateway}"
CLIENT_SECRET="${CLIENT_SECRET:-catalog-gateway-secret}"
USER_SERVICE="${USER_SERVICE:-http://localhost:28090}"
PG_CONTAINER="${PG_CONTAINER:-opa-abac-postgres}"
UM_PG_CONTAINER="${UM_PG_CONTAINER:-opa-abac-usermgmt-postgres}"
OPA_CONTAINER="${OPA_CONTAINER:-opa-abac-opa}"
COMPOSE_PROJECT="${COMPOSE_PROJECT:-opa-abac-example}"
MCP_COMPOSE="${MCP_COMPOSE:-$REPO_ROOT/infra/compose.mcp.yaml}"
MCP_HEALTH="${MCP_HEALTH:-http://localhost:28093/actuator/health}"
OPA_URL="${OPA_URL:-http://localhost:28181}"
OPA_HEALTH="${OPA_HEALTH:-$OPA_URL/health}"
GATEWAY="${GATEWAY:-http://localhost:9085}"
# The ref the "as shipped" advisory compares against; override on a fork without origin/main.
SHIPPED_BASE="${SHIPPED_BASE:-origin/main}"
REPORT_DIR="${REPORT_DIR:-build/reports/postman}"
RUN_ID="${E2E_RUN_ID:-agent-$$}"

# The principal behind every agent cell, and the low-privilege principal E5 replays for.
DEMO_USER="${DEMO_USER:-demo}";         DEMO_PASS="${DEMO_PASS:-demo}"
LOWPRIV_USER="${LOWPRIV_USER:-outsider}"; LOWPRIV_PASS="${LOWPRIV_PASS:-outsider}"
# The identity that seeds the FOREIGN catalog (the only member of its team).
SEEDER_USER="${SEEDER_USER:-editor}";   SEEDER_PASS="${SEEDER_PASS:-editor}"

# The agent clients the shipped pod lists (infra/compose.mcp.yaml's MCP_AGENT_CLIENTS default, mirrored
# from application.yml). E12 EXTENDS this list; it never replaces it.
MCP_DEFAULT_AGENT_CLIENTS="${MCP_DEFAULT_AGENT_CLIENTS:-catalog-agent-readonly,catalog-agent-overreach,catalog-agent-revoked,catalog-mcp-host}"
# The MCP host's login (E14–E16): the miner in discovery mode, as the public agent client.
MINER="${MINER:-$SELF_DIR/mint-code-flow-token.py}"
HOST_CLIENT="${HOST_CLIENT:-catalog-mcp-host}"
HOST_REDIRECT="${HOST_REDIRECT:-http://localhost:33418/callback}"

AGENT_CATALOG_ID="${AGENT_CATALOG_ID:-bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb}"
FOREIGN_CATALOG_ID="${FOREIGN_CATALOG_ID:-bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbc}"

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

curl -sf "$MCP_HEALTH" 2>/dev/null | grep -q '"status":"UP"' || {
  echo "ERROR: the MCP server is not up on $MCP_HEALTH." >&2
  echo "  Bring the rig up with: ENABLE_MCP=1 ./deploy.sh up --pods 2" >&2
  exit 1
}

# --- rig state restore (however the run ends) ---------------------------------
# The drills switch parts of the rig OFF. Without this, a failing drill would leave the PDP stopped or
# the agent gate disabled, and the NEXT run — or the demo UI — would silently behave differently.
mcp_compose() { "$RUNTIME" compose -p "$COMPOSE_PROJECT" -f "$MCP_COMPOSE" "$@"; }

wait_opa() {
  for _ in $(seq 1 30); do
    curl -sf "$OPA_HEALTH" >/dev/null 2>&1 && return 0
    sleep 1
  done
  echo "ERROR: OPA did not become healthy ($OPA_HEALTH)." >&2
  return 1
}

wait_mcp() { # label — a recreated pod must be UP before the folder that depends on the new switch
  for _ in $(seq 1 60); do
    curl -sf "$MCP_HEALTH" 2>/dev/null | grep -q '"status":"UP"' && return 0
    sleep 2
  done
  echo "ERROR: the MCP server did not become healthy after $1 ($MCP_HEALTH)." >&2
  return 1
}

restore_rig() {
  local status=$?
  echo "==> Restoring the rig (OPA up, MCP pod on its default profile) ..."
  "$RUNTIME" start "$OPA_CONTAINER" >/dev/null 2>&1 || true
  mcp_compose up -d --force-recreate mcp >/dev/null 2>&1 || true
  wait_opa || true
  wait_mcp "the restore" || true
  return $status
}
trap restore_rig EXIT

# --- helpers -----------------------------------------------------------------
mint_token() { # username password [client_id] [client_secret]
  local user="$1" pass="$2" cid="${3:-$CLIENT_ID}" secret="${4:-$CLIENT_SECRET}" json
  json="$("$RUNTIME" run --rm --network "$NETWORK" curlimages/curl -s \
    -X POST "$KEYCLOAK_TOKEN_URL" \
    -d grant_type=password -d "client_id=$cid" -d "client_secret=$secret" \
    -d "username=$user" -d "password=$pass" || true)"
  printf '%s' "$json" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p'
}

token_payload() { # jwt -> decoded payload JSON
  local tok="$1" payload
  payload="$(printf '%s' "$tok" | cut -d. -f2 | tr '_-' '/+')"
  while [ $(( ${#payload} % 4 )) -ne 0 ]; do payload="${payload}="; done
  printf '%s' "$payload" | base64 -d 2>/dev/null
}
token_sub() { token_payload "$1" | sed -n 's/.*"sub":"\([^"]*\)".*/\1/p'; }

post_json() { curl -s -X POST "$1" -H 'Content-Type: application/json' -d "$2"; }
json_field() { python3 -c "import sys,json; print(json.load(sys.stdin)['$1'])"; }
create_category() { # token catalog_id body -> id
  curl -s -X POST "$GATEWAY/api/v1/catalogs/$2/categories" \
    -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -d "$3" | json_field id
}
create_product() { # token catalog_id category_id body -> id
  curl -s -X POST "$GATEWAY/api/v1/catalogs/$2/categories/$3/products" \
    -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -d "$4" | json_field id
}

run_folder() { # folder-name report-suffix
  newman run "$COLLECTION" \
    -e "$ENV_FILE" \
    --folder "$1" \
    --env-var "gateway=$GATEWAY" \
    --env-var "mcp_catalog_id=$AGENT_CATALOG_ID" \
    --env-var "mcp_category_id=$CATEGORY_ID" \
    --env-var "mcp_product_id=$PRODUCT_ID" \
    --env-var "foreign_catalog_id=$FOREIGN_CATALOG_ID" \
    --env-var "foreign_category_id=$FOREIGN_CATEGORY_ID" \
    --env-var "foreign_product_id=$FOREIGN_PRODUCT_ID" \
    --env-var "human_token=$HUMAN_TOKEN" \
    --env-var "readonly_token=$READONLY_TOKEN" \
    --env-var "overreach_token=$OVERREACH_TOKEN" \
    --env-var "lowpriv_token=$LOWPRIV_TOKEN" \
    --env-var "revoked_token=$REVOKED_TOKEN" \
    --env-var "host_token=$HOST_TOKEN" \
    --reporters cli,json \
    --reporter-json-export "$REPORT_DIR/$RUN_ID/agent-tool-matrix-$2.json"
}

# --- reload the policies (agent_tools.rego's schema-v1 guard is what every cell runs against) ----
# OPA runs without --watch, so a rig brought up before agent_tools.rego last changed still decides with the
# old document — and E1-E5 would pass against it, since a policy without the guard ignores the jar's schema
# marker. Restart, then poll the guard ITSELF — proof that the loaded document is the v1-aware one, which a
# health check cannot give.
echo "==> Restarting OPA ($OPA_CONTAINER) so the current agent_tools.rego is live (--watch is not used) ..."
"$RUNTIME" restart "$OPA_CONTAINER" >/dev/null
OPA_GUARD_LIVE=0
for _ in $(seq 1 60); do
  if curl -sf --max-time 5 "$OPA_URL/v1/data/agent_tools/known_schemas" 2>/dev/null \
       | grep -qF '"opa-abac.mcp.tool-gate/v1"'; then
    OPA_GUARD_LIVE=1; break
  fi
  sleep 1
done
[ "$OPA_GUARD_LIVE" = 1 ] || {
  echo "ERROR: OPA restarted but agent_tools.known_schemas does not hold the v1 marker — the loaded policy is not the current agent_tools.rego." >&2
  exit 1
}

# --- mint tokens -------------------------------------------------------------
HOST_TOKEN=""   # minted by E14's discovery login, after the drills
# Four tokens, ONE human principal behind three of them. The actor rides in act_chain, minted by the
# client's protocol mapper — which is what lets E1/E10 use a no-actor token for the SAME person.
echo "==> Minting the human + three agent tokens in-network ($NETWORK) ..."
HUMAN_TOKEN="$(mint_token "$DEMO_USER" "$DEMO_PASS")"
READONLY_TOKEN="$(mint_token "$DEMO_USER" "$DEMO_PASS" catalog-agent-readonly catalog-agent-readonly-secret)"
OVERREACH_TOKEN="$(mint_token "$DEMO_USER" "$DEMO_PASS" catalog-agent-overreach catalog-agent-overreach-secret)"
LOWPRIV_TOKEN="$(mint_token "$LOWPRIV_USER" "$LOWPRIV_PASS" catalog-agent-readonly catalog-agent-readonly-secret)"
REVOKED_TOKEN="$(mint_token "$DEMO_USER" "$DEMO_PASS" catalog-agent-revoked catalog-agent-revoked-secret)"
SEEDER_TOKEN="$(mint_token "$SEEDER_USER" "$SEEDER_PASS")"
for pair in "human:$HUMAN_TOKEN" "agent-readonly:$READONLY_TOKEN" "agent-overreach:$OVERREACH_TOKEN" \
            "agent-readonly(low-priv):$LOWPRIV_TOKEN" "agent-revoked:$REVOKED_TOKEN" \
            "seeder:$SEEDER_TOKEN"; do
  name="${pair%%:*}"; tok="${pair#*:}"
  [ -n "$tok" ] || {
    echo "ERROR: no token for '$name'. Is the rig up with OIDC, and is the realm current?" >&2
    echo "  The agent clients arrived with this slice — recreate Keycloak to import them:" >&2
    echo "  docker compose -p $COMPOSE_PROJECT -f ../../infra/compose.keycloak.yaml up -d --force-recreate keycloak" >&2
    exit 1
  }
done

# The dual-identity preflight: the agent tokens must actually carry act_chain, and the human one must
# NOT. Without this, every agent cell would silently degrade into an ordinary human call and the whole
# matrix would pass while proving nothing.
token_payload "$READONLY_TOKEN" | grep -q '"act_chain"' || {
  echo "ERROR: the catalog-agent-readonly token carries no act_chain claim — stale realm import." >&2
  exit 1
}
token_payload "$HUMAN_TOKEN" | grep -q '"act_chain"' && {
  echo "ERROR: the catalog-gateway token carries an act_chain claim — E1/E10 need a no-actor token." >&2
  exit 1
}
echo "  act_chain present on the agent tokens, absent on the human one."

DEMO_SUB="$(token_sub "$HUMAN_TOKEN")"
SEEDER_SUB="$(token_sub "$SEEDER_TOKEN")"
LOWPRIV_SUB="$(token_sub "$LOWPRIV_TOKEN")"
echo "  subjects: demo=$DEMO_SUB seeder=$SEEDER_SUB low-privilege=$LOWPRIV_SUB"

# --- seed the two fixture catalogs -------------------------------------------
# Seeded WITH the ltree path (the run-pagination-matrix.sh model) — a direct-SQL catalog row without a
# path breaks category creation fail-closed ("parent has no path").
echo "==> Seeding fixture catalogs $AGENT_CATALOG_ID (granted) + $FOREIGN_CATALOG_ID (foreign) ..."
"$RUNTIME" exec -i "$PG_CONTAINER" psql -U catalog -d catalog -v ON_ERROR_STOP=1 >/dev/null <<SQL
CREATE EXTENSION IF NOT EXISTS ltree;
DELETE FROM category WHERE catalog_id IN ('$AGENT_CATALOG_ID', '$FOREIGN_CATALOG_ID');
INSERT INTO catalog (id, name, created_at, version, tags, path)
VALUES ('$AGENT_CATALOG_ID', 'Agent tool demo catalog', now(), 0, '{}'::jsonb,
        CAST('catalog_' || replace('$AGENT_CATALOG_ID','-','') AS ltree))
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, path = EXCLUDED.path;
INSERT INTO catalog (id, name, created_at, version, tags, path)
VALUES ('$FOREIGN_CATALOG_ID', 'Agent tool foreign catalog', now(), 0, '{}'::jsonb,
        CAST('catalog_' || replace('$FOREIGN_CATALOG_ID','-','') AS ltree))
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, path = EXCLUDED.path;
SQL

# --- bootstrap the teams + roles + memberships --------------------------------
# The principal's ceiling is TYPE-LEVEL and resolved from membership, so the granted role must carry
# catalog + category + product READ for the human roster (E10) to contain all four tools. Membership
# lives on the governing ROOT (ADR 0018) while the role carries the whole hierarchy's permissions —
# which is exactly why the tool-gate's ceiling enumerates the catalog scope, not the asked-for type.
echo "==> Bootstrapping the teams, roles and memberships ..."
DEMO_UID="$(post_json "$USER_SERVICE/internal/bootstrap/users" "{\"subject\":\"$DEMO_SUB\",\"displayName\":\"$DEMO_USER\"}" | json_field userId)"
SEEDER_UID="$(post_json "$USER_SERVICE/internal/bootstrap/users" "{\"subject\":\"$SEEDER_SUB\",\"displayName\":\"$SEEDER_USER\"}" | json_field userId)"

TEAM_ID="$(post_json "$USER_SERVICE/internal/bootstrap/teams" "{\"name\":\"Agent tool demo\",\"targetType\":\"catalog\",\"targetId\":\"$AGENT_CATALOG_ID\"}" | json_field teamId)"
post_json "$USER_SERVICE/internal/bootstrap/custom-roles" \
  "{\"teamId\":\"$TEAM_ID\",\"code\":\"at-catalog-steward\",\"roleLevel\":20,\"permissions\":{\"catalog\":[\"READ\",\"WRITE\",\"TAG\"],\"category\":[\"READ\",\"WRITE\",\"TAG\"],\"product\":[\"READ\",\"WRITE\",\"TAG\"]}}" >/dev/null
post_json "$USER_SERVICE/internal/bootstrap/memberships" "{\"teamId\":\"$TEAM_ID\",\"userId\":\"$DEMO_UID\",\"roleCode\":\"at-catalog-steward\"}" >/dev/null

# The FOREIGN catalog is its own governed island (Slice B4: membership is the sole access path). The
# seeder holds the only membership on it, so demo — the principal behind every agent cell — cannot
# reach it, and E4/E7 deny at the catalog service's own gate rather than at the tool-gate.
FOREIGN_TEAM_ID="$(post_json "$USER_SERVICE/internal/bootstrap/teams" "{\"name\":\"Agent tool foreign\",\"targetType\":\"catalog\",\"targetId\":\"$FOREIGN_CATALOG_ID\"}" | json_field teamId)"
post_json "$USER_SERVICE/internal/bootstrap/custom-roles" \
  "{\"teamId\":\"$FOREIGN_TEAM_ID\",\"code\":\"at-foreign-steward\",\"roleLevel\":20,\"permissions\":{\"catalog\":[\"READ\",\"WRITE\",\"TAG\"],\"category\":[\"READ\",\"WRITE\",\"TAG\"],\"product\":[\"READ\",\"WRITE\",\"TAG\"]}}" >/dev/null
post_json "$USER_SERVICE/internal/bootstrap/memberships" "{\"teamId\":\"$FOREIGN_TEAM_ID\",\"userId\":\"$SEEDER_UID\",\"roleCode\":\"at-foreign-steward\"}" >/dev/null
echo "  team $TEAM_ID governs $AGENT_CATALOG_ID (demo bound); team $FOREIGN_TEAM_ID governs $FOREIGN_CATALOG_ID (demo NOT bound)."

# E5's principal must have an EMPTY ceiling, or the low-privilege replay proves nothing. A stray
# membership from a demo click would silently turn the headline cell green for the wrong reason.
LOWPRIV_TEAMS="$("$RUNTIME" exec -i "$UM_PG_CONTAINER" psql -U usermgmt -d usermgmt -tAc \
  "SELECT count(*) FROM team_membership m JOIN app_user u ON u.id = m.user_id WHERE u.subject = '$LOWPRIV_SUB';" 2>/dev/null | tr -d '[:space:]')"
[ "${LOWPRIV_TEAMS:-0}" = "0" ] || {
  echo "ERROR: the low-privilege principal '$LOWPRIV_USER' holds $LOWPRIV_TEAMS membership(s)." >&2
  echo "  E5 needs an empty ceiling. Remove them (a demo click, or another matrix that kept fixtures)." >&2
  exit 1
}
echo "  low-privilege principal '$LOWPRIV_USER' holds no membership (an empty ceiling)."

# --- create the fixture trees through the gateway ------------------------------
echo "==> Creating the fixture categories/products through the gateway ..."
CATEGORY_ID="$(create_category "$HUMAN_TOKEN" "$AGENT_CATALOG_ID" '{"name":"Agent tool demo category"}')"
PRODUCT_ID="$(create_product "$HUMAN_TOKEN" "$AGENT_CATALOG_ID" "$CATEGORY_ID" '{"name":"Agent tool widget","sku":"AT-DEMO","priceCents":2500,"currency":"USD"}')"
FOREIGN_CATEGORY_ID="$(create_category "$SEEDER_TOKEN" "$FOREIGN_CATALOG_ID" '{"name":"Foreign category"}')"
FOREIGN_PRODUCT_ID="$(create_product "$SEEDER_TOKEN" "$FOREIGN_CATALOG_ID" "$FOREIGN_CATEGORY_ID" '{"name":"Foreign widget","sku":"AT-FOREIGN","priceCents":9900,"currency":"USD"}')"
for pair in "category:$CATEGORY_ID" "product:$PRODUCT_ID" \
            "foreign-category:$FOREIGN_CATEGORY_ID" "foreign-product:$FOREIGN_PRODUCT_ID"; do
  name="${pair%%:*}"; id="${pair#*:}"
  [ -n "$id" ] && [ "$id" != "None" ] || { echo "ERROR: failed to create the '$name' fixture." >&2; exit 1; }
done
echo "  category=$CATEGORY_ID product=$PRODUCT_ID foreign-category=$FOREIGN_CATEGORY_ID foreign-product=$FOREIGN_PRODUCT_ID"

# --- E5, the wire-level half: an OPERATOR check, not a collection assertion -----
# A client speaking to the gateway cannot see the MCP-server -> catalog hop, so "nothing is asserted
# downstream" is unprovable from inside the collection (10-QA-TEST-CASES scopes it here deliberately;
# the wire-level proof is I1). Three things are checked instead, and any of them failing would mean
# the slice had quietly reintroduced propagation:
#   1. the outbound client sets exactly two headers, Authorization + Accept — no role, no capability,
#      no acting-as;
#   2. no source in the MCP module mentions such a header at all;
#   3. the target layer really is exercised AS SHIPPED — the branch changed no library module, no
#      existing example service, and no pre-existing .rego document. This third check is ADVISORY: it
#      is a statement about THIS slice's branch, not about the rig, and a later slice that legitimately
#      touches the catalog service must not find this runner failing for a reason that has nothing to
#      do with the run. It warns; it does not abort.
if [ "${SKIP_OPERATOR_CHECK:-0}" != "1" ]; then
  echo "==> E5 operator check: nothing is asserted downstream ..."
  OUTBOUND_HEADERS="$(grep -o '\.header("[^"]*"' "$REPO_ROOT/example-mcp-server/src/main/java/dev/dmitriikonovalov/example/mcp/tool/CatalogApiClient.java" \
    | sed 's/.*"\(.*\)"/\1/' | sort -u | tr '\n' ',' )" || true
  [ "$OUTBOUND_HEADERS" = "Accept,Authorization," ] || {
    echo "ERROR: the outbound catalog client sets headers [$OUTBOUND_HEADERS] — expected exactly Accept + Authorization." >&2
    exit 1
  }
  # Both trees: the example's tools AND the opa-abac-mcp library the gate code moved into (ADR 0036).
  if grep -rniE 'x-(agent|actor|acting-as|capability|role|on-behalf)' \
      "$REPO_ROOT/example-mcp-server/src/main/java" "$REPO_ROOT/opa-abac-mcp/src/main/java" >/dev/null 2>&1; then
    echo "ERROR: the MCP module or the opa-abac-mcp library names an agent/role/acting-as header — the tool surface must assert nothing downstream." >&2
    exit 1
  fi
  # Advisory from here down — a VCS fact about the branch, not a rig fact about the run.
  UNTOUCHED="$(git -C "$REPO_ROOT" diff --name-only "$SHIPPED_BASE"...HEAD -- \
      opa-abac-core opa-abac-spring-security opa-abac-spring-data opa-abac-spring-boot-starter \
      opa-abac-keycloak-directory example-catalog-management-service example-user-management-service \
      2>/dev/null || true)"
  CHANGED_REGO="$(git -C "$REPO_ROOT" diff --name-only "$SHIPPED_BASE"...HEAD -- 'infra/opa/policies/*.rego' 2>/dev/null \
    | grep -v 'agent_tools' || true)"
  if [ -n "$UNTOUCHED" ] || [ -n "$CHANGED_REGO" ]; then
    echo "  NOTE: this branch changes code the tool surface treats as 'the shipped target layer':" >&2
    printf '    %s\n' $UNTOUCHED $CHANGED_REGO >&2
    echo "    The matrix still runs; re-read E4/E7 before trusting them as an as-shipped proof." >&2
  else
    echo "  outbound headers = Authorization + Accept only; no library, example-service or sibling-policy change."
  fi
fi

# --- the main pass -------------------------------------------------------------
mkdir -p "$REPORT_DIR/$RUN_ID"
echo "==> newman: E1/E10 — the human's surface"
run_folder "E1 · E10 — the human's surface: REST parity, and the ceiling-only roster" e1-e10
echo "==> newman: E2/E3/E8 — the agent's narrowed surface (this is also E11's turn 1)"
run_folder "E2 · E3 · E8 — the agent's narrowed surface, on one token" e2-e3-e8
echo "==> newman: E4/E8 — the target-gate as a distinguishable layer"
run_folder "E4 · E8 — the target-gate is a distinguishable second layer" e4
echo "==> newman: E5 — the agent never exceeds its principal"
run_folder "E5 — the agent never exceeds its principal (low-privilege replay + the overreach probe)" e5

# --- E6: the mid-run PDP kill --------------------------------------------------
# Stopping the container (not pausing it) makes the PDP unreachable rather than slow, which is the
# outage shape the fail-closed contract is written against.
echo "==> E6 drill: stopping OPA ($OPA_CONTAINER) mid-suite ..."
"$RUNTIME" stop "$OPA_CONTAINER" >/dev/null
run_folder "E6 — PDP kill: nothing is callable, and the roster says so" e6
echo "==> E6 drill: restarting OPA ..."
"$RUNTIME" start "$OPA_CONTAINER" >/dev/null
wait_opa
run_folder "E6-restore — the pre-kill cut returns EXACTLY" e6-restore

# --- E7: the agent-gate kill-switch ---------------------------------------------
echo "==> E7 drill: recreating the MCP pod with the agent gate OFF ..."
MCP_AGENT_GATE_ENABLED=false mcp_compose up -d --force-recreate mcp >/dev/null
wait_mcp "the gate-off recreate"
run_folder "E7 — the agent-gate kill-switch: OFF is never wider than ON" e7

# --- E11: revocation -------------------------------------------------------------
# The actor stays DECLARED with an emptied capability — a known agent answering authoritatively, not
# a missing profile and not an outage. (This also restores the agent gate, which E11 needs ON.)
echo "==> E11 drill: recreating the MCP pod with the readonly actor's capability revoked ..."
MCP_READONLY_CATEGORIES= MCP_READONLY_TOOLS= MCP_READONLY_ACTIONS= MCP_READONLY_MAX_RISK= \
  mcp_compose up -d --force-recreate mcp >/dev/null
wait_mcp "the revoked-profile recreate"
run_folder "E11 — revocation is visible end to end" e11

# --- E12: the agent-clients guard --------------------------------------------------
# catalog-gateway — the client the HUMAN token comes from, with no actor mapper — is ADDED to the agent
# clients the pod already lists (azp is copied by default, as the startup wiring check requires). No realm
# change. (This also restores the readonly profile E11 emptied: its variables are unset here.)
echo "==> E12 drill: recreating the MCP pod with catalog-gateway added to the agent clients ..."
MCP_AGENT_CLIENTS="$MCP_DEFAULT_AGENT_CLIENTS,catalog-gateway" MCP_SUBJECT_ATTRIBUTE_CLAIMS=act_chain,azp \
  mcp_compose up -d --force-recreate mcp >/dev/null
wait_mcp "the agent-clients recreate"
run_folder "E12 — the agent-clients guard: a listed client without an actor is refused" e12
echo "==> E12 drill: recreating the MCP pod on its default profile ..."
mcp_compose up -d --force-recreate mcp >/dev/null
wait_mcp "the default-profile recreate"
run_folder "E12-restore — the default pod answers the human token normally again" e12-restore

# --- E13–E16: the MCP OAuth surface (GATEWAY-AUDIENCE-BINDING, ADR 0035) -------------------------
# On the shipped pod (E12-restore just recreated it): discovery on, catalog-mcp-host a listed agent client.
echo "==> newman: E13 — the protected-resource documents through the gateway"
run_folder "E13 — the protected-resource documents through the gateway, without a token" e13
# E13's other half, here because curl sends a caller-chosen Host as given: the document is built from the
# MCP server's configuration, so a forged Host and forwarding headers must not move a single byte of it.
DOC_PATH="/.well-known/oauth-protected-resource/mcp"
DOC_PLAIN="$(curl -s "$GATEWAY$DOC_PATH")" || true
DOC_FORGED="$(curl -s -H 'Host: attacker.invalid' -H 'X-Forwarded-Host: attacker.invalid' \
  -H 'X-Forwarded-Proto: https' "$GATEWAY$DOC_PATH")" || true
[ -n "$DOC_PLAIN" ] && [ "$DOC_FORGED" = "$DOC_PLAIN" ] || {
  echo "ERROR: the protected-resource document changed under a forged Host (E13):" >&2
  echo "       plain:  $DOC_PLAIN" >&2
  echo "       forged: $DOC_FORGED" >&2
  exit 1; }
echo "  E13: a forged Host / X-Forwarded-* answers the byte-identical document."

echo "==> E14: the discovery login — the host agent's token, minted the way an MCP host does ..."
HOST_MINT_LOG="$(mktemp -t agent-host-mint.XXXXXX)"
HOST_PAIR="$("$MINER" --discover "$GATEWAY/mcp" --client "$HOST_CLIENT" --redirect-uri "$HOST_REDIRECT" \
  --user "$DEMO_USER" --password "$DEMO_PASS" --field access_token,refresh_token 2>"$HOST_MINT_LOG")" || {
  echo "ERROR: the discovery login failed (E14):" >&2
  sed 's/^/    /' "$HOST_MINT_LOG" >&2
  rm -f "$HOST_MINT_LOG"
  exit 1; }
grep -E 'metadata' "$HOST_MINT_LOG" | sed 's/^ */  /' || true
rm -f "$HOST_MINT_LOG"
HOST_TOKEN="$(printf '%s' "$HOST_PAIR" | sed -n '1p')"
HOST_REFRESH="$(printf '%s' "$HOST_PAIR" | sed -n '2p')"
[ -n "$HOST_TOKEN" ] && [ -n "$HOST_REFRESH" ] || {
  echo "ERROR: the discovery login returned no access/refresh pair (E14)." >&2; exit 1; }
python3 - "$HOST_TOKEN" "$GATEWAY" "$HOST_CLIENT" <<'PY' || { echo "ERROR: the host token is not an agent token for the gateway (E14)." >&2; exit 1; }
import base64, json, sys
payload = sys.argv[1].split(".")[1]
claims = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
aud = claims.get("aud")
expected = {"azp": sys.argv[3], "act_chain": ["agent-host"], "iss": sys.argv[2] + "/realms/catalog-demo"}
wrong = [f"{k}={claims.get(k)!r}, expected {v!r}" for k, v in expected.items() if claims.get(k) != v]
if "catalog-gateway" not in (aud if isinstance(aud, list) else [aud]):
    wrong.append(f"aud={aud!r} does not carry catalog-gateway")
for line in wrong:
    print("  " + line, file=sys.stderr)
if wrong:
    sys.exit(1)
print(f"  E14: aud={aud!r} azp={claims['azp']} act_chain={claims['act_chain']} iss={claims['iss']}")
PY

echo "==> newman: E15 — the MCP host is an agent, narrowed on the real login path"
run_folder "E15 — the MCP host is an agent: narrowed on the real login path" e15

# E16: rotation, as the host presents it — through the gateway origin, as the public client, the way the
# miner's discovery login (and so the token's own issuer) reached the realm.
echo "==> E16: the host's refresh token rotates ..."
HOST_TOKEN_URL="$GATEWAY/realms/catalog-demo/protocol/openid-connect/token"
refresh_as_host() { # refresh_token -> "<status> <body>"
  curl -s -o /dev/stdout -w ' %{http_code}' -X POST "$HOST_TOKEN_URL" \
    -d grant_type=refresh_token -d "client_id=$HOST_CLIENT" -d "refresh_token=$1"
}
ROTATED="$(refresh_as_host "$HOST_REFRESH")" || true
ROTATED_CODE="${ROTATED##* }"
ROTATED_REFRESH="$(printf '%s' "${ROTATED% *}" | sed -n 's/.*"refresh_token":"\([^"]*\)".*/\1/p')"
[ "$ROTATED_CODE" = "200" ] && [ -n "$ROTATED_REFRESH" ] && [ "$ROTATED_REFRESH" != "$HOST_REFRESH" ] || {
  echo "ERROR: the host's refresh did not answer 200 with a NEW refresh token (E16): $ROTATED_CODE" >&2
  echo "       $(printf '%s' "${ROTATED% *}" | head -c 300)" >&2
  exit 1; }
REPLAYED="$(refresh_as_host "$HOST_REFRESH")" || true
REPLAYED_CODE="${REPLAYED##* }"
REPLAYED_BODY="${REPLAYED% *}"
[ "$REPLAYED_CODE" = "400" ] \
  && printf '%s' "$REPLAYED_BODY" | grep -q '"error":"invalid_grant"' \
  && printf '%s' "$REPLAYED_BODY" | grep -q 'Maximum allowed refresh token reuse exceeded' || {
  echo "ERROR: replaying the spent refresh token was not refused as a reuse (E16): $REPLAYED_CODE" >&2
  echo "       $REPLAYED_BODY" >&2
  echo "       Is revokeRefreshToken on in the realm? (re-import it: ./deploy.sh down, then up)" >&2
  exit 1; }
echo "  E16: the refresh answered a new refresh token; the replay -> 400 invalid_grant (reuse exceeded)."

echo "==> newman: E17 — the transport's own refusals carry no stack trace"
run_folder "E17 — the transport's own refusals name the error, never a stack trace" e17

# --- teardown (success only — a failed run keeps its fixtures for debugging) --
# KEEP_FIXTURES=1 skips it. The DELETEs ride the FK cascades: team -> memberships + custom roles;
# catalog -> categories -> products. The rig itself is restored by the EXIT trap either way.
if [ "${KEEP_FIXTURES:-0}" != "1" ]; then
  echo "==> Teardown: removing the $AGENT_CATALOG_ID + $FOREIGN_CATALOG_ID fixture(s) (KEEP_FIXTURES=1 keeps them) ..."
  "$RUNTIME" exec -i "$UM_PG_CONTAINER" psql -U usermgmt -d usermgmt -v ON_ERROR_STOP=1 >/dev/null <<SQL
DELETE FROM team WHERE target_type = 'catalog' AND target_id IN ('$AGENT_CATALOG_ID', '$FOREIGN_CATALOG_ID');
SQL
  "$RUNTIME" exec -i "$PG_CONTAINER" psql -U catalog -d catalog -v ON_ERROR_STOP=1 >/dev/null <<SQL
DELETE FROM catalog WHERE id IN ('$AGENT_CATALOG_ID', '$FOREIGN_CATALOG_ID');
SQL
fi

echo "==> Agent tool-call authorization matrix: E1-E8, E10-E17 green through the gateway."
