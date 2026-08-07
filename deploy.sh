#!/usr/bin/env bash
#
# deploy.sh — local container-pool rig for the catalog example app.
#
# Builds the catalog app into a Docker image and runs N replicas behind an APISIX
# round-robin upstream, so you can exercise concurrency / load balancing locally.
# Postgres comes from the base compose.yaml (./profile.sh up); APISIX + etcd come from
# infra/compose.apisix.yaml. No auth in this phase — that's intentional.
#
# Topology:
#   client -> APISIX :9085 (round-robin) -> catalog-1..N (host ports 28081..2808N) -> Postgres
#   (host 9085 not 9080 — podman-machine's gvproxy holds :9080 on this host)
#
# Usage:
#   ./deploy.sh up [--pods N]     Build (if needed) + start Postgres, APISIX, and N app pods,
#                                 then point the APISIX upstream at all pods. Default N=2.
#   ./deploy.sh down [-v]         Stop the whole rig: app pods + APISIX + OPA + Keycloak +
#                                 user-mgmt + Jaeger + base Postgres. -v also removes volumes.
#   ./deploy.sh build             Rebuild the app image only.
#   ./deploy.sh scale --pods N    Re-generate + restart the pool at N pods, resync upstream.
#   ./deploy.sh status            Show what's running + the current upstream node set.
#   ./deploy.sh logs [--pod K]    Tail all app pods, or one.
#
# Pod count is remembered in build/.deploy.state across up/down.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="opa-abac-example"
APP_DIR="$SCRIPT_DIR/example-catalog-management-service"
USERMGMT_DIR="$SCRIPT_DIR/example-user-management-service"
MCP_DIR="$SCRIPT_DIR/example-mcp-server"
BASE_COMPOSE="$SCRIPT_DIR/compose.yaml"
APISIX_COMPOSE="$SCRIPT_DIR/infra/compose.apisix.yaml"
JAEGER_COMPOSE="$SCRIPT_DIR/infra/compose.jaeger.yaml"
OPA_COMPOSE="$SCRIPT_DIR/infra/compose.opa.yaml"
KEYCLOAK_COMPOSE="$SCRIPT_DIR/infra/compose.keycloak.yaml"
USERMGMT_COMPOSE="$SCRIPT_DIR/infra/compose.usermgmt.yaml"
MCP_COMPOSE="$SCRIPT_DIR/infra/compose.mcp.yaml"
GEN_COMPOSE="$SCRIPT_DIR/build/compose.pods.generated.yaml"
STATE_FILE="$SCRIPT_DIR/build/.deploy.state"
IMAGE="opa-abac-catalog:local"
USERMGMT_IMAGE="opa-abac-usermgmt:local"
MCP_IMAGE="opa-abac-mcp:local"

# Feature toggles. Tracing + OPA on by default (Phase B). OIDC off by default — opt in with
# ENABLE_OIDC=1 ./deploy.sh up  (Phase 2 gateway auth; needs Keycloak, slower to start).
ENABLE_TRACING="${ENABLE_TRACING:-1}"
ENABLE_OPA="${ENABLE_OPA:-1}"
ENABLE_OIDC="${ENABLE_OIDC:-0}"
# Phase 4: run the user-management-service alongside the catalog pool and resolve roles from it
# (the app-resolved path). Off by default — opt in with ENABLE_USER_SERVICE=1 ./deploy.sh up.
# When on, the catalog pods get CATALOG_ROLE_SOURCE=http pointed at http://usermgmt:8080.
ENABLE_USER_SERVICE="${ENABLE_USER_SERVICE:-0}"
# ADR 0029: the supervised-scope edge (/internal/supervised-targets) has its OWN base URL so it can be
# fault-injected ALONE — repointing it at a dead port degrades a supervisor to their own memberships
# while role resolve and tag definitions keep working. Defaults to the shared user-service URL, so the
# rig is unchanged unless you set it. This is what the supervised-scope matrix's second (E8) pass uses:
#   CATALOG_USER_SERVICE_SUPERVISED_BASE_URL=http://127.0.0.1:9 ./deploy.sh up --pods 2
# (do NOT use ENABLE_RESILIENCE_STUB for this — that repoints the WHOLE user-service the rest of the
# supervised matrix needs).
CATALOG_USER_SERVICE_SUPERVISED_BASE_URL="${CATALOG_USER_SERVICE_SUPERVISED_BASE_URL:-http://usermgmt:8080}"
# ADR 0022: the root-read tag exemption ships ON via infra/opa/policies/config.json (members always
# read/list the governing root; required_tags gate mutations + everything below the root). Set
# ROOT_READ_TAG_EXEMPTION=0|1 to override the LIVE flag right after OPA starts — an in-memory
# /v1/data PUT, so an OPA container restart reverts to the file default. Unset = file default.
ROOT_READ_TAG_EXEMPTION="${ROOT_READ_TAG_EXEMPTION:-}"
# Slice B3: a fault-injecting stand-in for the resolve endpoint, for the resilience e2e. Off by default —
# opt in with ENABLE_RESILIENCE_STUB=1 [STUB_MODE=transient|down] [STUB_FAILS=1] ./deploy.sh up. When on,
# the catalog pods get CATALOG_ROLE_SOURCE=http pointed at http://resolve-stub:8080 (NOT the real
# user-mgmt) so the resolve CallGuard sees a controlled outage. See infra/compose.resilience-stub.yaml.
ENABLE_RESILIENCE_STUB="${ENABLE_RESILIENCE_STUB:-0}"
RESILIENCE_STUB_COMPOSE="$SCRIPT_DIR/infra/compose.resilience-stub.yaml"
# Phase 7 (demo SPA): the complete browser-demo recipe in one flag. ENABLE_SPA=1 puts the gateway
# in bearer-only auth mode (APISIX validates `Authorization: Bearer <jwt>` against the realm JWKS —
# no redirect login), proxies Keycloak through the gateway for single-origin PKCE, and enables CORS.
# It composes the FULL stack the SPA exercises, so it force-enables its prerequisites:
#   - ENABLE_OIDC        — the openid-connect plugin does the bearer validation.
#   - ENABLE_USER_SERVICE — the http role source + Phase-6 _actions enrichment (the affordance map
#                           the SPA renders); without it the catalog uses the demo role source and
#                           returns resources with no _actions.
# Off by default — opt in with ENABLE_SPA=1 ./deploy.sh up. (Run ./deploy.sh build first if the
# Phase-6 enrichment code isn't yet in the app images.)
ENABLE_SPA="${ENABLE_SPA:-0}"
if [ "$ENABLE_SPA" = "1" ]; then ENABLE_OIDC=1; ENABLE_USER_SERVICE=1; fi
# USER-DIRECTORY-PORT (ADR 0020): the user-service's identity-directory search via the Keycloak admin
# API (client catalog-directory, view-users only). Off by default — the default rig is unchanged (the
# user-service keeps the NoOp directory: the search sub-path answers 200-empty). Opt in with
# ENABLE_DIRECTORY=1 ./deploy.sh up. Needs Keycloak + the user-service, so (like ENABLE_SPA) it
# force-enables its prerequisites. compose.usermgmt.yaml interpolates ${DIRECTORY_ENABLED} (true/false).
ENABLE_DIRECTORY="${ENABLE_DIRECTORY:-0}"
if [ "$ENABLE_DIRECTORY" = "1" ]; then ENABLE_OIDC=1; ENABLE_USER_SERVICE=1; fi
DIRECTORY_ENABLED="false"; [ "$ENABLE_DIRECTORY" = "1" ] && DIRECTORY_ENABLED="true"
export DIRECTORY_ENABLED
# AGENT-TOOL-AUTHZ (Phase 9): the example MCP server — the agent tool surface behind the tool-gate.
# Off by default, so the default rig is byte-for-byte unchanged. Opt in with ENABLE_MCP=1 ./deploy.sh up.
# Like ENABLE_SPA/ENABLE_DIRECTORY it force-enables its prerequisites, and it needs all three: the
# gateway validates the bearer (OIDC), the tool-gate asks OPA, and the principal's type-level ceiling
# is resolved from the user-service — a missing one of those does not degrade, it denies every call.
ENABLE_MCP="${ENABLE_MCP:-0}"
if [ "$ENABLE_MCP" = "1" ]; then ENABLE_OIDC=1; ENABLE_OPA=1; ENABLE_USER_SERVICE=1; fi
# The two kill-switches, exposed so the E6/E7 drills can flip one and redeploy just the mcp service.
MCP_AGENT_GATE_ENABLED="${MCP_AGENT_GATE_ENABLED:-true}"
MCP_ROSTER_FILTER_ENABLED="${MCP_ROSTER_FILTER_ENABLED:-true}"
export MCP_AGENT_GATE_ENABLED MCP_ROSTER_FILTER_ENABLED

APISIX_ADMIN="${APISIX_ADMIN:-http://localhost:9180}"
API_KEY="${APISIX_API_KEY:-edd1c9f034335f136f87ad84b625c8f1}"

# pod K publishes on BASE_PORT+K (28081, 28082, …). Base 28080 (in the 2xxxx range, not the common
# 18080 range) so this rig never collides with another local service or a podman-machine on 18081/18082.
BASE_PORT=28080

# --- arg parsing ------------------------------------------------------------
CMD="${1:-help}"; shift || true
PODS=""; POD_INDEX=""; DOWN_ARGS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --pods) PODS="$2"; shift 2 ;;
    --pod)  POD_INDEX="$2"; shift 2 ;;
    -v)     DOWN_ARGS+=("-v"); shift ;;
    *)      shift ;;
  esac
done

save_pods() { mkdir -p "$(dirname "$STATE_FILE")"; echo "PODS=$1" > "$STATE_FILE"; }
resolve_pods() {
  if [ -n "$PODS" ]; then echo "$PODS"; return; fi
  if [ -f "$STATE_FILE" ]; then grep -E '^PODS=' "$STATE_FILE" | cut -d= -f2; return; fi
  echo "2"
}

# --- image build ------------------------------------------------------------
build_image() {
  echo "==> Building app image $IMAGE (Gradle bootJar inside the image)..."
  # Build context is the repo root so the multi-module Gradle build can see the libraries.
  docker build -t "$IMAGE" -f "$APP_DIR/Dockerfile" "$SCRIPT_DIR"
}

image_exists() { docker image inspect "$IMAGE" >/dev/null 2>&1; }

# --- multi-pod compose generation ------------------------------------------
# Pod K: container catalog-K, published host port BASE_PORT+K -> 8080.
# All pods talk to the Postgres container by its compose DNS name (opa-abac-postgres).
generate_compose() {
  local n="$1"
  mkdir -p "$(dirname "$GEN_COMPOSE")"
  {
    cat <<HEADER
# Auto-generated by deploy.sh — do not edit. Regenerated on every up/scale.
name: $PROJECT

services:
HEADER
    # When tracing is on, prepend the OTEL Java agent (baked into the image). Computed
    # per-pod inside the loop (each pod gets its own pod.name resource attribute).
    local java_opts="-Xms256m -Xmx512m -XX:+UseG1GC"
    [ "$ENABLE_TRACING" = "1" ] && java_opts="-javaagent:/opt/otel/agent.jar $java_opts"

    for i in $(seq 1 "$n"); do
      local host_port=$((BASE_PORT + i))
      # ABAC enforcement in the app. Only enabled when OIDC is on — without a forwarded token there is
      # no subject to authorize, so every request would (correctly) fail closed. The policy prefix is
      # empty: the per-type resolver posts to /v1/data/{catalog,category,product}.
      local abac_env=""
      if [ "$ENABLE_OIDC" = "1" ] && [ "$ENABLE_OPA" = "1" ]; then
        abac_env="      OPA_ABAC_ENABLED: \"true\"
      OPA_ABAC_BASE_URL: \"http://opa:8181\"
      OPA_ABAC_POLICY_PREFIX: \"\""
      else
        abac_env="      OPA_ABAC_ENABLED: \"false\""
      fi
      # Phase 4: resolve role definitions from the user-management service (the app-resolved path)
      # instead of the static demo supplier. The user-service is reachable in-network as 'usermgmt'.
      local role_source_env=""
      if [ "$ENABLE_RESILIENCE_STUB" = "1" ]; then
        # B3 resilience e2e: resolve through the fault-injecting stub instead of the real user-mgmt.
        role_source_env="      CATALOG_ROLE_SOURCE: \"http\"
      CATALOG_USER_SERVICE_BASE_URL: \"http://resolve-stub:8080\""
      elif [ "$ENABLE_USER_SERVICE" = "1" ]; then
        # The catalog's internal /internal/effective-role READ is pinned to one pod (usermgmt-1) — a
        # single in-network base URL, and both pods read the same Postgres so the answer is identical.
        # The concurrency-critical WRITES (public /api/v1/teams*, /api/v1/users* membership/role
        # mutations) go through the gateway usermgmt-pool, which round-robins BOTH pods onto the shared
        # DB — that is where cross-pod @Version/locked-write contention is exercised.
        role_source_env="      CATALOG_ROLE_SOURCE: \"http\"
      CATALOG_USER_SERVICE_BASE_URL: \"http://usermgmt:8080\"
      CATALOG_USER_SERVICE_SUPERVISED_BASE_URL: \"$CATALOG_USER_SERVICE_SUPERVISED_BASE_URL\""
      fi
      local otel_env=""
      if [ "$ENABLE_TRACING" = "1" ]; then
        otel_env="      OTEL_SERVICE_NAME: \"catalog-management-service\"
      OTEL_RESOURCE_ATTRIBUTES: \"pod.name=catalog-$i\"
      OTEL_EXPORTER_OTLP_ENDPOINT: \"http://opa-abac-jaeger:4318\"
      OTEL_EXPORTER_OTLP_PROTOCOL: \"http/protobuf\"
      OTEL_TRACES_SAMPLER: \"always_on\"
      OTEL_TRACES_EXPORTER: \"otlp\"
      OTEL_METRICS_EXPORTER: \"none\"
      OTEL_LOGS_EXPORTER: \"none\""
      fi
      cat <<POD
  catalog-$i:
    image: $IMAGE
    container_name: catalog-$i
    hostname: catalog-$i
    environment:
      SERVER_PORT: "8080"
      SPRING_DATASOURCE_URL: "jdbc:postgresql://opa-abac-postgres:5432/catalog"
      SPRING_DATASOURCE_USERNAME: "catalog"
      SPRING_DATASOURCE_PASSWORD: "catalog"
      JAVA_OPTS: "$java_opts"
$abac_env
$role_source_env
$otel_env
    ports:
      - "$host_port:8080"
    healthcheck:
      test: ["CMD-SHELL", "curl -f http://localhost:8080/actuator/health || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 12
      start_period: 40s

POD
    done
    # All three composes share project name "$PROJECT", so they share the auto-created
    # "${PROJECT}_default" network — pods reach opa-abac-postgres by DNS, no explicit network block.
  } > "$GEN_COMPOSE"
}

app_compose() { docker compose -p "$PROJECT" -f "$GEN_COMPOSE" "$@"; }
apisix_compose() { docker compose -p "$PROJECT" -f "$APISIX_COMPOSE" "$@"; }
jaeger_compose() { docker compose -p "$PROJECT" -f "$JAEGER_COMPOSE" "$@"; }
opa_compose() { docker compose -p "$PROJECT" -f "$OPA_COMPOSE" "$@"; }

# ADR 0022: override the root-read tag exemption on the RUNNING OPA. Only acts when
# ROOT_READ_TAG_EXEMPTION is explicitly set (0|1); otherwise the config.json file default rules.
# The PUT writes OPA's in-memory store — an OPA restart re-seeds from the file, reverting the
# override (deliberate: the committed default is the durable truth; the env is a demo/test toggle).
apply_root_read_exemption() {
  [ -n "$ROOT_READ_TAG_EXEMPTION" ] || return 0
  local value=false
  [ "$ROOT_READ_TAG_EXEMPTION" = "1" ] && value=true
  local i
  for i in $(seq 1 20); do
    if curl -sf -X PUT "http://localhost:28181/v1/data/config/root_read_tag_exemption" \
        -H 'Content-Type: application/json' -d "$value" >/dev/null 2>&1; then
      echo "    root-read tag exemption -> $value (in-memory override; an OPA restart reverts to the file default)"
      return 0
    fi
    sleep 0.5
  done
  echo "WARNING: could not apply the root-read tag exemption override (OPA not answering on :28181)" >&2
}
keycloak_compose() { docker compose -p "$PROJECT" -f "$KEYCLOAK_COMPOSE" "$@"; }
usermgmt_compose() { docker compose -p "$PROJECT" -f "$USERMGMT_COMPOSE" "$@"; }
mcp_compose() { docker compose -p "$PROJECT" -f "$MCP_COMPOSE" "$@"; }
resolve_stub_compose() { docker compose -p "$PROJECT" -f "$RESILIENCE_STUB_COMPOSE" "$@"; }
base_compose() { docker compose -p "$PROJECT" -f "$BASE_COMPOSE" "$@"; }

build_usermgmt_image() {
  echo "==> Building user-management image $USERMGMT_IMAGE (Gradle bootJar inside the image)..."
  docker build -t "$USERMGMT_IMAGE" -f "$USERMGMT_DIR/Dockerfile" "$SCRIPT_DIR"
}
usermgmt_image_exists() { docker image inspect "$USERMGMT_IMAGE" >/dev/null 2>&1; }

build_mcp_image() {
  echo "==> Building the MCP server image $MCP_IMAGE (Gradle bootJar inside the image)..."
  docker build -t "$MCP_IMAGE" -f "$MCP_DIR/Dockerfile" "$SCRIPT_DIR"
}
mcp_image_exists() { docker image inspect "$MCP_IMAGE" >/dev/null 2>&1; }

wait_mcp_healthy() {
  echo "==> Waiting for the MCP server to become healthy..."
  local deadline=$(( SECONDS + 240 ))
  while (( SECONDS < deadline )); do
    if curl -sf "http://localhost:28093/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
      echo "   MCP server healthy (http://localhost:28093)."; return 0
    fi
    sleep 3
  done
  echo "   WARN: the MCP server did not become healthy in time." >&2
  return 1
}

wait_usermgmt_healthy() {
  echo "==> Waiting for the user-management service pods to become healthy..."
  # Two pods (HA parity with catalog): 28090 (usermgmt) + 28092 (usermgmt-2). Both must report UP.
  local deadline=$(( SECONDS + 240 ))
  local ports=(28090 28092)
  while (( SECONDS < deadline )); do
    local up=0
    for p in "${ports[@]}"; do
      curl -sf "http://localhost:$p/actuator/health" 2>/dev/null | grep -q '"status":"UP"' && up=$(( up + 1 ))
    done
    if (( up == ${#ports[@]} )); then
      echo "   user-management service healthy (${up}/${#ports[@]} pods: ${ports[*]})."; return 0
    fi
    sleep 3
  done
  echo "   WARN: not all user-management pods became healthy in time (${up:-0}/${#ports[@]})." >&2
  return 1
}

# Wait for Keycloak's realm discovery doc to be served (import + boot can take 30-60s).
wait_keycloak() {
  echo "==> Waiting for Keycloak realm 'catalog-demo' to be ready..."
  local deadline=$(( SECONDS + 120 ))
  while (( SECONDS < deadline )); do
    if curl -sf -o /dev/null "http://localhost:28888/realms/catalog-demo/.well-known/openid-configuration" 2>/dev/null; then
      echo "   Keycloak realm ready."; return 0
    fi
    sleep 4
  done
  echo "   WARN: Keycloak realm not ready in time (route's openid-connect may 503 until it is)." >&2
  return 1
}

# --- APISIX upstream sync ---------------------------------------------------
# Republish catalog-pool to round-robin over the N running pods (host ports 28081..).
apisix_sync_upstream() {
  local n="$1"
  if ! curl -sf -o /dev/null -m 2 -H "X-API-KEY: $API_KEY" "$APISIX_ADMIN/apisix/admin/upstreams" 2>/dev/null; then
    echo "==> APISIX admin unreachable at $APISIX_ADMIN — skipping upstream sync"
    return 0
  fi
  local nodes="" i=1
  while [ "$i" -le "$n" ]; do
    local port=$((BASE_PORT + i))
    [ -n "$nodes" ] && nodes="$nodes,"
    nodes="$nodes\"host.docker.internal:$port\": 1"
    i=$((i + 1))
  done
  local payload
  payload=$(printf '{"type":"roundrobin","pass_host":"pass","scheme":"http","nodes":{%s}}' "$nodes")
  local code
  code=$(curl -s -o /dev/null -w "%{http_code}" -X PUT \
    -H "X-API-KEY: $API_KEY" -H "Content-Type: application/json" \
    -d "$payload" "$APISIX_ADMIN/apisix/admin/upstreams/catalog-pool")
  if [ "$code" = "200" ] || [ "$code" = "201" ]; then
    echo "==> APISIX catalog-pool -> round-robin over $n pod(s): $nodes"
  else
    echo "   WARN: upstream PUT returned $code (payload: $payload)" >&2
  fi
}

wait_pods_healthy() {
  local n="$1"
  echo "==> Waiting for $n app pod(s) to become healthy..."
  local deadline=$(( SECONDS + 180 ))
  while (( SECONDS < deadline )); do
    local up=0 i=1
    while [ "$i" -le "$n" ]; do
      if curl -sf "http://localhost:$((BASE_PORT + i))/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
        up=$((up + 1))
      fi
      i=$((i + 1))
    done
    if [ "$up" -eq "$n" ]; then echo "   all $n pod(s) healthy."; return 0; fi
    sleep 3
  done
  echo "   WARN: not all pods became healthy in time." >&2
  return 1
}

require_postgres() {
  if ! docker ps --format '{{.Names}}' | grep -q '^opa-abac-postgres$'; then
    echo "ERROR: Postgres not running. Start base infra first: ./profile.sh up" >&2
    exit 1
  fi
}

# --- commands ---------------------------------------------------------------
case "$CMD" in
  build)
    build_image
    # `up` only builds the MCP image when it is ABSENT (mcp_image_exists || build_mcp_image), so
    # without this an edit to example-mcp-server would silently run against a stale opa-abac-mcp:local
    # — the same "new code not reflected in pods" trap the catalog image has. Only rebuilt when the
    # image already exists or ENABLE_MCP is on, so the default rig stays untouched.
    if [ "$ENABLE_MCP" = "1" ] || mcp_image_exists; then
      build_mcp_image
    fi
    ;;

  up)
    n="$(resolve_pods)"; save_pods "$n"
    require_postgres
    image_exists || build_image
    if [ "$ENABLE_TRACING" = "1" ]; then
      echo "==> Starting Jaeger (traces)..."; jaeger_compose up -d
    fi
    if [ "$ENABLE_OPA" = "1" ]; then
      echo "==> Starting OPA (allow-all policy)..."; opa_compose up -d
      apply_root_read_exemption
    fi
    if [ "$ENABLE_OIDC" = "1" ]; then
      echo "==> Starting Keycloak (realm import)..."; keycloak_compose up -d
    fi
    if [ "$ENABLE_USER_SERVICE" = "1" ]; then
      usermgmt_image_exists || build_usermgmt_image
      echo "==> Starting the user-management service + its Postgres..."
      usermgmt_compose up -d
    fi
    if [ "$ENABLE_MCP" = "1" ]; then
      mcp_image_exists || build_mcp_image
      echo "==> Starting the MCP server (agent tool surface)..."
      mcp_compose up -d
    fi
    if [ "$ENABLE_RESILIENCE_STUB" = "1" ]; then
      echo "==> Starting the resilience fault-injecting resolve stub (STUB_MODE=${STUB_MODE:-transient}, STUB_FAILS=${STUB_FAILS:-1})..."
      STUB_MODE="${STUB_MODE:-transient}" STUB_FAILS="${STUB_FAILS:-1}" resolve_stub_compose up -d
    fi
    echo "==> Starting APISIX + etcd..."
    apisix_compose up -d
    generate_compose "$n"
    echo "==> Starting $n app pod(s)..."
    app_compose up -d
    [ "$ENABLE_USER_SERVICE" = "1" ] && wait_usermgmt_healthy || true
    [ "$ENABLE_MCP" = "1" ] && wait_mcp_healthy || true
    wait_pods_healthy "$n" || true
    # OIDC route needs Keycloak's discovery doc reachable before APISIX validates tokens.
    [ "$ENABLE_OIDC" = "1" ] && wait_keycloak || true
    # Seed route (idempotent) then sync upstream to the real pod set.
    ENABLE_OIDC="$ENABLE_OIDC" ENABLE_SPA="$ENABLE_SPA" ENABLE_TRACING="$ENABLE_TRACING" ENABLE_OPA="$ENABLE_OPA" \
      ENABLE_USER_SERVICE="$ENABLE_USER_SERVICE" ENABLE_MCP="$ENABLE_MCP" \
      APISIX_ADMIN="$APISIX_ADMIN" APISIX_API_KEY="$API_KEY" \
      bash "$SCRIPT_DIR/infra/apisix/init-routes.sh"
    apisix_sync_upstream "$n"
    echo ""
    echo "==> Up. Gateway: http://localhost:9085   (e.g. curl http://localhost:9085/actuator/health)"
    [ "$ENABLE_TRACING" = "1" ] && echo "    Jaeger UI: http://localhost:26686"
    [ "$ENABLE_OPA" = "1" ] && echo "    OPA:       http://localhost:28181  (allow-all gateway policy)"
    [ "$ENABLE_OIDC" = "1" ] && echo "    Keycloak:  http://localhost:28888  (admin/admin; realm catalog-demo, user demo/demo)"
    [ "$ENABLE_SPA" = "1" ] && echo "    SPA auth:  gateway in bearer-only mode (validates Authorization: Bearer; no redirect login) + CORS for http://localhost:3000  [public client: catalog-spa]"
    [ "$ENABLE_USER_SERVICE" = "1" ] && echo "    user-mgmt: 2 pods http://localhost:28090 + http://localhost:28092  (gateway usermgmt-pool round-robins both; resolve API at /internal/effective-role; catalog role-source=http)"
    [ "$ENABLE_MCP" = "1" ] && echo "    mcp:       agent tool surface via the gateway at http://localhost:9085/mcp  (pod http://localhost:28093; streamable transport; agent-gate=$MCP_AGENT_GATE_ENABLED roster-filter=$MCP_ROSTER_FILTER_ENABLED)"
    [ "$ENABLE_DIRECTORY" = "1" ] && echo "    directory: identity search active on the user-service (Keycloak admin via catalog-directory, view-users only)"
    [ "$ENABLE_RESILIENCE_STUB" = "1" ] && echo "    resolve-stub: http://localhost:28091  (B3 fault injector; catalog role-source=http -> resolve-stub:8080; mode=${STUB_MODE:-transient})"
    for i in $(seq 1 "$n"); do echo "    catalog-$i -> http://localhost:$((BASE_PORT + i))"; done
    ;;

  scale)
    n="$(resolve_pods)"; save_pods "$n"
    require_postgres
    image_exists || build_image
    generate_compose "$n"
    app_compose up -d --remove-orphans
    wait_pods_healthy "$n" || true
    apisix_sync_upstream "$n"
    ;;

  down)
    if [ -f "$GEN_COMPOSE" ]; then app_compose down "${DOWN_ARGS[@]+"${DOWN_ARGS[@]}"}" || true; fi
    apisix_compose down "${DOWN_ARGS[@]+"${DOWN_ARGS[@]}"}" || true
    opa_compose down "${DOWN_ARGS[@]+"${DOWN_ARGS[@]}"}" || true
    keycloak_compose down "${DOWN_ARGS[@]+"${DOWN_ARGS[@]}"}" || true
    usermgmt_compose down "${DOWN_ARGS[@]+"${DOWN_ARGS[@]}"}" || true
    mcp_compose down "${DOWN_ARGS[@]+"${DOWN_ARGS[@]}"}" || true
    resolve_stub_compose down "${DOWN_ARGS[@]+"${DOWN_ARGS[@]}"}" || true
    jaeger_compose down "${DOWN_ARGS[@]+"${DOWN_ARGS[@]}"}" || true
    # Base Postgres last: it's the final container on the project network, so tearing it
    # down here lets the auto-created "${PROJECT}_default" network be removed cleanly
    # instead of failing with "Resource is still in use" (which happens if ./profile.sh down
    # runs while these infra containers are still attached). This makes `./deploy.sh down`
    # a complete one-command teardown of the whole rig.
    base_compose down "${DOWN_ARGS[@]+"${DOWN_ARGS[@]}"}" || true
    echo "==> Full rig down: pool + APISIX + OPA + Keycloak + user-mgmt + Jaeger + base Postgres."
    ;;

  status)
    echo "=== containers ==="
    docker ps --filter "name=catalog-" --filter "name=opa-abac-" \
      --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
    echo "=== APISIX upstream catalog-pool ==="
    curl -s -H "X-API-KEY: $API_KEY" "$APISIX_ADMIN/apisix/admin/upstreams/catalog-pool" 2>/dev/null \
      | grep -o '"nodes":{[^}]*}' || echo "(unreachable)"
    ;;

  logs)
    n="$(resolve_pods)"
    if [ -n "$POD_INDEX" ]; then
      docker logs -f "catalog-$POD_INDEX"
    else
      app_compose logs -f
    fi
    ;;

  *)
    grep -E '^#( |$)' "$0" | sed -E 's/^# ?//'
    exit 1
    ;;
esac
