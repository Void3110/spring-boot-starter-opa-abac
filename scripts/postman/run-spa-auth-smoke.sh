#!/usr/bin/env bash
#
# SPA bearer-validation smoke test (Phase 7 demo SPA).
#
# Proves the gateway's ENABLE_SPA=1 posture: APISIX validates an incoming
# `Authorization: Bearer <jwt>` against the realm JWKS and DENIES (401) anything missing/invalid —
# it does NOT redirect to login (the default OIDC posture answers 302). This is the bearer-only
# contract the browser SPA relies on (it does its own Authorization Code + PKCE and presents a token).
#
# Run AFTER:  ENABLE_SPA=1 ./deploy.sh up --pods 2
# Then:       scripts/postman/run-spa-auth-smoke.sh
#
# Tokens are minted IN-NETWORK (issuer must match what APISIX validates against — see infra/README.md
# "Issuer gotcha"). The browser SPA uses the public `catalog-spa` client via PKCE; that client has
# direct-access-grants OFF (correct for a real browser client), so this CLI smoke test mints via the
# confidential `catalog-gateway` client instead — the token is realm-scoped, so APISIX validates it
# regardless of which client minted it.

set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SELF_DIR"

# --- config (override via env) -----------------------------------------------
NETWORK="${COMPOSE_NETWORK:-opa-abac-example_default}"
KEYCLOAK_TOKEN_URL="${KEYCLOAK_TOKEN_URL:-http://keycloak:8888/realms/catalog-demo/protocol/openid-connect/token}"
CLIENT_ID="${CLIENT_ID:-catalog-gateway}"
CLIENT_SECRET="${CLIENT_SECRET:-catalog-gateway-secret}"
GATEWAY="${GATEWAY:-http://localhost:9085}"
# A cheap, always-present endpoint to probe the auth posture without touching app data.
PROBE_PATH="${PROBE_PATH:-/actuator/health}"
# A read the viewer is allowed (proves a valid token is not just accepted but flows through to a 200).
READ_PATH="${READ_PATH:-/api/v1/catalogs}"
# Note: not USER/PASS — $USER is a shell-provided env var (your login name) that would shadow a default.
SMOKE_USER="${SMOKE_USER:-viewer}"; SMOKE_PASS="${SMOKE_PASS:-viewer}"

# --- preflight ---------------------------------------------------------------
RUNTIME=""
for c in docker podman; do command -v "$c" >/dev/null 2>&1 && { RUNTIME="$c"; break; }; done
[ -n "$RUNTIME" ] || { echo "ERROR: need docker or podman to mint in-network tokens." >&2; exit 1; }

mint_token() {
  local user="$1" pass="$2" json
  json="$("$RUNTIME" run --rm --network "$NETWORK" curlimages/curl -s \
    -X POST "$KEYCLOAK_TOKEN_URL" \
    -d grant_type=password -d "client_id=$CLIENT_ID" -d "client_secret=$CLIENT_SECRET" \
    -d "username=$user" -d "password=$pass" || true)"
  printf '%s' "$json" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p'
}

code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

PASS_N=0; FAIL_N=0
check() { # check <label> <expected-code> <actual-code>
  local label="$1" want="$2" got="$3"
  if [ "$got" = "$want" ]; then
    printf '  ✓ %-46s %s\n' "$label" "$got"; PASS_N=$((PASS_N+1))
  else
    printf '  ✗ %-46s got %s, want %s\n' "$label" "$got" "$want"; FAIL_N=$((FAIL_N+1))
  fi
}

echo "==> SPA bearer-validation smoke test against $GATEWAY"
echo "    (gateway must be up with ENABLE_SPA=1; posture: bearer_only + unauth_action=deny)"

# 1) No token -> 401 (deny), NOT 302 (the default OIDC posture would redirect).
no_tok="$(code "$GATEWAY$PROBE_PATH")"
check "no token -> 401 (deny, not 302 redirect)" "401" "$no_tok"

# 2) Garbage token -> 401 (JWKS validation rejects it).
bad_tok="$(code -H 'Authorization: Bearer not.a.real.jwt' "$GATEWAY$PROBE_PATH")"
check "invalid token -> 401" "401" "$bad_tok"

# 3) Valid in-network token -> 200 (validation passes; request flows through).
echo "==> Minting a valid '$SMOKE_USER' token in-network ($NETWORK) ..."
TOK="$(mint_token "$SMOKE_USER" "$SMOKE_PASS")"
[ -n "$TOK" ] || { echo "ERROR: failed to mint a token (is Keycloak up? network $NETWORK?)" >&2; exit 1; }
ok_tok="$(code -H "Authorization: Bearer $TOK" "$GATEWAY$PROBE_PATH")"
check "valid token -> 200 (health)" "200" "$ok_tok"
ok_read="$(code -H "Authorization: Bearer $TOK" "$GATEWAY$READ_PATH")"
check "valid token -> 200 (read $READ_PATH)" "200" "$ok_read"

echo ""
echo "==> $PASS_N passed, $FAIL_N failed"
[ "$FAIL_N" -eq 0 ]
