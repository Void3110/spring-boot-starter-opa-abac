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
# ENABLE_OPA / ENABLE_TRACING let deploy.sh add these only once Jaeger/OPA are actually up.
PLUGINS='"response-rewrite":{"headers":{"set":{"X-Upstream-Addr":"$upstream_addr"}}}'
if [ "${ENABLE_TRACING:-1}" = "1" ]; then
  PLUGINS="$PLUGINS,\"opentelemetry\":{\"sampler\":{\"name\":\"always_on\"}}"
fi
if [ "${ENABLE_OPA:-1}" = "1" ]; then
  PLUGINS="$PLUGINS,\"opa\":{\"host\":\"http://opa:8181\",\"policy\":\"gateway\",\"response_allow_field\":\"result.allow\"}"
fi

curl -sf -o /dev/null -X PUT \
  -H "X-API-KEY: $API_KEY" -H "Content-Type: application/json" \
  "$APISIX_ADMIN/apisix/admin/routes/catalog-all" \
  -d "{
    \"name\": \"catalog-all\",
    \"uri\": \"/*\",
    \"methods\": [\"GET\",\"POST\",\"PUT\",\"DELETE\",\"OPTIONS\",\"PATCH\",\"HEAD\"],
    \"upstream_id\": \"catalog-pool\",
    \"status\": 1,
    \"plugins\": { $PLUGINS }
  }"
echo "  route 'catalog-all' (/*) -> catalog-pool  [tracing=${ENABLE_TRACING:-1} opa=${ENABLE_OPA:-1}]"
echo "==> APISIX ready: proxy at http://localhost:9085"
