#!/usr/bin/env bash
#
# Seed the APISIX upstream + route for the catalog app pool.
#
# Idempotent: safe to re-run. Creates a single-node upstream so the route exists on a fresh
# `compose up` before any pods are launched; deploy.sh then republishes the upstream with the
# real pod set (round-robin over all running pods). Run automatically by deploy.sh.
#
# Phase A: no auth, no OPA, no tracing — just proxy + load balancing.

set -euo pipefail

# This script's own directory (infra/apisix), and the repo root two levels up — resolved so the
# script works regardless of where it is invoked from.
SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SELF_DIR/../.." && pwd)"

APISIX_ADMIN="${APISIX_ADMIN:-http://localhost:9180}"
API_KEY="${APISIX_API_KEY:-edd1c9f034335f136f87ad84b625c8f1}"
# Default single node — the first app pod publishes on host port 28081 (see deploy.sh BASE_PORT).
DEFAULT_NODE="${DEFAULT_NODE:-host.docker.internal:28081}"

echo "==> Configuring APISIX at $APISIX_ADMIN ..."

# Wait for the Admin API (APISIX takes ~10-30s to come up).
for i in $(seq 1 30); do
  if curl -sf -o /dev/null -H "X-API-KEY: $API_KEY" "$APISIX_ADMIN/apisix/admin/routes" 2>/dev/null; then
    break
  fi
  if [ "$i" -eq 30 ]; then
    echo "ERROR: APISIX Admin API unreachable at $APISIX_ADMIN" >&2
    exit 1
  fi
  sleep 2
done

# Upstream: round-robin pool over the catalog app pods.
#   pass_host=pass keeps the original Host header so the Spring app sees the right URL.
curl -sf -o /dev/null -X PUT \
  -H "X-API-KEY: $API_KEY" -H "Content-Type: application/json" \
  "$APISIX_ADMIN/apisix/admin/upstreams/catalog-pool" \
  -d "{
    \"type\": \"roundrobin\",
    \"pass_host\": \"pass\",
    \"scheme\": \"http\",
    \"nodes\": { \"$DEFAULT_NODE\": 1 }
  }"
echo "  upstream 'catalog-pool' -> $DEFAULT_NODE (deploy.sh republishes with all pods)"

# Route: everything to the catalog pool.
#   - response-rewrite: echo the served pod in X-Upstream-Addr (load-balance visibility):
#       curl -sD - localhost:9085/actuator/health | grep -i upstream
#   - opentelemetry: emit a gateway span per request -> Jaeger.
#   - opa: call OPA for a decision (allow-all policy for now). Gracefully skipped if OPA is
#     down only when ENABLE_OPA is unset; by default we wire it so the topology is traced.
#
# ENABLE_OPA / ENABLE_TRACING / ENABLE_OIDC let deploy.sh add each plugin only once its
# backing service (OPA / Jaeger / Keycloak) is actually up.
PLUGINS='"response-rewrite":{"headers":{"set":{"X-Upstream-Addr":"$upstream_addr"}}}'

# openid-connect FIRST (authenticate before authorize). Terminates Keycloak OIDC at the
# gateway: bearer tokens are validated against the realm JWKS. The validated access token is
# forwarded upstream (Authorization: Bearer ...), so OPA and the app *could* read identity —
# but the service does no JWT check yet (Phase 2).
#
# Two postures, selected by ENABLE_SPA:
#   - Default (browser-flow): bearer_only=false + unauth_action="auth" — an unauthenticated
#     browser is redirected to the Keycloak login page (the gateway runs the confidential
#     auth-code flow itself). This is what the existing e2e matrices + the catalog-gateway
#     confidential client rely on; keep it the default so nothing else changes.
#   - SPA (ENABLE_SPA=1, bearer-only): bearer_only=true + unauth_action="deny" — the gateway
#     ONLY validates an incoming `Authorization: Bearer <jwt>` against the realm JWKS and 401s
#     when it is missing/invalid; it never initiates a redirect login. The browser SPA does its
#     own Authorization Code + PKCE against Keycloak (public client `catalog-spa`) and presents
#     the token. Same JWKS validation, no gateway-driven login.
if [ "${ENABLE_OIDC:-0}" = "1" ]; then
  if [ "${ENABLE_SPA:-0}" = "1" ]; then
    OIDC_BEARER_ONLY="true"
    OIDC_UNAUTH_ACTION="deny"
  else
    OIDC_BEARER_ONLY="false"
    OIDC_UNAUTH_ACTION="auth"
  fi
  PLUGINS="$PLUGINS,\"openid-connect\":{\
\"_meta\":{\"priority\":2599},\
\"client_id\":\"catalog-gateway\",\
\"client_secret\":\"catalog-gateway-secret\",\
\"discovery\":\"http://keycloak:8888/realms/catalog-demo/.well-known/openid-configuration\",\
\"realm\":\"catalog-demo\",\
\"scope\":\"openid profile email\",\
\"bearer_only\":$OIDC_BEARER_ONLY,\
\"use_jwks\":true,\
\"unauth_action\":\"$OIDC_UNAUTH_ACTION\",\
\"set_access_token_header\":true,\
\"access_token_in_authorization_header\":true,\
\"ssl_verify\":false}"
fi

# CORS — only needed when a browser SPA on a different origin (the Vite dev server on :3000)
# calls the gateway cross-origin. In the packaged demo the SPA is served *through* APISIX
# (same-origin) so this is belt-and-suspenders. Enabled with the SPA posture; harmless when on.
if [ "${ENABLE_SPA:-0}" = "1" ]; then
  PLUGINS="$PLUGINS,\"cors\":{\
\"_meta\":{\"priority\":4000},\
\"allow_origins\":\"http://localhost:3000,http://localhost:9085\",\
\"allow_methods\":\"GET,POST,PUT,DELETE,OPTIONS,PATCH,HEAD\",\
\"allow_headers\":\"Authorization,Content-Type,Accept,Origin,X-Requested-With\",\
\"expose_headers\":\"X-Upstream-Addr,Location\",\
\"allow_credential\":false,\
\"max_age\":3600}"
fi
# NOTE: the demo identity enricher was RETIRED in the library-spine slice (Phase 3). The gateway no
# longer injects X-User-Id / X-Username via a serverless-pre-function — `openid-connect` validates the
# token and forwards the Bearer, and the application now does Spring-native AbacContext extraction
# itself (opa-abac-spring-security's AbacFilter). The gateway stays coarse (authn + a coarse OPA route
# check); fine-grained, role-definition-driven ABAC lives in the app via the library.
if [ "${ENABLE_TRACING:-1}" = "1" ]; then
  PLUGINS="$PLUGINS,\"opentelemetry\":{\"sampler\":{\"name\":\"always_on\"}}"
fi
if [ "${ENABLE_OPA:-1}" = "1" ]; then
  PLUGINS="$PLUGINS,\"opa\":{\"_meta\":{\"priority\":2000},\"host\":\"http://opa:8181\",\"policy\":\"gateway\",\"response_allow_field\":\"result.allow\"}"
fi

# SPA single-origin auth: proxy Keycloak THROUGH the gateway so the browser does its entire
# Authorization Code + PKCE flow against http://localhost:9085 — never needing to resolve the
# in-network hostname `keycloak:8888` and never needing an /etc/hosts entry.
#
# Keycloak honors the forwarded Host/X-Forwarded-* headers APISIX sends and rewrites ALL of its
# advertised URLs (issuer, authorization/token/jwks endpoints) to the gateway origin, so a token
# minted through the gateway carries issuer `http://localhost:9085/realms/catalog-demo` and the
# discovery doc is self-consistent at that origin. We proxy Keycloak at its NATIVE paths
# (/realms/*, /resources/*) — NOT under an /auth prefix — because Keycloak builds its URLs from the
# proxied path; a prefix would make the advertised endpoints (…:9085/realms/…) not match where the
# browser actually called (…:9085/auth/realms/…). These paths don't collide with the app's /api/**.
#
# CRITICAL: these routes carry NO openid-connect plugin — the login/token/JWKS calls must be public
# passthrough (you can't present a bearer token while you're still trying to obtain one). Priority 50
# beats the catalog-all catch-all (/*, priority 0). Only wired in SPA mode.
if [ "${ENABLE_SPA:-0}" = "1" ]; then
  curl -sf -o /dev/null -X PUT \
    -H "X-API-KEY: $API_KEY" -H "Content-Type: application/json" \
    "$APISIX_ADMIN/apisix/admin/upstreams/keycloak-pool" \
    -d '{"type":"roundrobin","scheme":"http","nodes":{"keycloak:8888":1}}'
  for kc in "keycloak-realms:/realms/*:GET,POST,OPTIONS,HEAD" "keycloak-resources:/resources/*:GET,OPTIONS,HEAD"; do
    name="${kc%%:*}"; rest="${kc#*:}"; uri="${rest%%:*}"; methods="${rest#*:}"
    methods_json="$(printf '%s' "$methods" | sed 's/[^,]*/"&"/g')"
    curl -sf -o /dev/null -X PUT \
      -H "X-API-KEY: $API_KEY" -H "Content-Type: application/json" \
      "$APISIX_ADMIN/apisix/admin/routes/$name" \
      -d "{\"name\":\"$name\",\"uri\":\"$uri\",\"methods\":[$methods_json],\"upstream_id\":\"keycloak-pool\",\"priority\":50,\"status\":1}"
  done
  echo "  routes 'keycloak-realms' (/realms/*) + 'keycloak-resources' (/resources/*) -> keycloak:8888  [SPA single-origin auth, public passthrough]"
fi

# Slice B4 (ADR 0019): route the PUBLIC user-management self-service prefixes through the gateway so
# the user-service's CallerIdentity gets the gateway-validated subject (createTeam owner-on-create + the
# ownership squat-check both need the real `sub`). Two routes, /api/v1/teams* and /api/v1/users*, at a
# priority ABOVE the catalog catch-all (/*, priority 0) so they win, pointed at a new usermgmt-pool. They
# carry the SAME openid-connect bearer validation (+ tracing/cors) as the catalog route — the user-service
# does its own fine-grained @OpaPreAuthorize, so they do NOT carry the catalog `opa` gateway-route plugin.
#
# CRITICAL: /internal/** is permitAll + in-network only across BOTH services (the resolve API, the
# governed-targets endpoint, the ownership created-by read, the bootstrap seed) — exposing it through the
# gateway would let anyone forge a `sub` or read a creator id. The user-service pool is protected by
# OMISSION (its only routes are /api/v1/teams* + /api/v1/users*). The CATALOG pool, however, is served by a
# catch-all (/*, below) that WOULD otherwise match /internal/catalog/{id}/created-by — so an explicit
# `internal-blocked` route (priority 70, defined just before the catch-all) 404s every /internal/* path at
# the edge. The gateway thus proxies ONLY /api/v1/teams*, /api/v1/users*, /api/v1/catalogs* (catch-all),
# and the Keycloak realms/resources paths; /internal/* is positively blocked.
#
# Gated under ENABLE_USER_SERVICE (ENABLE_SPA implies it) — only wired when the usermgmt pod is up.
if [ "${ENABLE_USER_SERVICE:-0}" = "1" ]; then
  # The usermgmt pod publishes on host port 28090 (deploy.sh wait_usermgmt_healthy).
  USERMGMT_NODE="${USERMGMT_NODE:-host.docker.internal:28090}"
  curl -sf -o /dev/null -X PUT \
    -H "X-API-KEY: $API_KEY" -H "Content-Type: application/json" \
    "$APISIX_ADMIN/apisix/admin/upstreams/usermgmt-pool" \
    -d "{\"type\":\"roundrobin\",\"pass_host\":\"pass\",\"scheme\":\"http\",\"nodes\":{\"$USERMGMT_NODE\":1}}"
  echo "  upstream 'usermgmt-pool' -> $USERMGMT_NODE"

  # The user-mgmt routes' plugin set: openid-connect (the same bearer validation as the catalog route)
  # + tracing + cors-when-SPA. NOT the catalog `opa` gateway plugin (the user-service authorizes itself).
  UM_PLUGINS='"response-rewrite":{"headers":{"set":{"X-Upstream-Addr":"$upstream_addr"}}}'
  if [ "${ENABLE_OIDC:-0}" = "1" ]; then
    UM_PLUGINS="$UM_PLUGINS,\"openid-connect\":{\
\"_meta\":{\"priority\":2599},\
\"client_id\":\"catalog-gateway\",\
\"client_secret\":\"catalog-gateway-secret\",\
\"discovery\":\"http://keycloak:8888/realms/catalog-demo/.well-known/openid-configuration\",\
\"realm\":\"catalog-demo\",\
\"scope\":\"openid profile email\",\
\"bearer_only\":$OIDC_BEARER_ONLY,\
\"use_jwks\":true,\
\"unauth_action\":\"$OIDC_UNAUTH_ACTION\",\
\"set_access_token_header\":true,\
\"access_token_in_authorization_header\":true,\
\"ssl_verify\":false}"
  fi
  if [ "${ENABLE_SPA:-0}" = "1" ]; then
    UM_PLUGINS="$UM_PLUGINS,\"cors\":{\
\"_meta\":{\"priority\":4000},\
\"allow_origins\":\"http://localhost:3000,http://localhost:9085\",\
\"allow_methods\":\"GET,POST,PUT,DELETE,OPTIONS,PATCH,HEAD\",\
\"allow_headers\":\"Authorization,Content-Type,Accept,Origin,X-Requested-With\",\
\"expose_headers\":\"X-Upstream-Addr,Location\",\
\"allow_credential\":false,\
\"max_age\":3600}"
  fi
  if [ "${ENABLE_TRACING:-1}" = "1" ]; then
    UM_PLUGINS="$UM_PLUGINS,\"opentelemetry\":{\"sampler\":{\"name\":\"always_on\"}}"
  fi

  # Priority 60 (> the catch-all's 0, and distinct from the Keycloak proxy's 50). A prefix match on
  # /api/v1/teams* / /api/v1/users* — NOT /internal/** — so only the public self-service surface routes.
  for um in "usermgmt-teams:/api/v1/teams*" "usermgmt-users:/api/v1/users*"; do
    name="${um%%:*}"; uri="${um#*:}"
    um_resp=$(curl -s -w "\n%{http_code}" -X PUT \
      -H "X-API-KEY: $API_KEY" -H "Content-Type: application/json" \
      "$APISIX_ADMIN/apisix/admin/routes/$name" \
      -d "{\"name\":\"$name\",\"uri\":\"$uri\",\
\"methods\":[\"GET\",\"POST\",\"PUT\",\"DELETE\",\"OPTIONS\",\"PATCH\",\"HEAD\"],\
\"upstream_id\":\"usermgmt-pool\",\"priority\":60,\"status\":1,\"plugins\":{$UM_PLUGINS}}")
    um_code=$(printf '%s' "$um_resp" | tail -n1)
    if [ "$um_code" != "200" ] && [ "$um_code" != "201" ]; then
      echo "  ERROR: route '$name' PUT failed ($um_code): $(printf '%s' "$um_resp" | head -n1)" >&2
      exit 1
    fi
  done
  echo "  routes 'usermgmt-teams' (/api/v1/teams*) + 'usermgmt-users' (/api/v1/users*) -> usermgmt-pool  [priority 60; NO /internal route]"
fi

# Slice B4 hardening (deep-review): EXPLICITLY block /internal/* at the gateway. The catalog route below
# is a catch-all (/*, priority 0); without this, GET /internal/catalog/{id}/created-by (catalog
# SecurityConfig permitAll's /internal/**) would match the catch-all and proxy through to the catalog pod,
# leaking a creator `sub`. The user-service is protected by OMISSION (its pool only has /api/v1/teams* +
# /api/v1/users* routes), but the catalog catch-all needs a POSITIVE block. Priority 70 (> usermgmt's 60 >
# the catch-all's 0) so it wins for any /internal/* path; a serverless-pre-function (already enabled in
# config.yaml) returns 404 BEFORE any upstream is selected — the path is not even acknowledged at the edge.
# No upstream is attached: the pre-function exits first. This makes the "/internal is never gateway-fronted"
# invariant STRUCTURAL (an explicit deny route) rather than incidental (a missing route).
internal_block_resp=$(curl -s -w "\n%{http_code}" -X PUT \
  -H "X-API-KEY: $API_KEY" -H "Content-Type: application/json" \
  "$APISIX_ADMIN/apisix/admin/routes/internal-blocked" \
  -d "{
    \"name\": \"internal-blocked\",
    \"uri\": \"/internal/*\",
    \"methods\": [\"GET\",\"POST\",\"PUT\",\"DELETE\",\"OPTIONS\",\"PATCH\",\"HEAD\"],
    \"priority\": 70,
    \"status\": 1,
    \"plugins\": {
      \"serverless-pre-function\": {
        \"phase\": \"rewrite\",
        \"functions\": [\"return function(conf, ctx) require('apisix.core').response.exit(404) end\"]
      }
    }
  }")
internal_block_code=$(printf '%s' "$internal_block_resp" | tail -n1)
if [ "$internal_block_code" != "200" ] && [ "$internal_block_code" != "201" ]; then
  echo "  ERROR: route 'internal-blocked' PUT failed ($internal_block_code): $(printf '%s' "$internal_block_resp" | head -n1)" >&2
  exit 1
fi
echo "  route 'internal-blocked' (/internal/*) -> 404 at the edge  [priority 70; the /internal-never-gateway-fronted invariant, enforced]"

# Note: use -s (not -sf) and inspect the body — a -f swallows APISIX's 400 error messages
# (e.g. "unknown plugin [...]" when a plugin isn't enabled in config.yaml).
route_resp=$(curl -s -w "\n%{http_code}" -X PUT \
  -H "X-API-KEY: $API_KEY" -H "Content-Type: application/json" \
  "$APISIX_ADMIN/apisix/admin/routes/catalog-all" \
  -d "{
    \"name\": \"catalog-all\",
    \"uri\": \"/*\",
    \"methods\": [\"GET\",\"POST\",\"PUT\",\"DELETE\",\"OPTIONS\",\"PATCH\",\"HEAD\"],
    \"upstream_id\": \"catalog-pool\",
    \"status\": 1,
    \"plugins\": { $PLUGINS }
  }")
route_code=$(printf '%s' "$route_resp" | tail -n1)
if [ "$route_code" != "200" ] && [ "$route_code" != "201" ]; then
  echo "  ERROR: route PUT failed ($route_code): $(printf '%s' "$route_resp" | head -n1)" >&2
  exit 1
fi
echo "  route 'catalog-all' (/*) -> catalog-pool  [oidc=${ENABLE_OIDC:-0} (app does extraction) spa=${ENABLE_SPA:-0} (bearer-only+cors) tracing=${ENABLE_TRACING:-1} opa=${ENABLE_OPA:-1}]"
echo "==> APISIX ready: proxy at http://localhost:9085"
